# TDD-发票服务-代码结构与类清单

状态：草稿 · 待评审 · 2026-11-22
依据：[TDD-发票服务-总体设计](./TDD-发票服务-总体设计.md) · [供应商适配抽象](./TDD-发票服务-供应商适配抽象.md) · [表结构说明](./TDD-发票服务-数据库表结构说明.md) · [API 文档](./TDD-发票服务-API文档.md) · 规范 [java.md §七 包规划、类名与业务边界](../../.claude/skills/ai-shop-dev-standards/references/java.md)
参照：[TDD-支付服务-代码结构与类清单](./TDD-支付服务-代码结构与类清单.md)

---

## L1 定位

把发票服务的设计落到**模块 → 包 → 完整类名**，并逐个标明每个类的来源：沿用现有代码、改造、拆分、迁移还是新增。
实施时按本清单建类；实现后用 `git show --stat` 与本清单逐行对账（TDD §11）。

## L2 结构

```
backend/invoice/                                   包前缀 ai.neargo.shop.invoice
├── invoice-channel   供应商适配            channel.{invoke,credential,http,cetustek,ecpay,turnkey,stub}
├── invoice-domain    领域 + 应用 + 后台    entity · mapper · dto · service(.impl) · gateway · regime(.tw) · policy · statemachine · port · job · client
├── invoice-portal    接入层（无业务）      api.{biz,mp,ops} · api.auth
└── invoice-svc       独立形态进程          svc · api.internal · api.callback
backend/shop-base     spi.invoice           InvoicePort · InvoiceSourcePort · 事件
backend/shop-app      invoiceclient · invoicebridge · config
```

| 模块 | 依赖 | 为什么单独成模块（java.md §7.1：只因部署、数据源或契约边界拆分） |
|---|---|---|
| `invoice-channel` | `invoice-domain`（实现其 `gateway` 接口）、`secret-api` | 供应商协议随对方变，与领域发布节奏不同；协议代码不进领域 |
| `invoice-domain` | `shop-base`、`shop-store-mybatis`、`secret-api` | 领域与持久化，独立数据源 |
| `invoice-portal` | `invoice-domain`、`shop-base-auth` | 两种部署形态都装同一套 controller（内嵌进主应用 / 装进 `invoice-svc`） |
| `invoice-svc` | 以上三个 + `secret-core` | 独立部署形态 |

**依赖倒置**：领域调用供应商只经 `gateway` 包里的接口（`ProviderInvoker`、能力接口）；实现全部在 `invoice-channel`。所以领域不依赖任何供应商代码。

### 本清单对总体设计 §3.2 的修正（以本清单为准）

| # | 总体设计 §3.2 写法 | java.md §七 | 本清单 |
|---|---|---|---|
| F1 | controller 放 `invoice.portal.{biz,mp,ops,open}` | 单域 controller 在 `<domain>.api.biz/mp/ops/internal`；`portal.<端>` 只给 shop-app 跨域组合用 | 包为 `invoice.api.{biz,mp,ops,internal,callback}`，**模块**仍是 `invoice-portal` / `invoice-svc` |
| F2 | 按子域分包（`access`、`seller`、`account`、`numbering`、`issuing` …） | Service 接口在 `<domain>.service`、实现在 `.service.impl`；实体在 `.entity`；Mapper 在 `.mapper` | 按 java.md 分层分包；策略类进 `policy`，状态机进 `statemachine` |
| F3 | Mapper 分 `mapper.{master,seller,document,log}` | 集合按域命名 `XxxMappers.YyyMapper` | `InvoiceMappers`（业务表）+ `InvoiceLogMappers`（往来记录表），都在 `invoice.mapper` |
| F4 | `SellerService` | 不加没有语义增量的前缀，但要避免与他域同名不同义 | `InvoiceSellerService`（「卖家」在商品域另有含义） |

---

## §1 现状清单与去向

### 1.1 后端 `backend/invoice`（39 个 Java 主类文件 + 5 个迁移脚本）

| 现有文件（`invoice-domain` 下省略模块名） | 行数 | 去向 |
|---|---|---|
| `api/biz/BizInvoiceController` | 86 | **迁移** → `invoice-portal` `api.biz.BizInvoiceController` |
| `api/biz/BizInvoiceScope` | 17 | **迁移** → `invoice-portal` `api.biz.BizInvoiceScope`，改为产出 `InvoiceCaller` |
| `api/biz/BizInvoiceSetupController` | 75 | **拆分** → `BizInvoiceSellerController` / `BizInvoiceAccountController` / `BizInvoiceBindingController` / `BizInvoiceProviderController`；`/biz/invoice-setup/profile`、`/ranges` 保留两个版本作兼容 |
| `api/mp/MpSalesInvoiceController` | 82 | **拆分** → `MpSalesInvoiceController`（我的发票、开法）+ `MpInvoiceCarrierController`（载具） |
| `dto/InvoiceDtos` | 183 | **改**：保留票据类 DTO；营业人 / 账户 / 绑定 DTO 移到 `InvoiceSetupDtos`；删 `RegimeProfileReq/VO` |
| `entity/IvcInvoice` `IvcAllowance` `IvcNumberRange` `IvcTransmission` `IvcUserCarrier` | 213 | **改**：按表结构说明加列 |
| `entity/IvcInvoiceLine` `IvcBuyerPreference` | 59 | 沿用 |
| `entity/IvcRegimeProfile` | 41 | **删除**（V6 迁移验证后） |
| `gateway/InvoiceGateway` | 36 | **拆分** → 能力接口 `IssueCapability` 等（§2.3）；`ProviderAccount` → `ProviderAccountContext` |
| `gateway/InvoiceProviderGateway` | 41 | **拆分** → `AccountCapability`、`RangeCapability`；`ProviderException` 并入 `ProviderResult.Rejected` |
| `job/InvoiceTransmissionJob` | 59 | 沿用 |
| `mapper/InvoiceMappers` | 83 | **改**：加 11 个业务表 Mapper；往来记录表的 Mapper 放新的 `InvoiceLogMappers` |
| `port/InvoicePortImpl` | 37 | **改**：委托 `InvoiceIssueService`（内嵌形态的本地实现） |
| `port/InvoicePrintSourceFactsPortImpl` | 238 | 沿用；在线配号的票只在 `issue_status=ISSUED` 后出事实 |
| `regime/InvoiceRegime` `InvoiceRegimes` | 102 | **改**：`message` → `document`（返回 `RegimeDocument`）；`InvoiceRegimes` 改名 `RegionRegistry`，按 `ivc_region` 校验 |
| `regime/tw/TwEguiRegime` `TwBuyerRules` `TwPeriods` `TwProofCodes` `TwQrCipher` | 563 | 沿用；`TwEguiRegime` 适配新签名 |
| `service/InvoiceService` + `impl/InvoiceServiceImpl` | 558 | **拆分** → `InvoiceIssueService` / `InvoiceVoidService` / `InvoiceAllowanceService` / `InvoiceQueryService`；取号逻辑移入 `NumberingStrategy` |
| `service/InvoiceSetupService` + `impl/InvoiceSetupServiceImpl` | 285 | **拆分** → `InvoiceSellerService` / `ProviderAccountService` / `NumberRangeService` / `AccountBindingService` |
| `service/InvoiceBuyerService` + `impl` | 180 | 沿用 |
| `service/InvoiceTransmissionService` + `impl` | 105 | **改**：经 `ProviderInvoker` 发送、结果交 `InvoiceOutcomeService` |
| `service/impl/InvoiceProviderAccounts` | 57 | **迁移** → `invoice-channel` `credential.SecretProviderCredentialAccess`；封存改由 `ProviderAccountServiceImpl` 调 `SecretVault` |
| `invoice-channel` `cetustek/CetustekInvoiceGateway` | 275 | **改** → `CetustekInvoiceProvider`（继承 `AbstractSoapInvoiceProvider`，实现 7 个能力接口）+ `CetustekDocumentMapper`（MIG → 鲸跃 XML） |
| `invoice-channel` `cetustek/CetustekSoapClient` | 112 | 沿用（改为经 `RecordingProviderClient` 发出） |
| `invoice-channel` `turnkey/TurnkeyInvoiceGateway` | 109 | **改** → `TurnkeyInvoiceProvider`（继承 `AbstractFileExchangeInvoiceProvider`）+ `TurnkeyMigWriter` |
| `invoice-channel` `stub/StubInvoiceGateway` | 23 | **改** → `StubInvoiceProvider`（直接继承 `AbstractInvoiceProvider`，实现全部能力） |
| `db/invoice/V1`–`V5` | 221 | 冻结不改；新增 `V6`–`V9`（§5） |

### 1.2 跨模块

| 文件 | 去向 |
|---|---|
| `shop-base` `spi/invoice/InvoicePort` `InvoiceSourcePort` | 沿用；新增事件 `InvoiceIssuedEvent`、`InvoiceVoidedEvent`（同包） |
| `shop-app` `invoicebridge/port/SubOrderInvoiceSourcePortImpl` | 沿用 |
| `shop-app` `invoicebridge/InvoiceAutoIssueConsumer` | 沿用 |
| `shop-app` `config/InvoiceSchemaConfig` | **改名** → `InvoiceDataSourceConfig`：独立数据源 + `invoiceTxManager` + Mapper 扫描 `invoice.mapper` |
| `print` 打印中心（场景 `INVOICE_PROOF` / `INVOICE_DETAIL` / `INVOICE_ALLOWANCE`、条码与 QR 版面块） | 沿用，不动 |

### 1.3 前端

| 文件 | 去向 |
|---|---|
| b-app `pages/sales-invoices` `sales-invoice` `sales-invoice-issue` | 沿用；开立中 / 结果未知两种状态加展示 |
| b-app `pages/invoice-setup/index.vue` | **重写**：营业人卡片 + 账户列表 + 门店绑定（见 §4） |
| b-app `api/sales-invoice.ts` `sales-invoice-http.ts` `mocks/sales-invoice.ts` | **改**：加 13 个设置接口 |
| b-app `shared/sales-invoice.ts` | 沿用 |
| c-app `pages/invoice-carriers` `invoice-choice` `sales-invoices` `sales-invoice` | 沿用 |
| b-app `pages/invoice/index.vue`、ops-web `finance/invoice-tab.tsx` `buyer-invoice-tab.tsx` | 不相关（平台事后开票申请 / 结算发票），不动 |

---

## §2 目标类清单

完整类名省略前缀 `ai.neargo.shop.invoice`。来源：**沿用** / **改** / **迁移自 X** / **拆自 X** / **新**。阶段对应总体设计 §7：1 收拢、2 多供应商、3 独立部署。

### 2.1 `invoice-domain` · 实体 `entity`（23 个，表名驼峰）

| 类 | 表 | 来源 | 阶段 |
|---|---|---|---|
| `entity.IvcRegion` | `ivc_region` | 新 | 1 |
| `entity.IvcProvider` | `ivc_provider` | 新 | 1 |
| `entity.IvcProviderRegion` | `ivc_provider_region` | 新 | 1 |
| `entity.IvcProviderProduct` | `ivc_provider_product` | 新 | 1 |
| `entity.IvcProviderCredentialField` | `ivc_provider_credential_field` | 新 | 1 |
| `entity.IvcSeller` | `ivc_seller` | 新（拆自 `IvcRegimeProfile`） | 1 |
| `entity.IvcProviderAccount` | `ivc_provider_account` | 新（拆自 `IvcRegimeProfile`） | 1 |
| `entity.IvcAccountCredential` | `ivc_account_credential` | 新 | 1 |
| `entity.IvcAccountBinding` | `ivc_account_binding` | 新 | 1 |
| `entity.IvcAccountEvent` | `ivc_account_event` | 新 | 1 |
| `entity.IvcNumberRange` | `ivc_number_range` | 改 | 1 |
| `entity.IvcInvoice` | `ivc_invoice` | 改 | 1 |
| `entity.IvcInvoiceLine` | `ivc_invoice_line` | 沿用 | — |
| `entity.IvcAllowance` | `ivc_allowance` | 改 | 1 |
| `entity.IvcBuyerPreference` | `ivc_buyer_preference` | 沿用 | — |
| `entity.IvcUserCarrier` | `ivc_user_carrier` | 改 | 1 |
| `entity.IvcTransmission` | `ivc_transmission` | 改 | 1 |
| `entity.IvcApiRequestLog` | `ivc_api_request_log` | 新 | 1 |
| `entity.IvcProviderCallLog` | `ivc_provider_call_log` | 新 | 1 |
| `entity.IvcProviderReceipt` | `ivc_provider_receipt` | 新 | 2 |
| `entity.IvcRawPayload` | `ivc_raw_payload` | 新 | 1 |
| `entity.IvcBizNotify` | `ivc_biz_notify` | 新 | 3 |
| `entity.IvcReconDiff` | `ivc_recon_diff` | 新 | 2 |

### 2.2 `invoice-domain` · Mapper `mapper`

| 类 | 内含 Mapper | 来源 |
|---|---|---|
| `mapper.InvoiceMappers` | `RegionMapper` `ProviderMapper` `ProviderRegionMapper` `ProviderProductMapper` `CredentialFieldMapper` `SellerMapper` `ProviderAccountMapper` `AccountCredentialMapper` `AccountBindingMapper` `AccountEventMapper` `NumberRangeMapper`（含 `takeForUpdate` / `countOverlap`） `InvoiceMapper`（含 `lockActive`） `InvoiceLineMapper` `AllowanceMapper` `BuyerPreferenceMapper` `UserCarrierMapper` `TransmissionMapper` `ReconDiffMapper` | 改（现有 9 个 + 新 9 个；删 `RegimeProfileMapper`） |
| `mapper.InvoiceLogMappers` | `ApiRequestLogMapper` `ProviderCallLogMapper` `ProviderReceiptMapper` `RawPayloadMapper` `BizNotifyMapper` | 新 |

### 2.3 `invoice-domain` · 供应商契约 `gateway`（领域定义、渠道实现）

| 类 | 种类 | 职责 | 来源 |
|---|---|---|---|
| `gateway.ProviderInvoker` | interface | 领域调用供应商的唯一入口：`ProviderResult invoke(ProviderAccountContext, ProviderCommand)` | 新 |
| `gateway.ProviderAccountContext` | record | 地区、供应商、环境、账户 id / 号、代号、凭据版本、地址；**不含密钥** | 拆自 `InvoiceGateway.ProviderAccount` |
| `gateway.ProviderCommand` | sealed interface | `IssueCommand` `VoidCommand` `AllowanceCommand` `AllowanceVoidCommand` `QueryCommand` `RangeCommand` `VerifyCommand`（同文件 record） | 新 |
| `gateway.ProviderResult` | sealed interface | `Accepted(providerCode, providerRef, data)` · `Rejected(providerCode, message, retryable, accountProblem)` · `Unknown(reason)` · `Unsupported(operation)` | 拆自 `GatewayReceipt` / `ProviderException` |
| `gateway.InvoiceProvider` | interface | 一家供应商：`code()`、`profile()` | 新 |
| `gateway.ProviderProfile` · `CredentialField` | record | 地区、编号方式、上报方式、凭据字段（与种子表启动比对） | 新 |
| `gateway.ProviderCall` | record | 账户上下文 + 现取凭据 + 追踪号，传给能力方法 | 新 |
| `gateway.CredentialSet` | final class | 一次调用内的凭据明文；`toString` 不输出值 | 新 |
| `gateway.ProviderOperation` | enum | `ISSUE` `VOID` `ALLOWANCE` `ALLOWANCE_VOID` `QUERY` `RANGE` `VERIFY` `PRINT_DATA` | 新 |
| `gateway.ProviderCapability` | interface | `providerCode()`、`regions()` | 新 |
| `gateway.IssueCapability` · `VoidCapability` · `AllowanceCapability` · `AllowanceVoidCapability` · `QueryCapability` · `RangeCapability` · `AccountCapability` · `PrintDataCapability` | interface | 按动作拆的能力；厂商类只实现自己有的（见[供应商适配抽象](./TDD-发票服务-供应商适配抽象.md)） | 拆自 `InvoiceGateway` / `InvoiceProviderGateway` |
| `gateway.ProviderCredentialAccess` | interface | 按 `(accountId, version)` 现取凭据明文（仅渠道层调用） | 新 |

### 2.4 `invoice-domain` · 税制 `regime`

| 类 | 种类 | 来源 |
|---|---|---|
| `regime.InvoiceRegime` | interface | 改：`message` → `document`；加 `validateSeller(IvcSeller)` |
| `regime.RegimeDocument` | record | 新：`schema`、`body`、`ext` |
| `regime.RegionRegistry` | @Component | 改自 `InvoiceRegimes`：启动时比对 `ivc_region` 与实现 |
| `regime.tw.TwEguiRegime` · `TwBuyerRules` · `TwPeriods` · `TwProofCodes` · `TwQrCipher` | class | 沿用（`TwEguiRegime` 适配签名） |

### 2.5 `invoice-domain` · 策略与状态机 `policy` / `statemachine`

| 类 | 种类 | 职责 | 来源 | 阶段 |
|---|---|---|---|---|
| `policy.InvoiceCaller` | record | 调用方：类型（BIZ/MP/OPS/OPEN/INTERNAL）、租户、主体、门店、操作人 | 新 | 1 |
| `policy.InvoiceCallerPolicy` | @Component | 能力矩阵：哪类调用方能做哪类动作 | 新 | 1 |
| `policy.AccountManagementPolicy` | @Component | 写权判定：BIZ 只写 MERCHANT、OPS 只写 PLATFORM，另有两个运营例外 | 新 | 1 |
| `policy.CredentialSchema` | @Component | 按 `ivc_provider_credential_field` 校验凭据输入；算掩码与指纹 | 新 | 1 |
| `policy.NumberingStrategy` | interface | `allocate`（事务内）· `afterCommit`（事务后） | 新 | 1 |
| `policy.LocalRangeNumbering` | @Component | Turnkey / Stub：号段 `FOR UPDATE` 取号 | 拆自 `InvoiceServiceImpl` | 1 |
| `policy.ProviderRangeNumbering` | @Component | 鲸跃：同上 + 低水位触发领号 | 新 | 1 |
| `policy.ProviderOnlineNumbering` | @Component | 绿界：占位 + `RelateNumber` + 事务后同步开立 | 新 | 2 |
| `policy.InvoiceRouteResolver` | @Component | 门店绑定 → 账户；`frozen(accountId)` | 新 | 1 |
| `policy.InvoiceRoute` | record | 冻结路由快照 | 新 | 1 |
| `policy.ProviderContextFactory` | @Component | **唯一**构造 `ProviderAccountContext` 的地方 | 新 | 1 |
| `statemachine.InvoiceStateMachine` | class | 法律状态 / 开立过程 / 上报三状态的合法迁移 | 新 | 1 |
| `statemachine.ProviderAccountStatus` | enum + `canTransitTo` | `ONBOARDING → ACTIVE → ISSUE_STOPPED / SUSPENDED → CLOSED` | 新 | 1 |

### 2.6 `invoice-domain` · 应用服务 `service` / `service.impl`

接口在 `service`，实现 `XxxServiceImpl` 在 `service.impl`；每个公开方法第一个参数是 `InvoiceCaller`（内部任务除外）。

| 接口 | 主要方法 | 来源 | 阶段 |
|---|---|---|---|
| `service.InvoiceIssueService` | `issue` · `issueIfAutomatic` · `activeOf` · `rememberBuyer` | 拆自 `InvoiceService` | 1 |
| `service.InvoiceVoidService` | `voidInvoice` | 拆自 `InvoiceService` | 1 |
| `service.InvoiceAllowanceService` | `allowance` · `voidAllowance` | 拆自 `InvoiceService` | 1 |
| `service.InvoiceQueryService` | `page` · `detail` · `mine`（C 端我的发票） | 拆自 `InvoiceService` | 1 |
| `service.InvoiceOutcomeService` | `apply(InvoiceOutcome)` —— 开立 / 上报状态的唯一写入点 | 新 | 1 |
| `service.InvoiceSellerService` | `list` · `save` · `saveQrKey` | 拆自 `InvoiceSetupService` | 1 |
| `service.ProviderAccountService` | `create` · `rotateCredential` · `verify` · `changeStatus` · `changeManagement` · `events` · `importAccounts` | 拆自 `InvoiceSetupService` | 1 |
| `service.AccountBindingService` | `list` · `bind` | 新 | 1 |
| `service.NumberRangeService` | `list` · `register`（手工登记）· `fetchFromProvider` · `take`（供策略调用） | 拆自 `InvoiceSetupService` | 1 |
| `service.InvoiceProviderService` | `list` · `update` · `changeRegion` · `availableFor(region)` | 新 | 1 |
| `service.InvoiceTransmissionService` | `transmitDue` | 改 | 1 |
| `service.InvoiceRecoveryService` | `recoverDue`（开立中超时 / 结果未知 → 查单） | 新 | 2 |
| `service.ProviderReceiptService` | `receive` · `replay` | 新 | 2 |
| `service.InvoiceReconService` | `reconcile(date, account)` | 新 | 2 |
| `service.InvoiceBuyerService` | 载具与开法（沿用） | 沿用 | — |
| `service.InvoiceRequestLogService` | `record`（接入层请求留痕） | 新 | 1 |

### 2.7 `invoice-domain` · 其它

| 类 | 种类 | 职责 | 来源 | 阶段 |
|---|---|---|---|---|
| `dto.InvoiceDtos` | records | 票据类 Req / VO（沿用 + 开立中状态字段） | 改 | 1 |
| `dto.InvoiceSetupDtos` | records | `SellerReq/VO` · `AccountCreateReq` · `OpsAccountCreateReq` · `CredentialRotateReq` · `AccountStatusReq` · `ManagementReq` · `AccountVO` · `CredentialStateVO` · `VerificationVO` · `BindingReq/VO` · `AccountEventVO` · `RangeFetchReq` · `NumberRangeVO` · `ProviderVO` · `ProviderProductVO` · `CredentialFieldVO` · `AccountImportReq` · `AccountImportResultVO`（与 OpenAPI 一一对应） | 新 | 1 |
| `port.InvoicePortImpl` | `spi.invoice.InvoicePort` 本地实现 | 内嵌形态 | 改 | 1 |
| `port.InvoicePrintSourceFactsPortImpl` | 打印事实 | — | 沿用 | — |
| `job.InvoiceTransmissionJob` | 每分钟上报 | — | 沿用 | — |
| `job.InvoiceRecoveryJob` | 每分钟恢复开立中 / 结果未知 | — | 新 | 2 |
| `job.NumberRangeWatermarkJob` | 号段低水位提醒 / 自动领号 | — | 新 | 2 |
| `job.InvoiceReconJob` | 每日对账 | — | 新 | 2 |
| `job.ReportDeadlineJob` | 上报期限告警（台湾 B2C 48 小时） | — | 新 | 2 |
| `client.InvoiceInternalApi` | interface | 独立形态内部契约（核心 → 发票）：`issue` / `activeOf` / `rememberBuyer` / `issueIfAutomatic` | 新 | 3 |
| `client.InvoiceInternalPaths` | 常量 | `/internal/invoice/*` 路径 | 新 | 3 |
| `client.InvoiceSourceInternalApi` | interface | 独立形态反向契约（发票 → 核心）：`load` / `sourcesOfBuyerOrder` | 新 | 3 |

### 2.8 `invoice-channel`（供应商层）

| 类 | 种类 | 职责 | 来源 | 阶段 |
|---|---|---|---|---|
| `channel.invoke.StandardProviderInvoker` | @Component implements `ProviderInvoker` | 管线入口：按能力表找实现，依次过拦截器 | 新 | 1 |
| `channel.invoke.ProviderInterceptor` | interface | 拦截器契约 | 新 | 1 |
| `channel.invoke.ValidationInterceptor` · `TraceInterceptor` · `IdempotencyInterceptor` · `TimeoutInterceptor` · `RetryInterceptor`（仅 QUERY/VERIFY） · `RecordingInterceptor` · `ClassifyInterceptor` | class | 管线各环节（形状同 `pay-channel` `StandardPipeline`） | 新 | 1 |
| `channel.invoke.ProviderRegistry` | @Component | 按供应商码收集 `InvoiceProvider`，启动时比对能力与种子表 | 新 | 1 |
| `channel.base.AbstractInvoiceProvider` | abstract class | 模板基类：final `execute`（凭据现取、按能力分发、异常归类、留痕）、`classify` | 新 | 1 |
| `channel.base.ResultCodeTable` | final class | 厂商结果码表（数据）：成功 / 已处理过 / 可重试 / 账户问题 / 终止 | 新 | 1 |
| `channel.base.AbstractSoapInvoiceProvider` | abstract class | SOAP 形态：信封、认证参数、`<return>` | 新（自 `CetustekSoapClient` 抽出） | 1 |
| `channel.base.AbstractHttpApiInvoiceProvider` | abstract class | HTTP 接口形态公共层：`path` / `seal` / `open` / `callApi` | 新 | 2 |
| `channel.base.AbstractEncryptedApiInvoiceProvider` · `AesCbcPayloadCipher` | abstract class / class | 报文整体 AES 加密（绿界、蓝新） | 新 | 2 |
| `channel.base.AbstractSignedApiInvoiceProvider` | abstract class | 明文 + 签名（光贸、大陆服务商，预留） | 新 | 2+ |
| `channel.base.AbstractFileExchangeInvoiceProvider` | abstract class | 文件交换形态：原子写文件、回执解析 | 新（自 `TurnkeyInvoiceGateway` 抽出） | 1 |
| `channel.base.AbstractNetworkInvoiceProvider` | abstract class | 网络接入点形态（Peppol，预留） | 新 | 3+ |
| `channel.tw.TwMigView` · `AbstractTwDocumentMapper` | class / abstract class | 台湾 MIG 报文类型化视图与各厂商共用的映射规则 | 新 | 1 |
| `channel.credential.SecretProviderCredentialAccess` | @Component implements `ProviderCredentialAccess` | 从 secret 模块现解凭据 | 迁移自 `InvoiceProviderAccounts` | 1 |
| `channel.http.RecordingProviderClient` | class | 发 HTTP、写调用日志（脱敏）与加密原文 | 新 | 1 |
| `channel.http.ProviderTransport` · `JdkProviderTransport` | interface / class | 传输层（可替换为测试桩） | 新 | 1 |
| `channel.cetustek.CetustekDocumentMapper` | class | MIG 树 → 鲸跃 Invoice / Allowance / 作废 XML | 拆自 `CetustekInvoiceGateway` | 1 |
| `channel.cetustek.CetustekInvoiceProvider` | @Component extends `AbstractSoapInvoiceProvider` | 实现开立 / 作废 / 折让 / 折让作废 / 查单 / 领号段 / 核验；内含结果码表（`S0/S2/C0/C5/A0/A6` …） | 改自 `CetustekInvoiceGateway`（`CetustekSoapClient` 并入形态抽象类） | 1（查单 2） |
| `channel.ecpay.EcpayDocumentMapper` | class | MIG 树 → 绿界 Issue / Allowance 请求；`RelateNumber` | 新 | 2 |
| `channel.ecpay.EcpayInvoiceProvider` | @Component extends `AbstractEncryptedApiInvoiceProvider` | `Issue` · `Invalid` · `Allowance/AllowanceInvalid` · `GetIssue` · 凭证探测 · `InvoicePrint`；外层 `TransCode`、AES-128 + URL Encode（`AesCbcPayloadCipher`） | 新 | 2 |
| `channel.ezpay.EzpayInvoiceProvider` · `channel.amego.AmegoInvoiceProvider` | extends 加密 / 签名接口抽象类 | 只定形态，协议待核实后实现 | 新 | 待定 |
| `channel.turnkey.TurnkeyMigWriter` | class | MIG 树 → XML、原子落文件 | 拆自 `TurnkeyInvoiceGateway` | 1 |
| `channel.turnkey.TurnkeyInvoiceProvider` | @Component extends `AbstractFileExchangeInvoiceProvider` | F0401 / F0501 / G0401 / G0501；回执解析 | 改自 `TurnkeyInvoiceGateway` | 1 |
| `channel.turnkey.TurnkeyReceiptScanner` | @Component | 扫回执目录 → `ProviderReceiptService` | 新 | 2 |
| `channel.stub.StubInvoiceProvider` | @Component extends `AbstractInvoiceProvider` | 实现全部能力，收下即成功（仅测试 / 沙箱） | 改自 `StubInvoiceGateway` | 1 |

### 2.9 `invoice-portal`（接入层，按资源命名 controller）

| 类 | 路径 | 权限 | 来源 | 阶段 |
|---|---|---|---|---|
| `api.biz.BizInvoiceController` | `/biz/invoices*` | `biz:invoice:read/issue/void` | 迁移 | 1 |
| `api.biz.BizInvoiceScope` | — | — | 迁移（产出 `InvoiceCaller`） | 1 |
| `api.biz.BizInvoiceProviderController` | `GET /biz/invoice-setup/providers` | `biz:invoice:setup` | 新 | 1 |
| `api.biz.BizInvoiceSellerController` | `/biz/invoice-setup/sellers*` | `biz:invoice:setup` | 拆自 `BizInvoiceSetupController` | 1 |
| `api.biz.BizInvoiceAccountController` | `/biz/invoice-setup/accounts*`（含 credentials / verifications / status / events / ranges） | `biz:invoice:setup` | 拆自 `BizInvoiceSetupController` | 1 |
| `api.biz.BizInvoiceBindingController` | `/biz/invoice-setup/bindings*` | `biz:invoice:setup` | 新 | 1 |
| `api.mp.MpSalesInvoiceController` | `/mp/sales-invoice*` | 登录 | 拆分 | 1 |
| `api.mp.MpInvoiceCarrierController` | `/mp/invoice-carrier*` | 登录 | 拆自 `MpSalesInvoiceController` | 1 |
| `api.ops.OpsInvoiceProviderController` | `/invoice-ops/v1/providers*` | `ops:invoice:provider` | 新 | 1 |
| `api.ops.OpsInvoiceSellerController` | `/invoice-ops/v1/sellers*` | `ops:invoice:account` | 新 | 1 |
| `api.ops.OpsInvoiceAccountController` | `/invoice-ops/v1/accounts*`（含 imports / management） | `ops:invoice:account` | 新 | 1 |
| `api.ops.OpsInvoiceBindingController` | `/invoice-ops/v1/bindings*` | `ops:invoice:account` | 新 | 1 |
| `api.auth.InvoiceRequestLogFilter` | 全部入口 | — | 写 `ivc_api_request_log`、处理 `Idempotency-Key` | 新 | 1 |

`/open/invoice/v1/*`（第三方开放 API）不在本期类清单，接入方式待定后再加 `api.open`。

### 2.10 `invoice-svc`（独立形态）

| 类 | 职责 | 阶段 |
|---|---|---|
| `svc.InvoiceServiceApplication` | Spring Boot 启动类；装 portal + domain + channel + secret-core | 3 |
| `svc.InvoiceSvcDataSourceConfig` | 连 `ai_shop_invoice`、Flyway `db/invoice` | 3 |
| `api.internal.InternalInvoiceController` | `/internal/invoice/*`，实现 `InvoiceInternalApi`（内部令牌） | 3 |
| `api.callback.InvoiceCallbackController` | `/callback/invoice/{provider}/{type}/{accountNo}` → `ProviderReceiptService` | 2（内嵌时由主应用装配） |
| `svc.RemoteInvoiceSourceClient` | `InvoiceSourcePort` 的远程实现 → 主应用 `/internal/invoice-sources/*` | 3 |
| `svc.BizNotifyDispatcher` | 投递 `ivc_biz_notify` 到主应用 | 3 |

### 2.11 `shop-base` / `shop-app`

| 类 | 职责 | 来源 | 阶段 |
|---|---|---|---|
| `ai.neargo.shop.spi.invoice.InvoicePort` · `InvoiceSourcePort` | 跨服务契约 | 沿用 | — |
| `ai.neargo.shop.spi.invoice.InvoiceIssuedEvent` · `InvoiceVoidedEvent` | 事件 record | 新 | 1 |
| `ai.neargo.shop.config.InvoiceDataSourceConfig` | 独立数据源 + `invoiceTxManager`；Mapper 扫 `invoice.mapper`，平台全局扫描排除 `invoice.*` | 改自 `InvoiceSchemaConfig` | 1 |
| `ai.neargo.shop.invoiceclient.InvoiceClientConfig` | 按 `shop.invoice.deployment` 装配本地 / 远程 | 新 | 3 |
| `ai.neargo.shop.invoiceclient.port.RemoteInvoicePortImpl` | `InvoicePort` 远程实现 → `invoice-svc` | 新 | 3 |
| `ai.neargo.shop.invoicebridge.port.SubOrderInvoiceSourcePortImpl` | 来源适配 | 沿用 | — |
| `ai.neargo.shop.invoicebridge.InvoiceAutoIssueConsumer` | 付款后自动开立 | 沿用 | — |
| `ai.neargo.shop.invoicebridge.api.internal.InternalInvoiceSourceController` | `/internal/invoice-sources/*`，供独立形态回读来源 | 新 | 3 |

---

## §3 测试与架构守卫

| 类 | 覆盖 | 来源 |
|---|---|---|
| `invoice-domain` `service.impl.InvoiceServiceMysqlTest` | MySQL 9 上的开立、作废、折让、取号、上报（沿用 24 例，按新服务拆分后迁移） | 改 |
| `invoice-domain` `service.impl.ProviderAccountMysqlTest` | 两种维护方的写权、凭据版本切换、核验失败保旧版本、接管 / 交还、事件 | 新 |
| `invoice-domain` `service.impl.ProviderOnlineNumberingMysqlTest` | 在线配号：成功 / 拒绝 / 超时 → 未知 → 恢复；未知期间来源单锁定 | 新 |
| `invoice-domain` `regime.tw.TwRulesTest` · `TwProofCodesTest` | 台湾规则 | 沿用 |
| `invoice-channel` `cetustek.CetustekInvoiceProviderTest` | 由 `CetustekInvoiceGatewayTest` 改名（本机假 SOAP） | 改 |
| `invoice-channel` `ecpay.EcpayCipherTest` | 官方测试向量逐字节一致 | 新 |
| `invoice-channel` `ecpay.EcpayInvoiceProviderTest` | 本机假服务：开立、查单、作废、折让、`TransCode` / `RtnCode` | 新 |
| `invoice-channel` `turnkey.TurnkeyMigWriterTest` | 由 `TurnkeyInvoiceGatewayTest` 改名 | 改 |
| `invoice-channel` `invoke.StandardProviderInvokerTest` | 管线顺序、仅查询类重试、记录不含凭据 | 新 |
| `invoice-domain` `InvoiceBoundaryTest` | 扩展：`invoice-*` 不依赖业务域与 `shop-app`；Mapper 只在 `invoice.mapper` | 改 |
| `shop-app` `arch.InvoiceArchitectureTest` | I3 编号分支只在 `policy.*Numbering`；I4 只有 `InvoiceOutcomeServiceImpl` 写终态；I5 能力类无密钥字段、只被 `ProviderInvoker` 调；`invoice-domain` 无 controller | 新 |
| `shop-app` `arch.InvoiceStandaloneAssemblyTest` | 独立形态装配能启动、走通一次开立 | 新（阶段 3） |
| `shop-app` `arch.BizEndpointPermTest` · `OpsEndpointPermTest` · `MpEndpointAuthTest` | 登记新端点的权限 | 改 |

---

## §4 前端清单

| 端 | 文件 | 内容 | 来源 |
|---|---|---|---|
| b-app | `pages/invoice-setup/index.vue` | 营业人卡片（统编、名称、QR 金钥状态）· 账户列表（供应商、代号、维护方、状态、凭据掩码）· 门店绑定 | 重写 |
| b-app | `pages/invoice-account/index.vue` | 新建 / 编辑账户：按 `credentialFields` 动态渲染表单；核验结果；事件列表；`editable=false` 时只读 | 新（需登记 `pages.json` 与界面清单） |
| b-app | `api/sales-invoice.ts` · `sales-invoice-http.ts` · `mocks/sales-invoice.ts` · `endpoints.ts` | 13 个设置接口 | 改 |
| ops-web | `app/invoice/providers/page.tsx` | 供应商与地区开通 | 新（加 `lib/nav.ts` 菜单） |
| ops-web | `app/invoice/accounts/page.tsx` | 账户查询、代建、轮换、接管 / 交还、暂停、批量导入 | 新 |
| ops-web | `app/invoice/sellers/page.tsx` | 营业人与门店绑定 | 新 |
| 共享 | `packages/shared/src/contract/enum-registry.ts` | 登记 `managedBy`、账户状态、凭据状态、编号方式、上报方式 | 改 |

新页面落地时按 CLAUDE.md 重跑 `python3 scripts/gen-ui-catalog.py` 并一并提交。

---

## §5 迁移脚本（`db/invoice`，只加不改）

| 脚本 | 内容 | 阶段 |
|---|---|---|
| `V6__invoice_master_and_accounts.sql` | 建 A 组 5 张、B 组 5 张；种子：地区 TW、供应商 4 家、能力、凭据字段 | 1 |
| `V7__invoice_documents_extend.sql` | `ivc_number_range` / `ivc_invoice` / `ivc_allowance` / `ivc_user_carrier` / `ivc_transmission` 加列与约束；`fiscal_number` 等改可空 | 1 |
| `V8__invoice_logs.sql` | 建 F 组 5 张新表与 `ivc_recon_diff` | 1 |
| `V9__invoice_migrate_profiles.sql` | `ivc_regime_profile` → 营业人 / 账户 / 凭据 v1 / 默认绑定；回填票据与任务的 `account_id`、`region_code`、`issue_status`、`report_status` | 1 |
| `V10__invoice_drop_regime_profile.sql` | 删 `ivc_regime_profile`（两个版本后、验证通过再执行） | 1+ |
| `V11__invoice_ops_permissions.sql` | 运营权限 `ops:invoice:account`、`ops:invoice:provider` 种子 | 1 |

`V9` 只搬引用不搬明文：原 `provider_secret_ref` 直接作为凭据 v1 的 `sec_key_id`，原 `qr_key_ref` 直接作为 `ivc_seller.qr_key_id`。

---

## §6 规模汇总

| 模块 | 沿用 | 改 / 迁移 / 拆分 | 新增 |
|---|---|---|---|
| `invoice-domain` | 10 | 17 | 约 45 |
| `invoice-channel` | 1 | 5 | 约 30 |
| `invoice-portal` | 0 | 4 | 8 |
| `invoice-svc` | 0 | 0 | 6 |
| `shop-base` / `shop-app` | 4 | 1 | 5 |
| 前端 | 10 | 5 | 5 页 |

按阶段：**阶段 1**（收拢 + 两种维护方式 + 鲸跃迁新接口）是主体；**阶段 2** 加绿界、恢复 / 对账 / 回执；**阶段 3** 独立部署。
