# Task55C 工单范围实施验证报告

> Current evidence/status: see the final Fix round3 section. Earlier INCOMPLETE/waitingENV and compile-only statements are historical snapshots, preserved verbatim. No Task55C product commit or final acceptance yet.

状态：**INCOMPLETE**。按控制器条件裁决停止新增原生容器；本地实现与已通过证据已整理，C未完成、无C产品commit。两个同key竞态生产修复尚未取得专属RED，仍未实施。以下结果不代表C验收通过。

BASE `f1d5daf809bbc481c71402fef172e37c012ae7d6`；工作树beauty-data，branch codex/beauty-foundation。前实现者容量中断后保留全部修改接续，未重做设计或伪造首RED。C仅052/054/055/155本地Service；Remote052保留明确fail-closed default，HTTP/remote/walk-in/执行/交易/支付/Job不在本步。V1–V7冻结，不造V8。

首业务RED为 `/tmp/task55c-red1b.log`：真实预约CONFIRMED→CHECKED_IN后调用本地052期望OPEN，触发既定未覆盖default，1test/1assertionfailure/0errors。`red1.log`缺CustomerIdentityVO的编译错误不计RED。`/tmp/task55c-green1.log`同首用例1/0/0/0。

诚实历史边界：查询SQL/Query初稿在专属前置RED前实现，没有逐方法前置RED证据；后续有效父链消融是实际回归敏感性证明，不倒填为前置TDD。unassigned真实政策hash校验 `red2.log`、无预约null指纹 `red3.log`、Outbox回滚后真实发布新27分钟/新buffer/notes而原key应保持原17分钟范围 `red4.log` 均各1assertionfailure/0errors；`green3.log`三条3/0/0/0。

`batch4.log` SQL父链/receipt/回滚、`batch5.log`不同key并发/完整请求hash+当前权限/CHECKED_IN+live计划、`batch6.log`原ACCEPT UNKNOWN重启/开单禁重排/qty与同resource capacity份额均各3/0/0/0。新增多定义展开用例在`recovery1.log`真实PASS；后续新增测试断言尚待最终记录，不从旧批次推断。

SQL父链有效消融：仅ServiceStepMapper.selectForTask临时移除`AND t.entity_no=c.entity_no`和`AND c.entity_no=#{entityNo}`两个实际冗余防线，正常真实step REAL及跨entity同taskNo的POISON均被返回，断言实际[REAL,POISON]期望[REAL]，`ablation-red.log`1test/1assertionfailure/0errors。finally恢复原SQL，`ablation-green.log`1/0/0/0。未重复消融。

`host1.log` BeautyReceptionIdentityIT.workOrderOpeningUsesRealCustomerAndGuestFacts真实宿主1/0/0/0：真cr1/guest owner消费、伪cr1与旧BTG拒、成功原key过期cr1重放、新动作过期拒、当前撤权拒、GUEST范围仅本域不可变claim receipt授权、CLAIMED→USER规范化。目录/资源在core受控fixture，host只有资源准入为显式fixture；真实owner跨进程部署仍Task60。

普通beauty reactor第一轮`reactor.log`因既有BookingServerWiringTest未补6个新真实mapper夹具2失败，不计业务RED；补夹具与真实WorkOrderService单Bean断言后`reactor2.log`全部reactor SUCCESS。BeautyArchitectureTest13/0/0/0。host-guards.log：BackendI18nParityTest6/0/0/0；BeautyEmbeddedWiringTest8/0/0/2skip；ArchitectureTest16/2fail/0error/0skip。两失败准确为旧OpsPointsInspectionController FQN及topLevelPackagesMustBeRegistered [beauty,store]：beauty是本分支BM01业务域登记遗漏，在-Pbeauty-embedded首次显现，store是OURBM03跟踪遗漏；Task60前beauty须加入业务域、store按既定迁job.config，C不加infra豁免。旧V506 parser本轮未跑，不称已验证。不是全backend绿。

生成check：Java方法171 DTO/47自定义mapper方法、API20模块118功能、数据库41设计/10生成物均一致；SQL方言扫描162处/51文件，无新增依赖。未增加ErrorCode/i18n词条。

环境失败：final7/final8在readiness未达业务断言，不计RED/GREEN。只针对恢复UUID10da35a8-dbf2-4391-872f-23777787d299的events保存在`/tmp/task55c-own-container-events.log`：03:44:59Z create/start，03:45:45Z readiness=true，03:47:26Z die(exit137,duration147s)/destroy，无kill/oom事件，原因未证实。Maven03:47:27Z结束，故容器在Maven仍跑时消失。recovery1.log实际3test/0fail/2error/0skip：unassignedExpandsMultipleDefinitionsAndEnforcesBounds PASS14.366s；UNKNOWN ACCEPT在第二schema BeautySchemaReady SQLState08S01错误；EXECUTE建库Communications错误。未把它们计通过。

环境适配：控制器裁决每个新容器只跑一个UNKNOWN窗口case，最多两次（ACCEPT/EXECUTE各一）；两个@Test复用private runReplacementUnknown，不复制逻辑。相同cached mysql8.4/磁盘64MB/关闭performance_schema/UUID/pull-never/localTCP/仅本UUID日志events与finally；其他pricing/food容器未触碰。任一再环境失败即停止开新容器。

命令、最新case统计、最终文件清单及commit：待后续真实结果补齐。

## 尚待实际运行的既定AC（本段随真实结果更新）

- concurrentSameKeyReturnsOneFrozenResult：同key两连接并发唯一原结果。
- requiresCheckedInAndLiveOriginalArrangement新增active fence/current断言：旧CHECKED_IN/过期/缺fence已batch5通过，新断言未执行。
- replacementRejectedKeepsOriginalHoldAndCannotOpenOnReplay：C外层确定拒绝及原键不可回退开单。
- unassignedPreparationRejectsForeignInactiveAndChangedHeads新增目录owner跨实体/INACTIVE返回断言。
- scopedReadsRejectDeletedAndForeignParents新增错误appointment-item父链与错误工单参数断言；既有删除/跨entity污染已batch4与消融通过。
- 原op父链scopedReadRequiresOriginalSucceededOperation已op-parent-red.log真实1assertionfailure/0errors（错op.workOrderNo未拒）；正在修复后GREEN。
- 新Query原op校验后legacy/receipt相关单case重验；适配fixture使原op/context/root身份三者一致。
- B单一抽取后定向重排冻结规则/容量份额/拒绝/UNKNOWN回归，不扩大无关全量backend。

两UNKNOWN隔离最终结果：unknown-accept-single.log和unknown-execute-single.log各1/0/0/0。EXECUTE93.80s，03:55:07Z Maven结束，03:55:08.008Z finallybegin，03:55:12.214Z finallyend；events03:55:12Z的signal9/die137/destroy与本runner正常清理对齐，不计环境异常。ACCEPT28.41s，同样为Maven结束后的自有finally清理。原recovery1的3test/1pass/2error历史仍保留。

## 后续精确单case结果与环境取证

op-parent-red.log为1test/1assertionfailure/0errors：把原op.workOrderNo改FOREIGN后仍能读取，真实暴露常量成功问题。补精确SQL原op/context/root/canonical identity及phase父链后op-parent-green2.log1/0/0/0；中间op-parent-green.log因Bean签名漏新mapper参数编译失败，op-compile.log修正编译SUCCESS，该轮不计业务RED。

same-key.log、active-pointers.log、replace-rejected.log、catalog-scope.log各1/0/0/0。parent-full.log新UUID754ceab4启动仅3秒即die137/destroy，readinessfalse、未进Maven；队列自动停，legacy-final/B回归未运行。

此前仅events无法确定退出原因，现追加只读内核证据已明确其中两次：kernel-own-termination.log的global_oom/task_memcg精确匹配recovery1容器IDc1b5e5...和parent-full容器ID2a2a19...，Killed mysqld RSS分别233892kB/168204kB。此后才确认这两次是测试VM全局OOM；不把所有旧EOF/失败笼统归为OOM。Docker MemTotal4096294912字节(3.815GiB)，匿名13容器内存使用约3.2GiB；VM MemAvailable192208kB、Swap0。只读聚合docker info/stats及本UUID内核metadata，无读取其他容器业务日志/数据，无停止或修改其他容器。四个常见已安装MySQL8.4二进制路径均不存在，未启动/安装其他数据库。

新发现既定同key成功竞态测试successfulConcurrentReplaySkipsOldRevisionAndDoesNotRegressPhase与successfulReplacementReplayNeverRegressesSucceededPhase共用runSuccessfulReplayRace：分别暂停真实begin已持久原pending对象、真实REPLACE advance已成功后的时点；赢家真实提交后恢复输家。spy仅调度真实调用，不替代权限/SQL/owner事实。race-compile.log SUCCESS；尚未取得业务RED或修复，不计已过。继续等待只涉及自有测试实例的资源配置裁决，未盲开新容器。

### Task55C测试容器显式低内存opt-in（控制器裁决，2026-10-23）

只在BEAUTY_TEST_LOW_MEMORY=1且BEAUTY_TEST_DISK=1时启用，普通默认64MB路径参数不变。启动前通过既有测试VM只读/proc/meminfo核MemAvailable≥163840KiB(160MiB)，不足不创建容器；LIMA_HOME/BEAUTY_TEST_LIMA_INSTANCE仅测试显式指定，不改全局配置。

仅自己的UUID容器memory=memory-swap=128m（硬限134217728字节、无swap），innodb_buffer_pool_size16M、innodb_log_buffer_size4M、innodb_redo_log_capacity16M、key_buffer_size1M、max_connections16、thread_cache_size0、table_open_cache64、table_definition_cache400、mysqlx关闭、performance_schema仍OFF；磁盘datadir/事务持久性不变。不调oom_score_adj或优先级，不操作其他容器。不下载或安装，使用缓存mysql8.4/pull-never；实际启动错误或SHOW VARIABLES验证支持，读取自身cgroup peak/events并保存本UUID Docker终态事件。

先仅校准单case scopedReadsRejectDeletedAndForeignParents（parent-full），通过且自身未OOM/有余量后沿相同配置一case/新UUID完成pending/raceRED→修复→GREEN、B与必要host收口。128MiB仍不足或再次globalOOM立即停新容器、交准确pending，不试其它参数，不换H2。代价是更慢IO，但自身资源有硬上界。

## 本轮结束状态：128MiB校准失败，按裁决停止新增容器

`/tmp/task55c-low-calibration.log`：MemAvailable197316KiB满足160MiB gate；自己的UUIDb7298766-82c7-47c0-989d-6ab94a5298c8(容器IDece2a700...)启动3秒即Docker **oom事件**→die(exit1)/destroy，readiness未达到，未进入SHOW VARIABLES或Maven。此轮可确认是自身cgroup128MiB内存不足，不冒充业务RED/GREEN或参数运行支持已验。因为--rm后容器已消失，自身peak/events文件与MySQL日志不可读取，明确无peak值；Docker自身oom事件已保存于原log。cgroupVersion=2。

未尝试其他参数、未改Docker全局配置或其他容器。默认64MB启动参数不变；新增显式opt-in模式仅限测试，包含裁定全部参数、MemAvailable gate、自身变量/peak/events验证及finally。native runner语法检查通过；低内存分支校准**未通过**。该实验配置不得被报告成生产已验证能力。

### 确切pending（未打勾，不计完成）

1. `scopedReadsRejectDeletedAndForeignParents`新增错appointment-item父链、错工单参数：parent-full新启动失败，尚未执行业务；已有基本父链/删除/消融证据保留。
2. 新Query原op guard后的`legacyContextIsReadOnlyUnknownAndReceiptOwnershipIsScoped`：fixture已保证原op/context/root一致GUEST，需实际复验。
3. `successfulConcurrentReplaySkipsOldRevisionAndDoesNotRegressPhase`与`successfulReplacementReplayNeverRegressesSucceededPhase`：仅真实调用spy调度test写好/compile成功，未运行、无RED、生产竞态收口尚未实施。静态风险分别为pending对象取得后赢家已成功、输家旧revision冲突；及REPLACE openingRevision持久更新可将已成功phase改回OPEN_PENDING。后续须先真RED，再仅原完整namespace/key/hash且相同outerOperation成功重读，并在锁内保护SUCCEEDED不可回退，取得对应GREEN。
4. B抽取/构造路径定向 `reassignsCheckedInWithFrozenRules`、`replacementRejectsWithoutChangingOldArrangement`、`replacementUnknownKeepsOriginalCommandAndFence`：本轮队列未运行，保持pending。
5. Query新mapper constructor/phase guard及后续race修复后的必要targeted wiring/host身份消费链复验；118普通reactor证据是在该新Query guard之前，不能笼统推广为最终版本全绿。新guard本身op-parent-green2.log已真实1/0/0/0；当前修改的测试源码compile成功。
6. 本轮只将下列39个owned文件精确stage，git write-tree生成不可变index指纹供根正式只读审查；**不做产品commit**。产品commit与最终独立review验收仍未完成，C不得标完成，不进入D/公开wire。

### 最终命令和日志索引

所有Maven均JDK21 `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`、离线`-o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2`。

- 原生常规：`BEAUTY_TEST_DISK=1 BEAUTY_TESTS='<上述Class#精确method>' node scripts/verify-beauty-persistence.mjs`；early批次green1/green3/batch4/5/6使用现有runner。诊断single日志使用仅/tmp instrumented runner`/tmp/task55c-native-recovery.mjs`，相同数据/容器参数，只加CLI10s限制及本UUID诊断/events/finally时点。
- 低内存一次校准：`LIMA_HOME=/Users/robin/.colima/_lima BEAUTY_TEST_DISK=1 BEAUTY_TEST_LOW_MEMORY=1 BEAUTY_TESTS='WorkOrderScopeIT#scopedReadsRejectDeletedAndForeignParents' node scripts/verify-beauty-persistence.mjs`，结果见上，未启动第二次。
- 普通全beauty reactor：`mvn ... -f backend-plugins/shop-industry-beauty/pom.xml test`→reactor2.log，118test/0fail/error/skip；早先reactor.log的2个缺mapper夹具失败已修正，详历史。
- host真实消费：`BEAUTY_TEST_DISK=1 BEAUTY_RECEPTION_TESTS='BeautyReceptionIdentityIT#workOrderOpeningUsesRealCustomerAndGuestFacts' node scripts/verify-member-guest-registration.mjs --beauty-reception`→host1.log，1/0/0/0（在新Query op guard之前）。
- host守卫：`mvn ... -f backend/pom.xml -pl shop-app -am -Pbeauty-embedded test -Dtest=ArchitectureTest,BackendI18nParityTest,BeautyEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false`→host-guards.log，30test/2fail/0error/2skip，按上文准确区分branch package遗漏与旧OpsPoints。
- `python3 scripts/gen-beauty-java-method-design.py --check`、`gen-beauty-api-design.py --check`、`gen-beauty-database-design.py --check`、`node scripts/check-sql-portability.mjs`、`git diff --check`通过（SQL最终日志sql-final.log）。V7 SHA仍8894786048bf04ca45eff129a83fa7776da867994d72dff2343a91b215d6d3f2；V1–V7无diff，无V8。

除特别列出的首能力、政策hash、无预约指纹、语义hash、原op父链真实RED外，其余AC是实现后的回归验证，无逐条专属前置RED证据；不把后验GREEN或消融倒填成前置TDD。

### owned文件（精确index快照供审查，无C commit）

控制器owned progress/main TDD/brief/总体design/审查文件均排除；根本轮对main TDD与progress的新改动保持原状。精确列表亦保存`/tmp/task55c-owned-files.json`。

- `.superpowers/sdd/beauty-backend/task-55c-design.md`
- `.superpowers/sdd/beauty-backend/task-55c-report.md`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/CustomerIdentityVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/MyWorkOrderVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/OpenAppointmentWorkOrderReq.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/OpenWorkOrderReq.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/WorkOrderDetailVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/WorkOrderParticipationVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/WorkOrderStepVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/WorkOrderTaskVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/WorkOrderVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/service/AppointmentService.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/service/WorkOrderService.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyAppointmentConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyReceptionConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/mapper/BeautyMappers.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentPreparationService.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentServiceImpl.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/PreparedWorkOrderScope.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionCoordinator.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionPreparation.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionReassignment.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderCoordinator.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderPreparation.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderQuery.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderServiceImpl.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/AppointmentIncompleteSearchTest.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/BeautyConditionalWiringTest.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ReceptionBusinessIT.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ReceptionFixture.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/WorkOrderScopeIT.java`
- `backend-plugins/shop-industry-beauty/beauty-server/src/test/java/ai/neargo/shop/beauty/scenario/BookingServerWiringTest.java`
- `backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautyReceptionIdentityIT.java`
- `docs/technical/design/schema/beauty-api-design.json`
- `docs/technical/design/schema/beauty-java-method-design.json`
- `docs/technical/design/美业系统-API清单.md`
- `docs/technical/design/美业系统-Controller-Service-Mapper方法清单.md`
- `docs/technical/design/美业系统-Java类与方法清单.md`
- `scripts/verify-beauty-persistence.mjs`

## Fix round1 / I5 独立收口（2026-10-23，仍 INCOMPLETE）

统一审查文件为 review-task-55c.md，修复基线是不可变 INDEX TREE b06f773aadb588d65b40ce7dae31853c270e2155。本轮先按 I1–I5 原文更新 task-55c-design.md §11；没有修改 root-owned brief/main TDD/progress/overall/review，没有 C 产品 commit。VM 资源未恢复，根复核约233MiB小于512MiB恢复要求；本轮未调用真实 Docker/Lima/Maven，未创建容器、调参数或接触其他实例。

### I5 真实 subprocess RED → 最小修复 → GREEN

新增 scripts/tests/beauty-persistence-runner.test.mjs。真实 node 子进程执行仓库原 runner，PATH 完全指向独立临时 fake docker/limactl/mvn；fake docker run 先写本 UUID 的模拟 daemon 状态，再报错或真实等待到客户端10秒 ETIMEDOUT。fake rm 根据实际 runner 参数删除本 UUID 状态，另一个 foreign-running 状态必须保持。断言检查全部 docker 调用只针对 own UUID、rm 恰好一次；启动失败不得进入 fake Maven。此为 runner 行为反例，不是源码字符串匹配，也不是 native 数据库测试。

- RED（旧 runner，修改生产脚本之前）：`node --test scripts/tests/beauty-persistence-runner.test.mjs > /tmp/task55c-fix1-i5-red.log 2>&1`，exit1，6 tests / 1 pass / 5 failures / 0 cancelled/skipped/todo。前四项确为创建状态未被删除的 assertion failure，其中 timeout case 实际耗时10373ms；第五项确为 cleanup异常覆盖原 Maven主体异常；第六项原有单独cleanup失败行为已绿。没有编译/装配/真实Docker故障充RED。
- 仅随后修改 scripts/verify-beauty-persistence.mjs：docker run 移入同一 try/catch/finally；记录主体失败，finally无论run是否返回都按本 UUID 尝试 rm；诊断失败各自隔离，cleanup失败不覆盖原异常；无原异常时cleanup失败仍抛出。原默认64MB与显式低内存参数/前置gate未改变。
- GREEN（同一测试文件/断言）：`node --test scripts/tests/beauty-persistence-runner.test.mjs > /tmp/task55c-fix1-i5-green.log 2>&1`，exit0，6 tests / 6 pass / 0 failures/cancelled/skipped/todo，14401ms。真实timeout case10707ms后检查原ETIMEDOUT且own状态已删除；low模式也确实尝试自身终态events；run错误、diagnostic错误、cleanup错误与Maven主体失败均有独立断言。
- 该 GREEN 只验证隔离runner的控制流、参数目标和异常保留，不证明128MiB MySQL配置可运行。旧校准的启动OOM/无readiness/无SHOW VARIABLES/无peak值历史全部保留。

### I1–I4 精确剩余项 / waitingENV

I1/I2既有两个确定调度 native测试仍只有上轮编译证据，无实际业务RED，生产竞态fix未实施。§11进一步明确I1最初find absence、旧pending、begin已返回成功、无预约准备定义变化四个窗口与严格同namespace/key/hash+同outer成功重读；I2成功phase/result/payload不可回退及详情保持可读。

I3精确方案为新052和省略身份054锁外真实guest owner.get/完整scope相交，CLAIMED归一化必须同scope不可变receipt桥接；I4为同root/fence/current/outer短事务复用已有workOrder+actualtask守卫、保留历史软删CAS/唯一键。它们的新增host/孤立task反例已定位并记录精确测试方案，**本次未新增这些Java测试方法，也未运行或宣称compile/RED/GREEN**。I1新增窗口的Java测试代码同样尚未落地；环境恢复后的实现轮须按 §11 先补测试、取得真实业务RED，再生产fix与GREEN。旧V1–V5最终native/B/host/wiring未达清单仍然有效，不因I5通过而完成。

### M1 输出分类

现有输出并非pristine：reactor中的预期negative context初始化/连接关闭告警是反例测试；Mockito/JDK动态agent和Flyway对MySQL8.4提示属依赖版本噪声；host重复mapper、宿主H2夹具Flyway提示及测试配置OTP限流提示属宿主夹具/既有配置噪声。本任务native证据仍来自真实MySQL8.4；不据host夹具提示改称H2验native，也不据测试OTP提示推断生产关闭限流。未为收敛噪声降级生产日志或扩改其他业务。

本轮仅design/report、runner与一个新增行为测试相对FIX_BASE变化。精确stage后写新index tree供scoped复审；无产品commit，I1–I4及最终必要原生回归仍待环境，C维持INCOMPLETE。

I5 GREEN后额外静态检查：node --check runner、node --check 新测试、git diff --check、git diff --cached --check 均 exit0；本轮没有Java改动，因此未重复旧reactor或伪称新增native编译。当前owned清单共40文件（旧39加本次runner行为测试），精确清单 /tmp/task55c-owned-files.json。

## Fix round2：R1-N1/R1-N2 仅文档纠正（2026-10-23，仍 INCOMPLETE）

完整读取 /tmp/review-bm04-55c-r1-i5.md：I5 scoped Approved/ADDRESSED；I1–I4仍OPEN/waitingENV。本轮基线为INDEX TREE 2db283f10b6eaa765c45a0eb345e3b1880153ebd，仅修改design/report，不改Java/runner/测试，不启动Docker/Lima/Maven，不新增产品commit。

- R1-N1：focused核对AppointmentPreparationService的真实outer链接写者，确认绑定appointmentOperationNo时phase=RESOURCE_PENDING。design §11.2已明确先处理SUCCEEDED且phase/result/payload不回退；完整原outer/namespace/key/hash/appointmentOperationNo/成功receipt/sourceRevision binding验证后允许RESOURCE_PENDING→OPEN_PENDING，已OPEN_PENDING按同binding/已存openingRevision幂等恢复，其他前态拒绝。不能把首次REPLACE续行误限为OPEN_PENDING。
- R1-N2：focused核对真实MemberGuestRegistrationServiceImpl注册/get/claim与按完整tenant/entity/store读取的owner事实，以及ReceptionIdentity.validFact；design §11.3将错误ACTIVE改为REGISTERED，并保留guestNo相等、revision1、userNo为空完整合同。CLAIMED为revision2/userNo非空非blank且guestNo相等，归一化另须同scope不可变receipt桥接；不扩展生产状态合同。

本轮只读focused源码核对与git diff --check，无新编译、子进程测试或native结果；旧I5的6/6证据原样保留，不重复运行。I1–I4及最终native/B/host/wiring的既有pending不变，未取得真实业务RED前不实施对应生产fix；C仍INCOMPLETE。新快照仅含这两份owned文档相对上述基线差异，交根复审后冻结写入。

## MySQL9.7本机基线重试历史与 round3 恢复（2026-10-23）

用户指定mysql9.7后，runner镜像已改本机cached mysql:9.7.2且pull never。上次任务中断前实际运行一次：JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home LIMA_HOME=/Users/robin/.colima/_lima BEAUTY_TEST_DISK=1 BEAUTY_TEST_LOW_MEMORY=1 BEAUTY_TESTS='WorkOrderScopeIT#scopedReadsRejectDeletedAndForeignParents' node scripts/verify-beauty-persistence.mjs > /tmp/task55c-mysql97-local-retry.log 2>&1；exit1。完整输出附下。gate实测10316KiB<160MiB，因此未调用docker run、未启动MySQL、未进入Maven，不是业务RED/GREEN。

同次镜像配置变更后的原6项fake CLI子进程测试已真实复验一次：node --test scripts/tests/beauty-persistence-runner.test.mjs > /tmp/task55c-mysql97-runner-regression.log 2>&1；exit0，6tests/6pass/0fail/0cancelled/skipped/todo，14856.694333ms（timeout10716.510375ms）。只读已有log，不重复运行。这些不是原生数据库验证。

用户后续授权清理后根确认VM可用3653792KiB/0running，round3恢复。新基线9.7.2默认DISK=1/64Mbuffer，不再使用失败128MiB opt-in；先parent-full单case确认环境，继而I1–I4逐项真实RED→fix→GREEN及最终必要定向回归。此前8.4结果仅历史版本证据，不改写为9.7。本轮host runner唯一镜像同步9.7.2（新增owned文件scripts/verify-member-guest-registration.mjs），不做全项目迁移、不改全局VM/其他容器。

```text
node:internal/modules/run_main:107
    triggerUncaughtException(
    ^

AssertionError [ERR_ASSERTION]: Test VM needs 160MiB available; observed 10316KiB
    at file:///Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/scripts/verify-beauty-persistence.mjs:17:3
    at ModuleJob.run (node:internal/modules/esm/module_job:437:25)
    at async onImport.tracePromise.__proto__ (node:internal/modules/esm/loader:642:26)
    at async asyncRunEntryPointWithESMLoader (node:internal/modules/run_main:101:5) {
  generatedMessage: false,
  code: 'ERR_ASSERTION',
  actual: false,
  expected: true,
  operator: '==',
  diff: 'simple'
}

Node.js v26.0.0
```

## Fix round3：I1–I4 真实修复与验证收口（2026-10-23）

当前状态：**I1–I4的修复和本轮必要验证已完成，待独立审查确认；Task55C尚未验收，无C产品commit。** 本节更新末尾事实；前文INCOMPLETE、等待环境、仅compile等文字均是当时版本历史，不反向改写。FIX_BASE为最后复审TREE e4daa1fa36881aa9bf8a26b2e21eb1751a36112e；HEAD仍edbbefeb941aa3c27c5029f7c616f226c805748a。

### 实施与边界

- I1：当前权限仍先于完整请求hash和重放；begin返回SUCCEEDED立即回原result。completedOrThrow仅CONFLICT可重读，限完整namespace/key/hash、同原outer operation、SUCCEEDED；无原intent的absence窗口只允许同完整namespace原键，不能借异键赢家。初次准备与已验证身份后的续行分开保护，当前身份owner错误不吞。sameOperation逐项核tenant/entity/store/actor realm-kind/endpoint/key/hash/operationNo，并用于最终锁内原行。新begin显式设置当前tenantNo，保持内存对象与DB MAIN一致，非放宽namespace。
- I2：openingRevision短TX先锁内sameOperation/SUCCEEDED原结果返回，成功phase/result/payload不回退。未成功严格核原appointmentOperationNo、成功receipt完整binding/sourceRevision；RESOURCE_PENDING匹配原payload后推进OPEN_PENDING，已OPEN_PENDING仅相同opening payload恢复。原成功结果不重新构造。
- I3：新052和省略身份054在新intent前锁外调用真实guest.get；REGISTERED/revision1/user空，CLAIMED/revision2/user非blank且同guest，CLAIMED归一USER须同scope不可变receipt。注入既有CustomerOwnership并复用owns，不造第二套算法。preparedForIdentity只规范本次prepared，不修改原预约GUEST；已有pending坚持原outer canonical/hash/payload，不因当前guest后续claim重生成。成功原键不重验过期proof。真实HOST断言错误owner scope、无/错scope receipt拒、GUEST预约未重写、成功原键不再get；core控制owner只用于pending时序，边界如实。
- I4：同root/fence/current/outer短TX的verifyAppointmentLocked复用countWorkOrders完整已有WO/实际task守卫，普通无REPLACE同样拒绝；两个真实孤立task端点反例断言无新范围/context/草稿和owner变更调用。历史软删CAS/唯一键保留。
- I5沿用已复审closed。仅两指定runner镜像改cached mysql:9.7.2/pull never；默认64M/DISK路径，LOW_MEMORY=0。每一新native批次启动前limactl只读MemAvailable≥512MiB（/tmp/task55c-r3-memory-*.log），串行单UUID、localhost TCP、readiness、finally仅清自身。未改VM全局配置/触碰其他容器。失败128MiB模式放弃，不据9.7预检拒绝说native通过。

### RED→GREEN 证据顺序（F=assertion failure，E=error）

原生统一前缀：JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home BEAUTY_TEST_DISK=1 BEAUTY_TEST_LOW_MEMORY=0 BEAUTY_TESTS='<下列selector>' node scripts/verify-beauty-persistence.mjs。Maven全部离线隔离m2=/tmp/codex-beauty-bm01-m2。

| 日志（/tmp/） | selector / 真实结果 |
|---|---|
| task55c-r3-parent-full.log | WorkOrderScopeIT#scopedReadsRejectDeletedAndForeignParents；1/0F/0E/0skip GREEN。包括此前pending的错appointment-item与错工单参数；仅环境恢复后的真实case，非新防线RED。 |
| task55c-r3-races-red.log | successfulConcurrentReplaySkipsOldRevisionAndDoesNotRegressPhase + successfulReplacementReplayNeverRegressesSucceededPhase；2/2F/0E/0skip。旧helper期望正常原结果、实际CONFLICT；确为真实begin旧pending/真实advance已成功后调度。 |
| task55c-r3-i1-windows-red.log | successfulReplaySurvivesInitialFindAbsence + successfulReplayReturnsAlreadySucceededBegin + unassignedReplayIgnoresChangedPreparationAfterWinnerSucceeds；3/2F/1E。前两项期望原VO实际CONFLICT有效RED；无预约项发布27min但产品fixture17min，BAD_REQUEST夹具error不计该窗口RED。 |
| task55c-r3-i1-unassigned-red.log | 仅unassignedReplayIgnoresChangedPreparationAfterWinnerSucceeds；改为同17min的新buffer/NEW规则后1/1F/0E，期望原VO实际scopeHash CONFLICT，有效业务RED。 |
| task55c-r3-i4-red.log | appointmentWithOrphanActualTaskRejectsOpening + fullAppointmentWithOrphanActualTaskRejectsOpening；2/2F/0E，真实已有孤立task却未抛异常。 |
| task55c-r3-races-green.log | I1/I2最小fix首次尝试+successfulOtherKeyDoesNotRescueStalePending；3/2F/0E（异key case PASS）。严格绑定发现新begin对象tenantNo为null、DB默认MAIN，sameOperation误拒；保留这次失败，随后显式赋当前tenant。 |
| task55c-r3-races-green2.log | 同上3case，3/0F/0E GREEN；成功phase/result/payload不变及B/C详情可读。 |
| task55c-r3-i1-windows-green.log | 三个I1新增窗口，3/0F/0E GREEN；新目录定义不改变成功原结果。 |
| task55c-r3-guard-legacy-green.log | I4双入口 + legacyContextIsReadOnlyUnknownAndReceiptOwnershipIsScoped，3/0F/0E GREEN；此为新Query原op守卫之后的最终legacy/receipt组合证据。 |
| task55c-r3-i3-host-red.log | 真实HOST omittedGuestIdentityUsesCurrentOwnerAndScopedReceipt(false/true)，2/2F/0E，真实开单已返回而owner.get期望1实际0。 |
| task55c-r3-i3-pending-red.log | pendingGuestIntentRetainsCanonicalIdentityAfterClaim，1/1F/0E，真实Outbox回滚保留pending，首次get期望1实际0。 |
| task55c-r3-identity-unknown-green.log | pendingGuestIntentRetainsCanonicalIdentityAfterClaim + replacementAcceptUnknownReusesDurableOperationAfterRestart + replacementExecuteUnknownReusesDurableOperationAfterRestart，3/0F/0E GREEN；原pending身份/hash/payload不漂移，两UNKNOWN窗口原号恢复正常。 |
| task55c-r3-i3-host-green.log | 两个I3真实owner参数case + workOrderOpeningUsesRealCustomerAndGuestFacts，3/0F/0E GREEN；在最终I3 artifacts install之后，覆盖真cr1/guest、当前撤权/完整请求与过期proof原成功重放、归属。 |
| task55c-r3-b-regression.log | ReceptionBusinessIT#reassignsCheckedInWithFrozenRules + replacementRejectsWithoutChangingOldArrangement + replacementUnknownKeepsOriginalCommandAndFence，3/0F/0E GREEN。 |
| task55c-r3-final-scope.log | replayChecksFullRequestAndCurrentPermissionBeforeFrozenSuccess + concurrentDifferentKeysOnlyOneScopeAndSameKeyReplaysOldRevision，实际2/0F/0E GREEN。命令还误带不存在的旧名replacementRejectedLeavesOriginalHoldAndNoScope，未执行第三项；未声称3通过。 |
| task55c-r3-terminal-final.log | 准确名replacementRejectedKeepsOriginalHoldAndCannotOpenOnReplay + successfulReplacementReplayNeverRegressesSucceededPhase + successfulOtherKeyDoesNotRescueStalePending，3/0F/0E GREEN；补齐上一选择器未跑的拒绝case，最终完整namespace锁内守卫下再核终态与异key明确CONFLICT。 |

I1/I2/I4先RED再生产fix；I3先真实host/core RED再生产fix。增强的phase/result/payload与详情断言有修复后GREEN，races-red当时的具体失败是旧helper期望原结果却CONFLICT，不把增强断言倒填为旧RED。此前SQL/Query的专属前置RED历史缺口仍如前文，不用本轮后验回归或旧有效消融抹去。

HOST命令统一为JAVA_HOME=上述JDK BEAUTY_TEST_DISK=1 BEAUTY_RECEPTION_TESTS='<selector>' node scripts/verify-member-guest-registration.mjs --beauty-reception。RED的selector准确为BeautyReceptionIdentityIT#omittedGuestIdentityUsesCurrentOwnerAndScopedReceipt；GREEN准确为BeautyReceptionIdentityIT#omittedGuestIdentityUsesCurrentOwnerAndScopedReceipt+workOrderOpeningUsesRealCustomerAndGuestFacts。两次均cached9.7.2真实本机MySQL；资源准入为显式fixture，member事实/票据/权限/本域receipt与持久事务均真实。安装命令为mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml install -DskipTests；日志task55c-r3-pre-host-install.log、task55c-r3-final-host-install.log均BUILD SUCCESS。不是部署验收。

### 最终必要验证与未决边界

最终普通beauty reactor命令：JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml test > /tmp/task55c-r3-reactor-final.log 2>&1；exit0，**118tests/0fail/0error/0skip**（api2+core41+web22+channel25+server28）。此为本轮最终Java快照，不借用旧reactor2结果；包括BeautyConditionalWiringTest、BookingServerWiringTest，以及BeautyArchitectureTest13/0/0/0，验证新ownership/Query真实构造依赖与条件装配。最后一处测试行宽换行在本次reactor之前完成，已包含编译；其后没有Java变更。

生成与规范：task55c-r3-generation.log记录gen-beauty-java-method-design.py生成及--check，171 DTO/47自定义mapper方法；API --check 20模块118功能，database --check 41设计/10生成物；均PASS。task55c-r3-sql.log：162处/51文件，无新增方言依赖。方法JSON补本轮精确私有辅助签名/原键/终态/identity守卫合同，同步两份生成清单；不增公共DTO/空Service。git diff --check通过，新Java变更行宽≤120、无新增跨域全限定类型写法。

V1–V7相对任务BASE无diff，无V8；V7 SHA仍8894786048bf04ca45eff129a83fa7776da867994d72dff2343a91b215d6d3f2。native各真实日志实际运行MySQL9.7.2；Flyway“未验证9.7(最高8.1)”属于依赖兼容提示，保留原输出，没有隐藏告警。Mockito/JDK agent、host重复mapper/测试seed/security/Netty Mac DNS fallback提示保持测试/依赖分类，不推断生产行为。前述host全局ArchitectureTest已知OpsPoints及[beauty,store]登记问题本轮不重跑、不修复、不声称全backend全绿；旧V506 parser本轮未跑。Task60归属保持。

截至本轮，旧pending V1父链完整case、V2最终legacy/receipt、V3两个race、V4 B抽取3回归、V5最终wiring/HOST均已有上表或本节真实结果。I1–I4已实现并取得对应业务RED→GREEN，等待正式独立复审确认关闭；I5已在先前复审关闭且未回退。旧SQL/Query前置TDD历史限制仍留给控制器按既定过程裁决，不补造历史。

剩余事项仅正式独立review及其finding处理、C产品commit/验收；HTTP/remote/部署/walk-in/执行/来源提交/交易/支付仍归后续任务，本步没有越界。精确owned清单41文件见/tmp/task55c-owned-files.json（新加host runner）；root mainTDD/progress/brief/overall/review文件排除。新index tree和相对e4的fixdiff单独交根；冻结写入待审，不标Task55C已验收完成。
