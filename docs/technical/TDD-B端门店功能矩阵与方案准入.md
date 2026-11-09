# TDD · B 端门店功能矩阵与方案准入

状态：草稿 · 档位：1（新增 2 个端点；不新增表、权限码、i18n 词条与配置项）
关联需求：[注册开店与行业 Plan 商城-API 清单](../api/门店行业Plan商城-API清单.md) §2–§3、[三端角色权限功能对齐清单](../requirements/三端角色权限功能对齐清单.md)、商户 App 交接文档模块 3/7/11–19
创建：2026-11-09 · 最后更新：2026-11-09

## 背景

门店建好（或收款开通）后，每家店都应**默认能收款**；餐饮、零售、服务业各自的作业功能则要门店选了对应 Plan 才开放。
现在端上要自己拼三处数据才能决定一个入口显示什么：`/biz/context` 的权限码、`/biz/stores/{storeNo}/context` 的权益、
`/biz/payment/collect-status` 的收款开通状态。三处各推一遍，迟早出现「看得见但点了报错」或「买了却没入口」。

本设计给出一张**功能矩阵**（功能 × 方案档 × 角色），并提供两个只读接口，由服务端一次算好：

1. `GET /biz/business-types/{typeCode}/feature-matrix`：某个经营类型的三档方案分别包含哪些功能、默认角色能用到哪一级。用于开店选方案、方案比较页。
2. `GET /biz/stores/{storeNo}/features`：**当前用户在当前门店**每个功能的状态与下一步。用于工作台入口裁剪、锁定态与引导。

## §0 对账一 · 需求 → 设计

| AC | 需求（Given / When / Then） | 落点 |
|---|---|---|
| AC1 | Given 经营类型已发布三档方案，When 读 feature-matrix，Then 返回每个功能在 FREE/PRO/CHAIN 是否包含、最低档 `minTier`、6 个预置角色的 `NONE/USE/MANAGE`；功能所需权益码不在该类型任何一档方案里的，不返回 | `StoreFeatureMatrix#forBusinessType` · `BizStoreFeatureController#matrix` |
| AC2 | Given 新店只有 FREE 且收款已开通，When 店主读 features，Then 基础功能（收款等）`AVAILABLE`；行业功能 `NEED_PLAN`，带 `requiredTier`，`nextAction=VIEW_SUBSCRIPTION` | `StoreFeatureMatrix#forStore` |
| AC3 | Given 同上但读的人没有采购权（店长、店员），Then 行业功能 `NEED_PLAN` + `nextAction=CONTACT_OWNER` | 同上（沿用 `AccessDecision.nextActionFor(status, canProcure)`） |
| AC4 | Given 门店收款未开通，Then 依赖收款的功能 `NEED_PAY_ACTIVATION`，有 `biz:finance` 的人 `nextAction=APPLY_PAYMENT`，否则 `CONTACT_OWNER`；收款状态读取失败时为 `CHECK_UNAVAILABLE` + `RETRY_CHECK`，不当成未开通 | 同上 |
| AC5 | Given 方案已含该功能，但当前用户角色没有它的任一使用权限码，Then `NO_PERMISSION` + `CONTACT_OWNER`；`canManage` 按管理权限码单独判；自定义角色以 `BizContext#effectivePerms` 为准 | 同上 |
| AC6 | Given 权益已过期 / 未开始 / 被撤销，Then 分别为 `PLAN_EXPIRED` / `NOT_STARTED` / `REVOKED`，下一步与端准入一致，并带 `effectiveUntil` | 同上 |
| AC7 | Given 旧模型门店（`commerceModel=LEGACY`），Then 不做方案拦截：只按角色与收款开通判，`currentTier=null` | 同上 |
| AC8 | 两个端点都在路由策略里登记（账号通道 / 门店引导通道），权限表达式登记在 `BizEndpointPermTest` | `CommerceRoutePolicy#match` · `BizEndpointPermTest` |

**孤立项**：无。功能名称不在服务端本地化（端上按 `featureCode` 翻译），因此不新增 i18n 词条。

## §1 现状与影响面

- **方案与权益已有**（commerce 模块）：经营类型 → 三档方案（`PlanTier` FREE/PRO/CHAIN，每档一组 `entitlementCodes`）→ 门店权益（`FoundationQueryFacade#store` 的 `Entitlements.items[]`，含 `commercialStatus`）。运营在「经营类型、业务端与 Plan」里发布，FREE 必含 `END_BIZ_APP`。
- **角色已有**：`BizPerms` 6 个预置角色（OWNER 为 `*`），自定义角色经 `BizContext#effectivePerms` 生效。
- **收款开通已有**：`BizPaymentAppService#collectStatus(storeNo)`（本地或远程 pay-svc）。
- **可复用**：`AccessDecision.nextActionFor(status, canProcure)`（下一步动作同一套词）；`ShopCommerceActorAdapter.current()`；`HostStoreContext` 的取数方式。
- **会被改到**：`CommerceRoutePolicy#match`（两条登记）；`BizEndpointPermTest`（两条登记）。
- **明确不受影响**：现有端点的入参、出参与鉴权全不变；**本次只给端上展示用，不在服务端新增按功能拦截**（服务端拦截仍是各接口自己的权限码 + 业务端准入，见 §4 后续）。

## §2 方案

### 功能矩阵（建议发布配置）

功能的**权益码**写在代码里（`StoreFeature`），某个经营类型的**哪一档包含哪个权益码**由运营发布的方案决定 —— 下面「档位」列是建议发布值，接口返回的是实际发布数据。

权益码约定（运营在「业务端」目录登记，`scope=STORE`，`allowedBusinessTypes` 填适用的经营类型）：

| 权益码 | 含义 | 适用经营类型（建议） | 建议档位 |
|---|---|---|---|
| `END_BIZ_APP`（已有） | 商户 App 基础：收款、门店、员工、列印、评价、方案 | 全部 | FREE 起（必含） |
| `END_APP_POS`（已有） | 感应收款 / POS | 全部 | PRO 起 |
| `END_RETAIL` | 零售作业：商品、订单履约、进销存、团购 | 零售 | PRO 起 |
| `END_FNB` | 餐饮作业：点餐单、菜单、桌台、出品看板、餐饮报表 | 餐饮 | PRO 起 |
| `END_SERVICE` | 服务业作业：预约、服务项目、排班技师、服务报表 | 服务业 | PRO 起 |
| `END_MARKETING` | 会员与营销 | 全部 | PRO 起 |
| `END_CHAIN` | 连锁：跨店管理、考勤工资 | 全部 | CHAIN |

功能 × 档位 × 角色（`U`=能用，`M`=能管理，`-`=默认没有；OWNER 恒为 M；「收款前置」=要先开通收款）：

| featureCode | 功能 | 分组 | 权益码 | 收款前置 | 使用权限（任一） | 管理权限（任一） | FREE | PRO | CHAIN | 店长 | 店员 | 理货 | 配送 | 客服 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `COLLECT` | 收款记录、查找、详情 | BASE | END_BIZ_APP | ✓ | `mch:pay:order:view` | `mch:pay:refund` | ✓ | ✓ | ✓ | U | U | - | - | - |
| `COLLECT_QR` | 本店收款码 | BASE | END_BIZ_APP | ✓ | `mch:pay:qr` | `mch:pay:qr` | ✓ | ✓ | ✓ | - | - | - | - | - |
| `CASHIER` | 收银台、付款链接 | BASE | END_BIZ_APP | ✓ | `mch:pay:collect` | `mch:pay:collect` | ✓ | ✓ | ✓ | M | - | - | - | - |
| `PAY_STATEMENT` | 收款汇总、资金 | BASE | END_BIZ_APP | ✓ | `mch:pay:statement` | — | ✓ | ✓ | ✓ | - | - | - | - | - |
| `PAY_ACTIVATION` | 开通收款 | BASE | END_BIZ_APP |  | `biz:finance` | `biz:finance` | ✓ | ✓ | ✓ | - | - | - | - | - |
| `STORE_PROFILE` | 门店资料 | BASE | END_BIZ_APP |  | `biz:store` | `biz:store:admin` | ✓ | ✓ | ✓ | U | - | - | - | - |
| `STAFF` | 员工与角色 | BASE | END_BIZ_APP |  | `biz:store:admin` | `biz:store:admin` | ✓ | ✓ | ✓ | - | - | - | - | - |
| `SUBSCRIPTION` | 方案、商城采购 | BASE | END_BIZ_APP |  | （门店成员） | `biz:store:admin` | ✓ | ✓ | ✓ | U | U | U | U | U |
| `PRINT` | 列印 | BASE | END_BIZ_APP |  | `biz:print:read` | `biz:print:config` | ✓ | ✓ | ✓ | M | U | - | - | - |
| `REVIEW` | 评价 | BASE | END_BIZ_APP |  | `biz:review` | `biz:review` | ✓ | ✓ | ✓ | M | - | - | - | M |
| `SOFTPOS` | 感应收款 | POS | END_APP_POS | ✓ | `biz:receive` | — | - | ✓ | ✓ | U | U | U | - | - |
| `GOODS` | 商品 | RETAIL | END_RETAIL |  | `biz:goods` `biz:stock` | `biz:goods` | - | ✓ | ✓ | M | U | U | - | - |
| `ORDERS` | 订单与履约 | RETAIL | END_RETAIL |  | `biz:order:view` `biz:receive` `biz:verify` `biz:ship` | `biz:aftersale` | - | ✓ | ✓ | M | U | U | U | M |
| `INVENTORY` | 进销存 | RETAIL | END_RETAIL |  | `biz:stock` | `biz:goods` | - | ✓ | ✓ | M | U | U | - | - |
| `GROUP_BUY` | 团购 | RETAIL | END_RETAIL |  | `biz:campaign` | `biz:campaign` | - | ✓ | ✓ | M | - | - | - | - |
| `FNB_ORDERING` | 点餐单（堂食/外带/外送） | FNB | END_FNB |  | `biz:order:view` | `biz:aftersale` | - | ✓ | ✓ | M | U | - | U | M |
| `FNB_MENU` | 菜单、沽清、剩余份数 | FNB | END_FNB |  | `biz:order:view` `biz:goods` | `biz:goods` | - | ✓ | ✓ | M | U | - | U | U |
| `FNB_TABLES` | 桌台与桌码 | FNB | END_FNB |  | `biz:store` | `biz:store` | - | ✓ | ✓ | M | - | - | - | - |
| `FNB_KITCHEN` | 出品看板 | FNB | END_FNB |  | `biz:receive` | — | - | ✓ | ✓ | U | U | U | - | - |
| `FNB_REPORT` | 餐饮报表 | FNB | END_FNB |  | `biz:customer` | — | - | ✓ | ✓ | U | - | - | - | - |
| `SVC_BOOKING` | 预约、签到、改约、结账 | SERVICE | END_SERVICE |  | `biz:booking:view` | `biz:booking:manage` | - | ✓ | ✓ | - | - | - | - | - |
| `SVC_CATALOG` | 服务项目 | SERVICE | END_SERVICE |  | `biz:goods` | `biz:goods` | - | ✓ | ✓ | M | - | - | - | - |
| `SVC_SCHEDULE` | 技师、资源、排班、停约 | SERVICE | END_SERVICE |  | `biz:store` | `biz:store` | - | ✓ | ✓ | M | - | - | - | - |
| `SVC_REPORT` | 服务业报表 | SERVICE | END_SERVICE |  | `biz:customer` | — | - | ✓ | ✓ | U | - | - | - | - |
| `MEMBERS` | 会员 | MARKETING | END_MARKETING |  | `biz:customer` | `biz:customer` | - | ✓ | ✓ | M | - | - | - | - |
| `CAMPAIGN` | 营销活动 | MARKETING | END_MARKETING |  | `biz:campaign` | `biz:campaign` | - | ✓ | ✓ | M | - | - | - | - |
| `CHAIN_OPS` | 连锁跨店管理 | CHAIN | END_CHAIN |  | `biz:store:admin` | `biz:store:admin` | - | - | ✓ | - | - | - | - | - |
| `HR` | 考勤与工资 | CHAIN | END_CHAIN |  | `biz:store:admin` | `biz:store:admin` | - | - | ✓ | - | - | - | - | - |

角色列是从 `BizPerms` 预置角色**算出来**的（接口也是现算），不是另一份手写表。值得产品留意的几处：

- `SVC_BOOKING`：`biz:booking:view/manage` 目前没有预置角色授予（`BizPerms` 注释「no seeded role grant」），只有店主能用；店长、店员要店主在自定义角色里勾。是否给店长/店员默认授予，见 §4 待决 1。
- `COLLECT_QR`、`PAY_STATEMENT`：沿用「店长也碰不了钱」的既有规则，默认只有店主。
- 餐饮、服务业的专用接口（`/biz/food/*`、`/biz/beauty/*`）还没冻结，上面的权限码先借用现有码；冻结时若新增专用码，只改 `StoreFeature` 一处。

### 状态与下一步

`/features` 每个功能按下面顺序判，**第一条不满足的就是它的状态**：

| 顺序 | 判什么 | 不满足时 `status` | `nextAction` |
|---|---|---|---|
| 1 | 该经营类型的方案里有没有这个权益码 | （不返回该功能） | — |
| 2 | 门店该权益码的商业状态（旧模型门店跳过） | `NEED_PLAN` / `PLAN_EXPIRED` / `NOT_STARTED` / `REVOKED` | 有采购权 `VIEW_SUBSCRIPTION`，否则 `CONTACT_OWNER`；未开始 `WAIT_FOR_START`；撤销 `CONTACT_ADMIN` |
| 3 | 当前用户有没有任一使用权限码 | `NO_PERMISSION` | `CONTACT_OWNER` |
| 4 | 需要收款前置的，门店收款是否已开通 | `NEED_PAY_ACTIVATION`（读失败为 `CHECK_UNAVAILABLE`） | 有 `biz:finance` 为 `APPLY_PAYMENT`，否则 `CONTACT_OWNER`；读失败 `RETRY_CHECK` |
| — | 全部满足 | `AVAILABLE` | `ENTER` |

先判商业、再判角色：店员看到「需要升级方案」比看到「没有权限」更接近事实，也与端准入（`EndAccessFacade`）的先后一致。

### 契约变更

- 端点：
  - `GET /biz/business-types/{typeCode}/feature-matrix` —— `@perm.merchantAccount()`，账号通道（与 `/plans` 同）。
  - `GET /biz/stores/{storeNo}/features` —— `@perm.memberOfSelectedStore(#storeNo)`，门店引导通道（与 `/context` 同）。
- 库表 / 迁移：无。
- 权限码：无新增。
- i18n：无（`featureCode`、`status`、`nextAction` 都是机器码，端上翻译）。
- 配置项：无。权益码 `END_RETAIL` / `END_FNB` / `END_SERVICE` / `END_MARKETING` / `END_CHAIN` 是**目录数据约定**，由运营登记与发布；没登记的码，对应功能不出现在两个接口里。

### 关键接口

```
GET /biz/business-types/{typeCode}/feature-matrix
Accept-Language: zh-Hant
→ {
  "businessTypeCode": "FNB", "catalogVersion": 12, "status": "PUBLISHED",
  "tiers":  [{"tier": "FREE", "planNo": "…", "displayName": "免費版", "purchaseAvailable": false}, …],
  "roles":  ["OWNER", "MANAGER", "CLERK", "PICKER", "COURIER", "CS"],
  "features": [{
    "featureCode": "FNB_ORDERING", "group": "FNB", "entitlementCode": "END_FNB",
    "requiresPayActivation": false, "minTier": "PRO",
    "tiers": {"FREE": false, "PRO": true, "CHAIN": true},
    "usePerms": ["biz:order:view"], "managePerms": ["biz:aftersale"],
    "roles": {"OWNER": "MANAGE", "MANAGER": "MANAGE", "CLERK": "USE", "PICKER": "NONE", "COURIER": "USE", "CS": "MANAGE"}
  }, …]
}

GET /biz/stores/{storeNo}/features
→ {
  "storeNo": "S…", "businessTypeCode": "FNB", "commerceModel": "STORE_V1",
  "currentTier": "FREE", "entitlementVersion": 7, "serverNow": 1762650000000, "nextBoundaryAt": null,
  "payActivation": "ACTIVE",            // ACTIVE | NOT_ACTIVE | UNKNOWN
  "features": [
    {"featureCode": "COLLECT", "group": "BASE", "entitlementCode": "END_BIZ_APP",
     "status": "AVAILABLE", "nextAction": "ENTER", "requiredTier": "FREE",
     "effectiveUntil": null, "canUse": true, "canManage": true},
    {"featureCode": "FNB_ORDERING", "group": "FNB", "entitlementCode": "END_FNB",
     "status": "NEED_PLAN", "nextAction": "VIEW_SUBSCRIPTION", "requiredTier": "PRO",
     "effectiveUntil": null, "canUse": false, "canManage": false}
  ]
}
```

- `features` 顺序固定（按 `StoreFeature` 声明顺序），端上可直接渲染。
- `canUse` = `status == AVAILABLE`；`canManage` = `canUse` 且有任一管理权限码（无管理码的功能恒为 false）。
- `requiredTier`：该类型已发布方案里包含此权益码的最低档；旧模型门店为 null。
- `effectiveUntil`：该权益码当前连续有效期的结束（毫秒，null = 长期或未生效）。
- 端上应在切店、购买/续费完成、`serverNow ≥ nextBoundaryAt` 时重读。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/feature/StoreFeature.java` | 功能注册表：featureCode、分组、权益码、收款前置、使用/管理权限码 |
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/feature/StoreFeatureMatrix.java` | 纯函数：由方案目录 + 预置角色算矩阵；由门店上下文 + 有效权限 + 收款状态算门店功能态 |
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizStoreFeatureController.java` | 两个端点，取数后交给 `StoreFeatureMatrix` |
| 修改 | `backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java` | 账号通道加 `feature-matrix`；门店引导读加 `features` |
| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java` | 登记两个端点的权限表达式 |
| 新增 | `backend/shop-app/src/test/java/ai/neargo/shop/portal/biz/feature/StoreFeatureMatrixTest.java` | AC1–AC7 |
| 新增 | `backend/commerce/commerce-spring-boot-starter/src/test/java/ai/neargo/commerce/starter/security/StoreFeatureRoutePolicyTest.java` | AC8 |

### 实施计划

1. 先写 `StoreFeatureMatrixTest`（红）→ `StoreFeature` + `StoreFeatureMatrix`（绿）。
2. 先写 `StoreFeatureRoutePolicyTest`（红）→ 改 `CommerceRoutePolicy`（绿）。
3. `BizStoreFeatureController` + `BizEndpointPermTest` 登记；跑 `ArchitectureTest`、`BizEndpointPermTest`。
4. 消融：注掉「收款前置」与「商业状态」两处判断，对应测试必须变红。

## §4 待决

1. `biz:booking:view/manage` 是否给店长、店员默认授予（不授予则服务业预约默认只有店主能用）。
2. 零售的「商品、订单」是否真要收进 PRO：现有零售门店已在免费使用。本设计对**旧模型门店不拦**，新模型门店按发布数据走；若产品希望 FREE 也能卖货，运营把 `END_RETAIL` 放进 FREE 档即可，代码不用改。
3. 服务端按功能拦截（把 `/biz/food/*` 等登记为 `END_PROTECTED` 并绑权益码）留到各行业接口冻结时一起做；本次接口只做展示判定。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `StoreFeatureMatrixTest#matrixForBusinessType` | ✅ | — |
| AC2 | `StoreFeatureMatrixTest#freeStoreOwner` | ✅ | 注掉商业状态判断 → 红 ✅ |
| AC3 | `StoreFeatureMatrixTest#freeStoreWithoutProcurement` | ✅ | 注掉商业状态判断 → 红 ✅ |
| AC4 | `StoreFeatureMatrixTest#payActivation` | ✅ | 注掉收款前置判断 → 红 ✅ |
| AC5 | `StoreFeatureMatrixTest#rolePermissions` | ✅ | — |
| AC6 | `StoreFeatureMatrixTest#entitlementLifecycle` | ✅ | 注掉商业状态判断 → 红 ✅ |
| AC7 | `StoreFeatureMatrixTest#legacyStore` | ✅ | — |
| AC8 | `StoreFeatureRoutePolicyTest`（2 个）· `BizEndpointPermTest` | ✅ | 改路由策略前先跑：2 个都红（`expected <ACCOUNT>/<BOOTSTRAP> but was <DENY>`）✅ |

```
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.portal.biz.feature.StoreFeatureMatrixTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.commerce.starter.security.StoreFeatureRoutePolicyTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.commerce.starter.security.MallRecoveryRoutePolicyTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.commerce.starter.security.EndContextBindingTest
Tests run: 12, Failures: 1 -- in ai.neargo.shop.arch.BizEndpointPermTest   ← 存量，见下
```

存量红（基线 `origin/codex/ai-shop-codex` fc070b2d4 就有，失败清单里没有本次的端点或类）：
`BizEndpointPermTest#everyBizEndpointHasADecision`（15 个 `/biz/inventory/food-*`、`/biz/personnel`、`/biz/service-orders/*` 等未登记）；
`ArchitectureTest` 5 条（Mapper、Port 包位、全限定名、定时任务锁、Service 接口化），均指向其他模块。

控制器 `BizStoreFeatureController` 只取数并转交，没有单独的 MockMvc 测试；它的判权由 `BizEndpointPermTest` 登记核对。

## §6 对账二 · 设计 → 实现

尚未提交，文件清单取自 `git status`：

```
 M backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java
 M backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java
 M docs/api/backend-ui-delivery-onboarding.json
 M docs/api/openapi-b-onboarding.json
 M docs/technical/README.md                                   （生成）
 M docs/technical/reference/B端功能点-权限码-页面.md            （生成）
 M docs/technical/reference/glossary.json                     （生成）
 M docs/technical/reference/中英文对照-实体与字典.md            （生成）
 M docs/technical/reference/三端权限矩阵-按业务域.md            （生成）
 M docs/technical/reference/后端分层清单.md                     （生成）
 M docs/technical/reference/静态常量清单.md                     （生成）
?? backend/commerce/commerce-spring-boot-starter/src/test/java/ai/neargo/commerce/starter/security/StoreFeatureRoutePolicyTest.java
?? backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizStoreFeatureController.java
?? backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/feature/StoreFeature.java
?? backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/feature/StoreFeatureMatrix.java
?? backend/shop-app/src/test/java/ai/neargo/shop/portal/biz/feature/StoreFeatureMatrixTest.java
?? docs/technical/TDD-B端门店功能矩阵与方案准入.md
```

| 差异 | 说明 |
|---|---|
| TDD 里没有、实际改了的文件 | `openapi-b-onboarding.json` + `backend-ui-delivery-onboarding.json`：两个端点的调用方是 Flutter 商户 App（ai-shop-bapp），按仓库规矩登记进它的 OpenAPI 契约与分阶段交付清单，`check-app-backend-orphan` 才不把它们算成孤儿；其余 7 个是生成器产物 |
| TDD 列了、实际没动的文件 | 无 |

### 偏差说明

实施计划第 1 步的测试与实现是同一轮写的，没有先单独跑红；用消融补证（见 §5）。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-09 | 草稿；实现完成，未提交。闸门：`check-app-backend-orphan` / `check-app-contract` / `check-controller-cohesion` / `check-generated-docs` / `check-i18n-orphan` / `gen-ui-catalog --check` 绿；`check-sql-portability` 红为存量（本次未改 SQL）。后端测试只跑了 shop-app 与 commerce starter 的上述类，没跑全量 |
