# 美业系统后端开发任务计划

> 状态：实施中 · 2026-10-20 · 已完成工程/迁移基础，政策与执行定义数据切片部分实现；全量业务未完成。
> **执行方式：** 使用`superpowers:executing-plans`逐任务实施，每步使用复现过的红灯/绿灯证据；本会话只推进美业。
> **Spec：** [功能矩阵](../requirements/美业系统-多端功能矩阵.md) · [独立Java结构](./TDD-美业系统-独立项目与包依赖方案.md) · [API清单](./design/美业系统-API清单.md) · [类与方法](./design/美业系统-Java类与方法清单.md) · [逐API方法](./design/美业系统-Controller-Service-Mapper方法清单.md) · [数据库设计](./design/美业系统-数据库结构设计.md)。

## 当前执行记录（2026-10-20）

实现位于独立 beauty-data worktree，保护原工作区未提交的餐饮与前端变更。代码入口：[美业工程](../../backend-plugins/shop-industry-beauty/README.md)。

| 任务 | 实施状态 | 实际交付与边界 |
|---|---|---|
| ENG-01 | 完成 | 五构件可独立构建；只有server可执行；13项完整依赖架构测试及非法依赖反例 |
| ENG-02 | 完成 | 默认关闭、显式部署形态；宿主retail/embedded/standalone验证；独立服务无用户入口；实际JAR无数据库启动及200/503探针 |
| DATA-01 | 完成 | 首批16表归属、BaseEntity字段、tenant/entity索引、源行guard身份与可空唯一键审计；共享14项及P3/P4留在原任务 |
| DATA-02 | 完成 | 冻结V1、专属历史；MySQL实际DDL与Flyway重入/漂移测试；拒绝无归属旧美业表；平台Flyway共存 |
| DATA-03 | 部分完成 | 主工厂/主事务/审计接线与5项实体数据域；真实policy Mapper回滚和范围CAS通过，其余Mapper随各批联验 |
| DATA-04 | 部分完成 | 5项Entity/嵌套Mapper、4个自定义方法；版本CAS与INACTIVE状态通过；不可变发布事务和同主体跨店子表父链尚待补齐 |
| ENG-03、DATA-05～07及后续 | 未完成 | 未实现可信业务入口、幂等/outbox联验、预约/日历/任务等业务闭环，不以基础工程通过计作营业可用 |

实际验证：独立reactor 42项测试通过，Node生成器6项通过，隔离MySQL DDL约束通过，23项真实JDBC IT通过。
宿主retail/embedded各6通过＋1个不适用形态用例跳过，standalone 7项全部通过；无H2替代MySQL约束结论。
设计API/Java/数据库/任务索引生成校验通过，SQL方言守卫无新增命中。独立服务readiness仍为OUT_OF_SERVICE。
全局文档守卫为5通过/3失败，剩余为既有方案输入中的Mermaid基线、餐饮断链和索引欠账；美业生成与链接检查不在失败清单中，未将全局守卫标记通过。

TDD证据：缺工程/配置→架构与装配绿灯；缺SQL生成器→6项绿灯；缺专属validator→真实MySQL绿灯；缺实体→政策3项绿灯。
复核修复均先增加反例：默认关闭数据源抢装、默认值/自增/主键/普通及前缀索引漂移、ACTIVE/INACTIVE状态合同。
CHECK比较使用闭合语法树保留运算优先级，5项专门测试通过。审查最初提出的非布尔超量CHECK例子被MySQL拒绝，未据此声称已接受超量。

取舍：先在隔离数据库完成持久化接线与政策切片；正式写入口仍由ENG-03控制，未绕过身份、幂等或outbox前置。
未连接任何共享营业库。目标库发布前由受控迁移执行者只读核对归属，未知历史直接拒绝；P3提成跨组仲裁仍是PERF-01前置。

**Goal：** 先完成美业工程与数据层，再逐步交付“项目定义→人员资源匹配→预约/日历→接待工单→先付服务/履约”的完整闭环，之后按P2/P3/P4扩展。

**Architecture：** 同仓独立reactor `backend-plugins/shop-industry-beauty`，beauty-api/core/web/channel/server五模块。Service接口与DTO在api，业务/Entity/Mapper在core，用户Controller在web，协议适配在channel，server仅独立内部业务启动。商品、订单、资金、人员资源、库存、通知打印由原所有者提供，不复制账本。

**Tech Stack：** 沿shop-parent/neargo-parent的Java21、Boot4、MyBatis-Plus、Flyway及现有认证/数据域/任务/outbox/可观测设施；不自行升级基础版本或建立第二套规范。

**任务真源：** [开发任务JSON](./design/schema/beauty-development-plan.json)；[完整任务/API/表/功能对照索引](./design/美业系统-开发任务对照索引.md)。索引由`python3 scripts/gen-beauty-development-plan.py`生成，校验追加`--check`。

## §0 当前事实与计划边界

当前美业有20模块118项功能、160项API/合同（95个美业HTTP、11个共用候选HTTP、54个共用合同接入点），44个美业Controller、28组Service/Impl、101条Service方法、25个Mapper及38条自定义Mapper方法的目标清单。**它们都是设计，不是已经实现的营业能力。** 本轮目录核查未发现独立美业Java工程；未查询数据库实例，因此不推定历史`bty_*`表一定不存在。

此前误入餐饮的桌台/菜品/订单安排不作为美业任务，餐饮NEXT-01～04的进度不转记到美业。美业从ENG-01开始；餐饮只作为五模块与薄适配的参考。本轮不继续餐饮NEXT-05，也不改其未提交代码或迁移。

| 数据结构 | 数量 | 本计划如何交付 |
|---|---:|---|
| 美业P1 | 16项bty结构 | 首批审计/迁移/Entity/Mapper，DATA-01～07 |
| 美业P3 | 7项bty结构 | CARE-01护理3项、PERF-01业绩提成4项；当期追加迁移 |
| 美业P4 | 2项bty结构 | PLAN-01疗程服务计划2项；不提前建余次账户 |
| 共用人员/排班/资源 | 14项 | 13候选新增＋1既有mch_appointment_slot兼容扩展；SHR-01/02由原所有者实施，不进入美业迁移 |

计划共49个任务；M0/P1是当前可细化执行主线，P2以后按正式合同另行展开小切片。每个API/表有唯一首次主责任务；联合验收可以涉及多个任务，但不重复计完成。

### 0.1 推进顺序与准出

| 阶段 | 主要任务 | 准出结果 |
|---|---|---|
| M0 工程和数据起步 | ENG-01/02；DATA-01/02/03；ENG-03 | 五构件可构建，关闭不影响零售；隔离库可执行/校验P1迁移，可信范围、事务/审计接线可检验 |
| P1 数据对象与共用前置 | DATA-04～07、SHR-01/02/03 | 16项美业Entity/Mapper与对应不变量通过；共用人员/资源合同真实存在，不能只返回成功占位 |
| P1 项目与预约 | POL-01、DEF-01、AVL-01、APT-01～03 | 同一SKU门店报价；资格/班表/完整资源匹配；无交易可预约，改约失败保留原位 |
| P1 接待、工单与执行 | WO-01、REC-01、SRC-01、TASK-01～03 | 真实来源就绪才开工，实际参与/完成量、核心履约与关闭分别收敛 |
| P1 工作台与恢复收口 | CAL-01、RECOVER-01、MSG-01、HIST-01、OPS-01，GOODS-01按实物需求 | 日历看到具体任务与真实资源占用，跨店匿名busy、快照完整性、原号恢复可验收；打印真机另验 |
| P2 资产接入 | ASSET-01/02 | 本次储值/已持有合格次数权益本人授权和未知恢复，门店隔离，不等于付费售卡 |
| P3 经营完善 | CARE/QUEUE/PERF/MARKET/MAT/FIN/RPT/DEVICE | 护理、协作、营销、耗材、财务报表按政策/权限与真实数据源分别验收 |
| P4 专项 | PLAN/SALE/ADV/ASSET-REV/MAT-02/EXT/EDGE | 疗程销售、复杂收银、团体/上门、资产逆向、外部/离线逐项合同和故障验收 |

CAL-01可先开发资源占用视图，但完整准出依赖TASK-01任务摘要及工作台合同联验；不能把只画出空泳道算“看得到具体预约任务”。任务依赖是业务前提，不要求等全部共用经营功能落地后才做单次服务。

## §1 全局约束与审查重点

1. 严格沿[统一Java规范§七](../../.claude/skills/ai-shop-dev-standards/references/java.md)及零售现有约定：资源Controller、Req/VO、Service/Impl分包、Impl直接调本域Mapper、BeautyMappers嵌套接口、BaseEntity/PageData/MybatisPages、统一ErrorCode/BizException/三语；不建Repository或BeautyApiResult/BeautyAuth等同义层。
2. 独立工程只拥有bty事实，Mch/商品/订单/Pay/库存实体和Mapper不得导入美业业务实现。同库不提供跨域直写许可；缺SPI在权威所有者补，不能先放beauty-core让餐饮依赖美业。
3. 人员可没有登录账号；业务人员、操作账号、顾客、买家、付款人分开。任职、业务岗位、资格、班表与系统权限分别校验；同主体同一STAFF跨店共用规范资源。
4. 请求主体/操作身份来自可信上下文，顾客接口校验本人归属；平台恢复不代授权/代完工。资源或Query里的personnelNo不是访问权限，跨店busy不返回他店顾客/任务/门店引用。
5. 迁移、类型、索引与默认值从当前设计真源评审生成；已执行版本冻结，后续只追加。不执行clean/repair/任意baseline；发现历史bty结构或资产账冲突先核对归属和历史，禁止IF NOT EXISTS掩盖漂移。
6. 本域短事务完成状态/版本/事实/outbox；跨资源/Core/Pay采用持久意图和原号恢复。HTTP不在长锁事务内等待，UNKNOWN不重新生成来源/支付号。
7. SQL CAS显式校验可信范围、版本/前态/数量上限并检查影响行；标量自定义SQL显式写version与updatedBy/updatedAt。空行查询不算稳定锁；首次创建靠唯一键与幂等协议。
8. 预约指定、确认安排、实际参与独立保存。服务完成和履约确认分开，重复回调不再增加doneQty或扣资产；工单关闭冻结范围后校验真实任务/绑定版本。
9. 日历采用不可变窗口快照和短轮询，分页齐全/投影延迟分别标记。日历空白不是可约承诺，真正容量仲裁在共用所有者；护理正文、照片和余额不混入日历/公共打印/外部通知。
10. 当期只生成需要的类/DTO和Mapper，不一次创建44个空Controller、25个Entity和160个DTO壳。后续源码变动先对账方法/API/字段真源再重建文档。

| 审查重点 | 预期 | 主责任务 |
|---|---|---|
| 历史表、共享表或错误主数据源 | 漂移报告明确；不重建共用表，不落到Pay/库存库 | DATA-01/02/03 |
| 首次并发与同人跨店资源竞争 | 完整组合只确认一个；失败不留半组合、改约不丢原占用 | SHR-02、APT-01/03 |
| 来源超时/数量重复绑定/履约回调重放 | 原号恢复、分配上限守恒、服务完成只计一次 | DATA-07、SRC-01、TASK-03 |
| 旧权限/旧快照/缺页 | 撤权即失效；他店busy匿名化；不完整视图不当空闲 | ENG-03、CAL-01 |
| 换人、超时、关闭冻结竞争 | 保留历史参与，未交接不静默变人，未知来源不强关工单 | TASK-02/03、RECOVER-01 |

## §2 首批工程及数据层详细任务

本节是实际编码前的领取粒度；其余任务总表及全部160项入口见索引。下述为任务目标，实际交付和未完成项以本页执行记录为准。每个任务开始先复核相关需求、方法和真实提供方；若字段/行为调整，先更新设计真源及受影响验收，不把原草稿当永远不变的事实。

### 2.1 文件责任与首次执行准备

- `R=backend-plugins/shop-industry-beauty`，工程根；`A=R/beauty-api/src/main/java/ai/neargo/shop/beauty`。
- `D=R/beauty-core/src/main/java/ai/neargo/shop/beauty`；`W=R/beauty-web/src/main/java/ai/neargo/shop/beauty`；`C=R/beauty-channel/src/main/java/ai/neargo/shop/beauty`。
- `S=R/beauty-server/src/main/java/ai/neargo/shop/beauty/server`；`T=R/beauty-core/src/test/java/ai/neargo/shop/beauty`。
- 美业表与方法真源为`docs/technical/design/schema/beauty-database-design.json`及`beauty-java-method-design.json`；公共新增只修改其真正所有者路径。

实施前确认工作区/分支和未提交文件归属，独立美业改动不夹带餐饮或其他前端变更；保护先前已形成的美业设计源。目标分支遵循codex/前缀，工作树选择遵守当时工作区及权限，不能将未提交设计遗漏在另一个checkout。

### 2.2 ENG-01：五模块与可构建边界

**文件：** 新建`R/pom.xml`、`R/README.md`、五个子POM；最小`S/BeautyApplication.java`；`R/beauty-server/src/test/java/ai/neargo/shop/beauty/architecture/BeautyArchitectureTest.java`。

**消费：** 当前shop-parent及共享构件；**产出：** api/core/web/channel/server五构件，父POM版本沿平台，只有server repackage；不带营业接口。

- [x] 写`rejectImplementationDependencies`、`webDoesNotDependOnCore`、`channelDoesNotDependOnWebOrCore`、`onlyServerIsExecutable`；断言整个reactor而非空测试目录，非法依赖反例必须失败。
- [x] 运行目标测试，确认因缺少目标构件/行为而失败，再按既有独立结构补五POM与最小启动。
- [x] 运行reactor verify/依赖树/实际jar检查；既有共享构件缺失时按平台流程准备，不升级BOM或引用shop-app可执行jar。
- [x] 对账五构件/直接依赖/扫描范围与结构真源，记录结果并按定向文件提交；不生成全部业务壳。

### 2.3 ENG-02：条件装配与用户/内部入口隔离

**文件：** `D/config/BeautyAutoConfiguration.java`、`BeautyProperties.java`；`W/config/BeautyWebAutoConfiguration.java`及独立条件；两模块AutoConfiguration.imports；`S/config/BeautyServerConfiguration.java`、`BeautyInternalSecurityConfiguration.java`；宿主仅在实际可挂载时修改其POM/配置，不复制用户Controller。

**产出：** shop.beauty.enabled默认false，enabled时deployment显式embedded/standalone；web不引用core配置，独立server不引用户web；关闭时无Mapper/Controller/Job注册。内部身份采用平台既有协议，不引第二套用户登录。

- [x] 写`disabledRegistersNoBusinessComponents`、`webLoadsWithoutCore`、`standaloneHasNoPublicRoutes`、`missingRequiredDeploymentFailsClosed`，验证真实Bean/RequestMapping而非以401冒充路由不存在。
- [x] 看红灯后实现互斥装配，保留平台原数据源、安全链和全局扫描约束；新增组件自身也受开关保护。
- [x] 复验embedded、standalone、禁用、api/ops组合；缺必要配置或正式业务提供方保持受影响readiness关闭。

### 2.4 DATA-01：数据合同审计与首批冻结范围

**文件：** 核对数据库设计JSON、Java方法JSON、字段字典、现有BaseEntity/AuditMetaObjectHandler与数据域注册规则；修订真实发现的冲突后重生成既有文档。

**产出：** P1的16项私有表/字段/唯一键/索引/默认值/校验矩阵，P3七项/P4两项延后清单；共享14项所有者及兼容清单；不得在此任务把39项全变成美业DDL。

- [ ] 核对entityNo须由子Entity明确承载、BaseEntity已有id/tenant/审计/version/deleted不重复定义；LocalDateTime持久化与API带偏移时间的UTC转换一致。
- [ ] 核对唯一索引的tenant/entity前缀、可空唯一键、正数/精度/数量守恒、子表受权父链、不可变事实追加、历史绑定与逻辑删除风险。
- [ ] 对源行guard唯一键不含sourceRevision、工单冻结/任务绑定修订、安排历史、审计/幂等持久检查点逐项确认；DTO摘要不等于已具备完整字段类型，只定当期切片所需Req/Query/VO。
- [ ] 如目标环境已有bty表或旧美业储值脚本记录，先只读核对应用历史及结构；缺少归属证据时不migrate、不覆盖资产账户。
- [ ] 已知P3复核点：数据库提成规则写“锁门店政策头”，方法清单含“规则组首版锚点”；开PERF-01前需确定跨组规则重叠的串行仲裁，两种粒度不直接视为等价。此项不强行扩大P1数据范围。
- [ ] 审计结论、缺口及当期取舍形成记录，才进入DATA-02；未确定结构不凭猜测写迁移。

### 2.5 DATA-02：首批迁移及真实隔离库验证

**文件：** 候选新增`scripts/gen-beauty-schema.mjs`、`scripts/tests/beauty-schema-sql.test.mjs`；`R/beauty-core/src/main/resources/db/industry/beauty/V1__beauty_p1_tables.sql`；隔离MySQL/MariaDB验证工具按项目已有方式复用。

**消费：** DATA-01已审定的P1结构；**产出：** 16项bty建表迁移、独立bty_flyway_history及漂移校验；后续版本按首次执行事实冻结。文件名/版本为首次实施目标，开工先核对是否已有同名执行历史。

- [ ] 先写`containsOnlyP1BeautyTables`、`rejectsSharedOrAssetTables`、`preservesBusinessUniqueness`、`rejectsChangedAppliedMigration`；证明非P1/shared表或冻结文件变化会被拒绝。
- [ ] 红灯后实现从审定真源生成SQL；不包含mch/prd/ord/Pay/库存账，不用另一份手写DDL漂移。
- [ ] 在一次性隔离目标数据库验证创建、字段/索引/约束与Flyway validate、再次执行不重复迁移；表数对不是唯一判据，未知历史/漂移必须失败。
- [ ] 不使用H2替代MySQL锁/检查语义，不连接共享营业数据库跑破坏性夹具；部署migrate只有一个受控执行者，普通启动默认校验。
- [ ] 记录实际DDL摘要与测试结果；P3/P4今后追加版本，不回写已执行V1。

### 2.6 DATA-03＋ENG-03：主库、数据域、事务与可信写入基础

**文件：** `D/config/BeautyPersistenceConfiguration.java`、`BeautySchemaReady.java`、`BeautyDataScopeRegistration.java`；宿主必要的Mapper扫描排除与独立server明确主连接装配；`W`接入范围校验仅在确需辅助时先登记类，不自造BizContext。

**测试：** `T/scenario/BeautyPersistenceWiringTest.java`、`BeautyStoreScopeTest.java`、`BeautyIdempotencyTest.java`、`BeautyOutboxWiringTest.java`；独立server对应数据源/迁移安全测试。

- [ ] 先验证`bindsBeautyMapperToPrimaryFactory`、`disabledDoesNotSuppressRetailFlyway`、`doesNotUsePaymentOrInventoryDataSource`、`rejectsUntrackedSchema`；MyBatis沿主工厂/事务，不能注册一个全局Flyway替换掉零售迁移。
- [ ] 校验真实entity列及子行受权父链，默认fail closed；未来加入哪张表就登记哪张表，不一次虚构所有数据域锚点。
- [ ] 将`sameKeyDifferentPayloadRejected`、`crossStoreMutationRejected`、`actorComesFromLogin`、`transactionRollsBackIntentAuditAndOutbox`落实到首次真实写入，不只验证mock调用次数。
- [ ] 接线本身先做条件/工厂验证，**真实Mapper、事务与审计结果随DATA-04/07首写联验收口**；不能在尚无Mapper时宣称所有数据层已通过。

### 2.7 DATA-04～07：按真实聚合实现Entity/Mapper

所有实体和Mapper归beauty-core；公共完整根包`ai.neargo.shop.beauty`。`D/mapper/BeautyMappers.java`是唯一集合文件；以下方法完整参数和返回沿既有方法JSON/清单，不重命名成另一组。Controller不提前依赖这些实体。

| 任务 | 数据与精确类范围 | 关键方法/不变量 | 目标失败用例 |
|---|---|---|---|
| DATA-04 政策/定义5项 | entity/BtyStorePolicy、BtyStorePolicyVersion、BtyServiceDefinition、BtyServiceDefinitionVersion、BtyServiceRequirement及同对象内嵌Mapper | selectScopedForUpdate、switchCurrentVersion；发布版本与要求追加；头指针CAS、唯一门店/SKU范围 | `PolicyDefinitionPersistenceTest#rejectsStalePointerSwitch`、`#keepsPublishedSnapshotImmutable`、`#rejectsCrossEntityRequirement` |
| DATA-05 预约3项 | entity/BtyAppointment、BtyAppointmentItem、BtyAppointmentAssignment及对应Mapper | changeSchedule、markArrived、changeStatus、selectByAppointmentRevision；原安排不覆盖，指定意愿不当实际执行 | `AppointmentPersistenceTest#keepsPreviousAssignmentRevision`、`#rejectsStaleScheduleChange`、`#limitsChildRowsToAuthorizedParent` |
| DATA-06 工单任务4项 | entity/BtyWorkOrder、BtyServiceTask、BtyServiceStep、BtyServiceParticipation及对应Mapper | freezeScope、advanceScope、closeFrozenScope、recordCompletion、switchBindingRevision、endParticipation；范围/绑定/根版本分开，参与区间追加 | `ServiceTaskPersistenceTest#rejectsOverCompletion`、`#rejectsScopeMutationWhenFrozen`、`#closesOnlyCurrentParticipation` |
| DATA-07 来源编排4项 | entity/BtyServiceSubmission、BtyTradeBinding、BtyActionLog、BtySourceAllocationGuard及对应Mapper | selectByRequestKey、advanceProgress、selectSourceForUpdate、allocateQuantity、releaseUnconsumedQuantity、refreshSourceSnapshot；同源guard跨任务累计上限，原号/原绑定留存 | `SourceAllocationConcurrencyTest#allocatesWithinOriginalQuantity`、`#sharesGuardAcrossSourceRevisions`、`#rejectsChangedPayloadForSameKey`、`#doesNotReleaseConsumedQuantity` |

每批具体步骤：

- [ ] 先用本批真实表在隔离数据库写失败测试：范围/唯一键/版本/数量/历史/审计；跨事务并发使用独立连接和稳定仲裁行。
- [ ] 新增当批实体，继承BaseEntity，字段/表名完整驼峰一致；JSON快照按既有序列化约定，私有护理字段尚未进入本批。
- [ ] 新增当批嵌套BaseMapper；继承insert/select/update不重写空方法，自定义SQL只为实际锁/CAS/批量查询，影响行数为0必须转明确冲突而非成功。
- [ ] 按root→稳定排序的task→子行/guard等已定锁序联验；自定义标量更新明确写审计/version；不能用synchronized、空SELECT FOR UPDATE或无版本update替代约束。
- [ ] 绿灯后复跑当批依赖和数据层套件，挑关键断言做消融，记录真实命令及结果；无业务Service时仅标数据合同通过，不声称开工/扣款/资源已可营业。

### 2.8 数据层首批完成的界线

满足：五模块可构建；16项P1 bty迁移可校验；16个Entity/对应Mapper与父链权限、CAS/数量/审计/事务真实测试通过；共享14项未混入；资料P3、疗程P4、资产账未提前造表。

尚不代表：共用多资源仲裁已存在，预约可成约，钱已收、权益已扣、任务能合法开工，或工作台具体任务与打印真机已验收。数据层与业务层分别有完成状态；数据层之后按SHR/POL/DEF/AVL/APT等任务解锁。

## §3 数据层之后的业务任务与启动深化

每部分开工前重新核对功能矩阵对应行、API输入输出、类/方法、表字段、权限与真实跨域提供方，提交“可实施 / 需先补合同 / 延后关闭”结论，再编码。上次形成的类清单不代替这次业务规则梳理。

| 部分 | 必须先深化的场景 | 开发任务与准出 |
|---|---|---|
| 共用人员/资源 | 可无账号人员、多店任职、岗位与角色、资格过期、停用在途交接；STAFF规范身份、班外/维修、缓冲、跨店冲突、hold过期和开放占用 | SHR-01/02；共用所有者可实际查询/原子占齐/替换/释放，旧slot不能同时独立扣量 |
| 项目/营业定义 | 同SKU门店价格与展示、固定时长/等级、版本生效、缓冲、技能和床位要求、先付政策 | POL-01/DEF-01；不给服务规则另存售价，高级规则仍受能力门控 |
| 预约 | 顾客自约/电话代约/临客、指定或不指定人、完整组合、重复提交、改时/换项目/换人、取消/爽约、来源未知与占位到期 | AVL-01/APT-01～03；原子成约、失败保留旧安排，不能靠已付自动复活被别人占用的时段 |
| 工作台 | 日/周、人员/工位/设备泳道、多任务多资源、未分派、计划/实际/缓冲/临时占位、匿名跨店busy、不完整与迟到响应 | CAL-01；含具体任务详情、来源阻断、快照同水位续页和动作后重载，不只日历空格 |
| 接待/工单 | 到店核验、无预约直客、一次签到转工单幂等、项目范围与真实买家、一个工单多任务及来源账单 | REC-01/WO-01；签到不核销、不伪造消费者订单 |
| 来源/执行 | 普通订单/资产消费来源区别、加项与本次确认、开工和退款/撤销的权威许可竞争、步骤数量、换人区间、超时及暂停占用、部分事实与关闭冻结 | SRC-01/TASK-01～03；真实来源就绪才开工，回调仅确认履约不再累加已做数量 |
| 逆向/异常/运行 | 未做撤项、普通售后、质量证据、支付/绑定UNKNOWN、丢事件、重复操作、通知打印未知回执 | AFTER-01/RECOVER-01/MSG-01/HIST-01/OPS-01；原号查询，事实保留，资金与服务/设备状态不互相冒充 |
| 后续经营与专项 | P2资产本店隔离/授权；P3护理与绩效政策；P4售卡、疗程、复杂收银、上门团体、逆向和离线 | 对照索引ASSET等任务；P4路线项先拆每类独立合同/用例/文件后再开工，不能一次提交全部 |

共用人员/资源的代码不在beauty-core复制；共享目录、Core/Pay/库存、媒体、通知/打印、审批/回访/导出等合同逐项核验真实签名/部署/恢复与能力验收。54个CONTRACT不是54个可以直接生成的Java方法。付费疗程销售与已持有权益消费分阶段，护理回访任务与护理内容也分别归其所有者。

## §5 验证命令、记录与发布界线

### 5.1 本轮仅计划检查

运行任务索引生成器验证49任务依赖无环、160个API与39项表首次主责唯一、118功能覆盖、阶段不被静默推迟；复验既有API/方法/数据库设计生成物。本轮不运行尚不存在的Maven工程、不执行DDL、不连接业务数据库。

### 5.2 实施时目标命令（当前不可当已执行）

在首次工程、共享构件及所需隔离环境准备后，从仓库根按当前任务运行：

```sh
# 工程结构/编译及当期完整reactor测试
mvn -f backend-plugins/shop-industry-beauty/pom.xml verify
# 当期定向测试；测试类创建后再运行，不能以不存在测试的跳过作为通过
mvn -f backend-plugins/shop-industry-beauty/pom.xml -pl beauty-core -am test -Dtest=PolicyDefinitionPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false
# 真实SQL及隔离MySQL/MariaDB测试的具体命令随DATA-02工具落地登记
# 任何Java/方法/字段/任务真源变动后重新生成相应清单
python3 scripts/gen-beauty-java-method-design.py --check
python3 scripts/gen-beauty-java-project-design.py --check
python3 scripts/gen-beauty-api-design.py --check
python3 scripts/gen-beauty-database-design.py --check
python3 scripts/gen-beauty-development-plan.py --check
```

定向命令的reactor汇总必须包含目标测试真实执行数且非0，相关模块的suite无跳过掩盖；完整verify不以单测试绿代替。改公共能力时增加其所有者、零售和架构回归；只改文档不运行资金/业务环境。

### 5.3 每任务完成记录

| 任务 | 实际文件/版本 | 红灯→绿灯/消融 | 真实环境与执行数 | 剩余阻塞/是否营业就绪 |
|---|---|---|---|---|
| 原49任务总体 | 基础工程与首批数据层见执行账本；本轮增量见§7 | 各切片记录实际红绿验证 | 不以计划检查代替业务测试 | 完整营业能力尚未完成 |

不在开始前承诺日历工期。先完成ENG-01/02和DATA-01～04测得真实构建/环境/合同成本，再估后续小切片；P2/P3/P4外部条件单列，不用任务数乘固定人日虚构排期。

## §6 下一次领取任务与本轮交付

**下一步先ENG-01，然后ENG-02＋DATA-01，再DATA-02/03＋ENG-03，接DATA-04～07的数据批次。** 部分独立数据审计可先做，但任何真实数据库写入都要先满足该步骤前置，不跳过历史核对。SHR-01/02/03核验可以在工程数据层就绪后推进，业务模块按依赖解锁。

本轮产物：本TDD、开发任务JSON、生成任务对照索引和校验器，并登记技术README；纠正本会话的行业范围。没有编写美业业务Java、创建POM、生成可执行SQL或改动餐饮实现。实际验证结果后附，尚未执行的业务测试不勾选。

### 本轮计划校验记录 · 2026-10-20

- 五组美业任务/Java方法/项目结构/API/数据库设计生成物校验通过；任务依赖无环，49任务覆盖全部160个API/合同、118功能与39项数据结构，首次归属唯一。
- 未发现早期阶段依赖后期能力；文档本地链接、状态行、README索引与定向空白检查通过。
- 未运行Maven、业务测试或DDL；未创建美业工程。以上结果只证明设计与计划一致，不证明业务可运行。

## §7 三行业公共写入基础实施（2026-10-20）

零售作为公共核心，餐饮最新已提交693ad5b77（NEXT-06实现ec5aba9a5）作为增强基线，美业接入。只导入经git status逐文件确认干净的15项已提交差异。原餐饮工作区的未提交NEXT-08等内容未导入。

| 任务 | 交付 | 验收 |
|---|---|---|
| S1 | auth/StoreRequestScopeValidator及餐饮薄封装 | 通用主体/门店比较；非法原门店头拒绝；零售旧过滤器不变 |
| S2 | idem/IdempotencyScope、PlatformOutboxPublisher、PlatformWritePrerequisites | 固定行业操作域、严格key、统一PLATFORM源和真实共享表检查；旧幂等接口兼容 |
| S3 | 两行业WriteGuardConfiguration及美业Web接线 | 复用原Mapper/Service；错事务/缺来源拒绝，schemaReady与可写前置分开 |
| S4 | BAPI-002原Controller/Service/Req/VO、ActionLog实体Mapper、spi/beauty事件合同 | 首个真实政策发布；不可变版本/头CAS/审计/幂等/outbox同主库事务；首建唯一键仲裁 |
| S5 | 零售/餐饮回归、美业真实HTTP和一次性MySQL测试 | 同键一次、异参冲突、并发首建、过期版本、跨店/伪造actor、原子回滚 |

每项先失败测试、后实现、绿灯及提交。公共代码放现有模块，不增加Maven子项目，不依赖food-core/web。原零售默认店回落和无key兼容保持；新入口采用严格校验。客户端key1～128字符；操作域BEAUTY:v1:<SHA256>，输入operation/entity/store逐段UTF-8长度编码。请求hash使用typed规范字段，包括expectedRevision/validFrom/policy，排除追踪字段。

首写沿既有方法签名；expectedRevision=0仅表示创建尚不存在的门店政策头，首版根version=1，后续必须带正数当前version。政策内容本批细化为bookingAheadDays、cancelRule（freeCancelBeforeMinutes/allowCustomerCancel）、allowedModes；P1仅PREPAID_SINGLE模式，不据配置开放后付。OffsetDateTime统一UTC毫秒持久化。事件合同位于shop-base/spi/beauty，正式路由未登记则发布失败、整体回滚，不以测试archive路由冒充投递就绪。政策本地发布不增加长流程ServiceSubmission。

### 7.1 实际实现边界

S1～S4 已落地；S5 以本节后续验证记录为准。共用能力所有者是零售已有核心包，
不是由美业反向承载零售。餐饮薄封装和美业入口使用共用门店校验；既有零售过滤器保持原行为。
BAPI-002 Controller 增加可选原始门店头 requestedStore，已同步 Java 方法真源；Service 签名保持不变。
业务审计沿系统员工身份；预约匹配所需技能、排班、资源及工作流留在后续共用人员/资源合同中。
已有疗程权益可复用共用权益合同；本次政策发布不实施售卡、核销或资金流程。

独立复核指出事务内元数据检查重复取连接可能耗尽连接池，已以真实 MySQL 单连接池复现，
改为 DataSourceUtils 统一复用/释放连接，不改变事务连接状态；复核确认原问题关闭。
本地政策、操作日志、幂等和事件原子写入不代表正式消费者和 standalone 远程调用提供方已完成。
readiness 保持503；未完成任务继续沿原49任务索引推进，不将本切片等同全量美业交付。

### 7.2 餐饮最新提交追平

收尾时餐饮已新增提交 `c634de551`（门店政策读取，NEXT-08），已原样 cherry-pick 为本分支 `74fdaf218`。
因此最终对齐餐饮基线为 c634de551，而不是停留在起始693ad5b77；未提交内容仍未导入。
新增餐饮政策 Controller 沿 FoodRequestScopeValidator 薄封装调用公共校验器；
本轮公共抽取及美业发布实现提交为 `f4f89ec3e`。在此最终组合上重新验证共享构件、餐饮 reactor、
临时 MySQL 及零售宿主同时嵌入餐饮/美业的构件形态。

### 7.3 本批最终验收记录

| 验证范围 | 实际结果 |
|---|---|
| 公共构件 reactor | 210项通过，0跳过 |
| 美业完整 reactor | 47项通过，0跳过 |
| 美业临时 MySQL | 31项通过，含单连接池、并发首建和真实事件写入后回滚 |
| 美业宿主 HTTP→SQL | 1项通过，真实 Controller/Service/事务/数据库；外部目录事实和归档路由为测试配置 |
| 最新餐饮完整 reactor | 91项普通测试通过；16项数据库专用例由独立脚本执行 |
| 最新餐饮临时 MySQL | 34项通过，0跳过（15主库＋18防护/政策读取＋1独立服务） |
| 零售宿主构件形态 | 默认零售；餐饮/美业同时embedded 11通过＋2不适用跳过；同时standalone13通过 |
| 设计与 SQL | 五组设计生成物、冻结迁移一致；Node6项；没有新增SQL方言依赖 |
| 服务启动 | 无数据库、关闭美业时真实JAR启动；liveness200/readiness503 |
| 门店头消融 | 删除原始门店头比较后4例中1个断言失败、0错误；恢复后公共构建通过 |

独立复核已关闭单连接池问题。全仓文档规范仍为5通过/3既有失败：Mermaid数量基线、
2处既有餐饮任务索引断链、5篇既有餐饮索引遗漏；没有新增美业断链。
本批S1～S5完成，不把完整49任务、正式事件消费或美业standalone业务能力标成已完成。
