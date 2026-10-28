# TDD-支付服务-历史支付回填安全收尾

状态：已实现（安全能力；生产执行未完成） · 2026-10-29 · 档位：1
基线：d24524397 · 分支 codex/pay-business-closure
依据：支付完整设计、I3 S9 B6/B7/Q5/Q7、退款统一入口及申请人贯通的历史支付单剩余项；用户“继续”。

## §0 需求与验收

| AC | 业务约束 | 实现落点 |
|---|---|---|
| B1 | 回填默认只读预检，明确可执行/已关联/阻断及原因为人工复核提供依据 | PayOrderBackfill.inspect / 手动作业 |
| B2 | 门店归属必须唯一，资金路径取原交易冻结字段或请求快照，不取当前商户配置；未知不猜 | 回填校验 / PayOrderBackfillJob |
| B3 | 重复收款、金额币种不一致、在途或未补偿退款、冻结冲突必须阻断且不写任何数据 | 回填短事务内复核 |
| B4 | 有确定事实才建支付单，成功渠道取实际成功尝试；原交易号、退款号和现金证据不改写 | 创建与关联 |
| B5 | 一单一事务并发幂等；已有实时支付单不再记期初；历史退款不伪造退款单 | 行锁 / opening凭证 |
| B6 | 批次有上限和游标，阻断单不妨碍后续单预检；写入需显式apply | 作业参数 / 逐单报告 |

## §1 现状与选择

已有PayOrderBackfill与手动作业。现实现选第一收款作为金额/渠道、重复成功取第一笔、在途退款按零占用处理；全部订单行直接挂支付单，重用已有实时单仍会回放BACKFILL凭证。作业按首个子单取当前门店资金配置，不足以证明历史资金路径。

选择扩展原回填器，新增只读检查与受保护执行；不另造第二套迁移表或自动回放资金业务。原已成功的退款只计历史已退与期初，不补虚构pay_refund/审批；原始ID和证据保持。旧无冻结路径或来源不唯一的记录阻断，需人工从真实资料核实，不能批量把当前配置当历史事实。

本批是安全回填能力与运行手册，不操作生产数据，不补造历史人员、不推进事件化、不删旧表。默认作业只读，apply=true才写；afterOrderNo显式推进，阻断项持续可查。仅支持可识别的商城/FOOD来源；无法识别的业务主体转人工。

## §2 实施计划

- [x] 新增PayOrderBackfillSafetyFlowTest，先红验证冻结路径冲突、重复收款、在途退款、已有实时单与历史退款凭证引用。
- [x] 扩展PayOrderBackfill：inspect结果、带游标候选；受保护执行加锁并重新校验；对缺/矛盾事实给出阻断原因。
- [x] 保留原号，按唯一可靠收款建支付单，B7仅新建时写期初，历史退款凭证引用PAY_ORDER而非虚构PAY_REFUND。
- [x] 作业默认dry-run；唯一门店/明确来源，使用历史冻结路径；逐单容错和有界游标，补作业回归。
- [x] 新建现金/统一退款继承原pay_order_id；已有真实pay_refund双向证明的旧退款采用LINKS_ONLY，仅补指针不重放余额或审批。
- [x] 更新既有回填夹具以提供真实冻结路径，验证预检无写、幂等、并发、退款结果及余额；跑模块与定向集成、消融、独立审查。
- [x] 回填验收/对账与剩余任务，提交隔离分支，保留生产执行和人工冲突处理为后续动作。

## §3 运行与验收手册

本批未连接生产库。适用当前本地金融服务部署；独立支付服务的远程回填编排随M7另验。实际执行前确认目标版本、权威支付库、内部MALL/FOOD调用方及源库归属，不在本地影子库或错误环境写入。

1. 只读运行 `pay-order-backfill`，参数如 `{"limit":"100","afterOrderNo":""}`。默认dry-run，不建支付单、凭证、审批或渠道指令。
2. 记录结果 `nextCursor`、阻断原因与同runId逐单结构日志。摘要最多500字符，长明细看完整日志；示例列表只列前10项，不能把它当全量名单。
3. 对照状态：READY可创建新支付单并记B7；LINKS_ONLY只补真实退款单已证明的关联；ALREADY_LINKED不改；BLOCKED不写，交人工核实。不用当前资金配置、当前改名、恢复任务账号填历史事实。
4. 需要写入时，由人工MANUAL触发并明确传 `apply=true`、相同扫描范围及合理limit。CRON/RETRY/BACKFILL不能通过参数变为写入。每单锁内重新校验，预检不构成持久授权。
5. 用 `afterOrderNo=nextCursor` 继续；阻断项仍在未关联队列中，不能因游标经过就视作完成。处理完分页后从头重扫，捕获并发新出现的未关联记录。
6. 核对Q5、Q7及支付净额恒等式，另外清点未关联REFUND、无冻结路径的已付支付单、删除/异租户/缺订单号异常。只有人工阻断清单已处理并通过全库校验才能宣称生产回填完成。已有模型的LINKS_ONLY不证明其历史会计数据已完整。

支付库只读核对示例（不跨库JOIN，不执行UPDATE/DELETE）：

```sql
SELECT direction, COUNT(*) AS unlinked
FROM stl_payment
WHERE deleted = 0 AND direction IN ('PAY', 'REFUND') AND pay_order_id IS NULL
GROUP BY direction;

SELECT payment_no, order_no, direction, status, tenant_no, deleted
FROM stl_payment
WHERE direction IN ('PAY', 'REFUND')
  AND (order_no IS NULL OR order_no = '' OR tenant_no IS NULL OR tenant_no <> 'MAIN'
       OR deleted IS NULL OR deleted <> 0);

SELECT pay_order_no, paid_minor, refunded_minor, reserved_refund_minor, funds_path
FROM pay_order
WHERE deleted = 0 AND paid_minor > 0 AND (funds_path IS NULL OR funds_path = '');
```

Q5取完整历史范围的未关联PAY；Q7用 `PayIntegrity.unbalancedVouchers()`；净额用 `ledgerMismatchedOrders`，不是只看借贷平衡。保留原交易号、退款号、来源证据与运行日志；不靠删历史或删凭证让检查归零。缺证据的SOURCE、FROZEN_FUNDS_PATH、REFUND_UNRESOLVED、EXISTING_ORDER等原因需要真实资料与独立审计修复流程，当前配置不能充当历史证明。

## §5 验证记录

| AC | 测试方法 / 实测 |
|---|---|
| B1 | previewShowsReliableAmountsWithoutAnyWrites：预检无支付单/关联/凭证写入；defaultInvocationIsReadOnlyAndReturnsAContinuationCursor |
| B2 | currentFundsConfigurationCannotOverrideOriginalFrozenPath、callerCannotSupplyMissingHistoricalFundsEvidence、originalPaymentRequestCanProveMissingFrozenPathWithoutChangingHistoricalColumns；首红路径冲突仍被接纳 |
| B2 来源 | mixedStoreRootIsNotAssignedToItsFirstChild、businessBuyerMustMatchOriginalFinancialPayer、knownFoodSourceKeepsItsOriginalPaymentOwner、foodBindingCannotBeLinkedToAnotherCashAttempt、unknownIndustryOwnerIsNotGuessedAsFood |
| B2 历史 | currentDisplayNamesAreNotClaimedAsHistoricalSnapshots、retiredIndustryBindingIsNotReclassifiedAsAnOrdinaryMallOrder、retiredChildCannotHideAnAmbiguousHistoricalOwner；各先红后修复，不把删除过滤后的剩余行冒充原始来源 |
| B3 | duplicateSuccessfulPaymentsRequireManualResolution、unresolvedRefundCannotBecomeAnUnreservedPaymentOrder、definitiveRefundFailureStillNeedsSourceCompensationBeforeBackfill、differentTenantAttemptCannotJoinThePaymentOrder；快照矛盾与错误原收款均阻断 |
| B4 | successfulAttemptSuppliesChannelWithoutInventingOlderAttemptRouting、openingRefundUsesPaymentOrderReferenceAndPreservesOriginalCashEvidence（200/1990部分/全额）；原现金证据与指令未变 |
| B5 | simultaneousBackfillsCreateOneOrderAndOneOpening、openingFailureRollsBackOrderLinksAndTheEarlierPaymentVoucher、alreadyExistingLivePaymentOrderCannotReceiveAnotherOpeningVoucher |
| B5 归属 | anotherInternalBusinessOwnerCannotReceiveADuplicatePaymentOrderForTheSameCash 先红，跨内部调用方同业务单冲突后阻断 |
| B5 仅补链 | provenRefundLinkCanBeRepairedWithoutReplayingOrChangingItsLiveBalance 先红，LINKS_ONLY后绿；missingRefundIntentProofStillRequiresManualAssociationReview；新退款两入口继承关联各先红后绿 |
| B6 | explicitCursorAndBoundedLimitReachTheFinancialCandidateScan、blockedSourceDoesNotPreventTheNextOrderFromBeingApplied、scheduledInvocationCannotApplyEvenWithAnOldPersistedApplyParameter、largeBlockedBatchFitsThePlatformSummaryWithoutLosingItsCursor（先红后限制500字符） |

聚焦先红：`pay-backfill-red.log` 5失败0错误，`pay-backfill-job-red.log` 6失败0错误。扩大时发现3个业务边界（历史路由误补写、异租户合并、定时写入）及1个夹具分类错误：`{}`被正确视为无效快照，随后改用合法但金额冲突的快照明确测试事实冲突。
聚焦绿灯：`pay-backfill-focused-final.log` 39项0失败0错误。后新增500字符报告长度用例先红后绿；最终集成中的回填3类共40项已通过。
模块全测：`pay-backfill-final-modules.log`，pay-svc及依赖265类、1323项，0失败0错误，12跳过。首次24类集成243项中241通过，2项反向依赖预算守卫失败：新增快照类型导入导致SettlePort引用67→69；改用本域只读快照投影，未放宽基线；`pay-backfill-final-integration-v2.log` 最终24类243项，0失败0错误0跳过，BUILD SUCCESS。独立复审完整增量及最终投影/归属/报告修复，未发现阻塞问题。

执行命令（JAVA_HOME为本机OpenJDK21，Maven离线）：

```sh
mvn -o -f backend/pom.xml -pl pay/pay-svc -am test
mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=PayOrderBackfillSafetyFlowTest,PayOrderBackfillJobTest,PayOrderBackfillFlowTest,LegacyRefundGovernanceFlowTest,RefundRequesterIdentityFlowTest,RefundReviewFlowTest,RefundOrderFlowTest,RefundDispatchBoundaryTest,RefundCapacityFlowTest,RefundCashEvidenceTest,RefundOriginalExecutionRecoveryFlowTest,RefundCoordinateRecoveryFlowTest,StrictRefundCallbackFlowTest,AfterSaleRefundExecutionTest,BasicRefundRecoveryFlowTest,PointsSettlementRefundPathsTest,M5AfterSaleFlowTest,M7SettleFlowTest,ArchitectureTest,BizEndpointPermTest,PayReverseDependencyBudgetTest,PayAuditTrailTest,PayHasNoControllerTest,SchemaDriftTest -Dsurefire.failIfNoSpecifiedTests=false
node scripts/check-generated-docs.mjs
node scripts/check-sql-portability.mjs
git diff --check
```

生成物25个生成器一致、SQL无新增方言依赖、diff检查通过。首次提交后pre-push在生成索引一致性处停止：TDD状态更新后未重跑doc-index。已重新生成索引，更新提交后重跑；最终结果见交付记录，不预先声明通过。
消融：临时去掉重复成功收款门禁，duplicateSuccessfulPaymentsRequireManualResolution 1失败0错误（`/private/tmp/pay-backfill-ablation.log`），实现已恢复。所有日志在 `/private/tmp/`。


## §6 实现对账

| 文件 | 与§2的实现对应 |
|---|---|
| PayOrderBackfill / impl | 结构预检、键集候选、锁内事实复核、新单期初、现有退款强证明仅补链、旧字段不补写 |
| PayOrderBackfillJob | 默认只读、人工显式写入、唯一来源/买家/门店、已删来源阻断、游标与逐单报告/500字符摘要 |
| PaymentLedgerServiceImpl / RefundPaymentStore | 新退款继承真实原收款pay_order_id，避免持续产生孤立记录；不改退款发送/复核/结果业务 |
| PayOrderBackfillSafetyFlowTest / JobTest | 真实H2事实、并发、事务回滚、来源与执行模式、已有模型仅补链与无证明阻断 |
| PayOrderBackfillFlowTest | 既有夹具提供冻结证据；仅验本批确定数据，不再任取同库所有待回填单强行填进一个门店 |
| I3、W3、资金闭环/退款记录与剩余开发表、生成索引 | 对账安全回填能力、生产未执行和人工剩余项 |

偏差与边界：历史路径或来源不能确认就阻断，不承诺仅运行脚本就使所有旧数据归零；不补历史人员，不创建虚构pay_refund/审批，不回放渠道业务。已有实时单不重记期初；只有双向pay_refund、原PAY、App、门店、指令、金额、币种与用户全部一致才允许LINKS_ONLY；其余既有模型冲突人工处理。原字段只更新pay_order_id及正常审计字段；未知历史名称不复制当前商户名。跨内部业务归属冲突阻断。无库表迁移，未跑真实MySQL或商户渠道，未执行生产回填或切流。

暂存区对账快照（追加本段前，最终TDD行数随后增加）：

```text
 .../pay/acquiring/backfill/PayOrderBackfill.java   |  43 ++-
 .../backfill/impl/PayOrderBackfillImpl.java        | 406 ++++++++++++++++-----
 .../pay/service/impl/PaymentLedgerServiceImpl.java |   1 +
 .../shop/pay/service/impl/RefundPaymentStore.java  |   1 +
 .../neargo/shop/paybridge/PayOrderBackfillJob.java | 172 +++++++--
 .../shop/paybridge/PayOrderBackfillJobTest.java    | 183 ++++++++++
 .../shop/scenario/PayOrderBackfillFlowTest.java    |   9 +-
 .../scenario/PayOrderBackfillSafetyFlowTest.java   | 359 ++++++++++++++++++
 docs/technical/README.md                           |   1 +
 docs/technical/TDD-支付服务-I3支付单退款流水.md    |   7 +-
 docs/technical/TDD-支付服务-W3收单补完.md          |   2 +-
 .../technical/TDD-支付服务-历史支付回填安全收尾.md | 100 +++++
 docs/technical/TDD-支付服务-资金闭环收尾.md        |   4 +-
 docs/technical/TDD-支付服务-退款申请人贯通.md      |   2 +
 docs/technical/TDD-支付服务-退款统一入口收尾.md    |   2 +
 docs/technical/reference/glossary.json             |  11 +
 docs/technical/reference/中英文对照-实体与字典.md  |   5 +-
 .../technical/支付服务-完成度复核与剩余开发计划.md |   6 +-
 18 files changed, 1160 insertions(+), 154 deletions(-)
```

全仓验收补充：首次完整运行发现 `RefundReachesChannelTest.unreachableChannelFailsLoudly` 仍断言旧中文errMsg。统一执行器在此前379fa1bec已将失败原因存errCode、errMsg保留原申请原因；本批未改变该语义。独立复审确认后保留FAILED断言，并强化为精确错误码REFUND_CHANNEL_UNAVAILABLE与原原因“试”同时断言；未改生产逻辑或失败基线。该文件作为§6额外对账项，最终重跑结果见交付记录。

验收补充记录：

- `pay-backfill-pre-push-v2.log`：干净HEAD编译通过；25个生成物、SQL、共享层589/运营端880、c-app635/b-app358及两端类型检查通过。ops Hook lint因本机依赖缺失跳过。
- 后端全仓命令完成前七批，6330项、1失败0错误；唯一失败是上述旧退款消息断言，最后S–Z批因Surefire失败未执行，不能称整套全绿。
- 补跑 `mvn -o -B -f backend/pom.xml -pl shop-app -am test '-Dtest=RefundReachesChannelTest,S*,T*,U*,V*,W*,X*,Y*,Z*' -Dsurefire.failIfNoSpecifiedTests=false`：1205项、2失败0错误、16跳过；旧退款5项已全绿。仅TerminalSandboxFlowTest的activateChargeUnknownQueryBatch与voidNeedsManagerCodeAndDailyLimit失败（UNKNOWN及70085状态限制）。日志 `pay-backfill-final-rest.log`。
- 改动前基线d24524397在独立目录仅跑TerminalSandboxFlowTest：4项、同样2失败0错误，同名、同断言值复现，日志 `pay-backfill-baseline-terminal.log`。不改终端业务、不把失败加入基线、不声明pre-push全绿。本批仅本地代码验证，生产回填未执行。

- 餐饮补验：`mvn -o -B -f backend/pom.xml -fae -Pfood-embedded,food-verify test '-Dtest=ai/neargo/shop/food/**,**/Food*,!FoodMigrationPackagingIT,!**/*Native*' -Dsurefire.failIfNoSpecifiedTests=false`，203类1060项，0失败0错误、57跳过，BUILD SUCCESS（`pay-backfill-final-food.log`）。
- 收尾结论：本批40项回填、243项定向跨域及支付模块全测通过；退款旧断言修正后5项通过。完整验证按原前七批+剩余批补验，除两项已在改动前基线复现的终端沙箱失败外未发现其他失败；未再次重跑整套6330项，不声称pre-push全绿。所有失败基线保持不变；只在隔离分支保存本地提交。

最终相对d24524397的暂存对账（追加本段前）：

```text
 .../pay/acquiring/backfill/PayOrderBackfill.java   |  43 ++-
 .../backfill/impl/PayOrderBackfillImpl.java        | 406 ++++++++++++++++-----
 .../pay/service/impl/PaymentLedgerServiceImpl.java |   1 +
 .../shop/pay/service/impl/RefundPaymentStore.java  |   1 +
 .../neargo/shop/paybridge/PayOrderBackfillJob.java | 172 +++++++--
 .../shop/paybridge/PayOrderBackfillJobTest.java    | 183 ++++++++++
 .../shop/scenario/PayOrderBackfillFlowTest.java    |   9 +-
 .../scenario/PayOrderBackfillSafetyFlowTest.java   | 359 ++++++++++++++++++
 .../shop/scenario/RefundReachesChannelTest.java    |   3 +-
 docs/technical/README.md                           |   1 +
 docs/technical/TDD-支付服务-I3支付单退款流水.md    |   7 +-
 docs/technical/TDD-支付服务-W3收单补完.md          |   2 +-
 .../technical/TDD-支付服务-历史支付回填安全收尾.md | 148 ++++++++
 docs/technical/TDD-支付服务-资金闭环收尾.md        |   4 +-
 docs/technical/TDD-支付服务-退款申请人贯通.md      |   2 +
 docs/technical/TDD-支付服务-退款统一入口收尾.md    |   2 +
 docs/technical/reference/glossary.json             |  11 +
 docs/technical/reference/中英文对照-实体与字典.md  |   5 +-
 .../technical/支付服务-完成度复核与剩余开发计划.md |   6 +-
 19 files changed, 1210 insertions(+), 155 deletions(-)
```
