# Task55D 实施报告 / 待独立审查

## 当前修复 Round 2/5（2026-10-23，待根窄复审）

R1复审原文 `/private/tmp/review-bm04-55d-r1.md` 完整已读。原审查者已关闭I1–I4；本轮仅N1：新增head锁等待会跨过CREATE冻结资源start。FIX_BASE=`8be377cc06b1e7320b469aa94b6ef448bdffd678`，HEAD仍`612b8c08144d0aaa81959d4c455ab1dcc3d4404d`；35 owned不扩张，无子agent，未commit/push/deploy。根批准范围内执行TDD与窄回归，等待独立复审后才可提交。

### N1修复与时序证据

`AppointmentPreparationService`复用已有`beautyReceptionClock`强注入；原CREATE短TX全部根/fence/current/outer/head验证及准备写完成后、return/commit之前读当前Clock，检查原plan全部interval严格未来。过期抛窄域内`ReceptionPlanExpired`让整个准备TX回滚；仅原048准备调用处在TX退出后捕获该信号，沿既有`walkInFailure`另短TX保存原操作ARRIVAL_REQUIRES_RESCHEDULE。无普通CONFLICT/权限异常吞并、无嵌套REQUIRES_NEW、无rematch/新key/新命令，冻结IDs/payload/namespace保留。原I1最终048时间检查、I2head验证、I3逐项lease/完成退避、I4非零HTTP错误传播未改。

`WalkInRecoveryIT#createHeadLockWaitCrossingResourceStartCannotAcceptCommand`先以真实root trigger保留冻结RECEIVED，独立JDBC事务锁住原定义head；当前B恢复原A操作。最终GREEN以指定worker实际调用栈同时含`WorkOrderPreparation.verifyCreateLocked`、head mapper `selectScopedForUpdate`、JDK21 `sun.nio.ch.Net.poll`且future未完成作同步屏障，确认SQL已进入阻塞调用后才把既有fixtureClock推进到最早资源start，释放未更改的head。真实数据库锁和业务路径未替换成stub/spy；共享clockAt为volatile确保两线程可见性。最终断言零owner accepts、零root/fence/current/preview/innerop、原outer仍RECEIVED且IDs/actor/key/requestHash/frozenPayload/links不变，返回PENDING及ARRIVAL_REQUIRES_RESCHEDULE。future早退会立即报告实际返回值，异常单独保存，首先断言资源副作用为空。

观测限制明确：本次GREEN使用线程阻塞点同步，**不声称监控表观察到了LOCK WAIT**。测试同步依赖本仓指定JDK21/JDBC调用栈；最终正确性由实际数据库/owner业务断言决定，未改变MySQL performance_schema=OFF、内存或VM配置。

| 日志（均 `/private/tmp/`） | 实际结果与快照 |
|---|---|
| task55d-r2-red.log / red2.log | 1/1/0/0各；监控等待观测失败，不作业务RED |
| task55d-r2-diagnostic.log | 观测到等待后旧流程arrive抛CONFLICT，尚未到owner副作用断言，不计正式RED |
| task55d-r2-red3.log | **1/1/0/0，genuine RED**；在任何R2生产变更之前，owner.accepts应空却实际有原RESERVE+CONFIRM |
| task55d-r2-green.log | **12/1/0/0**；修复后11项通过，N1仍在监控观测断言失败，不计GREEN |
| task55d-r2-wait-diagnostic.log | 1/1/0/0；worker确在MySQL socket→head mapper→verifyCreateLocked，但INNODB_TRX仅见blocker RUNNING。由此仅改测试同步屏障，未再改生产逻辑，未扩大sleep或重试掩盖问题 |
| task55d-r2-green-final.log | **12/0/0/0，BUILD SUCCESS**；最终线程阻塞点屏障与下表窄回归，16:41:03完成 |
| task55d-r2-wiring.log | **23/0/0/0，BUILD SUCCESS**；条件装配8+server wiring2+架构13，验证新增Clock构造依赖无环 |
| task55d-r2-checks.log | 三生成器--check、原SQL gate、whitespace及V1–V7逐字节/SHA对HEAD不变均通过；无超过120列的新Java行 |

最终native 12项覆盖：

| 方法 | 实际用例数 / 范围 |
|---|---|
| createHeadLockWaitCrossingResourceStartCannotAcceptCommand | 1；N1真实不变head阻塞跨start、回滚及原PENDING |
| openPendingCrossingResourceStartCannotCreateScope | 1；保留I1最终048检查，普通052仍可开单 |
| changedLocalDefinitionBeforeCreateDoesNotReserveResources | 2；definition/policy失效仍拒绝新CREATE，原冻结计划不替换 |
| atomicCreatePreparationRollsBackWithoutHalfLinks | 6；root/fence/current/preview/innerop/outer链接六点真实故障回滚 |
| expiredSelectedPlanRequiresRescheduleWithoutRematching | 1；既有早期过期恢复规则 |
| unknownConfirmRestartsWithOriginalCommandAndCurrentEmployee | 1；已接受UNKNOWN原命令对账不重新走新CREATE验证 |

### R2实际命令、边界与自审

工作目录为唯一beauty-data worktree。Native各批均先运行下列preflight并Python解析断言MemAvailable>=524288KiB；输出`task55d-r2-preflight1.log`至`7.log`，最终3631296KiB。cached MySQL9.7.2、普通DISK64M、单自有UUID、pullnever/localhost/readiness/finally只清自身；未停止他人容器/prune/restartVM。

```bash
LIMA_HOME=/Users/robin/.colima/_lima limactl shell --instance colima-hxkey-tests cat /proc/meminfo
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  BEAUTY_TEST_DISK=1 BEAUTY_TEST_LOW_MEMORY=0 BEAUTY_TESTS="$S" \
  node scripts/verify-beauty-persistence.mjs
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 \
  -f backend-plugins/shop-industry-beauty/pom.xml -pl beauty-server -am test \
  -Dtest=BeautyConditionalWiringTest,BookingServerWiringTest,BeautyArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false
python3 scripts/gen-beauty-java-method-design.py --check
python3 scripts/gen-beauty-api-design.py --check
python3 scripts/gen-beauty-database-design.py --check
node scripts/check-sql-portability.mjs
git diff --check
```

- red/red2/diagnostic/red3/wait-diagnostic 的S=`WalkInRecoveryIT#createHeadLockWaitCrossingResourceStartCannotAcceptCommand`。
- green/green-final 的S=`WalkInRecoveryIT#createHeadLockWaitCrossingResourceStartCannotAcceptCommand+openPendingCrossingResourceStartCannotCreateScope+changedLocalDefinitionBeforeCreateDoesNotReserveResources+atomicCreatePreparationRollsBackWithoutHalfLinks+expiredSelectedPlanRequiresRescheduleWithoutRematching+unknownConfirmRestartsWithOriginalCommandAndCurrentEmployee`。
- 上述输出分别重定向到表内对应日志；wiring为`task55d-r2-wiring.log`，三生成器/SQL/byte-hash/whitespace为`task55d-r2-checks.log`。V1–V7检查逐一比较Path.read_bytes与`git show HEAD:<path>`并打印SHA256；七文件均一致，ready7/27私表无变化。
- 自审确认最终时间检查后至TX提交无额外锁/RPC或业务写；窄异常只在原048准备入口处理，回滚先于原结果保存；本地head验证和原锁顺序保留。未修改Resource owner协议/SPI或生产SQL/schema，原SQL checker仍只扫描backend，未扩称覆盖beauty-plugin。
- 生成源仅追加N1方法合同并同步两份Java方法清单；未运行DDL生成器。精确owned仍35项，root mainTDD/progress/brief/总体design/review/Task56草稿均排除。
- R1的39 native/118普通reactor及首轮HOST/迁移/i18n均是历史快照，本轮未无关重跑、不重标当前全绿；当前最终证据仅本轮12 native、23普通及checks。M1保持全分支review deferred，受控故障及继承环境WARN分类沿R1，不声称pristine日志。

本轮精确stage后停止产品修改；新tree、FIX_BASE→新tree fixdiff、HEAD→新tree完整diff及SHA见`/private/tmp/task55d-r2-review-manifest.txt`，owned见`/private/tmp/task55d-r2-owned.txt`。未commit，交根独立窄复审。

## 历史修复 Round 1/5（2026-10-23 16:20 CST，I1–I4经复审关闭，新增N1由R2处理）

正式审查 `/private/tmp/review-bm04-55d.md` 原文完整已读：Spec FAIL / Quality Needs fixes，0 Critical / 4 Important / 1 Minor。I1–I4均作为实际实现缺陷修复，不将未覆盖细项当新业务需求；M1按根指示记为全分支审查deferred，非本轮阻断。FIX_BASE=`cf450f743f9c26d4ac20c441daa9938e566eb50e`；HEAD仍`612b8c08144d0aaa81959d4c455ab1dcc3d4404d`。本人实施、无子agent；采用receiving-code-review、原TDD/验证工作流。未commit/push/deploy，不接Task56，不标Task55D完成；根独立复审后才能提交。

### 缺陷、修复与真实证据

| 审查项 | 修复与对应原名测试 | 前置RED → 当前GREEN |
|---|---|---|
| I1 最终048跨冻结start仍开单 | `openPendingCrossingResourceStartCannotCreateScope`：先draft trigger留下OPEN_PENDING；hold query回调跨最早interval start；最终writer全部根/outer/本地head锁及证据核验之后，立即用当前Clock检查原plan全部interval，过期同TX保留原OPEN_PENDING/plan/key/namespace并写ARRIVAL_REQUIRES_RESCHEDULE、显式清retry/lease，零order/context/tasks/draft。普通052仍原before-end规则，同用例确认052仍可开单 | r1-red原错误SUCCEEDED；r1-final-green2通过；不改052时间规则，无另事务写结果 |
| I2 新CREATE漏本地head核验 | `changedLocalDefinitionBeforeCreateDoesNotReserveResources` 两参数（definition/policy）：真实root故障留下冻结RECEIVED后禁用head，恢复原号；CREATE短TX内复用本域WorkOrderPreparation新增verifyCreateLocked，按definition业务号再policy锁head，核ACTIVE、原selectedVersion/SKU；版本记录仅定位父head。原匹配计划/key不重建，无RPC入TX。已链接UNKNOWN不重新走新CREATE核验 | r1-red两例均已有RESERVE/CONFIRM而失败；r1-final-green2两例零owner accepts、零root、原RECEIVED及payload/key保持 |
| I3 lease/退避误用批次时间 | `slowBatchRetainsPerItemLeaseAndCompletionBackoff`：既有beautyReceptionClock强注入，SELECT独立cutoff，逐项claimAt+30s；finally锁到原outer后取当前完成时间+30/60s。保持DATETIME秒精度withNano(0)，原phase/version/lease守卫。回调推进31s/61s，断言第二lease及正常/失败retry相对实际时点，最终lease清空 | r1-red两项lease错误相等；r1-final-green2通过；原公平/排队在r1-native-final通过 |
| I4 明确owner权限错误被吞 | `releaseProofPermissionFailureIsNotConvertedToPending`：本地FORBIDDEN/PLAN_CAPABILITY_REQUIRED及远程403/500/503传播，逐次原outer全行不变；TIMEOUT保持PENDING。原错hold、未知释放/手工状态及真实release完成收口另以`releasedOriginalHoldClosesRejectedWalkInOnlyAfterVerifiedRelease`通过 | r1-red FORBIDDEN未抛；r1-remote-red 500/503两参数真实2fail0error；r1-final-green2两参数+真实释放回归通过 |

实际日志（均 `/private/tmp/`；数字tests/failures/errors/skipped）：

- `task55d-r1-red.log`：**5/5/0/0**，16:07:37完成，先于本轮生产fix；根核读。I2两参数导致5用例，不是setup/编译失败。
- `task55d-r1-green.log`：首修复 **5/0/0/0**，后续远程分类/锁顺序完善前的历史证据。
- `task55d-r1-native-final.log`：**39/0/0/0**，D整类33 + B2 + C4，16:14:40。覆盖完整D及最相关B/C；它早于HTTP500/503分类最后收紧，不冒称最后生产快照整类已重跑。
- `task55d-r1-remote-red.log`：**2/2/0/0**；新增HTTP500/503反例在原“5xx→PENDING”分支实际不抛而失败，随后才收紧。
- `task55d-r1-final-green.log`：**7/0/0/0**；四问题方法（I2/I4参数化）+真实释放回归。其后自审将048时间判断保持在所有根证据/head验证之后，052检查原顺序不变。
- `task55d-r1-final-green2.log`：**7/0/0/0**，上述selector在最后生产快照通过。
- `task55d-r1-reactor-final.log`：**118/0/0/0**，最后普通完整插件reactor，含api/core/web/channel/server、实际无环装配、Job与server wiring。先前`task55d-r1-reactor.log`也是118通过，历史保留。
- `task55d-r1-checks.log`：三生成器--check、原SQL gate PASS（仍仅backend范围）、V1–V7对HEAD逐字节/SHA不变、whitespace检查。本轮无SQL或DDL变更，无新增ErrorCode/三语文案；不无关重跑全backend、HOST、迁移故障全集。首轮HOST/迁移/i18n仍明确是首轮证据。

### I4 实际remote错误链及根裁决

真实源码链：`InternalResourceBookingController` → `InternalServiceResponses.invoke`（`backend/shop-base/.../svc/InternalServiceResponses.java:22`）；其catch BizException仅少量码映射专用状态，FORBIDDEN→403，ENTITLEMENT_FEATURE_UNAVAILABLE/MEMBER_GUEST_UNAVAILABLE→503，未枚举的PLAN_CAPABILITY_REQUIRED等→500，**响应无body、Biz码已经丢失**。`ServiceClients.create`（`backend/svc-client/.../ServiceClients.java:55`）对所有非2xx只产生`ServiceCallException(REMOTE_ERROR,statusCode)`；`RemoteResourceBookingPort.call`→`ServiceCalls.call`原样传播该异常，**没有先转回BizException的结构化decode**。

因此根明确同意最小保守规则：**所有非零HTTP错误传播，包括500/503**，不凭5xx推断“仅网络未知”；代价是对无细分协议的远程失败更保守，可用性不以吞明确owner拒绝换取。不改全局owner协议/异常框架。只有TIMEOUT、UNREACHABLE、REMOTE_ERROR/status0（无可解析核验证据）可PENDING；本地CONFLICT/NOT_FOUND与adapter非法证据IllegalStateException仍为未证明，其它BizException/能力错误直接传播。新增500/503严格先RED再GREEN。记录此路径为源码证据，并以实际异常类型native fault验证；不声称部署全remote owner HTTP服务器做过故障注入。

### 实际命令、配置与自审

Native命令统一：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  BEAUTY_TEST_DISK=1 BEAUTY_TEST_LOW_MEMORY=0 BEAUTY_TESTS="$S" \
  node scripts/verify-beauty-persistence.mjs
```

- r1-red/r1-green 的 S=`WalkInRecoveryIT#openPendingCrossingResourceStartCannotCreateScope+changedLocalDefinitionBeforeCreateDoesNotReserveResources+slowBatchRetainsPerItemLeaseAndCompletionBackoff+releaseProofPermissionFailureIsNotConvertedToPending`。
- r1-remote-red 的 S=`WalkInRecoveryIT#releaseProofPermissionFailureIsNotConvertedToPending`。
- r1-final-green/r1-final-green2 的 S=`WalkInRecoveryIT#openPendingCrossingResourceStartCannotCreateScope+changedLocalDefinitionBeforeCreateDoesNotReserveResources+slowBatchRetainsPerItemLeaseAndCompletionBackoff+releaseProofPermissionFailureIsNotConvertedToPending+releasedOriginalHoldClosesRejectedWalkInOnlyAfterVerifiedRelease`。
- r1-native-final 的 S=`WalkInRecoveryIT,ReceptionBusinessIT#checkInKeepsScheduleHoldAndNoTasks+replacementUnknownKeepsOriginalCommandAndFence,WorkOrderScopeIT#replacementAcceptUnknownReusesDurableOperationAfterRestart+replacementExecuteUnknownReusesDurableOperationAfterRestart+successfulConcurrentReplaySkipsOldRevisionAndDoesNotRegressPhase+preservesExpandedItemsAndEveryResourceCapacityUnit`。
- r1-reactor-final：`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml test`。
- 本轮原生每批均先 `LIMA_HOME=/Users/robin/.colima/_lima limactl shell --instance colima-hxkey-tests cat /proc/meminfo`，`task55d-r1-preflight1.log`至`6.log`各自Python断言MemAvailable>=524288KiB；cached mysql9.7.2、DISK64M、单自有UUID、pullnever/localhost/readiness/finally仅清自己。未prune/停他人容器/重启VM。
- 自审：CREATE head锁沿原根/fence/current/outer之后、稳定DEFINITION业务号→POLICY，与C一致；新增WorkOrderPreparation依赖仍强注入无环；Clock沿既有bean，无自造时钟基础设施；最终048判断在所有证据/head检查之后、第一次范围写之前。无额外资源命令、无旧actor冒用、无冻结payload重写；非零HTTP传播为已说明的保守边界。
- 生成源新增verifyCreateLocked方法及R1合同、更新数据库字典的逐项时间语义；未运行DDL生成器。精确owned从34增至35，仅增加既有`WorkOrderPreparation.java`；根审查档案/brief/mainTDD/progress/Task56草稿均不stage。

M1分类：`Reception reconciliation deferred ... IllegalStateException`是controlled brokenSource/slowBatch故障注入预期日志；MySQL9.7/Flyway已验证8.1提示及首轮HOST mapper/development/native-resolver警告是已有环境输出。本轮不声称日志pristine，未为消噪改生产logger或宿主配置；按根指示M1保留全分支review deferred，未把它当阻断缺陷或伪称已添加log-capture断言。

本轮快照、fixdiff（FIX_BASE→新tree）、完整diff及精确owned路径见 `/private/tmp/task55d-r1-review-manifest.txt` 和交接消息；先精确stage、未commit，等待根独立复审。

首轮送审历史状态（2026-10-23 15:46 CST）：本地实现与必要验证已完成，等待根独立审查；未 commit、未 push/merge/deploy，不标记55D/Task55/BM04完成。下方历史 checkpoints 中“运行中/尚未HOST/未产品修改”等仅说明当时状态，不代表当前状态。

唯一实施工作树 `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`，分支 `codex/beauty-foundation`；编码 BASE=`612b8c08144d0aaa81959d4c455ab1dcc3d4404d`。最初设计阶段旧HEAD仅留历史。根已批准设计，无第二用户门。根拥有 mainTDD/progress/brief/总体设计/review；本次不纳入这些文件。

实际交付：真实048 Service/DTO → 固定原身份/计划/派生号 → 单事务CREATE准备链接 → 既有资源协议 → 受限签到 → C唯一persist原子写完整WalkInVO；原号scoped纯读/动作恢复；当前第二员工恢复保留原namespace/canonical；后台仅原receipt对账、LIMIT前资格过滤、30秒lease/30或60秒轮转；api JobDeclaration复用原框架。无HTTP/remote接线、trade/pay/部署扩展，无新资源saga或伪造actor。

## 最新验证证据与快照边界

所有数字顺序为 tests/failures/errors/skipped；均读取实际输出，不把setup错误、编译或旧快照当当前行为通过。

| 日志（均 `/private/tmp/`） | 实际结果 | 快照与范围 |
|---|---|---|
| task55d-native-final.log | 21/0/0/0 | 最后行为生产修改（preview/原命令绑定）及消融还原之后，当时WalkInRecoveryIT整类；后来仅增加测试覆盖及格式整理，不能称当前28用例整类跑过 |
| task55d-coverage-final.log | 11/0/0/0 | 后续新增/扩展六方法，atomic参数化6；含RECEIVED第二员工、六点rollback、释放未知/错hold/手工状态、ARRIVAL当前员工、lease/失败60秒、RESERVE待人、重复物理资源份数 |
| task55d-green7b-migration.log | 29/0/0/0 | 五项D修复验证+BeautySchemaMigrationIT整类24；真实当前V1→V7及physical drift，早于仅测试扩展/格式整理 |
| task55d-host-final.log | 1/0/0/0 | install-final最新行为产物之后，新真实member/customer/guest/claim/receipt链；包含claim丢响应后的generic四参恢复 |
| task55d-host1.log | 5/0/0/0 | 较早五项HOST回归；不作为后续绑定修改后的全HOST最终证据 |
| task55d-regression1.log | 26/0/0/0 | BM03 6+A3+D2+B7+C8；后续D绑定收紧由native-final/green7b覆盖，不冒称26是在最后生产快照重跑 |
| task55d-reactor-all-final.log | 118/0/0/0 | 最新普通完整插件reactor：api2/core41/web22/channel25/server28，包含BookingServerWiring/DTO/channel/architecture；默认不含nativeIT |
| task55d-reactor-final.log | 21/0/0/0 | 先前targeted条件装配8+架构13；不是完整普通reactor |
| task55d-i18n-final.log | 6/0/0/0 | BackendI18nParityTest；无新增ErrorCode，failureCode是既有安全恢复原因 |
| task55d-checks-final.log | PASS | 三生成器--check、原SQL gate、V1–V7逐字节比较与SHA、diff whitespace |

最后普通reactor首轮 `task55d-reactor-all.log` 为118/1/0/0：旧BookingServerWiringTest把JobDeclaration限定一项，现真实上下文成功装配两项。修测试为精确验证两个已实现handler对应的declaration，并保留ops/no-api均不注册两Job与declaration；没有放松生产形态约束。完整重跑118全绿。

有效消融 `task55d-ablation-red.log`：仅临时移除 `i.command_hash=JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.commandHash'))` 这一LIMIT前SQL资格行，`invalidBindingsCannotOccupyFirstBatch` 实际50而期望1，1 failure/0 errors，确实由错误绑定占满首批产生；没有stub/no-op/setup冒充。finally恢复后cmp原字节通过，native-final中同方法GREEN。消融后mapper备份SHA=`dc81076526b3df3213e5ee3a4d2b64278d5f0b1ed53500dc59f3a9ba3ecd18d6`；后续仅import整理未改变此SQL。

## AC → 实际方法、证据和边界

下列D方法均位于 `beauty-core/.../scenario/WalkInRecoveryIT.java`，继承共享fixture而非IT。N=task55d-native-final.log，X=task55d-coverage-final.log，H=task55d-host-final.log，R=task55d-regression1.log。

| AC | 实际方法/日志 | 已验证及明确边界 |
|---|---|---|
| 048合同/真实范围/草稿一次 | confirmedWalkInCreatesOneAppointmentScope (N); defaultStartIncludesEveryBeforeBufferAndOffset (N); walkInPreservesRepeatedPhysicalResourceUnits (X) | OPEN/UNBOUND、任务PENDING/无执行事实、单order/context/submission、完整typed结果、重复ROOM1两容量份；多定义2任务。展开qty原共享writer另由C preservesExpandedItemsAndEveryResourceCapacityUnit (R)覆盖 |
| CREATE同TX六点无半链接 | atomicCreatePreparationRollsBackWithoutHalfLinks (X，6参数); concurrentOriginalKeyCreatesOnlyOnePreparationChain (N) | root/fence/current/preview/op插入及outer链接真实MySQL trigger fault，回滚无资源accept；原RECEIVED保留、B恢复；两个独立线程同key只一条链 |
| 最终范围与typed结果同TX | finalScopeAndTypedResultRollBackTogetherThenSecondEmployeeOpens (N) | submission插入故障时order/context/task/submission全零、原OPEN_PENDING/result空，B成功继续。未单独注入JSON序列化/Outbox异常，但两者与结果包装同一既有persist TX；没有另事务改写result |
| 时间/全部buffer/UTC | defaultStartIncludesEveryBeforeBufferAndOffset (N); confirmedWalkInCreatesOneAppointmentScope (N) | null最早5分钟网格、后续项目before80/offset0、全窗口严格未来、明确时间、off-grid拒绝。bookingAhead上限/DTO全部长度极值通过复用校验及源码核查，未新增每个边界的D故障用例 |
| accept/confirm未知/重建 | unknownAcceptLeavesNoScopeAndReplaysPersistedCommand (N); unknownConfirmRestartsWithOriginalCommandAndCurrentEmployee (N) | UNKNOWN无单/tasks，原payload/key/operation/command续行，服务重建、原accept不新增；UNKNOWN active fence与终态已核后clear语义由原协议回归和D拒绝断言共同验证 |
| 当前员工/原namespace | operationQueryIsPureScopedReadAndSecondEmployeeRecoversOriginal (N); atomic... (X); backgroundReconcilesOnlyAcceptedOriginalCommands (X); finalScope... (N) | 原A撤权B按原号分别续RECEIVED/无receipt/ARRIVAL/OPEN；owner收到真实B，原outer.actor仍A。没有伪SecurityContext或要求当前actor=历史creator |
| 成功重放/权限/来源链 | originalReplayChecksRequestNamespaceAndCurrentGrants (N); linkedPreviewNamespaceMustMatchOriginalAppointmentCreator (N); innerAppointmentRecoveryCannotBypassCustomerPermission (N) | 过时原成功结果重放、改参CONFLICT、CUSTOMER撤权、preview creator错配拒绝、错误action/endpoint读取拒绝、inner CREATE不能绕过CUSTOMER。跨店/无grant另有operationQuery；tenant/realm/kind所有排列未逐一新造D测试，依现有Access+完整namespace/SQL guards及源码核对 |
| 过期不重选不发新命令 | expiredSelectedPlanRequiresRescheduleWithoutRematching (N); expiredConfirmedCommandStillReconcilesWithoutArrival (N) | 原冻结start/plan/key保持；已接受CONFIRM仍先reconcile到真实终态，再外层reschedule-PENDING/零单；不新建替代预约/hold |
| 拒绝与真实释放 | reserveRejectionIsFinalButConfirmRejectionRequiresRealRelease (N); releasedOriginalHoldClosesRejectedWalkInOnlyAfterVerifiedRelease (X) | RESERVE无hold才REJECTED，CONFIRM拒绝保持release-required；手工CANCELLED不足、真实RELEASE丢响应需恢复、错hold不足，最终原release/intent/owner RELEASED证明才收口，不新D释放saga |
| 锁外owner RPC | ReceptionFixture ControlledOwner/access/catalog/personnel/schedule callbacks assertions，N/X；HOST真实owner H | 实际路径均assert无本域activeTX；prepare原子写体没有外层套REQUIRES_NEW，owner校验在进入锁链前 |
| 原号纯读/安全分派 | operationQueryIsPureScopedReadAndSecondEmployeeRecoversOriginal (N); openOperationRecoveryRetainsOriginalNamespaceAndCanonical (N); H内generic claim恢复；BM03普通预约 R | reception first scoped SQL，真实action/endpoint，GET不推进；C保持原canonical/key，claim调用已有四参API。SQL异常无catch-fallback由源码确认，未注入SQL连接故障；UNASSIGNED C第二员工新入口未单独故障测试，依同writer/hash及C既有回归 |
| 后台仅原receipt | backgroundReconcilesOnlyAcceptedOriginalCommands (X); backgroundReserveTerminalWaitsForAuthenticatedConfirm (X); recoverySkipsSixtyFiveUserBlockedRows (N) | 清SecurityContext运行，零新accept/execute/guest、无签到/单；CONFIRM最多ARRIVAL，RESERVE对账清receipt停待当前用户CONFIRM；65缺receipt/用户项之前置排除 |
| 有界公平/lease/退避 | resourceRetriesRotateBeyondFirstFifty (X); invalidBindingsCannotOccupyFirstBatch (N+消融RED) | 同schema 60独立业务50+10轮转；活lease排除、完成清lease，未决30秒/异常60秒；65错commandHash不占首批。并发用户已SUCCEEDED与scan finally精确交错未单独压测，代码以phase/version/lease重读及CAS防覆盖 |
| C防回退/父链/孤儿guard | openOperationRecoveryRetainsOriginalNamespaceAndCanonical (N); C八项R | B续C原REPLACE绑定；原C唯一writer、成功同键竞争、canonical冻结、子行父链、实际孤儿task守卫均保留。D详情错endpoint新增反例；其它全父链组合沿C既有scopedRead回归 |
| HOST真实身份消费 | BeautyReceptionIdentityIT.walkInConsumesRealCustomerGuestAndScopedClaimReceipt (H) | 真实member cr1/guest/claim/同scope不可变receipt/当前manager，GUEST历史root/context保留并被USER本人归属消费。catalog/personnel/schedule/resource admission明确是测试fixture；不是部署全资源owner |
| Job/装配/架构 | receptionJobUsesApiProfileAndExistingFramework (N); BeautyConditionalWiringTest / BookingServerWiringTest / BeautyArchitectureTest (reactor-all-final) | api两声明准确、非api无Job、beauty关闭无恢复bean、无环；无内置timer/伪staff。Task60宿主顶层job/domain欠账不在本次修补 |
| 迁移/生成/三语 | BeautySchemaMigrationIT全24 (green7b); checks-final; i18n-final | ready7/27私表未变，V1–V7与BASE逐字节一致，无DDL/迁移；SQL gate只扫backend，**不声称自动覆盖beauty-plugin新SQL** |

## 实际执行命令和运行边界

工作目录均为上述唯一worktree。Native批次前执行 `LIMA_HOME=/Users/robin/.colima/_lima limactl shell --instance colima-hxkey-tests cat /proc/meminfo`，输出 `task55d-preflight*.log`，Python解析并assert MemAvailable>=524288KiB；最新preflight17=3665504KiB。cached mysql:9.7.2、BEAUTY_TEST_DISK=1、普通64M、pullnever、localhost readiness、自有UUID容器串行/finally只清自身；未stop他人/prune/restartVM/远程部署。

```bash
# Native统一前缀；下列各S是实际BEAUTY_TESTS值，不把多个类用+相连
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  BEAUTY_TEST_DISK=1 BEAUTY_TEST_LOW_MEMORY=0 BEAUTY_TESTS="$S" \
  node scripts/verify-beauty-persistence.mjs
```

- N 的 S=`WalkInRecoveryIT`。
- X 的 S=`WalkInRecoveryIT#atomicCreatePreparationRollsBackWithoutHalfLinks+releasedOriginalHoldClosesRejectedWalkInOnlyAfterVerifiedRelease+backgroundReconcilesOnlyAcceptedOriginalCommands+resourceRetriesRotateBeyondFirstFifty+backgroundReserveTerminalWaitsForAuthenticatedConfirm+walkInPreservesRepeatedPhysicalResourceUnits`。
- green7b 的 S=`WalkInRecoveryIT#linkedPreviewNamespaceMustMatchOriginalAppointmentCreator+concurrentOriginalKeyCreatesOnlyOnePreparationChain+confirmedWalkInCreatesOneAppointmentScope+releasedOriginalHoldClosesRejectedWalkInOnlyAfterVerifiedRelease+backgroundReconcilesOnlyAcceptedOriginalCommands,BeautySchemaMigrationIT`（selector由保留Surefire XML property复核）。
- 消融的 S=`WalkInRecoveryIT#invalidBindingsCannotOccupyFirstBatch`。
- R 的 S=`WalkInRecoveryIT#innerAppointmentRecoveryCannotBypassCustomerPermission+originalReplayChecksRequestNamespaceAndCurrentGrants,ReceptionBusinessIT#checkInKeepsScheduleHoldAndNoTasks+replacementUnknownKeepsOriginalCommandAndFence+replacementBeforeBufferMustAlsoRemainInFuture+receptionRetainsRepeatedResourceUnits+checkInRacesCancelOnIndependentConnections,WorkOrderScopeIT#replacementAcceptUnknownReusesDurableOperationAfterRestart+replacementExecuteUnknownReusesDurableOperationAfterRestart+pendingGuestIntentRetainsCanonicalIdentityAfterClaim+successfulConcurrentReplaySkipsOldRevisionAndDoesNotRegressPhase+preservesExpandedItemsAndEveryResourceCapacityUnit+scopedReadRequiresOriginalSucceededOperation+appointmentWithOrphanActualTaskRejectsOpening+fullAppointmentWithOrphanActualTaskRejectsOpening,GuestClaimReceiptIT#sharedClaimCommitThenLocalRollbackRecoversReceiptWithCurrentEmployee+unknownClaimKeepsOriginalIntentAndRegisteredNeedsFreshCurrentProof+receiptCannotRebindAndSuccessReplayNeedsCurrentPermissionButNoFreshTicket,AppointmentBusinessIT#lostAcceptResponseReplaysIdenticalPersistedKeyAndPayload+recoveryReconcilesOriginalWithoutMintingUserThenAwaitsAuthenticatedConfirm+anotherCurrentlyAuthorizedManagerRecoversRevokedInitiatorsExactCommand+confirmRejectionRequiresActualReleaseAndNoMoneySourceMayBypassProof+guestCreationReplaysOriginalOperationAfterClaim`。

```bash
# task55d-install-final.log（H前最新行为构建）
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 \
  -f backend-plugins/shop-industry-beauty/pom.xml install -DskipTests
# task55d-host-final.log
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home BEAUTY_TEST_DISK=1 \
  BEAUTY_RECEPTION_TESTS='BeautyReceptionIdentityIT#walkInConsumesRealCustomerGuestAndScopedClaimReceipt' \
  node scripts/verify-member-guest-registration.mjs --beauty-reception
# task55d-reactor-all-final.log
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 \
  -f backend-plugins/shop-industry-beauty/pom.xml test
# task55d-i18n-final.log
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am \
  test -Dtest=BackendI18nParityTest -Dsurefire.failIfNoSpecifiedTests=false
python3 scripts/gen-beauty-java-method-design.py --check
python3 scripts/gen-beauty-api-design.py --check
python3 scripts/gen-beauty-database-design.py --check
node scripts/check-sql-portability.mjs
git diff --check
```

生成器实际写入API/Java方法清单及数据库字典；数据库源是beauty-reception-schema.json新增非DDL恢复合同，未运行冻结迁移生成器。原SQL checker 162 hits/51 files/54既有欠账通过，仅backend扫描范围；新mapper SQL另以真实MySQL执行和手工共同语法核查JOIN/JSON_EXTRACT/JSON_UNQUOTE/JSON_TYPE/LENGTH/LIMIT/普通UPDATE。未实际运行MariaDB；不伪称双引擎验证。

## 自审与审查快照

已核对原CREATE短TX锁序root→fence→current→outer、现C单writer无反依赖、owner锁外、原receipt/intent核验、实际当前actor、typed结果同TX、SQL前置资格及Java权威复核、终于清lease和显式null更新、动作/endpoint父链、无票据进入持久payload。全部新Java import排序/无通配/新增行≤120整理；之后仅格式变化，不扩大行为测试结论。没有修改owner API、迁移、root TDD/progress或Task60棘轮。

剩余限制为上表明确未单独注入/穷举的情形；本报告不将源码审查等同实测。需要根独立审查当前具体diff后才能提交。根最新指示要求精确stage为未commit审查tree；stage前真实index为空，仅stage下列精确owned路径，不纳根脏文件。树hash/diff路径由交接消息与 `/private/tmp/task55d-review-manifest.txt` 给出，避免报告自包含树hash递归。

## 精确 owned 文件（R1当前35文件）

- `.superpowers/sdd/beauty-backend/task-55d-design.md`
- `.superpowers/sdd/beauty-backend/task-55d-report.md`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/OperationVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/ReceiveWalkInReq.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/WalkInVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/service/ReceptionService.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyAppointmentConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyReceptionConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/job/ReceptionRecoveryJob.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/mapper/BeautyMappers.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/policy/ReceptionIdentity.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentCoordinator.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentMatching.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentPreparationService.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionCoordinator.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionPreparation.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionRecovery.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionServiceImpl.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceOperationQueryServiceImpl.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceOperationRecoveryServiceImpl.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderCoordinator.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderPreparation.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/BeautyConditionalWiringTest.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ReceptionFixture.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/WalkInRecoveryIT.java`
- `backend-plugins/shop-industry-beauty/beauty-server/src/test/java/ai/neargo/shop/beauty/scenario/BookingServerWiringTest.java`
- `backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautyReceptionIdentityIT.java`
- `docs/technical/design/schema/beauty-api-design.json`
- `docs/technical/design/schema/beauty-java-method-design.json`
- `docs/technical/design/schema/beauty-reception-schema.json`
- `docs/technical/design/美业系统-API清单.md`
- `docs/technical/design/美业系统-Controller-Service-Mapper方法清单.md`
- `docs/technical/design/美业系统-Java类与方法清单.md`
- `docs/technical/design/美业系统-数据库字段字典.md`
- `scripts/gen-beauty-database-design.py`

---

以下仅历史记录；当前结论以上方验收表为准。

# 历史记录：初始设计与实施 checkpoints

状态：根技术核对通过；恢复实施中，尚未提交或完成审查。2026-10-23。初始设计阶段历史如下，实际实施证据见末尾。

工作树 `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`，分支 `codex/beauty-foundation`，BASE/只读起始HEAD `1608709c3e35318b750449641af83b7c8e3c35e0`。前置55C产品22e1d8938已审；ready7/27私表，V1–V7冻结。

本轮owned仅：

- `.superpowers/sdd/beauty-backend/task-55d-design.md`
- `.superpowers/sdd/beauty-backend/task-55d-report.md`

未修改根mainTDD/progress/brief/总体design/review；开始时已有progress.md根改动，未覆盖。两份新文档受仓库.gitignore第42行`.superpowers/`忽略（已用git check-ignore核实），文件真实存在，未force-add。未stage/commit、未产品修改、未启动容器/测试、未改schema。

规格绑定与核对：先读task-55d-brief.md，再仅Task55主TDD条目、task-55-design.md指定§3/7.2/8/9/10及ADR045；项目SKILL与Java规范。核实当前AppointmentPreparationService/Coordinator/Matching/Persistence、ReceptionCoordinator/Preparation/Reassignment、WorkOrderCoordinator/Preparation/Query、真实Operation Services/DTO、GuestRegistrationServiceImpl四参recoverClaim、JobHandler/JobDeclaration现有模式、ResourceBookingPort SPI、ReceptionOperationMapper、V5/V6/V7及测试/runner真实selectors。仅阅读生产源码，测试源码用于selector与夹具边界核对。

关键设计：

1. 真实048方法/DTO及OperationVO尾增2字段；没有假wire/空Remote交付。
2. RECEIVED先固定规范身份、派生key与root/preview/op候选号；锁外匹配，冻结事务仅外层。新CREATE单短TX插/锁固定root→fence→current→outer并原子建preview/op/Accept和链接，重复root竞争在失败TX结束后回读原outer；全回滚无孤儿链接，无REQUIRES_NEW嵌套。
3. 时间模拟所有展开项目offset/before/after，求所有资源区间严格未来的最早5分钟服务网格；冻结后不重选。资源UNKNOWN零工单/task，当前第二员工真实actor沿原key/bytes/commandNo。
4. 内部签到只接受048原绑定+成功hold；openReception复用C唯一openPrepared→persist。persist同TX按实际action包装WalkInVO，成功父链仅扩准确动作集合，保留原scope/context/root/identity守卫；B/C详情和原子失败有AC。
5. 原号GET纯读scoped SQL reception first，动作类型分派；恢复先当前权限，claim复用真实recoverClaim(entity,store,actor,operationNo)→GuestClaimVO；C原操作恢复保持原namespace/payload/canonical。
6. 后台只已receipt且可真实reconcile的RESOURCE_PENDING；LIMIT前排用户项/RELEASE_REQUIRED，lease30秒、未决30秒/异常60秒、公平轮转；只recover，不guest.get/accept/execute/签到/开单；RESERVE对账后待用户CONFIRM。
7. 根已裁决fence：UNKNOWN保留active pointer/current；明确终态phase3/hash核后既有协议清pointer合法，永久row/原hold仍保留。人工现有CANCEL/RELEASE后，D复用原verify/terminalHold核原hold RELEASED及完整原释放链再REJECTED；缺证据仍PENDING，不新建释放saga。

待根技术核对的实施选择：CREATE固定root唯一键串行化及TX外回读；唯一persist的typed结果包装；SQL资格完整binding过滤；已接受CONFIRM先recover、仍ACCEPTED时当前人继续同原command执行（不新accept）。无新增业务裁决/新owner API需求；fence语义已按根裁决写入。若根不同意上述具体实现，请在设计范围内指出准确替代；本代理在技术放行前停止产品实现。

设计§8区分了拟新增WalkInRecoveryIT/HOST方法与真实存在的B/C/A/BM03/迁移/wiring回归名。当前所有selector均未运行，测试统计=未执行，未把编译/环境问题或前置历史结果当D业务RED/GREEN。以后报告会分别记录前置TDD RED、后验有效消融、GREEN、native配置和完整日志。

只读核对及文档检查输出：`/tmp/task55d-design-readonly-evidence.log`（本轮收尾只运行git/sha/行数/无产品修改检查，无Maven/容器）。`git diff --check`仅检查tracked diff，新设计/报告另做行尾空白/文件存在检查；此设计非产品验收。源代码阅读工具输出在当前会话；此日志不声称完整重放所有cat/rg。产品owned候选清单和实际后续命令模板在design§9/§8.2，最终以实际diff收敛。

## 实施启动

根技术放行，编码BASE=612b8c08144d0aaa81959d4c455ab1dcc3d4404d。沿executing-plans本人inline执行；首轮先补可编译的DTO/入口签名边界（不计业务实现），新增真实MySQL行为用例后记录缺行为RED。Colima只读preflight初次sandbox拒socket后，授权只读实测MemAvailable=3698708KiB≥512MiB；未重启VM/清他人容器。

## 恢复实施证据（2026-10-23 14:55 CST）

- 接手续原partial，BASE=612b8c08144d0aaa81959d4c455ab1dcc3d4404d；不reset，不重做55C，不改根mainTDD/progress。
- 前置真实RED `/private/tmp/task55d-red1.log`：WalkInRecoveryIT 2 tests / 2 failures / 0 errors，entry行为stub导致；根已独立核读，结束14:43:17，早于部分helper写入14:45。恢复代理没有将未亲历的历史扩写为更多RED。
- 编译检查 `/private/tmp/task55d-resume-compile1.log`：失败为partial prepareLocked提取漏current局部引用；补同TX读取后解决。不是业务RED。
- 当前首GREEN `/private/tmp/task55d-green1.log`：`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home BEAUTY_TEST_DISK=1 BEAUTY_TEST_LOW_MEMORY=0 BEAUTY_TESTS=WalkInRecoveryIT node scripts/verify-beauty-persistence.mjs`；2 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS，14:55:27。仅覆盖confirmedWalkInCreatesOneAppointmentScope及unknownAcceptLeavesNoScopeAndReplaysPersistedCommand；尚不代表D其余AC完成。
- 此批preflight实测MemAvailable=3696504KiB（>=512MiB），默认cached MySQL9.7.2/64M，单UUID runner finally清自身，未停止他人/改VM。
- 此阶段实现：048入口→冻结身份/计划→原子CREATE→既有资源协调→受限签到→C唯一persist同TX写WalkInVO；继承partial安全父链读取。原号公共分派、后台Job、公平性、HOST、生成物与其余AC仍待实施/验证。

## 历史 checkpoint 15:02 CST

- red2: /private/tmp/task55d-red2.log, 2 tests / 2 failures / 0 errors. GET original reception number returned NOT_FOUND; explicit RESERVE rejection stayed PENDING.
- green2: /private/tmp/task55d-green2.log, all current WalkInRecoveryIT 4 tests / 0 failures / 0 errors / 0 skipped, BUILD SUCCESS at 14:58:58. Covers reception-first read/current second employee continuation, immutable namespace, reserve rejection and confirm release-required.
- red3: /private/tmp/task55d-red3.log, background 3 tests / 3 failures / 0 errors. Stub scan returned zero instead of 1, 1, 50. Fairness seeds 65 blocked + 1 eligible and 60 eligible in one schema per method.
- green3 log /private/tmp/task55d-green3.log currently running, not yet a claimed pass. Implementation uses only AppointmentCoordinator.recover; SQL eligibility precedes LIMIT and Java verifies full receipt/intent. Lease30s, retry30s/failure60s.
- Per-batch memory evidence /private/tmp/task55d-preflight2.log through preflight5.log; every batch asserted MemAvailable>=524288KiB. Same JDK21, DISK=1, LOW_MEMORY=0, cached mysql9.7.2 isolated UUID runner.
- C recover entry currently has indirect compilation coverage, not a claim of complete pending-C second-employee verification. Job declaration, remaining AC, HOST, regression, ablation and generated docs remain.

Current owned product candidates (not staged/committed; task design/report also owned):
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/OperationVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/service/ReceptionService.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyAppointmentConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyReceptionConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/mapper/BeautyMappers.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/policy/ReceptionIdentity.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentCoordinator.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentMatching.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentPreparationService.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionCoordinator.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionPreparation.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionServiceImpl.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceOperationQueryServiceImpl.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceOperationRecoveryServiceImpl.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderCoordinator.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ReceptionFixture.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/ReceiveWalkInReq.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/WalkInVO.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionRecovery.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/WalkInRecoveryIT.java`

## 历史 checkpoint 15:13 CST

- green3 completed 3/0/0/0 at 15:02:40.
- red4 /private/tmp/task55d-red4.log = 4 failures / 0 errors: released original remains PENDING, second employee C recovery CONFLICT, 65 wrong commandHash rows consume LIMIT50, missing API reception job. green4 /private/tmp/task55d-green4.log = 4/0/0/0.
- Queue SQL now joins original intent and verifies matching commandHash before LIMIT; Java remains authority verifier.
- boundaries1 /private/tmp/task55d-boundaries1.log = 5 tests / 0 failures / 1 setup error. Four behavioral passes: preparation rollback, final scope rollback/current second employee, expired frozen plan, confirm unknown plus service rebuild. Invalid negative requirement offset in the timing test was a fixture error (existing DTO forbids it), not RED. Corrected to legal second-item before80/offset0.
- red5 /private/tmp/task55d-red5.log = 2 tests / 1 failure / 0 errors. Legal default time test passes; expired accepted CONFIRM must reconcile original appointment to SUCCEEDED while outer remains reschedule-PENDING. Implementation now reconciles accepted effects before expiry prevents new effects.
- HOST test added in BeautyReceptionIdentityIT with real member cr1/guest/claim/receipt/current permissions and test-only catalog/personnel/schedule/resource admission fixtures; not yet executed, no HOST success claim.

## 历史 checkpoint 15:24 CST

- Full current native /private/tmp/task55d-native1.log: 17 tests / 0 failures / 0 errors / 0 skipped, BUILD SUCCESS at 15:19:42.
- Current plugin offline install /private/tmp/task55d-install1.log: complete reactor BUILD SUCCESS.
- HOST /private/tmp/task55d-host1.log: BeautyReceptionIdentityIT#walkInConsumesRealCustomerGuestAndScopedClaimReceipt+workOrderOpeningUsesRealCustomerAndGuestFacts+omittedGuestIdentityUsesCurrentOwnerAndScopedReceipt+realClaimUnknownRecoversImmutableReceiptUnderCurrentManagerWithoutOldProofOrImpersonation = 5 / 0 / 0 / 0 (omittedGuest parameterized two cases), BUILD SUCCESS 15:21:27. Genuine member/claim/receipt/grants; test-only directory/personnel/schedule/resource admission.
- red6 /private/tmp/task55d-red6.log: 2 tests / 1 failure / 0 errors. Passing original success replay/changed-request/revoked-CUSTOMER/source endpoint mismatch; failing innerAppointmentRecoveryCannotBypassCustomerPermission demonstrated inner appointment number bypass. Now routes a scoped linked CREATE back through the original 048 operation.
- /private/tmp/task55d-regression1.log currently running: those two plus exact approved A/B/C/BM03 selected regressions.
- Contract sources/generated API and Java method docs synchronized. Reception persistence increment is beauty-reception-schema.json (base 41-table model contains no reception operation); added non-DDL behavior metadata and database dictionary rendering. No schema migration generator run; V1-V7 diff remains empty.

## Binding self-review checkpoint

- regression1 completed 26/0/0/0 (BM03 6, A3, D2, B7, C8; parameterization explains counts beyond selector names).
- red7 /private/tmp/task55d-red7.log: 2 tests / 1 failure / 0 errors. Same-key concurrent CREATE produces one chain; corrupted preview creator namespace was wrongly accepted. Added full persisted preview/actor/canonical/scope/key/plan validation before owner IO.
- green7 first attempt /private/tmp/task55d-green7.log: 5 tests / 2 failures / 1 error, implementation regression from incorrectly requiring CONFIRM sourceRevision==1. Existing root optimistic version advances after RESERVE; corrected to RESERVE initial1 / CONFIRM later revision, with original receipt+intent authority verification retained. This is not an additional pre-implementation RED claim.
