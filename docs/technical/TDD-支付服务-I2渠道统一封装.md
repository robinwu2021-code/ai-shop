# TDD-支付服务-I2渠道统一封装

状态：实施中
档位：1 · 依据：[开发计划 · 支付服务](./开发计划-支付服务.md) §5 I2 · [渠道接入统一封装设计](./支付服务-渠道接入统一封装设计.md) §2–§17 · [数据库表结构说明 v4](./TDD-支付服务-数据库表结构说明.md)
创建：2026-10-25 · 最后更新：2026-10-25

把「支付 → 渠道」从「每个渠道一个网关类、各自重试各自记日志」改为**命令 + 管线 + 处理器**：
对上一个门面，中间一条固定顺序的拦截器链（记录、日志、幂等、熔断、隔离、重试、超时、四态分类），底下每个「渠道 × 场景 × 操作」一个处理器。
同时落四向记录（请求 / 调用 / 回调 / 答复）、「未知」补查、适配器生命周期与统一回调。**CN 现有支付行为不变。**

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

S2 是新组件、无旧行为可先红：一次性消融 5 处（重试、熔断粒度、未登记码、精度、可用性范围）→ 7 条测试变红，还原后 pay-channel 84 / 84 绿。

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `MapperSmokeTest`（每个 Mapper 查自己的表）· `SchemaDriftTest` · `PayBaseEntityTest` · 本机 MySQL 启动执行 V4–V6 | ✅ | 把 `PayChannelCallLog.invocationNo` 改名 → `MapperSmokeTest` 红（`Column "invocation_no_x" not found`）✅ |

## §6 对账二 · 设计 → 实现

（实现后填）

### 偏差说明

0. **管线 13 环先落 11 环**：指标（60）与限流（80）推迟 —— 指标要 Micrometer 进 pay-channel、限流要按产品读 `refund_interval_seconds`，二者都不影响「钱打对、记对」；调用量与耗时今天可由调用记录（`elapsed_ms`）与结构化日志得到。

1. **`sys_pay_channel_market` 只加 `status` 列、不改语义**：v4 模型要改为「无行 = 不可用」，与现有「空集 = 不限市场」相反（I1 F14 刚确认），且改变现有渠道可用性。语义切换随新渠道上线（I8）。
2. **新渠道（蓝新 / LINE Pay / PayBy）的 `sys_pay_channel` 行留到 I8**：今天没有处理器，开了也路由不到。
