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

## §1 方案

- **一个部署只有一个经营市场**：`shop.merchant.default-market`（默认 `CN`）。市场元数据取 `sys_market`（经 `MarketPort`），该市场未启用则 store-setup 返回空列表、quick-start 拒绝。
- **默认行业映射**：不新建映射表。每个已发布、已启用的经营类型 T 映射为 行业 T → 子业态 T → 经营类型 T（显示名、目录版本取自真实已发布目录，不造数据）。客户端回传的 `industryCode`/`subIndustryCode` 必须等于 `businessTypeCode`。
- **以后多市场**：再引入平台行业映射表与运营配置，届时替换 §1 默认映射，接口形状不变。

## §2 实施计划

1. `MerchantAdminPort.QuickStartCommand` 增加 `market`（兼容构造保持旧调用）；`MerchantPortImpl.quickStart` 写 `mch_entity.market`，已有待补证壳市场不同则拒绝。
2. `BizMerchantController.QuickStartReq` 增加 `market`、`industryCode`、`subIndustryCode` 并校验。
3. `BizFoundationController` 新增 `GET /biz/merchant/store-setup`；`CommerceRoutePolicy` 引导白名单加入该路由。
4. `application.yml` 增加配置项；本实例部署设 `TW`，且 `sys_market` 中 TW 须启用（运营在「市场货币」开启）。

## §5 验证

- `BizStoreSetupTest`（宿主）：AC1–AC4。
- 本地实例（TW）：模拟器上 App 登录 → 选 TW → 建店 → 商城页签看到 TWD 方案。
