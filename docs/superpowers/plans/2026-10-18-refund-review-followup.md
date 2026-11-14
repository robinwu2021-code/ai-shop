# 退款恢复第四轮 Review 修复计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**执行记录：** [第四轮修复执行记录](../../technical/archive/records/退款Review第四轮-修复执行记录-2026-10-18.md)。T1–T4 代码已完成，定向验收通过；生产 MySQL 专项及完整发布门禁仍待执行。实际接口裁决与验收范围以执行记录为准。

**Goal:** 修复迟到成功误走失败补偿、历史失败无法回查、人工异常对账丢失和单笔异常中断整轮扫描四项问题。

**Architecture:** 保留现有两条现金退款执行链，共用退款安全结果解释规则。通过现有 SPI 将可信终止、待人工和原支付冻结信息传递给售后及积分补偿；对账保留类型化结果，逐笔隔离异常，复用现有运营差异队列。

**Tech Stack:** Java 21、Spring、MyBatis、现有 payTxManager、JUnit、H2、原生数据库定向验证。

**Spec:** `../specs/2026-10-18-review问题修复方案.md` 的未知不释放、迟到成功冻结、历史现金回查和人工恢复约束；本文件是第四轮 Review 的增补设计及执行清单。此前 A–F 的局部验收记录保持原样，本轮四项均待实施。

## Global Constraints

- 工作区：`/Users/robin/.codex/worktrees/points-prod-integration/ai-shop-codex`；分支：`codex/points-prod-integration`；保留已有未提交修改。
- 积分仍为 pay 内独立业务模块；以门店为账务基本维度，主体负责签约授权；不新增 JAR、服务或退款引擎。
- 不迁移旧积分数据、不恢复旧积分引擎；保留现金收款、退款与订单历史。
- UNKNOWN、PROCESSING、MANUAL_REQUIRED 和无可信终止证据的 FAILED 不得释放容量、自动补偿或生成替代退款。
- 可信终止后的必要补偿完成，原售后仍待运营重试或关闭；不得自动创建新退款。
- 外部渠道调用在数据库事务外；补偿发出后的未知结果只能查询原补偿号，不能假定未发送。
- 实施时分批小范围测试，5 秒慢例阈值、15 秒进度日志；无 30 分钟总时长限制。本方案不包含部署。

## Review Focus

1. 迟到成功在补偿准备后、发送认领前到达：不得新发补偿；已经发出的补偿继续原号查询。
2. FAILED 无证明而通道返回 PROCESSING：维持占用及原号查询，不进入现金失败分支。
3. FAILED 无证明且旧记录没有请求快照：使用既有不可变退款坐标查询，不重建发送请求；坐标不足转人工。
4. ABNORMAL 后再遇网络失败：不得用 UNKNOWN 覆盖已知人工异常，也不能自动解除冻结。
5. 一笔 CLOSED 回执金额错误或数据库写入失败：该笔不变成可信失败，后续正常退款仍完成核对。

## 1. 统一状态和接口设计

新增 `backend/shop-base/src/main/java/ai/neargo/shop/spi/settle/RefundSafety.java`：

`record RefundSafety(RefundQueryResult.Status outcome, boolean terminalVerified, boolean originFrozen, String errorCode)`。

在 `SettlePort.RefundEvidence` 与 `PointsCashRefundReadStore.CashRefund` 追加 `RefundSafety safety`。缺失 safety 的旧构造/夹具默认 UNKNOWN、terminalVerified=false，不从 FAILED 推导可信终止；更新资金测试夹具以提供真实证据，不用默认可信值使测试通过。

新增 `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/policy/RefundOutcomePolicy.java`，提供纯策略 `resolve(StlPayment row, boolean originFrozen): RefundSafety`，由两条读链及对账共用，避免三处各自解释状态。原支付冻结状态由受信查询补齐，不让来源或客户端传入。

| 持久化事实 | 对外资金结果 | 自动动作 |
|---|---|---|
| LATE_SUCCESS，无论普通 status 是什么 | MANUAL_REQUIRED，保留异常码及原支付冻结 | 阻止新发送、补偿提交、abort、释放及运营替代退款；保留查询与审计 |
| 已核验成功且无矛盾事实 | SUCCEEDED | 幂等完成；原支付冻结不抹去已核验成功事实 |
| FAILED + 合法终止证明 + TERMINAL_FAILED | TERMINAL_FAILED，terminalVerified=true | 按既有补偿/安全释放流程处理；若原支付被冻结则禁止新资金动作 |
| FAILED 且无终止证明 | UNKNOWN，或后续可信回查得到的 PROCESSING/MANUAL_REQUIRED | 查询原退款；不补偿、不释放 |
| ABNORMAL 或已持久化人工异常 | MANUAL_REQUIRED | 创建运营差异；后续网络未知不清除该事实 |
| INIT/PENDING、无可信终态 | UNKNOWN/PROCESSING | 保留容量；查询不触发重新发送 |

`RefundDispatchResult` 暂保留四值枚举：人工异常返回 UNKNOWN + 明确异常码 + retryable=false。来源业务通过新增 safety 判断，不能把 UNKNOWN 当成可重发。现有 HTTP URL 和门店授权保持不变；DTO 必须显式区分普通失败与待人工。

数据库先复用 V465 的 query_state、terminal_proof、capacity_state 和原支付 FROZEN，不新增业务状态列，不修改已应用迁移。现有对账差异表承载人工队列；仅确有索引缺口时追加新迁移，禁止为此批量改写历史 FAILED。

## 2. T1：P1 迟到成功与补偿安全边界

**修改文件：**

- `backend/shop-base/src/main/java/ai/neargo/shop/spi/settle/SettlePort.java`
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/RefundExecutionServiceImpl.java`
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/PaymentLedgerServiceImpl.java`
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/RefundCapacityLedger.java`
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/points/store/PointsCashRefundReadStore.java`
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/points/store/impl/MybatisPointsCashRefundReadStore.java`
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/mapper/points/PointsConfigurationMapper.java`
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/points/refund/impl/PointsRefundCompensationServiceImpl.java`
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/points/refund/impl/PointsRefundAbortServiceImpl.java`
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/AfterSaleServiceImpl.java`
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/points/impl/OrderPointsRefundCoordinatorImpl.java`

**Interfaces:** 产出上述 RefundSafety 与 RefundOutcomePolicy；`PointsCashRefundReadStore.lockRefund(String paymentNo): CashRefund` 在调用方 pay 事务中按原支付→退款行顺序锁定并读取，供补偿及撤销的权威校验使用，其 MyBatis adapter 负责调用锁查询和纯策略。积分应用服务依赖自身 store 接口，不直接依赖 pay 的 ServiceImpl。读取不改变资金状态，不调用渠道。

- [ ] 先新增 `RefundOutcomePolicyTest`：LATE_SUCCESS+FAILED 返回 MANUAL_REQUIRED；FAILED 无证明不是可信终止；被冻结的原支付不把已核验成功改成失败。
- [ ] 在 `RefundCapacityFlowTest` 增加迟到成功后 query/evidence 一致性断言；在 `AfterSaleRefundExecutionTest` 增加不得触发补偿、abort、释放或新退款断言。先定向运行，确认失败是待修复行为。
- [ ] 实现共享策略和两个证据 DTO，普通 query 先判断资金异常，再判断普通 status；来源层及 coordinator 的现金失败分支必须检查可信终止和非冻结条件。
- [ ] 在补偿 prepare、首次 submit claim 和无补收 abort 底层再次校验权威资金事实。涉及原支付和积分准备的入口先锁原支付/退款，再进入既有 type/pool/准备记录锁序；对所有本次修改入口核对锁图，禁止新增相反锁序。
- [ ] 已有补偿处于 COMPENSATING/UNKNOWN 时，即使后来出现 LATE_SUCCESS，仍允许 query 原补偿号和接收真实结果；禁止重新 submit。已发送的补偿不能被“撤销状态”伪装成未发生；保留真实证据，原支付继续冻结，交人工核对。
- [ ] 在 `RefundCapacityDatabaseTest` 用屏障验证“迟到成功先提交→新补偿 claim 被拒绝”；同时验证“补偿已 claim/发送→迟到成功”仍能原号查询，不自动释放或反向付款。外部发送与回调不存在数据库原子性，此竞态按异常处置，不能承诺绝不发生。
- [ ] 定向复验以上类的新增方法；记录 RED/GREEN、每例耗时及缺报告情况。形成可选择提交的本任务 diff，禁止打包其他已有修改。

## 3. T2：P2 历史失败的原号恢复

**修改文件：** `RefundExecutionServiceImpl.java`、`SettleServiceImpl.java`、`ReconServiceImpl.java`、`RefundCapacityLedger.java`，均位于现有 pay-domain；`SettleMappers.java` 仅在筛选或分页查询需要时修改。

**Interfaces:** 保留 `query(String executionNo)`、`refreshRefund(String paymentNo)` 与 `checkStalePayments(long now)`；它们消费 T1 的 RefundSafety，历史读取不要求构造发送命令。

- [ ] 在 `RefundOriginalExecutionRecoveryFlowTest` 增加三组失败用例：无证明 FAILED 回查成功、回查 CLOSED 补证、回查 PROCESSING/UNKNOWN 持续占用。断言渠道 refund submit 调用为 0。
- [ ] 两条查询入口取消“一切 FAILED 均直接返回”的分支；无证明 FAILED 可查，已有可信终止/成功幂等返回，已知人工异常可查但不能重发。历史缺 request_json 时使用原行 outTradeNo/channel 查询；缺失坐标则返回明确人工异常，禁止编造坐标。
- [ ] 对账候选增加 REFUND+FAILED+无可信终止证明；保留现有 PAY 范围不扩大到失败收款。查询 UNKNOWN 不把历史 FAILED 当终止，查询成功走原核验入口，可信关闭才写证明；新记录必须继续遵循持久化意图后发送。
- [ ] 为缺快照但坐标完整、坐标不足、历史重复回查分别增加断言；不得自动释放历史额度，也不因回查创建新退款号。
- [ ] 定向运行恢复测试和 `RefundCapacityFlowTest#unknownAndUnprovedHistoricalFailuresKeepCapacity`；原生候选 SQL 用 EXPLAIN 检查。如需新索引追加迁移并同步 schema-test，不覆盖旧脚本。

## 4. T3：P2 人工异常进入运营对账队列

**修改文件：**

- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/ReconService.java`
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/ReconServiceImpl.java`
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/recon/PaymentReconAxis.java`
- `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/PaymentReconReconciler.java`

**Interfaces:** `ReconService.Finding` 追加可空 `RefundQueryResult.Status refundOutcome` 与 `String errorCode`；非退款传 null；历史构造器可保留但不得用于新退款判断。`recordFinding(Finding,String,String)` 保持现有签名。两个扫描入口均使用相同人工异常归档规则，不依赖只有某个定时任务才会调用的分支。

- [ ] 在 `ReconFlowTest` 为 MANUAL_REQUIRED、LATE_SUCCESS、可信 CLOSED 各增加断言：运营待办有明确原因及原退款号，PROCESSING 不误报人工异常。
- [ ] 不再将人工结果压缩成三个布尔值后丢弃；持久化人工差异使用现有 PLATFORM_ONLY 类型，说明区分 REFUND_MANUAL_REQUIRED、REFUND_LATE_SUCCESS_FROZEN 与 REFUND_TERMINAL_CLOSED，不增加第二套运营页面。
- [ ] 复用现有账期+支付流水+差异类型幂等规则，重复扫描不重复建同类待办；已有未解决异常的证据更新不能被网络 UNKNOWN 清除。已解决后仍持续异常，下一账期再次发现可再次形成待办。
- [ ] 来源回调检测迟到成功时须留下可扫描的异常事实；对账候选包含该异常，避免 FAILED 被排除后永久不可见。记录运营差异失败时保留异常事实并重试，不能将其当作归档成功。
- [ ] 两个运营扫描入口分别验证可见性。resolve/ignore 只记录处置意见，不解除资金冻结、释放额度或发送退款；保持既有权限和响应 URL。必要 DTO 同步及 API 文档随本任务完成。
- [ ] 定向复验 `ReconFlowTest` 新方法、`ReconAxisFlowTest` 涉及方法及 `RefundCapacityFlowTest` 迟到场景。

## 5. T4：P2 对账逐笔隔离

**修改文件：** `ReconServiceImpl.java`、`PaymentReconReconciler.java`；测试优先新增轻量 `backend/pay/pay-domain/src/test/java/ai/neargo/shop/pay/service/impl/RefundReconIsolationTest.java`，复用现有 Mapper/Port mock。

**Interfaces:** 沿用 T3 的 Finding；单笔查询或证据入库失败返回 queryFailed=true、errorCode=REFUND_RECON_RECEIPT_INVALID 或 REFUND_RECON_WRITE_FAILED。失败不返回 TERMINAL_FAILED，不增加已修复数，不吞掉整轮故障信息。

- [ ] 写三笔扫描的失败测试：第一笔 CLOSED 金额不符、第二笔查询抛异常、第三笔可信成功；断言结果数=3，前两笔 deferred，第三笔保留成功发现并可由既有确认链处理。
- [ ] 将查询、receipt 校验、observe 持久化与结果转换置于每笔异常边界。每笔 pay 写入独立事务；失败回滚该笔，整轮不包事务。
- [ ] 差异归档本身失败也不能阻断后续流水；日志记录 paymentNo/outRefundNo/错误类别，不输出凭据或完整敏感响应。计数区分扫描、延后、待人工与修复，不能把“本轮执行完”当成“全部成功”。
- [ ] 增加观察写入失败、归档失败和最后一笔失败的断言；确认失败笔仍保留容量并可在下一轮恢复。
- [ ] 运行 `RefundReconIsolationTest` 与 T3 涉及的对账方法；检查重跑幂等、已成功处理不回滚。

## 6. 执行、验收与交付

顺序：T1 → T2 → T3 → T4 → 一次相关范围联合验证。T3 的异常候选覆盖与 T2 的历史候选查询统一实现，不各自复制 SQL。每项先失败用例、确认 RED、实现、定向 GREEN，再记录结果；本轮不启动多代理并行编辑。

执行模板（在目标工作区根目录，TEST_SELECTOR 替换为当批精确类或 `类#方法`）：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home python3 scripts/run-backend-tests.py --slow-seconds 5 --progress-seconds 15 --timeout 180 --output /tmp/refund-review-followup -- mvn -f backend/pom.xml -pl shop-app -am '-Dtest=TEST_SELECTOR' -Dsurefire.failIfNoSpecifiedTests=false test
```

轻量策略/隔离测试使用 `-pl pay/pay-domain`，不为纯策略启动整应用。180 秒是单次定向命令的异常看门狗；超限保留本次进度及不完整报告，定位后拆小命令，不用反复增加超时掩盖挂起。用户无总时长限制。

- [ ] 联合验证 T1–T4 直接影响的退款/补偿/对账测试，检查跳过、缺报告、失败和慢例，使用本次独立报告目录作为证据。
- [ ] 仅对新增锁序并发场景及候选 SQL 做原生数据库验证；MariaDB 结果不得写成生产同版本 MySQL 通过。
- [ ] 同步 API 清单、现金失败/人工恢复设计、执行记录；标注本轮四项完成情况，保留此前全量 133 条失败基线，不因局部通过宣称整合分支全绿。
- [ ] 自查旧构造器、raw FAILED/CLOSED 判断及 Finding 消费方，确认没有遗漏底层补偿/abort、运营重试和轴扫描入口。
- [ ] 输出代码清单、逐项 AC→测试证据、剩余发布门禁和可选择提交的 diff；本方案阶段不提交业务代码、不推送、不部署。

完成标准：已知迟到成功不会触发新的自动补偿；无证明历史失败可查询而不重发；人工异常运营可见且处置不隐式解冻；单笔故障不阻断后续对账。四项均有对应测试证据才关闭本轮 Review。
