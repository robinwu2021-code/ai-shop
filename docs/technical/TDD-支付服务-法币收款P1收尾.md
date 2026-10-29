# TDD 支付服务法币收款 P1 收尾

状态：已实现（安全门禁；真实多商户执行未开通） · 2026-10-29 · 档位：1 · 基线：7908c0a86
依据：[R0—R3评审](./支付服务-R0-R3评审-2026-10-29.md) F01—F04及用户“按照建议执行”。使用writing-plans、executing-plans、test-driven-development；在既有隔离分支内顺序实施，完成后独立审查。

## §0 需求与验收

| AC | 业务约束 | 实现/验证 |
|---|---|---|
| C1 | 金额数值相同但币种不同，首次或重放都不能形成支付单正常成功、正常分录或正常业务结果 | PaymentOutcomeService / PaymentLedgerService；PaymentOutcomeFlowTest |
| C2 | 所选账户、市场、接入模式、资金路径和密钥版本持久冻结，真实渠道命令与原快照一致 | PaymentOpen可选acquiringRoute、账本字段及LegacyChannelBridge显式路由调用；集成和渠道单测 |
| C3 | 复用在途尝试不重新选择当前路由；请求/付款身份冲突不提交父单冻结；并发发起/取消使用同一父单锁 | PaymentService短事务准备、OrdinaryPaymentInitiationService.prepare；回滚/重试/并发测试 |
| C4 | 市场启用、渠道市场、产品市场、商户OPEN授权、币种、资金路径、限额和精度均通过才发起；可用方式与执行同口径 | RouteSelectionPolicy及收银台；路由边界测试 |
| C5 | 保留旧PaymentOpen构造、旧JSON及旧普通发起调用；本批不猜补在途历史路由，不执行生产迁移 | 兼容、未知快照拒绝、既有支付/关闭/退款回归 |

## §1 实现选择

复用现有payment_request_json保存可选、不可变的PayMerchantQueryPort.PayRoute；不新增表列，不改已应用迁移。PaymentOpen旧构造保留，空新增字段不参与旧请求JSON和摘要，旧记录缺失新字段时仍可读取。公开Open API请求/响应JSON不变；内部普通支付协议增加可选冻结路由，调用双方需同版验证。

PaymentService.start在父单行锁下检查状态，已有在途尝试从原快照准备并校验方式，不读取当前门店绑定替换历史路由；新尝试在同一短事务内选择路由、冻结父单、落尝试并冻结付款人。OrdinaryPaymentInitiationService.prepare只执行本地准备；网络下单在事务外，由原快照创建完整ChannelRoute交给渠道桥。未知历史快照阻断，不猜补。网络不明后保留已准备的同一尝试，不能因“接口失败”抹去可能已发出的意图。

路由准入读取MarketService、渠道市场及产品市场。订单有币种和金额时逐笔校验；仅列门店可用方式时校验市场/币种/资金路径等静态条件；收银台知道订单金额时也检查限额。旧普通调用保持原入口，R4进一步评审其本地/远程和结果投递，不在本批顺带建设事件总线。

## §2 实施计划

- [x] 任务1：扩展PaymentOutcomeFlowTest的异币首次/重放、无正常凭证和正常结果断言；先红，再修PaymentOutcomeServiceImpl及PaymentLedgerServiceImpl。AMOUNT_MISMATCH表示完整Money不匹配，避免新增不兼容异常类型。
- [x] 任务2：新增法币路由集成测试，先覆盖准备失败回滚、绑定切换后重复发起保持原路由、完整渠道上下文、未知原快照和并发；按红绿扩展PaymentOpen、OrdinaryPaymentInitiationService(/impl)、PaymentLedgerServiceImpl、PaymentServiceImpl及LegacyChannelBridge。保留旧签名并验证旧快照/摘要。
- [x] 任务3：新增市场、渠道、产品、币种、资金路径、限额及精度拒绝用例；先红后扩展RouteSelectionPolicy与CheckoutServiceImpl，确认拒绝发生在渠道调用前。
- [x] 任务4：做一次关键门禁消融、支付模块及定向跨域/架构回归；独立审查完整增量并处理阻塞问题。复核SettlePort反向依赖棘轮，仅允许保持或下降，不放宽基线。
- [x] 任务5：更新§5/§6、评审和进度记录，本地提交；继续R4内部调用专项评审并单独输出证据与剩余项。

文件落点：shop-base/spi/settle/SettlePort.java；pay-domain/acquiring/service/impl/{PaymentServiceImpl,PaymentOutcomeServiceImpl}.java、acquiring/route/RouteSelectionPolicy.java、service/{OrdinaryPaymentInitiationService,impl/OrdinaryPaymentInitiationServiceImpl,impl/PaymentLedgerServiceImpl}.java、checkout/service/impl/CheckoutServiceImpl.java；pay-channel/channel/legacy/LegacyChannelBridge.java；相应domain/channel单测及shop-app场景测试。原评审文档和技术索引随结果同步。

## §5 验证记录

币种反例先红：pay-fiat-p1-currency-red.log，4项1失败0错误，异币回执错误返回业务单号。修复后pay-fiat-p1-currency-green.log，4项0失败0错误。路由首跑被沙箱禁止Mockito代理附加，允许测试代理后pay-fiat-p1-routes-red-v2.log为12项11失败0错误，确认路由下传、重试改写、未知快照和8项准入反例。

最终验证：

| 验证 | 实测 |
|---|---|
| 币种消融 | 临时删除Money币种校验，新异币用例1失败0错误，pay-fiat-p1-ablation.log；finally恢复，确认源码门禁在位 |
| 最终支付服务模块全测 | pay-fiat-p1-final-modules.log：266类1331项，0失败0错误、12跳过，BUILD SUCCESS |
| 最终跨域/架构回归 | pay-fiat-p1-final-integration.log：17类143项，0失败0错误0跳过，BUILD SUCCESS；包含最新重复到账重放反例 |
| 独立审查 | fiat_p1_final_review发现并复现1项重放缺口；修复后只读复核确认关闭，其余所审增量无阻塞问题 |
| 生成物/SQL | 25个生成器一致；SQL方言无新增；diff检查通过 |

最终命令（JAVA_HOME为本机OpenJDK21，日志均在/private/tmp）：

```sh
mvn -o -f backend/pom.xml -pl pay/pay-svc -am test
mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=FiatPaymentRouteFlowTest,PaymentOutcomeFlowTest,OpenApiFlowTest,PayOrderFlowTest,PayMerchantRoutingFlowTest,FundInvariantFlowTest,PaymentChannelCloseFlowTest,PayCloseFlowTest,PaymentLedgerCloseRaceFlowTest,RefundRequesterIdentityFlowTest,LegacyRefundGovernanceFlowTest,PayOrderBackfillSafetyFlowTest,RemoteOrdinaryPaymentInitiationPortTest,RemoteOrdinaryPaymentPortTest,ArchitectureTest,PayReverseDependencyBudgetTest,SchemaDriftTest -Dsurefire.failIfNoSpecifiedTests=false
node scripts/check-generated-docs.mjs
node scripts/check-sql-portability.mjs
git diff --check
```

本轮未重跑全仓，未声明pre-push全绿；先前两项TerminalSandboxFlowTest基线失败没有改写或纳入失败豁免。最终绿测后仅做Java换行及文档对账。重点：异币回执、失败回滚、绑定变更、重试/取消竞争、产品停用与原始凭据版本。现有生产版本/结构不满足上线前提，本批不部署、不写生产库。

## §6 实现对账与边界

| 实现文件组 | 对应计划 |
|---|---|
| PaymentOutcomeServiceImpl / PaymentLedgerServiceImpl / PaymentOutcomeFlowTest | C1币种、首次/重放、重复到账、原始事实与正常结果资格 |
| FundInvariantService(/impl) | C1延伸：I8业务补单不绕过异常资格 |
| SettlePort.PaymentOpen / OrdinaryPaymentInitiationService(/impl) | C2/C3/C5可选持久路由、旧JSON/摘要、事务内准备与事务外发送 |
| PaymentServiceImpl / PayOperationsNumberReservations | C3父单锁、重试原快照、准备失败回滚、既有单号预留 |
| OnlinePayCapability / InProcessChannel / LegacyChannelBridge | C2显式账户执行证明，准入与发送双重门禁；无默认账户回退 |
| RouteSelectionPolicy / CheckoutServiceImpl | C4地域/币种/产品/资金路径/金额精度交集 |
| FiatPaymentRouteFlowTest / FrozenAccountDispatchTest / OrdinaryPaymentInitiationServiceTest | 真实H2并发、回滚、原账户、无证明拒绝、旧JSON兼容 |
| known-pay-reverse-deps / 评审与进度文档 / 索引 | 棘轮只下降；R0—R3状态与R4后续队列对账 |

偏差与边界：未建设真实渠道的签名账户保险库或ConfiguredAccountBinding；不能证明实际账户/版本匹配就拒绝新冻结路由执行，只有有状态TEST银行显式支持。旧无acquiringRoute内部路径保留兼容，其统一迁入及可靠结果回传见[R4内部调用评审](./支付服务-R4内部调用评审-2026-10-29.md)。未新增DDL、外部API JSON变化或新渠道；未部署、未触发生产回填。P2支付场景与市场币种硬编码尚在后续队列；如P1准入要求拒绝不支持场景，明确拒绝即可，不伪造新渠道能力。

实现决议：独立设计复审发现真实微信/支付宝handler仍使用固定部署凭据；仅下传上下文不足以证明实际签名账户。新增显式supportsFrozenAccount能力默认拒绝，在准入和实际发送前同时检查；仅无外部签名账户的有状态TEST银行显式支持。真实渠道在签名账户/版本证据接上前拒绝ACQUIRING_ACCOUNT_UNVERIFIED，不回退部署默认号；本批不声称真实多商户签名执行已完成。旧无acquiringRoute调用保留兼容，后续R4标明其不在此新路径保证内。

集成修复决议：第一轮路由绿测发现3个正例因既有单号预留规则失败（18项0断言失败3错误）：编号必须在业务事务前预留。将PaymentService.start加入原PayOperationsNumberReservations的PAYMENT_ATTEMPT单号预算；嵌套ledger.open复用预算，不放宽编号事务守卫。生产事务模型不变，不在行锁事务内分配序号。补充编号文件为§6实际变更。

C1补充边界：R4追踪发现I8业务补单扫描仍只看原始stl_payment.SUCCESS，可能绕过Money异常。追加正式反例先红（pay-fiat-p1-repair-scan-red.log，1失败0错误），再约束已关联支付单必须为正常成功、金额币种一致且无收款异常，保留无父单旧链路兼容；原始现金事实仍可通过流水查询，不删除异常到账。FundInvariantService(/impl)纳入本批变更对账。

反向依赖：通过已有类型的嵌套import降低重复文本引用，SettlePort预算实际67→59，基线仅同步下降；未新增业务侧反向Port或放宽棘轮。

最终独立审查发现重复到账的SUCCESS重放仅校验父单Money，仍可能把DUPLICATE_PAYMENT重新投影成正常成功。正式duplicateSuccessfulAttemptCannotBecomeNormalBusinessSuccessOnReplay先红（pay-fiat-p1-duplicate-red.log，1失败0错误），修复为已关联尝试重放同时排除其收款异常；补单扫描也排除这些异常。随后做币种门禁消融并恢复，再运行最终回归。

本次暂存区对账（追加本段前；最终TDD行数随本段增加）：

```text
 backend/known-pay-reverse-deps.txt                 |   2 +-
 .../channel/capability/OnlinePayCapability.java    |   6 +
 .../pay/channel/legacy/LegacyChannelBridge.java    |  27 ++-
 .../pay/channel/platform/InProcessChannel.java     |   7 +
 .../channel/legacy/FrozenAccountDispatchTest.java  | 102 +++++++++
 .../pay/acquiring/route/RouteSelectionPolicy.java  |  88 +++++--
 .../service/impl/PaymentOutcomeServiceImpl.java    |   4 +-
 .../acquiring/service/impl/PaymentServiceImpl.java | 101 +++++----
 .../checkout/service/impl/CheckoutServiceImpl.java |   3 +-
 .../numbering/PayOperationsNumberReservations.java |   3 +-
 .../shop/pay/service/FundInvariantService.java     |   4 +-
 .../service/OrdinaryPaymentInitiationService.java  |   8 +-
 .../pay/service/impl/FundInvariantServiceImpl.java |  10 +
 .../impl/OrdinaryPaymentInitiationServiceImpl.java |  42 +++-
 .../pay/service/impl/PaymentLedgerServiceImpl.java |  49 +++-
 .../port/OrdinaryPaymentInitiationServiceTest.java |  26 +++
 .../shop/scenario/FiatPaymentRouteFlowTest.java    | 252 +++++++++++++++++++++
 .../shop/scenario/PaymentOutcomeFlowTest.java      |  47 ++++
 .../java/ai/neargo/shop/spi/settle/SettlePort.java |  17 +-
 docs/technical/README.md                           |   3 +
 docs/technical/TDD-支付服务-法币收款P1收尾.md      |  83 +++++++
 docs/technical/支付服务-R0-R3评审-2026-10-29.md    | 164 ++++++++++++++
 .../支付服务-R4内部调用评审-2026-10-29.md          |  80 +++++++
 .../technical/支付服务-完成度复核与剩余开发计划.md |   6 +
 24 files changed, 1056 insertions(+), 78 deletions(-)
```
