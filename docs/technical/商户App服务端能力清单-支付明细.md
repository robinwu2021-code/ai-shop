# 商户 App 收款 / 进件 / 资金 / 感应收款：ai-shop × near-pay 接口盘点

> 2026-11-08，只读盘点（没改文件，也没提交）。
> 对照基线：
> - ai-shop：ai-shop 仓库，分支 `codex/bapp-handoff-tdd`，HEAD `891008be6`；
> - near-pay：near-pay 仓库，分支 `develop/s1`，HEAD `993cf6b49`；
> - App 交接文档：ai-shop-bapp `docs/implementation/功能矩阵与API交接文档.md`，第 3 模块的在线付款部分，以及第 4、7、8、9 模块。
>
> 分类：**A** 已可用；**B** 有缺口，比如字段缺、台湾语义不对、错误码不对或只通了一半；**C** 没有。
> 下文的「ai-shop 路径」都相对 `backend/`，「near-pay 路径」都相对 `near-pay/backend/pay/`。

---

## §1 两系统分工与调用链

**分工**
- **ai-shop（shop-app）在支付这里只当 BFF。** 它负责认人（商家会话）、判权（`@perm.canBiz`）、定门店（`BizContext`，即 `X-Store-No`），以及门店准入登记（`ShopStoreMemberRoutes` 和 `CommerceRoutePolicy` 的 foundation 通道），然后把请求转发给支付服务。
  - 主体、入驻申请、资质、上传、商城订单仍归 ai-shop。
  - 外部记账（`/biz/payment/offline-records*`）按已冻结的 `TDD-商户App外部记账` 放在 ai-shop 的 trade 域，不进支付。
- **near-pay 是支付数据的真源。** 收单、退款、收款码、付款链接、平台商户与来源链接、渠道账户、进件、终端与 SoftPOS 执行、对账差异都在它那里。166 和生产都是独立形态（`shop.pay.deployment=standalone`）。
- **ai-shop 仓库里的 `backend/pay` 是内嵌形态和测试用的同包名副本。**
  - `PayBizInternalApi` 和 `InternalPayBizEndpoint` 两仓库**逐字相同**（diff 为 0 行）。
  - `PayBizFacade` 和 `MerchantPaymentQueryServiceImpl` 有小幅分叉（分别差 48 行、24 行，主要是 near-pay 改成 setter 注入、加了 `LocalPaymentStoreResolver` 和立牌链接）。
  - 迁移号两边各走各的：ai-shop `pay` 到 V16，near-pay 到 **V70**。

**调用链**

| App 入口 | ai-shop 控制器（权限码） | ai-shop 端口 / 客户端 | near-pay 端点 | near-pay 实现 |
|---|---|---|---|---|
| `/biz/payment/*`（收款 Tab、收款码、收银、链接、退款） | `shop-app/.../portal/biz/pay/BizPaymentController.java`（`PAY_ORDER_VIEW / PAY_STATEMENT / PAY_REFUND / PAY_QR / PAY_COLLECT`） | `payclient/BizPaymentAppService` → `RemoteBizPaymentAppService` | `/internal/pay/v1/biz/stores/{storeNo}/**`（`PayBizInternalApi.PREFIX`） | `pay-svc/.../svc/InternalPayBizEndpoint.java` → `pay-domain/.../pay/biz/PayBizFacade.java` |
| `/biz/merchant/pay-service`、`/apply`（申请开通收款） | `shop-app/.../portal/biz/BizPayServiceController.java`（`biz:finance`，foundation 通道） | `payclient/PaySourceLinkApi.java` | `POST /internal/pay/v2/source-links/apply`、`GET …/status` | `pay-svc/.../svc/platmch/InternalSourceLinkController.java` |
| `/biz/merchant/payment*`、`/pay-channel`（渠道进件） | `shop-app/.../portal/biz/BizMerchantController.java:738-826`（`biz:finance`） | 本地 `MerchantPaymentServiceImpl` + `mch_payment_merchant`，进件网关只有微信 / 支付宝 / Stub | **不调 near-pay** | —（near-pay 自有 `/biz/pay/v2/onboardings*`、`/internal/pay/v2/merchants/{m}/capability-progress`，ai-shop 没接） |
| `/biz/entities`、`/biz/entity/{e}`、`/biz/merchant/apply`、`/agreement/accept`、`/biz/qualifications*`、`/biz/upload/image` | `BizEntityController`、`BizMerchantController`、`BizCertController`、`shop-channel/.../BizUploadController` | 本地 | — | — |
| `/biz/stores/{s}/mall/orders/{o}/payments*`、`/refund-previews`、`/refunds` | `commerce-spring-boot-starter/.../CommerceBuyerMallHttpController.java` | `ShopMallPlatformPaymentAdapter` → `pay-domain` `PlatformCollectionServiceImpl`，直连 `payDataSource`（`ai_shop` 库） | **不经 near-pay** | — |
| `/biz/funds/*`（TDD 已冻结） | 待建 `BizFundsController`（`biz:finance`） | 待建 `BizFundsAppService`（远程实现） | 待建 `/internal/pay/v1/biz/stores/{storeNo}/funds/*` | 待建 `MerchantFundsQueryService` |
| `/biz/softpos/*`（App 建议契约） | 无 | 无 | near-pay 的设计走 `/terminal/pay/v2/*`，由设备签名加员工会话调用，并非经 ai-shop 转发 | `pay-portal/.../api/terminal/TerminalExecutionController.java` |

---

## §2 接口对照表

统计：共 **64** 行，其中 **A 26 行，B 18 行，C 20 行**。
- 按行计：`signRequired / signUrl` 字段和 `SOFTPOS` 来源值各单列一行；第 8、9 模块里引用别处的行（`/biz/merchant/payment`、`SOFTPOS`）也计入。
- 第 7 模块的 `pay-service` 两行不重复计。
- 去掉重复引用后，独立接口约 60 个。

### 第 4 模块 · 商户与进件（21 行）

| 接口 | 分类 | 缺在哪侧 | 缺口 | 要写的代码 | 缺的表 |
|---|---|---|---|---|---|
| `GET /biz/entities` | B | ai-shop | `EntityVO` 没有 `review / applyNo / rejectReason / taxNo / owner`（N4）；进度页和改挂选择列表没数据 | `BizEntityController`、`EntityVO` 加字段 | — |
| `GET /biz/entity/{entityNo}` | B | ai-shop | 同上（N4） | 同上 | — |
| `GET /biz/merchant/payment` | B | ai-shop | 读的是 ai-shop `mch_payment_merchant`（大陆微信 / 支付宝进件档）；台湾店没有蓝新 / LINE Pay 行，只回 `NONE` 占位（`MerchantPaymentServiceImpl.java:86-104`）。台湾渠道的真实进件与能力状态在 near-pay（`BizOnboardingController /biz/pay/v2/onboardings*`、`/internal/pay/v2/merchants/{m}/capability-progress`），ai-shop 没转 | 台湾门店改读 near-pay `capability-progress`（新 `PayOnboardingApi` 客户端），按 App 现有行形状映射；或由产品定 App 改读 `/biz/funds/accounts` 加 `pay-service` | — |
| `GET /biz/merchant/pay-channel` | B | ai-shop | 同上，台湾无行 | 同上 | — |
| `POST /biz/merchant/payment`（进件 / 补件） | B | ai-shop | 进件网关只有 `Wechat / Alipay / Stub ApplymentGateway`，蓝新、LINE Pay 会回 70045；台湾进件的正路是 `pay-service/apply`，再由 near-pay 运营审核 | 台湾市场拦截并引导到 N3 或 `pay-service`；或转 near-pay onboarding（要 near-pay 先开 internal 版） | — |
| `POST /biz/merchant/payment/store/{storeNo}` | B | ai-shop | 同上，台湾无意义 | 同上 | — |
| `POST /biz/merchant/payment/{payChannel}/refresh` | B | ai-shop | 同上；N2 的 `source=BIND` 行刷新要改查 near-pay 认领状态（TDD B5） | `MerchantPaymentServiceImpl#refresh` 分支 | — |
| `signRequired / signUrl`（渠道行字段） | B | ai-shop | near-pay 进件单有 `signUrl`（`pay-portal/.../ops/v2/OpsOnboardingDtos.java:20-23`），ai-shop 没转出来 | 随上面的 near-pay 进度接入一并映射 | — |
| `POST /biz/store/{storeNo}/payment` | A | — | 能用；但台湾没有 `mch_payment_merchant` 行可选，实际只对大陆有意义 | — | — |
| `POST /biz/store/{storeNo}/entity` 加预检 `GET …/entity/relink-check`（N1） | C | ai-shop | 没有端点；ADR-055 限定占位认领，near-pay 零改动（靠门店同步首次建关联） | 两个端点、改挂服务、`StoreRelinkGuardPort`（订单域与商城订阅各一个实现）、`PaySourceLinkApi#status` 守卫；登记 `ShopStoreMemberRoutes` 或 foundation 通道 | — |
| `POST /biz/merchant/payment/bind`（N2） | C | **两边** | ai-shop 没有；near-pay 只有运营建渠道账户（`OpsChannelAccountController`），商户端 `BizChannelAccountController` 只能列表和绑门店 | ai-shop：`MerchantChannelBindService`、`PaySourceLinkApi#claimChannelAccount / claimStatus`。near-pay：`POST /internal/pay/v2/source-links/channel-account-claims` 和 `GET …/{claimRef}`（ADR-056） | ai-shop：`mch_payment_merchant` 加 `source`、`channel_merchant_masked`（V6xx，ai-shop 当前最大 **V636**）。near-pay：认领单表（设计里没有，要新设计） |
| `POST /biz/merchant/onboarding`（N3） | C | **两边** | ai-shop 没有编排端点；near-pay `source-links/apply` 的 `EntityProfileInput` 没有 `settlement`（`pay-svc/.../platmch/InternalSourceLinkController.java:85-87`）；`materials[].ref = file:<assetKey>` near-pay 只做格式校验（`ProfileMasking.java`），运营端取不到图 | ai-shop：新控制器、编排服务、`OpsService#reviseMerchantApply`、`PayServiceApplyRequests.Form` 加可选字段、修订号刷新与 409 重发；foundation 通道登记 `POST /biz/merchant/onboarding`。near-pay：`profile.settlement` 透传与落库（掩码 + 密文），`file:` 引用取图（回源 ai-shop 媒体或拷贝） | near-pay：结算账户的存放位置待定（设计里的 `pay_settlement_rule_revision` 批次是「按专项阶段」，不在 S1） |
| `GET /biz/merchant/apply` | A | — | — | — | — |
| `POST /biz/merchant/apply` | A | — | — | — | — |
| `POST /biz/merchant/agreement/accept` | A | — | — | — | — |
| `GET /biz/merchant/pay-service` | A | — | 166 已联调（`InternalSourceLinkController.STATUS_PATH`） | — | — |
| `POST /biz/merchant/pay-service/apply` | A | — | 两侧都有；**166 未验证 apply** | — | — |
| `GET /biz/qualifications` | A | — | — | — | — |
| `POST /biz/qualifications/save` | A | — | — | — | — |
| `POST /biz/qualifications/recognize` | A | — | — | — | — |
| `POST /biz/upload/image` | A | — | — | — | — |

### 第 7 模块 · 收款与收银（18 行；`pay-service` 两行已在第 4 模块计过）

| 接口 | 分类 | 缺在哪侧 | 缺口 | 要写的代码 | 缺的表 |
|---|---|---|---|---|---|
| `GET /biz/payment/collect-status` | A | — | 两侧 `PayBizInternalApi` 与 `InternalPayBizEndpoint` 逐字一致 | — | — |
| `GET /biz/payment/orders` | A | — | 11/06 near-pay `d022cb2f1` 修过 166 上的列表与汇总 | — | — |
| `GET /biz/payment/orders/summary` | A | — | 手续费估算用配置 `estimate-rate-bps`（默认 0），和费率表不一致（资金 TDD D5） | （随资金一期改） | — |
| `GET /biz/payment/orders/lookup` | A | — | — | — | — |
| `GET /biz/payment/orders/{payOrderNo}` | A | — | — | — | — |
| `POST /biz/payment/orders/{payOrderNo}/query` | A | — | — | — | — |
| `POST /biz/payment/refund` | A | — | — | — | — |
| `GET /biz/payment/qr-code` | A | — | near-pay `2342025c8` 起回 `storeCodeUrl`（`/h5/s/{storeCode}`，V66） | — | — |
| `POST /biz/payment/qr-code` | A | — | — | — | — |
| `POST /biz/payment/collection` | A | — | — | — | — |
| `GET /biz/payment/links` | A | — | 166 已联调 | — | — |
| `POST /biz/payment/links/{payOrderNo}/reissue` | A | — | — | — | — |
| `POST /biz/payment/links/{payOrderNo}/void` | A | — | — | — | — |
| 收款来源 `source=SOFTPOS` | B | **两边** | near-pay `MerchantPaymentQueryServiceImpl#sourceKind` 只有 `QR_CODE / COLLECT / POS / ORDER`，SoftPOS 单（`source_type=TERMINAL`）会归成 `POS` | near-pay：按终端类型 / 产品 `NEWEBPAY_SOFTPOS_CARD_ONETIME` 出 `SOFTPOS`，`applySources` 加分支；ai-shop `backend/pay` 同步 | — |
| `GET /biz/payment/offline-records` | C | ai-shop | 没有（外部记账 TDD 已冻结，归 trade 域） | 控制器、服务、Mapper；登记 `ShopStoreMemberRoutes` | `ord_offline_record`、`ord_offline_record_event`（V6xx，**TDD 写的「当前最大 V635」已过期，实际是 V636**） |
| `POST /biz/payment/offline-records` | C | ai-shop | 同上 | 同上 | 同上 |
| `POST /biz/payment/offline-records/{recordNo}/correct` | C | ai-shop | 同上 | 同上 | 同上 |
| `POST /biz/payment/offline-records/{recordNo}/void` | C | ai-shop | 同上 | 同上 | 同上 |

> 横向小缺口（B，ai-shop）：`/biz/payment/*` 在 near-pay 连不上时会落到兜底 10500，不是 10505（资金 TDD §1.5-4，`RemoteBizPaymentAppService#unwrap` 只翻译对端回了的码）。

### 第 8 模块 · 资金（9 行）

| 接口 | 分类 | 缺在哪侧 | 缺口 | 要写的代码 | 缺的表 |
|---|---|---|---|---|---|
| `GET /biz/funds/overview` | C | **两边** | 都没有 | ai-shop：`BizFundsController`、`BizFundsAppService`（远程 + 内嵌）、新错误码 `FUNDS_ENTITY_TOO_MANY_STORES`（三语）、`ShopStoreMemberRoutes` 一行。near-pay：`MerchantFundsQueryService#overview`、`PayBizInternalApi` 与 `InternalPayBizEndpoint` 加 `/funds/overview`；`estimatedPaidOutMinor`（D10-B） | 一期 A：无 |
| `GET /biz/funds/daily` | C | **两边** | 都没有；`/biz/payment/statement` 只给单日，上限 1000 条 | 同上 `#daily`；`scope=ENTITY` 的扇出在 ai-shop | 一期 A：无 |
| `GET /biz/funds/payouts` | C | **两边** | 都没有；一期只能出 `ESTIMATED` 行 | 同上 `#payouts`；一期 B 读渠道拨款 | 一期 B：`pay_channel_payout`（near-pay 设计里没有） |
| `GET /biz/funds/reconcile` | C | **两边** | 都没有；`stl_recon_diff` 没有门店列 | 同上 `#reconcile`（经 `payment_no` 反查 `pay_order.store_id`） | 一期 B：`pay_channel_bill`、`pay_channel_bill_line`、`pay_reconciliation_run`；`stl_recon_diff` 加 `store_id` |
| `GET /biz/funds/rates` | C | **两边** | 都没有；near-pay 迁移里**没有台湾 `sys_pay_channel_rate` 种子**（该表只来自旧基线） | 同上 `#rates`（`PayChannelRateService#effective` + `PayChannelMasterService#enabled`） | 费率数据（运营配，或者写种子迁移） |
| `GET /biz/funds/accounts`（评审后新增，F06） | C | **两边** | 都没有 | 同上 `#accounts`（`pay_merchant_binding` → `pay_merchant`） | — |
| F06 原用的 `GET /biz/merchant/payment` | B | App 与 ai-shop | 读错表（大陆进件档），台湾无行；TDD 已改为 `accounts` + `pay-service` | App 改调用 | — |
| F02 / F04 明细 `GET /biz/payment/orders?from&to` | A | — | 复用 | — | — |
| F02 现成替代 `GET /biz/payment/statement` | B | — | 只给单日、没有手续费，不能替代 daily | 不用 | — |

### 第 9 模块 · 感应收款（8 行）

| 接口 | 分类 | 缺在哪侧 | 缺口 | 要写的代码 | 缺的表 |
|---|---|---|---|---|---|
| `GET /biz/softpos/devices` | C | **两边（契约不对齐）** | ai-shop 没有。near-pay 没有这个形状，但有等价能力：`GET /biz/pay/v2/terminals?storeNo=`（`BizTerminalController`）、运营 `/pay-ops/v2/terminals/{t}/softpos-bindings` | 先定对接形态（§6 R1）。走 ai-shop 转发就要 near-pay 开 internal 版终端列表，ai-shop 新建 `BizSoftPosController` | near-pay V58 `pay_terminal_*` 已有 |
| `POST /biz/softpos/devices`（申请开通本机） | C | **两边** | near-pay 的设备由运营登记绑定和资格审批（`SoftPosBindingService`），另有 `POST /biz/pay/v2/terminal-activation-codes`；没有「商户自助申请」 | near-pay：商户申请单（或复用激活码），ai-shop 转发 | 可能要新增申请表 |
| `POST /biz/softpos/devices/{deviceId}/unbind` | C | **两边** | near-pay 有运营 `/terminals/{t}/lost / disable / retire`，商户端没有 | near-pay internal 端点，ai-shop 转发 | — |
| `POST /biz/softpos/charges`（回报成功） | C | **两边（模型冲突）** | near-pay 设计是先建任务、领取、授权，再上报事件：`/terminal/pay/v2/payments/{orderNo}/device-executions`、`…/claim`、`…/start`、`…/events`（`TerminalExecutionController`），设备上报**只记待核实，不入账**。App 的「凭 `txnNo` 回报一笔」不在这条链上 | App 改走 near-pay 终端链（推荐），或 near-pay 加适配端点 | — |
| `GET /biz/softpos/charges/{txnNo}` | C | **两边** | near-pay 有 `GET /terminal/pay/v2/device-executions/{taskNo}`，按任务号查，不按 `txnNo` | 同上 | — |
| 9-11 退款 `POST /biz/payment/refund` | B | near-pay | 通用退款可用；SoftPOS 退货登记（TW-S05）和当日撤销没实现，要等厂商 Q7 | near-pay TW-S05 | — |
| 9-10 `?source=SOFTPOS` | B | 两边 | 见第 7 模块 `SOFTPOS` 行 | — | — |
| 9-01 开通前提 `GET /biz/merchant/payment` | B | ai-shop | 同第 4 模块：台湾看不到 NEWEBPAY 状态 | 同第 4 模块 | — |

### 第 3 模块 · 商城在线付款与退款（App 未接）（8 行）

| 接口 | 分类 | 缺在哪侧 | 缺口 | 要写的代码 | 缺的表 |
|---|---|---|---|---|---|
| `POST /biz/stores/{s}/mall/orders/{o}/payments` | B | **两边** | ai-shop 端点在（`CommerceBuyerMallHttpController:138`），但 `PlatformCollectionServiceImpl` 注释写明统一线渠道 handler 都没声明 `platformCollectionCapability`，**在线路由恒 `ROUTE_UNAVAILABLE`，只有线下转账能用**；而且它直连 `payDataSource`（`ai_shop` 库），独立形态下也不经 near-pay。near-pay 没有平台代收（`PlatformCollection*`）的对应物 | 先定商城平台代收归属（§6 R5）：near-pay 提供平台商户代收的 internal API，ai-shop 改成远程端口；或把 handler 移植到 ai-shop 内嵌线 | 视方案而定 |
| `GET …/payments`、`POST …/payments/refresh` | B | 同上 | 同上 | 同上 | — |
| `POST …/refund-previews` | B | ai-shop | 预览本身可用（商城域）；在线原路退依赖上面的代收 | — | — |
| `POST …/refunds` | B | 同上 | 线下单可走人工退款付款（`CommerceOfflineOpsHttpController …/payouts`）；在线退要等代收 | 同上 | — |
| `POST …/cancel` | A | — | 商城域，不依赖支付 | — | — |
| `POST /biz/stores/{s}/mall/upgrade-quotes` | A | — | 商城域 | — | — |
| `POST …/component-refunds`（App 自拟） | C | ai-shop（商城域） | 后端没有；要和 `/refunds`（带 `sourcePeriodNos`）二选一 | 建议不做，App 改用 `/refunds` | — |
| `GET /biz/stores/{s}/usage` | C | ai-shop（商城 / 权益域） | 后端没有，与支付无关 | — | — |

---

## §3 TDD 要求 near-pay 新增项核对表

near-pay 迁移实际最大号是 **V70**（`pay-domain/src/main/java/db/pay/V70__qualification_single_operator.java`）。SQL 迁移在 `pay-domain/src/main/resources/db/pay/`，共 67 个；Java 迁移 3 个（V56、V60、V70）。**下一个可用号是 V71**，但 Google Pay（`NEWEBPAY_GOOGLEPAY`）、SoftPOS 启用和收款链接表**三份交付文档都写了「V71 起」**（`docs/delivery/32-…/Google-Pay-接入信息.md`、`16-…/SoftPOS-接口与任务.md`、`32-…/方案.md`；提交 `993cf6b49`）。

| # | TDD 要求 | 出处 | near-pay 现状 | 判定 |
|---|---|---|---|---|
| 1 | `/internal/pay/v1/biz/stores/{storeNo}/funds/{overview\|daily\|payouts\|reconcile\|rates\|accounts}` | 资金 TDD §2.1 | `PayBizInternalApi`（`pay-domain/.../client/PayBizInternalApi.java`）只有 app-grants、collections、qr-codes、refunds、statement、payment-orders、collect-status、payment-links；全仓没有 `funds` 端点。`pay-domain/.../pay/funds/` 包里只有资金路径策略（`FundsPathStrategy` 等）和 `BizDates` | **完全没有** |
| 2 | `MerchantFundsQueryService`（同名查询加测试） | 资金 TDD §4、ADR-052 D2 | 无 | **完全没有** |
| 3 | `pay_channel_bill` | 资金 TDD §2.1、ADR-052 | `docs/database/design-v2/00-数据库表清单.md:237` 写「拟新增，待实现，S1 台湾首期：渠道账单与对账」；代码和迁移里都没有 | **设计有，未实现** |
| 4 | `pay_channel_bill_line` | 同上 | 同上（`:238`） | **设计有，未实现** |
| 5 | `pay_reconciliation_run` | 同上 | 同上（`:242`）；运营端 `/pay-ops/v2/channel-bills`、`/reconciliations` 只在 `docs/api/handbook/07-S1台湾接口切片.md:28` 里，代码没有；账单格式「待联调」（`:37`） | **设计有，未实现** |
| 6 | `pay_channel_payout`（渠道拨款） | 资金 TDD §2.1（「near-pay 设计里没有，由 near-pay TDD 定」） | design-v2 里没有；只有 `stl_payout`（平台放款，语义不同）和 `pay_fund_account*`（按专项阶段） | **完全没有（设计也没有）** |
| 7 | `stl_recon_diff.store_id` | 资金 TDD §2.1、§6 | 表在（`pay-domain/.../entity/StlReconDiff.java`，V3 加了 `dedup_key / note`）。design-v2 `09-…md:836-845` 的「本次增量」只有 `reconciliation_run_id / channel_bill_line_id / assignee_no / adjustment_approval_id`，**没有 `store_id`** | **完全没有（设计也没列）** |
| 8 | 渠道账单下载能力（`StatementCapability` 真实实现） | 资金 TDD §1.1 | `pay-channel/.../capability/StatementCapability.java` 只有 `mockpay/MockpayChannel.java` 实现；`newebpay / linepay / jkopay` 都没有 | **完全没有** |
| 9 | 台湾费率 `sys_pay_channel_rate`（D5 估算改读费率表） | 资金 TDD D5 | 实体和服务都在（`pay-channel/.../entity/SysPayChannelRate.java`、`PayChannelRateServiceImpl`）；near-pay 迁移里没有这张表的 DDL，也没有台湾种子，表只来自旧基线 | **代码有，数据没有（要运营配或写种子）** |
| 10 | 渠道账户认领 `POST /internal/pay/v2/source-links/channel-account-claims` + `GET …/{claimRef}` | 资料提交 TDD §2 N2、ADR-056 | 没有。现有的是运营 `OpsChannelAccountController`（建、`verify`、`activate`…）和商户 `BizChannelAccountController`（只列表加绑门店）；渠道账户表 V26 已有 `source / evidence_ref / verification` 字段可复用 | **完全没有** |
| 11 | `source-links/apply` 的 `profile` 加可选 `settlement` | 资料提交 TDD §2、已拍板 4 | `InternalSourceLinkController.EntityProfileInput` 只有 `purpose / legalName / registrationNo / registeredAddress / … / materials`；`materials.kind` 里有 `BANK_ACCOUNT`（可作为附件，但不是结构化账户） | **完全没有** |
| 12 | `materials[].ref = file:<ai-shop assetKey>` 运营端取图 | 资料提交 TDD §2、§6 | `ProfileMasking.java` 只校验正则 `^(file\|cipher):…`，没有回源 ai-shop 媒体的解析器 | **完全没有** |
| 13 | near-pay 门店投影：改挂后「无关联 → 按新主体建关联」 | ADR-055 | 已有（`pay-domain/.../platmch/store/StoreProjectionService.java` 32、202-216 行） | **已有，不用改** |
| 14 | 运营核实已有端点 `POST /pay-ops/v2/channel-accounts/{payMerchantNo}/verify` | ADR-056 | 已有（`OpsChannelAccountController.java:116`） | **已有** |
| 15 | `pay_settlement_rule_revision`（结算配置版本，D6 账期词表统一的落点） | 资金 TDD D6（间接） | design-v2 `00-…md:243` 写「拟新增，待实现，按专项阶段」（不在 S1） | **设计有，未实现（不在 S1）** |

---

## §4 渠道进度（near-pay）

| 渠道 / 方式 | 接法 | 代码 | 生产 | 主要待办 | 出处 |
|---|---|---|---|---|---|
| **蓝新 MPG 信用卡 / TWQR** | MPG 托管页 | `pay-channel/.../newebpay/NewebPayMpgChannel.java` | 在用（台北早餐，蓝新 `TRL00007919`） | 信用卡**当日取消授权**没实现，Apple Pay 和 Google Pay 退款都依赖它 | `docs/delivery/32-蓝新支付方式扩展/LINE-Pay-Apple-Pay-Google-Pay-进度.md` §5 |
| **Apple Pay** | 蓝新 Apple Pay 网页直连（UME CreditCard 1.1） | `newebpay/applepay/*`，迁移 V62–V65 | **已上线**；授权成功即入账；4 笔历史单都已入账；台北早餐 11-07 开通 | 当日取消授权；一笔新单的真实小额验证；运营端标出「从未提交到蓝新」的单 | 同上 §3 |
| **Google Pay** | 蓝新 MPG `ANDROIDPAY=1` | **未写代码**（`NewebPayMpgChannel.java:53` 把 `ANDROIDPAY` 显式置 0） | 无 | 产品 `NEWEBPAY_GOOGLEPAY`（V71 起）；回传校验（含 `GOOGLEPAY_FOREIGN`）；H5 方式；向蓝新确认手册 v2.3、开通、测试商店 | 同上 §4；`Google-Pay-接入信息.md` |
| **LINE Pay** | LINE Pay Online v3 直连（不经蓝新） | `pay-channel/.../linepay/*`（Request / Confirm / Check / Refund） | 已上线，**但没有成功闭环**：11-05 起建付款页 22 次，3 次连接失败，**Confirm 0 次** | 查 Confirm 为什么没发生（跳回 → H5 → 后端三段日志）；166 到 `api-pay.line.me` 间歇超时（抽测 14/40 失败）；补查和退款在生产验证 | 同上 §2；`16-…/LINE-Pay-共享测试部署与实测-2026-11-04.md` |
| **街口 JKOPAY** | OnlinePay（H5 聚合）；另有 TWQR POS | `pay-channel/.../jkopay/*`、`pay-domain/.../jkopay/JkoPayOnlineReconciliation.java`，迁移 V51、V54 | **默认关闭**；没有 OnlinePay 测试商户，没真实建单；POS UAT 只做过 Inquiry（804 / 916） | 拿 OnlinePay 凭据；真实通知；固定 TWQR 立牌通知合同没证实。**App 交接文档说「JKOPAY 后端无代码」不准确：near-pay 有代码，只是没开** | `16-…/街口支付接入交接-2026-11-04.md` §1 |
| **SoftPOS（蓝新简单收）** | B 端 App 以 App-to-App 唤起 ezPay SoftPOS App（仅 Android，MineSec poslib）；回传先记待核实，人工或核实适配器确认后才入账 | 框架在：V58（`pay_terminal_payment_binding / execution_slot / execution_task`）、V59 产品 `NEWEBPAY_SOFTPOS_CARD_ONETIME`（**DISABLED**）、V60、V61 设备事件；`/terminal/pay/v2/device-executions*`、运营 `softpos-bindings` | **整体关闭** | 产品启用（V71 起）；运营端登记和审批界面；B 端原生插件（S02）；待核实列表与人工入账、退货登记（TW-S05）；自动核实（S07，要等厂商 Q6）；厂商待答 Q1–Q7（包名签名、poslib 字段、MID / TID、Maven 令牌、核实方式、退货） | `16-…/SoftPOS-接口与任务.md` §0、§8–§10；`32-…/SoftPOS与ezAIO-资料对齐.md` |

> 结论：App 第 9 模块的 `/biz/softpos/*` 建议契约（设备申请、凭 `txnNo` 回报成功）**和 near-pay 已定的执行模型不一致**。near-pay 那边是：任务一次授权，事件上报只记待核实，由设备签名加员工会话直连 `/terminal/pay/v2`。App 侧要按 near-pay 文档重写 `SoftPosTerminal` 的后端交互，不适合让 ai-shop 照 App 契约另造一套。

---

## §5 要补清单

### near-pay 侧

| 类型 | 项 | 优先级 | 说明 |
|---|---|---|---|
| 新接口 | `/internal/pay/v1/biz/stores/{storeNo}/funds/{overview,daily,payouts,reconcile,rates,accounts}`，加上 `PayBizInternalApi`、`InternalPayBizEndpoint` 方法和 `MerchantFundsQueryService` 与测试 | **P0** | 资金一期 A 的硬依赖；`PayBizInternalApi` 要和 ai-shop 保持逐字一致 |
| 新接口 | `POST /internal/pay/v2/source-links/channel-account-claims`、`GET …/{claimRef}` | **P1** | N2；ADR-056 |
| 改接口 | `source-links/apply` 的 `profile` 加可选 `settlement{bankCode, accountName, accountNumber}`（只存掩码加密文；不计入事件号摘要） | **P0**（N3 的第四步）／赶不上就按已拍板 4 后补 | 资料提交 TDD 已拍板 4 |
| 新代码 | `file:<assetKey>` 材料取图（回源 ai-shop 媒体，或 apply 时拷贝） | **P1** | 不做的话运营审核看不到证照 |
| 改代码 | `MerchantPaymentQueryServiceImpl#sourceKind / applySources` 加 `SOFTPOS` | **P1** | 随 SoftPOS 启用 |
| 新接口 | 商户端或 internal 版的进件进度：已有 `/internal/pay/v2/merchants/{m}/capability-progress`，核对字段够不够 App 渠道行（`applyStatus / canReceiveMoney / signUrl / settleAccountMasked / settleCycle`） | **P1** | 让 ai-shop 的 `/biz/merchant/payment` 在台湾有真数 |
| 新接口 | SoftPOS 商户自助：设备列表、申请、挂失的 internal 版（或确认 App 直连 `/biz/pay/v2/terminals`） | **P2** | 先定对接形态（§6 R1） |
| 新表 | `pay_channel_bill`、`pay_channel_bill_line`、`pay_reconciliation_run`（按 design-v2 定义） | **P1**（资金一期 B） | 要先拿到蓝新和 LINE Pay 的账单格式 |
| 新表 | `pay_channel_payout`（渠道拨款，要先写设计） | **P1**（一期 B） | design-v2 里没有 |
| 新表 | 渠道账户认领单（或复用 `pay_channel_account` 的 `source / evidence_ref` 加状态） | **P1** | N2 |
| 改表 | `stl_recon_diff` 加 `store_id`，以及 design-v2 的 `reconciliation_run_id / channel_bill_line_id / assignee_no / adjustment_approval_id` | **P1** | 一期 A 可先反查 `pay_order` |
| 新代码 | `StatementCapability` 的蓝新和 LINE Pay 实现 | **P1** | 一期 B |
| 新代码 | 信用卡当日取消授权（Apple Pay、Google Pay 退款前置） | **P0**（Apple Pay 已上线，退款受限） | 进度文档 §5 |
| 新代码 | Google Pay：产品迁移、MPG 开关、回传校验、H5 | **P2** | 先等蓝新确认 |
| 新代码 | LINE Pay：Confirm 没发生的排查、出口网络 | **P0**（已上线但 0 成功） | 进度文档 §2.3 |
| 新代码 | SoftPOS：TW-S02 到 S05（插件对接、运营登记审批、待核实入账、退货登记）加产品启用迁移 | **P1** | 厂商 Q1–Q5、Q7 先关 |
| 配置 | 台湾 `sys_pay_channel_rate` 费率（NEWEBPAY、LINEPAY × 方式） | **P0**（资金上线前） | D5 |
| 配置 | 账期词表统一 `T1…Tn / WEEKLY / SEMI_MONTHLY / MONTHLY`（`PayChannelMasterService#settleCycle` 注释与默认值） | **P1** | D6 |
| 配置 | 迁移号统一分配：V71 起同时被 Google Pay、SoftPOS 和收款链接三处认领，资金和账单表也要号 | **P0**（开工前） | §6 R3 |

### ai-shop 侧

| 类型 | 项 | 优先级 | 说明 |
|---|---|---|---|
| 新接口 | `GET /biz/funds/{overview,daily,payouts,reconcile,rates,accounts}`（`BizFundsController`，`biz:finance`；接住 `ServiceCallException` 转 10505） | **P0** | 资金 TDD |
| 端口 | `BizFundsAppService`（远程实现，以及按 D2-A 的内嵌实现）；`backend/pay` 镜像 `MerchantFundsQueryService`、`PayBizInternalApi` 和 `InternalPayBizEndpoint` 方法 | **P0** | 两仓库同形 |
| 路由登记 | `ShopStoreMemberRoutes.ALL` 加 `HostRoute.of("GET", "/biz/funds/(?:overview\|daily\|payouts\|reconcile\|rates\|accounts)")` | **P0** | 不加的话新模型门店会 70006 |
| i18n | 错误码 `FUNDS_ENTITY_TOO_MANY_STORES`（三语） | **P0** | — |
| 新接口 | `POST /biz/merchant/onboarding`（N3）加编排服务和 `OpsService#reviseMerchantApply` | **P0** | 登记在 **foundation 通道**（`CommerceRoutePolicy` 的 `case`），**不要**登记 `ShopStoreMemberRoutes` |
| 改接口 | `GET /biz/entities`、`GET /biz/entity/{e}` 加 `review / applyNo / rejectReason / taxNo / owner`（N4） | **P0** | — |
| 新接口 | `POST /biz/store/{s}/entity`、`GET …/entity/relink-check`（N1）加守卫端口 | **P1** | ADR-055 |
| 新接口 | `POST /biz/merchant/payment/bind`（N2）、`PaySourceLinkApi#claimChannelAccount / claimStatus` | **P1** | 依赖 near-pay 认领端点 |
| 改表 | `mch_payment_merchant` 加 `source`、`channel_merchant_masked`（V6xx，当前最大 V636） | **P1** | N2 |
| 改端口 | `PaySourceLinkApi.Profile` 加 `settlement`；`PayServiceApplyRequests.Form` 加可选字段（事件号摘要只算原来的五个字段） | **P0** | 和 near-pay 同时发 |
| 改接口 | `/biz/merchant/payment*` 在台湾市场改读 near-pay 进度（或拦截加引导），并转出 `signRequired / signUrl` | **P1** | 现在台湾恒为 `NONE` 占位 |
| 新接口 | `/biz/payment/offline-records*` 四个端点 | **P0**（交接清单 3-4） | trade 域，不进支付 |
| 新表 | `ord_offline_record`、`ord_offline_record_event` | **P0** | 号从 V637 起排，TDD 的「V635」要更正 |
| 路由登记 | `ShopStoreMemberRoutes` 加 `/biz/payment/offline-records(?:/.*)?`：现有的 `/biz/payment(?:/.*)?` 已经能匹配，**只需确认不被误放行**；新端点的权限码按外部记账 TDD 定 | **P1** | — |
| 改代码 | `RemoteBizPaymentAppService`：near-pay 不可达时转 10505，不落兜底 10500 | **P2** | §1.5-4 |
| 新接口 | `/biz/softpos/*`：**建议不建**，先按 §6 R1 定形态 | **P2** | — |
| 改代码 | 商城平台代收的远程化（在线 `payments` 和原路退款） | **P2**（App 未接） | §6 R5 |

---

## §6 风险与待拍板

| # | 风险 / 待拍板 | 影响 | 建议 |
|---|---|---|---|
| R1 | **SoftPOS 对接形态**：App 契约 `/biz/softpos/*`（经 ai-shop、凭 `txnNo` 回报）和 near-pay 的 `/terminal/pay/v2` 执行链（设备签名、任务一次授权、上报只记待核实）不一致 | 两边各写一套会出现双入口、重复入账风险 | 拍板：App 直连 near-pay 终端链（推荐，near-pay 已有），ai-shop 只出设备列表视图；或 ai-shop 做转发层但路径和语义照 near-pay。厂商 Q1–Q7 没答之前不排后端 |
| R2 | **跨仓库发布顺序**：资金、N3 的 `settlement`、N2 认领都要「near-pay 先、ai-shop 后」 | ai-shop 先上，166 / 生产会调到不存在的 internal 路径：资金 → 10505（按 TDD 处理还算体面）；N3 若把 `settlement` 送给旧 near-pay，取决于 Jackson 是否忽略未知字段，**要核实**，否则 400 → `payService.status=UNAVAILABLE`；N2 → 10505 | 定发布顺序：① near-pay 发版（新端点、`profile.settlement` 可选、迁移），② ai-shop 发版，③ App 去掉 `contracts.dart` 的拦截。ai-shop 侧用 `PayBizInternalApiRoundTripTest` 那类契约测试锁住路径；near-pay 也要有同名测试 |
| R3 | **near-pay 迁移号冲突**：V71 同时被 Google Pay、SoftPOS 启用和收款链接表三份文档认领；资金一期 B 的账单、拨款、认领表也要号；多会话并行 | Flyway 号冲突，或者一边改号导致 checksum 失败 | 开工前在 near-pay 建一份号段登记（例如 V71 SoftPOS、V72 Google Pay、V73+ 账单 / 认领），各会话按登记取号 |
| R4 | **两份 pay 代码分叉**：`PayBizFacade` 和 `MerchantPaymentQueryServiceImpl` 已经分叉；资金查询按 D2-A 要两边各写一份 | 本地测试通过而 166 不同；后续修复只进一边 | 资金查询先写 near-pay，再逐字拷回 ai-shop；`PayBizInternalApi` 和 `InternalPayBizEndpoint` 加一道 diff 闸门（CI 比较两仓库同名文件） |
| R5 | **商城平台代收不经 near-pay**：`PlatformCollectionServiceImpl` 直连 `payDataSource`（`ai_shop` 库），在线路由恒 `ROUTE_UNAVAILABLE` | 商城在线付款上线时会和 near-pay 的收单、对账各算各的（违背 ADR-021 / 043 的方向）；线下转账钱账也在 ai-shop 库 | 拍板商城平台代收归 near-pay（平台商户收单 internal API），还是维持 ai-shop 内嵌只做线下；App 第 3 模块在线付款暂不接 |
| R6 | **台湾渠道进件两套入口**：`/biz/merchant/payment*`（ai-shop 大陆进件档，台湾无行）和 `pay-service` / N3（near-pay） | App 4-02、4-03、9-01、F06 在台湾一律显示「未开通」；同一主体可能出现两份资料 | 拍板：台湾只认 near-pay（`pay-service` / N3 加 `capability-progress`），`/biz/merchant/payment` 在台湾改成读 near-pay 或直接 404 引导；交接文档的「两套进件」待决一起关掉 |
| R7 | **渠道拨款和账单文件没确认**（交接清单 3-3）：蓝新、LINE Pay 是否提供 | 资金一期 B 没有期限；F03 的 `PAID / MISMATCH`、F05 的渠道笔数一直是「预计 / 自查」 | 先发问；一期 A 按 D10-B 和 D11-A 上线 |
| R8 | **台湾费率空**：near-pay 迁移里没有 `sys_pay_channel_rate` 台湾种子 | D5 改读费率表后，F01 手续费仍是 0 或兜底值；F07 全是 `UNAVAILABLE` | 上线前由运营配置或写种子迁移；要和合同费率核对 |
| R9 | **LINE Pay 生产 0 次 Confirm**、Apple Pay 当日取消授权没实现 | 收款列表会出现长期「待确认」；Apple Pay 退款受限，App 退款按钮可能失败 | near-pay P0 先修；App 退款失败码映射要覆盖「不支持当日取消」 |
| R10 | **生产主密钥明文曾出现在会话输出**（near-pay 进度文档 §5） | 蓝新和 LINE Pay 凭据都由它加密 | 轮换（不属于本盘点范围，按原文转述） |
| R11 | **单人审批开关**（V69、V70）目前开着 | `pay-service` 的「审一次即开」依赖它；上线运营前要决定是否关 | 和 4-13 的「单人审核」口径一起拍板 |
| R12 | ai-shop 外部记账 TDD 的迁移号基线过期（写的是 V635，实际最大 V636，多会话并行） | 号冲突 | 动手时再取号 |
