# TDD · B 端建店默认经营市场

状态：已实现（2026-11-04） · 档位：1（新增端点 `GET /biz/merchant/store-setup`、配置项 `shop.merchant.default-market`、quick-start 入参）

## §0 需求与落点

用户 2026-11-04 决定：**暂时用默认方案，本实例为 TW**。B 端 App（ai-shop-bapp 的 `openapi-b-onboarding.json`，28 操作）登录后先读 `store-setup` 选经营市场，再带 `market`/`industryCode`/`subIndustryCode` 调 quick-start。后端此前没有 store-setup，quick-start 也不写市场，新主体落库默认 `CN`，免费期按 CNY 冻结，台湾门店看不到 TWD 方案。

| AC | 落点 |
| --- | --- |
| AC1 Given 部署默认市场 TW 且启用，When 商家读 store-setup，Then 只返回 TW（TWD、小数位、IANA 时区）及已发布经营类型 | `BizFoundationController.storeSetup` |
| AC2 Given 同上，When quick-start 不带或带 `market=TW`，Then 主体 `market=TW`，FREE 期币种 TWD | `BizMerchantController.quickStart` → `MerchantPortImpl.quickStart` |
| AC3 When 带其它市场，或行业/子业态与经营类型不匹配，Then 拒绝，不建壳 | 同上校验 |
| AC4 Given 未配置（默认 CN），Then 旧行为不变 | 配置默认值 `CN` |
| AC5 Given 主体市场为 TW，When 读 `GET /biz/stores/mine`，Then 每家门店带 `market`/`currency`/`currencyScale`/`timeZone` 与 `businessTypeCode`/`industryCode`/`subIndustryCode`（按 §1 默认映射），App 建店后据此认出新店 | `MerchantEntityServiceImpl.assemble` |
| AC6 Given 同上，When 读 `GET /biz/stores/{storeNo}/context`，Then 在原响应上平铺 `storeName`/`address`/`market`/`currency`/`currencyScale`/`timeZone`/`industryCode`/`subIndustryCode`（Flutter 契约 CurrentStoreCommercialContext 的可选字段），App 据此核验建店结果与 FREE | `BizFoundationController.context` + `HostStoreContext` |

## §1 方案

- **一个部署只有一个经营市场**：`shop.merchant.default-market`（默认 `CN`）。市场元数据取 `sys_market`（经 `MarketPort`），该市场未启用则 store-setup 返回空列表、quick-start 拒绝。
- **默认行业映射**：不新建映射表。每个已发布、已启用的经营类型 T 映射为 行业 T → 子业态 T → 经营类型 T（显示名、目录版本取自真实已发布目录，不造数据）。客户端回传的 `industryCode`/`subIndustryCode` 必须等于 `businessTypeCode`。
- **以后多市场**：再引入平台行业映射表与运营配置，届时替换 §1 默认映射，接口形状不变。

## §2 实施计划

1. `MerchantAdminPort.QuickStartCommand` 增加 `market`（兼容构造保持旧调用）；`MerchantPortImpl.quickStart` 写 `mch_entity.market`，已有待补证壳市场不同则拒绝。
2. `BizMerchantController.QuickStartReq` 增加 `market`、`industryCode`、`subIndustryCode` 并校验。
3. `BizFoundationController` 新增 `GET /biz/merchant/store-setup`；`CommerceRoutePolicy` 引导白名单加入该路由。
4. `application.yml` 增加配置项；本实例部署设 `TW`，且 `sys_market` 中 TW 须启用（运营在「市场货币」开启）。
5. （2026-11-05 真机闭环发现）`StoreVO` 末尾增加 7 个可空字段（旧构造保留）：市场取门店所属主体 `mch_entity.market`，币种/小数位/时区取 `sys_market`（`MarketPort`，取不到则只给 market），经营类型取 `mch_store.business_type_code`，行业/子业态按 §1 等于经营类型。契约 `openapi-b-onboarding.json` 的 `Store` 同步加这 7 个可选字段（与 Flutter 仓 `.2-store-setup` 版一致）。
6. 同日：门店经营上下文由宿主平铺 8 个字段（可移植 commerce 的 `StoreContext` 不变）。门店名/地址/主体市场经 `MerchantQueryPort.storeProfileOf` 读，币种等取 `MarketPort`；契约 `CurrentStoreCommercialContext` 同步加可选字段。

## §5 验证

- `BizStoreSetupTest`（宿主）：AC1–AC4。
- `MerchantEntityStoreMarketTest`（shop-merchant）：AC5。
- `HostStoreContextTest`（宿主）：AC6。
- 本地实例（TW）：模拟器上 App 登录 → 选 TW → 建店 → 商城页签看到 TWD 方案。
