# Task60A设计与只读审计报告

状态：DONE_WITH_CONCERNS（设计候选；没有债务被本阶段修复）。2026-10-24。

实测只读身份：HEAD `920482037595e39d3db7408758430511b39a8531`，分支 `codex/beauty-foundation`，初始 `git status --short` 空。接受产品Task59 `c9d9518798ee74f93d7a1f899d356c4760d57b43`。本人只新增本报告和task-60a-design.md；收尾只读status看到根并发追加progress.md两行，未触碰该文件；未构建、测试、生成器、native、stage、commit、push、merge、部署或子agent。命令设计不是执行证据。读取CLAUDE及ai-shop-dev-standards/java，按1档契约对账组织；主TDD/brief/progress/ADR/review/pins未修改。

以下路径均相对唯一工作树 `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`。行号针对上述DESIGN_BASE当前字节，非旧review行号推断。

## 1. 五组mandatory审计

| 债/AC | 当前file:line与实际事实 | 最小处理/片 |
|---|---|---|
| JobLock配置 | backend/shop-store-mybatis/src/main/java/ai/neargo/shop/store/config/JobLockConfiguration.java:1,22,37,47：store包、无条件@Bean、usingDbTime与executor；HA config/ShedLockConfig.java:3,9；beauty-server BeautyJobTargetConfiguration.java:5,16是两生产import | 同模块迁job.config；不改HOST依赖及SERVER外层条件，A1 |
| disabled/锁测试 | backend-plugins/shop-industry-beauty/beauty-server/src/test/java/ai/neargo/shop/beauty/scenario/BeautyJobTargetTest.java:35,42,52；backend/shop-app/src/test/java/ai/neargo/shop/scenario/JobTargetProfileTest.java:88,98 | 已有disabled/锁/token测试，补唯一Bean/包位置；clean自身旧class后验证 |
| beauty业务域 | backend/shop-app/src/test/java/ai/neargo/shop/arch/ArchitectureTest.java:46,74 DOMAINS止于logistics；:136域间棘轮；:360/:372/:397各规则；:451登记门 | beauty登记DOMAINS。rg检查美业主源码未见直接import其它列举业务域或servlet/web runtime；这不是ArchUnit全绿证据，embedded必跑 |
| Ops FQN | backend/shop-app/src/main/java/ai/neargo/shop/portal/ops/pay/OpsPointsInspectionController.java:84全限定PathVariable | 只import，A1 |
| selector | scripts/verify-checkout-payment.mjs:9用`||`吞显式空；:10仅beauty regex；:33前已读迁移；:33起limactl；:96默认分支只filter已知类 | 空/未知/混合可能先provision，A2纯验证入口前置 |
| secret/XML | 同runner:47 MYSQL_PWD=secret、:64 MYSQL_ROOT_PASSWORD=secret均在argv；:104起删report/:118读XML但无目标方法验证 | 禁止先运行旧脚本；复用scripts/verify-beauty-bm04-wire.mjs:88,115,120环境传值，A2 |
| 竞争 | backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ServiceSubmissionIT.java:801 helper，:815 added分支，:891冲突/回放，:902固定SUBMIT_SOURCE，:905 prepare=1；:1074复用跨动作 | 两动作任一胜者，追加真实winner回执，A3 |
| V506 | backend/shop-app/src/main/resources/db/migration/V506__entitlement_exception_action.sql:2单空格列，:23/24合法匿名CHECK。backend/shop-app/src/test/java/ai/neargo/shop/infra/SchemaDriftTest.java:62仅2+空白列regex；:219消费 | 不改冻结SQL；按结构解析，不用缩进判列，A4 |
| CHECK续行/AND | scripts/lib/ddl.mjs:44 NOT_A_COLUMN不含CHECK；:132逐行；:148类型regex无结束边界。V517__entitlement_migration_alias.sql:35与V533__shared_resource_identity.sql:31,33的AND CHAR_LENGTH可能被CHAR前缀误判；现有scripts/tests/ddl-column-definitions.test.mjs:8已明确要求inline和多行CHECK | 顶层定义lexer+类型边界；不加AND字符串豁免，不改600上界；Python gen-test-schema.py:385–410已合并续行/保留CHECK，不能先假定它有同一错 |
| 12非ORM表 | packages/shared/tests/entity-alignment.test.ts:38起精确NO_ENTITY_YET、:239表扫描、:253精确IGNORE、:266 stale；真实owner表见下表 | A5严格12名单，不加前缀、不造无用实体 |
| 插件SQL漏扫 | scripts/check-sql-portability.mjs:38 RULES；:156 targets；:169仅walk backend；:186统一mask+RULES；末尾--check阻断 | A6同一规则加固定美业目录与真实坏SQL负例；原无--check退出0不能叫阻断通过 |
| 实际组合缺口 | backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautyReceptionIdentityIT.java:368仅CLAIMED→USER；:486明示fixture；:503 hold塞map；:576起@Primary替代resource/catalog/personnel/schedule。ResourceBookingExecutionIT.java:409真实REPLACE是独立资源owner测试 | 两个单测不等于一个组合，A7新in-process隔离native，真实owner不能替换 |
| 当前claim正例 | backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautySourceCheckoutIT.java:167,215–226实际guest claim→buyer quote；没实际资源REPLACE。GuestRegistrationServiceImpl.java:76,81,113 current CLAIMED事实校验；WorkOrderCoordinator.java:657规范化 | 复用真实身份链，不伪造缺失CLAIMED实现；A7验证组合而非先改产品 |
| booking词条 | backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java:103/105定义，:152/153中文为“查看预约”/“创建、调整及取消预约”；b-app/src/i18n/locale/zh-CN.ts:2827 perm段缺两码，en/ar同样未检索到booking | A8三语补齐，中文与后端逐字对账；旧“重复中文”理解为双真源一致性，当前两权限并非同文案，勿臆造duplicate bug |
| common.delete | b-app/src/i18n/locale/zh-CN.ts:57有；en.ts:44起common块无delete；ar.ts:44块同样需补顶层delete（:264是别处子块，不能充数） | A8精确key补齐，不改其它delete |
| STOCK_SHARED_SOURCE | messages.properties:263、messages_en.properties:262、messages_ar.properties:271均已有{1}；InventoryWritebackService.java:159与StockReconcileService.java:156均传storeNo,other；packages/shared/tests/message-placeholder.test.ts:39–45仅用{0}分类 | 原“完全无占位/参数全吞”不成立。A8保留两个真实参数，用{0}当前店/{1}冲突店完整文案，不放宽checker |
| 四旧B registry | backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java没有四路径；实际BizPersonnelController.java:67/77/87都有store:admin，:102 ownerOnly；BizStoreSalesCatalogController.java:45有goods | A9精确REQUIRED；account-links/status非普通店长权限；不加PUBLIC或grant |
| Ops八行 | OpsPointsInspectionController实际四GET（accounts/stats-period/transactions/transactions/{voucherNo}）；历史差异/tmp/task56-generated-baseline.patch记录FINANCE+SUPER_ADMIN八行；scripts/check-generated-docs.mjs:64只快照JSON，Ops md是同生成器副产物 | A9从真实注解/角色重生JSON+md；旧patch不是当前执行输出 |
| README四处 | docs/technical/README.md:489、645、646、649旧索引；对应主TDD:3当前2026-10-24/60A未实现，字段字典:3 Task58 V1–8/28私表，数据库结构:3和聚合边界:3有各自历史状态 | A9从当前真源生成四处；不能把旧patch中的Task55或逐模块部署顺序抄回来 |

## 2. 严格12项真实名字与owner

旧报告把三stock表合并简写为`prd_stock_sync_operation/alignment_batch/alignment_line`、两refund写为`ord_refund_coupon_scope/obligation`，不能机械补前缀。真实列表为：

| 精确表 | 当前读写owner证据（完整包/文件） |
|---|---|
| sys_outbox_stream | backend/shop-store-mybatis/src/main/java/ai/neargo/shop/event/outbox/runtime/JdbcOutboxStore.java:37 PLATFORM sys前缀、:65 streams |
| sys_outbox_delivery | 同文件:64 deliveries，实际JDBC持久投递 |
| sys_outbox_operation | 同文件:66 operations，实际JDBC运维动作 |
| prd_stock_sync_operation | backend/shop-app/src/main/java/ai/neargo/shop/invbridge/StockReconcileService.java:90,98,163,187 JDBC写/读 |
| prd_stock_alignment_batch | backend/shop-app/src/main/java/ai/neargo/shop/invbridge/StockAlignmentService.java:91,216,246 |
| prd_stock_alignment_line | 同文件:100,224,254 |
| ord_refund_coupon_scope | backend/shop-core/src/main/java/ai/neargo/shop/trade/order/mapper/RefundObligationMapper.java:10,18 |
| ord_refund_obligation | 同文件:27–40及后续Update；V476__refund_closure_obligations.sql:15实际建表 |
| inv_outbox_stream | JdbcOutboxStore.java:37 INVENTORY inv前缀、:65 |
| inv_outbox_delivery | 同文件:64 |
| inv_outbox_operation | 同文件:66 |
| inv_event_consumed | backend/shop-store-mybatis/src/main/java/ai/neargo/shop/event/outbox/runtime/TransactionalOutboxHandler.java:27白名单与后续JDBC消费，backend/shop-app/src/main/java/ai/neargo/shop/config/OutboxConfiguration.java:70选择 |

不声称上述登记已经完成。A5未来校验相对该片IMPLEMENTATION_BASE的新增差集恰好这12项且理由具体；原有points/logistics等全部合法精确登记及理由保留，绝非把整张NO_ENTITY_YET收缩为12项。未知probe与删除登记必须进入真实表守卫共用分类函数并产生拒绝结果，不能用静态名单复制品自证；原有实体列检查/stale登记保持。

## 3. Task59 M1/M2与55D告警

M1当前仍在 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautyBm04EmbeddedHttpIT.java:273` 用Map.of()，:279–285才是有效confirmation字段；:591 denied只判失败。实际DTO `backend/shop-core/src/main/java/ai/neargo/shop/trade/api/mp/MpCheckoutController.java:125`要求quoteNo/hash。consumer filter `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/AbstractTokenAuthFilter.java:91`只建立同realm认证；`CheckoutAccessPolicy.java:46`先requireUser再CONSUMER/USR/MAIN；SecurityUtils.java:35无身份抛UnauthorizedException。A10有效body+明确authority响应+实际操作无副作用，不用Map.of负例冒充权限；普通夹具测试与test-compile先行，真实HTTP仍60B。

M2原审查 `review-task-59.md`已明确四类历史输出位置：task-59-unit6-unused-mapper-host.log:337 deprecated；plugin.log:95 dynamic agent；/private/tmp/task59-evidence/aliases-3b86eabf-5e72-4e5e-932e-e7d25cd2e68c/maven.log:66 Flyway；owners-27f4486f-8256-4e2b-b319-26e7e61cba68/owners.log:563 duplicate mapper。此设计读取审查定位，未重新执行那些输出；存在与内容不能由“曾全绿”抹去。Task59 Important I1/I2已独立验收，与本项无关。

55D原 `review-task-55d.md:33` 指向当前可读 `/private/tmp/task55d-coverage-final.log:140`：`Reception reconciliation deferred operation=BTRO261023E0RY8YE037 failure=IllegalStateException`；实际生产 `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionRecovery.java:46`记录operation和异常类。归属为故障注入恢复WARN，应测试捕获具体logger/operation后仍保留日志；不能捕获全日志或调ERROR静默屏蔽。该日志:76等有Flyway MySQL9.7超过verified8.1提示；:94 Sequence慢初始化是另一环境提示。HOST测试seed/security、native resolver/Netty、Mockito/CDS/动态agent、deprecated及mapper重复应逐条记录源与环境。没有足够证据说这些都已修复或无生产影响；A11只落实真实故障warn断言和分类，发现真实装配冲突再给根精确修复片。

## 4. 冻结与差异裁定

只读枚举当前平台最大V540、beauty最大V8。V506实际997 bytes、SHA256 `9b930f86c27426e37599d773162add8aa9a81f28b68ea6dbd50f2e4fb322effb`。`backend/known-sql-dialect.txt`实际7765 bytes、SHA256 `0edd37e78ae6e67d805327e9f2afe33361b6cee403a3bd75e0072bbc1c96f502`。beauty1–8/platform537–540冻结由根复核；本阶段未运行SQL、更未改任何迁移。本方案不提新迁移，因此没有伪造新SQL/hash/升级结果。

主要关注点：

1. A1清理旧store.class只在未来本工作树可信构件刷新中做；HOST无条件DataSource和SERVER disabled形态不能混同。
2. A3跨动作现存方法已核定为ServiceSubmissionIT:1073 concurrentDerivedActionsShareOnePermanentEndpointKey；每片IMPLEMENTATION_BASE与owned最终由根收口。
3. A4已存在lexer测试，首跑可能直接RED；本阶段未跑，不能提前声称失败数。V506/AND是消费方边界，不是改冻结SQL理由。
4. A7要真实资源配置与当前身份，旧fixture正例不足；尚未证明需产品修复。若发现真实缺口，再小片扩owned。
5. A10明确401/10401；GlobalExceptionHandler:173会把TOKEN_EXPIRED_ATTR变10402，所以先后用同token合法B读取证明会话仍有效。实际HTTP仍待后置拓扑；不得用10402或任意failure冒充wrong-realm断言。
6. A11新增独立明确故障断言，复用WalkInRecoveryIT:361/:384已有pending扫描夹具，不声称它就是55D历史日志原方法；原日志仍保留。不能把所有环境warning“清零”作为通过门槛。

推荐第一个片A1。完整owned/接口/命令/AC/消融与依赖见task-60a-design.md；根先审设计并同步主TDD，再逐片给release。没有源码修改或测试执行，本报告不宣称任何债已关闭、BM04已部署或BM05已实现。

## 5. 根技术预审三项修订（仅文档）

- A1取消包名字串镜像测试，HOST/SERVER各校验唯一LockProvider/LockingTaskExecutor；原Architecture包登记规则负责迁包RED。扩展原disabledServer方法为api下beauty=false/job=true、beauty=true/job=false、bothfalse三个有限场景，分别无DataSource/target/锁Bean；保留nonApi/token行为。embedded存在性方法用EnabledIfSystemProperty，HOST命令必带beauty.arch.required=true。固定本工作树store clean install→HOST embedded clean test→SERVER clean test，均无-am、不clean其它模块；可信RED后最小迁包，同序GREEN，再beauty登记消融与HOST恢复。完整8路径owned及命令见设计A1；仍未执行。
- A2撤销“全部+拒绝”的兼容性缩减。保留beauty单类及payment单类/多类合法Class#method1+method2，逐分量白名单与freshXML；空、重复、未知、不规范+拒绝，mode边界明确。
- A5明确12为新增差集，原有合法登记保留；未知probe通过真实守卫同一分类路径拒绝，不以复制名单代替行为证据。

仅A1为当前可直接核定的第一片；其余10片是后续细化候选，不构成实施release。原重大关注和冻结边界不变。
