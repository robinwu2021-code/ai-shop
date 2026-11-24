# TDD · 子行业与能力端验收

状态：已实现（待发版与运营发布） · 档位：1（新表 `sys_store_industry` + 种子；`mch_store` 加两列，**带 DDL**；`store-setup` / quick-start / 门店回显行为变化，接口形状不变；部署配置 `accepted-deployments` 允许能力端）
关联：[B端门店功能矩阵-执行方案](./B端门店功能矩阵-执行方案-2026-11-09.md) D3 / W2.2；[TDD-B端门店功能矩阵与方案准入](./TDD-B端门店功能矩阵与方案准入.md) §4-5；[TDD-注册地区与三步建店](./TDD-注册地区与三步建店.md)；[TDD-B端建店默认经营市场](./TDD-B端建店默认经营市场.md) §1
创建：2026-11-09

## §0 需求与落点

两件事一次发版：

**甲、能力端可以验收**。166 只读核对（2026-11-09）：业务端只有 `END_BIZ_APP`、`END_APP_POS`；零售专业版与连锁版内容相同，餐饮高两档停售，服务业不存在。原因是 `ShopMallDeploymentRegistry#installedPolicy` 只认 BIZ、POS 两条安装路由，`END_RETAIL / END_FNB / END_SERVICE / END_MARKETING / END_CHAIN` 永远验收不了，含它们的方案发布不了（`CatalogSupport#endValid`）。这五个码是**商户 App 里的一组功能**，没有独立客户端与路由。

**乙、每个行业有子行业**。App 建店已按「行业 → 子行业 → 经营类型」两级选（`GET /biz/merchant/store-setup`），后端一直是占位：每个经营类型当一个行业、下挂一个同名子行业；门店不存选了哪个子行业。

| AC | 需求（Given / When / Then） | 落点 |
|---|---|---|
| A1 | Given 部署配置登记 `END_FNB`（码 = 权益码，路由 `commerce-route-v1:BIZ`，带验收引用），Then 注册表接受，运营可把它登记成已验收业务端并放进方案发布 | `ShopMallDeploymentRegistry#installedPolicy` |
| A2 | 能力端的码必须在白名单（RETAIL / FNB / SERVICE / MARKETING / CHAIN）里且码 = 权益码；挂 POS 路由、码与权益码不一致、不在白名单的一律拒绝；BIZ、POS 原规则不变 | 同上 |
| B1 | Given `sys_store_industry` 有子行业，When 读 `store-setup`，Then 返回「行业 → 子行业」两级，每个子行业带它的经营类型；**只列经营类型已发布且启用的**；行业下没有可用子行业就不列 | `BizFoundationController#storeSetup` |
| B2 | Given `sys_store_industry` 为空（其他部署），Then 回落到原来的一对一默认映射，与改动前完全一样 | 同上 |
| B3 | 显示名按请求语言：繁体（zh-TW / zh-HK / Hant）取繁体名，英文取英文名，其余取简体；缺哪种就用简体 | `IndustryCatalog#displayName` |
| B4 | When quick-start 带已登记的行业 + 子行业，Then 子行业必须属于该行业、`businessTypeCode` 必须等于子行业的经营类型，否则 10400；门店落库 `industry_code`、`sub_industry_code` | `BizMerchantController#requireIndustry`、`MerchantPortImpl#quickStart` |
| B5 | 旧形状（行业 = 子行业 = 经营类型）仍接受，门店落库行业码、子行业码为空；「其他」两条规则不变（已登记行业 + 子行业「其他」时，经营类型取该行业的经营类型） | 同上 |
| B6 | `GET /biz/stores/mine` 与 `GET /biz/stores/{storeNo}/context` 回显门店存的行业、子行业码；自填那一级报 `OTHER`；没存（存量门店）回落经营类型，与改动前一致 | `StoreVO#withMarket`、`HostStoreContext#of` |
| B7 | 子行业**不影响**方案与功能矩阵：方案、功能仍按经营类型 | 不改 `StoreFeatureMatrix` |

## §1 现状与影响面

- 复用：经营类型目录（commerce `sys_business_type`，运营发布）；`store-setup`、quick-start 的请求 / 响应形状（App 不改）。
- 会被改到：`ShopMallDeploymentRegistry`；`BizFoundationController`；`BizMerchantController`；`MerchantAdminPort.QuickStartCommand`（加两个字段，旧构造保留）；`MerchantPortImpl#quickStart`；`MchStore`、`StoreVO`、`StoreAdminServiceImpl`、`MerchantQueryPort.StoreProfile`、`HostStoreContext`。
- 不受影响：功能矩阵、方案准入、经营类型目录本身。服务业经营类型（`SERVICE`）由运营在运营端建并发布；在那之前服务业子行业不出现在 `store-setup`（B1）。
- 定价配置里的 `BEAUTY_SERVICE` 是定价行业码，与经营类型 `SERVICE` 不是同一个概念，本次不动。

## §2 方案

### 数据

`sys_store_industry`（宿主库，迁移 V643；**带 DDL**）：

| 列 | 说明 |
|---|---|
| `industry_code` | 行业码，如 `FOOD` |
| `sub_industry_code` | 子行业码，如 `FOOD_BREAKFAST`；行业本身那一行为空 |
| `business_type_code` | 对应经营类型（行业行与子行业行都填；子行业决定建店的经营类型） |
| `name_zh` / `name_tw` / `name_en` | 简体 / 繁体 / 英文显示名 |
| `sort`、`enabled` | 排序、是否启用 |

唯一键 `(industry_code, sub_industry_code)`；行业行的子行业码用空串存，避免唯一键里的 NULL。

`mch_store` 加 `industry_code VARCHAR(32) NULL`、`sub_industry_code VARCHAR(48) NULL`（同一迁移）。

首批种子（行业 → 子行业）：

| 行业 | 经营类型 | 子行业 |
|---|---|---|
| 餐饮 | FOOD | 早餐店、中式餐馆、快餐便当、小吃、手摇饮料、咖啡厅、烘焙甜点、火锅、烧烤、日韩料理、西餐、酒吧 |
| 零售 | RETAIL | 便利店 / 超市、生鲜蔬果、服饰鞋包、美妆个护、母婴用品、数码家电、日用百货、宠物用品、书店文具、药妆保健 |
| 服务业 | SERVICE | 美发、美甲美睫、美容护肤、按摩 SPA、健身瑜伽、宠物美容、洗衣、维修、教育培训、摄影 |

### 能力端

`installedPolicy`：路由 `commerce-route-v1:BIZ` 时，码可以是 `END_BIZ_APP` 或能力端白名单之一；码必须等于权益码。路由 POS 仍只能是 `END_APP_POS`。`verified` 不变（逐项比对部署配置）。

166 部署配置示例（验收引用填真实记录）：

```yaml
shop.commerce.mall.accepted-deployments:
  - deploymentNo: ume-biz-fnb
    displayName: 餐飲功能
    code: END_FNB
    entitlementCode: END_FNB
    allowedBusinessTypes: [FOOD]
    clientId: biz-app
    routePolicyRef: commerce-route-v1:BIZ
    acceptanceRef: <验收记录>
```

### 模块

| 动作 | 路径 |
|---|---|
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopMallDeploymentRegistry.java` |
| 新增 | `backend/shop-app/src/main/resources/db/migration/V643__industry_and_store_sub_industry.sql` |
| 新增 | `backend/shop-merchant/.../industry/IndustryCatalog.java`（读表、按语言取名） |
| 修改 | `BizFoundationController`、`BizMerchantController`、`MerchantAdminPort`、`MerchantPortImpl`、`MchStore`、`StoreVO`、`StoreAdminServiceImpl`、`MerchantQueryPort`、`HostStoreContext` |
| 测试 | `MallHostReadinessTest`（A1/A2）、`BizStoreSetupTest`（B1–B5）、quick-start 落库与回显流程测试（B4/B6） |

### 上线步骤

1. 发版（V643 带 DDL：临时授权或 root 先执行）。
2. 166 部署配置加五个能力端的 `accepted-deployments`；`END_BIZ_APP` 允许类型加 `SERVICE`，`END_APP_POS` 加 `FOOD`、`SERVICE`。
3. 运营端：建 `SERVICE` 经营类型；登记五个业务端为已验收；按执行方案 D3 改三个行业的三档方案并发布。

## §4 待决

| # | 问题 | 建议 |
|---|---|---|
| D22 | 子行业由谁维护 | 首批迁移写入；运营端「行业管理」页面放第二期 |
| D23 | 子行业是否影响方案 | 不影响（B7）；以后可用于建店默认设置与统计 |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 |
|---|---|
| A1 | `MallHostReadinessTest#capabilityEndsRideOnTheBizRouteAndCanBeVerified`（五个能力码挂 BIZ 路由可验收；先红后绿） |
| A2 | `MallHostReadinessTest#capabilityEndsStayWithinTheirContract`（白名单外、挂 POS、码与权益码不一致、POS 挂 BIZ 都拒绝）；原 `#emptyRegistryAndUnavailablePaymentDoNotPreventCatalogConfigurationOrInventAcceptance`（POS 验收、未安装路由被拒）照旧 |
| B1 | `BizStoreSetupTest#b1StoreSetupListsRealSubIndustriesOfPublishedTypesOnly` |
| B2 | `BizStoreSetupTest#b2EmptyCatalogFallsBackToTheDefaultMapping`；原有 store-setup 用例全部照旧 |
| B3 | `BizStoreSetupTest#b3DisplayNamesFollowTheRequestLanguage` |
| B4、B5 | `BizStoreSetupTest#b4QuickStartValidatesSubIndustryAgainstTheCatalog`、`#b4StoredCodesFollowWhatWasActuallyChosen`；`RegistrationMarketFlowTest#subIndustryCodesAreStoredAndReadBack`（落库） |
| B6 | `RegistrationMarketFlowTest#subIndustryCodesAreStoredAndReadBack`（`/biz/stores/mine` 回显与存量回落）、`HostStoreContextTest#storedSubIndustryIsEchoedOtherwiseTheBusinessType` |
| B7 | 未改 `StoreFeatureMatrix`；功能矩阵测试照旧 |
| 回归 | `QuickStartFlowTest`（10）、`MerchantEntityStoreMarketTest`、`SchemaGeneratorTest`、`SchemaDriftTest` |

166 冒烟发现（2026-11-09）：B3 在真实请求下没生效 —— `I18nConfig` 的语言解析只认简体、英文、阿拉伯文，`zh-TW` 一律回落简体。修法：繁体请求（`zh-TW / zh-HK / zh-MO` 或书写体系 `Hant`）解析成 `zh_TW`；没有繁体文案文件，服务端提示照旧回落简体。测试 `I18nConfigTest`（3，先红后绿）。市场名「中国台湾」来自 `sys_market` 的单一名称，不随语言变，需要时由运营在「市场货币」改名。

实施中发现：库里已有一张 `sys_industry`（支付侧行业，带微信 / 支付宝小微开关），新表因此命名 `sys_store_industry`。契约 `openapi-b-onboarding.json` 的 `QuickStartRequest.subIndustryCode`、`Store.industryCode / subIndustryCode` 说明已同步。
