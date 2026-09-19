# TDD-C端商品收藏与送达判断

状态：已实现（2026-09-19）
档位：1（新表 · 新端点 · 既有端点加参数与字段 · i18n）
原型：https://claude.ai/artifact/UyG6Rj21TqqFZWxvyo7pN1（`prototypes/c-goods-group.html` g05 / g07 / g08）
依据：用户 2026-09-19「详情页要增加一个收藏功能」「按照原型执行」
创建日期：2026-09-19

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
