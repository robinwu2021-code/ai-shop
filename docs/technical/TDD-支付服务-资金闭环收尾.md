# TDD-支付服务-资金闭环收尾

状态：首批关单已实现（模块全测、定向集成通过）；后续队列未完成 · 2026-10-28
基线：0e686ab8f · 分支 codex/pay-business-closure
档位：1（支付关闭语义、内部服务契约、V17关闭意图列）；现有业务定义、ADR-045 和用户“按照建议执行”为执行依据。

## §0 需求与验收

依据：完整设计方案 AC15–AC19、AC22–AC23；W3 E04/E07/E09；支付接入守卫计划 M7。

| AC | 行为 | 落点 |
|---|---|---|
| C1 | 查单失败不得关闭本地支付单、撤销收银令牌或发送已关闭通知 | PaymentService.close |
| C2 | 渠道未付时按该尝试的冻结商户、子商户与密钥版本经 ChannelGateway 关单；全部确认后才本地关闭 | PaymentAttemptCloseService |
| C3 | 关单超时、失败、未支持不得冒充已关闭；重查只接受渠道明确已关闭；查无此单不排除在途创建 | 渠道四态结果 |
| C4 | 已付款进入原入账路径；本地终态重放不再次调用渠道；多次尝试未全部确认不得关闭支付单 | PaymentService.close |
| C5 | 关闭期间新出现尝试时保持未关闭；支付单已关闭后不得挂接新尝试 | 支付单行锁与 attachAttempt |
| C7 | 业务订单已取消、渠道未知后才到账：支付域留下 LATE_PAYMENT，可走现有异常退款，不能正常记成功；普通关单初查已付仍成功 | 持久 close_requested_at + 显式 cancelPayment |
| C6 | 支付宝关单使用 alipay.trade.close；测试银行关闭后不能再付款或重新下单 | AlipayChannel / TestPayGateway |

## §1 现状与影响面

渠道层已有 CloseCommand 和微信关闭处理器，PaymentService.close 只调用本地账本关闭，且查询失败仍会关闭。
支付宝和进程内渠道未支持 CLOSE。影响开放 API、收银台、POS 和内部 PaymentPort 的统一关单入口。
不改已应用迁移，不切生产流量，不写生产数据。保留关单后到账的现有异常退款语义。

## §2 实施计划

目标：按资金闭环 → 结果事件 → 操作界面 → 独立部署 → 新渠道/旧模型退役执行。
技术：Java 21、Spring、MyBatis、现有 ChannelGateway。沿既有服务接口/实现分包。

### Task 1: 查询不明时保留原支付状态
- [x] 新增 PaymentCloseSafetyTest，断言失败查询、缺回执不会关闭或通知。
- [x] 运行 pay-domain 定向测试，确认现实现错误关闭使测试失败。
- [x] 修改 PaymentServiceImpl.close，保留已支付/终态路径，未知不关闭。
- [x] 运行测试并记录红绿证据。

### Task 2: 真实渠道关闭与重试
- [x] 增加 PaymentAttemptCloseService 接口和 impl，用尝试冻结身份构造 ChannelRoute。
- [x] 测试成功、超时、明确失败、未支持、已关闭重查、旧商户密钥版本和多尝试。
- [x] 接入 PaymentService.close；渠道调用在数据库事务外，事务内复核尝试集合及支付单状态。
- [x] attachAttempt 在 CLOSED 时拒绝；测试关闭与新尝试竞争。
- [x] 支付宝与测试银行 CLOSE 能力测试先红，再实现。支付宝协议参考官方 https://aipay.alipay.com/docs/vibe-pay/ai-web-app-payment-qianyi/api-list/alipay-trade-close.html 。

### Task 2b: 业务取消意图（审查扩展）
- [x] 新增业务订单取消→渠道未知→到账→重复到账集成测试，先观察 pay_exception 为0（应为1）的红测试。
- [x] V17 添加 pay_order.close_requested_at，仅新增迁移；JSON真源、实体、H2 schema同步。
- [x] PaymentService.cancel 在支付单锁内记录业务取消意图；普通close在未查到已付时也保留意图。结果入口识别意图后转LATE_PAYMENT；start/attach拒绝已接受的关闭意图。
- [x] PaymentPort.cancelPayment 显式承载业务已终止，经Recording/Remote/独立内部端点同语义接力；订单事务提交后调用cancelPayment。
- [x] 验证本地/远程对拍、普通close已付兼容、结果幂等、SchemaDrift与迁移连续性。

### Task 3: 验收与业务任务对账
- [x] 运行 pay-domain 及依赖测试、shop-app 关单/开放 API/终端/架构场景测试。
- [x] 消融渠道关闭接线后，针对测试必须变红，再恢复并验证。
- [x] 回填 §5、§6 和总任务状态；交付独立分支。

### 后续任务队列（完成前置后逐片细化，不以存在类文件认定业务完成）
1. E07：积分售后退款统一入口已于2026-10-29补实现与回归，见[退款统一入口收尾](./TDD-支付服务-退款统一入口收尾.md)；来源操作员贯通见[退款申请人贯通](./TDD-支付服务-退款申请人贯通.md)；后续仍需历史回填、线下退款凭证、复核规则版本管理。
2. W6/E09：真实渠道账单采集、费用来源、差异与平台费应收、资金恒等式；不得用估算费用代替渠道事实。
3. E03/M7.3：历史回填、INTERNAL_EVENT、幂等消费者与恢复。
4. F55/F56/M6.5：商户生命周期、进件与终端管理入口及界面。
5. M7.2/4/6/7：远程 Port、双进程验收、回滚；生产切流为单独交付动作。
6. I8/M8：基于官方协议和商户真实接入资料完成新渠道；稳定观察后退役旧数据/双写。

复核重点：多尝试部分成功；关单响应丢失后重试；已付款并发回调；门店换绑或密钥轮换；关闭期间新尝试。

## §3 执行判定与边界

1. 后续完整清单是执行队列，不表示本批已交付。首批交付仅为 E04 渠道关单及其查询/并发安全。
2. 独立审查发现冻结身份遗漏：初查与失败后查回现在对有 pay_merchant_id 的尝试都经同一冻结路由；无快照的旧行继续沿原查单接口，不能替历史数据编身份。
3. 独立审查发现“查询不存在→跳过关单”的晚到下单窗口：所有尝试必须取得渠道关闭证明。TEST 对未知单号写关闭标记，阻断延迟下单；真实渠道只查不到但没有关闭证明时保持当前状态。这可能使未发成的单继续待查，优先保留未知事实，不假装已关。
4. 不引入 JVM 锁或跨 HTTP 的数据库长事务。渠道关单结束后在支付单行锁内复核尝试集合，终态禁止挂接新尝试；既有渠道与付款竞争的迟到回调继续进入原异常处置。
5. 新增 V17，仅记录关闭请求时间，不等于渠道已经关闭。业务已终止使用显式 cancelPayment（/internal/pay/v1/payments/cancel），不能退回普通 close 的“先查已付”语义；普通close初查已付仍维持原行为。内部两种形态需使用同一版本，旧服务不支持新端点时显式失败。
6. 未改生产配置、未切流、未执行迁移和旧表删除。真实渠道商户凭据与生产行为未在本批联调。

## §5 测试记录

基线：JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -f backend/pom.xml -pl pay/pay-domain -am test
结果：BUILD SUCCESS，47.060s（日志 /private/tmp/pay-closure-baseline.log）。仅基线，不代表本次实现验收。

### 红绿与消融

| 场景 | 证据 |
|---|---|
| 查询失败/缺回执仍关单 | 首次 2/2 失败，都是 expected AWAITING_PAYMENT but was CLOSED，错误数0；修复后通过。日志 pay-close-red.log / pay-close-green1.log |
| 仅查询未付，尚无渠道关闭证明 | 新测试失败1条（错误关闭），接线后通过。日志 pay-close-red3.log / pay-close-green2.log |
| 支付宝缺 CLOSE 能力 | 先因 CHANNEL_UNSUPPORTED 失败；实现后既有四条与新增协议测试通过。日志 pay-close-red2.log / pay-close-green2.log |
| TEST 没有真实关闭 | 先因 CHANNEL_UNSUPPORTED 失败；加入原子关闭后通过。日志 pay-bank-close-red.log / pay-closure-suite.log |
| 冻结商户、密钥版本、缺原商户、结果不明 | PaymentAttemptCloseServiceTest；查回复用同一身份，付款成功不能被当作已关 |
| 数据库与真实测试银行、晚到 prepay | PaymentChannelCloseFlowTest 2/2；latch 暂停在 ledger.open 后 prepay 发出前，关闭后延迟请求被拒 |
| 业务集成回归 | PayCloseFlowTest 3、OpenApiFlowTest 21、PayOrderFlowTest 3、PaymentChannelCloseFlowTest 2、反向依赖预算4，共33/33通过；日志 pay-close-db-regression.log |
| 移除渠道关单接线 | 消融后 PaymentCloseSafetyTest 10条中2条失败，0错误（错误 CLOSED）；恢复实现。日志 pay-ablation-channel-skip.log |
| 移除冻结查询接线 | 消融后10条中1条失败，0错误（调用默认商户查询）；恢复实现。日志 pay-ablation-frozen-query-skip.log |

新增取消意图红测试：PaymentChannelCloseFlowTest 3条中1条失败（LATE_PAYMENT数量期望1、实际0，0错误），日志pay-cancel-intent-red.log。
首次整仓回归在发现取消意图缺口后主动中止以先修正确性；中止前已完成的测试未报失败，不能据此声称全仓通过。日志pay-closure-full-backend.log。修复后针对支付完整模块和跨域调用方重新验证。

### 最终验证（2026-10-28）

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -f backend/pom.xml -pl pay/pay-svc -am test`：**1323条，0失败，0错误，12跳过，BUILD SUCCESS**；日志 `/private/tmp/pay-closure-final-modules.log`。
- `mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=PaymentChannelCloseFlowTest,PayCloseFlowTest,OpenApiFlowTest,OrderCloseRuleFlowTest,CloseRaceReleaseTest,ArchitectureTest,PayAuditTrailTest,PayHasNoControllerTest,PayReverseDependencyBudgetTest,SchemaDriftTest,PayMigrationTest,PayCallerConstructionTest -Dsurefire.failIfNoSpecifiedTests=false`（同一JAVA_HOME）：**74/74通过，0错误，BUILD SUCCESS**；日志 `/private/tmp/pay-closure-final-integration.log`。
- `PaymentPortLocalRemoteParityTest` 5条通过（含新增业务取消HTTP对拍）；`PayApplicationBootTest` 7条通过；包含于模块全测。单独边界日志 `/private/tmp/pay-closure-port-parity.log`。
- 失败态仍允许重新尝试，因此业务取消也必须保留意图：`PaymentCloseSafetyTest.failedOrderStillRetainsExplicitBusinessCancellationIntent` 先红（13条中1失败、0错误），改按状态机是否允许CLOSED判断后绿，包含于模块全测；日志 `/private/tmp/pay-cancel-failed-red.log`。
- `python3 scripts/verify-pay-close-intent-mysql.py`：**PASS MySQL 9.7**；自建临时数据目录、仅私有socket、无网络、结束销毁，只验证V17保留旧行及毫秒精度；未连接现有应用数据库。日志 `/private/tmp/pay-close-v17-mysql.log`。
- `node scripts/check-generated-docs.mjs`：**25个生成器产物最新**；`node scripts/check-sql-portability.mjs`：**无新增方言依赖**；`git diff --check`通过。
- 提交前重新fetch：远端 `f0bb429ac` 未占用支付V17；本分支仍基于已验收的 `0e686ab8f`，没有把无关餐饮提交混入验证范围。

结论边界：支付服务完整模块与指定跨域场景通过；**不是整仓所有后端测试通过**（首轮整仓已中止）。没有真实商户通道联调、生产发布、业务结果事件化或剩余队列交付的声明。渠道回调后的既有业务投影/ACK仍随M7收口；新增取消场景通过实际入账服务，不是端到端渠道回调HTTP测试。
日志均位于 /private/tmp/。

## §6 实现与偏差

| 设计 | 实现 |
|---|---|
| 查询与关单同一冻结身份 | PaymentAttemptCloseService / impl，ChannelContextFactory；原商户不可解析时返回未知，不回落当前绑定 |
| 不明保留，全部确认后本地关 | PaymentServiceImpl.close / queryAttempt / applyPaidAttempt；复用入账方法，反向依赖基线未放宽 |
| 新尝试竞争 | 事务内重查尝试集合；attachAttempt 拒绝不可开新尝试的终态 |
| 支付宝 | AlipayChannel 注册 CLOSE，alipay.trade.close 通过现有签名/验签/记录客户端；核验响应外部单号 |
| 测试渠道 | TestPayGateway 原子订单状态、关闭标记；InProcessChannel 注册 CLOSE 并保留 CLOSED 查询状态 |
| 持久取消意图 | V17 / PayOrder / JSON真源 / H2 schema；PaymentService.cancel及状态机守卫；PaymentOutcome转LATE_PAYMENT |
| 业务取消调用链 | PaymentPort.cancelPayment、Recording、Remote、PaymentInternalApi/Paths、InternalPaymentEndpoint；OrderService提交后显式通知业务已取消 |
| MySQL验证 | scripts/verify-pay-close-intent-mysql.py，自建9.7临时实例验证实际迁移 |
| 验收 | 三个新增单测类、一个新增 Spring/H2 集成类、扩展 AlipayChannelTest |

偏差：渠道明确不存在不再单独作为关闭证明（原因见§3）；计划中的退款、账单/费用、事件化与独立部署仍在后续队列。

