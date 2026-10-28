# TDD-支付服务-退款统一入口收尾

状态：本批已实现并验证 · 2026-10-29 · 档位：1
依据：支付完整设计 AC17–AC19、W3 E07、资金闭环收尾队列及用户“继续下一任务”。

## §0 需求与验收

| AC | 业务约束 | 实现落点 |
|---|---|---|
| R1 | 旧售后现金退款挂接统一退款单，复核前不得取得发送权 | PaymentLedgerService / RefundService |
| R2 | 复用原退款号、冻结请求、原收款身份；通道发送仅一个实现、最多一次 | RefundExecutionService / SettleService |
| R3 | 成功回执同时更新现金证据、退款单、支付单与退款记账；重复回执幂等 | PaymentLedgerService / RefundPaymentStore / RefundOutcomeService |
| R4 | 驳回复核或渠道终止不能发送；原资金容量仍等待业务补偿后释放 | RefundCapacityLedger / RefundService |
| R5 | 历史缺执行号退款只回查，不重新发送；响应丢失按原号恢复 | 历史兼容分支 / RefundDispatchBoundaryTest |

## §1 现状与边界

旧售后 prepareRefund 创建 PENDING 现金行并冻结请求，dispatchRefund 独立发送，绕过 pay_refund 与复核。积分补偿协议依赖原 paymentNo、outRefundNo 和 refund_verified_*，不能替换原指令。
新统一执行器已有复核、发送权和结果投影。复用它，保留旧行的 PENDING+refund_dispatch_at 一次发送协议。历史缺 pay_order 的收款保留兼容，不伪造支付单；历史缺执行号的退款不自动收编或重发。新建现金退款执行号取原 outRefundNo，不改渠道号。
本批不含线下凭证、复核规则管理界面、来源操作员贯通（现存协议只冻结 AFTER_SALE 来源），不宣称 E07 全部完成。

## §2 实施计划

- [x] 新增 LegacyRefundGovernanceFlowTest：复核门禁、成功幂等、驳回、响应丢失；先运行确认红灯。
- [x] PaymentLedgerService 创建时挂退款单并在发送权处复核；现金成功证据同步结果投影。
- [x] RefundExecutionService 增加已准备现金指令入口，共用渠道发送；SettleService 委托。
- [x] RefundPaymentStore 确认保留旧现金证据；终止回查投影失败，容量仍保留。
- [x] 跑既有退款/积分恢复回归、模块测试和文档守卫，执行门禁消融，独立审查。

## §5 验证记录

基线：RefundDispatchBoundaryTest、RefundReviewFlowTest、RefundCapacityFlowTest、RefundExecutionRecoveryTest 离线 Maven BUILD SUCCESS（2026-10-29 00:08:44 +08）。

| AC | 测试 | 实测证据 |
|---|---|---|
| R1/R2 | LegacyRefundGovernanceFlowTest.legacyIntentRequiresReviewBeforeClaimAndSendsOriginalInstructionOnce | 首轮找不到 pay_refund（4个测试均缺行）；实现后通过。移除 claim 的 dispatchAllowed 行后单测失败 expected false but true，恢复实现 |
| R3 | commonCallbackKeepsCashEvidenceAndProjectsSuccessOnce / lostResponseRecoversOriginalCashIntentAndUnifiedRefund | 重复公共回调退款金额只增一次、退款凭证仅一张；丢响应后原号查回并同步退款单 |
| R4 | reviewRejectionNeverSendsAndRetainsCashCapacityForSourceCompensation / terminalQueryProjectsFailureWithoutReleasingCashCapacity | 复核驳回或查回终止后支付单占用释放，原现金容量仍 RESERVED |
| R5 | historicalIntentWithoutExecutionIdentityIsQueryOnly | 不重发、不打发送标记，仍可按原坐标查回现金证据 |
| 容量 | legacyReservationAndPublicRefundShareAvailableBalance | 1990实收，旧入口占200后统一入口申请1800被 PAY_REFUND_EXCEEDS 拒绝 |
| 审查修正 | RefundDispatchBoundaryTest.uninstalled_channel_is_a_definitive_unsent_rejection_for_source_recovery | 先红：expected FAILED but PENDING；修复为发送前明确拒绝，保留终止证明 |

日志：`/private/tmp/pay-refund-red.log`（4错误，均缺统一退款单）、`pay-refund-green.log`（首轮24测试通过）、`pay-refund-ablation.log`（1失败0错误）、`pay-refund-channel-red.log`（7治理场景通过、未装配渠道1失败0错误）。首次扩大回归：177项，1失败（StrictRefundCallbackFlowTest.confirmedRefundRejectsDifferentProviderReplay），0错误、1跳过；原因是把所有缺执行号历史退款都当旧现金协议，放宽了公共回调重放约束。修正为按冻结现金请求或售后+子单识别现金来源；现金最终回执已确认后也必须与首个 verified_provider 一致。通用历史退款继续保持原 trade_no 写入/拒绝冲突语义。最终复跑结果见下。

### 验证命令与范围

全部命令使用 Java 21（`/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`）与本地依赖缓存，不访问真实商户渠道。

```sh
mvn -o -f backend/pom.xml -pl pay/pay-svc -am test
mvn -o -f backend/pom.xml -pl shop-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=LegacyRefundGovernanceFlowTest,RefundDispatchBoundaryTest,RefundReviewFlowTest,RefundCapacityFlowTest,RefundOrderFlowTest,RefundCashEvidenceTest,RefundOriginalExecutionRecoveryFlowTest,RefundCoordinateRecoveryFlowTest,PointsOrderRefundStageTest,PointsSettlementRefundPathsTest,PointsRefundPreparationTest,AfterSaleRefundExecutionTest,StrictRefundCallbackFlowTest,BasicRefundRecoveryFlowTest,RefundCapacityDatabaseTest,ArchitectureTest,PayReverseDependencyBudgetTest,PayAuditTrailTest,PayHasNoControllerTest,SchemaDriftTest
node scripts/check-generated-docs.mjs
node scripts/check-sql-portability.mjs
git diff --check
```

回调修正后的定向验证：LegacyRefundGovernanceFlowTest、RefundCashEvidenceTest、StrictRefundCallbackFlowTest，14项，0失败/0错误，BUILD SUCCESS（`/private/tmp/pay-refund-callback-green.log`）。
最终代码支付模块复跑：**1323项，0失败、0错误、12跳过，BUILD SUCCESS**（`/private/tmp/pay-refund-final-modules-v2.log`）。20类跨域回归：**177项，0失败、0错误、1跳过，BUILD SUCCESS**（`/private/tmp/pay-refund-final-integration-v2.log`）。生成文档25个生成器产物最新，SQL方言无新增依赖，`git diff --check`通过。独立审查两项发现已修正并验证，最终无阻塞项。此处不等同整仓后端全测；本批无迁移，未复跑真实MySQL；容量库测试中的MySQL RR专用场景在H2会按条件跳过。

## §6 实现对账

| 文件 | 与§2对应的实现 |
|---|---|
| PaymentLedgerServiceImpl | 创建现金行时关联退款单；claim 校验复核；成功证据同事务投影 |
| RefundExecutionService / impl | 已准备现金指令入口与共用发送器；冻结原交易子商户与币种；未装配渠道明确终止 |
| SettleServiceImpl | 移除独立渠道发送实现、委托统一执行器；保留历史回查协议 |
| RefundPaymentStore | 公共回调按旧现金协议保存 verified 字段，结果不冒充 BasicRefundExecution |
| RefundCapacityLedger | 已验证终止回查同步退款单失败，保留现金占用直到源补偿完成 |
| LegacyRefundGovernanceFlowTest / RefundDispatchBoundaryTest / RefundSubMerchantTest | 新治理场景与既有渠道身份夹具补充 |
| W3、资金闭环收尾、完成度总表与生成索引 | 对账本批完成范围和剩余任务 |

偏差：不直接把旧 PENDING 当作新 INIT；保留各自发送权协议，共用真正的渠道发送。公共回调对旧现金不返回 BasicRefundExecution 标识，避免把治理键错误投影到另一来源执行。独立审查发现未装配渠道被误归未知，已新增先红测试并修复。无库表迁移、无端点或配置变更；未修改既有容量释放条件。

暂存实现快照（补本段前的 `git diff --cached --stat`）：

```text
 .../ai/neargo/shop/pay/impl/SettleServiceImpl.java |  40 +---
 .../shop/pay/service/RefundExecutionService.java   |   4 +
 .../pay/service/impl/PaymentLedgerServiceImpl.java |  30 +++
 .../pay/service/impl/RefundCapacityLedger.java     |  14 ++
 .../service/impl/RefundExecutionServiceImpl.java   |  21 ++-
 .../shop/pay/service/impl/RefundPaymentStore.java  |  30 ++-
 .../pay/service/impl/RefundSubMerchantTest.java    |   1 +
 .../scenario/LegacyRefundGovernanceFlowTest.java   | 202 +++++++++++++++++++++
 .../shop/scenario/RefundDispatchBoundaryTest.java  |  12 ++
 docs/technical/README.md                           |   3 +-
 docs/technical/TDD-支付服务-W3收单补完.md          |   6 +-
 docs/technical/TDD-支付服务-资金闭环收尾.md        |   2 +-
 docs/technical/TDD-支付服务-退款统一入口收尾.md    |  72 ++++++++
 .../technical/支付服务-完成度复核与剩余开发计划.md |   4 +-
 14 files changed, 401 insertions(+), 40 deletions(-)
```
