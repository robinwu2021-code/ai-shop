# TDD-C端商品收藏与送达判断

状态：已实现（2026-09-19）
档位：1（新表 · 新端点 · 既有端点加参数与字段 · i18n）
原型：https://claude.ai/artifact/UyG6Rj21TqqFZWxvyo7pN1（`prototypes/c-goods-group.html` g05 / g07 / g08）
依据：用户 2026-09-19「详情页要增加一个收藏功能」「按照原型执行」
创建日期：2026-09-19

## 0. 本轮需求依据

2026-10-15 用户反馈：商家售后／退款列表不应混入普通订单；我的收藏出现公司主体，需按实际门店展示、收藏及跳转。验收对应 §5。

## 1. 需求 → 落点

| AC | 说的是什么 | 落点 | 测试 |
|---|---|---|---|
| AC1 | 详情页标题旁「收藏」，点了变「已收藏」，再点取消；未登录先静默登录 | `POST /mp/favorite/goods/{goodsNo}`（切换）；`GoodsVO.favorited` | `GoodsFavoriteFlowTest.toggleOnOffOn` · c-app `goods-favorite` |
| AC2 | 取消后能**再次**收藏（不能撞唯一键） | 取消走**物理删除**（`@Delete`），不走全局逻辑删除 | `GoodsFavoriteFlowTest.toggleOnOffOn` |
| AC3 | 我的收藏 · 商品：按收藏时间倒序；下架 / 售罄的压淡保留 | `GET /mp/favorite/goods` → `PageData<GoodsVO>`（含下架的，用 `onSale` 判） | `GoodsFavoriteFlowTest.listNewestFirstKeepsOffShelf` |
| AC4 | 我的收藏 · 店铺：只列收藏的店（不混入归因店） | `GET /mp/favorite/store`；店铺详情头部加收藏星 → `POST /mp/favorite/store/{merchantNo}` | `GoodsFavoriteFlowTest.storeFavoriteRefavoriteAndList` |
| AC5 | 送不到时（g05）：底栏上方一行「当前收货地址不在销售区域」，按钮置灰 | `GET /mp/goods/{goodsNo}?communityNo=` → `GoodsVO.deliverable`；判据与首页商品池同一份（`prd_community_pool`） | `GoodsFavoriteFlowTest.deliverableFollowsCommunityPool` · c-app `goods-favorite` |
| AC6 | 判据用**收货地址推出来的社区**，不用顶栏的模糊定位（只准到区，会误拦） | 端上只传 `community.community.communityNo`；不传 `regionCode`；没有社区号时 `deliverable = null`（不判） | 同上（null 那一支） |

## 2. 契约

### 2026-10-15 门店收藏纠正（1 档，实施计划）

用户反馈收藏中出现公司主体名。收藏新增可空 `store_no`（V422），唯一键改为 `(user_no, store_no)`；保留 `entity_no` 供主体统计。新操作按门店号保存，旧主体号兼容解析默认门店；历史空门店记录仅映射该主体的默认门店，不扩散到所有分店。主体停用或无有效门店不输出主体卡。

三语新增 B 端 `afterSale.status.*`（9 个真实售后状态）；独立售后处理页明确展示该状态。

返回 `StoreBriefVO.storeNo`，名称和评分来自 `StoreDirectoryPort`；C 端收藏按钮提交门店号，收藏行按门店号跳转。覆盖历史名称转换、同主体多店独立收藏、取消后再收藏及跳转。商家售后页签同时修复异步请求覆盖，只显示售后关联订单与售后状态。


### 表（V343）

```
prd_goods_favorite
  user_no  VARCHAR(64)   买家
  goods_no VARCHAR(64)   商品
  + 通用列（tenant_no / created_* / updated_* / version / deleted）
  UNIQUE uk_prd_goods_favorite (user_no, goods_no)
  KEY    idx_prd_goods_favorite_user (user_no, created_at)
```

放在**商品域**（`prd_`），不放用户域：收藏列表要取商品的展示形状（`GoodsService.detailAll`），
放用户域就要为此开一个跨域 Port，而收藏本身就是「买家 × 商品」的一条边。

### 端点（新控制器 `MpFavoriteController`，资源段单数，与 /mp 约定一致）

| 方法 | 路径 | 登录 | 返回 |
|---|---|---|---|
| POST | `/mp/favorite/goods/{goodsNo}` | 要 | `{ favorited: boolean }` |
| GET | `/mp/favorite/goods?page&size` | 要 | `PageData<GoodsVO>`，收藏时间倒序 |
| GET | `/mp/favorite/store` | 要 | `List<StoreBriefVO>`，只含收藏 |
| POST | `/mp/favorite/store/{merchantNo}` | 要 | `{ favorited: boolean }` |

既有端点：`GET /mp/goods/{goodsNo}` 加可选 `communityNo`；`GoodsVO` 末尾加
`Boolean favorited`（未登录 = false）与 `Boolean deliverable`（没给社区号 = null）。

### 顺带修的缺陷

`usr_store_favorite` 同样有 `UNIQUE (user_no, entity_no)`，而取消收藏走的是全局逻辑删除（`deleted=1`，行还在）——
**同一家店取消后再收藏，插入撞唯一键**。一期没有入口所以没人撞到；本次要给店铺加收藏星，一并改成物理删除。

## 3. 模块

- 后端：`V343__goods_favorite.sql` · `PrdGoodsFavorite` · `ProductMappers.GoodsFavoriteMapper` ·
  `GoodsFavoriteService(+Impl)` · `GoodsService.deliverableTo` · `GoodsVO`（两字段 + `withViewer`）·
  `MpFavoriteController` · `MpCatalogController.goodsDetail` · `StoreFavoriteService(+Impl)`（物理删除 + `favorites()`）·
  `UserMappers`（物理删除）
- 端上：`endpoints / contract / http / mocks` · 商品详情（收藏、g05 提示条、传 communityNo）·
  新页 `pages/favorites/index`（商品 / 店铺两栏）· 「我的」入口 · 店铺详情头部收藏星 · 三语词条

## 4. 测试

后端 `GoodsFavoriteFlowTest`（真库 H2，走 MockMvc）；端上 `goods-favorite.test.ts`。
每条 AC 做一次消融：把物理删除改回 `deleteById`，AC2 必红；把 `deliverableTo` 写死 true，AC5 必红。

## 偏差说明

- **店铺收藏的入口没有新加**：门店主页（`pages/store`）本来就有收藏星，只是一直调旧接口
  `POST /mp/store/{merchantNo}/favorite` —— 那个接口回的是「收藏列表」，端上当布尔用，数组恒真，
  接真后端时点取消也提示「已收藏」。现在端上改调 `POST /mp/favorite/store/{merchantNo}`（回 `{favorited}`），
  **旧接口删掉**（端上已无调用，留着会被 app-backend-orphan 闸门点名）。§3 里「店铺详情头部加收藏星」作废。
- 「送不到时」的提示条不是固定在底栏上方的一条，而是沿用页面已有的「买不了的原因」那一块（贴在操作条上方、跟着页面走），
  外加一个「换地址」链接 —— 同一件事不另起一种样式。
- 收藏列表里「已售罄」不单独标：商品卡自己会说（`goods.soldOut`），只有「已下架」压淡并给「删除」。

## 设计 → 实现（git diff --stat 对账）

后端：`V343__goods_favorite.sql` · `schema-test.sql`（生成）· `PrdGoodsFavorite` · `ProductMappers` ·
`GoodsFavoriteService(+Impl)` · `GoodsService(+Impl).deliverableTo` · `GoodsVO` · `MerchantGoodsServiceImpl`（构造补两个 null）·
`MpFavoriteController` · `MpCatalogController` · `MpStoreController`（删旧收藏接口）· `StoreFavoriteService(+Impl)` · `UserMappers` ·
测试 `GoodsFavoriteFlowTest` · `MpEndpointAuthTest`。
端上：`types/product.ts` · `endpoints / contract / http` · `mocks/catalog / merchant` · `mock/db.ts` · `constants`（ROUTES.favorites）·
`icons.ts`（star）· `pages.json` · `pages/goods` · `pages/favorites`（新）· `pages/me` · `pages/store` · 三语词条 · 测试 `goods-favorite`。

## 实现 → 需求（测试真实输出）

- 后端 `GoodsFavoriteFlowTest`：5 跑 / 0 红；消融（商品取消改回软删、店铺取消改回软删、`deliverableTo` 写死 true）→ 3 红。
- 端上 `goods-favorite.test.ts`：4 跑 / 0 红；消融（`outOfScope` 写死 false）→ AC5 红。


## 5. 2026-10-15 修复验收

| AC | 验证 |
|---|---|
| 售后页签排除无售后单的普通订单，迟到响应不得覆盖当前页签 | `b-app/tests/orders-after-sale.test.ts`（两项）；旧实现出现 OTHER，修复后只剩 AS1 |
| 独立售后处理页只枚举售后单 | 同文件第二项，普通 PAID/FULFILLING 不增加行 |
| 收藏返回实际门店号及门店名 | `GoodsFavoriteFlowTest.favoritesUseStoreIdentityAndName`；旧实现 storeNo 为空（红），修复后通过 |
| 同主体两店独立收藏，历史主体收藏兼容读取及取消 | `GoodsFavoriteFlowTest.siblingStoresAreIndependentAndLegacyFavoritesResolve` |
| 收藏按门店号跳转，不按主体打开默认门店 | `c-app/tests/favorite-store-navigation.test.ts` |

集成源保留 68733ba93 的订单/售后/物流改动及主工作区的手机号登录改动。已验证 C 端 88 文件 / 577 项，B 端 35 文件 / 212 项（随后新增独立售后页测试，再单跑 2 项通过）；两端 vue-tsc 通过。后端登录、收件人、收藏、架构共 35 项通过。

## 6. 本轮实现范围

`StoreFavoriteServiceImpl`、`UsrStoreFavorite`、`UserMappers`、`StoreBriefVO`、`MpStoreController`、V422 及生成 H2 schema；C 端 store/favorites/mocks 与 MerchantBrief 可选 storeNo；B 端 orders/after-sale 与三语状态词条；三份回归测试及生成 API 文档。实体收藏人数按用户去重，避免同一人收藏同主体两家店造成重复计数。历史无门店号的数据保留原记录，仅在读取时解析默认门店，未清理用户或收藏数据。


上线验证：2026-10-15 后端 `68733ba93-store-favorite` 部署成功，内外网 health=UP；Flyway V422 成功，原 4 条收藏保留，新唯一键 `uk_user_store` 生效。小程序 `0.1.139 · 1015-1646` 已上传到 `wx3201250c28393850`（含商家分包），未提审或正式发布。真机交互待用户退出重进体验版验收。词条对账缺失键 0，孤儿键 57（原有欠账未增加）。
