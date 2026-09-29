# TDD-C端门店化与门店门户

状态：**一期大部分已实现**（2026-09-29；未完成项见文末「偏差说明」）
档位：1（端点返回结构、新库表、i18n；不新建域、不改结算与归因）
关联需求：用户 2026-09-29 原话「c 端要展示的是具体的门店，不是主体或者商户；门店列表优先展示查看和消费过的门店，其次是附近的门店，这个重点用来鼓励商家分享门店」「门店首页将来成为独立的门店门户，UI 要漂亮、整洁」「取消详情页的海报入口，海报的目的是分享」；
需求文档 [多门店与分享激励-需求](../requirements/多门店与分享激励-需求.md) §六 R4（本次补入）；上游 [ADR-011 经营主体与门店边界](ADR/ADR-011-经营主体与门店边界.md)
原型：[prototypes/c-store-portal.html](../../prototypes/c-store-portal.html)（s01–s12，[发布版](https://claude.ai/artifact/TxQikiY2JD8toqZa1xvEyf)）
同批修订：[TDD-C端商品详情页v3](TDD-C端商品详情页v3.md) §v4 修订（去掉已选 / 配送 / 保障，海报收进分享）
创建：2026-09-29

## §0 对账一 · 需求 → 设计

已拍板（2026-09-29）：①「平台推荐」移出店铺页；② 只逛过的店 30 天没再逛退出「我的店」，买过的一直留；③ 老链接保留到旧版小程序下线。

| AC | 需求 / 原型屏 | 落点 |
|---|---|---|
| AC1 | C 端一行 = 一家**门店**，名字是门店名；主体名只在资质页出现（s01、s06） | 所有 `/mp` 列表返回 `StoreCardVO`；门户 `StoreHomeVO` 以门店为根 |
| AC2 | 店铺页两段：「我的店」（逛过 ∪ 买过，按最近一次接触倒序）→「附近」（按距离，去掉上一段已有的）（s01、s02） | `GET /mp/store/mine` · `GET /mp/store/nearby`；`pages/merchants` 重写 |
| AC3 | 只逛过的 30 天未再逛退出「我的店」；买过的一直在 | `MyStoreServiceImpl` 合并规则；天数走配置 `shop.mp.my-store.view-keep-days` |
| AC4 | 「平台推荐」移出店铺页 | 店铺页不再调 `/mp/merchant/promoted` |
| AC5 | 「附近」按门店坐标算距离；无坐标的排最后 | `NearbyStoreServiceImpl`：服务范围可达集 → 距离升序 |
| AC6 | 「附近」只收门店 ACTIVE 且主体 ACTIVE；「我的店」里 READONLY 的压淡显示「暂停营业」 | 列表过滤；`StoreCardVO.status` |
| AC7 | READONLY 门店**下不了单**；直达链接显示暂停营业页，并给最近的同主体门店（s07） | 下单落店只落 ACTIVE 门店（§2.7）；买家指定的店暂停 → `STORE_PAUSED`；门户 `closed` + `sibling` |
| AC19 | 在哪家店的门户里买，就由哪家店履约（2026-09-29 实现时补） | 下单带 `storeChoices`（主体 → 门店）；落店优先级见 §2.7 |
| AC8 | 任何入口进门户都记一次「逛过」，含**首次来源**（分享 / 扫码 / 列表 / 搜索 / 商品页）与首次邀请人 | 新表 `usr_store_view`；`POST /mp/store/{no}/enter` 写入 |
| AC9 | 门户按 `storeNo` 取数；列表、扫码、分享三个入口进**同一个门户**（s03、s04） | `pages/store` 成为唯一门户；`GET /mp/store/{no}` |
| AC10 | 老链接继续可用：`/mp/store/{merchantNo}`、老分享、老店码解析到默认 ACTIVE 门店 | 编号前缀分派（§2.1） |
| AC11 | 门户结构：头图（顶到状态栏，店名与营业信息写在图上）→ 公告与领券一张白卡 → 标签页（商品 / 评价 / 店铺）；商品页签**不做左右分栏**（2026-09-29 用户改定，选 B 版）：搜索 → 老客的「我常买」横滑一栏 → 分类横排（往下滑时吸顶）→ 单列列表（s03–s07） | `pages/store/index.vue` + `biz-goods-card`（与首页、搜索同一个单列件，`in-store` 不写店名） |
| AC12 | 评价按门店（s05） | `/mp/review?storeNo=` |
| AC13 | 店铺页签有「经营主体与资质」入口；`pages/merchant` 收成资质页（s06） | `pages/merchant` 精简；门户 → 资质页 |
| AC14 | 分享一个入口，面板两条路：发给朋友 / 生成海报（s08） | `biz-share-act` 改为面板；门户与商品详情共用 |
| AC15 | 分享链接与海报码都带 `storeNo`；海报画门店名、用门店码（s09） | `biz-share-act` 参数；`biz-poster`；`GET /mp/store/{no}/acode` |
| AC16 | 收藏按门店（二期） | `usr_store_favorite.store_no` + 回填 |
| AC17 | B 端分享门店：选门店 → 看分享效果三数 → 分享（二期，s12） | `GET /biz/store/{storeNo}/share-stats` |
| AC18 | 门店坐标补齐：无坐标 / 坐标异常在 B 端门店设置与运营端门店治理提示 | 数据治理（§2.6） |

**孤立项**：无。所有设计条目都挂得上 AC。

## §1 现状与影响面

### 1.1 诊断（2026-09-29 线上与代码实查）

| 现象 | 根因 | 证据 |
|---|---|---|
| 四家店在小程序上都叫「虹选科技有限公司」 | `/mp` 这一层的主键全是 `merchantNo`（列表、门户、收藏、进店、分享），一个主体只能出一行 | `MpCatalogController` / `MpStoreController` / `MpFavoriteController` 的路径参数 |
| 名字与地址对不上 | `MerchantServiceImpl.frontsOf` 取「任意一家门店」当门面：`toMap(..., (a, b) -> a)` 不排序、不看默认、不看状态，可能取到 READONLY 的福田店 | `MerchantServiceImpl.java:182` |
| 「附近的」其实不按距离 | `search()` 查主体表，按「认证 → 销量」排 | `MerchantServiceImpl.java:68` |
| 「逛过」没有门店级数据 | `mkt_store_visit.store_no` 只有扫码那条路会写；线上共 1 条且匿名。列表 / 分享进门户只写主体级归因 | 线上查询 |
| 分享落不到具体门店 | `biz-share-act` 与 `onShareAppMessage` 只带 `merchantNo`；海报画主体名、码是主体店铺码 | `pages/store` `pages/merchant` `biz-poster.vue` |
| 同一家店两个页面 | 扫码进 `pages/store`（交易页），列表进 `pages/merchant`（商家详情），都按主体取数 | 两个 `.vue` 的调用 |
| READONLY 对买家无效 | 列表、门户、下单都不读 `mch_store.status` | 记忆「停用门店没人读」 |
| 两家门店无坐标、一家坐标在山西 | 录入时没选点 | 线上 `mch_store.lat_e6/lng_e6` |

线上数据：主体「虹选科技有限公司」下 4 家门店（虹选鲜果〔默认〕、虹选鲜果·福田店〔READONLY〕、虹选粮油、虹选粮油·深圳测试店）；子订单已带 `store_no`（24 + 2 单）。

### 1.2 影响面

- **不动**：结算 / 分账 / 积分 / 券的主体口径（ADR-011）；归因 `mkt_attribution` 仍按主体（它决定客流费率，按主体谈）；`mkt_store_visit` 仍是扫码漏斗日志。
- **改**：C 端店铺页、门户、资质页、分享组件、海报、搜索页的门店结果；`/mp` 七个端点；下单闸加门店状态校验。
- **兼容**：老 `merchantNo` 路径与老分享 / 老店码（AC10）；`/mp/merchant`、`/mp/merchant/visited`、`/mp/merchant/promoted` 标记废弃，旧版小程序下线后删。

## §2 方案

### 2.1 同一条路径，按编号前缀分派

`/mp` 的路径约定是**单数**（`api-path-naming` 守卫：24:1，防回潮），所以不能新开 `/mp/stores/{storeNo}`；而 `/mp/store/{merchantNo}` 正好占着同一个形状。做法：**路径不变，服务端按编号前缀分派**——

| 前缀 | 是什么 | 怎么处理 |
|---|---|---|
| `ST` | 门店号 | 直接用 |
| `M` | 主体号（老链接、老版本） | 解析到该主体的默认 ACTIVE 门店；没有就取任一 ACTIVE 门店；都没有 → 暂停营业页 |

编号前缀由 `BizKey` 生成，是稳定契约，不是会挪动的东西。落地时加一条守卫：两种前缀都能解析、未知前缀 404。

### 2.2 契约变更

**端点**（`/mp` 全部单数；`{no}` 按 §2.1 分派）

| 端点 | 变更 | 说明 |
|---|---|---|
| `GET /mp/store/mine` | **改返回**（现无页面调用） | 「我的店」`List<StoreCardVO>`；可带 `lat`/`lng` 算距离 |
| `GET /mp/store/nearby` | 新 | `PageData<StoreCardVO>`；`lat`/`lng`/`communityNo`/`keyword`/`page`/`size`；登录时去掉「我的店」已有的；匿名可调 |
| `GET /mp/store/{no}` | **改返回** | `StoreHomeVO` 以门店为根：门店名、状态、营业时间与是否营业中、评分、履约方式、距离、公告、是否关注、`closed`、`nearestSibling` |
| `POST /mp/store/{no}/enter` | 改 | 请求体加 `source`（`SHARE`/`SCAN`/`LIST`/`SEARCH`/`GOODS`）；写归因（主体）+ 写 `usr_store_view`（门店） |
| `GET /mp/store/{no}/goods` | 新 | 门户商品列表：本店在售（店级上架语义见 §2.7）；`categoryNo`（货架类目，取自门户的 `categories`）/`keyword`/`page`/`size`。**不给 `/mp/goods` 加 `storeNo`**：那是跨店目录，一条端点两种可见性口径迟早分岔；门户的读都挂在 `/mp/store/{no}/` 下，前缀分派只写一处 |
| `POST /mp/order` · `POST /mp/order/preview` | 加字段 | `storeChoices: [{merchantNo, storeNo}]`（§2.7）。不传 = 与改造前逐字相同 |
| `GET /mp/store/{no}/frequent` · `POST /mp/store/{no}/rebuy` | 改 | `{no}` 按前缀分派；**仍按主体聚合**（同品牌几家店买过的都算「常买」—— 商品定义本在主体级） |
| `GET /mp/store/{no}/acode` | 新 | 门店小程序码（海报用）；一店一码生成一次落库复用，码里不带邀请人（§7.3 已定） |
| `GET /mp/goods` | **不改** | 门户商品走上面的 `/mp/store/{no}/goods`（理由同那一行） |
| `GET /mp/review` | 加参数 | `storeNo`；可单独传（此前 `goodsNo`/`merchantNo` 至少一个） |
| `POST /mp/favorite/store/{no}` · `GET /mp/favorite/store` | 改（二期） | 按门店 |
| `GET /biz/store/{storeNo}/share-stats` | 新（二期） | 分享效果三数；按「新增 /biz 端点要登记七处」走 |
| `GET /mp/merchant` · `/mp/merchant/visited` · `/mp/merchant/promoted` | 废弃 | 旧版小程序下线后删；`/mp/merchant/{merchantNo}` 保留为资质页 |

**`StoreCardVO`**：`storeNo` `storeName` `entityNo` `logo` `status`（ACTIVE / READONLY）`openNow` `openHours` `address` `distanceM`（可空）`rating` `relation`（`orderCount` `lastOrderAt` `lastViewAt` `firstSource`，仅「我的店」）。

**库表**

```sql
-- 用户 × 门店，一人一店一行（覆盖写）。回答「这个人和这家店是什么关系」。
-- 不复用 mkt_store_visit：那张是扫码漏斗的追加日志（匿名也记），回答「这家店被扫了多少次」，
-- V290 的作者专门写了不混用的理由；这里是登录用户的关系表，两者语义不同。
CREATE TABLE IF NOT EXISTS usr_store_view
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL COMMENT '冗余，B 端按主体汇总分享效果用',
    first_source VARCHAR(16) NOT NULL COMMENT 'SHARE / SCAN / LIST / SEARCH / GOODS —— 这家店是怎么进入他的列表的',
    first_inviter_no VARCHAR(64) DEFAULT NULL,
    first_at BIGINT(20) NOT NULL,
    last_at BIGINT(20) NOT NULL,
    view_count INT(11) NOT NULL DEFAULT 1,
    ... BaseEntity 六列 ...,
    PRIMARY KEY (id),
    UNIQUE KEY uk_store_view_user_store (user_no, store_no),
    KEY idx_store_view_store_source (store_no, first_source)
) COMMENT='用户逛过的门店（我的店 · 分享效果）';
```

二期：`usr_store_favorite` 加 `store_no`，存量按主体的默认门店回填（不作废存量收藏）。迁移号实现时当场取最大号 +1。

**跨域端口**：`PurchaseHistoryPort` 加 `purchasedStores(userNo)`（按 `ord_sub_order.store_no` 聚合：单数、最近下单时间）；门店读取走 `spi/user` 现有的门店查询端口，缺什么补什么，不直接读 `mch_*` 表。

**i18n**（三语）：`shops.mine` `shops.nearby` `shops.relBought` `shops.relShared` `shops.relViewed` `shops.paused` `store.tabGoods` `store.tabReviews` `store.tabInfo` `store.frequent` `store.hot` `store.entityInfo` `store.pausedNotice` `store.goNearby` `share.toFriend` `share.poster` `share.sheetTitle` `store.favAct` `store.favOn`（头图浮层上的「收藏 / 已收藏」）；删 `shops.promoted` `shops.visited` `poster.act`。

**配置**：`shop.mp.my-store.view-keep-days`（默认 30）。

### 2.3 「我的店」与「附近」的算法

**我的店** = V ∪ P：

- V：`usr_store_view` 里该用户 `last_at ≥ 现在 − 30 天` 的门店；
- P：`purchasedStores(userNo)`，买过的**不设期限**；
- 排序：`max(last_at, last_order_at)` 倒序；并列时买过次数多的在前；
- 门店被停用（READONLY）：仍列，压淡、写「暂停营业」；主体被封 / 门店删除：不列。

**附近**：门店 ACTIVE 且主体 ACTIVE、服务范围覆盖当前位置（沿用 `applyReachable` 的三档口径）→ 去掉「我的店」已有 → 按球面距离升序（`lat_e6`/`lng_e6`）；无坐标的排最后；无位置时退回「覆盖当前社区」、按门店评分排。一期门店量小，在应用层算距离；门店上千后再加空间索引。

### 2.4 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新建 | `backend/shop-app/src/main/resources/db/migration/V<n>__usr_store_view.sql` | 上面的表 |
| 新建 | `user/entity/UsrStoreView.java` · `UserMappers.StoreViewMapper` | |
| 新建 | `user/service/StoreViewService.java` + `impl/` | 覆盖写：首次来源只在插入时定 |
| 新建 | `merchant/service/MyStoreService.java` + `impl/` | 我的店 / 附近的合并与排序（AC2–AC6） |
| 修改 | `merchant/service/impl/MerchantServiceImpl.java` | `frontsOf` 退役；`StoreCardVO` 组装 |
| 修改 | `spi/trade/PurchaseHistoryPort.java` + 实现 | `purchasedStores` |
| 修改 | `portal/mp/MpStoreController.java` | §2.1 分派；`mine` / `nearby` / `enter` / `acode` |
| 修改 | `product/service/impl/StoreServiceImpl.java` | `home` 以门店为根；`closed` + `nearestSibling` |
| 修改 | 商品 / 评价列表的 `storeNo` 过滤 | `/mp/goods` `/mp/review` |
| 修改 | 下单闸 | READONLY 门店拒单（AC7），新错误码 + 三语文案 |
| 重写 | `c-app/src/pages/merchants/index.vue` | 两段 |
| 重写 | `c-app/src/pages/store/index.vue` | 门户（s03–s07） |
| 新建 | `c-app/src/components/biz/biz-store-row.vue` · `biz-store-head.vue` · `biz-store-menu.vue` | 店铺行、店招、左分类右列表 |
| 精简 | `c-app/src/pages/merchant/index.vue` | 资质页 |
| 修改 | `c-app/src/components/biz/biz-share-act.vue` · `biz-poster.vue` | 分享面板（AC14）、门店码（AC15） |
| 修改 | `c-app/src/pages/search/index.vue` | 门店结果走 `/mp/store/nearby?keyword=` |
| 修改 | `packages/shared/src/types/*` · `c-app/src/api/endpoints.ts` · mock | 契约与替身 |
| 修改 | `c-app/src/pages.json` → 重跑 `gen-ui-catalog.py` | 页面标题（门户、资质页） |

### 2.5 分享与海报

- 门户与商品详情各只有**一颗「分享」**；点开面板：「发给朋友」（小程序原生转发；H5 复制链接）/「生成海报」（朋友圈、存相册）。
- 链接：`pages/store/index?no=ST…&from=SHARE&inviter=…`；商品：`pages/goods/index?goodsNo=…&storeNo=ST…`。
- 进入门户时 `enter(source=SHARE, inviterNo)` → 写 `usr_store_view`。**这就是给商家的回报**：别人点开他分享的门店，这家店就留在对方「我的店」里。
- 这一层**不做防刷**：回报只落在点开的那个人自己的列表里；商家点自己的链接，店只会出现在他自己的列表里。R3 的首页曝光（需求 §三）仍然需要防刷，与本期无关。
- 海报：门店名、门店码；从门户生成时用门头照与店名。

### 2.6 数据治理（AC18）

- 线上 4 家门店：2 家无坐标、虹选粮油坐标为 (35.03, 111.01)（山西），需核对。
- B 端门店设置页：无坐标时顶部一行提示「补全门店位置，附近的顾客才看得到你」，点进地图选点。
- 运营端门店治理列表加「坐标」一列，异常（无坐标 / 与地址所在城市不符）标黄。

### 2.7 下单落店（实现时补，AC7 / AC19）

**缺口**：设计阶段只改了「看」，没改「买」。下单落哪家店由 `OrderServiceImpl.storesOfEntities` 决定：
自提点所属店 → 默认店（服务得了买家社区时）→ 最近的服务店。**不看买家是从哪个门户进来的** ——
门户做成门店之后，在 B 店门户里下的单照样落到默认的 A 店：价格、库存、履约全按 A 店走，
而页面上一路写的是 B 店。门户若只换展示，这一期就是一次换皮。

**做法**：下单与预览带 `storeChoices`（主体号 → 门店号），端上在进门户时记下「这个主体我在逛哪家店」。
落店优先级：

| 序 | 条件 | 落到 |
|---|---|---|
| 1 | 自提点属于本主体 | 自提点所属店（人要去那儿取货，改不了） |
| 2 | `storeChoices` 给了本主体的店 | 那家店；**非 ACTIVE → 拒单 `STORE_PAUSED`**；不属于本主体 → 忽略（按 3、4 走） |
| 3 | 默认店 ACTIVE 且服务得了买家社区 | 默认店（与改造前相同） |
| 4 | 否则 | 服务该社区的 ACTIVE 店里最近的；一家都没有 → 默认店（若它也不是 ACTIVE → `STORE_PAUSED`） |

- 第 2 档**不看服务范围**：买家点进这家店的门户、在里面挑的货，送不送得到由后面的配送闸
  （`requireFulfillmentSupported` / 自送半径）判，与今天从默认店下单是同一套闸 —— 不在落店这一步另判一遍。
- 第 3、4 档只取 ACTIVE：此前 READONLY 的默认店照样收单（记忆「停用门店没人读」）。
- **自提点属于一家 READONLY 店**：第 1 档照落，再由状态闸拒 `STORE_PAUSED` —— 货在那家店，换店等于让人白跑。
- 新错误码 `STORE_PAUSED`（20008，三语）：「这家店暂停营业了，去看看同品牌的其他门店」。

**门户商品的在售口径**与 `PrdStoreGoods` 注释一致：某商品没有任何店级行 → 看 `prd_goods.on_sale`；
有了任意一行 → 只有本店那行 `on_sale=1` 才算在售。**价格仍是主体级**（`PrdStoreGoods` 注释：分店价单独一批做）。

## §3 分期

| 期 | 范围 | 验收 |
|---|---|---|
| **一期** | §2.1 分派；`usr_store_view`；我的店 / 附近；门户 s03–s07；资质页；分享面板与门店码；READONLY 拒单；坐标补录 | AC1–AC15、AC18 |
| **二期** | 收藏按门店；B 端分享效果；废弃端点下线 | AC16、AC17 |

## §4 风险

- **两次写**：扫码进门户会同时写 `mkt_store_visit`（漏斗）与 `usr_store_view`（关系）。两者回答不同的问题，不合并；B 端「分享效果」只读后者。
- **老版本小程序**：审核中的旧版仍按 `merchantNo` 调用，§2.1 保证它们照常工作。
- **闸门**：`api-path-naming`（单数）、`BackendI18nParityTest`（新错误码三语）、`env-consumed`（新配置项要有读取方）、`vue-tsc`（两端）、`gen-ui-catalog --check`、`entity-alignment`（新表与实体）、二期 `/biz` 端点七处登记。

## §5 对账三 · 实现 → 需求

| AC | 测试 | 结果 |
|---|---|---|
| AC1 | `MyStoreFlowTest` ★ 主体号进店落到具体门店：我的店里是门店名 · ★ 同主体两家店是两行 · `StorePortalFlowTest` ★ 门户以门店为根 · `store-portal.test` ★★★ 门头写门店名、页面不出现主体名 | ✅ |
| AC2 | `MyStoreFlowTest` ★ 附近去掉我的店、按距离升序 · 店铺页 H5 mock 两段 | ✅ |
| AC3 | `MyStoreFlowTest` ★ 只逛过的超过 30 天掉出，买过的一直在（取消的单不算成交） | ✅ |
| AC4 | 店铺页不再调 `/mp/merchant/promoted`（代码） | ✅ |
| AC5 | `MyStoreFlowTest` ★ 没坐标的排最后而不是以 0 米排第一 | ✅ |
| AC6 | `MyStoreFlowTest` ★ 停用的不列进附近、在我的店里状态为 READONLY；店铺行压淡 | ✅ |
| AC7 | `StoreOrderRoutingTest` ★ 指定的店暂停营业拒单 · ★ 默认店暂停落到营业店 · 默认店暂停且无别店拒单 · `StorePortalFlowTest` ★ 暂停营业给同主体营业店 · `store-portal.test` ★★ 整页不可加购 | ✅ |
| AC8 | `MyStoreFlowTest` ★ 首次来源只定一次 · 非分享不记分享人 · ★ 不带 source 按 LIST | ✅ |
| AC9 | `StorePortalFlowTest` ★ §2.7 门户只列本店在售 | ✅ |
| AC10 | `StorePortalFlowTest` ★ 主体号落默认门店、未知门店号 404 · `MyStoreFlowTest` 种子商家老链接落 ST-M0002 | ✅ |
| AC11 | `store-portal.test` ★★★ 买过的人先看到「我常买」一栏、没买过没有 · ★★★ 不做左右分栏：分类横排、商品单列、行上不写店名 · ★★★ 售罄照列、行上写售罄没有加号 · ★★★ 头图的底取卖得最好的那件真图、只有 emoji 时退回品牌色 | ✅ |
| AC12 | `StorePortalFlowTest` 评价按门店：只回这家店的，老评价不混进来 | ✅ |
| AC13 | 门户「店铺」页签最后一行「经营主体与资质」→ `pages/merchant` | ⚠️ 资质页未收窄，见偏差 |
| AC14 | `goods-detail-layout` ★★★ 海报收进分享面板 · ★★ 小程序原生转发在面板里、H5 复制链接 | ✅ |
| AC15 | 门户分享路径带 `no`、详情从门户来的带 `storeNo`；`StorePortalFlowTest` 店码按门店；海报用门店码（H5 mock 出图） | ✅ |
| AC19 | `StoreOrderRoutingTest` ★ 门户选店落到那家店 · 别家主体的门店号被忽略 · ★ 不传与改造前相同 · ★ 请求体 storeChoices 一路接到落店 | ✅ |
| AC16 / AC17 | 二期 | — |
| AC18 | 坐标治理 | ❌ 未做，见偏差 |

```
后端：MyStoreFlowTest 9 · StorePortalFlowTest 6 · StoreOrderRoutingTest 7 全绿；
     下单相关回归（*Order* / *Trade* / M*Flow / *Pickup* / *Store* / *Fulfillment* / *Cart* …）803 条全绿；
     M*Flow + Store* + Merchant* 混跑 580 条里 MyStoreFlowTest 9/9（全量的共享种子下也成立）
前端：c-app 60 个文件 / 415 条全绿；vue-tsc 0 错；设计守卫（字色 / 圆角 / 投影 / 网格 / 孤儿样式）全过
消融（每条都先红后还原）：
  距离判空、30 天保留期 → MyStoreFlowTest 两条红
  source 判空（Set.of().contains(null) 抛 NPE）→ 两条红 —— 这是 M6a 老用例先抓到的真缺陷
  店级在售过滤、M 前缀分支 → StorePortalFlowTest 两条红
  门户选店分支 → 3 条红；状态闸 + 默认店营业判断 → 4 条红
  左栏「我常买」挪到「热卖」之后 → store-portal 一条红
```

## 偏差说明

- **模块归属**：「我的店 / 附近」写在 `user` 域的 `MyStoreService`（不是 §2.4 写的 `merchant/service`）——
  它的主语是买家（逛过、买过），门店信息经新的窄端口 `StoreDirectoryPort` 取；`StoreViewService`
  并进了 `MyStoreService.recordView`，`NearbyStoreServiceImpl` 即 `MyStoreServiceImpl.nearby`。
- **门户商品端点**：`GET /mp/store/{no}/goods`，没给 `/mp/goods` 加参数（§2.2 已改，理由在那一行）。
- **我常买**按主体聚合，没按门店（同品牌几家店买过的都算，商品定义在主体级）。
- **§2.7 下单落店是实现时补的**：设计阶段漏了「买」，只改「看」的话门户是换皮。
- **门户底部**用现有悬浮购物车（`biz-cart-fab`），没做原型里的通栏购物车条。
- **商品页签改版（2026-09-29 用户：「菜单不要做成左右方式」）**：左分类右列表 → 搜索 · 我常买横滑 · 分类 chip · 双列网格。
  左右分栏把一小半宽度给了分类名，商品图小、一屏看得少；「我常买」从左栏的一格变成列表前的一栏，老客仍然第一眼看到。
  三个页签改用分段控件（`sh-seg`），与下面的分类 chip 分开两种样子 —— 两排 chip 分不出哪排是页、哪排是筛选。
  原型 s03 / s04 / s07 同步改了（样式在 `proto.css` 的 `sp-shelf` 一组）。
- **再改（同日，用户在 A 双列 / B 单列 / C 分段三版里选了 B，并要求「优化头部，加背景图，高端一点」）**：
  - 商品：双列网格 → 单列（`biz-goods-card`，加 `in-store`：落款行不写店名，已售照留）；分类吸在顶部标题条下面。
    商品这一块的 `sh-block` 放开了 `overflow`：它自带的 `overflow: hidden` 会让 sticky 静默失效。
  - 头部：页面改沉浸式（`pages.json` 全平台 `navigationStyle: custom`，与商品详情同一套：`ports/capsule` 对齐胶囊，
    滑过头图变白底标题条）。头图的底取**本店销量最高且有真图（http/https）的商品主图**，放大虚化再压一层品牌色；
    没有就是品牌色渐变。收藏与分享挪到浮层（`biz-share-act` 加 `image-box`：半透明圆钮）；公告与券收进压在头图下沿的白卡
    （`biz-coupon-strip` 加 `bare` 与 `count`，一张券都没有且没公告时不画这张卡）。
  - **店招图（店主自己传的门头照）没做**：门店没有这个字段（审核表里留了 `BANNER` 类型，从没接通）。
    要加库表列、B 端上传与机审人审，是下一份 TDD；有了之后头图优先用它，虚化商品图退为兜底。
- **未完成**：
  1. AC13 资质页：`/mp/merchant/{no}` 没有营业执照等资质数据。「亮照」展示什么（执照图 / 信息摘要 / 打码规则）
     是合规决定，**待产品确认**后补后端字段与页面。
  2. 商品详情的店铺卡写门店名 / 距离：要后端先回答「这件货由哪家店卖给这位买家」（同 TDD-C端商品详情页v3 第二步）。
  3. AC18 坐标治理：线上两家门店无坐标、虹选粮油坐标在山西 —— **需要真实坐标**，B 端提示与运营端标黄未做。
  4. 待部署：后端 V361 与 c-app 新页面都还没上线；上线后旧版小程序继续按主体号调用（§2.1 保证可用）。
