# TDD-支付服务-I2渠道统一封装

状态：已实现
档位：1 · 依据：[开发计划 · 支付服务](./开发计划-支付服务.md) §5 I2 · [渠道接入统一封装设计](./支付服务-渠道接入统一封装设计.md) §2–§17 · [数据库表结构说明 v4](./TDD-支付服务-数据库表结构说明.md)
创建：2026-10-25 · 最后更新：2026-10-25

把「支付 → 渠道」从「每个渠道一个网关类、各自重试各自记日志」改为**命令 + 管线 + 处理器**：
对上一个门面，中间一条固定顺序的拦截器链（记录、日志、幂等、熔断、隔离、重试、超时、四态分类），底下每个「渠道 × 场景 × 操作」一个处理器。
同时落四向记录（请求 / 调用 / 回调 / 答复）、「未知」补查、适配器生命周期与统一回调。**CN 现有支付行为不变**，只有一处有意收紧：回调里渠道确认的金额与交易不符、或路径收款商户不符时不再推进成功（S7）。

## 切片

| 片 | 内容 | 提交 |
|---|---|---|
| S1 | 迁移 4–6（`db/pay` V4–V6）：支付产品与地区、四向记录、补查与生命周期表；6 个实体与 Mapper | — |
| S2 | 通道模型（`ChannelKey` / 命令 / 四态 `ChannelResult`）+ 执行器 + 拦截器链 + 注册表与启动校验 + 容错 | — |
| S3 | 记录与日志：发送前写 `pay_channel_call_log`（五段金额、来源两维）、`PayLogger` | — |
| S4 | 现有微信 / 支付宝 / 测试网关适配为处理器；下单、退款、查单切到门面 | — |
| S5 | 「未知」补查：`FollowUpScheduler` + `ChannelFollowUpJob` | — |
| S6 | 生命周期：`ChannelLifecycleManager`（`SmartLifecycle`）、关闭排空 | — |
| S7 | 统一回调：`/pay/callback/{channel}/{notifyType}/{ref}` + 旧地址别名 + `pay_callback_log` | — |
| S8 | `stl_channel_message` 停写开关 `shop.pay.log.recording` | — |

## §0 对账一 · 需求 → 设计

| AC | 验收（来自设计 §17 / 开发计划 I2 完成标准） | 片 |
|---|---|---|
| AC1 | 迁移 4–6 在 H2 与 MySQL 9.7 上都能执行；新表实体与表逐列对齐 | S1 |
| AC2 | 管线顺序固定：记录在发送前、分类在最内层（`InterceptorOrderTest`） | S2 |
| AC3 | 注册表三项启动校验各有反例 → 启动失败（`HandlerRegistryStartupTest`） | S2 |
| AC4 | 退款 / 刷卡 / confirm 永不重发；配置只能收紧（`FaultPolicyTest`） | S2 |
| AC5 | 超时、5xx、未登记渠道码、验签失败一律 `Unknown`（`OutcomeClassifierTest`） | S2 |
| AC6 | 一个商户熔断不影响同渠道其他商户（`CircuitBreakerTest#per_merchant_isolation`） | S2 |
| AC7 | 每次调用在发送前落 `pay_channel_call_log`，五段金额与来源两维完整（`AmountTraceTest` · `SourceTraceTest`） | S3 |
| AC8 | CN 一笔支付 + 一笔退款能在 `pay_channel_call_log` 查到完整记录 | S4 |
| AC9 | 补查与回调同时到达只入账一次（`FollowUpFlowTest#unknown_resolved_by_query_books_once`） | S5 |
| AC10 | 关闭时在途调用排空，超时的转 `Unknown` 补查（`LifecycleDrainTest`） | S6 |
| AC11 | 回调模板各失败分支（验签、商户不符、金额不符、重复）；旧回调地址仍可用（`CallbackTemplateTest`） | S7 |
| AC12 | 渠道码分支只在处理器内；处理器不重试、不打业务日志、不注入 Mapper（架构守卫） | S2 / S4 |

## §1 现状与影响面

- 现有通道层：`PayGateway`（三态 `Result`）、`AbstractPayGateway#exec`、`PayGatewayRouter`（按渠道名一张 Map）、`ChannelMessageRecorder` → `stl_channel_message`。
  直接调用方 4 个：`SettleServiceImpl`、`PayPointsFundsAdapter`、`RefundExecutionServiceImpl`、`OrdinaryPaymentInitiationServiceImpl`。
- 回调：`shop-core` 的 `ChannelPayCallbackController`（`/pay/callback/{channel}`、`/{channel}/refund`）与 stub 回调。
- 不受影响：端上接口、权限码、i18n。

## §2 方案

### S1 · 迁移 4–6 与实体
- `db/pay/V4__pay_channel_product.sql`：`pay_channel_product`、`pay_channel_product_market`；`sys_pay_channel` 加 `callback_style` / `sandbox_host` / `prod_host` / `supports_statement`；`sys_pay_channel_market` 加 `status`。种子：`WECHAT_JSAPI_DIRECT`（归集）、`WECHAT_JSAPI_SP`（收付通）、`ALIPAY_APP_SP`（DISABLED，客户端未实现）、`TEST_ONLINE`，各一条 CN / CNY / 精度 2 的地区行。
- `db/pay/V5__pay_four_way_records.sql`：`pay_api_request_log`、`pay_channel_call_log`、`pay_callback_log`、`pay_raw_payload`、`pay_biz_notify`、`pay_biz_notify_attempt`。
- `db/pay/V6__pay_channel_runtime.sql`：`pay_channel_followup`、`pay_channel_lifecycle_log`。
- DDL 取自结构化模型 v4 生成的 `pay-service-review.sql`，逐字未改（无 ENGINE / CHARSET / COLLATE）。
- 实体（`pay-channel` · `ai.neargo.shop.pay.channel.entity`）：`PayChannelProduct` · `PayChannelProductMarket` · `PayChannelCallLog` · `PayCallbackLog` · `PayChannelFollowup` · `PayChannelLifecycleLog`，Mapper 进 `ChannelMappers`。
- `PayBaseEntity` 从 `pay-domain` 移到 `pay-channel`（包名不变）：通道层的记录实体要继承它，而 `pay-channel` 不能依赖 `pay-domain`。
- `pay_api_request_log`、`pay_biz_notify(+attempt)`、`pay_raw_payload` 的实体随用到它们的迭代（I3 / I5）建。

### S2 · 通道模型、执行器、注册表、容错
- `channel.api`：`ChannelKey`（+ `Pattern`：地区 null = 任意、场景 ANY = 任意，具体度 = 地区 2 + 场景 1）· `AccessMode` · `PayScene` · `ChannelOperation`（`readOnly` / `resendSafeAfterUncertainty`）· `Money` · `CallerType` · `ClientType` · `SourceInfo` · `InvocationMeta` · `ChannelAccountContext`（含币种与渠道精度）· 四态 `ChannelResult` · `PayErrorCode` · `ChannelTransportException`（6 种发送问题）· 门面 `ChannelGateway`。
- `channel.api.command`：`ChannelCommand`（sealed）+ `PayCommand` / `QueryCommand` / `CloseCommand` / `RefundCommand` / `RefundQueryCommand`。confirm / void / 进件 / 对账单 / 出款随用到它们的迭代加入。
- `channel.invoke`：`ChannelInvoker`（构造时排序冻结、同序号即抛；执行前后通知监听者）· `Chain` · `Invocation`（`requestSent` 区分「没发出去」与「发出去了不明」）· `InvocationState` · `InvocationListener`。
- `channel.handler`：`ChannelHandler`（不重试 / 不写库 / 不打业务日志）· `UnsupportedHandler`（空对象）· `ChannelHandlerRegistry`（最具体者优先；启动校验 ① 同等具体两处理器 ② ENABLED 产品无收款处理器）· `ChannelProductCatalog`（接口，DB 实现在 S4）。
- `channel.fault`：`FaultPolicy` · `FaultPolicyRegistry`（按操作的默认值；`override` 只能收紧）· `ChannelErrorMapper`（未登记码 → 空）· `OutcomeClassifier`（§8.2 逐行）。
- `channel.invoke.interceptor`（S2 八环）：追踪 10 · 校验 20（金额 > 0、币种 = 地区币种、按 `java.util.Currency` 小数位与渠道精度判余数、退款 ≤ 原单）· 可用性 30（只拦新收款）· 熔断 90（Resilience4j，按渠道 × 收款商户；查询放行）· 隔离 100（Resilience4j，按渠道并发上限，满了立即 `CHANNEL_BUSY`）· 重试 110（没发出去可重发；纯读不明可重发；写操作发出后不重发）· 超时 120（虚拟线程，超时 = `Unknown`）· 分类 130（最内层）。记录 / 日志 / 指标 / 幂等 / 限流（40–80）在 S3。
- 依赖：`resilience4j-circuitbreaker` / `-bulkhead` 2.3.0，版本钉在根 pom（Boot BOM 不管），依赖收敛通过。
- **S2 不注册 Spring bean**：种子里两个微信产品是 ENABLED，注册表的启动校验在 S4 有处理器前会让应用起不来；S2 是纯类 + 单测，S4 统一装配。

### S3 · 调用记录、结构化日志、幂等
- `channel.invoke.record`：`CallLogStore`（读写口）· `MybatisCallLogStore`（**独立事务**：业务回滚不带走「调没调渠道」）· `ChannelCallRecorder`（追踪号、调用号、五维键、关联 id、来源两维、请求金额 / 按精度的金额字符串 / 响应金额 / 手续费、四态与统一错误码、容错摘要与熔断状态；写失败只计数不阻断）。
- `channel.log`：`PayLogger` · `JsonPayLogger`（单行 JSON 固定字段，成功 INFO / 失败 WARN / 不明 ERROR；不写报文）。
- 拦截器：记录 40（发送前开行；借监听者在每次再发时关行开行，重试每次一行，`retry_of_id` 串起）· 日志 50 · 幂等 70（同渠道同操作同单号已明确成功 → 返回记录结果不再发；纯读不拦）。
- `http_method` / `url_path` 发送前写占位 `-`，由传输层（S4 `RecordingChannelClient`）覆盖成真实值。

### S4 · 现有网关接入管线
- `channel.legacy.LegacyGatewayHandler`：每个现有 `PayGateway` 一个过渡适配（开发计划风险应对里的「兼容组合」），支持 PAY / QUERY / REFUND × 任意场景。结果映射逐字照原调用方：下单被拒 → Failed、无回执 → Unknown；查不通 → Unknown、没有这笔 → `TRADE_NOT_FOUND`；退款受理 → Pending、可重试或未被明确拒绝 → Unknown。
- `channel.legacy.LegacyAccessModes`：网关类 → 接入模式（微信直连 = MERCHANT_OWN；收付通、支付宝 = SERVICE_PROVIDER；测试 / stub / 免单 = PLATFORM_SUB）。此前模式藏在「装的是哪个类」里。
- `channel.legacy.LegacyChannelBridge`：下单、退款、查单三个调用点签名不变，内部走 `ChannelGateway` 再把四态译回旧类型 —— 调用方判断一行不改（改判断是 I3 的事）。零金额（免单）不进管线。三个调用点（`OrdinaryPaymentInitiationServiceImpl`、`RefundExecutionServiceImpl`、`PayQueryPortImpl`）以可选 setter 注入，手工构造的单测里没有时直调原网关。
- `channel.catalog.DbChannelProductCatalog`：每次现查产品 × 地区（几十行；运营停用要立刻生效）。场景由 `channel_method` 定（JSAPI / APP / TEST_PAY → ONLINE_SDK，NATIVE → ONLINE_QR），认不出时按 `pay_type` 兜。**只有明确停用的组合拦截**，未建模的渠道放行。
- `channel.config.ChannelPipelineConfiguration`：装配 11 环与执行器。注册表启动校验 ② 收窄为「本部署装了处理器的渠道 × 接入模式」，并挪到全部单例就绪后（`SmartInitializingSingleton`）—— 构造时支付库迁移可能还没跑。
- V4 的地区种子补 `NOT EXISTS` 幂等闸：H2 生成器据此把它认作种子而不是回填（见偏差 4）。

### S5 · 「未知」补查
- `InvocationListener#invocationEnded`：整次调用的最终结果（重试、分类之后）。
- `channel.followup.FollowUpScheduler`（监听者）：收款结果为 Unknown → 登记 `pay_channel_followup`（30 秒后首查）；`open_key` 唯一 → 同一笔只有一条进行中；登记失败只记日志。
- `channel.followup.ChannelFollowUpRunner#runOnce`：到期任务 → 乐观锁领取（多实例只有一个查）→ 以<b>原追踪号</b>查单 → 已付交 `FollowUpResolver` 入账并结案；渠道没有这笔 → 结案不入账；未付 / 查不通 / 不明 → 退避 30 秒起翻倍、封顶 1 小时；第 12 次仍无结论 → `ESCALATED`。
- `channel.followup.MybatisFollowUpStore`：独立事务（同调用记录）；调用坐标（地区、接入模式、币种）从原调用记录取。
- 应用层 `paybridge`：`PaymentFollowUpResolver`（`SettlePort.settlePayment` → `OrderRepairPort.markPaid`，与渠道回调同序同幂等键）· `ChannelFollowUpJob`（每分钟，`shop.job.enabled` 守卫，ShedLock）· `ChannelFollowUpConfiguration`。

### S6 · 生命周期与关闭排空
- `channel.lifecycle.ChannelLifecycleManager`（`SmartLifecycle` + 监听者 + 准入）：按首次发送登记在途、调用结束移除；`stop()` 停收新调用 → 等在途（`shop.pay.channel.drain-timeout`，默认 20s）→ 等不完的以 `Unknown("SHUTDOWN_DRAIN_TIMEOUT")` 通知补查登记后放行。相位 `DEFAULT_PHASE - 1536`：排在 Web 优雅关闭之后。
- `ChannelInvoker` 加准入：不收时直接 `Failed(CHANNEL_UNAVAILABLE, retryable)`，不进管线、不留调用记录（没发出去）。
- `pay_channel_lifecycle_log`：`ACTIVE→DRAINING`、`DRAINING→STOPPED` 两行，原因里带在途数与超时清单；渠道码记 `*`（应用级）。写失败只记日志（关闭期库可能已断）。

### S7 · 统一回调
- 地址：`POST|GET /pay/callback/{channel}/{notifyType}/{ref}`（`notifyType` = pay / refund，`ref` = 收款商户号，`route_style=PATH`）；旧地址 `POST /pay/callback/{channel}`、`/{channel}/refund` 保留为别名（`LEGACY`，`path_ref='-'`）。三者进同一个支付侧模板。
- `spi.pay.PayCallbackPort`（新 SPI）· `pay.port.PayCallbackPortImpl` · `pay.callback.CallbackTemplate`：① 先落 `pay_callback_log`（原文 SHA-256、验签前只存指纹与前缀、来源 IP 取代理头）→ ③ 验签 → ④ 取单号 → ⑤ 路径收款商户 ≠ 这笔的 `pay_merchant_no` → `MERCHANT_MISMATCH` → ⑥ 回查（同一管线）→ ⑤′ 渠道确认金额 ≠ 这笔金额 → `amount_matched=0`、不推进 → 重复识别（同一原文已入账、账还在、渠道事实一致 → `DUPLICATE` 回成功）。每步失败：落原因、回失败 ack 让渠道重推。
- ⑦ 入账与订单投影留在交易域控制器（`ChannelPayCallbackController` 只剩路由 + `settlePayment → markPaid` / `settleRefund`），之后 `handled` 回写 `APPLIED` / `REJECTED`。
- `channel.callback.CallbackLogRecorder`：独立事务；落库失败不挡回调。
- 注册表启动校验二（产品有处理器）挪到 shop-app `PayDataSourceConfig#channelProductCoverageCheck`，依赖 `PayMigrated`（见偏差 11）。
- 注册表启动校验三：装了外部渠道（非 `PLATFORM_SUB`）收款处理器、却没有 `ChannelCallbackVerifier` → 启动失败（`CallbackConfiguration#callbackCoverageCheck`）。

### S8 · 旧报文表停写开关
- `shop.pay.log.recording`：`both`（默认）= 照写 `stl_channel_message`；`new` = 回调与外发都只写四向记录。开关在 `ChannelMessageRecorder` 自身，回调模板拿到空报文号时自然跳过回填。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC2 | `ChannelPipelineTest$InterceptorOrder`（2 条：注入顺序打乱仍固定；同序号即抛） | ✅ | — |
| AC3 | `ChannelPipelineTest$Registry`（4 条：同等具体即抛；ENABLED 无处理器即抛；最具体者优先；无处理器 → `CHANNEL_UNSUPPORTED`） | ✅ | 见下：断「不可重试」的那条随重试消融变红 |
| AC4 | `ChannelPipelineTest$Fault`（4 条：退款超时只发一次且为 Unknown；配置只能收紧；纯读与未发出可重发；超时 = Unknown） | ✅ | 重试判据放宽 → `refundNeverResent`、`readOnlyAndNeverSentAreRetried` 红 ✅ |
| AC5 | `ChannelPipelineTest$Classifier#table` | ✅ | 未登记码改成 Failed → 红 ✅ |
| AC6 | `ChannelPipelineTest$Breaker#perMerchantIsolation` | ✅ | 熔断器按渠道（去掉商户）→ 红 ✅ |
| AC12（S2 部分） | `NoChannelBranchOutsideHandlersTest`（公共层无渠道码字面量 + 判据正反例） | ✅ | — |
| — | `ChannelPipelineTest$Guards`（精度余数不发；币种 / 退款上限；停用只拦新收款） | ✅ | 精度判据恒真 → 红；可用性拦全部操作 → 红 ✅ |
| AC7 | `ChannelRecordingTest`（6 条：11 环顺序；`amountTrace`；`sourceTrace`；`retryRowPerAttempt`；`recordingFailureDoesNotBlock`；`succeededRefundIsNotResent`） | ✅ | 去掉重试开新行 / 金额不按精度 / 关幂等 / 记录失败抛出 → 4 条各自红 ✅ |
| AC8 | `ChannelCallLogFlowTest#payQueryRefundEachLeaveACallLogRow`（TEST 渠道 CNY 12.34：收款 PENDING、查单 SUCCEEDED、退款 3.00 PENDING 各一行；接入模式 PLATFORM_SUB、金额字符串 `12.34`、来源 INTERNAL、三个追踪号） | ✅ | 去掉 `LegacyChannelBridge` 的 `@Bean`（调用点退回直调原网关）→ 红：`PAY … 应有且只有一行调用记录 Expected size: 1 but was: 0` ✅ |
| AC9 | `ChannelFollowUpFlowTest`（2 条：`unknownPrepayIsFollowedUpAndBooked` 下单超时 → 登记 → 用户付款后补查独自转已支付、查单沿用原追踪号；`followUpRacingCallbackBooksOnce` 补查与 stub 回调并发 → 一笔成功流水、订单只转一次、补查结案）· `ChannelFollowUpRunnerTest`（5 条：已付入账、没有这笔结案、退避与封顶、12 次转人工、一条进行中、多实例只领一次） | ✅ | ① 去掉补查登记 → 两条红（`EmptyResultDataAccess`）② 解析器不入账 → `unknownPrepay…` 红（订单仍待支付）③ 订单转已支付去掉「已支付即返回」→ `followUpRacing…` 红（落败一方抛错、补查未结案）✅ |
| AC10 | `LifecycleDrainTest`（2 条：`drainsInFlightBeforeStopping` 在途未完不放行、新调用 `CHANNEL_UNAVAILABLE`、完成后结果照常不登记补查；`drainTimeoutRegistersFollowUp` 200ms 超时 → 收款登记补查、日志列出 `WECHAT PAY OD1`） | ✅ | ① 准入恒放行 + 超时不通知补查 → 两条红 ② 不等在途（第一轮即跳出）→ `drainsInFlight…` 红「在途没跑完，不能放行关闭」✅ |
| AC11 | `CallbackTemplateTest`（6 条，带真实安全过滤链：`badSignature` · `merchantMismatch` · `amountMismatch` · `duplicate`（第二次 `DUPLICATE`、`markPaid` 恰一次）· `legacyAliasStillWorks` · `unknownTypeOrChannel`）· `CallbackCoverageCheckTest`（2 条：外部渠道无回调验签即抛；平台自有账户豁免）· 既有 `ChannelPayCallbackFlowTest` 14 · `StrictRefundCallbackFlowTest` 5 · `BasicRefundRecoveryFlowTest` 9 · `PhaseOneOrderEndToEndFlowTest` 2 全绿 | ✅ | 一次去掉 4 处（商户核对、金额核对、重复识别、启动校验三）→ `merchantMismatch` / `amountMismatch` / `duplicate` / `externalChannelWithoutCallbackFails` 各自红 ✅ |
| S8 | `ChannelMessageRecordingSwitchTest`（2 条：both 照写；new 回调与外发都不写、报文号为空） | ✅ | 回调入口去掉开关判断 → `newStopsLegacyWrites` 红 ✅ |
| AC1 | `MapperSmokeTest`（每个 Mapper 查自己的表）· `SchemaDriftTest` · `PayBaseEntityTest` · 本机 MySQL 启动执行 V4–V6 | ✅ | 把 `PayChannelCallLog.invocationNo` 改名 → `MapperSmokeTest` 红（`Column "invocation_no_x" not found`）✅ |

**全量回归**（2026-10-25，4031 条）：与 I0 基线（`fails5`，43 条存量）相比无新增失败；基线里 `M7SettleFlowTest.rateCardIsVisibleToMerchant` 转绿。第一次全量曾新增 65 条（pay-svc 上下文起不来带倒 62 条 + 两道守卫），按偏差 11 修正后这 65 条复跑全绿。

S2 是新组件、无旧行为可先红：一次性消融 5 处（重试、熔断粒度、未登记码、精度、可用性范围）→ 7 条测试变红，还原后 pay-channel 84 / 84 绿。

## §6 对账二 · 设计 → 实现

六个提交：`4c1df12bb` S1 · `360a6e0e9` S2 · `f416284bb` S3 · `9ec07d801` S4+S5 · `ca5d46a75` S6 · `c7afa5644` S7+S8。
`git diff --stat`（相对 I1 分支，不含生成的 `schema-test.sql`）：**103 个文件，+6442 / −185**。按包对 §2：

| 改动位置（文件数） | §2 | 对得上 |
|---|---|---|
| `db/pay` V4–V6（3）· `channel/entity`（6）· `pay/entity`（1，`PayBaseEntity` 搬家）· `mapper`（1） | S1 | ✅ |
| `channel/api` + `api/command`（20）· `channel/handler`（4）· `channel/fault`（4）· `invoke` + `invoke/interceptor`（17）· 根 pom 与 `pay-channel/pom.xml`（resilience4j） | S2 | ✅ |
| `invoke/record`（3）· `channel/log`（2） | S3 | ✅ |
| `channel/legacy`（3）· `channel/catalog`（1）· `channel/config`（1）· `channel/port/PayQueryPortImpl` · `pay-domain/service/impl` 下单与退款（2） | S4 | ✅ |
| `channel/followup`（6）· `shop-app/paybridge` 补查三件（3） | S5 | ✅ |
| `channel/lifecycle`（3）· `ChannelInvoker` 准入 | S6 | ✅ |
| `spi/pay/PayCallbackPort` · `pay-domain/callback`（2）+ `port/PayCallbackPortImpl` · `channel/callback`（1）· `trade/api/callback/ChannelPayCallbackController` | S7 | ✅ |
| `ChannelMessageRecorder` 停写开关 | S8 | ✅ |
| 测试：pay-channel 9 个类 · pay-domain 1 · shop-app 场景 3（`ChannelCallLogFlowTest` · `ChannelFollowUpFlowTest` · `CallbackTemplateTest`）· `MapperSmokeTest` 扩表 | §5 | ✅ |

没有 §2 之外的文件；§2 列了而没动的：无。

### 偏差说明

0. **管线 13 环先落 11 环**：指标（60）与限流（80）推迟 —— 指标要 Micrometer 进 pay-channel、限流要按产品读 `refund_interval_seconds`，二者都不影响「钱打对、记对」；调用量与耗时今天可由调用记录（`elapsed_ms`）与结构化日志得到。

1. **`sys_pay_channel_market` 只加 `status` 列、不改语义**：v4 模型要改为「无行 = 不可用」，与现有「空集 = 不限市场」相反（I1 F14 刚确认），且改变现有渠道可用性。语义切换随新渠道上线（I8）。
2. **新渠道（蓝新 / LINE Pay / PayBy）的 `sys_pay_channel` 行留到 I8**：今天没有处理器，开了也路由不到。
3. **S4 先挂过渡适配，不拆处理器**：渠道报文组装仍在原网关里，管线（记录、日志、熔断、隔离、超时、分类）先对现有渠道全量生效；逐个拆成独立处理器时只换适配这一层。退款结果查询（五态 `queryRefundOutcome`）与免单零金额仍走原路径 —— 前者的五态与四态不是一一对应，随 I3 退款单拆分一起改。
4. **改了未发布的 V4**：地区种子原为 `INSERT … SELECT`，H2 生成器按「回填」丢弃，测试库里产品全都「未开通地区」。补 `NOT EXISTS` 幂等闸后生成器认作种子。V4 只在本机库执行过（已改 `pay_flyway_history` 校验和），未进任何共享库。
5. **管线判出的明确失败在退款上译为「明确拒绝」**：管线里的 Failed 只有两种来源 —— 渠道明确拒绝，或根本没发出去（校验、熔断开、产品停用）—— 两者都可据以判失败；未发出的那种由售后重新发起。
6. **补查只跟收款**：退款「未知」已有 `RefundExecutionService.query` 与退款坐标恢复两条收口，且它要的是五态退款查询（偏差 3）；随 I3 退款单拆分接入。转人工今天只记 ERROR 日志、`pay_exception_id` 留空 —— `pay_exception` 表在 I5。`open_key` 用「操作|渠道|单号」而非表设计的「操作|stl_payment_id|pay_refund_id」：两个 id 要到 I3 才进调用元数据。
7. **生命周期先做应用级排空**：设计里「渠道 × 收款商户」实例的七态（含熔断带来的 DEGRADED / SUSPENDED）要等 `pay_merchant`（I4）；今天日志只记应用关闭这一种变化，渠道码记 `*`。
8. **回调模板的 ⑦ 入账留在交易域，入口仍在 `shop-core`**：设计把 ①–⑨ 都放在支付侧，但订单投影（`markPaid`）属于交易域；I3 结果入口与 Outbox 事件就位后，⑦ 改为支付域发事件、交易域消费，控制器随之迁入 `pay-portal` 的 CALLBACK 面（ADR-044 补充随 I3）。
9. **回调核对的边界**：收款商户比的是这笔交易落下的 `pay_merchant_no`（`pay_merchant` 表在 I4，`pay_merchant_id` 留空）；币种不比 —— 现有测试 / stub 网关查单恒报 CNY，比了会误拒；`notify_amount_minor` 记的是回查确认的金额（各家验签实现还没把通知里的声明金额规整出来）。重复识别放在回查之后而非验签之前：字节相同不代表事实相同，渠道改口（换了退款单号）要交入账一侧去拒（`StrictRefundCallbackFlowTest#confirmedRefundRejectsDifferentProviderReplay` 钉着）。进件 / 出款 / 跳回三种通知类型、原文加密落 `pay_raw_payload`、回调重放，随各自迭代。
10. **补查服务改名 `ChannelFollowUpRunner`**：`ArchitectureTest#serviceMustBeInterface` 要求 `*Service` 是接口 —— S5 提交时没跑架构测试，S7 一并改正。
11. **启动校验二装在 shop-app**：它读支付库的产品目录；独立的 pay-svc 不跑迁移、装配冒烟连空库（`PayApplicationBootTest`），放在 pay-channel 里会让 pay-svc 起不来。改为挂在跑迁移的进程上、显式依赖 `PayMigrated`。全量回归抓到的另外两处一并改：回调模板不再引用 `PayMessagePort` 的常量（反向依赖预算）；两张产品表登记为种子主数据（`TableHasProducerTest`，运营入口在 I5）。
