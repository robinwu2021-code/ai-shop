# Task60A-A2 付款原生测试runner的选择与资源边界

Status: IMPLEMENTATION_APPROVED after accepted A1 product94aa791edb445b56bec9bcd348724ecff4ad803a. Actual IMPLEMENTATION_BASE is next root docs commit supplied at dispatch. 仅A2；A3～A11继续候选。档位0，无营业接口/字段/权限/config key变化。

读本brief first，再读task-60a-design.md §1/2/A2和task-60a-report.md对应实际旧源码。根technical ruling以本brief为准：保留已支持合法+多方法语法、精确方法/新鲜XML、秘密只经env，增加与现有Task59同一串行锁及lost-create-response自有清理。不要运行尚未修好的旧正常native路径。

唯一工作树/分支/离线工具规则沿A1：/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex，codex/beauty-foundation，JDK21=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home，Maven -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2。只owned scripts/verify-checkout-payment.mjs 与新增 scripts/tests/checkout-payment-runner.test.mjs；允许task-60a-a2-report.md及task-60a-a2-evidence目录。不改原Task59runner、Java/SQL/POM/known/根文档/其它60A片，不派子agent。

## 精确选择合同

新增可单独测试的纯validateSelection(mode,selected)，返回{mode,selectors,classes,requestedMethods}；mode仅payment/beauty-source。selected===undefined才使用原默认，null/非字符串、显式空/全空白/带无关空格均拒绝。默认payment=PaymentOperationNativeMysqlTest，beauty-source=BeautySourceCheckoutIT。CLI只允许原--beauty-source单次或无参数，未知/重复flag拒绝。main(argv,env,effects)仅直接CLI入口调用，导入测试不触发native。effects只做必要Node边界隔离，不建通用框架。

payment原14类原名保留：PaymentOperationNativeMysqlTest, PaymentOperationProcessRestartNativeTest, OfflineReceiptFlowTest, CheckoutPaymentAuthenticationFlowTest, CheckoutCashPermissionFlowTest, ReceiptEvidenceFlowTest, CheckoutNativeMysqlTest, CheckoutRestartNativeTest, CheckoutPermissionFlowTest, CheckoutPricingFlowTest, CheckoutHttpFlowTest, CheckoutManagedLifecycleFlowTest, CheckoutOwnerBoundaryFlowTest, CheckoutSourceFactsFlowTest。beauty-source此片仅BeautySourceCheckoutIT。

每类允许的方法为该现有scenario Java文件的真实@Test/ParameterizedTest等可选Java方法名，显式有限名单，先只读核实来源，不在运行时读Java来决定可选能力。/tmp/task60a-a2-source-method-inventory.json仅15文件/131候选的词法准备及SHA，不是权威AST/固定总数；必须逐项验证，不能盲录非测试helper/注释。支持实际Unicode方法名如存在，不把ASCII regex当权限。没有新业务测试方法或Java修改。

beauty-source只一个类selector：整类、Class#method、Class#method1+method2；不接受逗号或其它类。payment允许上述形式及逗号多类；同类逗号项可选互不重复方法。每个+分量/逗号方法均需显式合法、实际XML出现。拒绝未知类/方法、wildcard/其它未定义Maven模式、空项/空#、前后或连续+、重复方法（含跨逗号）、重复整类、同类整类和方法混用。合法原字符串原样传Maven，不能为strict删除已支持+。选择验证在文件read、UUID/秘密、工具探测、锁、memory、Docker之前，非法选择不得触发任何上述effect。

## XML、秘密与自有资源

inspectReport(xml,requested)为纯覆盖检查，requested能标识预期class及每个显式method。每个被选类都要本轮删除旧XML并记录开始水位，读回存在、mtime新鲜、suite/测试所属class对应、tests>0且failures/errors/skipped全部0，每个显式方法匹配真实testcase（正确处理JUnit参数化名称边界，不用宽泛startsWith吞前缀）。非零/超时/信号Maven退出不得成功；缺/错类/stale/零项/skip/漏方法都失败。整类选择保留原语义，不伪造只测一项当全类结果。

复用现有verify-beauty-bm04-wire.mjs env-secret、owned-before-run与锁模式（只读参考不编辑）：MYSQL_ROOT_PASSWORD/MYSQL_PWD由子进程env传值，Docker argv仅--env加变量名，SQL经stdin。密码或其它秘密不入argv、日志/报告；保留安全脱敏错误。迁移V5398444bytes/SHA5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b、V5407477bytes/SHAca76b6512cf79c6f25fa71a4441f533e03cc609c6320611a0dcdc9634a10f745、beauty-source V84582bytes/SHA c5b54d574624a5265366cf4bf7897ec949d107bd302f171539c461a89a9d8343既有pin不改。

明确同一串行锁目录/tmp/codex-beauty-bm04-native.lock，选择合法后才mkdir；已存在就失败且不清别人锁，只清本次成功获取锁。Docker context colima-hxkey-tests / VM profile hxkey-tests，cached mysql:9.7.2 --pull never，正常DISK64M、MemAvailable>=524288KiB、自有UUID名和tmp、random127.0.0.1、finally只清自己。保留env LIMA_HOME既有正确路径；不改VM/停止其他容器/访问远程或共享库。

旧脚本在docker run成功返回后才owned=true，丢创建响应会跳过清理。必须在发起自己的UUID创建前记录attempt，并在抛错/超时后仍清理该UUID；确认未创建/已自动删除与cleanup真正失败分开记录，不能把未知资源残留写成功。保留V538结构夹具+正式539/540（beauty-source另实际beauty迁移）的准确边界，不声称全历史迁移重放。

## TDD与有限验证

先写安全CLI负例，以空可执行PATH/受控effects确保旧密码argv路径不可能实际调用Docker。真实RED应为非法选择未在effect前拒绝/覆盖漏验等断言，不用缺export/编译/setup冒充。再最小实现纯函数/CLI导入保护/env/资源生命周期。

scripts/tests/checkout-payment-runner.test.mjs至少包含：rejectsInvalidSelectionBeforeAnyEffect、usesDefaultsOnlyWhenVariableIsAbsent、keepsSecretsOutOfAllChildArguments、rejectsStaleZeroSkippedAndMissingMethodReports（含错class）、acceptsEachKnownClassAndExplicitMethod、preservesPlusSelectionsAndChecksEveryRequestedMethod、acceptsPaymentMultiClassAndDisjointSameClassSelections、cleansOnlyItsOwnUuidAfterCreateResponseLoss、neverRemovesAnotherRunsSerialLock。测试结果保存真实数，不固定总数。node --test scripts/tests/checkout-payment-runner.test.mjs先RED/GREEN；最小撤显式空值判断或XML skip/漏方法防线，负例应红，逐字恢复GREEN，不重复其它未变业务套件。

仅当脚本安全unit全绿后，执行一个实际隔离native smoke：CHECKOUT_PAYMENT_TESTS=BeautySourceCheckoutIT#realGuestClaimThenCurrentConsumerPurchaseFeedsBindingAndBill node scripts/verify-checkout-payment.mjs --beauty-source。方法在当前Java:167已只读确认。它证明新runner真实启动/非零零skipXML/清理，不新增业务实现、不扩跑14类/完整owner suite。保存fresh XML与SHA、移除资源/锁的证据。必要隔离native不等同后置双JVM/JAR部署；不启动正式部署。

## 交付

完整报告task-60a-a2-report.md：实际RED/GREEN/消融逐字恢复、argv不含秘密的可复核证据（不能把env值打印出来）、有限方法表来源核实、命令/退出码/XML/SHA、native归属与cleanup、范围和偏差。给两文件owned manifest及含新增未追踪测试文件的完整diff/hash。所有旧迁移冻结，report不称全部60A/BM04/BM05完成。不stage/commit/push/merge；根独立spec+quality审查后精确提交。只返回状态、报告/manifest/hash、测试一句话与关注点。
