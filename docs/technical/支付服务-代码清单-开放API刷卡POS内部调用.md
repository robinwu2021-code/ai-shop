# 支付服务 · 代码清单（开放 API · 刷卡 POS · 内部调用）

状态：**草稿**（2026-10-25）
依据：[完整设计方案](./TDD-支付服务-完整设计方案.md)（D1 `pay-portal`、§6 渠道层、§7 支付单、§8 统一回调、§9 接入层）·
[数据库表结构说明 v4](./TDD-支付服务-数据库表结构说明.md) · [渠道接入统一封装设计](./支付服务-渠道接入统一封装设计.md) · [数据库现状对比与执行方案](./支付服务-数据库现状对比与执行方案.md) ·
POS 安卓 App 技术栈与架构（`design/POS安卓App-技术栈与架构.md`，在途、尚未入库）（终端侧）·
规范 [java.md §七](../../.claude/skills/ai-shop-dev-standards/references/java.md)
取代：[代码结构与类清单](./TDD-支付服务-代码结构与类清单.md) 中与这三条路径相关的部分（那份写于 v3 之前：收款账户归商家域、业务号关联、controller 在 shop-app —— 均已被推翻）。

---

## 0 读法与约定

**范围**：只列三条调用路径需要的类 —— 第三方经**开放 API** 调用、**刷卡 POS** 终端调用、**内部系统**（商城订单、餐饮账单）调用；以及三条路径共用的底座。进件、对账、结算、运营端页面不在本篇。

**状态标记**

| 标记 | 含义 |
|---|---|
| 🆕 新增 | 新写的类 |
| ♻️ 改造 | 现有类，改签名或改职责（写明从哪来） |
| ✅ 复用 | 现有类，不改或只加方法 |
| 🗑 迁出 / 删除 | 现有类，搬走或在收尾阶段删除 |
| ⏸ 过渡 | 密钥模块搁置期间的临时实现，就绪后替换 |

**放置规则**（java.md §7.1 + 完整设计方案 D1）

| 放什么 | 放哪 |
|---|---|
| 面向第三方、终端的 controller 与鉴权过滤器 | 新模块 **`backend/pay/pay-portal`**，包 `ai.neargo.shop.pay.portal.<端>`；内嵌形态装进 `shop-app`，独立形态单独起进程 |
| 内部接口端点 | `pay-svc`，`ai.neargo.shop.pay.svc`（只在独立形态存在），路径 `/internal/pay/v1/**` |
| 渠道回调端点 | `pay-domain`，`ai.neargo.shop.pay.callback`（`PayHasNoControllerTest` 允许 `/callback`） |
| 领域服务 | `pay-domain`，接口在 `<子域>.service`，实现在 `<子域>.service.impl` |
| 实体 | `<子域>.entity`，继承 **`PayBaseEntity`**（无 `tenantNo`） |
| Mapper | **必须在 `ai.neargo.shop.pay.mapper.<子域>`**（`PayDataSourceConfig` 只扫这里） |
| 跨域 Port 接口 / 事件 | `shop-base` 的 `ai.neargo.shop.spi.pay` |
| 渠道协议适配 | `pay-channel`，`ai.neargo.shop.pay.channel.<渠道>` |

**id 约定**：所有实体主键与关联字段都是 `Long`（对应 `BIGINT` 自增）；对外接口里出现的只有业务号（`payOrderNo`、`payRefundNo`、`appNo`、`terminalSn`），controller 进来先把业务号换成 id，领域层只认 id。

---

## 1 总览

| 层 | 开放 API（第三方） | 刷卡 POS（终端） | 内部调用（商城 / 餐饮） |
|---|---|---|---|
| 入口 | `pay-portal` · `/open/pay/v1/**` | `pay-portal` · `/terminal/pay/v1/**` | 内嵌：`spi.pay.PaymentPort` 直调；独立：`pay-svc` · `/internal/pay/v1/**` |
| 鉴权 | `OpenApiSignatureFilter` → `AppAuthService`（AppId + HMAC / RSA + 时间窗 + nonce） | `TerminalSignatureFilter` → `TerminalAuthService`（终端号 + 设备密钥 + nonce） | 内嵌：无（进程内）；独立：`InternalCallerFilter` → `InternalCallerRegistry`（每个调用方一把凭据） |
| 调用方对象 | `PayCaller(PARTNER)` | `PayCaller(TERMINAL)` | `PayCaller(INTERNAL)` |
| 应用服务 | `PaymentService` · `RefundService` · `CheckoutService` | `InStorePaymentService` · `RefundService` · `TerminalService` · `TerminalBatchService` | `PaymentService` · `RefundService`（经 `PaymentPort`） |
| 渠道能力 | `OnlinePaymentGateway` | `CardPaymentGateway` · `OfflinePaymentGateway` | `OnlinePaymentGateway`（商城 JSAPI 等） |
| 结果回传 | webhook（`BizNotifier` → `WebhookNotifyChannel`） | 终端轮询 `GET .../payments/{no}` | 领域事件 `PaymentSucceededEvent`（Outbox） |

三条路径**进到应用服务后完全同一套**：同一个 `PaymentService`、同一张能力矩阵、同一个结果入口 `PaymentOutcomeService`、同一套流水。差别只在入口、鉴权和结果回传方式。

---

## 2 公共底座（三条路径共用）

### 2.1 调用方与权限 · `pay-domain` · `ai.neargo.shop.pay.access`

| 类 | 类型 | 状态 | 功能说明 |
|---|---|---|---|
| `access.CallerType` | enum | 🆕 | `PARTNER` `INTERNAL` `TERMINAL` `BIZ` `OPS` `CHECKOUT` 六类接入端 |
| `access.PayAction` | enum | 🆕 | 能力矩阵的动作：`QUERY_METHODS` `CREATE_ONLINE` `CREATE_IN_STORE` `START_PAYMENT` `QUERY` `CLOSE` `VOID` `REQUEST_REFUND` `REVIEW_REFUND` `READ_LEDGER` `TERMINAL_BATCH` … |
| `access.PayCaller` | record | 🆕 | 一次调用的身份：`type`、`callerId`（`pay_app.id` / `pay_terminal.id`）、`storeScope`（允许操作的门店 id 集合）、`grantedActions`、`operatorNo`、`authorizerNo`（店长）、`userId`。**只能由四个鉴权组件构造**（架构测试守） |
| `access.PaySource` | record | 🆕 | `(sourceType, sourceId, operatorNo)`，写进 `pay_order` / `pay_refund` / 流水，实现「按来源拆」 |
| `access.policy.CallerPolicy` | interface | 🆕 | `check(PayCaller, PayAction, Long storeId)`：不在矩阵或不在门店范围 → `BizException(PAY_FORBIDDEN)` |
| `access.policy.CapabilityMatrix` | final class | 🆕 | 能力矩阵本体（`Map<CallerType, Set<PayAction>>`），**唯一定义处**；`CallerPolicyMatrixTest` 逐格参数化 |
| `access.policy.impl.MatrixCallerPolicy` | class | 🆕 | 矩阵 ∩ 调用方自身授权 ∩ 门店范围的判定实现 |

### 2.2 支付单与退款 · `pay-domain` · `ai.neargo.shop.pay.acquiring`

| 类 | 类型 | 状态 | 功能说明 |
|---|---|---|---|
| `acquiring.entity.PayOrder` | entity | 🆕 | 表 `pay_order`。调用方要收的一笔钱；`(payAppId, outOrderNo)` 幂等；选定方式时冻结路由快照（`payMerchantId`、`fundsPath`、`subMerchantId`、`secKeyVersion`） |
| `acquiring.entity.PayRefund` | entity | 🆕 | 表 `pay_refund`。商家侧发起的退款；无路由字段，执行时取原单快照 |
| `acquiring.entity.PayRefundReviewRule` | entity | 🆕 | 表 `pay_refund_review_rule`。一行一个规则版本 |
| `acquiring.entity.PayException` | entity | 🆕 | 表 `pay_exception`。关单后支付、金额不符等待处置事实 |
| `acquiring.entity.PayCheckoutSession` | entity | 🆕 | 表 `pay_checkout_session`。收银令牌（只存哈希） |
| `mapper.acquiring.AcquiringMappers` | Mapper 集合 | 🆕 | `PayOrderMapper`、`PayRefundMapper`、`RefundReviewRuleMapper`、`PayExceptionMapper`、`CheckoutSessionMapper`；`PayOrderMapper#lockById` 用 `FOR UPDATE` |
| `acquiring.statemachine.PayOrderStatus` | enum | 🆕 | `CREATED` `AWAITING_PAYMENT` `AUTHORIZED` `AWAITING_CONFIRM` `SUCCEEDED` `PARTIALLY_REFUNDED` `REFUNDED` `CLOSED` `FAILED` |
| `acquiring.statemachine.PayOrderStateMachine` | class | 🆕 | 迁移表 + `transit(order, event)`；**进入 `SUCCEEDED` 只接受 `OutcomeEvent`**，且 `OutcomeEvent` 构造器包私有，只有 `PaymentOutcomeServiceImpl` 能造 |
| `acquiring.statemachine.PayRefundStatus` / `PayRefundStateMachine` | enum / class | 🆕 | 退款状态与迁移；`APPROVED` 只能从「未命中规则」或「第二人通过」进入 |
| `acquiring.service.PaymentService` | interface | 🆕 | **三条路径共用的收款入口**：`create(PayCaller, CreatePaymentCmd) → PaymentVO`（幂等建单）· `methods(PayCaller, Long payOrderId)`（按收款路由与渠道能力列可用方式）· `start(PayCaller, Long payOrderId, String payMethod) → StartPaymentVO`（选方式 → 选路由 → 冻结快照 → 调渠道建单，返回 `PayInstruction`）· `query` · `close`（关前必查单） |
| `acquiring.service.impl.PaymentServiceImpl` | class | 🆕 | 实现上面；第一行 `callerPolicy.check`；建单校验 `request_sha256`（同键不同内容拒绝）；`start` 时写 `stl_payment`（尝试）并经 `ChannelRouter` 调渠道；渠道调用在事务外 |
| `acquiring.service.PaymentOutcomeService` / `impl.PaymentOutcomeServiceImpl` | interface / class | 🆕 | **唯一把支付单推到成功 / 失败的地方**：回调、查单、LINE Pay confirm、终端上报、对账补单都调 `apply(ChannelOutcome)`；幂等键 `out_trade_no`；同事务：尝试 → SUCCESS、支付单 → SUCCEEDED、`FundsPathStrategy` 记账、Outbox 发 `PaymentSucceededEvent`；关单后到达 → 建 `PayException(LATE_PAYMENT)` |
| `acquiring.service.RefundService` / `impl.RefundServiceImpl` | interface / class | 🆕 | `request(PayCaller, RefundRequestCmd)`：权限（只有商家侧）→ `RefundablePolicy` → 占用可退余额 → `RefundReviewPolicy` 判定 → 命中则开复核单、未命中则交 `RefundDispatcher` · `review(...)`（运营复核）· `query` |
| `acquiring.service.RefundDispatcher` | class | 🆕 | 事务外调渠道退款：取原单冻结的路由 → `ChannelContextFactory` → `TransactionGateway#refund`；结果交 `RefundOutcomeService` |
| `acquiring.service.RefundOutcomeService` / impl | interface / class | 🆕 | 退款结果唯一入口：更新 `pay_refund`、`pay_order.refunded_minor`、写流水、发 `RefundSucceededEvent` |
| `service.RefundExecutionService`（现有） | interface | ♻️ | 现有的「先落库 → 抢发送权 → 调渠道 → 不明只查不重发」保留，成为 `RefundDispatcher` 的底层执行器；**旧的 `SettleServiceImpl#refund` 系列改为转调它**（修 R21） |
| `service.PaymentLedgerService`（现有） | interface | ♻️ | 降级为「通道交易尝试」存取（`stl_payment`）：`open` 带 `payOrderId`；`settle` 不再覆盖请求金额（修 R20）；`out_trade_no` 唯一（修 R07） |
| `acquiring.service.CheckoutService` / impl | interface / class | 🆕 | 收银令牌：`issue(payOrderId) → checkoutUrl`（开放 API 与终端屏显码都用）· `open(token)` · `pay(token, payMethod)` |
| `acquiring.policy.RouteSelectionPolicy` | class | 🆕 | 输入 `PayMerchantQueryPort#collectableRoutes(storeId, market)` 的候选 + 支付方式，按渠道能力、商户开通产品、`priority` 选一条；无则 `PAY_METHOD_NOT_AVAILABLE` |
| `acquiring.policy.RefundablePolicy` | class | 🆕 | 可退余额 = 实收 − 已退 − 已占用；渠道部分退款次数与间隔 |
| `acquiring.policy.RefundReviewPolicy` | class | 🆕 | 取最具体的 ACTIVE 规则（门店级 > 市场级）；**无 ACTIVE 规则 → 必须复核**；返回命中原因与规则版本 id |
| `acquiring.policy.AmountCodec` | class | 🆕 | 内部最小单位 ↔ 渠道金额，按 `pay_channel_product_market.amount_scale`；有余数抛 `PAY_AMOUNT_PRECISION` |
| `acquiring.dto.*` | record | 🆕 | `CreatePaymentCmd` `PaymentVO` `StartPaymentVO` `PayMethodVO` `RefundRequestCmd` `RefundVO` `ChannelOutcome` `ChannelRefundOutcome` |

### 2.3 资金路径与流水 · `ai.neargo.shop.pay.funds` / `.ledger`

| 类 | 类型 | 状态 | 功能说明 |
|---|---|---|---|
| `funds.FundsPathStrategy` | interface | 🆕 | `onPaymentSucceeded` / `onRefundSucceeded` / `onPlatformFee` → `LedgerVoucher`；DIRECT 与 AGGREGATED 的差异**只在这里**（架构测试守） |
| `funds.DirectFundsPathStrategy` / `AggregatedFundsPathStrategy` | class | 🆕 | 两种分录规则：DIRECT 记平台外备查账，AGGREGATED 记平台余额与对商家应付 |
| `ledger.LedgerVoucher` | record | 🆕 | 一张凭证与其分录；构造时校验借贷平衡、禁止 `MERCHANT_CHANNEL` 与 `PLATFORM_*` 直接对转 |
| `ledger.service.LedgerService` / impl | interface / class | 🆕 | `post(voucher)`（`idem_key` 幂等）· `reverse(voucherId, reason)`；只 insert |
| `ledger.entity.PayLedgerVoucher` / `PayLedgerEntry` · `mapper.ledger.LedgerMappers` | entity / Mapper | 🆕 | 表 `pay_ledger_voucher` / `pay_ledger_entry`；Mapper 不暴露 update / delete |

### 2.4 收款路由 · `spi.pay` + `ai.neargo.shop.pay.merchant`

| 类 | 类型 | 状态 | 功能说明 |
|---|---|---|---|
| `spi.pay.PayMerchantQueryPort` | interface | 🆕 | `routeOf(storeId, payChannel)` · `collectableRoutes(storeId, market)` · `routeByPayMerchantId(id, keyVersion)`（退款、查单用冻结值）· `capabilityOf(payMerchantId)`；取代 `MerchantQueryPort` 上 9 个支付解析方法 |
| `spi.pay.PayRoute` | record | 🆕 | 一条收款路由：`storeId` `payMerchantId` `payChannel` `market` `currency` `accessMode` `fundsPath` `channelMerchantId` `subMerchantId` `parentPayMerchantId` `keyVersion` `priority` |
| `merchant.port.PayMerchantQueryPortImpl` | class | 🆕 | 读 `pay_merchant_binding` + `pay_merchant`；**只按门店绑定解析，无主体级兜底**（修 R01） |
| `merchant.entity.PayMerchant` / `PayMerchantBinding` / `PayMerchantProduct` · `mapper.merchant.MerchantMappers` | entity / Mapper | 🆕 | 表 `pay_merchant` 等（迁移见执行方案 S4） |

### 2.5 渠道层 · `pay-channel`

| 类 | 类型 | 状态 | 功能说明 |
|---|---|---|---|
| `channel.gateway.ChannelAccountContext` | record | 🆕 | 一次渠道调用的账户上下文（`payMerchantId` `payChannel` `channelMerchantId` `subMerchantId` `fundsPath` `secrets` `keyVersion`）；`toString` 脱敏 |
| `channel.gateway.ChannelSecrets` | final class | 🆕 | 解密后的渠道凭据，只在内存；不可序列化，`toString` 恒为 `***` |
| `channel.gateway.ChannelContextFactory` | class | 🆕 | **构造 ctx 的唯一地方**：冻结的 `PayRoute` + `ChannelCredentialProvider` → ctx |
| `channel.credential.ChannelCredentialProvider` | interface | 🆕 | `secretsOf(payMerchantId, keyVersion)` |
| `channel.credential.ConfigChannelCredentialProvider` | class | ⏸ | **过渡实现**：从部署配置读 CN 平台级商户凭据（今天 `WechatPayChannelConfig` 那些值）；商户自有密钥一律报「未开通」→ 新渠道过渡期不能上线 |
| `channel.gateway.OnlinePaymentGateway` | interface | 🆕 | 线上：`create(ctx, CreateCmd) → PayInstruction` |
| `channel.gateway.OfflinePaymentGateway` | interface | 🆕 | 线下：`chargeCustomerCode(ctx, cmd, authCode)`（扫顾客付款码，同步扣款）· `createStoreQr(ctx, cmd)`（屏显码） |
| `channel.gateway.CardPaymentGateway` | interface | 🆕 | 刷卡：`charge(ctx, cmd, EncryptedCardData)` · `voidCharge(ctx, outTradeNo)` |
| `channel.gateway.ConfirmableGateway` | interface | 🆕 | LINE Pay：`confirm(ctx, channelTradeNo, amount, currency)` |
| `channel.gateway.TransactionGateway` | interface | 🆕 | 全部渠道必有：`query` · `close` · `refund` · `queryRefund` · `parseNotify` |
| `channel.gateway.PayInstruction` | sealed interface | 🆕 | 建单返回给端上的指令：`Redirect` `AutoPostForm` `SdkParams` `QrCode` `AwaitResult` |
| `channel.gateway.EncryptedCardData` | record | 🆕 | 读卡套件产出的不透明加密字节 + 套件标识；**服务端不解析、不落日志** |
| `channel.ChannelRouter` | class | ♻️ | 自 `PayGatewayRouter`：`transaction(payChannel)` · `<T> capability(payChannel, Class<T>)`；保留「同名两实现启动即炸、找不到不回退」；启动比对 `pay_channel_product` |
| `channel.PayGateway` | interface | ♻️ | 现有接口改为 `@Deprecated` 兼容组合；微信、支付宝、Stub、Test 经默认方法桥接到带 ctx 的新签名 |
| `channel.http.RecordingChannelClient` | class | 🆕 | 包住现有 `ChannelClient` 的装饰器：每次调用写 `pay_channel_call_log`（请求 / 响应脱敏副本、HTTP 状态、耗时、单号）；超时记 `UNKNOWN`；网关只能注入它（`RecordingClientOnlyTest`） |
| `channel.ChannelClient` / `WechatChannelClient` | class | ✅ | 协议层 HTTP 客户端，不变 |
| `channel.PayloadMasker` | class | ♻️ | 递归脱敏；补蓝新 `TradeSha` / `CheckCode`、LINE Pay `X-LINE-Authorization`、PayBy `sign`（修 R30） |
| `channel.ChannelMessageRecorder` | class | 🗑 | 由 `RecordingChannelClient` 与回调记录取代；`stl_channel_message` 停写 |
| `log.entity.PayChannelCallLog` · `mapper.log.LogMappers` | entity / Mapper | 🆕 | 表 `pay_channel_call_log`、`pay_callback_log` |

### 2.6 统一回调 · `pay-domain` · `ai.neargo.shop.pay.callback`

| 类 | 类型 | 状态 | 功能说明 |
|---|---|---|---|
| `callback.PayCallbackEndpoint` | controller | 🆕 | `/pay/callback/{channel}/{notifyType}/{ref}`：① 落 `pay_callback_log` ② `ref` 业务号 → `pay_merchant.id` ③ ctx → `parseNotify` 验签（补时间戳新鲜度）④ 报文商户号 = 路由 `channelMerchantId` ⑤ 回查 ⑥ `PaymentOutcomeService` / `RefundOutcomeService` ⑦ 回写记录、按渠道回 ack |
| `callback.LegacyCallbackAlias` | controller | 🆕 | 旧地址 `/pay/callback/{channel}`、`/{channel}/refund` 的兼容别名，按平台级商户处理；CN 渠道后台改完登记地址、30 天无流量后删 |
| `callback.CallbackUrlBuilder` | class | 🆕 | 下单 / 退款时生成回调地址（取代 `notifyUrl + "/refund"` 拼接，修 R32）；渠道不支持路径变量时退化为查询参数 |
| `callback.CallbackReplayService` | class | 🆕 | 运营重放失败回调：用原文重走 ②–⑦，新记录 `replay_of_id` 指向原记录 |
| `trade.api.callback.ChannelPayCallbackController`（`shop-core`） | controller | 🗑 | 迁入上面的 `PayCallbackEndpoint` 后删除；其中「回调后调 `orderService.markPaid`」改为订单侧消费 `PaymentSucceededEvent` |

### 2.7 跨域事件 · `shop-base` · `ai.neargo.shop.spi.pay`

| 类 | 类型 | 状态 | 功能说明 |
|---|---|---|---|
| `spi.pay.FundsPath` | enum | ♻️ | 自 `PointsFundsGateway.FundsPath` 提出（值不变），积分链路改引用 |
| `spi.pay.PaymentSucceededEvent` | record | 🆕 | `payOrderId` `payOrderNo` `payAppId` `outOrderNo` `bizType` `bizNo` `storeId` `amountMinor` `currency` `payChannel` `channelTradeNo` `succeededAt`；经 Outbox 投递 |
| `spi.pay.PaymentClosedEvent` / `RefundSucceededEvent` / `RefundFailedEvent` | record | 🆕 | 关单、退款结果 |

### 2.8 基础 · `shop-store-mybatis` / `pay-domain`

| 类 | 类型 | 状态 | 功能说明 |
|---|---|---|---|
| `pay.common.PayBaseEntity` | abstract class | 🆕 | 新表实体基类：`Long id`、`createdAt` `createdBy` `updatedAt` `updatedBy` `version` `deleted`，**不含 `tenantNo`**（否则 `AuditMetaObjectHandler` 会往不存在的列填值） |
| `numbering.PayOperationsDocuments` · `numbering.PayOperationsNumberReservations` | class | ♻️ | 现有单号生成（`pay_document_sequence` / `pay_document_number`）：新增 `PAY_ORDER` `PAY_REFUND` `APP` `TERMINAL` 等单号类型 |

---

## 3 开放 API（第三方调用）

### 3.1 入口与鉴权 · `pay-portal` · `ai.neargo.shop.pay.portal`

| 类 | 类型 | 状态 | 功能说明 |
|---|---|---|---|
| `portal.auth.OpenApiSignatureFilter` | servlet filter | 🆕 | 只挂 `/open/pay/**`：读 `X-App-Id` `X-Timestamp` `X-Nonce` `X-Signature`、计算 body 的 SHA-256 → `AppAuthService#authenticate` → 把 `PayCaller` 放进请求属性；失败一律在进入 controller 之前返回 401 / 403，原因只进日志 |
| `portal.auth.CachedBodyRequestWrapper` | class | 🆕 | 缓存请求体，让过滤器算哈希后 controller 还能再读 |
| `portal.auth.PortalCallerResolver` | `HandlerMethodArgumentResolver` | 🆕 | 把请求属性里的 `PayCaller` 注入 controller 参数；controller 不接受任何「我是谁」的入参 |
| `portal.open.OpenPaymentController` | controller | 🆕 | `POST /open/pay/v1/orders`（建单，返回 `payOrderNo` + `checkoutUrl` 或直接的 `PayInstruction`）· `GET /orders/{payOrderNo}` · `GET /orders?outOrderNo=` · `POST /orders/{payOrderNo}/close` · `POST /orders/{payOrderNo}/pay`（第三方自建收银页时选方式） |
| `portal.open.OpenRefundController` | controller | 🆕 | `POST /open/pay/v1/orders/{payOrderNo}/refunds`（需 `pay:refund` scope + 商家授权 + 日限额，命中复核规则时返回 `PENDING_REVIEW`）· `GET /refunds/{payRefundNo}` |
| `portal.open.OpenPayMethodController` | controller | 🆕 | `GET /open/pay/v1/stores/{storeRef}/pay-methods?market=&scene=`：该门店当前可用的支付方式（收款路由选择结果） |
| `portal.open.OpenLedgerController` | controller | 🆕 | `GET /open/pay/v1/ledger`（本 app 来源的流水分页）· `GET /statements/{billDate}`（日对账单 CSV） |
| `portal.open.dto.*` | record | 🆕 | `OpenCreateOrderReq` `OpenOrderVO` `OpenPayReq` `OpenRefundReq` `OpenRefundVO` `OpenPayMethodVO` `OpenLedgerVO`；**对外字段只有业务号**，`id` 不出现 |
| `portal.common.OpenApiResponse` | record | 🆕 | 统一响应 `{code, message, data, requestId}`；不走全局 `ApiResponseWrapper`（避免双层包装，java.md §四） |
| `portal.common.OpenApiExceptionHandler` | `@RestControllerAdvice` | 🆕 | 只作用于 `portal.open`：`BizException` → 错误码 + 三语文案；未知异常不泄露栈 |
| `portal.common.IdempotencyKeyInterceptor` | interceptor | 🆕 | 读 `Idempotency-Key`，与 `outOrderNo` / `outRefundNo` 二选一作为幂等键传入应用服务 |
| `portal.common.OpenRateLimiter` | class | 🆕 | 按 `pay_app.rate_limit_qps` 限流 |
| `portal.remote.RemotePaymentService` 等 | class | 🆕 | **独立形态**下实现 `PaymentService` / `RefundService` 接口，经 `PayInternalApi` 调 `pay-svc`；内嵌形态不装配（`shop.pay.deployment`） |

### 3.2 接入方领域 · `pay-domain` · `ai.neargo.shop.pay.openapp`

| 类 | 类型 | 状态 | 功能说明 |
|---|---|---|---|
| `openapp.entity.PayApp` / `PayAppGrant` / `PayApiNonce` | entity | 🆕 | 表 `pay_app`、`pay_app_grant`、`pay_api_nonce`（推送记录改由 `notify` 包的 `pay_biz_notify` 承担） |
| `mapper.openapp.OpenAppMappers` | Mapper 集合 | 🆕 | 上面四张表 |
| `openapp.service.AppAuthService` / `impl.AppAuthServiceImpl` | interface / class | 🆕 | `authenticate(SignedRequest) → PayCaller`：AppId 启用 → IP 白名单 → 时间窗 ±300s → nonce → 逐把有效密钥验签 → scope → `pay_app_grant` 门店范围；**纯数据校验，不读会话**，所以可以在支付域 |
| `openapp.SignatureCanonicalizer` | class | 🆕 | 生成 `stringToSign`（METHOD / PATH / 排序后 QUERY / AppId / 时间戳 / nonce / body 哈希）；与对外文档里的规则逐字一致，并提供示例向量单测 |
| `openapp.NonceStore` | interface | 🆕 | `claim(callerType, callerId, nonce, ttl) → boolean` |
| `openapp.RedisNonceStore` / `DbNonceStore` | class | 🆕 | 有 Redis 用 `SETNX`；没有则写 `pay_api_nonce`（唯一键冲突即重放） |
| `openapp.AppKeyResolver` | interface | 🆕 | `activeKeysOf(payAppId)` → 当前可用于验签的密钥（轮换重叠期可能两把） |
| `openapp.ConfigAppKeyResolver` | class | ⏸ | **过渡实现**：从部署配置读试点接入方的 HMAC 密钥或 RSA 公钥；密钥模块就绪后换 `SecretVaultAppKeyResolver` |
| `openapp.service.PayAppService` / impl | interface / class | 🆕 | 接入方登记、启停、scope、IP 白名单；商家授权 app 代本店收款 / 退款（`pay_app_grant`） |
| `notify.BizNotifier` · `notify.NotifyRouter` | class | 🆕 | 订阅 `PaymentSucceededEvent` 等 → 按 `pay_app.owner_type` 决定通道 → 写 `pay_biz_notify`（`event_key` 幂等）；外部接入方走 `WebhookNotifyChannel`（见[渠道接入统一封装设计](./支付服务-渠道接入统一封装设计.md) §13.2） |
| `notify.WebhookNotifyChannel` · `notify.job.BizNotifyDeliveryJob` | class / job | 🆕 | 取待投递的 `pay_biz_notify` → 用接入方密钥对推送体签名（头部与请求签名同构）→ POST → 每次写 `pay_biz_notify_attempt` → 指数退避，≥ 24h 后 `GIVEN_UP`；提供运营手动重推 |
| `openapp.job.NonceCleanupJob` | job | 🆕 | 每小时删除过期 `pay_api_nonce` |

### 3.3 运营与商家端（开放 API 的配套管理）

| 类 | 模块 | 状态 | 功能说明 |
|---|---|---|---|
| `portal.ops.OpsPayAppController` | `pay-portal` | 🆕 | `/pay-ops/v1/apps`：接入方登记、启停、scope、白名单、查看最近调用 |
| `portal.biz.BizPayAppGrantController` | `pay-portal` | 🆕 | `/biz/pay/app-grants`：商家授权 / 撤销某 app 代本店操作 |

---

## 4 刷卡 POS（终端调用）

> 终端侧的类（读卡模块 `CardReaderPort`、签名拦截器、离线队列）见 POS 安卓 App 技术栈与架构（`design/POS安卓App-技术栈与架构.md`，在途、尚未入库） §4、§6.2；本节只列服务端。
> **依赖密钥模块**：设备密钥在激活时动态生成，无法像渠道凭据那样过渡期放配置 → 本路径在密钥模块就绪前**只能在沙箱联调**（§4.5）。

### 4.1 入口与鉴权 · `pay-portal` · `ai.neargo.shop.pay.portal.terminal`

| 类 | 类型 | 状态 | 功能说明 |
|---|---|---|---|
| `portal.auth.TerminalSignatureFilter` | servlet filter | 🆕 | 只挂 `/terminal/pay/**`：读 `X-Terminal-Sn` `X-Timestamp` `X-Nonce` `X-Signature` `X-Operator` → `TerminalAuthService#authenticate` → `PayCaller(TERMINAL)`；激活接口除外（只凭激活码） |
| `portal.terminal.TerminalActivationController` | controller | 🆕 | `POST /terminal/pay/v1/activation`（激活码 + 设备指纹 → 一次性返回设备密钥）· `POST /heartbeat`（版本、读卡套件型号、最近离线队列长度） |
| `portal.terminal.TerminalPaymentController` | controller | 🆕 | 对**已建好的支付单**收款：`POST /payments/{payOrderNo}/card`（上送 `EncryptedCardData`）· `POST /payments/{payOrderNo}/customer-code`（扫顾客付款码）· `POST /payments/{payOrderNo}/qr`（取屏显码）· `GET /payments/{payOrderNo}`（轮询结果；终端状态 `UNKNOWN` 时必须先调它）· `POST /payments/{payOrderNo}/cancel`（关前查单） |
| `portal.terminal.TerminalRefundController` | controller | 🆕 | `POST /payments/{payOrderNo}/void`（当日撤销，带店长授权码）· `POST /payments/{payOrderNo}/refunds`（隔日退款，带店长授权码；过复核规则） |
| `portal.terminal.TerminalBatchController` | controller | 🆕 | `POST /batches`（日结上报：日期、笔数、金额、撤销）· `GET /batches/{bizDate}`（比对结果） |
| `portal.terminal.dto.*` | record | 🆕 | `ActivateReq` `ActivateVO`（含设备密钥，只此一次）`HeartbeatReq` `CardChargeReq` `CustomerCodeReq` `TerminalPaymentVO` `VoidReq` `TerminalRefundReq` `BatchReportReq` `BatchVO` |

### 4.2 终端领域 · `pay-domain` · `ai.neargo.shop.pay.terminal`

| 类 | 类型 | 状态 | 功能说明 |
|---|---|---|---|
| `terminal.entity.PayTerminal` / `PayManagerCode` / `PayTerminalBatch` | entity | 🆕 | 表 `pay_terminal`、`pay_manager_code`、`pay_terminal_batch` |
| `mapper.terminal.TerminalMappers` | Mapper 集合 | 🆕 | 上面三张表 |
| `terminal.service.TerminalService` / impl | interface / class | 🆕 | `register(storeId, payMerchantId, model)` · `issueActivationCode(terminalId)`（10 分钟、只存哈希）· `activate(code, fingerprint) → 设备密钥`（经 `TerminalKeyIssuer` 生成）· `heartbeat` · `reportLost(terminalId)`（吊销密钥 + 关在途未付单，关前查单） |
| `terminal.service.TerminalAuthService` / impl | interface / class | 🆕 | `authenticate(SignedTerminalRequest) → PayCaller`：终端 ACTIVE → 时间窗 → nonce → 设备密钥验签 → `storeScope = {terminal.storeId}`；`operatorNo` 取自 `X-Operator` |
| `terminal.TerminalKeyIssuer` | interface | 🆕 | 激活时生成并保存设备密钥、验签时取密钥；**实现依赖密钥模块** |
| `terminal.SandboxTerminalKeyIssuer` | class | ⏸ | **沙箱专用**：密钥存内存 / 沙箱配置，生产环境装配即启动失败；只用于联调 |
| `terminal.service.ManagerCodeService` / impl | interface / class | 🆕 | `set(storeId, managerAccountId, code)`（加盐慢哈希）· `verify(storeId, code) → managerAccountId`；连续失败锁定 |
| `terminal.service.InStorePaymentService` / impl | interface / class | 🆕 | 门店收款编排：`chargeCard(PayCaller, payOrderId, EncryptedCardData)` · `chargeCustomerCode(PayCaller, payOrderId, authCode)` · `storeQr(PayCaller, payOrderId)`；内部调 `PaymentService#start` 选路由冻结快照，再调 `CardPaymentGateway` / `OfflinePaymentGateway`；同步扣款结果不明时记 `UNKNOWN` 并安排查单，**绝不直接判失败** |
| `terminal.service.TerminalVoidService` / impl | interface / class | 🆕 | 当日撤销：校验店长授权码、单终端日限额 → `CardPaymentGateway#voidCharge` 或转退款；结果经 `RefundOutcomeService` |
| `terminal.service.TerminalBatchService` / impl | interface / class | 🆕 | 收日结上报 → 按终端 + 营业日汇总服务端成功交易 → 比对 → `MATCHED` / `MISMATCHED`；不一致交 `TerminalBatchAxis` |
| `service.recon.TerminalBatchAxis` | class | 🆕 | 实现现有 `ReconAxis`：日结差异进 `stl_recon_diff`，常见原因「离线刷卡未上送」先查单补单 |
| `terminal.job.TerminalOfflineJob` | job | 🆕 | 超过 24h 无心跳标「失联」并通知门店 |

### 4.3 渠道适配（刷卡与扫码）· `pay-channel`

| 类 | 实现的接口 | 状态 | 功能说明 |
|---|---|---|---|
| `channel.linepay.LinePayOfflineGateway` | `OfflinePaymentGateway` | 🆕 | LINE Pay Offline：扫顾客一次性付款码直接扣款；超时只查单不重扣 |
| `channel.newebpay.NewebPayCardGateway` | `CardPaymentGateway` | 🆕 | 蓝新卡片在场交易：透传读卡套件加密数据；撤销 / 请退款（接口规格待 Q12） |
| `channel.payby.PayByCardGateway` | `CardPaymentGateway` | 🆕 | PayBy 卡片在场交易（规格待 Q12） |
| `channel.payby.PayByCustomerCodeGateway` | `OfflinePaymentGateway` | 🆕 | PayBy 被扫（规格待 Q3） |
| 屏显码（C 扫 B） | — | — | 不新增渠道类：终端取到的是 `checkout-h5` 的收银链接（`CheckoutService#issue`），顾客扫码后走线上路径 |

### 4.4 运营与商家端（终端管理）

| 类 | 模块 | 状态 | 功能说明 |
|---|---|---|---|
| `portal.ops.OpsTerminalController` | `pay-portal` | 🆕 | `/pay-ops/v1/terminals`：登记、生成激活码、挂失、查看心跳与日结 |
| `portal.biz.BizTerminalController` | `pay-portal` | 🆕 | `/biz/pay/terminals`：本店终端列表、挂失 |
| `portal.biz.BizManagerCodeController` | `pay-portal` | 🆕 | `/biz/pay/manager-code`：店长设置 / 重置授权码 |

### 4.5 与 POS App 的对应关系

| POS App（终端侧） | 服务端 | 约束 |
|---|---|---|
| 签名拦截器（`X-Terminal-Sn` / `X-Timestamp` / `X-Nonce` / `X-Signature` / `X-Operator`） | `TerminalSignatureFilter` → `TerminalAuthService` | 签名规则同开放 API 的 `SignatureCanonicalizer`，终端侧与服务端用同一组示例向量测试 |
| 设备密钥存 Android Keystore | `TerminalService#activate` 一次性下发 | 服务端**不能再次展示**；丢了就挂失重激活 |
| `CardReaderPort.readAndEncrypt(amount) → EncryptedCardData` | `TerminalPaymentController#card` → `CardPaymentGateway` | 服务端把它当不透明字节透传，不解析、不落日志 |
| 终端状态 `UNKNOWN` 必须先查单 | `GET /terminal/pay/v1/payments/{no}` · `cancel` 关前查单 | 「终端已扣、我方已关」两边都挡 |
| 写端点必须接受 `Idempotency-Key`（离线重放） | `IdempotencyKeyInterceptor`（终端路径同样挂） | 同键重放返回同一结果 |
| 日结 | `TerminalBatchController` | 差异进对账 |

---

## 5 内部调用（商城订单、餐饮账单）

### 5.1 跨域契约 · `shop-base` · `ai.neargo.shop.spi.pay`

| 类 | 类型 | 状态 | 功能说明 |
|---|---|---|---|
| `spi.pay.PaymentPort` | interface | 🆕 | **内部系统调支付的唯一入口**（只管收款）：`createPayment(InternalCreatePaymentCmd) → PaymentHandle` · `startPayment(payOrderNo, payMethod, payerContext) → PayInstruction` · `queryPayment(payOrderNo)` · `closePayment(payOrderNo, reason)` · `requestRefund(InternalRefundCmd) → RefundHandle`（**须带商家操作员**：内部系统不能以系统身份退款） |
| `spi.pay.InternalCreatePaymentCmd` 等 | record | 🆕 | `bizType` `bizNo` `outOrderNo` `storeId` `amountMinor` `currency` `subject` `expireAt` `userId` `payerContext`（openid 等）；`PaymentHandle(payOrderId, payOrderNo, status)` |
| `spi.settle.SettlePort`（现有） | interface | ♻️ | `openPayment` / `initPayment` / `settlePayment` / `requestRefund` 等收款与退款方法标 `@Deprecated`，实现改为转调 `PaymentPort`；结算相关方法不动 |
| `spi.pay.PayMerchantQueryPort` | interface | 🆕 | 见 §2.4；订单、结算、售后读「这家店能不能收款、收到哪」都改走它 |

### 5.2 内嵌形态（今天的生产形态）

| 类 | 模块 | 状态 | 功能说明 |
|---|---|---|---|
| `acquiring.port.PaymentPortImpl` | `pay-domain` | 🆕 | `PaymentPort` 的本地实现：按 `bizType` 映射内部 app（`MALL` / `FOOD` 的 `pay_app.id`）→ 构造 `PayCaller(INTERNAL)`（`storeScope = {storeId}`）→ 调 `PaymentService` / `RefundService`；`requestRefund` 要求 `operatorNo` 非空 |
| `acquiring.port.InternalCallers` | `pay-domain` | 🆕 | 内部调用方的 `PayCaller` 工厂（`internal(appCode, storeId, operatorNo)`）——与 `AppAuthService`、`TerminalAuthService`、`CheckoutService` 并列的四个合法构造点之一 |
| `paybridge.port.SettlePortImpl` | `shop-app` | ♻️ | 收款相关方法改为转调 `PaymentPort`；保留「首次 `out_trade_no` = 订单号」规则（存量测试依赖） |
| `paybridge.PaymentSucceededConsumer` | `shop-app` | 🆕 | 消费 `PaymentSucceededEvent`：`bizType = MALL_ORDER` → 订单 `markPaid`；`FOOD_CHECK` → 餐饮账单结清；**取代回调 controller 直接调订单** |
| `paybridge.PaymentClosedConsumer` / `RefundResultConsumer` | `shop-app` | 🆕 | 关单 → 订单释放库存；退款结果 → 售后单推进 |
| `paybridge.ReconScanJob` · `paybridge.PaymentReconReconciler` | `shop-app` | ♻️ | 现有掉单巡检：改为分页、带上限、按渠道限速；查到已付走 `PaymentOutcomeService`，查无此单走 `PaymentService#close`（修 R18 的堆积问题） |
| `paybridge.OrderPaidReconciler` · `OrderPaidReconcileJob` | `shop-app` | ♻️ | 现有「支付成功但订单未 PAID」补偿：改为以 `pay_order.status = SUCCEEDED` 为准重放 `PaymentSucceededEvent`，不再直接调订单 |
| `merchant.port.MerchantQueryPortImpl`（`shop-merchant`） | `shop-merchant` | ♻️ | 9 个支付解析方法过渡期转调 `PayMerchantQueryPort`，读切完成后删除 |

### 5.3 独立形态（`shop.pay.deployment=standalone`）

| 类 | 模块 | 状态 | 功能说明 |
|---|---|---|---|
| `svc.InternalPaymentEndpoint` | `pay-svc` | 🆕 | `/internal/pay/v1/payments`（建单 / 发起 / 查询 / 关单）→ `PaymentService`；返回不经全局包装 |
| `svc.InternalRefundEndpoint` | `pay-svc` | 🆕 | `/internal/pay/v1/refunds` → `RefundService` |
| `svc.InternalPayMerchantEndpoint` | `pay-svc` | 🆕 | `/internal/pay/v1/merchant-routes` → `PayMerchantQueryPort` |
| `svc.InternalCallerFilter` | `pay-svc` | 🆕 | 只挂 `/internal/pay/**`：按 `X-Internal-Caller` + 凭据识别调用方，按路径前缀授权（如 FOOD 不能调商城的退款）；常量时间比较（修 R12）；未配置一律 401 |
| `svc.InternalCallerRegistry` | interface | 🆕 | `resolve(callerCode, credential) → 允许的路径前缀 + 内部 app id` |
| `svc.ConfigInternalCallerRegistry` | class | ⏸ | **过渡实现**：每个调用方一把令牌放部署环境（只存哈希）；取代全局共享的 `shop.services.internal-token`；密钥模块就绪后换实现 |
| `svc.InternalPayEndpoint`（现有） | `pay-svc` | ♻️ | 令牌比较改为 `InternalServiceResponses` 的常量时间实现（修 R12）；路径迁到 `/internal/pay/v1` 前缀 |
| `client.PayInternalApi` / `client.PayInternalPaths` | `pay-domain` | ♻️ | 增加上面三个端点的 Http Interface 方法与路径常量 |
| `payclient.port.RemotePaymentPortImpl` | `shop-app` | 🆕 | 独立形态下的 `PaymentPort` 实现：经 `PayInternalApi` 调 `pay-svc`；与 `PaymentPortImpl` 同一时刻只装配一个 |
| `payclient.port.RemotePayMerchantQueryPortImpl` | `shop-app` | 🆕 | 同上，`PayMerchantQueryPort` 的远程实现 |
| `svc.InternalServiceResponses` · `svc.InternalHttp`（`shop-base`） | — | ✅ | 内部调用的响应封装与 HTTP 客户端工厂，复用 |

---

## 6 调用链

### 6.1 开放 API：第三方建单 → 顾客付款 → 推送结果

```
第三方 ──POST /open/pay/v1/orders──▶ OpenApiSignatureFilter ─▶ AppAuthService ─▶ PayCaller(PARTNER)
  ─▶ OpenPaymentController.create ─▶ PaymentService.create   （幂等 pay_app_id + out_order_no）
      ─▶ CheckoutService.issue ─▶ 返回 payOrderNo + checkoutUrl
顾客打开 checkout-h5 ─▶ CheckoutService.pay ─▶ PaymentService.start
      ─▶ RouteSelectionPolicy（PayMerchantQueryPort.collectableRoutes）─▶ 冻结路由快照
      ─▶ ChannelContextFactory ─▶ OnlinePaymentGateway.create（经 RecordingChannelClient 记调用）
渠道 ──/pay/callback/{ch}/pay/{payMerchantNo}──▶ PayCallbackEndpoint ─▶ PaymentOutcomeService.apply
      ─▶ 状态机 SUCCEEDED · FundsPathStrategy → LedgerService · Outbox(PaymentSucceededEvent)
      ─▶ BizNotifier ─▶ BizNotifyDeliveryJob（WebhookNotifyChannel）──签名推送──▶ 第三方
```

### 6.2 刷卡 POS：餐饮账单 → 终端刷卡 → 结果

```
POS App 结账 ─▶ 餐饮核心 ─▶ PaymentPort.createPayment(bizType=FOOD_CHECK, storeId) ─▶ payOrderNo
POS App ──POST /terminal/pay/v1/payments/{payOrderNo}/card（EncryptedCardData）──▶ TerminalSignatureFilter
  ─▶ TerminalAuthService ─▶ PayCaller(TERMINAL, storeScope={终端门店})
  ─▶ TerminalPaymentController ─▶ InStorePaymentService.chargeCard
      ─▶ PaymentService.start（冻结路由）─▶ CardPaymentGateway.charge
      ─▶ 同步结果 → PaymentOutcomeService.apply；不明 → UNKNOWN + 安排查单
POS App 轮询 GET .../payments/{payOrderNo}；UNKNOWN 不许关单或换方式
Outbox(PaymentSucceededEvent) ─▶ PaymentSucceededConsumer ─▶ 餐饮账单结清
打烊 ─▶ POST /terminal/pay/v1/batches ─▶ TerminalBatchService ─▶（不一致）TerminalBatchAxis
```

### 6.3 内部调用：商城下单 → 微信支付 → 订单已付

```
OrderServiceImpl 去支付 ─▶ SettlePort.initPayment（♻️ 转调）─▶ PaymentPort.createPayment + startPayment
  内嵌：PaymentPortImpl ─▶ InternalCallers.internal(MALL, storeId) ─▶ PaymentService
  独立：RemotePaymentPortImpl ─▶ PayInternalApi ─▶ InternalCallerFilter ─▶ InternalPaymentEndpoint ─▶ PaymentService
  ─▶ OnlinePaymentGateway（微信 JSAPI，凭据来自 ConfigChannelCredentialProvider ⏸）─▶ SdkParams
渠道回调 ─▶ PayCallbackEndpoint ─▶ PaymentOutcomeService ─▶ Outbox(PaymentSucceededEvent)
  ─▶ PaymentSucceededConsumer ─▶ 订单 markPaid
售后退款 ─▶ PaymentPort.requestRefund（带商家操作员）─▶ RefundService ─▶（复核）─▶ RefundDispatcher ─▶ RefundExecutionService
```

---

## 7 测试清单（按路径）

| 路径 | 测试类 | 守什么 |
|---|---|---|
| 公共 | `CallerPolicyMatrixTest` | 矩阵逐格（含全部禁止格） |
| 公共 | `SingleOutcomeWriterTest`（架构） | 只有 `PaymentOutcomeServiceImpl` / `RefundOutcomeServiceImpl` 能把单子推到终态 |
| 公共 | `PayCallerFactoryTest`（架构） | `PayCaller` 只在四个合法构造点被创建 |
| 公共 | `GatewayStatelessTest` · `RecordingClientOnlyTest`（架构） | 网关不持密钥字段；网关只能注入 `RecordingChannelClient` |
| 公共 | `PaymentOutcomeServiceTest#notify_twice_books_once` · `#late_payment_creates_exception` | 幂等入账；关单后支付不静默成功 |
| 开放 API | `SignatureCanonicalizerTest`（固定示例向量） | 签名规则与对外文档逐字一致 |
| 开放 API | `OpenApiSignatureFilterTest#{bad_sig,skew,replay,ip,scope,store_not_granted}_rejected_before_controller` | 鉴权失败不进 controller、不落库 |
| 开放 API | `OpenPaymentControllerTest#same_out_order_no_same_order` · `#different_body_same_key_rejected` | 幂等与请求摘要 |
| 开放 API | `BizNotifyDeliveryJobTest#webhook_signed_retry_backoff_give_up` | 推送签名与重试 |
| 刷卡 POS | `TerminalAuthServiceTest#{inactive,lost,bad_sig,replay}_rejected` | 终端鉴权 |
| 刷卡 POS | `InStorePaymentServiceTest#timeout_is_unknown_not_failed` · `#cancel_queries_first` | 「终端已扣、我方已关」 |
| 刷卡 POS | `TerminalVoidServiceTest#requires_manager_code_and_daily_limit` | 撤销授权与限额 |
| 刷卡 POS | `TerminalBatchServiceTest#mismatch_goes_to_recon` | 日结比对 |
| 刷卡 POS | `SandboxTerminalKeyIssuerTest#refuses_production_profile` | 沙箱实现不会进生产 |
| 内部 | `PaymentPortImplTest#refund_without_operator_rejected` | 内部系统不能以系统身份退款 |
| 内部 | `InternalCallerFilterTest#food_cannot_call_mall_refund` · `#constant_time_compare` | 按调用方授权（修 R12） |
| 内部 | `PaymentPortLocalRemoteParityTest` | 内嵌与独立两种实现对同一输入结果一致 |
| 内部 | `SettlePortImplTest` 现有用例全绿 | 转调后行为不变，「首次 `out_trade_no` = 订单号」保持 |

---

## 8 与旧类清单的差异

| 旧（TDD-支付服务-代码结构与类清单） | 本篇 | 原因 |
|---|---|---|
| 收款账户归商家域，`MerchantQueryPort#collectableRoutesOf` | 归支付，`spi.pay.PayMerchantQueryPort` + `PayMerchantQueryPortImpl` | 完整设计方案 D2 |
| controller 在 `shop-app` 的 `portal.<端>.pay` | 在新模块 `pay-portal` | 完整设计方案 D1 |
| 按业务号关联（`payMerchantNo`、`payOrderNo` 作参数） | 领域层一律 `Long id`；业务号只在 controller 边界转换 | 表结构 v3 |
| `ChannelCredentialVault` 信封加密 | `ChannelCredentialProvider` 接口 + ⏸ 配置实现 | 密钥模块搁置 |
| `PayCaller.entityScope` | `PayCaller.storeScope`（门店 id 集合） | 统一按门店 id 关联 |
| 内部调用经 `payclient.*AppService` | 内部调用经 `spi.pay.PaymentPort`（本地 / 远程两个实现） | 内部系统是跨域调用，按 java.md 走 spi Port |
