# 美业系统后端开发任务计划

> 状态：实施中 · 2026-10-21 · 已完成首批P1数据层及政策/定义业务；全量业务未完成。
> **执行方式：** 使用`superpowers:executing-plans`逐任务实施，每步使用复现过的红灯/绿灯证据；本会话只推进美业。
> **Spec：** [功能矩阵](../requirements/美业系统-多端功能矩阵.md) · [独立Java结构](./TDD-美业系统-独立项目与包依赖方案.md) · [API清单](./design/美业系统-API清单.md) · [类与方法](./design/美业系统-Java类与方法清单.md) · [逐API方法](./design/美业系统-Controller-Service-Mapper方法清单.md) · [数据库设计](./design/美业系统-数据库结构设计.md)。

## 当前执行记录（2026-10-21）

实现位于独立 beauty-data worktree，保护原工作区未提交的餐饮与前端变更。代码入口：[美业工程](../../backend-plugins/shop-industry-beauty/README.md)。

| 任务 | 实施状态 | 实际交付与边界 |
|---|---|---|
| ENG-01 | 完成 | 五构件可独立构建；只有server可执行；13项完整依赖架构测试及非法依赖反例 |
| ENG-02 | 完成 | 默认关闭、显式部署形态；宿主retail/embedded/standalone验证；独立服务无用户入口；实际JAR无数据库启动及200/503探针 |
| DATA-01 | 完成 | 首批16表归属、BaseEntity字段、tenant/entity索引、源行guard身份与可空唯一键审计；共享14项及P3/P4留在原任务 |
| DATA-02 | 完成 | 冻结V1、专属历史；MySQL实际DDL与Flyway重入/漂移测试；拒绝无归属旧美业表；平台Flyway共存 |
| DATA-03 | P1完成 | 主工厂/主事务/审计接线、16项P1实体数据域及真实SQL父链范围验证完成；后续阶段随各批联验 |
| DATA-04 | 本批切片完成 | 政策/定义5项实体Mapper；不可变发布、版本CAS、历史查询和父链范围已真实SQL验收，实际记录见§8 |
| ENG-03 | 部分完成 | 政策/定义可信入口、幂等/审计/outbox及双部署已验收，后续聚合仍须各自完成业务级前置 |
| POL-01 / DEF-01 | 部分完成 / 主接口完成 | 政策查询发布、定义查询发布已交付；POL-01公开营业信息BAPI-144未在本批范围 |
| DATA-05/06 | 数据层完成 | 7项实体、15个原始Mapper方法、25项真实MySQL用例；见§9，不含预约/工单业务Service与工作台API |
| DATA-07 | 数据层完成 | 3项新实体、9条原始Mapper方法，复用已有动作日志；21项真实MySQL通过，见§10 |
| SHR-01a | 基础切片完成 | 共用人员5表及内部事实查询，见§11；SHR-01/02整体、写维护和远程接入仍未完成 |
| SHR-02a | 基础切片完成 | 规范资源主档及内部事实查询，见§12；排班/占用仲裁及维护入口后续 |
| SHR-01b | 基础切片完成 | 周班表及内部窗口查询、变更历史，见§13；审批/写维护与完整SHR-01/02后续 |
| SHR-02b | 02b-1a/1b/1c及2a基础完成 | 归一、五表、持久命令/归属CAS及锁内准入完成；见详细方案§5.7；原子容量预留与生命周期仍待实施 |
| 后续业务 | 未完成 | 共用资源、排班、来源恢复与预约/日历/任务业务闭环仍待实施，不以数据层通过计作营业可用 |

首批基础阶段验证（后续批次增量见§8、§9）：独立reactor 42项测试通过，Node生成器6项通过，隔离MySQL DDL约束通过，23项真实JDBC IT通过。
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

下一阶段SHR-02b详细需求与子阶段见[专门方案§2](./TDD-共用资源容量与组合占用-SHR02b.md#2-需求清单与交付拆分)，本轮为设计细化，不启动实现。

共用排班SHR-01b本批计划见§13.2，验收见§13.3。

后续共用资源SHR-02a的当批实施计划见[§12.2](#122-实施计划本会话直接执行)，验收见§12.3。

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
| DATA-05 预约3项 | entity/BtyAppointment、BtyAppointmentItem、BtyAppointmentAssignment及对应Mapper | changeSchedule、markArrived、changeStatus、selectByAppointmentRevision；原安排不覆盖，指定意愿不当实际执行 | `AppointmentPersistenceIT#keepsPreviousAssignmentRevisionAndRequestedPersonSeparate`、`#rejectsStaleScheduleAndWrongStoreOrTerminalState`、`#limitsAssignmentsToAuthorizedUndeletedParentChain` |
| DATA-06 工单任务4项 | entity/BtyWorkOrder、BtyServiceTask、BtyServiceStep、BtyServiceParticipation及对应Mapper | freezeScope、advanceScope、closeFrozenScope、recordCompletion、switchBindingRevision、endParticipation；范围/绑定/根版本分开，参与区间追加 | `ServiceTaskPersistenceIT#rejectsOverCompletionAndStaleBindingAndKeepsPartialWorkOpen`、`#freezeBlocksTaskMutationAndScopeAdvance`、`#closesOnlyCurrentParticipationAndNeverOverwritesPersonnel` |
| DATA-07 来源编排4项 | entity/BtyServiceSubmission、BtyTradeBinding、BtyActionLog、BtySourceAllocationGuard及对应Mapper | selectByRequestKey、advanceProgress、selectSourceForUpdate、allocateQuantity、releaseUnconsumedQuantity、refreshSourceSnapshot；同源guard跨任务累计上限，原号/原绑定留存 | `SourceAllocationConcurrencyIT#allocatesOnlyWithinOriginalQuantityAndExactRevision`、`#sourceRevisionsCannotCreateAnotherGuardForSameCanonicalLine`、`ServiceSubmissionPersistenceIT#sameRequestReturnsOriginalPayloadAndFrozenSourceCommand`、`SourceAllocationConcurrencyIT#doesNotReleaseConsumedQuantityEvenWhenHistoricalBindingWasSoftDeleted` |

每批具体步骤：

- [ ] 先用本批真实表在隔离数据库写失败测试：范围/唯一键/版本/数量/历史/审计；跨事务并发使用独立连接和稳定仲裁行。
- [ ] 新增当批实体，继承BaseEntity，字段/表名完整驼峰一致；JSON快照按既有序列化约定，私有护理字段尚未进入本批。
- [ ] 新增当批嵌套BaseMapper；继承insert/select/update不重写空方法，自定义SQL只为实际锁/CAS/批量查询，影响行数为0必须转明确冲突而非成功。
- [ ] 纯任务按root→稳定排序task→子行；涉及来源统一按排序guard→root→task→绑定/提交锁序联验（§10收敛此前guard位置歧义）；自定义标量更新明确写审计/version；不能用synchronized、空SELECT FOR UPDATE或无版本update替代约束。
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

## §8 政策与项目定义实施批次（2026-10-20）

用户已批准上一轮列出的第1、2项；沿现有五模块实现，不提前开发预约。使用 executing-plans/TDD，原工作树不变。

### 8.1 规则与文件边界

- N1：补 StorePolicyQuery/StorePolicyVO、StorePolicyService#getPolicy 及原 BizStorePolicyController GET。
  读取本店 ACTIVE 头下 effective_at<=at 的最新有效版本（effective_at降序、revision_index降序）；
  返回配置头revision及有效版本revision，未来版本不提前生效，缺有效版本404，不能将配置当交易能力许可。
- N2：在 api/spi 放 BeautyPolicyInternalApi、BeautyDefinitionInternalApi；channel/client 实现两种远程Service；
  server/api 提供内部端点，沿现有服务令牌保护。actor仅由平台认证入口委托，内部请求显式验证entity/store/actor，
  自带query.storeNo不得偏离委托门店；审计通过受控内部请求建立短生命周期操作身份并finally还原。
  ServiceName新增BEAUTY；所有网络调用在本地主事务之外，不在写锁中请求公共商品服务。
- N3：商品权威域补 ServiceProductFactsPort/实现及内部只读合同，返回本店SERVICE SKU的商品号、基础时长和实时报价；
  门店/主体归属、上架及类型检查在商品所有者完成。美业只存基础时长快照，duration必须与商品事实一致；售价不写bty表。
  ServiceDefinitionService按既有listDefinitions/publishDefinitionVersion签名实现；duration细化为minutes/bufferBeforeMin/bufferAfterMin，
  requirements使用现有字段，资源类型STAFF/ROOM/SEAT/DEVICE，selectionRule=ANY_QUALIFIED，P1单步骤。
  首建expectedRevision0、根version1；后续CAS。定义版本与要求追加，跨主体和同主体跨店父链隔离；
  查询at使用版本created_at（现有定义表无effective_at），按创建时间/版本号选历史版本，禁止改冻结V1。
  分页增加page/size（默认1/20，上限100）并同步方法真源。步骤固定SERVICE/顺序0；复杂步骤留P4。
- N4：政策/定义发布事实采用明确的归档合同：SQL版本和action_log是权威，事件作为可追溯发布凭据，
  本批没有必须异步执行的业务副作用。提供显式部署配置 shop.outbox.archive-only 两个精确事件名，
  不默认修改公共路由、不注册空消费者、不据归档宣称通知已交付；没启用正式配置或消费者仍拒绝写入。
  后续如要求异步投影/通知，先迁移路由并验收消费者，再去掉对应归档合同。

### 8.2 独立验收任务

| 任务 | RED→GREEN步骤与准出 |
|---|---|
| N1 政策有效版本读取 | 先真实MySQL测试未来版本/时点等价/异店拒绝，再DTO、Service、Controller；缺有效版本404且无写副作用 |
| N2 远程提供方 | 先真实HTTP测试缺/错令牌、范围不符、原actor审计、业务400/403/404/409传递，再内部合同、客户端、配置；web/channel不得依赖core |
| N3 项目定义发布查询 | 先真实MySQL测试商品类型/时长不符、幂等、版本CAS、并发首建、不可变父链、回滚，再共享商品合同与本域业务；外部查询不占主事务 |
| N4 路由与回归 | 正式归档配置由测试读取并使用真实outbox；非法路由仍回滚；三行业共享回归、设计生成器、SQL冻结、消融及独立复核 |

每项运行目标测试确认失败原因，再实现并运行完整受影响reactor；各完成点提交。最终按实际测试回填§8.3。
Ruling：前两项既有接口实施属于1档；补商品SPI不是新商品域，也不新增表/账本。已有用户执行授权覆盖常规实现选择，不重复请求审批。

### 8.3 实际交付对账

N1/N3已实现原四项HTTP（BAPI-001/002/007/008），DTO和方法真源已重新生成；N2实现两组内部合同、
channel远程Service、server内部入口和商品反向合同。商品所属实现位于shop-core/product/port，
公共合同在shop-base/spi/product，PLATFORM内部端点在shop-app/portal/internal；美业没有导入商品Entity/Mapper。
N4配置为core资源beauty-publication-archive.yml，独立server沿原JdbcOutboxStore注册PLATFORM归档存储，
缺正式路由拒绝写入；standalone实际TCP到DB已确认两类事件ARCHIVED和原actor审计。

执行中反例：新增多市场SKU导致selectOne多行，现补CN条件，与已有GoodsQueryPort同口径；
外层事务调用定义发布导致外部事实查询落入事务，现显式拒绝ambient事务；两处均先失败后修复。
历史定义created_at沿现有AuditMetaObjectHandler的JVM本地时间，按稳定部署时区转换，不能把它当UTC裸时刻；
本轮没有修改零售全局审计或冻结V1。政策effective_at仍为独立明确的UTC值。

界线：DEF-01主HTTP切片完成情况以最终验证为准；POL-01的BAPI-144公开营业信息未在本批范围，
完整预约/资源匹配、任务工作台、通知及投递引擎仍待后续。readiness保持503。
全局架构检查发现既有OpsPointsInspectionController的全限定注解导致1项失败，先核验基线并记录，不冒充新代码违规。

### 8.4 本批验收与剩余边界

| 对账 | 实际证据 |
|---|---|
| N1 有效政策、N3定义发布及父链 | BeautyCatalogIT10项真实MySQL通过；含未来政策、历史选版、首版前空页、幂等重放、商品错范围/时长、并发首建、outbox实际flush后回滚与外层事务拒绝 |
| N2 双部署 | BeautyStandaloneEndToEndIT实际TCP→独立安全链→业务→MySQL通过；BeautyPolicyEndToEndIT实际宿主HTTP→SQL通过，包含政策GET时间参数及跨店查询拒绝 |
| N4 正式归档配置 | 独立服务测试显式导入实际YAML，SQL确认两类事件ARCHIVED、审计actor为原委托人；不据归档冒充业务投递 |
| 商品事实 | ServiceProductFactsPortTest6项真实H2 Mapper SQL通过；真实HTTP反向商品错误映射测试通过；外部商品依赖在独立端到端中提供受控事实测试替身 |
| 普通reactor | 美业54项通过；公共模块210项通过；餐饮91项普通测试通过（16项数据库专用未在本轮重复，上一批34项已执行，不算本批新证据） |
| 宿主组合 | 双standalone14项形态/自动装配＋6商品事实＋6i18n共26通过；双embedded11通过＋2个不适用standalone形态跳过 |
| 设计与迁移 | 五组设计生成器、冻结SQL、Node6项通过；没有新增SQL方言依赖或修改V1 |

独立复核发现2项P2并已补反例修复：分页前EXISTS过滤尚无历史版本的头，确保records/total一致；
查询record构造仅填默认值，Bean Validation处理HTTP非法分页，Service继续显式防御，内部query加@Valid。
两项均经历真实RED→GREEN，复核确认代码原因关闭。

全局检查保留真实结论：ArchitectureTest15/16，既有OpsPointsInspectionController的@PathVariable全限定名违规在基线9ae57e5e6已存在；
BackendI18nParityTest6/6；文档守卫5/8，既有Mermaid基线及餐饮链接/索引3项失败未扩大，不修改无关业务或抬升基线。
本批只交付政策/项目定义切片，readiness503不变，未连接营业库、未合并或推送。

最终补验：standalone宿主从实际AutoConfiguration注册两个Remote Service与两个公开Controller，
无beauty-core；关闭美业不创建远程提供方，8项美业形态/装配用例全部通过。
消融移除query的@Max(100)后，4项Web用例中1个断言失败/0错误；finally恢复，完整54项reactor通过。
实际关闭态JAR不连接数据库启动，liveness200/readiness503。最终源文件按120列及补丁空白检查收口。

## §9 DATA-05/06：预约与工单任务数据切片

本批接续183b04e8a，工作树干净；只实现已定义的7项Entity和15条自定义Mapper方法，不新增HTTP、人员资源主档或迁移。

### 9.1 实施规则和接口对账

- E1 预约：BtyAppointment/Item/Assignment继承BaseEntity，字段沿冻结V1；AppointmentMapper原4方法与AssignmentMapper原读取方法不改签名。
  安排revision为显式历史版本，不从状态更新后的根version推算。改约限HELD/CONFIRMED且版本/前态一致，必须有非空新hold与合法时段；旧安排行只追加不覆盖。
  changeStatus只允许HELD→CONFIRMED/CANCELLED/EXPIRED、CONFIRMED→CANCELLED/NO_SHOW；确认须hold。
  markArrived只允许CONFIRMED→CHECKED_IN、首次签到、非空时间；其他终态拒绝。
  assignment读取从安排→项目→预约联结tenant/entity/业务号/store，跨店/跨主体/软删父链不得读到；requestedPersonnel与安排人员分开保存。
- E2 工单任务：BtyWorkOrder/ServiceTask/ServiceStep/ServiceParticipation，Mapper方法按既有清单。
  所有标量CAS显式version+1及actor/updatedAt，错范围、错版本、非法前态返回0；后续业务Service必须将0映射冲突。
  工单范围版本、task绑定版本、BaseEntity版本分别校验；旧task的创建scopeRevision允许小于当前根scopeRevision。
  advanceScope仅OPEN/IN_SERVICE且未冻结；freezeScope接受OPEN/IN_SERVICE/SERVICE_FINISHED，关闭仅SERVICE_FINISHED+已冻结。
  closeFrozenScope重查真实任务数量/终态、步骤、参与段和当前绑定引用，不以计数快照关闭；空工单不关闭。
  recordCompletion仅IN_SERVICE，正增量且最多六位小数、不超过qty-voided、最终完成须至少一步且步骤齐备；只有全额完成设置DONE/finishedAt。
  changeStatus不提供任意target捷径：PENDING/PAUSED→IN_SERVICE、IN_SERVICE→PAUSED；VOIDED仅PENDING、从未开始且done0/voided=qty。
  启动/暂停恢复与绑定切换要求净数量qty-voided>0；全部作废的待执行任务只能进入VOIDED。
  switchBindingRevision只前进，须本域相同任务的新绑定行按uom完整覆盖qty-voided（来源外部许可/guard原子分配仍由DATA-07/SRC负责）。
  step完成仅IN_PROGRESS且task在服务中，开始结束合法、前序步骤已DONE/SKIPPED；resultSnapshot非空JSON。
  participation只结束未结束区间，时间不倒置、保留人员与原计划；允许在冻结待关闭期间收口参与段，但已关闭/取消根不可变。
- 锁序：后续业务事务先锁workOrder，再稳定排序锁task，再子行；冻结/完成并发验收遵循此序。
  本地SQL检查不代表Core/Pay许可或公共资源占用已确认，不在本批创建假服务、假资源或成功占位。

### 9.2 RED→GREEN任务及文件

| 任务 | 文件 | 验收 |
|---|---|---|
| E1 | beauty-core/entity/BtyAppointment*.java；BeautyMappers内3个Mapper；BeautyDataScopeRegistration | AppointmentPersistenceIT：字段/主库审计、安排历史、改约CAS及回滚、状态/签到、父链隔离、同版本并发单胜者 |
| E2 | entity/BtyWorkOrder/BtyServiceTask/BtyServiceStep/BtyServiceParticipation；BeautyMappers内4个Mapper；注册数据域 | ServiceTaskPersistenceIT：冻结/范围、数量上限、绑定单调、步骤顺序、参与结束、真实关闭条件、租户/门店隔离、事务回滚和根锁并发 |
| E3 | scripts/verify-beauty-persistence.mjs、README及本页 | 先缺类红灯再完整真实MySQL；普通reactor、冻结SQL/生成物、关键CAS消融、独立复核；本地提交不合并推送 |

Ruling：本批复用V1已存在的trade_binding表做只读局部守卫，不实现DATA-07 Mapper或任何外部资金/资源权威；多聚合业务编排仍待后续Service验收。

### 9.3 本批实际验收

- 数据层范围：7实体、7个Mapper接口（其中预约项目只用BaseMapper）、15条原始自定义方法；累计13实体数据域。
  DATA-05/06仅持久化层完成，预约/任务Service与Controller、DATA-07来源编排及公共资源匹配未交付；readiness仍为503。
- RED→GREEN：缺实体16项断言失败；数量精度/缺步骤/关闭早于任务完成3项反例失败；
  已开始任务误作废1项反例失败；零净数量启动/空绑定切换2项反例失败；全部修复后最终66项真实MySQL通过，0失败/错误/跳过。
  新增预约8项、工单任务17项；原有41项保持通过。解析器PRIOR别名改为earlier_step，不绕过平台拦截器。
- 完整美业reactor 54项通过。五组设计生成器、冻结V1和Node6项通过；SQL方言扫描无新增依赖；新增Java120列及补丁空白检查通过。
- 消融：仅移除数量六位精度守卫，23项用例中1断言失败、0错误；finally恢复，最终全量回归通过。
- 独立复核核对7实体字段、15原始签名及父链/CAS/时间/数量守卫；发现的零净数量P2已有真实反例与修复复核。
  基于原冻结结构实现，不修改全局审计和V1，不连接营业库，不改原餐饮工作区。

最终双部署补验：BeautyStandaloneEndToEndIT实际TCP→业务→MySQL1项通过；BeautyPolicyEndToEndIT宿主HTTP→MySQL1项通过。
实际可执行JAR关闭态无数据库启动，liveness200/readiness503；全部以最终源码构建后执行。

## §10 DATA-07：来源分配与提交意图数据层

基线a0965d62d；沿冻结V1实现ServiceSubmission、TradeBinding、SourceAllocationGuard三个新Entity，ActionLog已实现不重复创建。
新增3个嵌套Mapper、9条原始签名方法及数据域注册；没有新增表、公开API、资金/资源权限或恢复业务Service。

### 10.1 规则与先行取舍

- F1 提交：tenant/entity/store显式范围；有workOrderNo时还须同范围有效工单。requestKeyHash查回原payload/frozenCommand，不能修改原意图。
  原签名不带action，但数据库唯一键带action；同一摘要匹配多行时由MyBatis单行合同拒绝歧义，不LIMIT1选任意记录。
  后续Service必须将action纳入规范键摘要，并比较payloadHash；本批不假装单纯读取已完成异参业务错误映射。
  固定迁移：RECEIVED→SOURCE_PENDING/REJECTED；SOURCE_PENDING→BOUND/RECOVERING/REJECTED；
  RECOVERING→SOURCE_PENDING/BOUND/REJECTED。三个非终态允许自身CAS用于认领租约；BOUND/REJECTED不能重新激活。
  活动态nextRetryAt按DATETIME(3)实际持久化精度严格晚于updatedAt；终态清空retry，REJECTED须非空failureCode。未知结果必须RECOVERING，
  只有后续Service查到权威明确拒绝后才可REJECTED，Mapper状态CAS不代表外部证明。
  selectDueForUpdate只取三个非终态、nextRetryAt非空且到期的行，稳定排序、limit1..100、FOR UPDATE SKIP LOCKED；
  认领者必须同短事务advanceProgress延后retry并提交，外部调用不持锁。无retry的行不擅自视作可恢复。
- F2 来源guard：同主体规范sourceKind/sourceNo/sourceLineRef唯一，不按store/sourceRevision复制；缺行由唯一键仲裁，空SELECT不是锁。
  allocateQuantity只接受正六位数量、expectedVersion/sourceRevision一致、不超sourceQty；refresh只接受更高来源修订、
  新数量不少于已分配量和64位摘要，保持累计allocatedQty。来源权威/门店/uom由后续Service核验。
- F3 释放：正六位数量、不超已分配量、CAS与审计；保留原绑定历史。
  原签名没有目标binding或消费水位，因此采用保守整guard守卫：所有历史绑定（包括软删）都必须拥有可验证的本主体任务/工单/提交，
  来源三元组/uom一致、同门店父链、任务从未开始且全量VOIDED、提交仅BOUND/REJECTED，否则拒绝整次释放。
  无绑定的预留量可释放；已消费、在途、残缺父链不释放。代价：存在一项受保护分配时，暂不能局部释放该guard其他空闲量，
  待SRC业务有正式目标绑定/消费事实合同再扩展，不能靠估算消费量放行。冻结命令尚未形成绑定的在途引用必须由未来协调Service验证。
- F4 绑定批查：按taskNos/bindingRevision读取；空/null列表返回空，沿binding→task→workOrder与binding→submission双父链确认tenant/entity/store，
  支持不同任务按版本分组批查；不覆盖旧绑定。
- 多根锁序：涉及来源时先按规范身份排序锁guard，再排序工单根→任务→绑定/提交；纯任务仍沿§9根→任务→子行。
  恢复扫描先在独立短事务认领并提交，不带扫描所得submission锁进入guard业务事务，以免与上述顺序反转。

### 10.2 任务与验证

| 任务 | 文件范围 | 行为证据 |
|---|---|---|
| F1 | BtyServiceSubmission、ServiceSubmissionMapper | 原请求/冻结命令恢复、歧义拒绝、状态CAS/审计/租约、到期范围与SKIP LOCKED、终态禁止重启 |
| F2/F3 | BtySourceAllocationGuard、SourceAllocationGuardMapper | 共享guard跨任务并发上限、版本跨修订唯一、精度/陈旧修订拒绝、刷新兼容、释放消费/在途/异常父链拒绝、回滚 |
| F4 | BtyTradeBinding、TradeBindingMapper及数据域注册 | 双父链、批查空参/指定版本、历史保留、真实Mapper主库审计和主体域 |
| F5 | verify-beauty-persistence.mjs、README、TDD/ledger | 先缺类RED、实际MySQL GREEN，完整reactor与原66项回归、双部署端到端、消融、一次独立审查及本地提交 |

数据层冲突仍以影响行0返回，后续Service必须转为明确业务冲突。完整跨域恢复、源行权限/消费证明、调度Job与公开API尚未交付。

### 10.3 实际交付与验证对账

| AC | 实际测试方法 | 结果 |
|---|---|---|
| F1 原请求/参数/冻结命令保留、歧义拒绝 | ServiceSubmissionPersistenceIT#sameRequestReturnsOriginalPayloadAndFrozenSourceCommand、#rejectsAmbiguousRequestHashAcrossActionsInsteadOfPickingOne | 实际Mapper插入同action同键异参被唯一约束拒绝，原payload保持；跨action摘要歧义拒绝 |
| F1 固定状态与审计 | #progressCasRetainsOriginalIntentAndRejectsTerminalRestart、#activeLeaseNeedsFutureRetryAndRejectedNeedsFailureCode | 陈旧版本/非法迁移/终态重启均0，成功显式审计，原号/命令不替换 |
| F1 恢复认领 | #dueRowsAreBoundedScopedAndLeasedInSameTransaction、#concurrentRecoverySkipsLockedIntent | 实际双连接SKIP LOCKED、限额/范围、同事务租约已验证 |
| F1 持久化租约精度 | #leaseMustRemainFutureAtPersistedMillisecondPrecision | 亚毫秒先RED：expected0/actual1；按DATETIME(3)比较后GREEN |
| F2 共享来源行上限 | SourceAllocationConcurrencyIT#concurrentTasksShareOneGuardAndCannotExceedSourceQuantity、#sourceRevisionsCannotCreateAnotherGuardForSameCanonicalLine、#refreshPreservesAllocatedQuantityAndRequiresNewerCompatibleSnapshot | 并发仅一个1.5分配成功，来源2数量不超限；修订不另建guard，刷新保留已分配量 |
| F3 释放保护 | #doesNotReleaseConsumedQuantityEvenWhenHistoricalBindingWasSoftDeleted、#doesNotReleaseAllocationReferencedByRecoveryInFlight、#malformedParentChainCannotAuthorizeRelease、#releasesFullyVoidedNeverStartedTaskWithoutDeletingBindingHistory | 已消费/在途/异常父链拒绝，从未开始全量作废可收口且历史保留 |
| F4 批读与主库数据域 | #batchReadsOnlyRequestedBindingRevisionAndAuthorizedDoubleParent、#entityMapperInsertUsesPrimaryAuditAndSubjectDataScope；ServiceSubmissionPersistenceIT#actualEntityInsertUsesPrimaryAudit | 指定版本/空列表/错门店/错主体、实际审计与数据域通过 |

首轮缺类20项断言失败、0错误；实现后20/20通过。独立审查指出持久化租约精度，按合同影响升级为P2，补反例RED→GREEN。
最终普通reactor54/54、真实MySQL87/87（新增21＋原66），全部0失败/错误/跳过；不以H2或mock冒充实际约束。
消融仅移除来源修订单调守卫，21项中1断言失败/0错误；finally恢复后完整回归通过。
五组设计生成器、冻结V1、Node6项及SQL方言无新增检查通过；方法JSON及生成清单已同步实施边界，9条原签名不变。

文件对账：beauty-core新增3Entity、2个IT；改Mapper集合和数据域注册；改MySQL验证脚本；README、TDD、方法JSON/对应生成方法清单及ledger同步。
ActionLog复用已有类，未新建Repository/转发Service、未加公共接口/表/错误码/配置，不修改零售或餐饮代码。
首批16项P1持久化合同完成；49任务整体、SHR共用人员/资源及跨域恢复/预约工作台仍待后续，readiness503不变。

最终双部署与可执行验证：BeautyStandaloneEndToEndIT实际TCP→业务→MySQL1项通过；BeautyPolicyEndToEndIT宿主HTTP→MySQL1项通过，全部0失败/错误/跳过。
实际最终JAR关闭态无数据库启动，liveness200/readiness503。本地检查点保留在美业隔离工作树，不合并/推送。

## §11 SHR-01a：共用人员基础数据与批量事实查询

### 11.0 核验与本批边界

基线aaa7ff243；只读核验最新本地餐饮分支codex/food-next01=07f6b2347。其shop-merchant没有新增人员/资源实现，
账号与预约槽实现沿现有共同基线；不合并其无关餐饮/前端工作。原目录仍有用户未提交变更，不写入该目录。
复用MchAccount作为可选登录身份、MchStoreRole作为权限，不把权限角色当业务岗位。现有AppointmentSlotPort只支持单店总名额，
不具备跨店STAFF、技能、组合hold/原子替换；本批保留原接口及行为。

发现依赖：原SHR-01包含按resourceNo排班，而SHR-02依赖SHR-01。按既定所有权拆为SHR-01a人员5表→SHR-02a规范资源→SHR-01b班表→SHR-02b组合仲裁。
本批只交付01a基础数据和内部批量事实查询，不实现人员维护HTTP、账号绑定/任职写Service、排班、资源占用或美业远程接入；SHR-01/02整体不标完成。

### 11.1 设计与合同

- shop-base新增spi.personnel.PersonnelFactsPort，getBatch(Query)：tenant/entity/store、1..200个明确personnelNos、UTC时点（截到毫秒）；
  返回稳定personnelNo与assignmentNo、业务姓名/本店等级、公开标记、生效区间、业务岗位和已核验有效资格。
  不返回账号号、电话、联系资料或权限；有无/停用登录账号不决定业务任职。无结果是没有有效事实，数据库/范围查询错误不得伪装空闲或匹配成功。
- shop-merchant新增5个Entity，原MerchantMappers增加对应5接口；实际任职、岗位、资格投影PersonnelAssignmentFactRow/PersonnelPositionFactRow/PersonnelQualificationFactRow归merchant.dto。
  查询按tenant/entity/store与有效主体/门店/人员/任职父链，区间[validFrom,validTo)，岗位只读ACTIVE，资格只读VERIFIED且有效、门店为当前店或主体通用。
  返回全部符合条件的资格及其scope，匹配策略/开工许可由后续实现；不在本查询中授予收款、隐私或服务权限。
- PersonnelFactsPortImpl直接查询本域Mapper。四处上限：最多200个人、最多200条任职（SQL取201作溢出哨兵）、岗位/资格各最多2000条（取2001）；
  超限或同人员同店同一时点存在多个有效任职，明确拒绝，不能LIMIT后静默丢记录或任取一条。
  三次批量SQL在独立只读REPEATABLE_READ快照事务内执行；拒绝外层事务，调用方在自己的写事务外读取事实。
  数据域绕过仅封装内部可信查询，全部SQL仍有显式范围；通用BaseMapper的5表主体域仍注册。
- shop-app正式平台迁移V532__shared_personnel_foundation.sql，5表沿既有设计真源字段、业务唯一键带tenant/entity；
  CHECK保护状态、有效区间和公共version/deleted；不加跨域外键、不修改已应用迁移。账号主体/任职不重叠的写业务守卫与审计由后续维护Service完成，当前读取拒绝歧义。
  新表随数据库默认字符集/排序规则，避免引入MariaDB/MySQL专有排序规则。只在一次性MySQL执行，不连接营业库。

### 11.2 RED→GREEN计划与准出

| 任务 | 精确文件/职责 | 测试与预期 |
|---|---|---|
| P1 | backend/shop-base/.../spi/personnel/PersonnelFactsPort.java | Query拒绝缺范围、空/超200名单，毫秒规范化与不可变集合 |
| P2 | shop-merchant/entity/MchPersonnel{,Account,Store,Position,Skill}.java；mapper/MerchantMappers；dto/Personnel{Assignment,Position,Qualification}FactRow；port/PersonnelFactsPortImpl；config/PersonnelDataScopeRegistration | PersonnelFactsPersistenceIT：无账号、多店、资格过期/未核验/跨主体、权限角色不授技能、时间端点、父链停用、歧义、超限、事务边界、真实审计和主体数据域 |
| P3 | shop-app/resources/db/migration/V532__shared_personnel_foundation.sql；scripts/verify-personnel-persistence.mjs | 直接执行正式迁移；状态/区间/账号可空唯一键/业务唯一键真实MySQL验证；不以H2约束替代 |
| P4 | README、TDD本节、ADR-040及ledger | 全量merchant依赖测试、既有账号/slot回归、美业reactor与DB回归、SQL/设计/静态检查、消融及一次独立审查；只本地提交 |

先写真实IT并确认缺类红灯，再实现；新增功能的每项验收按实际方法回填§11.3。
架构决定沿既有多行业方案落在merchant，不新建行业人员表；ADR仅记录这次实施和阶段拆分，不重新定义账号权限。

审查P2：不区分大小写的数据库可能将子引用P/p、A/a关联到同一父行；Java不得按子引用原字符串分组。
实际MySQL三反例19项/3断言失败/0错误后，SQL投影统一取父行规范业务号，任职歧义与子事实分组使用规范号；不修改部署排序规则。
快照验收增加真实第二连接在第一读后更改岗位/资格，当前快照仍读原值，下次查询读新值；消融切为READ_COMMITTED须触发断言失败。

派生文档范围：新增merchant表/Entity/Port触发表清单、词汇、分层及ER生成；按当前分支真源同步派生物，不修改原餐饮工作区。全局ER同时收敛既有源与派生物漂移，不据此声称新增无关业务。

新增表配套血缘：personnel_no/assignment_no登记到共用主档/任职，link_no和position_no按不同业务登记冲突而非错误关联；正式V532收尾采用InnoDB，不指定字符集/排序规则。
H2派生schema随平台迁移重生成，仍以MySQL断言证明约束。新增业务键断言先失败再修复。
基线aaa7ff243在/tmp干净归档只读重现schema-lineage两个既有失败：跨表同名业务键未分类、gen-erd键表不一致；不修无关资金/营销语义，单独记录实际结果。


### 11.3 实际交付与验收

实际新增5个Entity、5个嵌套Mapper、3个本域事实投影、1个共用PersonnelFactsPort及其merchant实现、数据域注册，
以及正式V532和可重复的一次性MySQL验证脚本。人员归属merchant，零售、餐饮、美业共用同一事实合同。

| AC | PersonnelFactsPersistenceIT实际方法 | 验收结果 |
|---|---|---|
| 可选登录、业务身份与权限分离 | personnelWithoutLoginAccountHasStableBusinessIdentityAndNoContactLeak、loginPermissionsDoNotCreateBusinessPositionOrQualification | 无账号仍有业务身份，不泄露联系资料，权限角色不授岗位/资格 |
| 多店与有效区间 | samePersonnelCanHaveIndependentStoreAssignments、effectiveIntervalsAreInclusiveAtStartAndExclusiveAtEnd | 任职按店独立，[from,to)端点通过 |
| 有效岗位、资格及父链 | businessPositionIsReadFromActiveAssignmentRowsOnly、onlyVerifiedCurrentStoreOrEntityWideQualificationsAreReturned，以及foreignSubject/inactivePersonnel/inactiveOrForeignStore/archivedEntity四项 | 只返回有效本域事实，错误父链不返回 |
| 歧义与规模上限 | overlappingEffectiveAssignmentsFailInsteadOfPickingAnArbitraryRow、tooManyChildFactsFailInsteadOfTruncatingSilently、queryRequiresBoundedExplicitNamesAndNormalizesToMilliseconds | 重叠任职、超限、非法请求明确拒绝 |
| 数据库规范身份 | databaseEquivalentPersonnelRefsCannotBypassAmbiguity、databaseEquivalentAssignmentRefKeepsPositionFact、databaseEquivalentPersonnelRefKeepsQualificationFact | 实际CI排序规则三反例先RED再GREEN，不按子引用字符串误分组 |
| 快照与事务边界 | factsMustBeReadOutsideCallerWriteTransactions、laterFactQueriesUseSameSnapshotDespiteAnotherConnectionChangingRows | 拒绝外层事务；第二连接更新不撕裂当前事实快照，下次读取可见新值 |
| 正式DDL、主库审计及主体域 | formalMigrationEnforcesIntervalsStatusesAndNullableAccountUniqueness、actualMapperInsertUsesAuditAndDataScopeRestoresAfterTrustedLookup | 正式V532真实执行、CHECK/唯一键、实际Mapper审计和主体域通过 |

最终人员MySQL20/20，共用merchant及依赖253/253，原账号/预约槽/商品事实兼容37/37，均0失败、0错误、0跳过。
美业普通reactor54/54、实际MySQL87/87、独立部署与宿主HTTP→MySQL各1项通过；最终关闭态JAR无数据库启动，liveness200/readiness503。
RR消融切换为READ_COMMITTED时20项中1断言失败、0错误；finally恢复，最终20项全绿。独立审查一次，P2规范身份问题已修复，无遗留审查项。

全局派生与边界：五组美业设计生成器、冻结V1、Node6项、SQL无新增方言依赖检查通过。新人员血缘断言1项通过，
完整schema-lineage为14通过/2失败；干净基线aaa7ff243为13通过/同2项失败，分别为“新出现的跨表同名业务键必须被分类”与
“与gen-erd.mjs的键归属表一致”。遗留source_event_no/execution_no/entry_no/line_no及旧资金键歧义不在本批修改范围，不能声称全局闸门全绿。
新5表已进入生成H2及ER/表清单，H2与迁移表集合一致；约束证明仍以实际MySQL为准。

本批仅SHR-01a基础切片完成。SHR-01/02整体、人员写维护/账号绑定守卫、美业远程消费、排班、资源占用和预约日历均未完成。
接续顺序：SHR-02a规范资源身份→SHR-01b排班→SHR-02b组合占用仲裁，再接美业预约任务与工作元占用日历；保持零售既有账号和预约槽兼容。
只在美业隔离工作树本地提交，不合并、不推送、不执行营业库迁移。


## §12 SHR-02a：共用规范资源身份

### 12.0 需求与范围

架构决定：[ADR-041](./ADR/ADR-041-共用规范资源身份与跨店人员唯一.md)。

基线5c360212c；只读核对最新本地餐饮分支8e732eeb7（新增桌台配置，共用merchant/base与平台迁移未变）。
延续已批准的多行业架构及§11.3接续顺序；需求为功能矩阵D01与共用服务人员结论，设计真源为数据库JSON的mch_resource。
目标是为零售服务人员、餐饮服务员与房间/座位、美业技师和设备提供一致资源身份，供后续班表和仲裁引用。
本批仅规范资源主档和内部批量事实，不交付维护HTTP/写Service、排班、容量桶、hold、现场占用、迁移旧slot或独立美业远程入口。
既有STAFF人员模型已存在；不引入第二份技师、桌台或工作流对象。餐饮床位等命名仍由行业展示，本期物理类型遵循ROOM/SEAT/DEVICE。

### 12.1 合同与实现设计

- shop-merchant拥有mch_resource及MchResource、ResourceMapper。V533只增该表，复用BaseEntity并显式entityNo；冻结V532及旧slot不改。
  STAFF的storeNo为空、personnelNo非空且capacity=1；物理资源必须有storeNo且personnelNo为空，capacity>0。
  tenant/entity/resourceNo与tenant/entity/personnelNo唯一；保留软删行的唯一身份，不能按门店或状态再复制同一STAFF。
  CHECK显式验证NULL结构，避免SQL UNKNOWN放过非法组合；状态仅ACTIVE/SUSPENDED，继承数据库排序规则，无跨域FK。
- shop-base/spi/resource/ResourceFactsPort.getBatch(Query)：Query(tenantNo,entityNo,storeNo,Set<String> resourceNos,Instant at)，
  明确1..200名单，时间截到UTC毫秒；at筛选任职有效期，主档姓名/状态/容量读取当前值，不提供历史主档重建。ResourceFact(resourceNo,Type resourceType,storeNo,personnelNo,name,capacity,boolean openOccupied)。
  STAFF返回同一规范号和规范personnelNo，storeNo仍为空。物理门店号来自有效store父行；不输出物理id、账号、联系资料或开放占用凭证号。
- ResourceFactsPortImpl查询本域ResourceMapper，单条批量SELECT关联主体/门店及人员任职。STAFF必须有该店该时点ACTIVE任职和有效人员；
  所有路径显式tenant/entity/store和未删/启用/未归档父链。单SQL已形成一致语句快照，无多步读取事务；不新增空转Service。
  SQL返回父行规范人员号和本表规范资源号；类型用数据库相同比较语义投影成固定枚举值，避免合法CI/AI类别导致Java转换错误；同一规范资源出现多个有效任职或超过200行明确拒绝，LIMIT201作哨兵。
  openOccupied只代表主档存在阻断指针，不证明占用可释放或没有未来hold；所有事实都不代表整个服务时段可约、资格许可或开工许可。
- 新表登记merchant主体数据域；内部可信查询暂绕过通用数据域但完整显式范围，并恢复原上下文。调用方不得直接把不可信HTTP主体传入Port。

### 12.2 实施计划（本会话直接执行）

Spec：既有资源数据库结构/字段字典及本节。Java21/Boot4/MyBatis-Plus，零新依赖；沿用隔离工作树。
Review Focus：SQL NULL约束绕过、CI引用身份、软删后重复人员资源、跨主体/店父链、重叠任职不静默折叠；各在真实MySQL验证。

| 任务 | 文件与接口 | 验证 |
|---|---|---|
| R1 | shop-app/.../scenario/ResourceFactsPersistenceIT.java；scripts/verify-resource-persistence.mjs | 正式MySQL反例先RED，缺类而非编译失败；资源查询/DDL/审计与数据域 |
| R2 | shop-base/.../spi/resource/ResourceFactsPort.java；merchant/entity/MchResource.java、mapper/MerchantMappers.ResourceMapper、dto/ResourceFactRow.java、port/ResourceFactsPortImpl.java；config/PersonnelDataScopeRegistration增资源注册 | 同人跨店同一resourceNo、物理范围/多类型、时间端点、停用/错误父链、NULL/唯一键、CI规范号、重叠拒绝及上限 |
| R3 | shop-app/.../V533__shared_resource_identity.sql | 正式迁移CHECK/唯一键，不修改旧迁移；实际审计与主体隔离 |
| R4 | 本TDD、ADR-041、README、ledger、派生schema/ER/表/词汇/分层 | 原人员20项与旧账号/slot/商品37项、merchant依赖、SQL/生成检查、消融、独立审查后本地提交 |

- [x] 写R1并运行缺类RED；字段来自现有设计，不新建行业私表。
- [x] 实现R2/R3使实际MySQL GREEN；逐条核对上述Review Focus。
- [x] 消融人员资源不按店复制的守卫（唯一键）应触发原反例，再恢复GREEN。
- [x] 完成R4并回填AC与真实方法/结果，只完成SHR-02a，不标SHR-02整体完成。


### 12.3 AC与真实验证方法

| AC | ResourceFactsPersistenceIT实际方法 | 验证行为 |
|---|---|---|
| 同主体同人跨店只有一个规范资源 | staffHasOneCanonicalResourceAcrossEffectiveStoreAssignmentsWithoutLogin、onePersonnelResourceSurvivesSuspensionAndSoftDeletionAndUsesScopedUniqueness | 跨店同resourceNo、无账号可用，停用/软删不释放唯一号，跨主体/租户各自隔离 |
| 物理资源店归属与结构 | physicalResourcesUseTheirStoreAndDoNotInventPersonnel、formalMigrationRejectsInvalidResourceShapesIncludingNullCombinations | ROOM/SEAT/DEVICE按店返回，NULL非法组合、STAFF容量及类型/状态/version/deleted拒绝 |
| 任职端点与有效父链 | missingAssignmentAndHalfOpenValidityCannotMatchStaff、inactivePersonnelAndAssignmentBlockStaffFacts、inactiveForeignOrDeletedStoreBlocksPhysicalAndStaffResources、archivedOrInactiveEntityCannotReturnAnyResource、personnelAndAssignmentParentChainsCannotCrossEntityOrTenant | [from,to)端点、未删/启用/同域父链 |
| 范围及失败语义 | explicitScopeAndExplicitNamesDoNotReturnOtherResources、suspendedOrDeletedResourcesAreOmitted、queryFailurePropagatesInsteadOfBeingReportedAsNoMatchingResource | 只返回明确名单及有效同域事实，数据库错误传播 |
| 数据库等价身份/类别 | caseEquivalentReferencesKeepCanonicalPersonnelResourceAndStoreIdentity、databaseEquivalentTypeValuesProduceCanonicalSharedCategories | P/p、S/s、R/r父链规范号和CI/AI类别均按数据库语义投影 |
| 脏数据与边界 | overlappingAssignmentsCannotBeSilentlyCollapsedIntoAnEligibleResource、oversizedJoinResultsFailInsteadOfTruncatingOrDeduplicating、queryIsExplicitBoundedImmutableAndNormalizedToPersistedMilliseconds | 重叠/超200行拒绝，显式1..200名单、毫秒规范化、不可变输入/输出 |
| 隐私与阻断事实 | openOccupationIsOnlyABlockingFactAndItsPrivateReferenceIsNotExported | 仅布尔阻断标记，不外传占用凭证或联系人/账号 |
| 主库审计与主体域 | actualMapperInsertUsesAuditAndSubjectScopeRestoresAfterTrustedLookup | 实际Entity插入审计ACTOR、BaseMapper主体隔离、可信查询恢复外层范围 |

首次缺类RED为17断言失败/0错误；初次实现18项中17通过，唯一失败为测试预设CHECK报告顺序。
非法类别同时违反type/shape，测试改为接受任一实际约束拒绝，不削弱DDL。补CI/AI类别反例19项中1断言失败/0错误；
SQL按同一比较语义投影类别后19/19通过。唯一人员资源约束消融19项中1断言失败/0错误，finally原样恢复正式V533。


最终验证：资源实际MySQL19/19（/tmp/resource-final-native.log）；原人员实际MySQL20/20；
merchant及依赖253/253；原账号/slot/商品事实兼容37/37；美业普通reactor54/54，均0失败/0错误/0跳过。
一次独立只读审查未发现需修复项；没有遗留Minor，不请求重复审查。测试未连接营业库。
五组设计生成器、冻结V1、Node6项、SQL无新增方言检查、词汇生成检查及git diff --check通过。
完整schema-lineage14通过/2失败，与§11已复现的基线一致：旧跨表业务键分类、旧ER键归属不一致；未声称全局闸门全绿。
新增表已进入H2派生schema（共346平台表）、表清单、ER、词汇及后端分层；resource_no在幂等/对账中仍有多态含义，登记冲突解释，不错误指定全局唯一归属。

文件对账：新增1个平台迁移、1个Entity、1个共用SPI、1个本域事实投影、1个Port实现、1个IT和1个一次性MySQL脚本；
修改既有MerchantMappers增加1接口、既有主体域注册增加1表；README、ADR041、本TDD、ledger及上述派生物同步。
未修改旧迁移、旧slot/账号/餐饮桌台实现、已冻结美业V1或设计任务JSON状态；无新增公开接口、权限、配置或通用依赖。

阶段边界：SHR-02a基础切片完成，SHR-01/02整体仍未完成。接下来实施SHR-01b排班，再完成组合资源占用仲裁与美业远程接入/预约日历。
资源创建/重分配/恢复及其维护鉴权仍为后续写业务；可信内部Port不等于公开或远程授权入口。
at只选择有效任职，不能重建历史姓名/状态/容量；本批真实DDL验证使用最小父表fixture，未重放全部平台历史迁移或验证所有部署排序规则。
未来部署仍须按目标环境验收；本批没有合并、推送或营业库发布。保留当前隔离分支与ledger用于后续49任务实施。


## §13 SHR-01b：共用周班表与排班窗口

### 13.0 需求与边界

基线94ff55cf7，隔离工作树codex/beauty-foundation干净；最新本地food8e732eeb7，共用merchant/base及迁移无新增。
依据功能矩阵D02、既有mch_schedule_rule/change_log设计及§12接续顺序；架构决定见[ADR-042](./ADR/ADR-042-共用排班窗口与跨店冲突.md)。
交付周规则、变更历史2表及可信内部窗口读取，供零售服务、餐饮人员/物理资源、美业资源日历和后续可约计算共用。
P3的请假/加班/停约例外审批、维护HTTP/写Service、生成容量桶、变更影响协调、远程消费及预约容量仲裁未交付，SHR-01/02整体仍不完成。
本批周排班窗口不考虑尚未交付的例外或容量，不代表整个项目可约/资格许可；不从班表直接扣旧slot或新建STAFF资源。

### 13.1 合同与语义

- shop-merchant新增MchScheduleRule、MchScheduleChangeLog，MerchantMappers增ScheduleRuleMapper/ScheduleChangeLogMapper；
  平台V534新增对应2表，字段沿既有设计与BaseEntity；业务唯一号带tenant/entity，不改旧迁移。历史仅作为后续追加证据，维护写入并发/权限仍待业务Service。
- shop-base/spi/schedule/ScheduleWindowsPort.getBatch(Query)：Query(tenantNo,entityNo,storeNo,Set<String> resourceNos,Instant startAt,Instant endAt)。
  明确1..50资源，UTC毫秒半开窗口、最长31天；范围须在1000-01-03至9999-12-29 UTC内，保留时区及跨日展开余量。
  Window(resourceNo,storeNo,assignmentNo,startAt,endAt,List<String> ruleNos)；物理资源assignmentNo为空。输入/输出及规则列表不可变。
- weekdayMask按ISO星期一bit0至星期日bit6（1..127）；startMinute0..1439、endMinute0..1440、endDayOffset0/1，
  本地持续时间>0且<=1440分钟，validFrom/validTo是规则开始日的包含端点日期。午夜结束可用1440/offset0或0/offset1。
- ScheduleRuleMapper.selectWindowRules一次SQL取requested资源及全部相关门店的有效规则、规范资源/人员/门店父链及任职，
  保留重叠任职行；STAFF同resourceNo跨店检查，物理资源只本店；主动请求门店也须有效。SQL显式tenant/entity范围；最多2000连接行，LIMIT2001哨兵，拒绝超限。
  规则生效日期用UTC请求日期前后各2日保守预筛，再按规则ZoneId本地锚定日精确展开；有效任职按UTC半开区间裁剪，不能把上班时间扩展到任职之外。
- merchant.policy.ScheduleWindowAssembler以UTC前后各2日为保守候选开始日、按业务时区展开跨午夜，再精确排除无关时段并裁剪请求/任职边界；非法ZoneId拒绝，不回退UTC。
  ZoneId允许有效区域或固定偏移；需要季节变化的规则应使用区域时区。DST不存在或重复的本地端点在可能影响请求范围时明确拒绝，不自动移到下一小时或任取一个偏移；有效唯一端点按真实UTC时长返回。
  同资源同店同任职的重叠/相邻段合并并保留ruleNos，不重复增加容量；跨店重叠或同店不同任职重叠明确拒绝，邻接换店/换任职保持分段。
  最多10000原始窗口，超限拒绝而非部分返回；先检查全部相关店冲突再只输出请求店，不泄露其他店规则/任职。
- 一次SQL形成一致语句快照，可信内部查询封装数据域绕过且恢复上下文；新表仍登记merchant主体域。主档读当前姓名/状态，不重建历史资源状态。
  affectedRefs保存JSON引用快照，beforeRevision>=0、afterRevision>beforeRevision；不能靠历史插入证明影响处理已完成。

### 13.2 实施计划（本会话直接执行）

Java21/Boot4/MyBatis-Plus沿平台，零新增依赖。Spec为本节及既有字段字典；采用原隔离工作树和直接执行方式。
Review Focus：跨日锚定/日期端点、DST重复或缺失端点、跨店同人冲突及相邻交接、任职裁剪/重叠、范围/数据量/隐私；以下实际MySQL测试覆盖。

| 任务 | 文件与产出 | 验证 |
|---|---|---|
| S1 | shop-app/.../scenario/ScheduleWindowsPersistenceIT.java；scripts/verify-schedule-persistence.mjs | 缺类RED，正式V532/533/534实际MySQL，不以H2代替DDL |
| S2 | shop-base/.../spi/schedule/ScheduleWindowsPort.java；merchant/entity/MchScheduleRule、MchScheduleChangeLog；dto/ScheduleRuleFactRow；policy/ScheduleWindowAssembler；port/ScheduleWindowsPortImpl；原MerchantMappers新增2接口及数据域注册 | ISO周掩码、时区/跨日/日期端点、任职裁剪与合并、跨店冲突、DST、脏数据/父链/规模/不可变输入输出 |
| S3 | shop-app/.../V534__shared_schedule_rules.sql | 正式字段/唯一键/时间规则/CHECK/JSON/修订约束，真实Entity审计和主体域 |
| S4 | 本TDD、ADR042、README、ledger、派生H2/表/ER/词汇/分层 | 旧资源19项、人员20项、账号/slot/商品37项、merchant及美业回归、消融/静态检查/一次独立审查、本地提交 |

- [x] S1写反例、运行缺类RED。
- [x] S2/S3实现至实际MySQL GREEN；跨店冲突消融必须触发断言失败，恢复后全绿。
- [x] S4完成对账及独立审查，只标本切片完成；P3例外及完整预约/资源业务后续。


### 13.3 AC与实际测试对账

| AC | ScheduleWindowsPersistenceIT实际方法 | 行为证据 |
|---|---|---|
| ISO星期/包含日期/跨日锚定 | isoWeekdayAndInclusiveRuleDatesProduceRealUtcWindows、overnightUsesPreviousLocalAnchorIncludingItsLastValidDate、timezoneAndQueryClippingDoNotUseUtcWeekdayInsteadOfLocalWeekday、midnightEndCanUseMinute1440WithoutInventingAnExtraDay | 当地星期与规则开始日、末日跨夜、上海本地日/UTC前日、1440午夜结束 |
| 同店合并与有效任职裁剪 | sameAssignmentRulesMergeWithoutDuplicatingCapacityAndKeepProvenance、employmentClipsWindowsAndAdjacentAssignmentsRemainSeparate | 合并保留W/W2来源，任职裁剪至10–12/12–14并保留A/A2分段 |
| 跨店冲突/隐私/相邻交接 | crossStoreOverlapFailsEvenWhenCallerRequestsOnlyOneStore、adjacentStoreHandoffIsAllowedAndForeignRulesAreNotExported、overlappingAssignmentsFailInsteadOfBeingMergedAsOneShift | 跨店重叠拒绝，相邻17点换店允许，不输出外店规则/任职号，同店重叠任职拒绝 |
| 规范身份与物理资源 | caseEquivalentReferencesReturnCanonicalResourceStoreAndAssignment、physicalResourcesUseOnlyTheirOwnStoreWithoutPersonnelAssignment | r/p/s引用按规范R/P/S/A父链；物理房间无任职且不能跨归属店 |
| 父链/范围/错误传播 | inactiveAndArchivedParentChainsBlockWindows、foreignTenantAndSubjectReferencesCannotProduceWindows、invalidTimezoneAndDatabaseFailuresDoNotPretendNoShiftExists | 停用/软删/归档/错域不返回，坏时区/数据库失败不伪装无班次 |
| DST真实时间 | dstGapIsRejectedOnlyWhenItsPossibleWindowAffectsTheQuery、dstOverlapIsRejectedRatherThanChoosingAnOffset、midnightDstGapOnNextLocalDateStillChecksItsPossibleUtcRange、uniqueEndpointsAcrossDstReturnActualElapsedTime | 缺失/重复端点影响请求时拒绝，非相关日期不误报，唯一端点跨回拨正确返回04Z–09Z |
| 规模/不可变与毫秒 | oversizedJoinedRowsAreRejectedBeforeExpansion、excessiveExpandedWindowsFailInsteadOfReturningPartialCalendar、requestAndResultsAreBoundedImmutableAndUsePersistedMillisecondPrecision | 2001连接行/超过10000窗口拒绝，名单/范围/精度与不可变列表验证 |
| 正式DDL/审计/主体域 | formalDdlRejectsInvalidRuleShapesAndMalformedChangeEvidence、actualEntityInsertUsesAuditAndRuleAndHistorySubjectScopes | 正式V534范围/周掩码/分钟/日期/JSON/修订/唯一键拒绝，实际Entity审计ACTOR及规则/历史主体域 |

实际缺类RED：22断言失败、0错误（/tmp/schedule-missing-red.log），此前断言API编译错误已纠正，不计为功能RED。
实现后22/22、0失败/错误/跳过（/tmp/schedule-green.log）。跨店守卫消融22项中1断言失败、0错误，
具体失败为重叠查询未抛异常（/tmp/schedule-cross-store-ablation.log）；finally恢复守卫，最终回归另记。


独立审查P2：原候选末日只取请求结束的实际本地日期，漏过午夜跳时后次日规则的可能UTC范围。
新增midnightDstGapOnNextLocalDateStillChecksItsPossibleUtcRange：Santiago2026-09-06 00:30–02:00，
短查询03:15Z–03:45Z原本错误返回空；实际MySQL23项中1断言失败/0错误（/tmp/schedule-midnight-red.log）。
候选锚定日改为UTC前后各2日，与SQL预筛一致，再用原端点范围过滤，覆盖跳过的本地日期且不猜测偏移。
固定偏移ZoneId保留支持，使用固定偏移不自动随季节变化；需要DST应选区域时区。


最终验证：P2反例修复后实际MySQL23/23（/tmp/schedule-fixed-native.log），共用merchant及依赖253/253，
资源实际MySQL19/19、人员实际MySQL20/20、原账号/slot/商品事实兼容37/37、美业普通reactor54/54，全部0失败/错误/跳过。
一次独立只读审查，P2以实际RED→GREEN关闭，无遗留Minor，不请求重复审查。
五组设计生成器、冻结V1、Node6项、SQL无新增方言、词汇检查及git diff --check通过；完整schema-lineage14通过/2既有失败，
仍为旧跨表键分类及ER键归属不一致，未修改无关资金/来源业务含义，不声称全局闸门全绿。

文件对账：新增平台V534两表、2个Entity、1个共用SPI、1个SQL事实投影、1个窗口Assembler、1个Port实现、1个真实IT与1个一次性MySQL脚本；
修改原MerchantMappers增2接口、原数据域注册增2表；README、ADR042、本TDD及ledger同步。
派生H2、表清单、ER、词汇与分层已更新（348平台表）；rule_no与既有分账费率规则同名，登记碰撞而非错误关联。
未修改旧迁移/冻结美业V1、旧slot/账号/餐饮实现、公开接口、权限、配置、依赖或设计任务JSON状态；实际进度以上表与本节为准。

阶段：仅SHR-01b基础切片完成，SHR-01/02与49任务整体未完成。接下来是SHR-02b容量桶与组合占用仲裁，然后再接维护/远程入口和美业预约日历。
保留内部可信范围、当前主档过滤与明确规模上限；固定偏移可用但不自动跟随季节变化，需要DST时选区域时区。
请假/加班/停约审批、完整变更影响协调、写入鉴权/历史追加守卫、容量/资格许可和旧槽迁移仍待后续。
本批MySQL执行正式V532/533/534但采用最小父表fixture，未重放全部平台历史迁移或覆盖全部部署排序规则，不宣称生产迁移验收。
仅本地检查点，不合并/推送/营业库迁移；保留隔离工作树及ledger供后续实施。


## §14 SHR-02b：下一阶段详细需求与方案

以下保留编码前提案；实际进度以§15/16为准。

完整入口：[共用资源容量与组合占用详细方案](./TDD-共用资源容量与组合占用-SHR02b.md)。
基于378a5f2f7人员/资源/周排班实际交付，明确34条需求、三行业边界、6个子阶段、状态机、时间桶/缓冲归一、根锁/净量替换、来源保护/冻结决策、现场交接/超时阻断及32项验收场景。
推荐原4张容量表补4类支撑数据：稳定归属头、持久命令日志、occupation-unit关系、多原因block，共8表；这是待评审模型，原39项数据库JSON尚未修改，不产生DDL。
5分钟UTC网格、10分钟TTL/建议30分钟普通续期上限等是候选值，正式编码前按三行业数据与压测冻结。
P1整单位交接、业务门店不通过replace迁移；正常开放占用限capacity1，真实超时阻断保留已存在的未来预约并协调，不能混为同一种状态。
来源冻结/保护协议及维护写守卫是接入门禁；既有只读人员/班表事实不代替锁内复核。旧零售slot写路径及原例外不自动切换。
上一批仅细化方案，编码前02b-1～6均未实施；现有V532–V534、设计任务JSON、Java代码及营业库未变更。


## §15 SHR-02b编码启动：需求归一

执行计划见[SHR-02b §2.2](./TDD-共用资源容量与组合占用-SHR02b.md)。
02b-1细分为1a归一算法→1b五表数据契约→1c归属/命令持久化，本轮实施可独立验证的1a。
1a不等于数据层或预留能力完成；未新增营业入口、迁移或消费方，后续仍须实际MySQL并发和来源恢复验收。

本轮实际交付ResourceDemandNormalizer及15项行为测试；共用merchant及依赖268项通过，消融1项真实失败后恢复全套通过。
五组设计检查、冻结V1、Node6项、SQL无新增方言、Java行宽与diff检查通过；独立审查无重要问题。
Minor待补：增强输入列表不被修改的断言；当前代码不修改输入。正式数据库与命令/归属并发仍未实现。
下一批为02b-1b五表字段/约束真源与正式MySQL数据层，实施前核对最新餐饮分支及迁移最大版本。


## §16 SHR-02b-1b五表数据契约

详细计划/实际证据见[SHR-02b §2.4、§5.5](./TDD-共用资源容量与组合占用-SHR02b.md)。
最新本地餐饮14f90a9ad核对后沿用merchant规范；V535新建归属头、命令、容量桶、hold及单位明细五表，
同步五Entity/五内嵌Mapper及主体数据域；真源39→41项，任务数量49不变，私有V1不改。
修订区分、合法JSON及状态/释放证据、UTC5分钟桶、容量溢出保护、历史唯一和精确组引用均有实际MySQL约束验收。
原生17/17，消融容量求和后2项真实失败再恢复17/17；merchant与依赖268/268，独立美业54/54，旧预约相关37项通过。
全量H2实际重放353表/2400种子成功；Node7、五组设计、冻结V1、SQL无新增方言及diff检查通过。
新增执行的SchemaDriftTest有1项既有支付异常表列解析失败，已在本批前7925cbf5e数据上调用同一方法复现；
schema-lineage仍为14通过/2既有失败，详见方案日志，不宣称全仓闸门全绿。
独立审查无重要问题，Minor为终态命令resultPayload/rejectionCode非空Mapper回读覆盖待补，排1c验收。
仅数据契约完成；命令不可变/结果恢复、owner根CAS、准入及原子预留尚未实现，接下来1c启动前细化方法再编码。


## §17 SHR-02b-1c持久命令与归属CAS

详细计划/实际证据见[SHR-02b §2.5、§5.6](./TDD-共用资源容量与组合占用-SHR02b.md)。
核对最新本地餐饮5a10a94e0，merchant/base/平台迁移无相关增量；按零售既有Service接口/impl与嵌套Mapper规范落地共享owner内部内核。
独立提交冻结命令，业务事务持执行凭证推进归属头和不可变成功结果；失败/未知结果按原号恢复，不猜拒绝。
同来源异键共享owner版本CAS，旧终态不可覆盖；引用按数据库规范身份，范围/登录租户/活动父链强校验。
31项命令原生测试、五表17项、merchant268项、美业54项及旧路径37项通过；ownerSQL CAS消融1项真实失败后恢复。
一次审查的两个Important已用三个原生反例RED→GREEN修复：占用证据缓存、保存点回滚凭证失效，无二次重审。
五组设计、冻结V1、Node7、SQL检查通过；旧全局结构/血缘失败不在本批修复，见详细方案§5.5/§5.6。
1b拒绝结果映射Minor关闭，1a输入列表断言增强仍待补。无新DDL、HTTP、SPI或营业库操作。
当前仅完成共用基础；下一步02b-2编码前继续细化准入、来源冻结许可、完整集合reserve/protect/confirm/renew/release/expire/reconcile与并发回滚验收。


## §18 SHR-02b-2a锁内准入基础

详细计划/实际证据见[SHR-02b §2.6、§5.7、§6.9](./TDD-共用资源容量与组合占用-SHR02b.md)。
核对最新本地餐饮eb6c686be，无相关merchant/平台迁移变化；按零售Service/impl/内嵌Mapper规范复用1a归一及周班表展开器。
新增ResourceAdmissionService，在原Execution事务内规范化引用并锁人员→资源根，复核身份绑定、资源有效性/归店及开放占用指针。
完整取整桶必须有无歧义任职/排班及所需资格覆盖；普通餐饮/零售员工可由可信行业策略显式声明无需项目资格，不能漏传或混用。
准入原生22项、命令31项、排班23项、merchant268项、美业54项、旧路径37项通过；开放占用消融1项真实失败后恢复22项通过。
五设计、冻结V1、Node7、SQL及Java行宽/diff通过，分层/索引同步。一次审查无重要问题，后续显式资格策略2项RED→GREEN；无重审。
待补Minor：多资源反向别名锁序及发现后人员绑定漂移的动态回归；历史1a输入断言增强仍保留。
本批不写容量/hold、不开放营业入口；下一步02b-2b/2c细化来源冻结许可、计划/资格策略绑定、服务端时间/提前量及完整原子预留生命周期。
现有全局结构/血缘基线失败不在本批修复，详见§5.7；保留隔离工作树，仅本地检查点。
