# 美业系统后端开发任务计划

> 状态：实施中 · 2026-10-21 · 按业务模块交付并逐模块部署验证；BM-01后端模块已通过隔离部署关口，BM-02人员/物理资源/周班表及远程事实后端模块已通过隔离部署；BM03-a1预约来源存储检查点完成；预约工作台未完成。
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
| POL-01 / DEF-01（BM-01） | 后端模块完成，隔离部署通过 | 政策/定义/顾客须知/公开门店及服务、共用销售目录；完整记录见§22，未交付新页面或预约营业闭环 |
| DATA-05/06 | 数据层完成 | 7项实体、15个原始Mapper方法、25项真实MySQL用例；见§9，不含预约/工单业务Service与工作台API |
| DATA-07 | 数据层完成 | 3项新实体、9条原始Mapper方法，复用已有动作日志；21项真实MySQL通过，见§10 |
| SHR-01a | 基础切片完成 | 共用人员5表及内部事实查询，见§11；SHR-01/02整体、写维护和远程接入仍未完成 |
| SHR-02a | 基础切片完成 | 规范资源主档及内部事实查询，见§12；排班/占用仲裁及维护入口后续 |
| SHR-01b | 基础切片完成 | 周班表及内部窗口查询、变更历史，见§13；审批/写维护与完整SHR-01/02后续 |
| SHR-02b | 02b-1a/1b/1c、2a、2b-1及2b-2a基础完成 | 归一/持久命令/锁内准入/来源消费防线及独立行业共用合同已交付；详细方案§5.4–5.9；生产来源provider和原子预留未完成 |
| BM03-a1 | 来源存储检查点完成 | 私有V2追加2表/2实体Mapper，来源冻结、拒绝形状与原预约写者保护；最终记录见§26，真实授权与预约业务闭环未完成 |
| SHR-03及后续业务 | 待逐项闭环 | 已使用部分商品/公共写守卫合同，未完成订单/资金/履约/通知打印的整体接入；预约/日历/任务营业闭环未完成 |

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

目标设计包含20模块118项功能、160项API/合同（95个美业HTTP、11个共用候选HTTP、54个共用合同接入点），44个美业Controller、28组Service/Impl、101条Service方法、25个Mapper及38条自定义Mapper方法。**这些数量是目标清单，不是已实现接口数量。** 当前独立Java五模块、P1数据层及部分业务/共享基础已交付；已完成范围见当前执行记录和§21，初始“没有独立工程”的核查结论仅适用于开发前基线。未访问营业库，不推定目标环境的历史表或迁移状态。

此前误入餐饮的桌台/菜品/订单安排不作为美业任务，餐饮NEXT-01～04的进度不转记到美业。美业原计划从ENG-01开始，现已交付工程与多个基础切片；餐饮作为五模块与薄适配的参考。本轮不继续餐饮NEXT-05，也不改其未提交代码或迁移。

| 数据结构 | 数量 | 本计划如何交付 |
|---|---:|---|
| 美业P1 | 16项bty结构 | 首批审计/迁移/Entity/Mapper，DATA-01～07 |
| 美业P3 | 7项bty结构 | CARE-01护理3项、PERF-01业绩提成4项；当期追加迁移 |
| 美业P4 | 2项bty结构 | PLAN-01疗程服务计划2项；不提前建余次账户 |
| 共用人员/排班/资源 | 16项目标结构 | 当前41表设计中15项候选新增＋1既有mch_appointment_slot兼容；含后续实际占用等未交付结构。SHR-01/02由原所有者实施，不进入美业迁移 |

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

### 2.10 BM-01服务项目与门店政策实施（2026-10-21）

基线dc5a3b543；用户已授权自动执行并逐大模块部署验证。executing-plans本会话执行，沿用原隔离树和ledger。
启动审计时，本模块POL-01/DEF-01九个API/合同：001/002/007/008已落地，009/144/145缺入口，005复用商品维护，006缺销售分组/时段提供方；这些BM-01后端缺项现已实现并按§22通过模块关口。
不因三个读入口完成而将整个BM-01勾选；BAPI-006与实际构件部署均是本模块准出条件。

| 单元 | 文件/方法与边界 | 验收 |
|---|---|---|
| 公共目录事实 | base/spi/product/ServiceCatalogFactsPort及InternalApi；product.port实现聚合已有StoreDirectoryPort/ServiceProductFactsPort/GoodsQueryPort；PLATFORM内部Controller；channel远程适配 | 商品/门店事实仍归各所有者，无复制价格；有效主体/门店、SERVICE/CN在架SKU、真实门店价/名称/图片；凭证与错误保真 |
| 美业目录读 | ServiceCatalogQueryService/Impl/config；PublicStoreVO/PublicServiceVO/ServiceDetailVO/ServiceDetailQuery | 按真实门店推导主体，不接受公开请求自报主体/actor；当前有效政策和定义；未来/停用/缺失不冒充生效；公开DTO无内部权限/资格/审计/哈希 |
| 三端入口 | MpStoreController.getPublicStore；MpServiceController.getPublicService；BizServiceController.getService；对应内部typed API/远程Service | 商家依旧使用真实BizContext和门店权限；公开只读不授予可约/收费权限；独立/嵌入同合同 |
| 分组/销售时段 | BAPI-006归共用catalog所有者；先审计实际提供方及缺口，再冻结实现 | 不把餐饮私有餐牌反向导入美业；未实现不能标整个BM01完成 |
| 部署验证 | 模块脚本构建/启动真实宿主及独立构件、隔离MySQL、真实认证与商品提供方、历史版本/重复发布/回滚/重启 | 缺正式提供方不能用测试Bean冒充；readiness继续按能力门控，不强改200；完成后才转BM02 |

目录事实Port新增只读Store(entityNo,storeNo,name,logo,status,openHours,address)及Service(store,skuNo,goodsNo,title,cover,priceMinor,durationMin)。
公开门店返回公开门面及有效取消/预约须知；公开服务返回当前门店报价/展示、时长/缓冲/注意事项和生效版本，不返回人员技能/内部要求。
商家详情可含既有完整定义。外部事实查询在本地主库事务外，读快照时间由服务端确定；拒绝调用方事务包住远程查询。
部署预检发现原归档YAML强制全局PLATFORM切UNIFIED，导致宿主尚未迁移的其它消费者因缺稳定handler ID无法启动。
本模块增加明确`shop.beauty.publication.archive-only=true`：仅两个不可变版本事件使用共用PLATFORM存储写ARCHIVED证据，不切换宿主全局来源模式、不启另一套投递泵。
存在真实消费者时拒绝该归档操作并回滚，非白名单事件拒绝；缺少该显式配置仍要求正式全局路由。不得通过批量为资金消费者补ID、删除消费者或禁用投递守卫来使BM01启动。
注意事项notes明确为最多20条、每条非空且不超过500字符的文本列表；纳入不可变版本及摘要。旧空notes命令保持原摘要与构造器兼容，发布/查询及公开投影都回读已发布内容。
无本批新表，不改已应用迁移。公开返回不是完整可约/库存/资金许可，BAPI-006提供方补齐前不承诺时段目录已闭环。

- [ ] 原生目录SQL测试先RED；实际政策/定义版本、隔离与公开投影。
- [ ] 公共事实/DTO及美业读服务实现，原生GREEN；正式owner SQL另验。
- [ ] Controller与双部署transport、权限/坏请求/错误保真；独立reactor回归。
- [ ] BAPI-005/006真实合同缺口收敛，不跳过原POL/DEF范围。
- [ ] 消融、一次独立只读审查与必要修复；模块完整业务/实际构件部署验收后登记完成。

### 2.11 BM-01共用销售目录与时段（BAPI-006，档位2）

需求为功能矩阵BTY-B03，沿商品域V2草稿Collection/Availability概念，决策见ADR-044。
V2草稿的整体商品/规格/listing重构尚未实现；本模块不重命名既有表、不复制价格库存、不自动插入店级上架行改变其它门店的覆盖语义。
本次在product所有者内增量提供“门店销售目录不可变版本”，零售/餐饮/美业共用。现有商品/SKU/货架/门店报价仍是交易权威。

| 单元 | 文件与方法 | 验收 |
|---|---|---|
| 正式合同 | base/spi/product/StoreSalesCatalogPort：read(entity,store,at)；StoreSalesCatalogService：publish(entity,store,actor,Req,key)；Req(expectedRevision,validFrom,timezone,groups,entries) | 读版本与头revision分开；分组顺序/显隐、SKU显隐、UNRESTRICTED/WINDOWED及周时段；固定公开渠道，不能冒充资源排班 |
| 存储 | 追加平台迁移，prd_store_catalog、prd_store_catalog_version；Prd实体及ProductMappers两个嵌套Mapper | 一店一头、版本不可变、唯一修订、有效时间和完整JSON/摘要，主库原子CAS；不改冻结美业V1 |
| 维护业务 | product.service.StoreSalesCatalogService/Impl、对应PortImpl；共用biz入口/实际权限和门店所有权 | 强制幂等键、真实SKU/店级报价校验、同键异参冲突；并发首建/旧版本冲突，审计/事件与版本同事务 |
| 只读接入 | ServiceCatalogFactsPort增加目录投影并提供实际内部HTTP；Beauty公开Store/Service投影消费 | 未配置沿明确旧口径“无额外时段/分组限制”，不能当资源可约；配置后缺项/隐藏项不公开；销售时段外显示不可售，保留详情和当前报价 |
| 部署验收 | BM-01真实共同商品维护/配置→发布政策/定义→客户读取；改组/时段边界/隐藏/旧版查询/恢复 | 真实owner/SQL/HTTP，无默认成功provider；完整模块完成后才转BM-02 |

目录payload仅含分组与SKU引用/可见性/销售窗口，最多50组、200项、64KiB UTF8；SKU引用必须由商品所有者解析并按规范号保存。
时区为显式ZoneId；周掩码周一至周日，时间以整数分钟[0,1440)半开区间表示，跨夜拆两条。UNRESTRICTED无窗口，WINDOWED须显式窗口，重复/无效/跨夜定义拒绝。
按服务端/受限查询Instant换算本地周时段，DST重复时刻按各自Instant投影；这不是员工排班或容量判定。
新版本只影响其生效后的售卖展示，不改写历史版本或既有预约/订单。既有引用可读取原版本，接受新预约仍由BM-03冻结其真实政策/版本。
未来完整V2可映射这些稳定分组/条目/窗口及版本，不在本批将草稿物理表全部落地。

- [x] 原生contract/service测试先RED：版本、范围、真实引用、首建/CAS/幂等、显隐、周时段/DST与失败回滚。
- [x] 追加DDL/Entity/Mapper、共用Service/Port；不得在beauty-core维护陈列表或销售价格。
- [x] 显式版本证据归档基础复用共用实现；不改变宿主旧投递、不隐式归档有消费者事件。
- [x] 商家实际权限/目录维护HTTP与跨部署读适配；重生成平台派生结构和相关设计索引。
- [x] 与BM-01既有查询/发布统一原生、权限、异常/重启、零售餐饮回归及实际构件部署关口；一次独立审查及重要问题修复。


### 2.12 BM-02人员、排班与资源维护实施（2026-10-21）

基线6d5f83fa5；BM-01隔离部署已通过。档位1，沿ADR-040/041/042及已批准功能矩阵，使用既有V532～535，旧迁移冻结。
人员仍归shop-merchant，账号/操作权限仍归原成员与角色业务，美业不新增员工表或账号池。

| 单元 | 文件/方法与范围 | 验收与顺序 |
|---|---|---|
| BM02-a 人员维护 | PersonnelService/Impl；BizPersonnelController；CreatePersonnelReq、AssignPersonnelReq、PersonnelQuery、ChangePersonnelStatusReq、LinkPersonnelAccountReq及VO；MerchantMappers必要锁/计数/CAS | BAPI012～016，先原生RED→GREEN；无账号建档及当前店初始任职，原子建立唯一STAFF资源；实际名单/岗位/资格事实可读 |
| 任职与资格 | 父人员revision统一保护孩子；当前门店任职区间不可重叠；TECHNICIAN/ASSISTANT/CONSULTANT/WAITER/CHEF/CASHIER/RECEPTIONIST业务岗位；资格只接受本店真实服务SKU及同商品，VERIFIED核验记录实际actor | 不授予登录权限；初始资格有效期绑定任职；更新通过新任职保留旧行并撤销旧店资格；跨店同一personnelNo/STAFF资源 |
| 账号及停用 | 当前可选成员关联；账号归本主体、ACTIVE、非owner；一人一当前账号，唯一索引裁决竞争；停用复用MerchantStaffService.setStatus，恢复业务人员不自动恢复登录账号 | 原账号角色不由岗位生成；解绑不删除成员/授权，严格审计保存旧/新账号引用；LEFT终态不复职；暂停/离职拒绝未处理占用/在途命令，失败不提交人员改动 |
| BM02-b 资源维护 | ResourceService/Impl、BizResourceController；BAPI020/021，实体STAFF不改身份，物理ROOM/SEAT/DEVICE限本店 | 不修改旧slot；容量/状态/人员身份和占用指针受保护；继承人员根→资源根锁序及必要版本CAS |
| BM02-c 周班表 | ScheduleService/Impl、BizScheduleController；BAPI022及新增P1周规则写入口（具体签名编码前在此补齐） | 复用ScheduleWindowsPort展开/跨店冲突，周规则真实维护；P3请假/例外审批BAPI023/024与协作BAPI018不冒充P1完成 |
| 模块关口 | 人员→任职资格→规范资源→周班表→匹配事实；真实宿主、standalone反向事实、默认零售/餐饮受影响链；权限/幂等/冲突/恢复 | BM02-a内部检查点不表示BM-02完成；全部必要单元完成后执行§21.4的一次模块部署关口 |

BM02-a具体合同：
- GET /biz/personnel：PersonnelQuery(page,size,status,keyword,storeNo?,skill?)；skill明确为有效服务资格goodsNo；当前门店任职名单，额外主体/门店不能由客户端选择；PersonnelVO带当前/未来有效任职及资格、父revision，禁止实体物理id/联系资料导出。INACTIVE/REVOKED/已到期历史保留存储但不累计挤占目录上限；新增/替换后活跃子集上限200在事务内检查，避免提交后目录不可读。
- POST /biz/personnel：CreatePersonnelReq(displayName,avatarRef,assignment)；初始AssignPersonnelReq必须是当前店、expectedRevision=0且assignmentNo为空；返回PersonnelVO；原子保存人员/任职岗位资格/规范STAFF/审计/事件/幂等。
- POST /biz/personnel/{personnelNo}/assignments：AssignPersonnelReq(expectedRevision,assignmentNo?,storeNo,validFrom,validTo?,publicVisible,levelCode?,sortOrder,positions,skills)；skills包含goodsNo/skuNo/levelCode；同店更新撤销旧店资格并保留历史任职行；不改变另一门店任职。
- POST /biz/personnel/{personnelNo}/account-links：LinkPersonnelAccountReq(expectedRevision,accountNo?)；null为解绑，用显式NULL更新，不依赖updateById忽略null；关联非owner同主体成员，不复制账号数据。
- POST /biz/personnel/{personnelNo}/status-changes：ChangePersonnelStatusReq(expectedRevision,status,reason)；ACTIVE/SUSPENDED/LEFT；停用登录账号先锁同主体规范成员行，再复用原service并回查DISABLED，不自行改密码/令牌；在途影响存在则409，未实现自动交接时不得返回已完成。
- 所有入口用biz:store:admin，可信actor/entity/currentStore来自现有会话；原始X-Store-No必须校验，强制幂等key及父revision，storeNo只做当前店相等校验。人员主体级停用/账号关联仅owner，防止某门店管理权影响其他店。
- 商品事实在本地TX外读取，异常保存后仅新幂等动作执行时抛出，已提交请求仍可原号恢复；SQL内部再次验证实体/门店父关系及 canonical业务键，拒绝大小写别名/越界引用。
- 每个变更短事务锁实体/门店活跃父链→人员根→规范STAFF资源根。已有任职历史保留；任职编辑只允许其原门店。
- 暂采用保守影响守卫：本主体存在任一ACCEPTED资源命令、该STAFF任一未释放单位或开放占用指针时拒绝破坏性变更，不宣称无影响/已自动交接。没有资源的异常旧人员拒绝修改，不伪造匹配事实。未来完整交接/外域实际任务写者须接入同根与影响协议。
- 审计复用mch_staff_log直接原子写入（不使用吞错logger）；主记录保存安全业务引用/状态/修订及请求摘要。停用原因最长500字，因现有detail上限512，追加独立PERSONNEL_STATUS_REASON记录并由主记录reasonRef关联；不截断原因或向事件广播自由文本。MERCHANT_PERSONNEL_CHANGED为不可变变更证据，沿共用PlatformPublicationPublisher；显式shop.personnel.publication.archive-only=true才归档，无消费者降级默认不开。

- [x] a1 原生反例：无账号建档/幂等/真实事实、任职交集及跨店、资格所属、账号唯一/解绑、revision冲突、占用/待处理阻断、停用联动、事件失败整笔回滚。
- [x] a2 DTO/Service/Mapper/Controller按规范实现，实际SQL与权限测试及公共事实回归；同步JSON/API/方法与对象文档。
- [x] a3 一次独立只读审查，重要问题一轮RED→GREEN；记录内部检查点，BM02-b/c尚未完成时不启动模块部署或宣布BM-02完成。

### 2.13 BM02-b规范资源维护实施（2026-10-21）

基线1679a32b0；延续已批准BM02-b，档位1，资源仍由shop-merchant统一所有，沿ADR-041和既有表，不新增迁移。

**合同与边界：**
- GET /biz/resources：ResourceQuery(page=1,size=20,storeNo?,type?,status?,keyword?)，页1..10000、大小1..100，当前门店物理资源及有当前/未来ACTIVE任职的STAFF；含暂停资源便于维护。返回PageData<ResourceVO>，VO只含resourceNo/type/storeNo/personnelNo/name/capacity/status/revision/openOccupied，不暴露物理id、占用凭证或剩余容量。
- POST /biz/resources：SaveResourceReq(resourceNo?,type,personnelNo?,storeNo?,name,capacity,status,expectedRevision)；强制Idempotency-Key，作用域及actor取可信会话。新建revision=0、状态ACTIVE；ROOM/SEAT/DEVICE限当前店，容量1..1000。更新保持resourceNo/type/storeNo/personnelNo不变，名称1..64、ACTIVE/SUSPENDED、revision严格CAS。STAFF仅查询，拒绝在此新建/改写，避免绕过人员状态/资格流程和同人唯一身份。
- 所有端点沿biz:store:admin及原始X-Store-No校验，不新增权限码。storeNo只允许省略或等于当前店；personnelNo必须空（STAFF由人员建档）。不提供迁店、删除、占用指针写入口，不修改旧slot。
- 写事务沿主体/门店活跃父链→物理资源根；STAFF修改直接拒绝。每次更新均保守检查主体ACCEPTED命令、资源开放占用、未释放单位/计数及异常孤儿证据。容量变更额外拒绝该资源已有任意容量桶（即使已释放），避免历史桶capacity快照与主档产生分歧；不批量重写历史桶。未来容量版本化另作明确设计。
- 复用IdempotencyService、严格mch_staff_log和PlatformPublicationPublisher，MERCHANT_RESOURCE_CHANGED仅广播不可变安全引用；显式shop.resource.publication.archive-only=true才允许归档。事件/审计失败整笔回滚，返回原请求回放而非再次写入。

**文件与接口：** 新增merchant.dto.ResourceQuery/SaveResourceReq/ResourceVO、service.ResourceService及impl.ResourceServiceImpl、api.biz.BizResourceController；shop-base.spi.resource.ResourceChanged；必要MerchantMappers.ResourceMapper容量桶计数/CAS。原生测试ResourceMaintenanceIT及scripts/verify-resource-maintenance.mjs；HTTP测试ResourceControllerTest；输入边界测试ResourceInputTest。复用现有人员影响计数（暂不另造同义SQL）。

**实施顺序与验收：**
- [x] b1 先原生RED：物理资源建档与真实ResourceFactsPort读取、分页范围/STAFF唯一查询、幂等及哈希冲突、跨店/大小写别名/身份不可变、stale revision、容量/暂停影响阻断、历史桶容量冻结、事件失败回滚。
- [x] b2 DTO/Service/Mapper/Controller实现，原生GREEN、输入边界/真实安全过滤器HTTP回归，执行一次守卫消融RED→恢复GREEN。
- [x] b3 同步矩阵/对象/API/方法清单，一次独立审查及必要重要修复，保存检查点。BM02-c及整体部署仍为后续；不把资源维护通过当作预约可用或BM-02部署完成。

### 2.14 BM02-c周班表与远程事实接入实施（2026-10-21）

基线226d46dc8；延续已批准BM02-c及模块部署关口，档位1，Java21/既有包规范、旧迁移及美业私有V1冻结。

**周班表合同：**
- GET /biz/schedules：ScheduleQuery(Set<String> resourceNos,OffsetDateTime from,to)，1..50规范资源号，正区间最多31日。ScheduleVO含当前门店ACTIVE规则、展开窗口、各resourceRevision及查询区间；规则和窗口只是工作时间事实，不是预约余量，历史/例外/请假不伪装已实现。
- POST /biz/schedule-rules：新增P1共用写入口，SaveScheduleRuleReq(ruleNo?,resourceNo,expectedRevision,timezone,weekdayMask,startMinute,endMinute,endDayOffset,validFrom,validTo,status,reason)。expectedRevision是资源根revision，初始资源也须传现revision；返回ScheduleRuleVO(ruleNo,resourceNo,resourceRevision,status)。时区沿ZoneId，mask1..127，开始0..1439、结束0..1440、跨日0/1、实际本地持续1..1440分钟。validTo首版必填且包含最后开始日，区间最多366日，日期留SQL/UTC两日边界余量；reason1..500。无限规则写入暂不开放，便于完整有效期验证；已存无限规则仍可按查询窗口读取。
- 新建仅ACTIVE；替换/停用须命中当前店ACTIVE旧ruleNo，旧行只标INACTIVE且原内容留存，新ACTIVE版本另号写入；INACTIVE命令返回旧号且不新建规则。resourceNo/storeNo不可迁移；重放在重新修改前返回原号。每资源最多200条ACTIVE规则，保留INACTIVE历史不占上限。
- 写锁主体/店活跃父链→STAFF人员根（适用）→规范资源根；锁内重新验身份及canonical引用，物理限本店，STAFF必须有本店任职。ACTIVE规则须存在有效任职裁剪后的窗口；暂停人员/资源不能新增ACTIVE。同资源根CAS同时保护物理资源和班表写入，不建立独立可绕过修订。
- 人员任职新增/替换在相同人员→资源锁下，遇本店任一ACTIVE周规则明确拒绝（含历史日期规则，避免跨日/时区筛漏）；须先显式停用相关规则再变更任职/资格，防止激活已存规则被任职裁剪掉的跨店重叠。代价是维护前需处理旧ACTIVE规则，不自动重排。
- 更新/新建周规则均采用同一保守影响守卫：主体任一ACCEPTED命令、该资源开放占用/正容量计数/未释放单位/异常孤儿证据拒绝。只证明守卫检查通过，不宣称自动迁移预约任务。
- 每次ACTIVE提交按31日分片复用ScheduleWindowsPort/Assembler检查整个最多366日期间并留跨日UTC边界，跨店重叠/任职歧义/DST端点缺失或重复明确CONFLICT；任职之外的窗口裁剪，不把同人多店当不同容量。变更记录严格写mch_schedule_change_log（affectedRefs=[]表示守卫下无已接受占用，非完整任务影响报告），原子审计/幂等/事件沿共用发布器；shop.schedule.publication.archive-only显式开启才归档。
- 商家入口沿biz:store:admin、可信entity/currentStore/actor与原始X-Store-No检查；STAFF班表是店内维护，不授予账号权限。BAPI023/024的P3例外审批保持未实施。

**远程事实合同：** shop-base新增MerchantFactsInternalApi的三条受凭证保护POST：/internal/merchant/facts/personnel、resources、schedules，使用既有三种Port.Query及各List事实返回。平台portal.internal.InternalMerchantFactsController复用InternalServiceResponses，在执行前校验MAIN、32字范围/号码及真实StoreDirectoryPort规范主体门店。无匿名/用户目录入口，不传账号/联系资料，失败不返回空闲；三个拥有方的任职歧义/窗口冲突明确转为HTTP409，不能由全局异常包装改成HTTP200错误对象。
独立服务beauty-channel分别实现RemotePersonnelFactsPort/RemoteResourceFactsPort/RemoteScheduleWindowsPort，导入BeautyMerchantClientConfiguration；embedded继续本地提供者。沿InternalHttp内部凭证和ServiceCalls错误语义，不新增数据副本或独立员工表。

**文件/方法与顺序：**
- [x] c1 原生ScheduleMaintenanceIT RED→GREEN：Service接口ScheduleService.listSchedules/saveScheduleRule、Impl；ScheduleQuery/SaveScheduleRuleReq/ScheduleVO/ScheduleRuleVO；BizScheduleController；ResourceMapper必要revision CAS；ScheduleChanged；历史保留/完整区间冲突/裁剪/夏令时/作用域/占用/事件回滚。
- [x] c2 ScheduleControllerTest及ScheduleInputTest、一次消融；真实TCP MerchantFactsTransportTest及内部凭证/范围入口测试RED→GREEN；上述远程事实三适配器与装配。
- [x] c3 一次独立审查、重要问题一轮RED→GREEN、共享/独立reactor回归；同步矩阵/对象/API/Java清单。继承原160项设计编号，新增周规则写入口作为BAPI022的P1补充，不把P3影响审批改成写入口。
- [x] c4 干净重建实际embedded/standalone宿主、beauty-server、默认零售构件；隔离MySQL正式登录→人员/任职/资格→资源→周班表→远程事实链、跨店冲突/停用/幂等/回滚/重启/依赖失败验证；通过才认定BM-02后端模块完成，继承既有平台结构夹具/历史迁移债务的证据边界。

### 2.15 BM-03预约模块启动深化与首个内部任务（2026-10-21）

基线6d675af2f，BM-02隔离部署通过。档位2，沿ADR-043及SHR02b§2.8/2.9既有批准方案；先完成真实来源持久化前置，之后才接提供者与容量事务，不用无交易模式绕过来源fence。

| 内部顺序 | 需求和成功条件 | 本次范围 |
|---|---|---|
| BM03-a1 来源意图/栅栏存储 | 预约根→fence→intent，完整冻结计划原UTF8字节、命令/来源修订/摘要不可被重试覆盖；同预约一个活动fence；过许可时点不自动解除 | 本次实现，两表追加私有V2；Mapper/实体/迁移验证及原预约写者NOT EXISTS守卫 |
| BM03-a2 真实来源权限与决策 | 正式经营成员/门店授权，按持久预约/版本/需求生成完整计划，freeze/query同Binding幂等；取消/改约/签到不绕fence；可信资源终态协调后解除 | 后续内部任务；a1 Mapper不等于许可，不注册可授权provider或开放写HTTP |
| BM03-b 共用容量 | 零售/餐饮/美业同一命令所有者；组合预留全成或全回滚、跨店人员防双占；保护/确认/续期/释放/过期/完整替换，失败保旧与原号恢复 | 完成a2及三行业真实来源适配验收后实施 |
| BM03-c 预约业务闭环 | 顾客身份/代约临客、当前政策/商品/定义版本、时段/资格/自动或指定匹配、创建/详情/列表/改期/取消/爽约/未知恢复 | 实际Service/Controller，冻结真实输入，不能只写holdNo返回成功 |
| BM03-d 大模块关口 | 创建→查询→改约→取消；并发、失败保旧、许可到期与未知结果保护；实际embedded/standalone/默认零售/餐饮影响 | 全部必要单元齐备后一次模块部署验证；a1不启动完成宣告 |

**a1物理合同：** bty_appointment_resource_intent保留预约/门店/命令号、sourceRevision/action/commandHash/planHash/frozenPlan、phaseCode（0 PREPARED/1 AUTHORIZED/2 DENIED/3 RECONCILED）、decisionNo/reasonCode/issuedAt/validUntil、terminalCode（1成功/2拒绝）与terminalHash；单命令主体内唯一。bty_appointment_resource_fence主体内每预约一个永久根，fenceNo稳定、activeIntentNo可空，version CAS；唯一活动意图引用不随软删释放。完整载荷LONGTEXT而非JSON列，保留UTF8原字节，JSON_VALID及64KiB检查；不使用数量guard或提交JSON代替。

**拒绝形状：** 对齐共用ResourceSourceAuthorityPort.Decision：DENIED保存decisionNo/reasonCode且许可时间为空；AUTHORIZED保存有效窗口且无reasonCode；RECONCILED保留原决定形状，拒绝意图只能对账为资源拒绝终态。a1不实现拒绝业务编排。

**边界：** Mapper只提供持久化原语，未来a2必须真实鉴权、比较完整Binding与持久业务计划、查询真实资源终态；不得把receipt参数或SQL种子视为权威结果。releaseFence仅允许已有RECONCILED及终态摘要，调用者仍须先核实原命令；无TTL自动解锁或网络调用。本批没有授权provider/预约写入口/收费承诺。

**文件/验证：** 新增V2__beauty_appointment_resource_fence.sql与独立增量JSON/生成检查（原41表设计及V1冻结）；BtyAppointmentResourceIntent/BtyAppointmentResourceFence及BeautyMappers两内嵌Mapper；AppointmentMapper四种修改均检查无活动fence。BeautySchemaValidator合并严格V1/V2清单且readiness版本升2，原工厂/数据域/写前置同步。保留原V1摘要；校验模式对未升级V1库必须拒绝，显式migrate才升级。既有16+新增2私表，不把共享资源表迁入美业。

- [x] a1-1 原生反例ResourceIntentPersistenceIT、V1→V2升级/新库/validate只读/数据保留、原计划字节、唯一活动fence、修订/CAS、四预约写者阻断与手工终态前禁止释放、事务回滚/跨店隔离。
- [x] a1-2 实现迁移/实体/Mapper/版本与严格结构检查；必要CHECK语法仅显式扩展json_valid/octet_length及MySQL同义length封闭函数，不放宽冻结V1验证；一次守卫消融RED→恢复GREEN。
- [x] a1-3 一次独立只读审查，重要问题一轮RED→GREEN；独立reactor/全原生回归及文档对账，保存内部检查点。真实a2/b/c/d仍未完成；不按技术切片宣称大模块交付或提前开始BM04。

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

**当前下一步：BM03-a2真实来源权限、计划核对与终态协调；BM03-a1来源存储本轮完成，记录见§26。BM-02人员/资源/周班表及standalone事实后端模块已通过§25隔离部署关口，BM-01已通过§22关口。** 后续按BM-03预约等业务模块推进，详见§21。SHR02b来源冻结/容量/替换是BM-03内部开发步骤，实际占用/超时是BM-05内部步骤，不再各自作为对外“大模块交付”。ENG-01/02及已完成数据层不重复实施。

初始计划阶段产物（2026-10-20，非当前工程状态）：本TDD、开发任务JSON、生成任务对照索引和校验器；当时尚未编码。之后实际实现及验收按§7–20逐批记录，当前总览与后续领取以§21为准。

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

以下保留编码前提案；后续实际进度以§15–21及SHR02b详细方案的验收记录为准。

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


## §19 SHR-02b-2b-1来源冻结合同与消费防线

详细计划/验收见[SHR-02b §2.7、§5.8、§6.10](./TDD-共用资源容量与组合占用-SHR02b.md)。
核对最新餐饮69b84d2ce，无merchant/平台迁移变化；不把订单数量分配guard或权益SourceDecision当资源来源冻结。
新增唯一共享ResourceSourceAuthorityPort及完整资源计划V1编码；原命令回执读出规范scope和实际payloadHash。
来源freeze/query仅在事务外调用，绑定原命令/完整计划和当前恢复actor；只有本service签发的私有对象可在原Execution中消费，提交前复核身份与期限。
合同/编码14项（纳入共享282项）、消费侧原生16项、命令31、准入22、美业54及旧路径37项通过；计划hash消融1项真实失败后恢复16通过。
一次审查无重要问题，Minor为非授权形状测试需隔离其它拒绝条件；历史2a/1a测试增强保留。五设计、冻结V1、Node7、SQL及Java行宽/diff通过。
现有生产来源适配器为零：无默认放行，未配置则拒绝，Port未实现缺口在分层清单可见。原生来源是合同double，绝不代表真实源持久冻结/权限/三行业接入。
下一批02b-2b-2先细化并实际实现美业来源适配及互斥/恢复，再按同合同覆盖零售/餐饮，最后接完整准入与原子容量预留。
无DDL、HTTP、配置或依赖变化，无营业库/合并/推送；全局结构/血缘基线问题未改，详见§5.8。


## §20 SHR-02b-2b-2a独立美业复用冻结计划

实施与验收见[SHR02b §2.8、§5.9、§6.11](./TDD-共用资源容量与组合占用-SHR02b.md)。
共用ResourcePlanContract下沉shop-base/spi/resource，保留merchant原调用接口和完整V1 JSON/摘要。
纯IdempotencyScope同包名原样移至base，clean构建证实旧模块无重复类；独立美业无需依赖merchant即可编码完整资源/资格计划。
共享289、美业56、原生消费16及美业预约/服务任务25通过；超限转换反例2项RED→GREEN，严格字段消融1项失败后恢复通过。
一次审查无重要问题，UTF8非ASCII摘要/字节上限反例待补；五组设计及冻结V1不变。
真实来源provider仍未实现，本次不是持久冻结或营业预约完成；下一步依§2.8落实来源意图/fence表、真实权限、预约写者互斥及原命令恢复。
无DDL/HTTP/配置/权限变化，无合并推送/营业库操作，整体49任务继续保留原worktree和ledger。


## §21 已完成任务与后续清单重排（2026-10-21）

核验代码基线`dc3b61dad`，分支`codex/beauty-foundation`；本节只整理实际状态和已有目标的实施顺序。
49任务JSON是首次API/表归属的设计索引，生成器明确禁止由索引推断完成状态；不修改其NOT_STARTED或据此计算完成率。
下表“基础完成”不能上卷为SHR-01/02、ENG-03等整任务完成；16项P1私有表已落地，41项为全阶段设计总数。

### 21.1 已交付范围

| 工作包/原任务 | 已完成成果 | 完成边界/证据 |
|---|---|---|
| ENG-01/02 | api/core/web/channel/server五模块，独立/嵌入条件装配，默认关闭及入口隔离 | 工程基础完成；实际部署形态见§7/8，不代表营业就绪 |
| DATA-01/02/03 | P1共16张私有表、冻结V1、独立历史与结构漂移检查、主库事务/审计/数据域 | P1数据基础完成，后续新表必须追加迁移；不推断生产发布完成 |
| DATA-04、POL-01/DEF-01切片 | 政策及服务执行定义查询、不可变版本发布，幂等/审计/outbox；商品事实接入 | 旧切片见§8；BAPI-144等BM-01后端补齐并通过§22；全量项目可约仍属于后续预约模块 |
| DATA-05/06 | 预约/项目/安排、工单/任务/步骤/参与等7项实体和Mapper | 数据层完成，无预约创建/接待/执行业务闭环；§9，a0965d62d |
| DATA-07 | 提交意图、来源绑定、数量分配guard及持久恢复查询 | 数据层完成，数量guard不是资源fence；§10，aaa7ff243 |
| SHR-01a | 人员、可选账号关联、门店任职、岗位/资格五表及内部事实查询 | 无账号人员可参与服务；维护权限/停用影响/远程接入未齐；5c360212c |
| SHR-02a | STAFF规范资源身份、物理资源主档及内部事实查询 | 同一业务人员跨店共用资源身份；容量事务未交付；94ff55cf7 |
| SHR-01b | 周规则、变更历史与时区/跨店排班窗口查询 | 班表读取基础完成；例外审批/维护闭环未完成；378a5f2f7 |
| SHR02b-1a/1b/1c | 5分钟UTC需求归一；owner/command/bucket/hold/unit五表；持久命令、原号恢复、owner CAS | 已有算法/数据/仲裁协议；尚无实际容量预留；7925cbf5e、88e49acd0、4f5e2cac6 |
| SHR02b-2a | 锁内复核任职、排班、资源、开放占用阻断及明确资格策略 | 内部准入基础完成，返回Plan不能视为可约许可；dcc9934ab |
| SHR02b-2b-1 | 唯一来源合同、原命令/完整计划绑定、私有许可及身份/提交时效守卫 | 消费侧完成，来源测试仍使用合同替身；6d0f7a005 |
| SHR02b-2b-2a | 计划证据合同与原摘要工具下沉base，merchant保持兼容，美业独立构建复用 | 消除跨域依赖，生产来源provider仍为零；dc3b61dad |

公共写入已有门店校验、幂等/审计/outbox等切片；ENG-03与SHR-03必须随预约、工单、来源动作逐项验收，不能因政策路径通过而整体勾选。

### 21.2 按业务模块交付及部署关口（替代上一版技术切片主线）

按用户要求调整为：**完成一个大业务模块，做一次正式部署验收，通过后再进入下一个业务模块开发。**
来源冻结、容量桶、Mapper、SPI、Outbox等是模块内部任务；每个内部任务仍做必要测试，不逐个技术切片安排正式模块部署。
原49任务及API/表首次归属保持不变，BM编号仅组织交付。同一共用能力可以支持多个模块，在首次需要的模块实现，后续模块扩展并回归，不重复建账。
现有已通过的工程/数据/接口切片不作废，但没有哪个大模块因这些切片自动取得“整体完成且部署通过”状态。

| 顺序/业务模块 | 当前状态及包含原任务 | 本模块完整交付范围 | 本模块部署验证的业务主线 |
|---|---|---|---|
| **BM-01 服务项目与门店政策** | **后端模块完成，隔离部署通过（§22）**；POL-01、DEF-01，所需ENG-03/SHR-03 | 共用商品/SKU及门店报价引用；服务执行定义与历史版本；门店服务政策/公开营业信息；相应权限、幂等、审计、事件及部署适配 | 使用真实商品提供方完成查询→发布→门店读取→历史版本回查；验证越权/重复发布/失败回滚，重启后结果保留 |
| **BM-02 服务人员、排班与服务资源** | BM-02 P1后端模块隔离部署通过（§25）；人员/任职/资格、物理资源、周规则及远程事实已实现；P3例外审批/完整任务影响仍后续 | 业务人员与可选账号、多店任职、岗位/资格、班表维护与例外/变更影响；床位/房间/设备等资源维护；同人STAFF规范身份和受权事实查询 | 建立无账号人员→任职/资格→排班→关联服务资源→查询匹配事实；验证跨店同人、停用/资格过期/班表变更与范围隔离 |
| **BM-03 预约管理** | 来源意图/栅栏数据前置完成（§26）；AVL-01、APT-01/02/03，所需SHR02b/ENG-03/SHR-03 | 可约/预览、自约/代约、指定或自动匹配、预约列表详情、改约/取消/爽约；完整资源冻结/预留/确认/续期/释放/过期/替换及原号恢复，必要的占用查询 | 创建→查询→改约→取消完整链路；并发争抢不超售、改约失败保旧、响应丢失原号恢复、租期与未知结果正确保护；不要求先做实际服务执行 |
| **BM-04 到店接待、工单与交易来源** | 数据基础，业务闭环未完成；REC-01、WO-01、SRC-01及所需SHR-03 | 临客认领、预约签到/直客接待、工单项目范围/加项、真实订单报价/来源提交/绑定/逐来源账单及恢复；权限/审计/事件、到店与取消互斥 | 预约到店或直客→幂等建工单→真实来源提交/绑定→查询账单；验证重复签到、越权、报价失效和来源结果未知；未就绪来源不能标为可开工 |
| **BM-05 服务作业与履约** | 任务数据基础，业务执行未完成；TASK-01/02/03，SHR02b实际占用及超时部分 | 接单、开工、工作流步骤、实际参与/交接、暂停/延长/提前结束、清洁、完工数量、履约确认和工单关闭；实际占用/开放占用/超时阻断及恢复 | 接单→开工→步骤→交接/暂停→完工→清洁释放→履约/关闭；验证不双扣、不双完工、未清洁不放开、超时与未来预约并存及冲突开工阻断 |
| **BM-06 预约与作业工作台** | 未完成；CAL-01及受控读模型/操作集成 | 日/周视图；人员/工位/设备泳道；具体预约及任务、计划/实际/准备清洁/临时占位/阻断、未分派；筛选/详情/操作后刷新、跨店匿名busy | 从真实预约与作业生成日历数据，任务与多资源占用可追溯；验证权限、分页同水位、不完整/延迟标记及动作后刷新。当前后端范围验收真实API/日历数据合同；可视页面另按端上任务交付 |
| **BM-07 服务异常与门店运营** | 基础设施部分复用，模块闭环未完成；AFTER-01、RECOVER-01、MSG-01、HIST-01、OPS-01 | 未做撤项/争议、异常待办与受控恢复、生命周期历史、通知/服务单打印、经营运行视图、完整对账/监控告警和旧径切换收口 | 撤项/争议→异常待办→原号恢复/复核→历史回查；真实通知与打印链路、重复/丢失/未知回执、容量对账与告警；设备真机未验不得记为打印完成 |
| **BM-08 既有储值与次数权益消费（P2）** | 未实施；ASSET-01/02 | 持有人授权、本店政策、既有储值与合格次数权益消费、未知恢复及争议财务待办；复用资产所有者 | 本人授权→消费→完成/撤销协调→原号恢复与账实核对，验证门店隔离及重复请求；不以此计作售卡/发行完成 |

BM-01→BM-02→BM-03→BM-04→BM-05→BM-06→BM-07为当前交付主线；BM-08及P3/P4继续按原业务阶段。
每个模块用到的日志、指标、幂等、权限、审计、事件、必要通知和失败恢复在本模块一起交付，不能全部推到BM-07。
BM-07负责跨模块运营入口和完整运行收口。共用商品、订单、资金、资产、通知和打印仍归原所有者，美业只持有自身服务事实。
BM-06当前属于后端计划，部署验证必须提供真实任务/占用数据和可用API，不能宣称已交付尚未实施的前端日历页面。

### 21.3 模块内部实施安排及当前下一步

**BM-01已执行清单（结果见§22；不重复领取）：**

1. 核对POL-01/DEF-01全部功能/API，列清已实现、缺项与既有外部依赖，补公开营业信息BAPI-144；核对共用SKU/报价引用，不在美业另存售价账。
2. 补齐本模块可信身份、门店范围、发布/查询权限、幂等/审计/事件投递和双部署正式提供方；测试替身不作为部署依赖。
3. 完成本模块查询/发布/历史选版的必要单元、真实MySQL、HTTP与权限/失败场景回归。
4. 打包实际构件，进入§21.4部署验证；形成记录并通过后，BM-01才标记完成，开始BM-02开发。

**BM-03内部技术顺序：** 真实美业来源意图/fence及终态协调→零售/餐饮来源合同兼容与真实提供方验收→全组合原子预留→租期/保护/确认/释放/过期→整计划替换→占用读模型→预约Controller/Service全链路联验。
已有代码不重做；从来源冻结开始的具体七项开发前检查保留在SHR02b §2.8，逐步写入可编码字段/方法与测试。
未配置来源拒绝；未知结果不凭TTL解冻；相反写者共用根锁；改约失败保旧，三行业不拥有不同的容量权威。
BM-03所需投影/恢复提前纳入，不能因为原编号为02b-6而拖到全部实际作业实现后。

**BM-05内部技术顺序：** 真实开工许可→预约单位交接实际占用→开放占用/结束清洁释放→超时阻断/活动调整→任务工作流/参与/数量→履约与关闭联验。
实际占用与超时不再作为BM-03基础预约完成前的统一阻塞；缺这些能力时，实际开工入口继续门控。
技术小任务做完可提交本地检查点，但不单独宣布大模块完成；跨模块必要合同在当前首次消费者模块补齐并回归。

### 21.4 每个大模块的正式部署验证

每个BM模块设置一个正式部署验收关口。一次关口可包含多种已承诺部署形态；失败先在本模块修复并复验，不能把失败或仅测试通过记作模块完成。
默认使用隔离验证环境及隔离数据库运行实际构件。BM-01本轮已完成实际构件验收（§22）；共享环境/营业库发布不在本次执行范围。

| 步骤 | 必须验证 | 留存证据 |
|---|---|---|
| 1 业务完成检查 | 对照功能矩阵/API/类方法/表，模块主流程、边界、权限、异常恢复齐备；所用共用能力有真实提供方 | 模块范围、原任务/API映射、缺项/门控清单及测试结果 |
| 2 构件与迁移 | clean打包可运行构件；本模块新迁移验证新库及已支持旧版本升级；正式配置、权限、事件路由和依赖接线正确 | 提交号、构件版本/摘要、配置项名称（不留密钥）、迁移版本/执行结果 |
| 3 实际部署启动 | 在隔离环境启动实际JAR/容器；验证该模块承诺的embedded/standalone形态，独立形态包含宿主与内部服务真实调用；默认零售关闭美业的启动回归 | 启动命令/环境、实际进程与路由、依赖连通和健康检查结果 |
| 4 部署后业务验证 | 通过真实HTTP/正式认证操作模块主流程；真实数据库/行业和跨域提供方；验证权限隔离、重复请求、并发及故障恢复，不以Test Bean替代业务依赖 | 请求与结果、关键持久事实/事件、失败场景及重启恢复记录 |
| 5 影响回归与关口结论 | 共用能力改动覆盖零售/餐饮受影响路径；接口错误/i18n、日志/指标/告警及必要打印设备验收；检查数据兼容和门控 | 回归清单、问题及复验、通过/不通过、允许的模块范围及下一模块 |

构件启动和liveness成功不等于模块业务通过；readiness按既有能力门控验证，阶段不具备完整营业能力时不能为验收强改200。
未接入模块保持关闭或明确拒绝，记录全局readiness与模块可验范围；只有整体必要条件就绪才开放对应营业入口。

模块完成记录统一格式：`BM编号｜范围/提交｜构件/环境/形态｜迁移｜真实业务用例｜回归/故障恢复｜遗留问题｜部署结论`。
状态按`待开发→开发中→业务测试通过→待部署验证→部署验证通过/模块完成`流转；不通过退回当前模块修复。
只在模块关口通过后进入下一个大业务模块编码；下一模块的只读需求核查可提前做。

### 21.5 质量待办及当前验证口径

- 最近代码检查点：共享及依赖289通过、美业普通reactor56通过、真实MySQL来源消费16和美业预约/任务持久化25通过；来自dc3b61dad的历史验收，本次文档整理不重复运行Java或将其称为新结果。
- 来源消费16项采用合同替身，不能证明真实来源fence；美业25项证明持久化，不能证明业务Service/工作台闭环。
- 既有架构失败：OpsPointsInspectionController全限定PathVariable；另有SchemaDrift及血缘旧问题，分别处理并保持公开，发布前重新运行对应闸门。
- 已记录测试增强：UTF8非ASCII摘要/字节上限；非授权决策逐项排除其它无效条件；多资源反向别名锁序及P→P2重绑定；输入集合非修改断言。它们不替代真实来源/容量并发验收。
- 不按历次测试数累计“已完成用例”，不把41表/160接口目标数量当完成量，不给49任务计算失真的百分比。

后续P2按BM-08推进ASSET-01/02既有储值与合格次数权益；P3按护理回访、业绩提成、营销、实物耗材、财务报表、终端设备等业务模块分别组织；P4按疗程计划、疗程销售、复杂收银、资产逆向、外部与离线等业务模块分别展开。每个后续大模块同样执行§21.4部署关口。既有权益消费复用权威资产合同，付费售卡和权益发行独立验收，暂不提前展开编码。


## §22 BM-01模块部署关口（2026-10-21）

当前状态：**BM-01后端模块完成，隔离部署关口通过。** 下一项BM-02人员、排班与服务资源；本检查点尚未开始其编码。

本模块交付政策/服务定义发布与有效历史、顾客须知、公开门店/服务及受权商家详情、
实际商品/SKU/门店报价引用、共用商品目录版本/分组排序/显隐/销售周窗口，以及必要权限、幂等、审计和不可变发布证据。
商品和目录分别由原商品域与共用product目录拥有；没有新的美业价格/库存/人员身份账本。
美业政策/定义归私有库，目录V536归平台主库，不增加美业私有V1表。

| 验收单元 | 已运行结果及证据 |
|---|---|
| 新功能RED→GREEN | 公开查询5、notes11、HTTP/传输各2、真实Bean名1、宿主Mapper接线2、归档10分别复现原缺陷后通过；日志见ledger |
| 目录原生及升级 | 最终9项MySQL8.4通过：范围/引用、CAS、并发首建、幂等/失败回滚、生效历史/防篡改、旧版本增量迁移/重启及报价故障/下架后已提交重放；`/tmp/store-catalog-review-green.log` |
| 全量美业原生 | 95项MySQL8.4通过，包含已有数据/迁移、政策/定义、服务查询、原预约/任务与提交来源持久化；不是完整预约业务验收；`/tmp/beauty-bm01-native-final.log` |
| 独立美业普通构建 | 最终clean reactor 64项通过；`/tmp/beauty-bm01-final-build.log` |
| 商品真实拥有方与窗口 | 13项通过：实际directory/product/quote、实时价格、隐藏分组、周窗口/DST、旧服务合同及Bean名；H2不替代原生部署；`/tmp/store-catalog-visibility-green.log` |
| 餐饮影响 | 幂等/事件/条件装配21项通过；`/tmp/beauty-bm01-food-impact.log` |
| 零售与架构 | 29项中27通过、1不适用形态跳过、1已核对既有失败；门店价格3/幂等4通过；既有OpsPointsInspectionController全限定PathVariable导致ArchitectureTest失败，未修改该文件或放宽守卫；`/tmp/beauty-bm01-retail-impact.log` |
| 设计与派生物 | 五份美业设计生成器`--check`、冻结V1、平台H2生成、变更Java行宽及diff空白检查通过；49项任务定义不批量改状态 |

独立只读审查：1项Important已通过RED9/1断言失败→GREEN9修复，既有提交重放不再受商品下架/报价故障阻断，新请求仍失败回滚。
1项Minor延期：重复Window输入目前去重，和§2.11“拒绝重复”存在合同差异；不扩大有效时段，不将其写成已经一致。

部署脚本为`scripts/verify-beauty-bm01-deployment.mjs`。使用真实JAR、正式PASSWORD登录、真实主库/商品/目录提供方；
分别验证embedded、standalone宿主→beauty-server→PLATFORM反向拥有方、默认零售，以及重复发布、隐藏/时段、报价、历史/未来生效、
越权、整笔失败回滚、恢复发布、进程重启、依赖中断拒绝与readiness门控。
平台为显式当前结构夹具＋真实V536；另有V535基线夹具→V536的真实Flyway增量升级/validate/noop重入。
这不覆盖完整历史平台迁移重放/全部排序规则/生产滚动发布，不代表共享营业库部署；已知SchemaDrift/lineage欠账未在此修复。
独立服务整体readiness保留503；公共展示不赋予预约/服务开工/收费许可。

最终部署证据：`/tmp/beauty-bm01-accepted-deployment/result.json`、`/tmp/beauty-bm01-accepted-deployment-run.log`；运行状态BM01_RUNTIME_CASES_PASSED。
正式门店定价API写入12900门店报价，基础SKU保持9900，美业读取12900；商品下架后原发布请求仍重放成功，新请求拒绝并回滚。
宿主readiness实际200；独立服务readiness实际503，未强行开放整体美业营业能力。

| 实际构件 | SHA-256 |
|---|---|
| embedded | `a22225a998423aac5dc0b28059ec842fb74b64fc955e47deb996a7927f94d6d6` |
| standaloneHost | `7000d5cc554a106fe8d475deca02a8d6927f072cfbb6577c40a5754a44a6bdb0` |
| defaultRetail | `1c34799a276d46ff618096dce188a5ac58cbc964fa5c91a0aa86cef548de5200` |
| beautyServer | `238f3a4d9e52186a0456622c7fd342f392eeacf842aa815050769c36c6245c4c` |

模块结论：**BM-01｜从dc5a3b543的后端增量｜MySQL9.7.2，embedded/standalone/默认零售实际构件｜平台真实V536＋私有V1及增量迁移测试｜正式认证、拥有方、版本/报价、权限/重试/回滚/重启/依赖中断通过｜保留重复窗口归一化Minor与全局既有守卫欠账｜本模块隔离部署通过。**
不代表生产发布、全49任务完成、完整预约/工单/收费、端页面或预约工作台已交付。


## §23 BM02-a人员维护内部检查点（2026-10-21）

**状态：BM02-a已实现并测试通过；不是BM-02模块完成或部署通过。** 基线6d5f83fa5。
BAPI012～016落在共用shop-merchant；美业继续引用人员/任职/岗位/资格与STAFF稳定业务键。
既有V532～535和美业V1全部不变；没有新建人员/账号池、业务权限或另一套容量权威。

| 交付 | 真实行为 |
|---|---|
| 建档/名单 | 无登录人员＋当前店初始任职/岗位/确切SKU资格＋全主体唯一STAFF一次提交；名单SQL分页、status/keyword/有效资格goodsNo筛选，查询storeNo只能等于当前授权店 |
| 任职/资格 | 父revision和人员根→STAFF根锁；同店有效区间不可重叠；替换保留旧任职并仅撤销原店/原期资格；跨店继续同一personnelNo/resourceNo；岗位不生成登录授权 |
| 目录与历史 | INACTIVE/REVOKED/过期配置保留存储，当前及未来有效子集单独读取；写入检查200项活跃上限，历史累计不会造成成功维护后目录永久409 |
| 可选账号 | 仅同主体规范ACTIVE非owner成员；一对一当前关联靠账号根锁＋唯一索引；null显式解绑；主审计保留previousAccountNo/accountNo，身份/成员授权仍归原服务 |
| 停用/离职 | owner入口；活动占用/容量证据/未恢复命令时409且不提交变更；锁关联成员再调用真实MerchantStaffService.setStatus并回查DISABLED；业务恢复不自动重启登录；LEFT终态不复职 |
| 审计与恢复 | 强制幂等＋父revision；审计直接原子写mch_staff_log；500字符原因单独关联记录，避免截断或向事件传播自由文本；显式shop.personnel.publication.archive-only才归档不可变证据 |

| 验证 | 已运行结果 |
|---|---|
| 人员原生MySQL8.4 | 最终13项通过；`/tmp/personnel-maintenance-final-accepted.log`。包含实际人员事实Port、跨店同人/STAFF、任职保留/资格撤销、300历史资格后可读及未来活跃上限拒绝、原号恢复、账号唯一/解绑/前后审计、容量/开放占用阻断、原因500字、原子回滚与真实账号/会话停用 |
| 影响补验 | `--impact-only`单项通过；`/tmp/personnel-impact-final.log`。另一店存在未恢复ACCEPTED命令也保守阻断本店人员变更；有容量计数无单位明细仍阻断 |
| 共享商家及依赖 | 292项通过；`/tmp/personnel-merchant-regression.log`，其中shop-merchant77项；原生测试的产品边界为明确控制事实，不宣称整条商品部署验收 |
| 最终输入/HTTP会话回归 | 25项通过；`/tmp/personnel-final-regression.log`，含DTO3、人员HTTP2、原成员会话4及门店员工16；匿名/经理拒绝、raw门店头拒绝、操作者来自会话、强制请求键 |
| 独立审查 | 一次fresh只读审查：0Critical/2Important/0Minor；两项已在一轮修复：并发改名使停用落空、历史资格挤占目录；额外自查补齐旧/新账号关联审计。无二次审查或放宽测试 |
| 红灯证据 | 初始原生6断言失败缺实现→6通过；HTTP2缺控制器→2通过；岗位空值消融3/1断言失败→恢复3通过；目录/关联审计反例失败后修复；真实账号改名竞态1断言失败（期望DISABLED实际ACTIVE）→最终通过；技能筛选1断言失败→最终通过 |

原生实际成员停用/竞态使用真实MerchantStaffService、真实Mapper和内存会话池；未使用的OTP构造依赖为显式mock，不作为验证码登录或实际部署证明。
另一类人员维护测试的商品事实/部分成员协作者有显式替身；实际商品提供方/standalone事实接入和完整构件部署在BM-02模块关口继续联验。
测试数据库使用平台结构夹具，不是完整历史Flyway重放；本批未执行新迁移、共享营业库操作、merge或push。

取舍：对主体中任何未恢复资源命令先拒绝破坏性维护，代价是无关人员也可能暂时被阻断；不假装已经完成预约/任务自动交接。
继续复用原成员停用和撤销会话行为；若外层事务后续失败，数据库会回滚，已撤销的会话可能需要重新登录，权限状态不会因此放宽。
公开顾客档案/本人任务权限、P3协作/请假审批、实际预约来源与容量生命周期均未据本批宣称完成。

**下一项BM02-b：** 深化并实现BAPI020/021规范资源名单与维护，保留STAFF实体级唯一及ROOM/SEAT/DEVICE店级归属；身份不可改写，容量/停用尊重同根、版本及在途影响。
之后BM02-c周规则写入/查询及standalone只读事实接入；全部必要功能齐备后执行§21.4的BM-02大模块部署验收，失败留在本模块修复。


## §24 BM02-b物理资源维护内部检查点（2026-10-21）

本批对应§2.13及BAPI020/021；BM-02仍未通过完整部署关口，不宣称预约/日历/工作流完成。

| 需求/设计 → 实现 | 实际验收 |
|---|---|
| 共用当前店名单、物理维护、唯一STAFF只读 | ResourceService/Impl、BizResourceController、ResourceQuery/SaveResourceReq/ResourceVO；ResourceMaintenanceIT.createsPhysicalResourceWithRealFactsAndDurableReplay及paginatesCurrentStoreResourcesAndOneStaffIdentityWithoutCopyingEmployment真实ResourceFactsPort读取 |
| 身份/类型不可变、门店与大小写规范、revision/幂等 | scopeCanonicalIdentityAndTypeCannotBeChanged、revisionsProtectCapacityAndSuspensionAndReplayReturnsCommittedResult；真实SQL和幂等记录，不接受自报主体或actor |
| 容量/开放占用/未恢复命令保护 | usedCapacityIsFrozenAndOutstandingCountersOrOpenOccupationsBlockUpdates、otherStoreUnrecoveredCommandsConservativelyBlockAndLeaveNoPartialWrites；已有桶冻结容量，释放后可暂停/恢复，不改历史桶 |
| 原子审计及不可变发布证据 | MerchantMappers.ResourceMapper只增桶计数/物理CAS；ResourceChanged复用PlatformPublicationPublisher和严格mch_staff_log；strictPublicationFailureRollsBackResourceAuditAndKey证明新建回滚 |
| 可信HTTP/字段边界 | ResourceControllerTest两项真实安全过滤器证明匿名/非授权/外店头拒绝、强制key和可信actor；ResourceInputTest两项验证类型/人员引用/容量/名称/修订/分页 |

真实输出：
- `/tmp/resource-maintenance-red.log`：7 tests / 7 assertion failures / 0 errors（缺实现）；`/tmp/resource-controller-red.log`：2 tests / 2 assertion failures / 0 errors（缺入口）。首次GREEN运行的表名、命令枚举及Jackson异常包装夹具问题已按真实基线修正，不计作业务RED。
- `/tmp/resource-maintenance-native.log`：7 tests / 0 failures / 0 errors，BUILD SUCCESS。独立MySQL8.4结构夹具，非完整历史Flyway重放，无商品/资源事实替身。
- `/tmp/resource-input-ablation-red.log`：去掉物理资源personnelNo为空守卫后2 tests / 1 assertion failure / 0 errors；finally恢复源码。
- `/tmp/resource-merchant-regression.log`：依赖reactor合计294 tests / 0 failures / 0 errors，BUILD SUCCESS；`/tmp/resource-http-regression.log`：输入5项+资源/人员HTTP及成员/门店会话24项，合计29 tests / 0 failures / 0 errors，BUILD SUCCESS。
- 五份美业设计生成校验、冻结私有V1、SQL方言无新增命中、人员/资源新增Java120列及diff检查通过；没有修改旧迁移、既有slot、全局故障基线或私有库。原49项设计任务定义仍保持原始状态，实际交付归本TDD。

一次独立只读审查：0 Critical / 0 Important / 1 Minor；未启动第二轮审查。延期Minor为生成方法文档共用DTO表尾仅指§2.12，资源语义也应指§2.13；主API/本节/§2.13合同已明确，不影响实际端点。审查未代执行器认证后续回归；最终29项结果由执行器核验。

边界与取舍：STAFF只读、已有任意历史桶冻结容量，代价是人员变更走人员维护、已使用物理容量待未来版本化协议；主体存在未恢复命令可暂时阻断无关资源维护。并发维护/admission交错、同时同revision提交，真实未释放/孤儿单位及confirmed计数，审计故障/已有资源更新发布失败/真实消费者投递未在本批额外注入；代码复用既有根锁/CAS/影响SQL及事务发布器，仅主张上述实际测试范围。完整历史迁移、真实构件部署、班表及容量/任务生命周期仍是后续范围，不能由本检查点推断通过。已知全仓Architecture/SchemaDrift/lineage债务未修改或声称转绿。

**下一项BM02-c：** 先深化周规则读写、跨店人员冲突/有效任职裁剪/时区边界及standalone事实反向接入；完成后按§21.4部署验证整个BM-02。BAPI023/024的P3例外审批不冒充P1周班表维护。


## §25 BM-02人员、资源与周班表后端模块部署关口（2026-10-21）

**模块结论：BM-02 P1后端基础模块完成，隔离实际构件部署关口通过。** 本模块是共用人员/资源/工作窗口维护与事实运输，不包含前端、预约工作台、容量承诺、自动任务交接、P3请假/例外审批或生产发布。

### 25.1 需求 → 设计 → 实现对账

| 需求/所有者 | 实际文件与验收 |
|---|---|
| 当前店周规则查询与维护 | merchant的ScheduleService/Impl、BizScheduleController、ScheduleQuery/SaveScheduleRuleReq/ScheduleVO/ScheduleRuleVO；GET /biz/schedules，P1补充POST /biz/schedule-rules |
| 统一根修订、历史/冲突 | MerchantMappers.ResourceMapper.advanceScheduleRevision与countStoreScheduleRules；ScheduleMaintenanceIT的规则持久化/替换/停用、跨月冲突、任职裁剪、DST、同revision并发及回滚共10项 |
| 任职变化不绕过排班 | PersonnelServiceImpl.assignPersonnel在既有人→资源锁下拒绝本店任一ACTIVE周规则；employmentExtensionCannotReactivateClippedCrossStoreOverlap、newEmploymentInAnUnassignedGapCannotActivateStoredWeeklyConflict先RED后GREEN，并验证停用旧规则后可变更 |
| 原子变更证据 | 既有mch_schedule_change_log严格记录reason/actor/前后资源修订；ScheduleChanged复用平台发布器。无真实消费者归档仅显式开关；不新增表/独立流水/旧slot改写 |
| 独立事实运输 | shop-base MerchantFactsInternalApi；平台portal/internal.InternalMerchantFactsController；beauty-channel的三Remote*Port及BeautyMerchantClientConfiguration，独立server导入；原Personnel/Resource/ScheduleWindowsPort是唯一权威 |
| 凭证、范围与错误 | MerchantFactsControllerTest含真实GlobalExceptionHandler，三种拥有方歧义明确HTTP409；MerchantFactsTransportTest真实TCP；ScheduleControllerTest真实安全过滤器；ScheduleInputTest及有效期守卫消融 |
| 实际构件部署 | scripts/verify-beauty-bm02-deployment.mjs复用BM01框架并扩展lib/beauty-bm02-runtime.mjs；外部测试驱动fixtures/BeautyMerchantFactsProbe.java以实际提取server类路径启动原应用，不新增生产探针入口或提供方替身 |

人员revision和资源revision不同：既有人员建档生成STAFF时资源初始revision=0，人员为1；周规则写入必须从资源目录/班表响应读取实际resourceRevision，不凭人员revision猜测。

### 25.2 测试、消融与一次审查

| 证据 | 实际结果 |
|---|---|
| 排班初始反例 / 最终 | `/tmp/schedule-maintenance-red.log` 8 assertion failures / 0 errors；`/tmp/schedule-maintenance-final.log` 10 tests / 0 failures / 0 errors |
| 两项重要问题反例 | `/tmp/schedule-employment-red.log` 2 assertion failures / 0 errors；`/tmp/merchant-conflict-red.log` 2 tests / 1 assertion failure / 0 errors，实际409期望/200错误对象 |
| 有效期守卫消融 | `/tmp/schedule-input-ablation-red.log` 2 tests / 1 assertion failure / 0 errors；源码finally恢复后输入测试通过 |
| 改动人员路径的完整原生回归 | `/tmp/bm02-personnel-final.log` 13 tests / 0 failures / 0 errors |
| 最终共享merchant依赖reactor | `/tmp/bm02-merchant-final.log` 296 tests / 0 failures / 0 errors |
| 最终输入/HTTP/成员门店会话 | `/tmp/bm02-final-http.log` 35 tests / 0 failures / 0 errors |
| clean独立美业reactor | `/tmp/bm02-beauty-final-build.log` 65 tests / 0 failures / 0 errors，含三种反向事实传输及原目录/版本回归 |
| 餐饮影响 | `/tmp/bm02-food-impact.log` 21 tests / 0 failures / 0 errors，幂等/outbox/条件装配 |
| 全仓架构守卫 | `/tmp/bm02-architecture.log` 16 tests / 1 failure / 0 errors；唯一noFullyQualifiedReferences指向未修改的OpsPointsInspectionController，和基线相同。其余15项通过，不声称全仓绿色 |

一次独立审查0 Critical / 2 Important / 1 Minor；两重要问题均在一次修复中RED→GREEN并通过最终回归，没有二次审查。Minor延期：外部构件探针验证数量、资格存在和容量，未逐项断言返回身份/窗口端点；实际SQL原生身份断言和正式HTTP链已有覆盖，未将该探针当全字段证明。
生产规模性能、真实admission持续争用及BM03尚未实现的消费行为未作运行认证；原生与部署库均为显式结构夹具，不是平台全历史Flyway重放。SchemaDrift/lineage债务未重跑或修改。

### 25.3 实际隔离部署与构件

最终输出：`/tmp/beauty-bm02-accepted-deployment/result.json`，status=BM02_RUNTIME_CASES_PASSED。
数据库为自建MySQL9.7.2 tmpfs、回环随机端口，平台结构夹具＋真实V536；美业私有V1校验，历史0/1重启不变。无共享/生产库操作或外部发布。

- 实际embedded宿主：正式密码登录，实际商品拥有方SKU资格、无登录建档与幂等、物理资源维护、相同人员跨店任职且仅一个STAFF、跨店周规则冲突/相邻放行、容量计数/历史桶阻断、成员关联与暂停/恢复（恢复业务人员不恢复账号）、stale回滚与重启查询均通过。
- 普通standalone宿主与beauty-server实际JAR启动，原目录/政策链继续通过；共用维护仍在PLATFORM。测试驱动对提取且记录摘要的同一server构件启动原BeautyApplication，验证三个实际远程Bean→真实PLATFORM事实、故意注入跨店规则冲突后返回BizException(CONFLICT)/HTTP409、连接故障三种事实均拒绝；冲突注入SQL已恢复，未冒充普通写入口。
- 默认零售真实构件：无美业路由，共用商品/人员资源/周班表仍可读取；餐饮影响由上述21项回归核验，未声称餐饮生产部署。
- 宿主readiness实测200；独立服务整体readiness仍503，这是未开放预约/完整营业能力的既有关口；不因本模块通过就改成200。进程与自建容器均自动清理。

| 构件 | SHA-256 |
|---|---|
| embedded | `77dbdabf52a9e64abf46290a1da546a3c5b1db345c28cf89c648417ef2cdc30a` |
| standaloneHost | `3715eb6d16e747a2ebb19df072dd45990645057b1f773fb95de8c6108d1607a6` |
| defaultRetail | `4f5e80a03857a577d51535979619a1f289c394084db98597df3e2d63920e13d1` |
| beautyServer | `ec56e931abb66f1b65223244230b5d322cf7145099e024166720b76b59894964` |

### 25.4 当前取舍与下一业务模块

新规则需明确结束日且最多366日，长期班表续排；已有ACTIVE周规则时，须先显式停用再新增/替换本店任职/资格（包括历史日期规则，避免跨日筛漏），不自动重排。已使用物理资源容量仍冻结，等待未来版本化容量方案。
独立事实测试驱动只证明当前真实Bean/运输/拥有方链，不能替代尚未实现的预约消费端点；完整生产迁移、全仓守卫和最终发布仍有独立关口。

下一项BM-03：先按已批准矩阵/API深化预约创建/改期/取消、可信顾客与临客范围、单次先付/无交易来源、来源冻结、组合容量占用/替换和未知恢复，再编码；预约详情/查询与恢复随模块交付，完成后再做一次实际构件部署验收。

## §26 BM03-a1预约来源存储内部检查点（2026-10-21）

基线6d675af2f。仅完成预约来源意图与永久栅栏的数据前置，不是整个预约模块交付；a2真实来源Service、b共用容量事务、c预约业务API及工作台、d模块部署关口继续按§2.15推进，不提前转BM04。

### 26.1 需求、设计与代码对账

- 共用商品/订单/人员/资源所有权保持原边界；预约来源过程记录由美业保存。新增BtyAppointmentResourceIntent/BtyAppointmentResourceFence及两内嵌Mapper、数据域注册，无新Controller或可授权provider。
- 原41项设计/49任务与冻结V1不改，独立增量JSON及私有V2追加2表，实际18私表；唯一键保持tenant/entity前缀，命令号不可跨预约/店重绑，永久预约根CAS一个活动意图。
- LONGTEXT保留原始完整计划字节；JSON_VALID及UTF8字节≤65536保护。authorize/reconcile不改命令、计划或摘要；SQL更新作用域、阶段和修订必须匹配。
- 原AppointmentMapper改期、状态（含确认/取消）、签到均检查活动fence；异常软删的fence也不绕过，许可到期不解锁。先锁预约→永久fence→intent，核实可信终态后显式NULL释放；本批只保存对账记录，实际终态真实性仍由a2服务负责。
- 严格manifest分别核对V1/V2资源唯一归属、固定摘要及精确列/默认值/索引/强制CHECK。readiness版本2，未升级V1库validate拒绝，受控migrate升级后保留原预约。
- 拒绝存储对齐共用Decision：DENIED有decisionNo/reasonCode、没有许可窗口；对账后保留原拒绝证据，不能转成成功资源终态。此项只补兼容存储，拒绝业务编排尚未实现。

### 26.2 验证及一次审查

初始原生7项全部按缺失实体/版本升级断言失败；CHECK函数单测先失败再实现。实际MySQL会把OCTET_LENGTH规范化为LENGTH，检查实际CHECK_CLAUSE后仅加入同义白名单，不允许任意SQL函数。
首轮全原生104项、独立服务1项、恢复守卫后专项9项及真实宿主HTTP到数据库1项通过；临时移除改期NOT EXISTS守卫得到1项断言失败，finally恢复。
拒绝合同兼容修正新增真实Decision形状测试，先因reason_code缺失失败；最终全量原生105项、独立服务联验1项及干净reactor66项均通过，最终专项10项及真实宿主HTTP到数据库1项均通过。

一次新上下文只读审查无Critical/Important，1项Minor：有效补充Unicode原样往返及精确65536字节接受边界未单列，当前有ASCII+空白往返和超限UTF8拒绝证据；保留后续补充。
执行者对审查未裁定的拒绝存储形状作重要问题处理：修正尚未发布的V2候选并补reasonCode/拒绝无许可约束/拒绝不得对账为成功的SQL守卫；一轮RED→GREEN后不重复审查。未修改已发布或外部持久库中的迁移。

### 26.3 取舍与剩余边界

本批记录和Mapper参数均不构成来源权限或资源终态权威；未注册假的许可提供者。代价是预约仍不可营业，后续必须补真实来源权限、持久计划生成/比较和真实终态协调。
完整拒绝/准备意图放弃/未知恢复编排、所有实际写者遵守根锁、禁止通用Mapper更新破坏不可变绑定由a2承担；本批不声称这些流程已验收。BM-03尚未达到大模块部署关口，本次数据库/服务集成测试不替代实际构件部署。
已有全仓Architecture/SchemaDrift/生产历史债务不由本切片消除；不作全局发布通过声明。保留ongoing worktree与执行账本，只有本地检查点，不合并/推送或改共享生产库。

最终V1 SHA-256：`ac2a539664c42fdb7e4893f1359879fc5affbe4b24f95aae1809cd25f11061a4`；V2：`5b6768928018b68635c9c081b200a2cb6202d40645c5db62c91c558cc49d4114`。
最终证据：`/tmp/beauty-bm03-final-native.log`、`/tmp/beauty-bm03-final-clean-build.log`、`/tmp/beauty-bm03-final-host.log`；守卫消融：`/tmp/beauty-bm03-ablation-red.log`；拒绝形状反例：`/tmp/beauty-bm03-denial-red.log`。五项设计生成、V1/V2冻结生成检查、SQL方言无新增依赖及新Java120列检查已通过。
