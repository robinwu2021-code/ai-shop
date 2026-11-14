# Task60A 代码质量收尾设计（仅设计候选）

2026-10-24；DESIGN_BASE `920482037595e39d3db7408758430511b39a8531`；已接受产品 Task59 `c9d9518798ee74f93d7a1f899d356c4760d57b43`。唯一工作树 `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`，分支 `codex/beauty-foundation`。

本设计唯一需求源为 `task-60a-brief.md`，证据索引见同目录 `task-60a-report.md`。本轮未运行构建、测试、native、生成器或部署，也未修改产品。本文件不是实施 release。每片由根核对、同步主TDD §2 后给独立 IMPLEMENTATION_BASE；不以本文赋予任何现有源文件写权限。

## 1. 档位、方案与总验收

档位1：既有权限登记/i18n契约对账；没有新域、营业功能、表族或新业务决策。纯迁包、测试及解析器片本身为0档。复用原业务 Service/Impl、本域Mapper、共享owner与同一SQL规则。正常import、4空格、新改行≤120、公开API有解释原因的Javadoc。

推荐方案：11个小片顺序交付；先A1架构，再安全runner，随后测试/schema/SQL，最后真实组合与词条/生成/M1/M2。每片独立测试、消融、审查；A7与A10刻意独立，前者是必要隔离native集成，后者实际HTTP拓扑执行仍后置。替代方案“全治理一次提交”会混淆失败归属；“先做全模块部署”违背用户统一部署顺序；均不采用。费用主要是一次真实资源夹具建设及多片有限审查，不重复无关全量native。

AC编号沿本任务局部映射：AC1锁/域/FQN；AC2严格selector/秘密/XML；AC3合法竞争；AC4冻结SQL/解析/12表；AC5永久SQL扫描；AC6真实CLAIMED→USER+REPLACE；AC7词条与权限生成对账；AC8Task59 M1；AC9告警归因。Task59 I1/I2已经关闭，不重开。

根需同步三处：主TDD §0将上述AC映射原Task60与本设计切片；§2逐片owned/接口/依赖；§5测试方法与真实结果、最小消融；完成时§6实际diff与偏差。本文不能把设计状态写为已实现。API160目标、共用owner所有权及BM05关闭状态保持；具体审计与发现不成立的旧假设见报告。

## 2. 通用命令与证据口径（均为未来执行计划）

以下命令均从唯一工作树执行。为避免重复，`H`、`P`、`V`为这里精确定义的shell函数，不是待填占位符：

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
H() { "$JAVA_HOME/bin/java" -version >/dev/null 2>&1; JAVA_HOME="$JAVA_HOME" mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am "$@"; }
P() { JAVA_HOME="$JAVA_HOME" mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml -am "$@"; }
V() { pnpm --dir packages/shared exec vitest run "$@"; }
```

每片先新增有意义断言并运行下面selector，记录真实行为RED；首次已绿不能伪造RED，用受控最小消融证明测试会失败，恢复精确bytes再GREEN。编译失败、环境启动失败不记行为RED。每次目标XML新鲜、tests>0、failures/errors/skips=0；普通native禁用skip不算native通过。必要构件刷新严格限本工作树自身输出/批准缓存，不覆盖正在运行的JAR，不删别人缓存。根授予实际执行gate之前不跑任何命令。

native：cached `mysql:9.7.2 --pull never`，`colima-hxkey-tests`/`hxkey-tests`，普通DISK64M、MemAvailable≥524288KiB、串行自有UUID/随机loopback/环境秘密/finally只清自己，不改VM、不连共享或远程库。旧checkout runner在A2修好前不运行。冻结beauty V1–8、平台V537–540与所有已执行迁移。当前最大号beauty8/平台540；本方案不需要新迁移。若实际发现必须DDL，停该依赖片，根先核最新最大号、完整SQL、bytes/SHA/升级与回滚策略，再给actual-file首次执行gate。

路径缩写仅用于下文精确owned清单：`HA=backend/shop-app`，`BA=backend-plugins/shop-industry-beauty/beauty-api`，`BC=backend-plugins/shop-industry-beauty/beauty-core`，`BS=backend-plugins/shop-industry-beauty/beauty-server`；`J=src/main/java/ai/neargo/shop`，`T=src/test/java/ai/neargo/shop`。展开后均为仓库相对路径。

## 3. 独立切片

### A1 有界架构（建议首先release，AC1）

Owned仅以下7个逻辑文件（移动前后共8个路径）；JobHandlerEndpointTest只执行、不编辑：

- 旧 `backend/shop-store-mybatis/src/main/java/ai/neargo/shop/store/config/JobLockConfiguration.java` → 新 `backend/shop-store-mybatis/src/main/java/ai/neargo/shop/job/config/JobLockConfiguration.java`。
- `backend/shop-app/src/main/java/ai/neargo/shop/config/ShedLockConfig.java`。
- `backend-plugins/shop-industry-beauty/beauty-server/src/main/java/ai/neargo/shop/beauty/server/config/BeautyJobTargetConfiguration.java`。
- `backend/shop-app/src/main/java/ai/neargo/shop/portal/ops/pay/OpsPointsInspectionController.java`。
- `backend/shop-app/src/test/java/ai/neargo/shop/arch/ArchitectureTest.java`。
- `backend/shop-app/src/test/java/ai/neargo/shop/scenario/JobTargetProfileTest.java`。
- `backend-plugins/shop-industry-beauty/beauty-server/src/test/java/ai/neargo/shop/beauty/scenario/BeautyJobTargetTest.java`。

接口：保留 `lockProvider(DataSource):LockProvider`、`lockingTaskExecutor(LockProvider):LockingTaskExecutor`、Bean名及usingDbTime；仅类包名改变。HOST ShedLockConfig仍无条件Import，既有DataSource依赖不变。独立SERVER的disabled-no-DataSource来自BeautyJobTargetConfiguration外层条件，不能顺手给锁配置增加条件或改默认开关。beauty加入唯一DOMAINS列表，不进infra；Ops只加正常PathVariable import。不修改POM/配置键/SQL/known棘轮。

测试：新增 `JobTargetProfileTest.hasExactlyOneLockProviderAndExecutor`、`BeautyJobTargetTest.enabledServerHasOneLockProviderAndExecutor`，各自真实装配上下文的 `getBeansOfType(LockProvider.class)` 和 `getBeansOfType(LockingTaskExecutor.class)` 均为size=1，不比较实现包名字串。保留HOST锁可用、route/token行为及SERVER `oneFrameworkRouteSetRequiresJobTokenAndRunsWithSharedDatabaseLock`。扩展既有 `disabledServerDoesNotRequireDatabaseForAnInactiveJobTarget` 为三个明确参数场景：api下beauty=false/job=true、beauty=true/job=false、beauty=false/job=false；每个都hasNotFailed、无DataSource、无job target及锁provider/executor。复用现方法/上下文，不新建同义壳。`nonApiShapesDoNotExposeAnInertJobEndpoint`保留覆盖ops/无api。包边界由原ArchitectureTest.topLevelPackagesMustBeRegistered负责，原store/beauty未登记失败就是迁包/登记行为RED。

新增 `ArchitectureTest.embeddedBeautyClassesArePresentWhenRequested`，明确使用 `@EnabledIfSystemProperty(named = "beauty.arch.required", matches = "true")`；方法直接对本次ArchUnit导入集合断言包含实际beauty业务类WorkOrderServiceImpl，不允许条件不符后无断言return。默认非embedded选择会明确显示条件禁用，不能把skip当embedded覆盖；本片HOST命令始终带属性true，该方法必须实际执行。原域间、Web运行时、Port与Service规则原样执行。

准确执行顺序（仅未来root release后；各命令从唯一工作树根执行，JAVA_HOME沿§2）：

1. 确认本任务三个target目录无运行进程/JAR占用；有占用就报告阻塞，不停止他人、不改命令绕过。记录依赖classpath来源。只写上列测试断言，产品保持旧包/旧DOMAINS/FQN。
2. 按下面三条命令依次刷新**仅shop-store-mybatis、shop-app、beauty-server**的target并取得可信RED。第一条install是构建准备；HOST原Architecture规则应报store/beauty登记和Ops FQN，SERVER已有行为应保持。HOST非零后仍单独执行第三条并分别记录。没有`-am`，其余模块均不clean，依赖使用Task59已验收离线缓存。缓存缺失/版本不匹配属setup阻塞，交根处理，不扩大clean/install范围。

```sh
JAVA_HOME="$JAVA_HOME" mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/shop-store-mybatis/pom.xml clean install -DskipTests
JAVA_HOME="$JAVA_HOME" mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -Pbeauty-embedded clean test -Dbeauty.arch.required=true -Dtest=ArchitectureTest,JobTargetProfileTest,JobHandlerEndpointTest
JAVA_HOME="$JAVA_HOME" mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml -pl beauty-server clean test -Dtest=BeautyJobTargetTest
```

3. 只实施迁包、两个import、正常PathVariable import和DOMAINS beauty登记，再按**同三条、同顺序**执行：store clean install移除旧class并刷新本任务缓存JAR；HOST embedded clean test验证实际业务类、原架构规则、锁/token；SERVER clean test验证新import后独立开关及唯一Bean。检查store JAR无旧store.config.JobLockConfiguration且新类唯一，HOST/SERVER实际classpath使用刷新后JAR；这是构件卫生证据，不另写包名镜像测试。三个target只属于本工作树，不clean其它模块/工作树/整个缓存。
4. 最小消融从DOMAINS撤去beauty（保留新锁包），仅重跑上述HOST命令，原topLevelPackagesMustBeRegistered应报beauty违规；恢复精确bytes后同HOST命令GREEN。不重复未受该消融影响的SERVER测试。实际套件XML必须新鲜、非零、零failure/error/skip；保存RED/GREEN/消融及JAR SHA，skipTests安装日志仅算构建证据。

若ArchUnit暴露其它实际beauty越界，保留精确失败交根切A1b，不放宽规则/known、不预授权其它产品文件。A1独立审查后才进入A2；A2–A11仍为后续细化候选，当前不release。

### A2 runner安全与选中证据（AC2）

Owned：`scripts/verify-checkout-payment.mjs`；新增 `scripts/tests/checkout-payment-runner.test.mjs`。不改生产Java或SQL。

接口：导出纯 `validateSelection(mode, selected)` 返回 `{mode,selectors,classes,requestedMethods}`，`inspectReport(xml, requested)` 返回counts；`main(argv, env, effects)`仅CLI入口调用，effects覆盖read/provision/exec等副作用以便验证非法选择时调用数0。沿现有14个payment类和一个BeautySourceCheckoutIT类；方法名使用各类显式允许名单（来自各现存@Test方法），参数化方法按真实Java方法名匹配。保留既有合法Maven `Class#method1+method2` 能力，不以strict校验为由删掉它。

逐mode语法：beauty-source仅一个 `BeautySourceCheckoutIT` 类selector，允许整类、`BeautySourceCheckoutIT#method`、`BeautySourceCheckoutIT#method1+method2`；和原regex一致，不接受逗号多项或其它类。payment允许14类中任意一个整类/单方法/`+`多方法selector，也允许逗号分隔这些selector组成多类选择；同类可分多个逗号项指定互不重叠方法，保留原runner合法能力。每个显式方法逐一验允许名单，解析`+`得到每类requestedMethods集合；原合法字符串原样传Maven，不强制改成其它分隔形式。

env未设置才取旧默认。拒绝显式空/空白、逗号空项、未知类/方法、跨mode混合、wildcard和未知CLI参数；拒绝空`#`、前导/尾随/连续`+`、重复方法（含跨同类逗号项重复）、重复整类项，以及同类整类与方法混用造成重复选择。正例覆盖两mode的`+`选择、payment多类及同类不重叠逗号方法；反例覆盖`Class#m+`、`Class#+m`、`Class#m++n`、`Class#m+m`、`Class#m+unknown`。验证位于文件read、UUID/秘密创建、工具探测、memory、Docker之前。

秘密复用BM04 env模式：子进程env放MYSQL_ROOT_PASSWORD/MYSQL_PWD，argv只传 `--env MYSQL_ROOT_PASSWORD`/`--env MYSQL_PWD`，SQL经stdin；错误脱敏保留。每类run前删旧XML并记录本轮起点；run后XML必须存在且新鲜，每个显式方法（含每个`+`分量和同类逗号项）均出现在该类testcase，非零且零失败/错误/skip，非零Maven退出永不成功。

新增测试名：`rejectsInvalidSelectionBeforeAnyEffect`（表驱动全部上述输入）、`usesDefaultsOnlyWhenVariableIsAbsent`、`keepsSecretsOutOfAllChildArguments`、`rejectsStaleZeroSkippedAndMissingMethodReports`、`acceptsEachKnownClassAndExplicitMethod`、`preservesPlusSelectionsAndChecksEveryRequestedMethod`、`acceptsPaymentMultiClassAndDisjointSameClassSelections`。RED/GREEN `node --test scripts/tests/checkout-payment-runner.test.mjs`。消融为空值回退或去掉XML skip校验即红；安全fake effects捕获argv，不执行旧路径。最后一个既有最小native方法仅在A2安全检查通过后，用 `CHECKOUT_PAYMENT_TESTS=BeautySourceCheckoutIT#realGuestClaimThenCurrentConsumerPurchaseFeedsBindingAndBill node scripts/verify-checkout-payment.mjs --beauty-source` 证明真实非零XML/cleanup，已有业务断言不扩展。

### A3跨动作竞争夹具（AC3，依赖A2或现有安全aliases runner）

Owned：`BC/T/beauty/scenario/ServiceSubmissionIT.java`；`scripts/verify-beauty-bm04-wire.mjs` 仅aliases显式方法白名单增加现有三race方法与新定向winner测试；`scripts/tests/beauty-bm04-wire-runner.test.mjs`同一选择边界。

不改业务接口。`raceEndpointKey(boolean changed, boolean added)`保存每个请求与结果（含完整ServiceSubmissionVO），根据成功结果识别实际赢家，查同scope/key中 `action IN ('SUBMIT_SOURCE','ADD_SOURCE')` 恰好1，唯一prepare/来源。验证行action、request hash、winner原始receipt及重放完全相等；loser冲突、同请求重放和不变量保持。不得固定SUBMIT_SOURCE获胜。

新增 `crossActionRaceRetainsEitherWinnersImmutableReceipt` 以两参数分别先放行不同调用，仅作为可重复覆盖两个合法结果；原真实无偏好并发测试保留。原三方法包括 `concurrentSameEndpointKeyReplaysOneCommittedSubmission`、`concurrentChangedEndpointPayloadHasOneWinner`、`concurrentDerivedActionsShareOnePermanentEndpointKey`（当前源码:1073已核定）。RED/GREEN `BEAUTY_BM04_TESTS=ServiceSubmissionIT node scripts/verify-beauty-bm04-wire.mjs --mode=aliases`，有限整类含原回放/冲突。首RED应是ADD赢家下旧固定SUBMIT断言，不能碰运气声称稳定复现；消融丢winner receipt核对时篡改回执负例必须红。

### A4 CHECK/列解析（AC4部分）

Owned：`scripts/lib/ddl.mjs`、`scripts/tests/ddl-column-definitions.test.mjs`；`HA/T/infra/SchemaDriftTest.java`、`HA/T/infra/SchemaGeneratorTest.java`；必要时 `scripts/tests/test-schema-constraints.test.mjs`。`backend/scripts/gen-test-schema.py`目前已按续行合并且保留CHECK，不能默认授权改它；只有真实RED指向它才给根最小增量。不改V506/V517/V533，也不手改schema-test.sql。

`readSchema(root, dirs)`接口不变。最小解法是在CREATE body按引号/注释/括号深度把顶层逗号分隔成定义，约束整项跳过；列类型必须有完整token边界，CHAR不能误认CHAR_LENGTH。这同时处理inline字段与多行CHECK，不用给AND加黑名单掩盖后续token。现存600收尾上界保留，线性扫描；非法未闭引号/括号报确定错误。Java schema guard复用同样结构原则但不依赖缩进宽度；不以宽泛 `\s+` + 任意大写单词修一处漏另一处。

现有 `column lexer separates inline fields without treating multiline CHECK clauses as columns` 是直接RED候选，新增 `frozen V506 checks remain constraints at one-space indentation`、`multiline V517 and V533 checks cannot become AND columns`、`malformed definitions fail without swallowing the next table`；Java新增 `singleSpaceColumnsAndMultilineChecksAreParsedStructurally`（SchemaDriftTest）与 `checksAreNotColumns`（SchemaGeneratorTest）。命令：`node --test scripts/tests/ddl-column-definitions.test.mjs scripts/tests/test-schema-constraints.test.mjs`；`H test -Dtest=SchemaDriftTest,SchemaGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false`；`V tests/entity-alignment.test.ts tests/schema-lineage.test.ts`（12表债归A5，明确拆分结果，不把整个失败套件记绿）。消融恢复逐行解析必须恢复AND/漏inline字段失败。

### A5严格12项非ORM表登记（AC4其余，依赖A4）

Owned仅 `packages/shared/tests/entity-alignment.test.ts`。接口仍为精确 `NO_ENTITY_YET: Record<table,reason>`。**本次新增键差集恰好报告中的12项；整张record并非只许12项。** 原有points/logistics等合法精确登记及理由保留，不删除、不重新分类；逐项新增12个owner读写理由。不加prefix/wildcard IGNORE、不虚构无用Entity/Repository、不扩充known-*。审查将最终record与该片IMPLEMENTATION_BASE作键/值diff，要求新增恰好12且原有键值不变；这是diff验收，不复制整张名单写自证测试。

抽取既有真实表守卫使用的纯 `unregisteredBusinessTables(schemaTables, entities, registrations):string[]`，原“每张业务表都有实体”断言也必须调用它，保持原精确IGNORE逻辑。新增行为测试 `登记的非ORM表通过分类而未登记的新业务表仍被拒绝`：以实际解析schema表集合/实体集合/当前record为输入，加入不存在Entity及登记的 `quality_unregistered_business_probe`，返回集合必须只含该probe；再从registrations副本删除一个本次真实新增项，结果必须同时包含probe与被删真实表。该路径直接供真实守卫失败断言使用，证明未知表不会被prefix豁免，非仅比较复制名单。probe仅内存模型，不创建迁移。新增 `既有非ORM登记与新增登记共同参与表分类` 覆盖原有points/logistics代表及新增owner表；原stale登记测试、实体列对齐保持。

RED/GREEN `V tests/entity-alignment.test.ts`，消融删一个精确登记仍红；加无归属probe也红。代价是精确登记的维护，不改业务持久策略。当前旧报告里stock_sync_alignment/ord_refund_coupon_obligation不是真实表名，不能照抄。

### A6 SQL永久覆盖（AC5）

Owned：`scripts/check-sql-portability.mjs`；新增 `scripts/tests/sql-portability-scope.test.mjs`。接口导出 `scanSqlPortability(root, scanRoots)`复用唯一RULES/mask/known判定；CLI固定root为现有backend和 `backend-plugins/shop-industry-beauty`，调用者不可借任意目录排除其中之一。无第二规则集，无baseline修改。

新增 `beautySqlAndJavaUseTheSamePortabilityRules`、`badBeautySqlFailsCliCheck`、`sqlNoiseDoesNotCreateHits`。测试在自有tmp仓库布局放美业 `CREATE INDEX IF NOT EXISTS ...` 真坏SQL，调用相同扫描入口/CLI包装断言精确file+rule与exit1；再放Java SQL文本块，证明规则同源。生产扫描不能把tests坏夹具永久当迁移；坏内容由测试运行时创建，finally只清tmp。

RED/GREEN `node --test scripts/tests/sql-portability-scope.test.mjs`；`node scripts/check-sql-portability.mjs --check`。消融去掉beauty scan root，负例红。必须执行--check而非仅输出命中的非阻断模式；known-sql-dialect冻结hash见报告。

### A7真实身份+资源REPLACE组合（AC6，依赖A1/A2/A4；必要隔离native，不是部署）

Owned新增 `HA/T/scenario/BeautyClaimedGuestResourceIT.java`；修改 `scripts/verify-checkout-payment.mjs` 的beauty-source严格类/方法映射，及A2测试对应正例。使用与BeautySourceCheckoutIT同类in-process Spring嵌入装配和native环境，默认没有执行条件即skip必须清楚标注，实际runner执行时非零零skip。不要继承BeautyReceptionIdentityIT的FaultConfiguration/ArrivalHoldFixture；不用手工写mch_booking_*或bty当前安排来假装真实资源动作。

接口仅消费实际 `GuestRegistrationService.registerGuest/claimGuest`、`MemberCodeResolveService.issue/resolve`、`AppointmentService.previewAppointment(entityNo,currentStoreNo,actorNo,PreviewAppointmentReq):AppointmentPreviewVO`及`createAppointment(entityNo,currentStoreNo,actorNo,CreateAppointmentReq,key):AppointmentVO`（其既有内部链负责CONFIRM，不存在额外公开confirm方法）、`ReceptionService.checkIn`、`WorkOrderService.openWorkOrder(entity,store,actor,OpenWorkOrderReq,key)`；资源仍由既有 ResourceBookingPortImpl→ResourceBookingServiceImpl/ResourceCommandServiceImpl→ResourceSourceAuthorizationServiceImpl拥有。测试可复用HTTP fixture configure的人员/资格/排班/商品发布输入，但所有动作调用真实Services（反射仅用于默认retail编译边界，不换Bean）。不另建业务Service。

步骤：自有实体/店/当前B owner或显式booking manage与customer权限、真实C账号；实际服务定义和目录、两名符合资格STAFF与有效周班表；真实guest注册+token；用实际预约链获得原CONFIRMED hold；真实claim使guest事实CLAIMED/userNo；用当前mc1→cr1 check-in；开单请求给原guestNo、真实expectedRevision及不同合法assignment/start以必经REPLACE。断言当前工单USER=user、原guest/claim receipt历史不改、一个工单/实际任务、原hold被替代、owner current_hold指向新hold、command.action=REPLACE且SUCCEEDED、原/新hold plan hash/容量/新arrangement/fence对应，无重复占用；原key重放返回同receipt不再REPLACE。以真实SQL观测owner结果，不以spy调用次数作为唯一证据。

测试名 `claimedGuestNormalizesToUserWhileRealReplacementCommits`；另 `revokedCurrentManagerCannotReplaceClaimedGuestArrangement`（撤grant后0新command且旧hold不变）、`failedRealReplacementKeepsOriginalArrangementAndClaimReceipt`（冲突资源导致真实拒绝，不伪造owner回应）。身份语义参照现有CLAIMED规范化，REPLACE断言参照独立资源native案例。RED/GREEN：`CHECKOUT_PAYMENT_TESTS=BeautyClaimedGuestResourceIT node scripts/verify-checkout-payment.mjs --beauty-source`。首次若全绿以局部消融CLAIMED规范化或接收绑定验证证明覆盖，随后原bytes恢复；任何真实产品缺口先给根精确证据扩owned，不默认允许全域修复。

### A8词条与两个库存参数（AC7部分）

Owned：`b-app/src/i18n/locale/{zh-CN,en,ar}.ts`；`HA/src/main/resources/i18n/{messages.properties,messages_en.properties,messages_ar.properties}`；`HA/J/invbridge/{InventoryWritebackService,StockReconcileService}.java`仅必要imports/调用附近；`packages/shared/tests/biz-perm-label.test.ts`与新增 `packages/shared/tests/stock-shared-source-label.test.ts`。

BOOKING_VIEW/MANAGE值不变；三语perm补条目，中文逐字采用BizPerms.LABELS“查看预约”“创建、调整及取消预约”；先核duplicate key而非假定二者同文案。en common.delete补“Delete”；ar如实际缺同键也补“حذف”。库存推荐保留两个参数顺序storeNo/other，用三语明确“门店 {0} 与门店 {1} 共用…”；{0}确实指当前店，{1}确实指冲突店。现有{1}已经显示other，旧“全吞参”描述不成立；不改checker识别规则，不把storeNo放进other占位。新增测试校验三语两个索引和参数顺序，可用真实Messages格式化Java补充（若需要此文件须根追加owned）。无权限授予变化。

RED/GREEN `V tests/biz-perm-label.test.ts tests/i18n-parity.test.ts tests/message-placeholder.test.ts tests/stock-shared-source-label.test.ts`；`H test -Dtest=BackendI18nParityTest -Dsurefire.failIfNoSpecifiedTests=false`；`pnpm --dir b-app exec vue-tsc --noEmit`。消融恢复缺{0}或错调参数应红。i18n是契约1档，先根同步TDD。

### A9权限注册与生成物（AC7其余，依赖A1/A8及根TDD同步）

Owned真源 `HA/T/arch/BizEndpointPermTest.java`；增加REQUIRED精确四路径：`/biz/personnel/{personnelNo}/assignments`、`/account-links`、`/status-changes`前三条完整人员路径→STORE_ADMIN；`/biz/store-sales-catalog/versions`→GOODS。以当前checker标准化路径登记；不用PUBLIC/ANY_OF，不改ROLE_PERMS。account-links/status仍有实际ownerOnly限制，不能因登记STORE_ADMIN宣称普通店长可操作。

Owned生成物：`packages/shared/tests/fixtures/ops-role-endpoint-matrix.json`、`docs/technical/reference/运营端-角色×端点矩阵.md`、`docs/technical/reference/B端功能点-权限码-页面.md`、`docs/technical/reference/B端功能矩阵-按角色.md`、`docs/technical/reference/三端权限矩阵-按业务域.md`、`docs/technical/README.md`。如A4/A8导致表/词汇生成物真实差异，根先增列精确产物再纳入，不能批量提交未知生成diff。

RED/GREEN `H test -Dtest=BizEndpointPermTest -Dsurefire.failIfNoSpecifiedTests=false`（methods everyBizEndpointHasADecision/noStaleEntries/allCodesExist/decisionsAreActuallyEnforced）；生成 `node scripts/gen-biz-feature-perm-matrix.mjs`、`node scripts/gen-biz-role-matrix.mjs`、`python3 scripts/gen-perm-domain-matrix.py`、`node scripts/gen-perm-endpoint-matrix.mjs`、`node scripts/gen-doc-index.mjs`。Ops四真实GET×FINANCE/SUPER_ADMIN八行来自既有注解/角色真源，不能改权限让旧JSON匹配。README四处从当时当前header重新生成，不抄旧patch的Task55状态。`node scripts/check-generated-docs.mjs --check`在根认可的干净副本检验，含未登记的Ops md副产物单独diff确认。消融一个REQUIRED条目/保留一个旧生成行应红；生成幂等与实际状态分开验证。

### A10 Task59 M1夹具质量（AC8；代码/编译先，实际拓扑后置）

Owned：`HA/T/scenario/BeautyBm04EmbeddedHttpIT.java`；新增 `HA/T/scenario/BeautyBm04ConfirmationFixtureTest.java`。不改生产鉴权，不重开I1/I2。

抽出package-private `confirmationBody(JsonNode quote):Map<String,Object>` 与 `assertWrongRealmConfirmation(int status, JsonNode body)`。在原prepared quote上共用同一otherwise-valid body（quoteNo/revision/hash），wrong-merchant用新key；明确401和10401，源依据为consumer filter不建商户认证→CheckoutAccessPolicy.requireCurrentBuyer→SecurityUtils.requireUser；不得接受任意400/业务失败。在同一负例前先用该merchant token读合法B资源成功、负例后再读成功，排除过期/被撤销token造成10402。GlobalExceptionHandler:173按TOKEN_EXPIRED_ATTR区分10402；合法可读B会话在consumer filter只因realm不匹配而无认证，期望固定10401。

错realm前后用现有fixture JDBC helpers按operationNo核 `ord_checkout_confirmation` 数0、checkout operation的confirmation/order/sub-order仍null与QUOTED、相应order/payment-operation/PAY ledger没有增量；随后同body当前C确认/下单成功证明请求有效。独立malformed body保留BAD_REQUEST覆盖。新普通测试 `validConfirmationUsesPreparedQuoteEvidence`、`wrongRealmAssertionRejectsMalformedBodyFailure`、`wrongRealmAssertionRequiresExactAuthorityFailure` 先RED→GREEN并编译：`H -Pbeauty-embedded test -Dtest=BeautyBm04ConfirmationFixtureTest -Dsurefire.failIfNoSpecifiedTests=false`；`H -Pbeauty-embedded test-compile -DskipTests`。最小消融让assertion接受400即红。明确这仅证明夹具断言，不证明真实HTTP授权；原 `guestArrivalOpenPurchaseSourceAndNullableBillUseRealOwners` 的实际embedded/standalone和权限消融留60B统一拓扑gate，不能声称M1实际部署闭环。

### A11 WARN归因（AC9）

Owned：`BC/T/beauty/scenario/WalkInRecoveryIT.java`（新增独立故障归因案例，复用:361/:384现有pending扫描夹具；不声称这是55D历史日志的原方法）；`scripts/verify-beauty-bm04-wire.mjs`仅aliases允许精确方法；报告证据部分，不改日志级别、不删WARN、不全局suppress。

新增 `expectedRecoveryFailureEmitsAttributedWarningAndKeepsIntentRecoverable`：在现有真实pending恢复夹具对owner一次抛IllegalStateException，用仅绑ReceptionRecovery logger的ListAppender捕获；finally detach，断言恰好一条WARN、原operationNo/异常类，pending原key和可恢复性；恢复后成功且无新增WARN。日志不含身份票据/密钥。全局保留其它WARN原文，归类fault、构建/agent、Flyway验证版本、mapper装配、测试配置、native resolver；新WARN需定位实际源，不自动归入baseline。

命令 `BEAUTY_BM04_TESTS=WalkInRecoveryIT#expectedRecoveryFailureEmitsAttributedWarningAndKeepsIntentRecoverable node scripts/verify-beauty-bm04-wire.mjs --mode=aliases`（先追加该精确白名单）；普通编译由P验证。若故障本来已有正确日志，新断言首次绿，用删除那条LOG.warn的精确消融证明失败，恢复原bytes；生产无提交改动。duplicate-mapper只读定位来源/Bean作用，若确认影响真实唯一owner则另发有界修复片；不借质量任务清空所有环境告警。

## 4. 审查顺序与遗留

依赖图：A1→A2；A2→A3/A7；A4→A5/A7；A6可独立；A8→A9；A1及根文档同步→A9；A10独立；A11在安全aliases基础上独立。实施按编号串行也可，无须并发。每片提交前有独立review与原执行者fix loop；根给每片新基线，避免前片生成/缓存污染。

重点审查输入：显式空env不得回退；同一类多个方法所有XML必须实际执行；ADD竞胜回执必须真赢家；CHECK中CHAR_LENGTH不得伪列；第13未登记表必须失败；CLAIMED身份与真实新hold必须同次调用；有效body在错误realm不得靠400过关；expected fault WARN不能吞掉未知WARN。各项均有上文owner测试。

未决只限技术事实：嵌入ArchUnit最终越界集合、A7真实组合可能暴露的产品缺口、A10真实HTTP对应断言均需未来对应gate观察。当前源码未证明需新SQL/权限grant。根批准小片后继续代码/必要测试，不要求用户重复批准；真实BM04部署与BM05推进状态由根统一维护。
