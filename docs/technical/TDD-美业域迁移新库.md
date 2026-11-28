# TDD-美业域迁移新库

状态：设计中（行业库部分）
关联：[ADR-068 门店交易库按耦合簇换表与行业插件公用部分并回](ADR/ADR-068-门店交易库按耦合簇换表与行业插件公用部分并回.md) ·
[零售域-第二段建议方案与表清单](零售域-第二段建议方案与表清单.md) A6（美业执行整段留在行业库）、C1 ·
[TDD-餐饮域迁移新库](TDD-餐饮域迁移新库.md)（行业库与共用表的先例）·
[交易库与行业库表清单](design/数据库设计/交易库与行业库-表清单.md) ·
行业库设计：[DDL](design/schema/ddl/06-n_industry.sql) · [美业执行表](design/schema/retail-ddl/beauty-execution-tables.sql)
创建：2026-11-28 · 最后更新：2026-11-28

档位：2 · 依据：ADR-068 与方案 A6（已采纳，本篇不另开 ADR）· 产出：本文件

## 一句话

美业插件现在读写 30 张 `bty_*` 表，表名都是旧的。按 A6，美业的「服务怎么做」留在行业库 `n_industry`，「卖了什么、收了多少、约了谁」进门店交易库 `n_trade`。
本篇先做**行业库部分**（与餐饮 P1 对称），并按用户 2026-11-28 定的**A6 全并**收敛表；交易库部分（预约、资源占用、服务定义）随交易簇乙与商品领域迁移另做，这里只标注。

## §0 对账一 · 需求 → 设计

本篇是重构，不加功能。验收标准是「行为不变 + 归属落地」：

| AC | 验收标准（Given / When / Then） | 落点 |
|---|---|---|
| AC1 | Given 行业句柄绑到按 `db/n_industry` 新建的库 When 跑美业接待、开工单、服务执行、工单结账、门店策略用例 Then 行为与切换前一致 | §2.4 步骤；新库测试 |
| AC2 | Given 行业句柄下任一 SQL When 静态扫描 Then 只碰行业库的表；交易句柄、主库句柄下的 SQL 不碰行业表 | §2.2 跨句柄拆分 |
| AC3 | Given A6 的合并 When 写入与查回 Then 合并后的表能还原原来每张表承载的业务事实（防重、并发闸门、恢复所需字段） | §2.3 合并映射 |
| AC4 | Given 跨库的接待、开工单、结账 When 中途失败 Then 按业务键重放或扫描补做后状态一致，不重复开单、不重复记账 | §2.2 事务拆分 |
| AC5 | Given .61 When 执行行业库变更脚本 Then 美业行业表就位，共用表（`ind_*`）原有数据保留，可重复执行 | §2.5 部署 |

## §1 现状与影响面（2026-11-28 读代码）

持久化只在 `beauty-core`，Mapper 都在 `mapper/BeautyMappers.java`，按标记接口分三条句柄（`config/BeautyPersistenceConfiguration.java`）：

| 句柄 | 表 |
|---|---|
| 行业（`IndustryTableMapper`，15 张） | 门店策略 `bty_store_policy`、策略版本 `bty_store_policy_version`；工单 `bty_work_order`、工单上下文 `bty_work_order_context`；服务任务 `bty_service_task`、步骤 `bty_service_step`、参与 `bty_service_participation`、提交 `bty_service_submission`、执行围栏 `bty_service_execution_fence`；绑定 `bty_trade_binding`、来源观察 `bty_source_observation`、分配仲裁 `bty_source_allocation_guard`；接待操作 `bty_reception_operation`、顾客认领回执 `bty_guest_claim_receipt`、到店核验 `bty_arrival_challenge` |
| 交易（`TradeTableMapper`，9 张） | 服务定义、定义版本、服务要求；预约、预约内容、指定技师、预约准备、预约安排、预约当前行 |
| 主库（6 张） | 操作日志 `bty_action_log`；预约资源的围栏、意向、决策；预约操作 `bty_appointment_operation`；库存意向 `bty_service_execution_stock_intent` |

- 美业不直接查 `prd_*`、`ord_*`、`rsv_*`、`sys_*`，都走接口（`CheckoutPort`、`ServiceExecutionPort`、`ResourceBookingPort` 等）。
- 迁移：私有 Flyway（`db/industry/beauty` V1–V11、V9_1，历史表 `bty_flyway_history`），跑在主库；表结构契约在 `BeautySchemaValidator.manifest()`。
- 测试：集成测试用真 MySQL（`BEAUTY_NATIVE_MYSQL_PASSWORD`），测试里三条句柄都是主库的别名。
- .61 现场：`n_industry` 里美业只有共用表 `ind_store_policy`、`ind_config_revision` 各 1 行（11-24，root 写入，应用账号对 `n_industry` 无授权）。

### 1.1 跨句柄的语句（分库后会断）

| 位置 | 问题 |
|---|---|
| `ReceptionOperationMapper`（行业）的查询 | 关联预约操作（主库）、预约准备、预约、预约资源意向（交易 / 主库） |
| `AppointmentResourceFenceMapper`（主库）的 `UPDATE … JOIN` | 关联预约、预约当前行（交易）与资源意向、预约操作（主库） |
| `AppointmentResourceIntentMapper`（主库）的 `UPDATE … JOIN` | 关联预约、预约当前行（交易） |
| `AppointmentMapper`（交易）的查询 | 引用资源围栏（主库） |
| `ServiceExecutionStockIntentMapper`（主库） | 引用执行围栏（行业） |
| `BeautyStoreRelinkFactsPortImpl` | 用主库 `JdbcTemplate` 一次查预约、预约操作、预约准备、工单、接待操作 |
| `ServiceExecutionSchemaVerifier` | 用交易数据源校验行业表 `bty_service_execution_fence` |

### 1.2 混用句柄的事务

| 事务 | 混用 |
|---|---|
| `StorePolicyServiceImpl.publishPolicyVersion`（行业） | 写主库操作日志；平台写前置检查走主库 |
| `ServiceDefinitionServiceImpl` 发布（交易） | 写主库操作日志 |
| `ServiceCatalogQueryServiceImpl`（交易只读事务） | 读行业的门店策略 |
| `AppointmentPersistence.tx()`（交易） | 同时写主库的资源围栏、意向、预约操作、操作日志 |
| 接待（`ReceptionCoordinator`、`ReceptionPreparation`、`ReceptionRecovery`）、`WorkOrderCoordinator` 的交易事务 | 锁或写行业的接待操作 |

## §2 方案

### 2.1 契约变更

| 项 | 变更 |
|---|---|
| 端点 / JSON | 无 |
| 库表 | 行业库：美业执行按 A6 全并后的表（§2.3）建在 `n_industry`，补充脚本与餐饮共用 `db/n_industry` V2；共用表 `ind_store_policy`、`ind_config_revision`、`ind_assignment` 加美业的行。旧主库不改 |
| 配置项 | 无新增；沿用 `shop.modules.industry.datasource` |
| 权限码 / i18n | 无 |

### 2.2 跨句柄拆分与事务（按餐饮的做法：业务键回查、先一库再调另一库、扫描补做）

**原则（用户 2026-11-28）**：工单与工作流服务（独立的审核 + 工单服务、轻量审批）解决通用问题；美业自己的功能尽量用行业方案解决——美业的服务工单、任务、步骤是行业库里的执行模型，不映射到通用工单或工作流；能用行业共用表（`ind_*`）的就用。

本篇只拆**碰到行业表**的点；纯交易与主库之间的（预约资源的围栏、意向、决策与预约的关联）随交易库部分处理。

| 位置（§1.1、§1.2） | 处理 |
|---|---|
| `ReceptionOperationMapper` 查询关联预约侧的表 | 拆成两次查询：先在行业库查接待操作，再按预约号经交易句柄查预约与准备，内存里拼 |
| `ServiceExecutionStockIntentMapper` 引用执行围栏 | 库存意向随库存退役前，先按围栏号经行业句柄取围栏的版本与轮次，再在主库做比较交换 |
| `BeautyStoreRelinkFactsPortImpl` | 按句柄各查各的（交易：预约、准备；主库：预约操作；行业：工单、接待操作），任一有即「有历史」 |
| `ServiceExecutionSchemaVerifier` | 改用行业数据源校验执行围栏 |
| 门店策略发布（行业事务里写主库操作日志） | 操作日志在行业事务提交后写（操作日志本就归基础能力库审计）；发布按业务键可重放 |
| 服务目录查询（交易只读事务里读行业的门店策略） | 门店策略改在行业句柄上单独读，不进交易事务 |
| 接待、开工单（交易事务里锁或写接待操作，15 处） | 接待操作留行业库（用户 2026-11-28 定）；统一收进 `ReceptionSteps`，见 §2.2.1 |
| 工单结账 | 按 A6：先调结账（已按操作号防重），再在行业库写提交与绑定；中途退出按操作号补做 |

#### 2.2.1 接待步骤的封装（用户 2026-11-28 定：接待操作留行业库，并做一层抽象）

**原则（用户）**：一个改动要调整多处，说明设计不好。接待操作现在在 15 处各自开交易事务、锁接待操作、推进阶段；分库只是暴露了这一点。所以先把「在哪个库开事务、按什么顺序、断了怎么补」收进一个组件，业务代码只描述每一步做什么。以后换库或调整顺序，只改这一处。

**现状（2026-11-28 逐处读代码）**：15 处交易事务分三类。

| 类 | 处数 | 位置 | 预约侧 |
|---|---|---|---|
| 只动接待操作 | 7 | 受理 `begin`、`beginClaim`；冻结 `freezeWalkIn`；记失败 `walkInFailure`；开单被拒 `reject`；恢复扫描抢租约、记重试 | 不碰 |
| 锁住预约核对，只改接待操作 | 6 | `resumeWalkIn`、`closeReleasedWalkIn`、`rejectWalkIn`、`prepare`、`resumeAppointment`、恢复 `project` | 只锁住核对 |
| 两边都写 | 2 | `prepareReception`（建预约与预约准备）、`arriveWalkIn`（标到店） | 写 |

**组件 `ReceptionSteps`**：接待操作唯一的写入口，只在它里面开事务、锁接待操作。

| 方法 | 做什么 | 事务 |
|---|---|---|
| `begin(...)` | 受理：插入接待操作（按幂等键，已有就返回原行） | 行业 |
| `update(op, guard, change)` | 只改接待操作：锁住 → 前态核对 → 改 → 写回 | 行业 |
| `claim(...)` / `release(...)` | 恢复扫描的租约与重试时间 | 行业 |
| `advance(op, step)` | 推进阶段，下面详述 | 交易 → 行业 |

`advance` 的一步（`ReceptionStep<E>`）只声明三件事：从哪个阶段出发（`from`）；交易侧做什么（`trade`，在交易事务里锁住预约侧、核对或写入，返回凭证 `E`）；凭证怎么落到接待操作上（`apply`，给出新阶段与要记的字段）。执行顺序固定在 `ReceptionSteps` 里：

1. 读接待操作；阶段已不是 `from`（别人推进过了），直接返回。
2. 交易事务：执行 `trade`。写入必须能安全重做：按接待操作里冻结的键判断「已经做过」，做过就只返回凭证。
   - 建预约：预约号、预约操作号在受理时就已生成并冻结；预约已存在且操作号相同，即为已做过。
   - 标到店：冻结了期望版本 `arrivalRevision`；预约已是已到店且版本为它加 1，即为已做过。
3. 行业事务：锁住接待操作；阶段仍是 `from` 才执行 `apply`、写回，否则返回现值。

第 2 步之后进程断了，接待操作还停在 `from`。恢复扫描再调一次同一步：第 2 步识别出已做过，第 3 步推进阶段，不会重复建预约、重复标到店。两个请求并发时，交易侧由预约锁串行，接待侧只有一个能通过「阶段仍是 from」的核对。核对类的 6 处，`trade` 只读不写，天然可重做。

**15 处怎么落**：受理两处用 `begin`；只改接待操作的 5 处用 `update`、`claim`；其余 8 处各写成一个 `ReceptionStep`，调用 `advance`。交易事务里不再出现接待操作的 Mapper。

**怎么守住「只在一处」**：
- 约束测试①：只有 `ReceptionSteps` 能调用接待操作 Mapper 的写入与加锁方法（`insert`、`update*`、`selectScopedForUpdate`、`claim*`），其他类只能读。
- 约束测试②（AC2 的事务版）：静态扫描 `db.tx(...)`、`db.industryTx(...)` 里用到的 Mapper，一个事务只能用一个库的 Mapper。它同时会揪出另外几处混用：
  - 交易事务锁提交表（`ServiceSubmissionServiceImpl:774`）；
  - 交易事务锁门店策略头（`WorkOrderPreparation.verifyLocked`）；
  - 行业事务写主库操作日志（门店策略发布、`GuestRegistrationServiceImpl:114`、`ServiceSubmissionServiceImpl:354`）。

  这些也各收进一个封装点：操作日志统一由 `ActionLogs` 在业务事务提交后写；另外两处由对应的组件按同样的顺序处理。

**不做的**：不为此造通用的跨库编排框架。`ReceptionSteps` 只管接待操作这一张表。开单（ADR-068 已是先交易、后行业）如果以后出现同样的分散，再看是否复用同一个执行器。

### 2.3 A6 全并：合并映射（用户 2026-11-28 定）

2026-11-28 逐表读代码（列、唯一键、生命周期、锁与比较交换、读写方）后的结论：7 组里 3 组能并，4 组按 A6 原文并会丢掉代码依赖的保证。

| 旧表 | A6 原文 | 与目标的关系 | 结论 |
|---|---|---|---|
| `bty_work_order_context` | 并入工单 | **1:1**（同事务插入、只插不改）；但可选：没有上下文的旧工单照常可用 | ✅ 能并：上下文列在工单上可空，保留「有无上下文」的区分；`customer_ref = 身份类型:身份引用` 的交叉核对改为同行比较 |
| `bty_guest_claim_receipt` | 并入工单 | **与工单无关**：每店每顾客一行，先于任何工单存在；还用于**预约**的归属判断（`bty_appointment` 上的 `EXISTS`）；「店 + 顾客」唯一键是防重与并发仲裁 | ❌ 放不进工单行 |
| `bty_arrival_challenge` | 并入工单 | **先于工单**：商户发核验码（90 秒），到店入座时要求「还没有工单」；码唯一、「操作人 + 键」唯一是防重 | ❌ 放不进工单行 |
| `bty_source_observation` | 并入绑定 | **N:1**：每张商家订单一行，跨工单共用；先于任何绑定建，是分配、开工、改范围的**根锁**；触发器与收编都要求「还没有绑定和仲裁行」 | ❌ 放不进绑定行 |
| `bty_source_allocation_guard` | 并入绑定 | **N:1**：每个来源行一行，`已分配 ≤ 来源数量` 是单行计数器，加锁后比较交换，库里还有检查约束兜底 | ❌ 拆到绑定行就没有单一串行点和库级上限 |
| `bty_service_execution_fence` | 改成提交表的唯一约束 | 与 START 提交 **1:1**，但「每任务一次」「每来源行每份一次（跨工单）」两条唯一键要用提交表没有的列；自带第二套状态机（意图、观察轮次、来源围栏版本、授予、粘滞的暂停凭证）、不可改与不可删触发器、库存意向按它的版本比较交换、运行时结构校验 | ❌ 只靠唯一约束替代不了 |
| `bty_service_participation` | 并入 `ind_assignment` | 生产代码**没有写入**，只有工单详情投影与「关闭冻结范围」的检查在读 | ✅ 能并：`ind_assignment` 加目标类型 `TASK`；`participation.assignment_no`（任职）与指派号重名，改存到新列；`planned_assignment_no` 一并补列 |
| `bty_store_policy`、`bty_store_policy_version` | 并入 `ind_store_policy`、`ind_config_revision` | 列都有对应；美业**保留历史**（按生效时间取版本、也按头指针取），与餐饮「每店每行业一行、不留历史」不冲突：头在 `ind_store_policy`，版本在 `ind_config_revision` | ✅ 能并：所有按门店取头的查询补 `industry_code='BEAUTY'`，读版本补 `config_kind`；首次创建的仲裁改由「门店 + 行业」唯一键承担 |

能并的 3 组照做；放不进的 4 组见 §8-Q1。

**最终表集（用户 2026-11-28 定：放不进的 4 组保留独立表；美业需要的表添加或迁移到行业库）**：
- 美业专有，行业库独立表（9 张）：`bty_work_order`（并入工单上下文）、`bty_service_task`、`bty_service_step`、`bty_trade_binding`、`bty_service_submission`、`bty_reception_operation`、`bty_guest_claim_receipt`、`bty_arrival_challenge`、`bty_source_observation`、`bty_source_allocation_guard`、`bty_service_execution_fence`（共 11 张，其中后 5 张即 §8-Q1 保留的 4 组）。
- 行业共用：`ind_store_policy`、`ind_config_revision`（门店策略）、`ind_assignment`（服务参与，加目标类型 `TASK` 与任职号、计划任职号两列）。
不在本篇：操作日志（归基础能力库审计）、库存意向（退役，随库存）、预约与资源占用、服务定义（交易库）。

### 2.4 实施步骤

| # | 动作 | 说明 |
|---|---|---|
| B-1 | 行业库测试建表与句柄登记 | 美业的行业表按定稿结构写进 `db/n_industry`（设计 DDL 已有其中 10 张；执行围栏、`ind_assignment` 两列在 `V2` 补）；建一个与餐饮共用的行业库建表入口（美业是独立构建，不依赖餐饮）；美业登记自己的行业表清单，启动校验在行业句柄上查 |
| B-2 | 门店策略并入 `ind_store_policy`、`ind_config_revision` | 头查询带 `industry_code='BEAUTY'`，版本带 `config_kind`；首次创建靠「门店 + 行业」唯一键；保留按生效时间取历史版本 |
| B-3 | 工单上下文并入工单 | 上下文列在工单上可空；五条查询里的「身份交叉核对」改为同行比较；上下文 Mapper 退役 |
| B-4 | 服务参与并入 `ind_assignment` | 只改读与关闭检查（生产代码无写入） |
| B-5 | 拆跨句柄语句与事务 | §2.2 逐条 |
| B-6 | 新库测试、部署脚本、设计稿 | 美业行业表在本机 MySQL 新库上跑；.61 变更脚本沿用餐饮的「只读核对 → 备份 → 执行 → 核对」；设计 DDL 与清单同步 |

测试基线（2026-11-28，本机 MySQL 9.7.4，未改代码）：beauty-core 407 个用例中 33 个失败或报错，其中 28 个因美业单独构建时拿不到 shop-app 的旧迁移 `V592__shared_personnel_foundation.sql`；每步与这份清单对比，不新增失败即可。

**B-1 已做（2026-11-28）**：
- `beauty-core` 测试资源 `db/n_industry/V1__industry_design.sql` 是设计稿 `06-n_industry.sql` 的副本（去掉建库语句，38 张）；`V2__beauty_supplement.sql` 补设计稿没有的三样：来源观察的域守卫触发器、执行围栏及其两个不可改触发器（结构取自 .61 `SHOW CREATE TABLE`，旧迁移 V10 的 CHECK 写法在 9.7.4 的 mysql 客户端下建不出来）、合并完成前代码仍读写的 4 张过渡表（门店策略头与版本、工单上下文、服务参与）。新库共 43 张表、3 个触发器。
- `BeautyIndustryTables` 登记美业在行业库的表（11 张定稿 + 4 张过渡）；B-2～B-4 每并一组就删一张过渡表。
- `BeautyIndustryDatabaseIT`：在本机 MySQL 用 V1、V2 新建行业库，断言行业句柄下的每个 Mapper 都已登记、且按实体全字段能在新库上查询，触发器齐全。
- 与原计划的出入：美业是独立构建，行业库建表脚本直接复制设计稿，没有与餐饮共用一个入口（两份都以设计稿为准，B-6 同步设计稿时一并核对）；`ind_assignment` 的两列随 B-4 一起加；启动校验暂由上面的集成测试承担，B-6 视部署方式再定。
- 全套（`-Dtest='*Test,*IT'`）：405 个用例 34 个失败，比基线多 `ReceptionBusinessIT#reassignsCheckedInWithFrozenRules`；移开 B-1 的文件后照样失败，是既有的时刻相关缺陷（读回的到店时间差 4 小时，即本机时区），不归本篇。

**B-2 已做（2026-11-28）**：
- 实体 `BtyStorePolicy` 映射到 `ind_store_policy`、`BtyStorePolicyVersion` 映射到 `ind_config_revision`（类名不改）。头上加行业码（默认 `BEAUTY`），版本上加配置类型（默认 `STORE_POLICY`）与门店号。所有读都经实体的 `beautyQuery()` 带上这两个条件；`StorePolicyMapper` 的加锁查询与切换指针的 SQL 也同样带上。
- 首次创建由共用表的「主体 + 门店 + 行业」唯一键仲裁（原来是「主体 + 门店」）。按生效时间取历史版本、按头指针取当前版本的写法不变。
- 共用表的 `tenant_no` 没有库默认值（旧美业表默认 `'MAIN'`），平台审计也不填它；两个实体在构造时带上 `MAIN`。漏掉这一步时，新行的租户为空，按 `tenant_no='MAIN'` 查不到，唯一键也拦不住空值，并发首建会出两个头（测试抓到过）。
- 数据范围登记改成两张共用表的表名，和餐饮登记的锚点相同。
- 美业行业表清单换成两张共用表，测试建库的 `V2` 删掉两张过渡表。
- 测试：美业测试库在私有 Flyway 之后按行业库设计稿补建这两张共用表（测试里三条句柄指向同一个库）；新增「同店有餐饮行」用例；用到旧表名的测试一并改过。宿主两个测试（`BeautyPolicyEndToEndIT`、`BeautyReceptionIdentityIT`）也改了表名，并补建共用表。
- 结果：beauty-core 全套与 B-1 相同（基线 + 时刻相关的那一个）；其余模块 api 2、web 73、channel 63（跳过 1）、server 48 全过；宿主 `BeautyEmbeddedWiringTest` 通过。
- 宿主的 `BeautyPolicyEndToEndIT` 启动上下文就失败：缺交易句柄的 `SqlSessionFactory`。这是交易、行业句柄拆分时这个测试没跟上，在碰到任何表之前就失败，与本步无关；`BeautyReceptionIdentityIT` 要打开开关才跑，这次只确认了编译。
- 上线要注意：本步之后，美业门店策略要读写行业句柄所指库里的 `ind_*` 两张表。行业句柄没绑定时用的是主库，而主库没有这两张表。所以 B-2 不能先于 B-6 单独上线；B-6 的脚本还要把主库旧表 `bty_store_policy`、`bty_store_policy_version` 里的数据搬进 `n_industry`（行业码 `BEAUTY`、配置类型 `STORE_POLICY`、版本补门店号）。旧表留在私有 Flyway 的冻结清单里，代码不再读写。

**B-3 已做（2026-11-28）**：
- 工单上下文的 5 列（身份类型、身份引用、开单接待操作号、预约安排版本、冻结范围）在工单实体上，与设计稿一致，可空。没有上下文的旧工单这几列为空。
- `BtyWorkOrderContext` 改成从工单行取出的只读视图（不再是表实体），这样 `ServiceSubmissionAccess` 等用它的地方不用动。上下文 Mapper 退役，改由工单 Mapper 的 `selectContext` 取；开单时 `attachContext` 在同一事务里把上下文写到工单行上，只写一次，已有上下文的行不覆盖。
- 五条查询的「身份交叉核对」改成同行比较 `customer_ref = 身份类型:身份引用`：按范围读工单、本人读、认领候选、认领读、接待开单回读。按范围读时，没有上下文的旧工单照常可读。
- **校验交接（用户 2026-11-28 定）**：已迁到行业库的表，主库私有 Flyway 的冻结校验不再逐列比（主库上只剩冻结的旧结构）。改由新的 `BeautyIndustrySchemaCheck` 在启动时在行业句柄上核对两件事：行业句柄下的 Mapper 与 `BeautyIndustryTables` 一致；每个实体的列都能查。执行围栏是可选扩展（V10），仍由它自己的校验器判断，启动时不要求它存在。行业句柄没绑到 `n_industry` 时，启动就报出缺哪些表和列。
- 测试：测试库在私有 Flyway 之后给工单补上下文列；用到上下文表的测试改成读工单行。主库校验测试里改动行业表约束、断言被拒的用例，不再成立，处理如下：
  - 校验通用逻辑的（字面量里的反引号、大小写、运算分组、JSON 函数、唯一索引、数量约束）改到主库仍校验的表上；
  - 针对具体行业表的 3 个（到店核验、认领回执、V6 守卫）换成一个「行业表交给行业核对」的用例：改动这些表的约束不挡主库校验，缺列由行业核对挡下；
  - 「工单上下文的门店与工单不同」这个场景合并后不会出现，从提交用例里去掉。
- 结果：beauty-core 全套与 B-1 相同（基线 + 时刻相关的那一个），其余模块全过；宿主 `BeautyEmbeddedWiringTest` 两种构建形态都通过。
- 宿主那几个要打开开关才跑的原生测试（`ManualSource*IT`、`BeautyClaimedGuestResourceIT`、`BeautyReceptionIdentityIT`）启动时跑美业迁移，库里没有行业表和上下文列，现在会在启动核对时停下。这次只改了表名、确认能编译，它们的建库随 B-6 一起补。

**B-4 已做（2026-11-28）**：
- 服务参与的实体 `BtyServiceParticipation` 映射到行业共用的指派表 `ind_assignment`：对象类型 `TASK`，对象号是任务号，性质 `ACTUAL`，状态 `ACTIVE`/`ENDED`。字段名不改，列名用 `@TableField` 对上：
  - 参与号 → `assignment_no`
  - 资源占用 → `hold_no`
  - 前一参与段 → `previous_assignment_no`
  - 交接理由 → `handover_reason`
  - 资格快照 → `skill_snapshot`
- 参与里的「有效门店任职号」和指派自己的号重名，与「原计划安排号」一起放进 `ind_assignment` 新加的两列：`employment_assignment_no`、`planned_assignment_no`。对象类型的注释补上 `TASK`。这些都写在测试建库的 `V2` 里，设计稿随 B-6 同步。
- 三处 SQL 都带 `target_type='TASK'`：按任务读参与、结束参与（同时把状态置为 `ENDED`）、关闭工单时「还有没结束的参与」检查。按任务读的那条改成按列取别名（`BtyServiceParticipation.COLUMNS`）：手写的 `SELECT *` 会按列名自动映射，`assignment_no` 会被塞进任职号字段。
- 同 B-2：共用表的 `tenant_no` 没有库默认值，实体构造时带上 `MAIN`。数据范围登记改成 `ind_assignment`，行业表清单去掉最后一张过渡表；A6 的三组合并到此完成。
- 测试：测试库补建 `ind_assignment`，并执行 `V2` 里加两列的语句；参与的种子数据改写到指派表；新增「同号的餐饮指派行（对象类型 `VISIT`）不算服务参与」。宿主测试里的计数改成查指派表。
- 结果：beauty-core 全套比基线多两个失败：
  - 一个是时刻相关的那个（同 B-1）；
  - 另一个是 `ServiceSubmissionIT#concurrentSameEndpointKeyReplaysOneCommittedSubmission`，并发比对修订号。在 B-3 的提交上单独跑 6 次也失败 2 次，是原有的不稳定用例，走的路径也碰不到服务参与。
- 其余模块全过；宿主 `BeautyEmbeddedWiringTest` 两种构建形态都通过。

**B-5a 已做（2026-11-28）：测试能把行业句柄连到独立的库**
- 开关 `-Dbeauty.test.industry-db=separate`（默认 `shared`，与主库同一个库）：
  - 测试另建一个按 `db/n_industry` V1、V2 建的库；
  - 应用上下文按 `shop.datasources.n_industry.*` + `shop.modules.industry.datasource=n_industry` 绑定。数据源名不能和模块同名，否则启动直接拒绝。
- 测试直接读写表的 SQL 走 `IndustryRoutingDataSource`，按表名分到两个库，测试代码基本不用改：
  - 一条 SQL 同时用到两个库的表就报错；
  - 测试建的触发器记下建在哪个库，删时照着走。
- 只碰行业表的 Mapper 测试改用行业句柄的事务（`industryTx`）。
- 默认仍是 `shared`，每次提交全套保持绿；B-5 收尾时把默认改成 `separate`。
- 打开 `separate` 跑全套：新增 26 个失败。去掉测试本身的问题后，暴露出：
  - **跨库 SQL**：接待「可恢复」查询关联主库表，直接报表不存在（6 个）。
  - **静默错结果**：「我的预约」按认领回执过滤（`CustomerOwnership`）不报错，却查不到行——主库上还留着旧的空表 `bty_guest_claim_receipt`。分库后旧表留在主库，比直接报错更危险；B-5d 拆这条时要配一个只在独立库下才过的用例。
  - **TDD 漏掉的跨库点**：幂等记录（`sys_idempotent`，主库）在行业事务里写，比如门店策略发布，并发首建就不再只有一个赢家。
  - 「审计写失败，业务也回滚」的用例在独立库下不成立：操作日志在主库。B-5c 要定是否接受「提交后写日志」带来的语义变化。
  - 接待相关的事务问题（`WalkInRecoveryIT`）。
- **设计稿缺陷**：`bty_service_step.status` 设计稿是 `varchar(10)`、注释写 `DOING`，代码用 `IN_PROGRESS`（11 字符，旧表 `varchar(24)`）。测试建库的 `V2` 先改正，设计稿随 B-6 同步。B-1 的核对只看列在不在、不看宽度，所以没发现。

**B-5b 已做（2026-11-28）：接待操作只经 `ReceptionSteps` 写**
- 按 §2.2.1 建 `ReceptionSteps`，提供以下方法：
  - `insert`：受理；
  - `update` / `save` / `clearRetry`：只改接待操作；
  - `claimResource` / `releaseResource`：恢复扫描的租约；
  - `advance`：先交易、后行业，推进阶段；
  - `once`：签到、接待异常这类一次性接待。

  它不存状态，各类拿已有的数据库访问对象和 Mapper 自己建，所以构造与装配不改。
- 实际写点比当初数的 15 处多：签到（BAPI-047、CAPI-154）、接待异常（BAPI-053）经 `begin` / `complete` 在交易事务里受理并收尾；开单「替换安排」在预约准备的事务里挂接接待操作。共 6 个类、38 处写或加锁，全部迁走。
- 预约侧能安全重做的依据：
  - 建预约：冻结的预约号、预约操作号已存在，即为已做过。
  - 标到店：预约已到店、版本为冻结版本加 1，且有这次接待的到店日志。
  - 记日志：按「这次接待的操作号 + 动作」查到已记就不再记。
  - 开单替换的挂接：在 `linkOuter` 里，按调用就补，所以重放路径也会补上。
- 一次性接待（签到、接待异常）原来在一个事务里，失败不留痕。现在先受理再做预约侧：**预约侧没做成时**，`once` 删掉刚受理、仍是「已受理」且无结果的那一行（`ReceptionOperationMapper.discardReceived`）。预约侧已提交、只是落结果失败时保留这一行，重试按它补结果。
- 同一次散客接待并发：原来靠锁接待操作排队。现在输的一方在建预约时撞唯一键，再走一遍就认出已建、只补挂接。
- 受理前的门店归属锁（宿主能力，主库）原来和插入接待操作在同一个事务里，现在先在交易事务里核对，锁放掉后才插入。中间有一小段窗口；门店改挂极少发生，接受。
- 开单「替换安排」后推进到「等开工单」的那一步，原来顺带锁住预约。分库后这把锁不再起作用，而下一步开单本来就在交易事务里锁住预约重新核对，所以去掉。
- 约束测试 `ReceptionStepsBoundaryTest`（单元测试，pre-push 美业闸门会跑）：除 `ReceptionSteps` 外，没有类对接待操作的 Mapper 做写入或加锁。
- 行为变化：`WalkInRecoveryIT#atomicCreatePreparationRollsBackWithoutHalfLinks` 的「挂接接待操作失败」那一例。原来预约侧整体回滚；现在预约侧已建好、接待操作仍是「已受理」，由同一用例后半的恢复补上挂接并成功，用例按此改了断言。预约侧任一表失败的 5 例仍是整体回滚。
- 结果：
  - 共用库模式下全套与基线一致（只多时刻相关那一个）。
  - 独立库模式失败从 60 降到 47。接待事务类的问题都消失了；剩下的是恢复扫描的跨库查询、「我的预约」认领过滤（B-5d），以及操作日志、幂等记录（B-5c）。

### 2.6 清理旧库连接（用户 2026-11-28：清理旧库的链接，访问迁到新库；按 `n_trade_v2` 与「商品领域迁移-交接」重新整理）

**前提**：
- 「主库」就是旧库（`spring.datasource`，.61 上是 `ai_shop_ume`）。用户 11-27 定业务不再访问，配置已删。
- 门店交易库用新建的 **`n_trade_v2`**（.61，按定稿 `05-n_trade.sql` 建，67 张空表，逐列核对一致；设计文件里库名仍写 `n_trade`，部署时数据源指向 `n_trade_v2`）。
- 按交接文档：商品句柄、交易句柄绑同一个数据源名，都指向 `n_trade_v2`。美业的交易句柄跟着绑过去。
- 绑定与给应用账号授权由用户确认；.61 上目前没有应用账号能访问 `n_trade_v2`。
- 改了定稿（加列、加约束）要同步生成器 `scripts/gen-retail-ddl.py` 与 `n_trade_v2`。

**逐项去处**（2026-11-28 按列逐表比对）：

| # | 美业现在用的（旧库） | 新去处 | 列对得上吗 | 状态 |
|---|---|---|---|---|
| 1 | 预约 `bty_appointment` | `n_trade_v2.ord_appointment` | 全有；新增 `work_order_no`，代码已在用 | 设计已定 |
| 2 | 预约内容 `bty_appointment_item` | `ord_appointment_item` | 全有 | 设计已定 |
| 3 | 预约安排 `bty_appointment_arrangement` | `ord_appointment_revision` | 全有 | 设计已定 |
| 4 | 预约准备 `bty_appointment_preparation` | 并入 `ord_appointment_revision`（「未确认的草稿也是一版」：预览号、操作人、过期时刻都在） | 缺 `actor_kind`、`expected_revision`、`frozen_plan`、`plan_hash` | **待定**：加列，或并进 `snapshot` |
| 5 | 预约当前行 `bty_appointment_current` | `ord_appointment.current_revision` | 缺「进行中的预约操作号」`operation_no`（它是预约操作的并发闸门） | **待定**：随第 6 项 |
| 6 | 预约操作 `bty_appointment_operation`、资源围栏 `_resource_fence`、资源意向 `_resource_intent`、资源决策 `_resource_decision` | 定稿里没有。资源占用改由平台预约 `rsv_booking` 承担：有预留、确认、释放三态，`current_key` 仲裁当前占用，取代 `mch_booking_owner` | 无对应表 | **待定**，见下 |
| 7 | 指定技师 `bty_appointment_assignment` | 行业库 `ind_assignment`（性质 `PLANNED`，对象 `APPOINTMENT_ITEM`，带预约修订号） | 缺 `requirement_no` | **待定**：加列，或放进 `skill_snapshot` |
| 8 | 服务定义 `bty_service_definition` | `prd_goods`（`type=SERVICE`、`industry_code=BEAUTY`；`industry_ext` 放时长、前后缓冲、步骤模板、注意事项） | 定义号、SKU、当前版本、状态都改用商品自己的列 | 设计已定，代码改动大 |
| 9 | 定义版本 `bty_service_definition_version` | `prd_goods_revision`（「服务项目的规则版本即此号」；`payload` 存时长快照、缓冲、步骤、注意事项） | 版本号从字符串（如 `DV…`）变成商品内自增的整数 | 设计已定；`ord_appointment_item.definition_version_no` 的取值要对齐 |
| 10 | 服务要求 `bty_service_requirement` | `prd_fulfillment_requirement`（按商品修订号） | 只缺定义版本号，换成 `goods_no + revision_no` | 设计已定 |
| 11 | 库存意向 `bty_service_execution_stock_intent` | 库存库 `inv_reservation`，经宿主库存接口 | — | **待定**：交接文档里库存同步一组暂停中 |
| 12 | 操作日志 `bty_action_log` | 基础能力库 `n_common.sys_audit_log`。对应关系：`action_no`→`audit_no`，`aggregate_type/no`→`resource_type/no`，前后版本→`before/after_version`，快照→`after_masked`，接待操作号→`request_id`，门店与人员放进快照 | 门店号、人员号没有独立列 | **待定**：写法，推荐业务提交后写 |
| 13 | 请求幂等（宿主 `sys_idempotent`）；发布前 `PlatformWritePrerequisites` 检查主库技术表 | 不建表：防重是功能能力，由应用层 + 业务唯一键做（用户 11-25 原则） | — | 去掉对主库技术表的检查；各写接口改按业务键 |
| 14 | 独立部署时的事件 outbox | `n_common` 公共事件 | — | 事件是公共能力 |
| 15 | 私有 Flyway（`bty_flyway_history`）、`BeautySchemaValidator`、要求主库工厂的 Mapper 配置 | 退役；结构由各库部署脚本负责 | — | 启动核对：行业库已有，交易库补一个 |
| 16 | `BeautyStoreRelinkFactsPortImpl`、两个结构校验器 | 各查各的库 | — | 随上面各项 |

**第 6 项要先定的问题**：
- 美业现在自己维护一套「预约对资源占用的编排」：预约操作记命令的防重、受理载荷、回执、重试与租约；围栏、意向、决策串起「同一张预约同时只有一个资源命令在途」。
- 定稿把资源占用交给平台的 `rsv_booking`（三态 + `current_key` 唯一约束仲裁）。从逻辑推：围栏、决策的仲裁作用由 `rsv_booking.current_key` 承担；命令防重按「应用层 + 业务唯一键」做。
- 但预约操作还承载异步恢复：资源系统超时后，靠它的重试、租约、回执把命令补完。这部分要么并进预约修订（每次改约一行，本来就对应一次占用），要么证明 `rsv_booking` 自身的状态足以恢复。需逐表读代码出方案，像 A6 那样。

**建议顺序**：
1. 不依赖交易侧设计、可以直接做的：第 13、14、15、16 项，以及 B-5d（拆跨库 SQL）。
2. 第 1～3、8～10 项设计已定，直接迁。服务定义并入商品（第 8～10 项）要和商品领域的写法对齐，见交接文档 §3-9。
3. 第 4、5、6、7、11、12 项先出方案、用户定稿再改；第 6 项最大。
4. 句柄绑到 `n_trade_v2` 与授权，由用户确认后再做。

**第一步已做（2026-11-28，B-5d + 第 13、16 项）**：
- 跨库 SQL 拆开：
  - **接待恢复扫描**：行业库按接待操作自己的条件，按「下次重试、id」翻页取候选；能否恢复由预约侧一条 SQL 判断（`AppointmentOperationMapper.receptionRecoverable`，条件与原来逐条相同）；凑满 50 个为止。
  - **「我的预约」**：先在行业库查出本人认领的「主体 + 门店 + 访客」，再按值拼进交易库的查询（`CustomerOwnership.appointmentPredicate(user)`），条件仍在分页之前生效。
  - **库存意向的观察更新**：去掉对执行围栏的 `EXISTS`。围栏已在行业事务里锁住（`withStockOriginal`），改由协调器按锁住的围栏核对围栏号、执行号。
- 交易事务里锁行业表的两处，改成普通读：
  - 恢复请求先按普通读核对提交的版本，再记录（新增与加锁查询同条件的 `ServiceSubmissionMapper.selectScoped`）；
  - 开单核对门店政策头，按版本做乐观核对。核对之后才发布的政策，下一次预约才用到。
- 第 16 项：
  - 门店改挂核对按表所在的库分别查：预约在交易库、预约操作仍在主库、工单与接待操作在行业库。
  - 执行围栏的结构校验器改到行业句柄（原来误用交易句柄）。没有美业私有迁移历史的库（行业库由部署脚本建）只核对结构与触发器；主库上原有的迁移记录核对不变。
- 第 13 项：去掉门店策略、服务定义发布前对主库技术表的检查（`PlatformWritePrerequisites`）。防重由宿主的幂等端口和业务唯一键负责。
- 约束测试②：`TransactionHandleBoundaryTest`，跨库只能读，一个事务不能写或锁另一个库的表。已知例外必须恰好等于清单：现在只有服务提交在行业事务里写操作日志一处，等第 12 项。修好后从清单删掉，测试会逼着删。
- 修正：
  - 第 15 项（私有 Flyway 与主库 Mapper 配置退役）**不能现在做**：主库句柄上的 6 张表还在用，要等第 6、11、12 项定了、表迁走以后。
  - 第 14 项（独立部署的事件改到 `n_common`）**先不动**：宿主自己的 outbox 还在主库，并且明确拒绝统一模式下单独绑定基础能力库。要等宿主的事件迁移，美业跟随。
- 结果：
  - 共用库模式全套与基线一致（只多时刻相关那一个，以及偶发失败的并发用例）。
  - **独立库模式从 47 降到 37**，比基线只多 3 个，都等第 12 项（操作日志）与幂等记录：门店策略首次发布并发、外层事务回滚操作日志、开单审计回滚。
  - 其余模块全过。宿主 `BeautyEmbeddedWiringTest` 8 个里 1 个失败（`disabledBeautyKeepsRetailBootable`，缺会员营销的 `memberMarketingSqlSessionFactory`），origin/v2 上一样，不是本次带来的。

**第二步，第 1～3 项已做（2026-11-28）**：
- 预约、预约内容、预约安排改用门店交易库的定稿表：`ord_appointment`、`ord_appointment_item`、`ord_appointment_revision`。宿主、餐饮、商品都还没有用到这三张表，美业是第一个使用者。
- 主代码与测试里的表名整体替换。美业私有迁移的冻结校验，以及专测旧迁移的两个测试，仍是旧表名：它们测的是主库上的旧结构，`ResourceIntentMigrationIT` 改为直接往旧表写种子数据。
- 实体：
  - 构造时带上 `tenant_no = MAIN`（这三张表也没有库默认值）；
  - 预约类型 `appointment_kind = SERVICE`、项目行类型 `item_kind = SERVICE`，与同表的餐饮订位、预点菜品区分；
  - 修订状态 `status` 暂不填，随第 4 项（预约准备并入修订）一起定。
- 取值差异，留给搬迁脚本：设计注释里「已到店」写 `ARRIVED`，美业用 `CHECKED_IN`（10 个字符，列宽正好够）。
- 测试：
  - 交易库设计 DDL 复制到测试资源 `db/n_trade/V1__trade_design.sql`，测试库按它建这三张表；交易句柄在测试里仍指向主库，尚未单独建库；
  - 宿主的 `BeautyIndustrySharedTables` 一并建这三张表。
- 顺带处理：本机磁盘被美业测试留下的一次性库占满，删掉了 1 小时前建的 5342 个（约 29G）。`BeautySchemaMigrationIT` 记下本进程建的库，进程退出时统一删除；`ManualExecutionSourceIT` 每个用例结束后删除。
- 结果：
  - 共用库模式全套与基线一致（只多时刻相关那一个，以及偶发失败的并发用例）；
  - 独立库模式与第一步相同。
- 第 8～10 项（服务定义并入商品）**还没做**：`prd_goods`、`prd_goods_revision` 归商品领域（shop-core 的 `ProductMappers`），美业不能直接写；独立部署的美业里也没有商品代码。要先定由谁发布服务的时长、缓冲、步骤和服务要求，见 §8-Q2。

### 2.7 服务定义经商品接口写（用户 2026-11-28 定，§8-Q2 ②）

**现状**：
- 美业的服务定义只有一个写入口：发布定义版本（SKU、期望修订、时长与前后缓冲、服务要求、注意事项）。
- 有 6 处读：服务目录查询、预约匹配、开单准备、提交查询等，按「门店 + SKU」取当前版本，或按版本号取。
- 版本号 `definition_version_no` 是 `BTDV…` 这样的字符串，冻结在预约项目行、工单冻结范围和服务任务里。
- 商品领域已有完整的商品修订流程（草稿 → 送审 → 线上 → 被替换 / 驳回，`GoodsRevisionService`），但代码里还没用到 `prd_goods.industry_ext`，`prd_fulfillment_requirement` 也还没有代码。

**接口**（放 `shop-base` 的 `spi/product`，沿用「端口 + 内部 HTTP 接口」的写法：嵌入部署走进程内，独立部署走内部调用）：

| 方法 | 做什么 | 商品侧落在哪 |
|---|---|---|
| `publish(范围, skuNo, 期望修订, 时长与缓冲, 服务要求, 注意事项, 操作人, 幂等键)` | 发布一版服务执行定义，返回 `(goodsNo, revisionNo, 摘要)` | `prd_goods.industry_ext`（当前生效的时长、缓冲、步骤、注意事项）、`prd_goods_revision`（这一版的快照，`revision_no` 即规则版本）、`prd_fulfillment_requirement`（按 `goods_no + revision_no` 的服务要求） |
| `current(门店, skuNo)` | 当前生效的执行定义 | 读 `prd_goods` 与最新线上修订 |
| `byRevision(主体, goodsNo, revisionNo)` | 某一版的执行定义（含服务要求） | 读 `prd_goods_revision.payload` 与 `prd_fulfillment_requirement` |

美业侧：服务定义三张表的 Mapper 退役，读写都换成这个端口；发布接口（BAPI）、幂等、操作日志留在美业。

**待定**（要和商品领域对齐）：
- **Q3 发布执行定义要不要走商品审核。** ✅ 用户 2026-11-28 定 ①：只改执行定义的修订免审直接上线。商品修订默认要送审，美业现在发布即生效。可选：
  - ① 只改执行定义（时长、缓冲、步骤、服务要求）的修订免审直接上线；改标题、价格等仍走商品审核。（推荐）
  - ② 一律走商品审核，美业发布后等审核通过才生效。
- **Q4 版本号怎么存。** ✅ 用户定 ①：只存修订号。 规则版本改成「商品号 + 商品内自增的修订号」。预约项目行、服务任务里的 `definition_version_no`（`varchar(32)`）存不下「商品号 + 修订号」。可选：
  - ① 只存修订号，商品号从同一行的 SKU 推出（推荐）；
  - ② 加列存商品号。
- **Q5 商品侧谁来实现。** ✅ 用户定 ①：本会话实现，提交前请商品会话核对。 交接文档说商品代码归商品迁移会话，评价与库存同步还在暂停中。可选：
  - ① 本会话实现端口及商品侧落库，提交前请商品会话核对；
  - ② 本会话只定接口、写交接，由商品会话实现。

### 2.5 部署

沿用 `deploy/ume/food-industry-cutover/` 的做法（只读 `check` → 备份 → 执行 → 核对），美业表另起一份脚本；共用表只原地改。

## §3 选型

| 方案 | 结论 |
|---|---|
| 按定稿 DDL，美业 10 张原样保留 | ❌ 用户 2026-11-28 选 A6 全并 |
| 按 A6 全并（认领、核验并入工单；来源观察、分配仲裁并入绑定；执行围栏改唯一约束；参与并入指派；门店策略并入共用表） | ✅ 用户 2026-11-28 定 |

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 共用表上同店两个行业的行 | 美业按门店取头的查询取到餐饮的行，或多行报错 | 美业头查询一律带 `industry_code='BEAUTY'`；补「餐饮与美业同店」用例 |
| 共用表的约束比美业旧表松 | `ind_*` 列多为可空、无检查约束、非 `_bin` 排序 | 美业写入前在服务里校验；必要的检查约束随 `V2` 补 |
| 美业测试依赖真 MySQL 与私有 Flyway | 改表后私有迁移、结构校验与宿主测试要一起改 | 行业库部分改用 `db/n_industry` 建表，私有 Flyway 只留未迁的表 |
| 宿主 `shop-app` 的美业测试直接写旧表 | 换表后这些测试失效 | 同批改写 |

## §8 待定

- **Q2 服务定义并入商品后由谁写（§2.6 第 8～10 项）。** ✅ 2026-11-28 用户定：② 美业保留发布入口，经商品领域的接口写入。接口设计见 §2.7。
- **Q1 A6 里放不进目标表的 4 组怎么处理。** ✅ 2026-11-28 用户定：① 保留独立表，留在行业库。 读代码后（§2.3）认领回执、到店核验、来源观察与分配仲裁、执行围栏按 A6 原文合并会丢掉防重、并发串行点或库级上限。可选：
  ① 这 4 组保留为独立表，留在行业库（与定稿 DDL 一致）；
  ② 换落点：认领回执归会员营销库（拆库清单原定 `mbr_guest_registration`）、到店核验归账号认证的一次性票据（原定 `auth_ticket`），来源观察与分配仲裁、执行围栏仍独立留在行业库；
  ③ 坚持并表，改成「一表多种行」（如绑定表加行类型承载观察与仲裁行，提交表加围栏列与生成列唯一键）：改动最大，触发器与结构校验要重写。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-28 | 用户定：美业对齐先做行业库部分，交易库部分随交易簇乙与商品领域迁移；行业库部分按 A6 全并 |
| 2026-11-28 | 逐表读代码：A6 的 7 组合并里 3 组可并（工单上下文、服务参与、门店策略），4 组放不进目标表（§2.3、§8-Q1），待定 |
| 2026-11-28 | 用户定：放不进的 4 组保留独立表（§8-Q1 ①）；美业需要的表添加或迁移到行业库；工单与工作流服务解决通用问题，美业功能尽量用行业方案解决（§2.2 原则） |
