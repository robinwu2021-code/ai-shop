# Task57 实施报告

Status: READY_FOR_ROOT_R2_REVIEW. Remaining I3 first-send gap corrected with focused RED/GREEN; independent scoped re-review/acceptance remains pending. 2026-10-24.

IMPLEMENTATION_BASE=`b34e31ba4f10bc75088ccd44d1eef4ef0c5a4ac1`。V540 已由 root 两次独立核验7477 bytes/SHA256 `ca76b6512cf79c6f25fa71a4441f533e03cc609c6320611a0dcdc9634a10f745` 并授权首次执行，现永久冻结。实际逐批命令/结果见§10；没有 commit/stage/push/merge/deploy，没有真实外部资金。root-owned brief/progress/mainTDD/ADR/pin/reviews始终排除。

以下§1–9保留**设计门时的历史审计记录**，其中“当前/未执行/仅两文档owned”仅描述设计门当时，不代表现实施状态。§10起为有时间顺序的实际实施账；最终owned/AC将另列明确快照。

## 1. 基线与授权

* 工作树 `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`，`codex/beauty-foundation`。
* DESIGN_BASE `5ef6d5f7fa1f9be593e467759ebaa5a8a7caa034`；Task56 accepted `dbb635a7cf9d36ddddfbd0138e8dfc40216d4e9b`。Task56六方法和§20/§23基线不重做。
* 原 cwd `/Users/robin/work/ai/ai-shop-codex` 是其它会话工作区，没有编辑。
* 先读 task-57-brief，再读绑定 bm04-checkout-design §3/7/8/10–11、task-56-report最终AC/§20/§23、CLAUDE.md、ai-shop-dev-standards SKILL.md/Java标准、工作流技能。未重读整篇主TDD。
* 应用 brainstorming/writing-plans 的设计阶段与项目规范；已读取 TDD/verification 技能供后续真实 RED→实现→GREEN，当前没有提前实施。root 唯一review，本实施者没有调用子代理/审查helper。
* 两份文档位于 sandbox writable roots 外，写入使用针对这两文件的 narrow require_escalated，经工具批准；没有扩大写入范围。

## 2. 真实源码审计与结论

详细 FQN/精确签名/DTO/路径/锁序/DDL见 task-57-design.md。以下均来自当前文件读取，不来自假设目录/旧草案：

| 审计范围 | 实际结论 |
|---|---|
| CheckoutPort、CheckoutServiceImpl、CheckoutAccessPolicy、MpCheckoutController、CheckoutInternalApi | 当前只有六方法；PaymentFact已有tradeNo；C真实TokenStore/MAIN/CONSUMER/USR可复用；内部v1是B scoped读，不能代C initiate/参数授权。source已op-first RR，支付query必须保持锁外。 |
| MerchantOperatorAccessPortImpl/OperatorAccessMapper/OperatorAccessRow/BizIdentityResolver | 同namespace SQL已返回真实accountNo与allowed；最小新增resolveAuthorizedOperator默认Optional.empty，原allowed合同保留，实际MCH与USR映射区别不能丢。 |
| PaymentLedgerServiceImpl/PaymentLedgerService/SettleMappers/StlPayment | open无order guard；旧init false→close可能抹未定尝试；close REQUIRES_NEW不能在持guard事务中再调。settle保留真实通道金额，订单单独核验；refund容量锁不得倒置新增guard。 |
| SettlePortImpl/PaymentReconReconciler/PayCallbackController/ChannelPayCallbackController | 旧新init必须共用canonical发送；回调/query settle后锁外投影；notFound不是可靠终态，SENDING/PAYABLE/UNKNOWN必须defer。所有open/close/settle调用点已列设计§4.1。 |
| PayGateway及微信直连/微信聚合/支付宝/FREE/TEST/STUB | 原PrepayResult四字段的false不证明未发送；QueryResult不区分确定未付终态，且真实adapter未全部核echo out_trade_no。候选新增明确NOT_SENT，仅本地client前失败可用；真实query必须核原号/金额/currency。只隔离TEST/STUB第三方测试，不动真实资金。 |
| OrderServiceImpl.markPaid/PaymentLifecycleServiceImpl/MerchantOrderServiceImpl | 当前markPaid锁内读SettlePort，需要拆成锁外readConfirmed和锁内纯receipt验证；managed旧线下shortcut继续拒绝，正式receipt才PAID且无旧verifyCode/伪服务完成。 |
| 当前managed投影provider逐级 | StockPort三实现无HTTP：PLATFORM本库、DUAL本库+outbox、INVENTORY v1独立invTX/v2本地outbox；person→PersonService→mapper，member→MemberService→AttributionPort→本地mapper，merchant额度本地mapper，entitlement决定/card快照本地mapper+outbox。generateForOrder本来AfterCommit；精确表在设计§4，已审计闭合而非等实现后调查。 |
| 旧unmanaged offline与竞态测试 | 旧RECEIVE入口只ord线下事实/账单，无stl SUCCESS，不能称新receipt。既有cash race测试实际是ONLINE渠道到账回调。生产唯一init caller必须WAIT_PAY，旧offline必须WAIT_OFFLINE_PAY且无两者转换边，因此该伪想交错不可达；增加真实offline确认前/并发/后C pay均拒的反例，详见§4.2。 |
| 媒体SysMediaAsset/BizUploadController/MediaUploadService/MediaScanner/MediaPurgeService/MediaReconcileJob/MediaReadController/MediaBackfillService | 无tenant字段，旧uploadedBy是主体号；需新OFFLINE_RECEIPT私有用途，服务端当前店/entity/actual account派生。真实bytes锁外exists，owner核ACTIVE等事实并冻结到pay。清理排除须包括filter/显式key/旧队列执行/定时job；PENDING实际是MediaReconcileJob，专用用途保留，不用不存在的MediaScan流程。 |
| PayModePort/PayModeServiceImpl/MerchantPayPortImpl/StorePaySettingServiceImpl | 四层商品/类目/资质/门店+履约仍权威；OFFLINE CASH/BANK_TRANSFER是纯记账，不平台资金通道，保留平台券/补贴限制、影子佣金/零佣金/无分账/排除通道对账。 |
| PayDataSourceConfig/pay-svc yml/BaseEntity/AuditMetaObjectHandler | pay独立pool/TX同平台schema，pay-svc Flyway关闭且backend/pay无迁移；平台实际最大539。旧财务身份是全局order/outTrade，MAIN默认审计字段不等于已启用tenant隔离；guard全局orderNo唯一，CHECKOUT MAIN、LEGACY不新加MAIN拒绝。 |

已读的需求精确语义：PRD-支付方式与预约预定 §7.1为A纯记账/不抽佣；PRD-支付域 AC3.6/6.3要求offline不分账/排除通道对账；TDD-线下收款商家开关保留正式四层规则。receipt表示当前有权经办确认线下已实收，不能声称银行或平台核验到账。

## 3. 设计已闭合的决定及 root 审查修正

1. trade长期P0先冻原key/hash/owner命令，再pay P1/P2。不是24h cache；原key异payload冲突，原操作永久可恢复。
2. 每actual payment一个canonical发送root，aliases不独立SENDING，不对payment_no/out_trade_no加unique。SENDING/send_started提交后锁外gateway；崩溃/false/timeout/query notFound只查原号，不close/reopen。
3. OFFLINE两个新key可先各PREPARED，完整offline_request_json不占evidence unique；P2同全局guard选root，root独占证据索引，alias仅原请求json+映射。A在P2前崩溃B可root，A恢复alias。不同订单重用证据整P2回滚，不留半笔SUCCESS。
4. CHECK显式method/actorRealm/actorKind非NULL，避免MySQL三值逻辑放行；PREPARED/ALIASED的列状态与JSON要求独立约束，计划真实NULL组合反例。
5. raw requestKey仅header/受控typed body，绝不URL；查询按业务paymentOperationNo，丢响应用header lookup。B按原key只自己actor/action命名空间，不能泛查C或另一经办。
6. 首笔线下已实收申报即使P0前订单已CLOSED/CANCELLED，也以已消费C确认+原quote+原order一致的应收记真实receipt，随后原LATE_PAYMENT resolution，原order不PAID。当前cash权限/店归属/证据仍必须；四层准入已在原quote/place完成，当前商品下架/删除或offline开关后来关闭不能拒记已实收，不能改应收或重收。
7. 新专用receipt媒体实际account上传、不可变绑定root/method/order/account/time/metadata，BANK_TRANSFER必填；CASH至少note与经办，附件如提供也同样核验。单证据单笔全额，未引泛化反欺诈/银行API。
8. Permission pay:cash:receive独立；OWNER沿既有*，无其它默认role赠权。旧allowed和RECEIVE不升级为新收款授权。
9. 旧资金owner真实identity为全局orderNo；guard唯一order_no，无MAIN硬CHECK。PaymentOperationPort.query(String)按全局业务号，operation_no也全局唯一。未来多tenant需要全套identity/查询迁移，本次不伪实现。
10. 真实付款回执receipt_no在stl_payment首次真实settle生成且稳定；不在媒体/美业建余额表，不把历史SQL seeded SUCCESS当新正式回执。

root已经方向批准纯记账/evidence/实际account/兼容allowed/canonical/租户事实，本候选精确DDL和最终代码仍需root独立审查，方向消息不代表实施放行。

## 4. 只读命令记录与非测试错误

本阶段使用 `git rev-parse/status/show`、`rg --files`、`rg -n`、`cat`、`sed -n`、只读Python(Path glob/stat/hash)；未运行Maven/Vitest/SQL/Flyway/docker/colima/网关。实际读取分组：

* checkout contracts/identity/managed guard/source RR/确认消费与Task56报告；`rg --files backend`定位实际类，再`sed`定点。
* `rg -n '\.initPayment\(|\.openPayment\(' backend --glob '*.java' --glob '!**/test/**'`确认唯一旧生产init caller；ledger `open/close/settle/refund`、所有callbacks/recon与existing cash race全文。
* `rg -n 'WAIT_OFFLINE_PAY|setPayMode\(|setStatus' .../trade`及PaymentMachine全文，核线上/旧线下状态互斥。
* pay渠道DTO/adapter、mapper/DS配置；migration Path glob计数/排序与V539 SHA256；没有查询运行库Flyway history。
* media owner模型/上传/签名读/实际delete调用点/scan/purge/reconcile；门店策略/权限常量与三语/mock/生成器输出路径。
* managed projector各可达Port的真实所有实现/条件装配与下游Mapper；BaseEntity/AuditMetaObjectHandler/无tenant查询事实。

读取中出现过路径定位失败：误猜 `trade/order/service/impl/OrderServiceImpl.java`、`ProductPayModePort.java`、`marketing/service/impl/AttributionServiceImpl.java`、`trade/entitlement/EntitlementOrderUseService.java` 等，随后用rg定位真实路径；一次zsh未匹配mybatis/*.java glob，未执行对应read；一次误猜backend/shop-beauty迁移路径得到0，后rg定位backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty，实际7版本V1–V7。末次文档写入曾因heredoc非UTF8 SyntaxError未执行，改ASCII编码脚本重试；不是产品/业务RED。大输出偶有截断，关键合同/SQL/条件已定点复读。上述均非业务RED/compile失败，没有影响产品状态。真实测试名称 `PayTxManagerConventionTest`，不是配置注释里旧名CrossDomainTxConventionTest，候选命令已更正。

## 5. 候选SQL与owned

候选完整DDL仅嵌设计§6：平台V540__checkout_payment_operation.sql，新增ord_checkout_payment_request、stl_payment_order_guard、stl_payment_operation，追加stl_payment.initiating_operation_no/receipt_no与索引。尚无真实V540文件，未执行。平台459个Vsql最大539，V539 8444bytes/SHA256 5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b；beauty V1–V7/27表冻结。最终候选hash由只读文档提取记录，root需独立复核并pin，首次执行后不可改。

当前实施者owned仅两份文档。未来候选产品/测试/生成物逐个准确路径列设计§7；不把主TDD/brief/progress/ADR/reviews/staging混入。工作树status中root同时修改的task-57-brief.md/progress.md/主TDD不是本实施者成果，不stage、不还原。git check-ignore -v实际确认两份新文档被.gitignore:42的.superpowers/规则忽略，提交归root后续明确处理。

## 6. AC、验证计划与独立重启要求（全部未执行）

| 组 | 计划主要selector | 当前证据 |
|---|---|---|
| 原key/原号/canonical/所有old-new open | PaymentOperationNativeMysqlTest 同键/异键/并发guard/alias/rollback/UNKNOWN/NOT_SENT/SQL NULL | PLANNED，0 executed |
| 发送前崩溃/响应丢失 | PaymentOperationProcessRestartNativeTest 两个真实独立JVM PID、同DB、原key查原号、独立第三方故障server记录 | PLANNED，不能用清cache/新bean/同进程context代替 |
| 当前C与真实B | CheckoutPaymentAuthenticationFlowTest/CheckoutCashPermissionFlowTest，真实HTTP/TokenStore/namespace/授权撤权 | PLANNED |
| evidence与正式offline | ReceiptEvidenceFlowTest/OfflineReceiptFlowTest，真实owner记录/bytes/店/actualaccount/不可删除、全额/重复/关闭前首申报晚款 | PLANNED |
| managed/source/旧支付回归 | CheckoutManagedLifecycleFlowTest/CheckoutSourceFactsFlowTest/PaymentInitiationCashRaceFlowTest/PaymentLedgerCloseRaceFlowTest + 真实既有离线/零元/退款 | PLANNED，Task56通过不算Task57通过 |
| 分层/三语/生成 | 设计§8准确Maven selectors/SQL portability/generated gates/shared精确pay权限检查 | PLANNED；已有债单列 |

准确命令、runner环境、最小消融与逐字节还原见设计§8。实施先真实行为RED（缺接口编译/setup错误单列），再code/GREEN、最小消融业务RED、原字节恢复GREEN、自查immutable snapshot，root独立review；无自动commit。

原生环境冻结：cached mysql:9.7.2、普通DISK64M；Docker context colima-hxkey-tests/profile hxkey-tests，每批MemAvailable>=524288KiB；串行自有UUID、--pull never、loopback随机端口/随机DB、秘密env、finally只清自有资源；JDK21=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home，Maven -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2。禁止test-on-mysql.sh/停他人容器/改VM/共享autotest/远程。native类必须各自显式@EnabledIfSystemProperty，runner选中classes新报告非0且skip=0，不能普通H2假绿。

## 7. Task56既有债与未验证边界

* ArchitectureTest的既有OpsPoints FQN和store登记问题；beauty/store job.config架构最终门归Task60。
* shared4套19项既有11通过8失败：booking:view/manage三语标签；B en common.delete；entity checker对ent_migration_alias SQL AND/资源CHECK误解析；12表清单债（sys/inv outbox stream/delivery/operation及inv event_consumed、库存sync/alignment、refund coupon scope/obligation）；两库存STOCK_SHARED_SOURCE placeholder。不能借旧债遮新失败，也不报告全后端green。
* 旧生成漂移ops-role-endpoint-matrix.json的8个OpsPoints路由和technical README时间/状态；不属于Task57真实新增生成差异。
* Task56 native结构fixture不是全历史Flyway replay；INVENTORY/DUAL native独立库部署未验证。本次候选同样只可声明V539 fixture追加精确V540与fresh结构，不扩大验证结论。
* 现有QueryResult缺确定terminal unpaid信号：候选仅确证NOT_SENT可让新C key换号，其余UNKNOWN保守查原号；参数过期不盲重签。本期不承诺所有通道失败都自动恢复可重付。
* 证据是真实owner归属/私有bytes与经办声明，不是银行到账证明；第三方不可变附件正文真实性不由平台保证。
* Task58绑定、Task59消费端接线、Task60整体验证/真实JAR/deploy、BM05均未实施；本任务仅设计阶段，资金行为验收尚无运行证据。

## 8. 交接门

完成：只读实际source审计、精确设计/完整候选DDL、root技术核对点修订、AC/命令/owned计划、真实报告。
待root：审查最终设计与DDL、确认未定终态保守范围、独立actualmax/hash pin，再发IMPLEMENTATION_BASE与实施释放。当前没有需用户泛化重新授权的事项，不自行越过设计门。

## 9. 最终文档自查（只读，不是运行测试）

* git rev-parse HEAD仍为DESIGN_BASE；git status --short只列root-owned progress.md、task-57-brief.md、主TDD修改，没有产品/测试/SQL改动。两份owned新文档因.gitignore:42未列普通status。
* Python只读统计平台459个Vsql、最大539，V539字节/hash与冻结相同；V540文件不存在。真实beauty目录7个迁移V1–V7，无编辑。
* 设计只有一个SQL代码块，3个CREATE TABLE+1个ALTER TABLE；提取定义为代码块正文UTF-8加一个结尾LF，候选7477bytes、SHA256 ca76b6512cf79c6f25fa71a4441f533e03cc609c6320611a0dcdc9634a10f745。这是候选指纹，不是root已pin，也不是已执行。
* 原单receipt不因后来商品删除/下架或offline开关关闭而被抹掉：原确认消费/quote经济快照/placed订单提供原准入事实，当前cash权限与可信附件仍核验，关闭后保留receipt+LATE_PAYMENT。计划原生AC覆盖该后变情形。
* 已追加真实上传caller/公开static读取/私有预览审计：专用分支不加generic白名单，C/zip上传固定其它用途，MediaUsageService对新用途不签缩略图URL。
* 设计/report显式PLANNED/UNEXECUTED，无RED/GREEN/native通过声明，等待root技术释放。

## 10. Implementation release and first tranche

IMPLEMENTATION_BASE=b34e31ba4f10bc75088ccd44d1eef4ef0c5a4ac1, tracked clean verified before writes. Root released technical implementation; final brief/ADR-047/pin read. No helpers, no commit. Existing report sections 1-9 describe the completed design phase, not current execution authorization.

Created exactly pinned V540 (7477 bytes, ca76b6512cf79c6f25fa71a4441f533e03cc609c6320611a0dcdc9634a10f745); first execution remains gated on independent root rehash. First test added before product implementation: PaymentLedgerCloseRaceFlowTest#unconfirmedPrepayCannotCloseOrAllocateAnotherOriginalNumber. It preserves real ledger/mapper/SettlePort and only loses the isolated TEST third-party response after actual channel acceptance. Expected behavior: original PENDING/outTradeNo, one send, one PAY; current old close-on-false is expected to fail. Not yet run at this entry.

First authentic RED: command JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test -Dtest=PaymentLedgerCloseRaceFlowTest#unconfirmedPrepayCannotCloseOrAllocateAnotherOriginalNumber -Dsurefire.failIfNoSpecifiedTests=false; log /tmp/task57-first-red.log. Exit1, 1 run/1 failure/0 errors/0 skipped, expected PENDING actual CLOSED at line52. Root independently rehashed actual V540 and released first execution at 2026-10-23 16:03:00 UTC; root artifact task-57-v540-actual-file-root-check.json excluded from worker owned. First RED used unchanged old H2 schema, no V540 SQL yet. Native operation test was written next before adding its contracts/implementation; missing symbols are setup, not another claimed behavior RED.

Core first GREEN: same Maven environment, -Dtest=PaymentLedgerCloseRaceFlowTest; /tmp/task57-pay-core-first-green-attempt.log, exit0, 2 tests /0 failures/errors/skips. First response-loss regression now reached all assertions: PENDING original number retained, retry same outTradeNo, send count1, PAY count1. Existing successful-cash close race also passed. This is core H2 GREEN only, not full Task57 acceptance or ablation. Generated schema from pinned migration (363 tables/2400 seeds); H2 initialized it in this run, therefore V540 is now executed/frozen. Actual candidate SQL file unchanged. Product tranche currently contracts/guard/ledger/canonical operations/lock-free dispatcher/old init and recon, not public checkout P0/HTTP/auth/media yet.

Native runner scripts/verify-checkout-payment.mjs derived from the audited Task56 isolation runner: root-pinned V539+V540 verified, original V539 then V540 applied on owned V538 structural fixture, separate cached MySQL9.7.2 UUID, memory preflight, loopback, secrets env, no shared cleanup. First native selectors limited to PaymentOperationNativeMysqlTest (PREPARED no dispatch, different-key alias single canonical); not yet run at this entry.

Core first native GREEN: CHECKOUT_PAYMENT_TESTS=PaymentOperationNativeMysqlTest node scripts/verify-checkout-payment.mjs; log /tmp/task57-pay-core-native-first.log; exit0. MemAvailable3680120KiB, cached MySQL9.7.2; exact V539+V540 applied. Fresh native XML was removed before execution and checked afterward: 2 tests/0 failures/0 errors/0 skips. Coverage is limited to PREPARED query with no ledger/dispatch and different valid keys sharing one canonical/outTradeNo/PAY/send-start marker (plus changed-key payload conflict). This is not concurrency/crash/HTTP/evidence acceptance. Runner finally cleaned its own UUID container/temporary tree; post-run owned-name container inventory empty. No other containers altered.

Legacy compatibility before fixes: /tmp/task57-legacy-compat-before.log, same Maven environment; selectors PaymentInitiationCashRaceFlowTest,PaymentRequestSnapshotTest,ZeroAmountPayFlowTest. Exit1, 10 tests/1 failure/3 BizException errors/0 skips. Snapshot expected 2 sends but actual 1 (intentional new canonical contract; retained original route/account/subject assertions and added same returned params). ZeroAmount three supported flows failed actual receipt validation: gateway tradeNo FREE:<outTradeNo>, old OrderService pay passed outTradeNo into exact receipt lookup. Fixed projection to use actual receipt tradeNo. Existing online stale-read race passed in that batch.

Receipt boundary authentic RED: new assertion in the real callback path observed SettlePort.successPayment under active source TX. /tmp/task57-receipt-boundary-red.log; 1 test/1 assertion failure/0 errors/0 skips, expected false, AtomicBoolean(true). Read now occurs before source transaction; locked method validates the supplied immutable receipt with no financial port calls. Original three-argument validation only retains unmanaged offline local shortcut; managed still rejects. No caller transaction is permitted for ordinary financial projection. Legacy prepare also preserves already-SUCCESS rejection. Compatibility GREEN pending.

Legacy/receipt finite GREEN: /tmp/task57-legacy-receipt-green-attempt.log exit0, 18 tests/0 failures/0 errors/0 skips (CloseRace2, Snapshot5, Impact6, Zero4, InitiationRace1). The new TX observation now sees the real callback financial read outside the source TX. No assertion was removed; snapshot now requires original immutable route plus one send/same returned params. Added CheckoutPaymentAuthenticationFlowTest before its new contracts/production tranche; symbols not yet implemented are compilation setup, not another behavior RED.

P0/currentC contracts, permanent request mapper/entity, CheckoutPaymentServiceImpl, original order instruction builders and HTTP/internal read endpoints are written. First compile /tmp/task57-p0-compile-first.log failed test compilation at CheckoutPaymentAuthenticationFlowTest:77 (one extra closing parenthesis), no tests executed; this is setup, not behavior RED. Corrected test syntax and rerunning. No SQL changes; V540 remains frozen.

P0 compile correction GREEN: /tmp/task57-p0-compile-second.log exit0; PaymentLedgerCloseRaceFlowTest 2/0/0/0. Native current-C first run /tmp/task57-p0-auth-native-first.log exit1, MemAvailable3676952KiB, 3 tests/1 assertion failure/0 errors/0 skips. Authentic new RED: revokedDuringAcceptedDispatchCannotReceiveParametersButOriginalFinancialFactSurvives expected unauthorized throwable, but accepted dispatch still returned params after the actual TokenStore session was revoked. Added final current-session/buyer recheck before returning parameters; original committed pay state remains intact. Other two cases reached first behavior GREEN: P0 survives failed prepare, read does not create P1, same-key params replay, changed channel conflict, B no params, real TEST paid query produces receipt and managed PAID/FULFILLING with null verifyCode; B/revoked-before-write/old managed pay rejected. Runner finally completed and owned container inventory empty. This batch is not a full native GREEN until the rerun passes.

P0/current-C native GREEN: CHECKOUT_PAYMENT_TESTS=CheckoutPaymentAuthenticationFlowTest node scripts/verify-checkout-payment.mjs; /tmp/task57-p0-auth-native-green.log, exit0, MemAvailable3660560KiB, 3/0/0/0. Runner verified fresh nonzero native XML and completed finally; owned container inventory empty. Post-dispatch current-session recheck now rejects revoked token; a fresh valid session reads the same PAYABLE original operation afterward. This is current owner/session service behavior on MySQL, not yet the full HTTP negative/independent-process/ablation AC.

Cash authority first native behavior GREEN: /tmp/task57-cash-permission-native-first.log exit0, MemAvailable3658932KiB, CheckoutCashPermissionFlowTest3/0/0/0, fresh XML verified and runner finally completed. Tests written before new capability implementation; first behavior run GREEN, no historical RED claimed. Covers real current TokenStore, default old-provider fail-closed Optional.empty, owner wildcard/actual MCH account, actual USR linked account, wrong namespace/store denial, receive/view-only denial and explicit cash grant/revocation within one session. Merchant allowed(Query) remains compatible and uses the same existing typed SQL. New dedicated receipt retention test added before media policy changes.

Receipt retention authentic RED: /tmp/task57-receipt-retention-red.log, native1/1/0/0, expected ACTIVE actual RECLAIMABLE at scanner assertion. Explicit/filter purge assertions were masked, not claimed as prior RED. Dedicated type now excluded from scan and every reclaimable query, including includeQual; queued execution refuses actual dedicated targets; pending reconciliation preserves missing bytes rows and only conditionally completes PENDING; ordinary governance preview emits no signed URL. Added queued/preview and pending owner-record tests before finite GREEN.

Cash labels: first command from repository root produced No test files found (setup only). Correct package-root command node ../../node_modules/vitest/vitest.mjs run tests/checkout-cash-permission.test.ts produced /tmp/task57-cash-label-red-actual.log 5 tests/4 failure/1 pass for missing three locales/mock. Added exact approved labels without default-role grants. /tmp/task57-cash-label-green.log exit0,5/0; existing biz-only guard was not misrepresented as covering pay:cash.

Receipt retention finite native GREEN: /tmp/task57-receipt-retention-green.log exit0, MemAvailable3669352KiB,3/0/0/0 fresh XML verified. Original scanner test now reached explicit/filter purge negatives; additional queued execution retained real bytes with zero purged count and no preview credential; actual pending job retained absent-byte record and completed existing bytes. Real bytes live only in class-owned UUID /tmp directory cleaned by AfterAll, DB in runner-owned MySQL cleaned in finally. Dedicated current-cash upload HTTP regression added next before uploader implementation.

Dedicated upload authentic RED: /tmp/task57-receipt-upload-red.log native1/1/0/0, legal current cashier HTTP upload expected code0 actual10400 at line136. Later scope/privacy assertions were masked. Added dedicated current-token/cash/account uploader and real media owner verify adapter; first GREEN pending. Root approved local timestamp correction: new OFFLINE_RECEIPT MediaUploadService writes createdAt UTC only for this purpose; fingerprint excludes updatedAt. Existing purposes unchanged. Offline receivedAt lower bound is original CONSUMED confirmation.consumedAt UTC, slightly stricter than order creation within the same transaction, never reinterpret legacy local order.createdAt as UTC. No DDL change; +08 evidence test added.

Receipt owner checkpoint /tmp/task57-receipt-owner-green-attempt.log native5/1/0/0: original retention3 and dedicated legal current-cash upload/private/revocation HTTP passed; owner +08 timestamp range failed (actual16:55:05Z upper16:55:04.581606Z). V152 actual created_at DATETIME(0) rounds fractional input; new OFFLINE_RECEIPT timestamp now explicitly UTC truncated to SECONDS, matching real storage precision and avoiding a future rounded fact. No broad time tolerance or schema change. Remaining owner negatives were masked at this checkpoint. OfflineReceiptFlowTest initial HTTP test written before endpoint implementation.

Mixed checkpoint /tmp/task57-evidence-green-offline-red.log exit1: ReceiptEvidenceFlowTest5/0/0/0 (all UTC/actual owner/bytes/no-TX and current cashier upload checks reached), OfflineReceiptFlowTest1/1/0/0 expected HTTP200 actual404. Total6/1/0/0 is not batch GREEN. Native XML was deleted before run and regenerated; runner final success validation did not run because Maven failed. Own finally completed. Offline endpoint absence is authentic RED; later ledger/alias assertions masked. Added real cancellation/policy change and strict amount/UTC consumption-seal tests before implementing OfflineReceiptPort/service/controller/permanent P0. First implementation compile/GREEN pending.

Offline first implementation checkpoint /tmp/task57-offline-first-green-attempt.log3/1 assertion/1 SQL fixture error/0 skips. Real receipt committed, but normal B HTTP expected SUCCEEDED returned MANUAL_REQUIRED: actual DataScopeRegistration ord_order only SELF/COMMUNITY, so current MERCHANT scope hid the order at trusted financial projection. Bridge now removes caller data scope only around the internal projection after an actual SUCCESS receipt; public current-cash authorization and locked receipt validation remain. The late test's ord_payment_resolution.order_no query was a fixture error (actual field pay_no), corrected without claiming business RED. Strict amount/consumed-time test first behavior GREEN; late reached actual receipt+unchanged CANCELLED before the fixture error. Added bank real bytes/evidence binding, original-key replay after status change, alias and cross-order unique rollback test.

Offline finite first GREEN /tmp/task57-offline-second-green-attempt.log exit0,4/0/0/0, fresh XML/nonzero/0skip verified, own finally completed. Normal B receipt now projects PAID/FULFILLING/null verifyCode; same-key/new-key alias keep one real PAY/receipt. Actual cancellation plus goods deleted/store switch off retains original303 SUCCESS+LATE_PAYMENT with CANCELLED unchanged. Strict input/time boundary and BANK_TRANSFER owner/bytes/evidence unique atomic rollback passed. This does not yet cover source metadata, concurrent open, independent process or ablation. Self-review found the cross-order evidence DuplicateKeyException escaped to generic logs (private key in SQL exception), while the test only required nonzero; strengthened the assertion to exact20021 and invalid typed amount to10400 before the bounded-error fix. Added real canonical source receipt/alias/TX regression before source changes.

Focused actual RED /tmp/task57-offline-errors-source-red.log3/3/0/0: bank cross-order uniqueness expected20021 actual10500, typed negative amount expected10400 actual10500, source canonical paymentOperationNo expected original PO actualnull. Later source outTradeNo/receiptNo/TX assertions masked. P2 duplicate key is now translated after rollback to bounded20021 with no private SQL exception cause; receipt controller translates constructor validation to10400. Source now reads all permanent request mappings and queries actual financial owner before opening RR snapshot, deduplicates aliases by canonical payment/receipt, validates mapping stamps inside the op-first snapshot, and carries canonical operation/outTradeNo/receiptNo. Missing or inconsistent required owner evidence stays UNKNOWN with original last verified pair; no fallback zero. Historical unmapped receipts retain their Task56 legacy null metadata behavior. Late actual receipt can appear while order stays closed with BLOCKED execution. Finite GREEN pending.

Offline/source finite GREEN /tmp/task57-offline-source-green-attempt.log exit0,14/0/0/0 (OfflineReceipt5, existing SourceFacts9). Runner verified fresh nonzero XML with0skips; own finally completed and read-only Docker inventory for codex-checkout-payment-* empty. All three exact errors/source REDs now pass; old source op-first RR/refund concurrency tests pass. A read-only Docker inventory initially lacked sandbox socket access (environment permission only, not business failure), rerun with narrow escalation succeeded. Added independent-process test support: separate real JVMs, real current-C HTTP and owner DB, loopback-only TEST provider survives child death. Planned two crash windows after SENDING commit (before external send and after third-party acceptance before response); process tests not yet executed at this entry.

Independent-process first attempt /tmp/task57-financial-process-first.log failed test compilation before any tests: helper line123 Java regex used an illegal single backslash escape. Corrected to [.] literals for the exact loopback-only host pattern; 0 tests executed, setup error only, no business RED. First corrected execution pending.

Independent-process first behavior GREEN /tmp/task57-financial-process-second.log exit0, MemAvailable3661756KiB,2/0/0/0. AFTER_ACCEPT actual child PID36332 was forcibly terminated only after committed SENDING and loopback third-party acceptance; independent PID36404 used real current-C HTTP original-key lookup, queried the same original number, stored SUCCESS/realRC and projected PAID, sends remained1. BEFORE_SEND PID36503 was terminated after SENDING commit before any external dispatch; PID36620 original-key lookup retained original PENDING/SENDING, sends0, no replacement. Both scenarios used one actual PAY and distinct process IDs, not a restarted Spring context/cache. Fresh native XML/nonzero0skip verified and runner finally completed. Child logs under shop-app/target/task57-process-87f3ba82-881a-48fd-8d4a-9072e2fffa1e and task57-process-71294d9a-27c8-46dd-9a77-8563113d5aea; contain no raw original key/token result fields. Startup inv-managed backfill dry-run emitted existing null category cache warning for test fixture goods (apply=false, no write); financial assertions passed, not a global inventory claim. No prior process behavior RED claimed; only the earlier compile setup error.

Gateway original-number authentic RED /tmp/task57-gateway-original-red.log exit1,6/5 assertion failures/0 errors/0 skips. Actual direct WeChat, ecommerce WeChat and Alipay adapters accepted another out_trade_no; malformed minor units were accepted; local precondition expected NOT_SENT but returned UNKNOWN. Valid original currency case passed. Later malformed-field/local-default assertions were masked, not claimed as prior RED. Added exact original echo/nonblank trade/currency/exact nonnegative money validation; only local precondition/default-unimplemented paths return NOT_SENT, all post-I/O failures remain UNKNOWN. Removed reusable JSAPI parameters from existing log, retaining boolean self-signature check. Finite channel GREEN pending. Independent-process own children and temporary control directories were cleaned in finally, retained target logs only; Docker owned-name inventory empty.

Gateway finite GREEN /tmp/task57-gateway-original-green.log exit0,23/0/0/0 (new6 plus existing WechatDirect17). All masked malformed identity/amount/missing fields now reached; existing original subject/account/query/refund assertions retained. Actual providers were only in-memory ChannelClient adapters, no external funds/network. Added seven native core boundary/concurrency/reconciliation tests before further changes; these are planned/unexecuted at this entry.

Native core checkpoint /tmp/task57-guard-recon-native-red.log exit1, MemAvailable2689700KiB,9/2 assertion failures/0 errors/0 skips. Actual RED: new CHECKOUT PaymentOperationReq accepted a foreign frozen store; OFFLINE pending ledger appeared in channel recon as notFound. Missing-route assertion was masked. Seven cases passed, including two previously prepared requests concurrent single canonical/send claim, old/new concurrent open with historical tenant audit sharing global guard, SUCCESS preventing another chargeable attempt, SENDING/UNKNOWN protected from legacy close/recon, and NOT_SENT retaining original rejected mapping while only a new operation allocates. These are first behavior GREEN, not fabricated prior RED. Minimal fixes add CHECKOUT-only exact store/route validation (legacy unaffected), exclude OFFLINE from external stale channel scan, and register financial evidence_ref with the existing media owner registry. Added real HTTP bank two-PREPARED-before-open/NULL-check, offline P0 unavailable/recovery and mapped source UNKNOWN tests; still unexecuted. V540 unchanged.

Guard/offline checkpoint /tmp/task57-guard-offline-native-green-attempt.log exit1,17/1 assertion/0 errors/0 skips: core9/0/0/0, Offline8/1/0/0. Bank concurrent two P1 PREPARED/no PAY and then one real receipt/evidence/alias all passed before a test exception-type mismatch: actual MySQL rejected method=NULL with3819/HY000, Spring translated UncategorizedSQLException rather than the expected DataIntegrityViolationException. Corrected assertion to actual DataAccessException root SQLException3819; no SQL/product relaxation. Offline P0 durable recovery and mapped UNKNOWN/last verified pair cases first behavior GREEN. Mandatory evidence bean now directly injected, no optional successful bypass.

Focused /tmp/task57-projection-scope-red.log exit1,2/1/0/0. Bank two-PREPARED test1/0/0/0 now reached all NULL checks (method/realm/kind/actor/account/time/evidence_ref/evidence_json) against frozen V540. Managed scope authentic RED1/1/0/0: altered original sub-store still projected SUCCEEDED instead of MANUAL_REQUIRED. Added locked pure-local original CONSUMED confirmation/quote/order/sub scope/economic and original OFFLINE-versus-channel validation; mismatch keeps actual owner SUCCESS receipt and writes RECEIPT_MISMATCH without projecting PAID. No financial/media/merchant port under this lock. Finite GREEN pending. Old unmanaged offline HTTP before/during/after test added; no new cash permission or fake ledger receipt applied to old behavior.

Managed/offline finite GREEN /tmp/task57-managed-offline-native-green.log exit0,12/0/0/0 (offline8/currentC4), fresh native XML verified and own finally completed. Managed altered store now retains actual SUCCESS+RC with RECEIPT_MISMATCH and WAIT_PAY; original valid online/offline paths still PAID/FULFILLING/null verifyCode, P0/key recovery and token revocation unchanged. At this checkpoint later-added offline HTTP permission/key-namespace/strict-missing-fields and billing assertions had not run and are excluded from the12 count. Added explicit public JSON/money/privacy contract tests and updated accepted eight-method/readonly-internal contract plus three B permission endpoints. Ordinary compatibility and remaining native cases pending.

Ordinary compatibility checkpoint /tmp/task57-ordinary-compat-first.log exit1: shop-base6/0/0/0; shop-app74/4 assertion failures/2 setup errors/12 skips. Ordinary native-only managed12 skips are expected isolation, not native coverage. Existing offline5+11+16, close2, snapshot5, lifecycle8, refund3, zero4 passed. Initiation new legacy fixture treated old G0002 empty store as an actual store (EmptyResult at108); changing gateway to spy exposed an existing nested spy call inside unfinished settlement stubbing (Mockito setup error). Fixed fixture to actual merchant store and computed provider trade before stubbing. Recovery three SUCCESS/PENDING failures arose because old teardown close now correctly protects PAYABLE; global fake query answer assigned one tradeNo to every retained pending row, violating real unique channel trade identity. Scope the third-party replacement to actual requested outTradeNo, retain all cash/currency/late assertions. Fourth old test expected accepted timeout→close→replacement; this conflicts with new safety contract. Preserve the full historical duplicate-cash resolution assertions using an explicit pre-migration ledger+TEST acceptance fixture (no operation), historical close then current new init and actual callbacks. No production uncertain-close relaxation. Finite rerun pending.

Ordinary focused checkpoint /tmp/task57-ordinary-recovery-second.log exit1,8/1 assertion/0 errors/0 skips: Recovery6/0/0/0 preserves original actual cash, foreign currency, late closed and historical duplicate receipt guarantees; Initiation2/1/0/0. Remaining new legacy test barrier never fired because actual old BizOrderController passes entity as its historic operator, whereas the test paused OFFLINE:<account>. This is instrumentation mismatch, not a financial behavior RED; corrected to actual OFFLINE:<entity>, preserving the documented old limitation without changing production or claiming old actual-account receipts. Original stale online race now passed with precomputed spy argument. Root reviewed and accepted exact-original external answers plus explicit pre-migration no-operation historical fixture, never UNKNOWN close exemption.


Legacy offline boundary finite GREEN /tmp/task57-legacy-offline-boundary-third.log exit0,2/0/0/0. Actual unmanaged OFFLINE C pay before/during/after old merchant confirmation stays denied; zero chargeable ledger rows and zero gateway sends. Existing stale online race also passes. Old merchant operator remains historic entity, not a newly claimed actual-account receipt.

Final boundary checkpoint /tmp/task57-final-boundaries-native-first.log exit1, MemAvailable2750184KiB,23/3 assertion failures/0 errors/0 skips: Offline9/1, Authentication7/2, Media6/0, selected actual two-connection/same-schema1/0. True REDs: changed original item paid share still SUCCEEDED; managed FREE actual receipt still WAIT_PAY. Fixes validate frozen item economics with local source reads and project actual FREE SUCCESS before returning. Wrong-store assertion used nonexistent header; existing BizContextFilter/Resolver intentionally falls back to default store. Root accepted real accessible second-store fixture preserving denial assertion and global old header behavior. Later key/permission assertions were masked. New concurrent current-C three-request test was added after this compilation and is not part of23. Current billing zero-commission/no-split, strict missing/extra fields, actual C HTTP/revocation and sixth media generic-upload isolation cases first behavior GREEN; no prior RED claimed. Finite affected rerun pending. Frozen V540 unchanged.


Final boundary finite GREEN /tmp/task57-final-boundaries-native-second.log exit0, MemAvailable3672172KiB,38/0/0/0: SourceFacts9, OfflineReceipt9, ManagedLifecycle12, Authentication8. Four fresh XML classes verified/nonzero0skip; finally completed and read-only owned Docker inventory empty. The new actual-item mismatch and FREE receipt projection assertions pass. Correct real second-store denial now reaches foreign actor raw-key denial, same-store authorized operation-number readonly access, revoked cash replay rejection and view-only original receipt recovery. Concurrent same original key twice plus new alias yields one real payment and one lock-free send. Existing refund/source RR/lifecycle safeguards remain passed. SQL portability /tmp/task57-sql-portability.log exit0,162 hits/51 files with54 pre-existing recorded entries, no new dialect dependency. Cleanup removes unused old dispatch dependencies/dead ledger helpers and formats76 owned Java files (full new files, changed ranges in old files), adds explicit durable-P0 row/aged lookup and real MediaStore.exists transaction observations. These additional observations and formatting compile are pending finite ablation baseline, not included in prior38.


Controlled ablation: /tmp/task57-ablation-baseline.log exit0,4/0/0/0,MemAvailable2617020KiB; /tmp/task57-ablation-red.log Maven exit1,4/3 assertion failures/0 errors/0 skips,MemAvailable3630880KiB. Temporary 24h key predicate caused aged-original lookup NOT_FOUND (assertThatCode line96); removing post-dispatch current-C recheck returned parameters to revoked session (line244); removing canonical phase/send-start guard allowed2 claims expected1 (line150). Real MediaStore.exists outside-transaction observation remained GREEN. Original P0 durable row before unavailable owner was reached and passed before the TTL failure; no historical pre-implementation RED fabricated. /tmp/task57-controlled-ablation.py finally restored all3 files byte-exact; /tmp/task57-ablation-manifest.json records before/mutated/restored SHA256. Restored four identical selectors /tmp/task57-ablation-restored-green.log exit0,4/0/0/0 with fresh XML0skip. Hashes: CheckoutMappers d8cf39dbf76c5a4dad92bdfb11bf1f953464649ed17d360779987d31377efaca; CheckoutPaymentServiceImpl 437749a05d778afd7583a4a8fa0e739ab59c23e7eefca4f2766394c5742fbe72; PaymentOperationServiceImpl 64a62786617f07a333fb966ce3ae2fe09df244c907cd6c9d4e259f7e3ecd5350.

Shared finite static /tmp/task57-shared-static-first.log exit1: exact suites checkout-cash-permission/entity-alignment/message-placeholder/i18n-parity,18tests/14pass/4fail, newcash5 GREEN. The4 failures exactly old AND pseudo-columns,12 unmodeled tables,B en common.delete,two STOCK_SHARED_SOURCE placeholder calls. Separate biz-perm-label first run /tmp/task57-biz-label-existing.log had0tests because formatting split literal LABELS = Map. consumed by the existing scanner; restored that declaration shape without changing scanner. /tmp/task57-biz-label-existing-second.log6tests/2pass/4fail, precisely existing booking:view/manage three-language sets and Chinese duplicate-label parity. Not claiming18 vs prior19 directly comparable or old booking debt gone. Self-review identified an unforced alias-wins-first-send response identity window; added native assertion before any corresponding product correction, pending actual RED.


Alias winning first-send authentic RED /tmp/task57-alias-response-red.log exit1,MemAvailable3651544KiB,1/1assertion/0errors/0skips: expected caller alias operationNo, got canonical operationNo at line64. RequestHash/replay assertions were masked. Trusted dispatch now preserves canonical ticket mutation but re-reads the requested operation view when identities differ; no second send/open and no DDL change. Added two bounded third-party scenarios for alias response loss after acceptance and proven local NOT_SENT before first corrective GREEN; these have no prior behavior RED claim. Finite regression pending.


## 11. 最终实施与有限验证收口

最终 alias / 原号 / 当前C / 独立进程批次 `/tmp/task57-alias-final-native-green.log` exit0，MemAvailable3672712KiB，**23 tests / 0 failures / 0 errors / 0 skips**：PaymentOperationNativeMysqlTest13、CheckoutPaymentAuthenticationFlowTest8、PaymentOperationProcessRestartNativeTest2。alias首发返回自身operationNo/requestHash，canonicalOperationNo/outTradeNo/账本仍原值；实际接受、接受后丢响应、本地确证NOT_SENT三种结果均覆盖。既有all-open guard、SQL CHECK、NOT_SENT/UNKNOWN、current-C/params/真实回执投影保留。独立进程实际记录：AFTER_ACCEPT PID50475→50531，send1/SUCCEEDED；BEFORE_SEND PID50586→50870，send0/SENDING。各自 child/server/tmp finally 清理；保留target/task57-process-3bf4bc2b-9a1a-4956-8481-d29a57ea64e1及6dc57a75-4db9-449a-98a1-c489d206a250日志；Docker自有名称查询为空。没有外部真实资金或生产fault hook。

普通最终gates `/tmp/task57-ordinary-final-gates.log` exit1：shop-base6/0/0/0；pay-channel23/0/0/0；shop-app48/4 failures/0 errors/1 skipped。ZeroAmount4、BackendI18n6、ConditionalBean1、PayTxManager1、PaymentLedgerCoverage1、MediaRef2、ErrorCode3、BizRole3、SchemaParity1均GREEN。FoodEmbeddedWiringTest在默认retail形态6项，5实际通过、1 standalone-only跳过。Architecture16两项为既有OpsPoints FQN和store顶层登记。BizEndpointPerm4两失败：新cash常量未被旧knownCodes识别是本任务新增检查缺口，已仅追加精确CASH_RECEIVE（不扩大pay:*）；另一项四个既有端点未登记详见§13。修复后 `/tmp/task57-food-cash-gate.log` 用明确`-Pfood-embedded`，FoodEmbeddedWiringTest6项5通过/1 standalone-only跳过，BizEndpointPermTest#allCodesExist1通过，合计7/0/0/1 BUILD SUCCESS。没有宣称完整普通gates已GREEN，也没有把standalone skip计为通过。

普通H2隔离检查 `/tmp/task57-ordinary-native-isolation.log` exit0，六个具体新native-only类**41 tests / 0 failures / 0 errors / 41 skips**（Offline9、Process2、Core13、CashAuthority3、Media6、CurrentC8）。这是默认构建隔离证据，实际业务执行为0，绝不替代前述各MySQL0skip证据。执行前删除这六类旧XML；删除前已保存原native XML计数/sha256至`/tmp/task57-native-xml-before-isolation.json`；本轮fresh XML逐类全部skip符合明确class-level condition。没有改全局POM掩盖native类。最后这次普通编译包括精确原字符的长测试SQL/JSON字符串分行和最终公共Javadoc，无后续产品行为修改。

六个批准生成器已实际运行（脚本与输出日志 `/tmp/task57-generated-owned-second.log`）；保留本任务9份reference产物，依赖清单无差异不owned。最初formatter扩展旧匿名REQUIRED map后，旧生成器按单行put漏读8个旧端点及一个新按PO读端点；实际发现并恢复原registry大块，仅保留3行新端点。BizPerms也恢复原块，仅保留4行cash增量；未修改扫描器，受控端点现282→285，未移除旧登记。`check-generated-docs` `/tmp/task57-generated-check.log` exit1仅原有ops-role-endpoint-matrix.json和technical README漂移；该checker另写未列入snapshot的运营端角色×端点MD副产物，确认执行前无差异后逐字还原BASE，不混入owned。schema-test.sql由冻结迁移真源生成，checker无新增schema漂移。SQL portability已exit0，无新增方言依赖。

Java自查：新/改行≤120、4空格、无新增wildcard import，跨域类型采用imports；新增公开契约有用途说明。`/tmp/task57-braces-proof.json`记录94处必要braces的其它token不变；旧Recon深lambda的过度格式化随后撤回，最终仅保留OFFLINE过滤1行，旧无关分支不纳入本任务风格重写。`/tmp/task57-literal-width-proof.json`记录测试SQL/JSON字符串精确字符不变的分行。`git diff --check`通过；没有批量改原工作区/历史SQL/mapper convention/known ratchet。实际实现类名偏差见§14，均为同一冻结合同范围内的最小落地。

## 12. 最终AC与实际证据对应

| AC / 行为 | 实际最终或最近有关证据（不累计重复运行数量） |
|---|---|
| P0先持久、24h后原key、pay owner不可用后恢复；current-C会话与参数隔离 | CurrentC8最终原生；originalKeySurvivesFailureBeforePayOwnerAndReadNeverDispatches、revokedDuringAcceptedDispatchCannotReceiveParametersButOriginalFinancialFactSurvives；消融4/3失败→逐字恢复4GREEN |
| 同/异key与canonical、所有old/new open全局order guard、唯一send claim | Core13最终原生；twoPreparedRequestsConcurrentlyShareOneCanonicalAndOneSendClaim、oldOpenAndNewOpenUseOneGlobalGuardDespiteHistoricalAuditTenant；CurrentC三请求并发1实际send |
| alias先抢首send仍返回自身identity，root只持一个原号 | Core13中的aliasWinningTheFirstSendReturnsItsOwnIdentityAndCanonicalOriginalNumber及UNKNOWN/NOT_SENT两项；先有1项真实RED |
| 原号发送后失联/发送前进程死；无缓存假重启 | 最终Process2真实不同PID、同DB、当前C HTTP原key、loopback第三方原号查询；实际send1/SUCCESS与send0/SENDING |
| 真实financial receiptNo/ledger+mapping原子；UNKNOWN/notFound不关 | Core13；offline bank跨单证据unique整P2回滚；真实callback/query共用RC；false/timeout最初CloseRace真实RED→2GREEN |
| actual C与actual B account、cash独立授权、B不取params | CashPermission3原生；CurrentC8最终；Offline9实际HTTP另一经办原key拒绝/同店按PO只读/撤cash后拒重放但view原RC |
| 严格JSON整数long，不接受numeric string/小数/null/缺失/超界/额外authority | PaymentOperationContract2最终普通；Offline9严格HTTP负例，错误10400；没有向旧API扩大全局coercion变化 |
| 原全额CASH/BANK_TRANSFER、同key/异key足额不重收 | Offline9原生：authorizedCashCreatesOneRealReceiptAndReplaysOriginalKeyAndNewAlias、twoBankRequestsPreparedBeforeOpenShareOneEvidenceBindingAndRealReceipt；真实SUCCESS+RC/一个ledger/一个证据root |
| 实际店/evidence owner/真实bytes/私有用途/不可跨操作复用 | Media6原生；authoritativeEvidenceUsesUtcActualOwnerAndBytesAndNeverAcceptsCallerTransaction在消融baseline/restored重复1GREEN；dedicatedUploadUsesCurrentCashierAccountAndActualStoreAndRemainsPrivate；银行证据unique冲突20021不泄漏SQL引用 |
| 普通媒体上传不能伪造专用用途；scanner/filter/显式/旧queue/定时保留 | Media6；真实public URL读取404、preview无签名、PENDING缺bytes保留、exists实际无事务。不是银行到账真实性核验 |
| 当前商品删除/开关关闭、order先关闭后首实收仍保留真实款 | Offline9已实际cancel+delete goods+offline switch off后首笔303 SUCCESS及LATE_PAYMENT，原order仍CANCELLED；冻结原应收，不现价/现开关重拒记 |
| 当前原order/sub/item/confirmed quote一致才managed PAID | CurrentC8真实改store与改paidShare分别MANUAL_REQUIRED、保留SUCCESS+RC/RECEIPT_MISMATCH/WAIT_PAY；正常FREE/CASH/online进入PAID/FULFILLING且无旧verifyCode |
| source实际canonical PO/outTradeNo/RC、RR op-first、未知不变0 | SourceFacts9+Offline9组合38GREEN；金融读在source锁外；mapped owner不可用保留last revision/hash、money null和BLOCKED；alias去重与late源款事实 |
| 旧unmanaged线下与旧online stale-read兼容 | InitiationCashRace2 GREEN；旧offline真实前/并发/后C pay拒绝、0 chargeable ledger/send，无新cash授权或虚构旧receipt；旧online由真实SUCCESS+guard拦第二笔 |
| old recovery/外币/late/duplicate cash原财务断言 | RecoveryWiring6定点GREEN（精确outTradeNo第三方替身）；显式迁移前CLOSED/no-operation fixture保留DUPLICATE_PAYMENT与单bill，不是UNKNOWN后新号路径 |
| 零佣金/影子bill/无split/不走channel recon | Offline9真实bill commission/channelFee/splitAmount0、gross/net303与shadow waived；Core13 stale OFFLINE排除；既有OfflinePaySwitch5/OfflinePay11/OfflineSettle16普通批已通过 |
| 真实平台/pay连接图与锁外IO | Core实际CONNECTION_ID不同/DATABASE相同1原生GREEN；真实channel prepay、financial read、media exists活性观察；原始managed graph仍本地provider，无美业账本 |
| SQL NULL组合/版本冻结 | bank并发测试真实MySQL3819拒method/realm/kind/actor/account/time/evidenceRef/json NULL组合；最终V5407477及root SHA完全相同，V539和beauty1–7逐字同BASE |
| 默认H2/原生隔离、retail/food、三语/架构/生成 | 本节前述41全部skip只为隔离；普通48有4失败1skip如实列明；cash定点+embedded7/0/0/1；shared18/14pass4fail与旧label6/2pass4fail分列；生成2既有漂移 |

可复现命令统一前缀：`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test -Dsurefire.failIfNoSpecifiedTests=false`，每条`-Dtest`见§10实际批次和对应日志。最终native精确selector：`CHECKOUT_PAYMENT_TESTS='PaymentOperationNativeMysqlTest,CheckoutPaymentAuthenticationFlowTest,PaymentOperationProcessRestartNativeTest' node scripts/verify-checkout-payment.mjs`。恢复消融四项：`CheckoutPaymentAuthenticationFlowTest#originalKeySurvivesFailureBeforePayOwnerAndReadNeverDispatches+revokedDuringAcceptedDispatchCannotReceiveParametersButOriginalFinancialFactSurvives,PaymentOperationNativeMysqlTest#twoPreparedRequestsConcurrentlyShareOneCanonicalAndOneSendClaim,ReceiptEvidenceFlowTest#authoritativeEvidenceUsesUtcActualOwnerAndBytesAndNeverAcceptsCallerTransaction`。最终普通gates完整selector：`ArchitectureTest,PayTxManagerConventionTest,ConditionalBeanWiringTest,BackendI18nParityTest,ErrorCodeUniqueTest,BizRoleSeedTest,BizEndpointPermTest,PaymentLedgerCoverageTest,MediaRefCoverageTest,SchemaParityTest,FoodEmbeddedWiringTest,ZeroAmountPayFlowTest,CheckoutContractTest,PaymentOperationContractTest,PaymentOriginalNumberGatewayTest,WechatDirectPayGatewayTest`；food追加`-Pfood-embedded -Dtest=FoodEmbeddedWiringTest,BizEndpointPermTest#allCodesExist`。

Shared命令在`packages/shared`执行`node ../../node_modules/vitest/vitest.mjs run tests/checkout-cash-permission.test.ts tests/entity-alignment.test.ts tests/message-placeholder.test.ts tests/i18n-parity.test.ts`；旧标签另`... run tests/biz-perm-label.test.ts`。未运行无关全量套件、真实外部资金或部署；这些有限结果不替代Task60整BM04最终门。

## 13. 未通过gates与仍未验证边界

保留§7原Task60债。本次完整BizEndpointPerm gate新观察到的旧漏登记精确如下，root已明确暂归Task60，代价是当前全门禁仍不绿：

* `/biz/personnel/{personnelNo}/assignments` — `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/api/biz/BizPersonnelController.java:67`。
* `/biz/personnel/{personnelNo}/account-links` — 同文件:77。
* `/biz/personnel/{personnelNo}/status-changes` — 同文件:87。
* `/biz/store-sales-catalog/versions` — `backend/shop-core/src/main/java/ai/neargo/shop/product/api/biz/BizStoreSalesCatalogController.java:45`。

两Controller当前与IMPLEMENTATION_BASE逐字一致：Personnel SHA256 `f2b5b48346ef01556c9938e411cab866a5dbadb8336bd0a78db726df98559fef`；StoreSalesCatalog SHA256 `3bebd8ebec588c4d9e80f229275834bfc88ad4edade1e22a1b98768184fc3ba0`。只读`git show b34e31ba4f10bc75088ccd44d1eef4ef0c5a4ac1:backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java`逐个检查四路径均不在BASE registry；没有把它们加进PUBLIC/放宽权限来让本步全绿。

UNKNOWN保守不可用边界仍有意保留：无确定terminal unpaid owner事实时只查原号，不自动关单/新号/重签；即使发送前崩溃且第三方notFound也不能证明未发送。只有真实本地NOT_SENT可用新C key创建另一次尝试。bank receipt只是有权经办声明已实收与可信归属附件，不是平台收钱或银行确认。专用receipt媒体本期全部保留，无新清理年限。实收时间下界来自原CONSUMED confirmation UTC consumedAt，严格于同TX稍早order创建时刻；新mediaUTC截到实际DATETIME0秒，旧用途时间不改。私有bytes被运维绕owner直接删除等外部破坏不承诺可防，后续核验明确不可用。native只覆盖V538结构fixture追加冻结539/540与fresh结构，不是完整历史Flyway回放。INVENTORY v1跨DS提交、standalone真实JAR、Task58绑定/59consumer wire/60deploy/BM05均无本步交付声明。

## 14. 精确owned与不可变审查快照

实际仅Task57产品/测试/相关9份生成物，以及原设计和本报告。根owned的mainTDD/progress/brief/ADR047/两个pin记录/reviews全部排除；不stage、不commit。设计候选与实际最小偏差：新gateway解析helper为`PaymentQueryReceipt`、测试为`PaymentOriginalNumberGatewayTest`；独立process support包实际`ai.neargo.shop.scenario.support`；`TestPayGateway`本地NOT_SENT修正与`MediaUploadService`新用途UTC秒写入、Snapshot/Recovery测试真实兼容修订按§10及root裁决进入owned。既有SourceFacts/ManagedLifecycle测试无需改文件，实际复跑并保留原断言。

精确逐文件sha256/bytes及base归属：`/tmp/task57-final-owned-manifest.json`；完整只含owned的可审diff：`/tmp/task57-review.patch`。manifest在本报告写完后生成，避免自引用hash；工作树HEAD固定IMPLEMENTATION_BASE。待root独立spec与quality审查，不自我验收。

精确owned共98文件（仓库相对路径，以本文工作树为唯一根）：

```text
.superpowers/sdd/beauty-backend/task-57-design.md
.superpowers/sdd/beauty-backend/task-57-report.md
b-app/src/i18n/locale/ar.ts
b-app/src/i18n/locale/en.ts
b-app/src/i18n/locale/zh-CN.ts
backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/AlipayPayGateway.java
backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/PayGateway.java
backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/PaymentQueryReceipt.java
backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/TestPayGateway.java
backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/WechatDirectPayGateway.java
backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/WechatPayGateway.java
backend/pay/pay-channel/src/test/java/ai/neargo/shop/pay/channel/PaymentOriginalNumberGatewayTest.java
backend/pay/pay-channel/src/test/java/ai/neargo/shop/pay/channel/WechatDirectPayGatewayTest.java
backend/pay/pay-domain/src/main/java/ai/neargo/shop/media/SettleMediaRefs.java
backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/entity/StlPayment.java
backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/entity/StlPaymentOperation.java
backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/entity/StlPaymentOrderGuard.java
backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/mapper/PaymentOperationMapper.java
backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/mapper/PaymentOrderGuardMapper.java
backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/PaymentLedgerService.java
backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/PaymentOperationService.java
backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/PaymentLedgerServiceImpl.java
backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/PaymentOperationServiceImpl.java
backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/ReconServiceImpl.java
backend/shop-app/src/main/java/ai/neargo/shop/paybridge/PaymentReconReconciler.java
backend/shop-app/src/main/java/ai/neargo/shop/paybridge/port/PaymentOperationPortImpl.java
backend/shop-app/src/main/java/ai/neargo/shop/paybridge/port/SettlePortImpl.java
backend/shop-app/src/main/java/ai/neargo/shop/paybridge/service/PaymentDispatchService.java
backend/shop-app/src/main/java/ai/neargo/shop/paybridge/service/impl/PaymentDispatchServiceImpl.java
backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalCheckoutController.java
backend/shop-app/src/main/resources/db/migration/V540__checkout_payment_operation.sql
backend/shop-app/src/main/resources/i18n/messages.properties
backend/shop-app/src/main/resources/i18n/messages_ar.properties
backend/shop-app/src/main/resources/i18n/messages_en.properties
backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/CheckoutCashPermissionFlowTest.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/CheckoutPaymentAuthenticationFlowTest.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/OfflineReceiptFlowTest.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/OrderPaymentRecoveryWiringFlowTest.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/PaymentInitiationCashRaceFlowTest.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/PaymentLedgerCloseRaceFlowTest.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/PaymentOperationNativeMysqlTest.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/PaymentOperationProcessRestartNativeTest.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/PaymentRequestSnapshotTest.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/ReceiptEvidenceFlowTest.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/support/PaymentGatewayFaultServer.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/support/PaymentOperationProcessMain.java
backend/shop-app/src/test/resources/schema-test.sql
backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java
backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java
backend/shop-base/src/main/java/ai/neargo/shop/spi/media/ReceiptEvidencePort.java
backend/shop-base/src/main/java/ai/neargo/shop/spi/settle/PaymentOperationPort.java
backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/CheckoutInternalApi.java
backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/CheckoutPort.java
backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/OfflineReceiptPort.java
backend/shop-base/src/main/java/ai/neargo/shop/spi/user/MerchantOperatorAccessPort.java
backend/shop-base/src/test/java/ai/neargo/shop/spi/settle/PaymentOperationContractTest.java
backend/shop-base/src/test/java/ai/neargo/shop/spi/trade/CheckoutContractTest.java
backend/shop-channel/src/main/java/ai/neargo/shop/channel/media/api/BizUploadController.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/api/biz/BizOfflineReceiptController.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/api/mp/MpCheckoutController.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/entity/OrdCheckoutPaymentRequest.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/mapper/CheckoutMappers.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/lifecycle/PaymentLifecycleService.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/lifecycle/impl/PaymentLifecycleServiceImpl.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/policy/CheckoutAccessPolicy.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/port/CheckoutPortImpl.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/port/OfflineReceiptPortImpl.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/service/CheckoutPaymentService.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/service/CheckoutService.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/service/OfflineReceiptService.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/service/OrderService.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutPaymentServiceImpl.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OfflineReceiptServiceImpl.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java
backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/port/MerchantOperatorAccessPortImpl.java
backend/shop-store-mybatis/src/main/java/ai/neargo/shop/media/MediaPurgeService.java
backend/shop-store-mybatis/src/main/java/ai/neargo/shop/media/MediaReconcileJob.java
backend/shop-store-mybatis/src/main/java/ai/neargo/shop/media/MediaScanner.java
backend/shop-store-mybatis/src/main/java/ai/neargo/shop/media/MediaUploadService.java
backend/shop-store-mybatis/src/main/java/ai/neargo/shop/media/MediaUsageService.java
backend/shop-store-mybatis/src/main/java/ai/neargo/shop/media/SysMediaAsset.java
backend/shop-store-mybatis/src/main/java/ai/neargo/shop/media/port/ReceiptEvidencePortImpl.java
backend/shop-store-mybatis/src/main/java/ai/neargo/shop/media/service/ReceiptEvidenceService.java
backend/shop-store-mybatis/src/main/java/ai/neargo/shop/media/service/impl/ReceiptEvidenceServiceImpl.java
docs/technical/reference/B端功能点-权限码-页面.md
docs/technical/reference/B端功能矩阵-按角色.md
docs/technical/reference/glossary.json
docs/technical/reference/三端权限矩阵-按业务域.md
docs/technical/reference/中英文对照-实体与字典.md
docs/technical/reference/中英文对照-词条.md
docs/technical/reference/后端分层清单.md
docs/technical/reference/数据库表清单.md
docs/technical/reference/静态常量清单.md
packages/shared/src/mock/db.ts
packages/shared/tests/checkout-cash-permission.test.ts
scripts/verify-checkout-payment.mjs
```


## R1 独立审查修复（进行中）

FIX_BASETREE `20a4801e8f7189dc1453c4d665892c0a78209c70`，HEAD仍 `b34e31ba4f10bc75088ccd44d1eef4ef0c5a4ac1`。独立审查 SPEC FAIL / QUALITY NEEDS_FIXES：I1 原 REJECTED/CLOSED 污染后续真实成功 source；I2 guard 前 canonical 快照导致 ALIASED CHECK 失败；I3 P0/OPENED 重放首次发送缺当前订单可支付性。已逐条核源码，暂存区及原98文件 manifest/review package不改。M1 runner selector按根裁决留Task60，不在R1。

先新增 I1 currentC原 NOT_SENT→显式新key真实成功→source（并保留相反晚款），I2 PREPARED read→真实offline SUCCESS alias commit→RR observe/claim 两个确定时序测试；以下验证尚未执行，不宣称 RED/GREEN。I3 精确无锁 owner 资格检查提案已交根，尚未改产品。

R1首跑：`CHECKOUT_PAYMENT_TESTS='CheckoutPaymentAuthenticationFlowTest#definitivelyUnsentOriginalDoesNotHideNewKeySuccessOrContraryLateMoney,PaymentOperationNativeMysqlTest#preparedReadBeforeAliasCommitRecoversCanonicalReceiptUnderRepeatableRead+preparedReadBeforeAliasCommitCannotClaimOrMutateSuccessfulAlias' node scripts/verify-checkout-payment.mjs > /tmp/task57-r1-i1-i2-red.log 2>&1`。MemAvailable=3678072KiB，3run/3fail/0error/0skip。I1为真实source业务RED（receivedMinor预期303实际null），之后canonical/late断言当时masked。I2两fail是MyBatis FactoryBean spy未命中真实mapper导致barrier超时，分类fixture错误而非业务RED；改为已有source测试同型SqlSession Executor interceptor，不替换资金行为。I1最小修正只跳过身份/原号映射一致且无receiptNo的REJECTED+CLOSED，SUCCESS等相反资金不跳过。


### R1 evidence continuation

I1 first GREEN: `/tmp/task57-r1-i2-red-i1-green-second.log`, Auth selected method 1/0/0/0, including B actual canonical/RC/303 and contradictory A late SUCCESS retaining UNKNOWN. I2 in that same batch was 2/0/2/0 **fixture errors**: Java generic overload selected ReflectionTestUtils Class overload; fixed with an explicit Object target. Earlier `/tmp/task57-r1-i2-red-i1-green.log` had 0 executed tests, testCompile setup typo `gate.prepared`; neither is business RED.

Authentic I2/I3 RED: `/tmp/task57-r1-i2-i3-red.log`, 7 run / 4 assertion failures / 2 errors / 0 skips. I2 actual MySQL connection isolation=RR; real offline A SUCCESS, B PREPARED commit, observer preguard read paused, B alias commit, then observer/claim resumed: both fail with SQL3819 `ck_payment_operation_alias`. I3 actual cancel, actual expiry sweep and OPENED-before-send cancel all unexpectedly allow dispatch; the PAID-new-key case throws only after creating P0 (count expected2, actual3). Later no-send/source assertions were masked. The accepted UNKNOWN -> actual cancellation -> original SUCCESS/LATE positive passed.

Exact RED command: `CHECKOUT_PAYMENT_TESTS='CheckoutPaymentAuthenticationFlowTest#acceptedKeyCannotFirstSendAfterActualOrderCancellation+acceptedKeyCannotFirstSendAfterActualOrderExpiry+openedOriginalCannotFirstSendAfterActualOrderCancellation+acceptedUnknownStillRecoversOriginalActualMoneyAfterCancellation+paidOrderRejectsNewKeyBeforeMappingWhilePreviouslyAcceptedAliasRecoversActualReceipt,PaymentOperationNativeMysqlTest#preparedReadBeforeAliasCommitRecoversCanonicalReceiptUnderRepeatableRead+preparedReadBeforeAliasCommitCannotClaimOrMutateSuccessfulAlias' node scripts/verify-checkout-payment.mjs > /tmp/task57-r1-i2-i3-red.log 2>&1`.

I2 first GREEN: `CHECKOUT_PAYMENT_TESTS='PaymentOperationNativeMysqlTest#preparedReadBeforeAliasCommitRecoversCanonicalReceiptUnderRepeatableRead+preparedReadBeforeAliasCommitCannotClaimOrMutateSuccessfulAlias+twoPreparedRequestsConcurrentlyShareOneCanonicalAndOneSendClaim+aliasWinningTheFirstSendReturnsItsOwnIdentityAndCanonicalOriginalNumber' node scripts/verify-checkout-payment.mjs > /tmp/task57-r1-i2-first-green.log 2>&1` -> **4/0/0/0, BUILD SUCCESS**, fresh XML verified by runner. Root-approved fix owns its commit/rollback boundary and forbids caller TX for observe/claim. Guard -> known request/root unique operation keys sorted -> current relationship comparison; only a changed canonical relationship triggers full rollback and one bounded retry. No ledger access before comparison; no blanket SQL/deadlock catch. Current ledger FOR UPDATE follows operation locks and claim reuses it. Observation DTO is read after commit in fresh autocommit calls, avoiding internal `view` reads of an old RR root/payment snapshot. `open` also uses its actual locked request and returns the committed view. No whole-order/table scan lock, extra connection or schema change. The test temporarily changes the actual owner's own TransactionTemplate to RR and restores original RC in finally. Additional lock-sequence/2-attempt assertions and caller-TX negative now added, pending final verification.

I3 root-approved authorization point: current ordinary-owner read succeeds before first send, with no caller TX/locks; later cancellation may legitimately produce LATE_PAYMENT. New-key instruction and P0 acceptance require WAIT_PAY/unexpired. Existing PREPARED/OPENED checks current original consumed quote/order/sub/items, without resolving route/current prices. Existing PAID is accepted only after a real readConfirmed SUCCESS matches original order/buyer/amount/currency/channel/tradeNo; pure checks do not call resolution-writing requireConfirmed. Frozen exact instruction + real SUCCESS pay guard permit only alias binding. Started/uncertain operations retain original-number recovery. A failed current eligibility check only queries the original operation; it never dispatches in that branch. Actual dispatch path remains `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/service/impl/PaymentDispatchServiceImpl.java` (unchanged in R1).

One attempted report append failed before writing due shell stdin encoding; retried with ASCII text. This is documentation setup, not test/business evidence.

### R1 final verification and review handoff

I3 first GREEN exact command: `CHECKOUT_PAYMENT_TESTS='CheckoutPaymentAuthenticationFlowTest#acceptedKeyCannotFirstSendAfterActualOrderCancellation+acceptedKeyCannotFirstSendAfterActualOrderExpiry+openedOriginalCannotFirstSendAfterActualOrderCancellation+acceptedUnknownStillRecoversOriginalActualMoneyAfterCancellation+paidOrderRejectsNewKeyBeforeMappingWhilePreviouslyAcceptedAliasRecoversActualReceipt' node scripts/verify-checkout-payment.mjs > /tmp/task57-r1-i3-first-green.log 2>&1` -> **5/0/0/0, BUILD SUCCESS**, fresh XML verified. The later unswept-deadline/unbacked-PAID and caller-TX negatives were added after the minimal fix and first executed GREEN; no retrospective RED is claimed for these extra boundaries.

Final native command (one owned container, serial, no external funds):

```sh
CHECKOUT_PAYMENT_TESTS='CheckoutPaymentAuthenticationFlowTest,PaymentOperationNativeMysqlTest#preparedReadBeforeAliasCommitRecoversCanonicalReceiptUnderRepeatableRead+preparedReadBeforeAliasCommitCannotClaimOrMutateSuccessfulAlias+canonicalOperationsRejectCallerTransactionsBeforeTakingLocks+aliasWinningTheFirstSendReturnsItsOwnIdentityAndCanonicalOriginalNumber+aliasWinningUncertainDispatchKeepsItsIdentityAndOriginalPendingPayment+aliasWinningProvenUnsentDispatchKeepsItsIdentityAndRejectedOriginal+twoPreparedRequestsConcurrentlyShareOneCanonicalAndOneSendClaim+oldOpenAndNewOpenUseOneGlobalGuardDespiteHistoricalAuditTenant+sendingAndUnknownNotFoundCannotBeClosedByLegacyCloseOrReconciliation+provenNotSentKeepsOriginalRejectedMappingAndPermitsOnlyNewOperationToAllocate,OfflineReceiptFlowTest#sourceExposesOneCanonicalOriginalReceiptAndCallsFinancialOwnerOnlyOutsideSnapshot+firstAlreadyReceivedCashAfterActualCancellationAndPolicyChangePreservesReceiptAndLateResolution+mappedOwnerUnavailablePreservesLastVerifiedRevisionAndHashWithoutInventingZeroMoney,CheckoutSourceFactsFlowTest#unknownKeepsLastVerifiedPairAndReturnsNoConsumableLines+refundBetweenFactReadsAndSerializedObservationNeverMixesSnapshots' node scripts/verify-checkout-payment.mjs > /tmp/task57-r1-final-native.log 2>&1
```

**31 run / 0 failures / 0 errors / 0 skips, BUILD SUCCESS**. Four fresh XML suites independently read after runner validation: PaymentOperationNativeMysqlTest10, CheckoutPaymentAuthenticationFlowTest16, OfflineReceiptFlowTest3, CheckoutSourceFactsFlowTest2. This is the finite amended/canonical/current-C/late-source selection, not all backend/native coverage.

| Finding | Actual R1 correction and final evidence |
|---|---|
| I1 | `CheckoutServiceImpl.sourceFunding` skips only REJECTED with no receipt, or a matching mapped CLOSED receipt with no receiptNo. Actual NOT_SENT A -> explicit currentC B -> real SUCCESS now yields B canonical/outTradeNo/RC and historical303, PAID order, both permanent keys retained. A contrary late real ledger SUCCESS is preserved, source remains UNKNOWN/BLOCKED, original B trade remains, and DUPLICATE_PAYMENT is retained. Relevant original owner-unavailable/late source positives also pass. |
| I2 | `PaymentOperationServiceImpl` performs bounded full-transaction retry for a changed canonical pointer, retaining global order guard -> unique operation keys in lexical order -> current ledger FOR UPDATE. The two real RR barriers now recover the same receipt and leave B ALIASED/A SUCCEEDED. Tests assert exactly two observer guard acquisitions and first-request then sorted request/root lock calls; caller TX is rejected before claims/observations. Original alias identity, uncertain/proven-unsent, single-claim and historical audit-tenant global guard positives pass. |
| I3 | `CheckoutPaymentServiceImpl` checks real current ordinary eligibility for PREPARED/OPENED before first-send authorization. `OrderServiceImpl` uses original consumed facts and current status/CheckoutClock deadline, with no caller TX, reprice or route resolution. New keys require WAIT_PAY at instruction and locked P0 acceptance. Existing PAID recovery requires exact real SUCCESS proof; actual accepted B -> A success -> B alias/source passes. Actual cancel/expiry/OPENED cancel preserve original key and prevent any first send; unswept expiry and a PAID flag without receipt also fail closed. Already accepted UNKNOWN after cancellation recovers real original money and LATE_PAYMENT without resending. Original current-C revocation, scope mismatch, FREE receipt, concurrency and HTTP auth cases pass. |

Ordinary compatibility exact command:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test '-Dtest=PaymentRequestSnapshotTest,ZeroAmountPayFlowTest,PaymentLedgerCloseRaceFlowTest,PaymentInitiationCashRaceFlowTest,PayTxManagerConventionTest' -Dsurefire.failIfNoSpecifiedTests=false > /tmp/task57-r1-ordinary-compat.log 2>&1
```

**14 run / 0 failures / 0 errors / 0 skips, BUILD SUCCESS**: snapshots5, zero4, close-race2, actual legacy cash/init boundary2, pay-TX convention1. Only these directly affected shared-owner checks were rerun. No full suites, financial process-restart rerun, old baseline cleanup, new ablation or external channel calls were performed in R1. Initial independent-process/ablation evidence remains historical evidence for the initial snapshot, not a claim that it was rerun against R1.

Self-review: changed Java imports/braces/public interface Javadoc and changed sections formatted; `git diff --check` exit0; 0 newly added Java lines exceed120. OrderServiceImpl's 17 pre-existing long lines remain unchanged outside R1. No new domain/endpoint/table/permission/translation/configuration/runner or media scope. Current I/O boundaries remain: trade's current owner eligibility and any receipt read are outside caller TX/locks; payment query projection still runs only after the owner transaction. The first-send authorization point is the successful current owner read, so cancellation after that point can still create a legitimate late-payment case. UNKNOWN is neither closed nor allocated a replacement. R1 retry accepts only canonical-binding change and does not retry arbitrary SQL errors/deadlocks; an unexpected second change raises CONFLICT.

Resource evidence: all R1 native runs use the approved runner's local cached MySQL9.7.2, disk64M, >=512MiB preflight, unique own UUID, finally cleanup. Final `docker --context colima-hxkey-tests ps -a --filter 'name=codex-checkout-payment-' --format '{{.Names}}'` returned empty. No independent child process was spawned in R1. No other container/VM/shared/remote database was changed.

Immutable handoff: exactly9 original-owned paths changed; 89/98 original-owned file hashes remain byte-identical. No new test paths. Initial `/tmp/task57-final-owned-manifest.json` and `/tmp/task57-review.patch` are retained unchanged. New `/tmp/task57-r1-owned-manifest.json` contains all98 before/current SHA256/bytes, exact changed paths, test/XML/log evidence and frozen boundaries; `/tmp/task57-r1-fix.diff` is only these9 paths against FIX_BASETREE. Index still equals `20a4801e8f7189dc1453c4d665892c0a78209c70`, HEAD still `b34e31ba4f10bc75088ccd44d1eef4ef0c5a4ac1`; worker did not stage/reset/commit/push. Root-owned docs/reviews/pins remain excluded. Design, V540, V539 and beautyV1-V7 remain byte-identical to their approved snapshots. Independent scoped re-review is still required; this is READY, not self-acceptance.

R1 changed paths:

```text
.superpowers/sdd/beauty-backend/task-57-report.md
backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/PaymentOperationService.java
backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/PaymentOperationServiceImpl.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/CheckoutPaymentAuthenticationFlowTest.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/PaymentOperationNativeMysqlTest.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/service/OrderService.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutPaymentServiceImpl.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java
backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java
```

Known/unverified boundaries remain those in the initial report. M1 runner-selector validation is explicitly deferred by root to Task60; this does not invalidate these known selectors with fresh nonzero/zero-skip reports. Prior architecture/permissions/booking/i18n/generated baseline debt is not claimed cleared or rerun in R1. No Task58/59/60 deployment/BM05 work entered this fix round.


## R2 remaining I3: real PAID proof must not authorize another unsent attempt

FIX_BASETREE `d4201a0b576d40985a5908e45a7a31ac18ff8bfa`, HEAD `b34e31ba4f10bc75088ccd44d1eef4ef0c5a4ac1`; root's staged98 unchanged. Read `/tmp/task57-r2-findings.md` verbatim and inspected actual payment open/claim, late ledger settlement, current owner checker and frozen schema. I1/I2 are independently ADDRESSED; only remaining I3 and interaction regressions enter R2. Initial and R1 manifests/diff packages remain immutable. No schema/runner/M1/root-doc changes.

Actual gap confirmed in source: A rejected/CLOSED can settle actual late SUCCESS while B already has a different OPENED/PENDING original. PAID receipt A is real, but R1 checker returns to ordinary B initiate; B claim checks only its own PENDING row. PREPARED B can likewise alias a newer PENDING C selected by currentPayment. Proposed minimum pay-owner guard invariant: before the first SENDING/ticket, current locking read of any existing PAY SUCCESS for the same global order blocks that other unsent root with existing CONFLICT; no close, purge, replacement, new successful claim or fake rebinding. Already-started/uncertain recovery remains untouched. Two real-owner/current-C native regressions added first, not yet executed; no RED/GREEN claimed yet.


R2 authentic RED command: `CHECKOUT_PAYMENT_TESTS='CheckoutPaymentAuthenticationFlowTest#lateSuccessfulOriginalBlocksOpenedOtherAttemptBeforeFirstSend+lateSuccessfulOriginalBlocksPreparedAliasOfNewerPendingAttempt' node scripts/verify-checkout-payment.mjs > /tmp/task57-r2-first-red.log 2>&1`. MemAvailable=3690680KiB; **2 run / 2 assertion failures / 0 errors / 0 skips**. Both actual A SUCCESS/normal PAID projection prerequisites pass. Each B replay unexpectedly returns and actually prepays the newer `-2` original, rather than throwing BizCONFLICT. Subsequent no-claim/no-send/identity/fund assertions were masked; no setup/compile failure occurred in this round.

Root-approved minimum correction now written in `PaymentOperationServiceImpl.claimSend`: after global order guard and lexical operation locks, reuse the current locked canonical payment; only OPENED/unsent/ONLINE/PENDING reaches an indexed current `PAY SUCCESS ... LIMIT 1 FOR UPDATE` read. Any existing received payment for the same global order throws the existing CONFLICT before SENDING/send_started_at/save/ticket. All query/claim SQL stays on the same pay connection, no owner/RPC calls, new connection, refund direction or new schema. The existing order_no/direction index is `idx_stl_payment_order` in actual baseline V1; the read is current under RR, not snapshot COUNT. The already-held global guard serializes this check with late ledger.settle before first claim commit. No phase/error/receipt/mapping update occurs on refusal. Existing OPENED B is retained; PREPARED B may retain its original open choice as an alias of pending C, but is explicitly blocked and never falsely succeeds/rebinds to A. Original A's real receipt remains recoverable. A sole winning SUCCESS still binds a happy PREPARED alias and never reaches a PENDING first claim. Started/UNKNOWN recovery never reaches this first-send check.

### R2 final verification and scoped handoff

R2 first/final native GREEN exact command:

```sh
CHECKOUT_PAYMENT_TESTS='CheckoutPaymentAuthenticationFlowTest#lateSuccessfulOriginalBlocksOpenedOtherAttemptBeforeFirstSend+lateSuccessfulOriginalBlocksPreparedAliasOfNewerPendingAttempt+paidOrderRejectsNewKeyBeforeMappingWhilePreviouslyAcceptedAliasRecoversActualReceipt+acceptedUnknownStillRecoversOriginalActualMoneyAfterCancellation+revokedDuringAcceptedDispatchCannotReceiveParametersButOriginalFinancialFactSurvives+definitivelyUnsentOriginalDoesNotHideNewKeySuccessOrContraryLateMoney,PaymentOperationNativeMysqlTest#aliasWinningTheFirstSendReturnsItsOwnIdentityAndCanonicalOriginalNumber+twoPreparedRequestsConcurrentlyShareOneCanonicalAndOneSendClaim+sendingAndUnknownNotFoundCannotBeClosedByLegacyCloseOrReconciliation+provenNotSentKeepsOriginalRejectedMappingAndPermitsOnlyNewOperationToAllocate+successfulReceiptPreventsBothOldAndNewOpenFromCreatingAnotherChargeableAttempt+preparedReadBeforeAliasCommitCannotClaimOrMutateSuccessfulAlias,OfflineReceiptFlowTest#sourceExposesOneCanonicalOriginalReceiptAndCallsFinancialOwnerOnlyOutsideSnapshot+firstAlreadyReceivedCashAfterActualCancellationAndPolicyChangePreservesReceiptAndLateResolution' node scripts/verify-checkout-payment.mjs > /tmp/task57-r2-scoped-green.log 2>&1
```

**14 run / 0 failures / 0 errors / 0 skips, BUILD SUCCESS, exit0.** MemAvailable=3677252KiB. Runner independently validated fresh nonzero XML for Core6, Offline2, Auth6; those XML files were also parsed for this handoff. There was no interim R2 setup/compile failure or failed GREEN attempt: the two new tests first failed on genuine behavior before the 13-line product fix, then passed with the finite interaction set.

| R2 focused evidence | Actual assertion scope |
|---|---|
| `CheckoutPaymentAuthenticationFlowTest.lateSuccessfulOriginalBlocksOpenedOtherAttemptBeforeFirstSend` | Actual current-C A NOT_SENT/CLOSED, actual B P0/P1 OPENED/PENDING without send, actual late A ledger SUCCESS and normal PAID projection. B public replay throws exact CONFLICT; direct owner first claim also fails; no B prepay or send timestamp, no replacement P0/P1/payment. B remains its original OPENED identity/PENDING ledger with no receipt/params. A remains actual SUCCESS/303/same RC/canonical/original trade; order PAID trade is not overwritten. |
| `CheckoutPaymentAuthenticationFlowTest.lateSuccessfulOriginalBlocksPreparedAliasOfNewerPendingAttempt` | B remains PREPARED while actual C P0/P1 creates the newer PENDING original. After actual late A SUCCESS/PAID, replay opens B as its exact existing C alias, then refuses first send. B stays ALIASED with that original identity, C pending/unsent, all three P0/P1 mappings and only two ledger rows retained. A real RC/canonical/money is unchanged. No fake B SUCCESS, no rebinding to A, no second ticket/prepay. |
| Relevant original Auth4/Core6/Offline2 positives | Sole winning SUCCESS still permits previously accepted PREPARED alias and actual source receipt; new PAID key creates no P0. Accepted UNKNOWN after cancellation still queries actual original money/LATE_PAYMENT. Current-C revocation still denies parameter delivery. I1 NOT_SENT/new-key success source and contrary late-money UNKNOWN remain. Alias winning claim preserves response identity, concurrent aliases produce one claim, original SENDING/UNKNOWN notFound cannot close, proven NOT_SENT alone permits a new attempt, SUCCESS blocks old/new open, and real RR stale alias claim cannot mutate its SUCCESS. Offline source uses actual canonical receipt outside snapshot locks; actual cancelled/policy-changed first cash receipt remains LATE_PAYMENT. |

Only the two new methods and their private helper were added to the existing Auth test; no new test files. The authentic first RED stopped at B's missing exception, so the later retention/no-send assertions are established by this GREEN, not retrospectively counted as pre-fix failures.

Direct shared legacy/H2 compatibility exact command:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test '-Dtest=PaymentLedgerCloseRaceFlowTest,PaymentInitiationCashRaceFlowTest' -Dsurefire.failIfNoSpecifiedTests=false > /tmp/task57-r2-ordinary-compat.log 2>&1
```

**4 run / 0 failures / 0 errors / 0 skips, BUILD SUCCESS, exit0**: PaymentLedgerCloseRaceFlowTest2 and PaymentInitiationCashRaceFlowTest2, fresh XML checked. This checks the affected shared claim's default database syntax and legacy close/init/cash boundaries. No full suite, process restart, broad generated/architecture/food gate or new ablation was rerun in R2. Initial/R1 evidence remains dated evidence for those snapshots; no new claim that deferred gates are green.

Self-review and preserved boundaries: the only product delta is 13 lines in `PaymentOperationServiceImpl.claimSend`. Guard -> unique sorted operation locks -> own current PAY -> current same-order successful PAY read stays in one pay transaction and connection. The global order guard already serializes every legitimate ledger settlement, so the new read and SENDING commit cannot cross a competing success write. It adds no operation lock after ledger access, no refund lock, tenant split, RPC, gateway I/O or extra connection. No DDL/DTO/owner eligibility/route/price/public purchase API change. R1 canonical retry/current-read mechanism and I1 source filtering are unchanged. The current trade-owner read remains the first authorization point; a later cancellation can legitimately yield late money. Started/UNKNOWN operations remain queryable and never enter this new first-claim refusal. A late receipt racing after an already committed claim can still require the existing late/duplicate resolution; this fix prevents a first claim only when real SUCCESS already exists under the shared guard.

Availability limit is explicit: a distinct original B that is still PENDING is left blocked with CONFLICT on initiation, readable as its original OPENED identity without parameters. A PREPARED B may remain bound to newer pending C after open commits; it is not fraudulently rebound to A. No automatic close/purge is introduced to make source appear settled, so unresolved competing pending facts may retain conservative source UNKNOWN. A's actual receipt/canonical/money and all accepted keys remain permanent. This is the root-approved refusal alternative, not a manufactured B success.

Resource evidence: both R2 native runs use the cached local MySQL9.7.2, disk64M, one owned UUID, >=512MiB preflight and runner finally cleanup. After the GREEN runner, `docker --context colima-hxkey-tests ps -a --filter 'name=codex-checkout-payment-' --format '{{.Names}}'` returned empty. No independent child JVM was spawned in R2; no other container/VM/shared/remote database or real external funds were touched.

Snapshot boundary: exactly3 original-owned paths changed relative to R1 FIX_BASETREE; 95/98 R1-owned file hashes remain identical. No new paths. Initial and R1 manifests/patches remain byte-identical. `/tmp/task57-r2-owned-manifest.json` records all98 current and R1/initial hashes/bytes, exact delta, fresh XML/methods, logs and frozen boundaries; `/tmp/task57-r2-fix.diff` contains only these3 paths against `d4201a0b576d40985a5908e45a7a31ac18ff8bfa`. `git diff --check` passes, new Java lines exceed120 count0. Design, V539, V540 and beautyV1-V7 stay byte-identical; no migration execution changed schema. Index still equals FIX_BASETREE and HEAD remains b34; worker has not staged/reset/committed/pushed. Root docs, ADR, review and pin files are excluded. M1 runner-selector validation remains explicitly deferred to Task60; prior baseline debt remains. This handoff is READY for scoped I3/new-R2-breakage re-review, not independent acceptance.

R2 exact changed paths:

```text
.superpowers/sdd/beauty-backend/task-57-report.md
backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/PaymentOperationServiceImpl.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/CheckoutPaymentAuthenticationFlowTest.java
```
