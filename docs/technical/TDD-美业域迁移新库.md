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
| 7 | 指定技师 `bty_appointment_assignment` | **退役，不迁**（用户 2026-11-28 定「用修订快照」）：每行都是确认那一版修订快照里安排的照抄；按人员筛预约、人员工作量报表改读当前那一版修订的快照 | 偏离原定「映射到 `ind_assignment` 计划安排」：跨行业的「谁在什么时段被安排」以后需要时再从修订快照派生 | 已改 |
| 8 | 服务定义 `bty_service_definition` | `prd_goods`（`type=SERVICE`、`industry_code=BEAUTY`；`industry_ext` 放时长、前后缓冲、步骤模板、注意事项） | 定义号、SKU、当前版本、状态都改用商品自己的列 | 设计已定，代码改动大 |
| 9 | 定义版本 `bty_service_definition_version` | `prd_goods_revision`（「服务项目的规则版本即此号」；`payload` 存时长快照、缓冲、步骤、注意事项） | 版本号从字符串（如 `DV…`）变成商品内自增的整数 | 设计已定；`ord_appointment_item.definition_version_no` 的取值要对齐 |
| 10 | 服务要求 `bty_service_requirement` | `prd_fulfillment_requirement`（按商品修订号） | 只缺定义版本号，换成 `goods_no + revision_no` | 设计已定 |
| 11 | 库存意向 `bty_service_execution_stock_intent` | **行业库**（2026-11-28 已搬）：表与触发器原样，写入本来就在行业事务里；库存库 `inv_reservation` 与宿主的执行保留表都还没有对应结构 | — | 已搬；库存迁移恢复后再与库存域定是否收进 `inv_reservation` |
| 12 | 操作日志 `bty_action_log` | **退役，不迁**（用户 2026-11-28 定）：8 类纯留痕的业务表里已有；到店证明、接待异常申请、恢复请求这三类改记在所属业务行上 | 运营审计暂不收美业操作（以后要看再接宿主 `AuditLogPort`） | 见下方「第 12 项执行计划」 |
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

#### B-6：行业库部署脚本与设计稿同步（2026-11-28）
- 设计稿：执行围栏、库存意向两张表进 `retail-ddl/beauty-execution-tables.sql`（服务端规范化写法）；生成器 `REPLACE_COLUMNS` 放宽 `bty_service_step.status` 到 `varchar(24)`、`ind_assignment.target_type` 注释加 `TASK`，`ADD_COLUMNS` 给 `ind_assignment` 加美业两列。`06-n_industry.sql` 由 38 张变 40 张。
- 测试：美业行业库 V1 按新 06 重新拷贝；V2 只剩 5 个触发器（设计 DDL 不收触发器）；共用库模式的辅助不再补两列（随 V1 来）。两种模式下 `BeautyIndustryDatabaseIT`、`ServiceTaskPersistenceIT`、`BeautySchemaMigrationIT` 全过，`WorkOrderScopeIT` 只剩 HEAD 上同样失败的 1 条。
- 部署：`deploy/ume/beauty-industry-cutover/`（`gen.py` 从 V1、V2 生成；`run.sh check|apply`：美业表为空才执行、备份、建 12 张表、补共用指派表两列、建 5 个触发器、按期望结构核对）。本机按旧设计稿演练两遍通过，数据保留。
- .61 已执行（2026-11-28 14:12Z，用户批准）：`check` 差异与预期一致 → `apply` 备份到 `~/backup/beauty-industry-20261128T141226Z/`、建 12 张表、补两列、建 5 个触发器，核对通过 → 再 `check` 无差异。`n_industry` 由 28 张变 39 张，美业表与 `ind_assignment` 都是 0 行。详见部署目录 README「执行记录」。

#### 第 4–6 项方案（草稿，2026-11-28，待用户定）：以预约修订为中心

**现状**（2026-11-28 逐表读代码）：美业为「一张预约对资源的一次变更」用了 6 张表，其中 3 张在交易句柄、3 张还在旧主库：

| 表 | 句柄 | 承担的事 |
|---|---|---|
| 预约准备 `bty_appointment_preparation` | 交易 | 服务端冻结的预览：5 分钟有效、只能本人用、一个预览只能建一次；冻结的资源计划原样作资源命令载荷，`plan_hash` 核对回执 |
| 当前行 `bty_appointment_current` | 交易 | 当前生效的安排版本号；`operation_no` 是「同一预约同时只有一个变更在途」的闸门 |
| 预约操作 `bty_appointment_operation` | 旧库 | 请求防重（操作人 + 键摘要）；受理载荷先落库再调宿主；记宿主回执；状态 PENDING → SUCCEEDED / REJECTED / RELEASE_REQUIRED；重试与租约 |
| 资源围栏 `_resource_fence` | 旧库 | 永久的预约根闸门：同一预约只有一个资源意向在途；在途时预约的改时间、改状态、到店都被挡 |
| 资源意向 `_resource_intent` | 旧库 | 每条资源命令一行：准备 → 许可 / 拒绝 → 已对账；锁定计划字节与来源版本；宿主回调 `freeze` 时据此答复是否仍被授权 |
| 资源决策 `_resource_decision` | 旧库 | 许可的原始答复（决策号、围栏号与版本、有效窗口），围栏释放后重放仍返回原答复 |

跨库 SQL 现在只在「交易库 = 主库」时能跑（围栏、当前行与预约根互相 `EXISTS`）。宿主一侧也有两张旧库表：命令防重与回执 `mch_resource_command`、占用归属 `mch_booking_owner`；定稿只给了 `rsv_booking`（三态 + `current_key` 仲裁当前占用）。

**方向**：一次变更就是一行预约修订。新建、改约、到店后改、取消、爽约都写一行修订；修订行自己的状态走完整个生命周期，6 张表收成 `ord_appointment` + `ord_appointment_revision`。

| 原来的 | 收进 |
|---|---|
| 预约准备 | 草稿修订（`DRAFT`）：预览号（加唯一约束）、操作人、过期时刻已有；补「冻结计划、计划摘要、基于的预约版本、操作人类型」 |
| 当前行的安排版本号 | `ord_appointment.current_revision`（已有） |
| 当前行与围栏的「只有一个在途」 | 修订状态 `APPLYING` + 生成列 `applying_key`（在途时等于预约单号）+ 唯一约束，写法照 `rsv_booking.current_key`。预约根上的 `EXISTS` 改成看这一行 |
| 预约操作 | 修订行的「动作、下次重试时刻」；**请求防重与宿主回执不进业务表**：按用户定的「业务库只存业务数据、防重是功能能力」，走基础能力库的统一防重（`n_common`，宿主幂等端口），宿主同一命令号重放返回同一回执也靠它存回执；重试扫描 `APPLYING` 且到期的修订，用行锁抢占，不留租约列 |
| 资源意向 + 决策 | 修订行的「命令号、命令摘要、决策号、许可时刻、有效截止、拒绝码」：宿主 `freeze` 回调按命令号找修订行作答；一经许可，这几列不再改（替代「围栏释放后重放仍返回原答复」的决策表） |

修订状态：`DRAFT` 草稿 → `APPLYING` 在途 → `CONFIRMED` 生效 / `REJECTED` 资源拒绝 / `RELEASE_REQUIRED` 待释放；另有 `EXPIRED` 草稿过期、`DISCARDED` 放弃、`SUPERSEDED` 被新版取代（后三个与定稿注释一致）。

**依赖与要宿主答复的**：
- 宿主的命令防重与回执（`mch_resource_command`）不迁进交易库，改用统一防重（`n_common`）存回执；占用归属（`mch_booking_owner`）不迁，由 `rsv_booking.current_key` 加唯一约束取代（「零售」会话转述的用户规则，2026-11-28）。美业这边只要求宿主：同一命令号重放返回同一回执；`freeze` 回调的契约不变（围栏号与版本改由预约单号与修订号充当）。
- 由谁负责宿主迁移：V2、「零售」两个会话都不在清单里，**待用户指定**。另外，平台基础库换表第 1 步的越界清单里有预约的 4 个文件，用户 11-26 定「跨库加锁改成读一次 + 版本比对，随预约模块对齐时做」，由负责方一并清掉（`交接清单-平台换表与连锁.md` §1.1）。

**要用户定**：
1. 方向：6 张表收进预约修订（推荐），还是保留「预约命令」为独立概念，只把旧库的 4 张搬到交易库。
2. 修订表要加约 12 列（冻结计划与摘要、基于的版本、操作人类型、动作、命令号与摘要、决策号与许可窗口、拒绝码、下次重试），并把定稿里的修订状态扩成上面那组。可接受吗？
3. 取消、爽约也写一行修订（快照不变，计划是释放命令），以统一「一次变更一行」。可接受吗？

**估计**：改动集中在预约协调、预约准备、来源授权、预约恢复、接待几条路径，测试面大（预约、接待、开单、恢复各套）。按「先定稿 DDL 与生成器 → 实体与 Mapper → 一条路径一条路径切 → 两种模式对照」推进。

#### 第 7 项：人员安排改读修订快照（2026-11-28，用户定「用修订快照」）
- 依据：安排行写在确认预约的交易事务里，搬到行业库就成了跨库写；而每行都是确认那一版修订快照 `assignments` 的照抄（占用编号即该版修订的 `hold_no`），唯一的程序用途是和快照逐条比对。同一事实只留快照一处。
- 改动：确认预约不再写安排行；开工核对去掉「安排行 = 快照」那段（快照与冻结范围、任务快照、修订占用编号的核对照旧）；按人员筛预约、人员工作量报表改用 `JSON_TABLE` 读当前那一版修订的快照；退役 `BtyAppointmentAssignment`、`AppointmentAssignmentMapper` 与数据范围登记。旧表留在私有迁移里，随第 15 项退役。
- 测试：报表种子改写进修订快照；只测安排行查询的两条持久层用例删去；「安排行占用编号被改」改为「修订占用编号被改」，同样在冻结前被拒。

#### 第 11 项：库存意向搬到行业库（2026-11-28，用户「先做 7 和 11」）
- 改动：`ServiceExecutionStockIntentMapper` 改为行业句柄；`BeautyIndustryTables` 加这张表、启动核对把它列为可选（同执行围栏）；结构校验器改在行业句柄上，没有私有迁移历史时只核对结构与触发器（同执行围栏）。
- 建表：测试行业库的美业补充脚本 `V2__beauty_supplement.sql` 第五节，表与两个触发器原样取自私有迁移 V11；设计稿与 .61 部署随 B-6（.61 的 `n_industry` 尚未建美业表）。主库上私有迁移建的旧表随第 15 项退役。
- 验证：独立库模式 `BeautyIndustryDatabaseIT`（触发器清单多两个）、`ServiceTaskStockPreparationCoordinatorTest` 15/15、`ServiceTaskPersistenceIT` 17/17、`TransactionHandleBoundaryTest` 通过；共用库模式 `BeautySchemaMigrationIT` 32/32，`ServiceSourceObservationIT` 的 5 条失败在 HEAD 上同样存在（缺迁移脚本）。

#### 第 12 项执行计划：操作日志退役（用户 2026-11-28 定「按建议执行」）

**为什么不迁到 `n_common.sys_audit_log`**：9 处写入里 8 类记的是业务表里已有的事实（发布人和时间、接待操作行、提交单、绑定、回执表），全仓库也没有页面或报表读这张表；搬过去只是在审计库多一份副本，还要为它扩宿主审计端口、协商形状。剩下三类不是副本：它们是业务记录误放在日志里，归回各自的业务行。

**口径**：
- 到店证明：受理时定好到店时刻并存进接待操作行；交易事务按这个时刻标到店；重做时「预约到店时刻 = 这次操作定的时刻」即证明是这次标的。不改交易库表结构。
- 接待异常申请：整笔记在接待操作行（冻结参数存原因，结果存申请内容）；申请号 = 接待操作号；交易事务只锁预约、核版本，不写。接口字段 `requestNo` 不变，取值格式由 `BTAL…` 变为接待操作号。审核流程以后交独立的审核与工单服务。
- 恢复请求：记进 `bty_service_submission`，动作取常量 `RECOVERY`、状态 `ACCEPTED`，`request_key_hash` = 请求命名空间摘要（借现有唯一键 `门店 + 动作 + 请求键摘要` 防重），冻结命令存回执；重放读这一行。表结构不改。
- 旧表 `bty_action_log` 留在私有 Flyway 里，随第 15 项退役；`ai_shop_ume` 已作废，不迁数据。

| 步 | 内容 | 主要文件 | 验证 |
|---|---|---|---|
| **12-1 到店证明** | `ArrivalPayload` 加到店时刻（受理时定，UTC 毫秒）；无预约到店在进入 `ARRIVAL_PENDING` 时由 `WalkInPayload.withRevisions` 一并写入；`ReceptionCoordinator` 两条到店路径按这个时刻标到店、`arrived()` 改为「已到店 + 版本正好加一 + 到店时刻相同」，不再查日志 | `ReceptionPreparation`、`ReceptionCoordinator` | `ReceptionBusinessIT`、`WalkInRecoveryIT` 里「提交后断了重做」的用例；补一条「别的接待先标了到店，这次不能冒领」 |
| **12-2 接待异常申请** | `ExceptionPayload` 加原因；申请号用接待操作号；交易事务只锁预约核版本；申请内容随结果落在接待操作行；去掉 `loggedActionNo` | `ReceptionPreparation`、`ReceptionCoordinator` | `ReceptionBusinessIT` 的异常申请用例改为看接待操作行；重放返回同一申请号 |
| **12-3 恢复请求** | `RECOVERY` 动作写进服务提交表（在行业事务里，与提交单同库）；重放与撞键回查都读这一行；已核对：提交表的扫描、重试、恢复租约查询都按动作过滤（`SUBMIT_SOURCE`/`ADD_SOURCE`、`START`、`OPEN`），不会收进这一行；按单号、按请求键摘要的回查不看动作，所以恢复行用自己的单号（沿用现在的 `recoveryNo`）和带命名空间的请求键摘要，不与提交单撞 | `ServiceSubmissionServiceImpl`、`BeautyMappers.ServiceSubmissionMapper`（如需按号回查） | `ServiceSubmissionIT` 的恢复用例（受理、重放、改了内容的重放被拒、并发同键只一条） |
| **12-4 删除留痕写入** | 去掉其余 8 类写入：门店政策发布、服务定义发布、接待变更准备、顾客认领回执、开工单、提交来源、绑定来源、拒绝无预约到店（连同它的防重查询） | `StorePolicyServiceImpl`、`ServiceDefinitionServiceImpl`、`AppointmentPreparation`、`GuestRegistrationServiceImpl`、`WorkOrderCoordinator`、`ServiceSubmissionServiceImpl`、`ServiceSourceBinding`、`ReceptionCoordinator` | 编译；各自的业务测试照过 |
| **12-5 清理引用** | 删 `BtyActionLog` 实体、`ActionLogMapper`、数据范围登记；`AppointmentPersistence` 与发布、预约、政策、定义四个配置类去掉注入 | `entity/BtyActionLog`、`BeautyMappers`、`BeautyDataScopeRegistration`、`AppointmentPersistence`、`Beauty{Appointment,Definition,Policy}Configuration` 等 | 全仓库 `grep` 不再有 `BtyActionLog` / `ActionLogMapper`（美业范围）；美业结构校验不因代码不用这张表报错 |
| **12-6 测试改造** | 14 个测试文件：数日志条数的断言改看业务行（政策版本、定义行、接待操作行、提交单），清表名单去掉 `bty_action_log`，注入日志 Mapper 的失败注入改挂到业务写入上；美业 server 装配测试去掉该 Mapper | 美业 `AppointmentBusinessIT`、`BeautyCatalogIT`、`BeautyPolicyPublicationIT`、`GuestClaimReceiptIT`、`ManualExecutionSourceIT`、`ManualSourceBindingIT`、`ReceptionBusinessIT`、`ReceptionFixture`、`ServiceSubmissionIT`、`WorkOrderScopeIT`；server `BeautyBm04AssemblyTest`、`BookingServerWiringTest`、`BeautyStandaloneEndToEndIT`；宿主 `BeautyPolicyEndToEndIT` | 见 12-8 |
| **12-7 约束测试** | `TransactionHandleBoundaryTest` 的 `PENDING` 清空（只剩「行业事务写操作日志」这一条已知例外，修好即删） | `TransactionHandleBoundaryTest` | 违规集合恰为空 |
| **12-8 验证** | 美业集成测试两种模式都与 HEAD 对照：不新增失败；独立库模式下与操作日志相关的 3 条（门店政策首次发布并发、外层事务回滚操作日志、开单审计回滚）应消失或改写后通过；美业 server/channel 单测、宿主编译 | — | 失败清单对照（同 P5 的做法） |
| **12-9 文档与提交** | 本节改为已实现、记偏差与测试输出；重新生成派生文档（旧库调用清单、分层、词条）；提交；推送前问用户 | 本 TDD | 生成文档检查全绿 |

**顺序与依赖**：12-1、12-2、12-3 互不依赖，先做（它们是删日志的前提）；12-4、12-5 在三者之后；12-6、12-7 随改随修；12-8、12-9 最后。

**实现记录（2026-11-28）**：
- 12-1～12-7 按上表完成。美业主代码与测试不再读写 `bty_action_log`；`TransactionHandleBoundaryTest` 的已知例外清空。
- **偏差一：第四类只在日志里的记录**。取消预约与登记爽约时，请求里的原因（≤500）与凭证号（≤128）只写进了日志。用户 2026-11-28 定方案 A：定稿 DDL `ord_appointment.cancel_reason` 放宽到 `varchar(500)`、加 `cancel_evidence_ref varchar(128)`（生成器加 `REPLACE_COLUMNS` 改列机制）；受理取消、爽约时在同一个锁住预约的交易事务里写这两列（定向更新、不动版本），释放资源被拒时清掉。.61 `n_trade_v2` 已执行（记在商品交接 §5）。
- **偏差二：提交防重也在读日志**。`acceptedSubmission` 原来靠「已受理」日志回执从命名空间找回提交单号；改为按提交单自己记的请求键摘要找回（`selectByRequestKey`），内容不同即拒。
- 测试：原来挂在日志写入上的故障注入改挂到同一步的业务写入上（到店 `markArrived`、异常核对锁围栏、开单写 `OPEN` 提交、顾客认领回执、定义发布后的美业事务）；数日志条数的断言改看业务行（政策版本的 `created_by`、提交单、接待操作行、预约的取消原因与凭证）。只为审计存在的两条「审计失败回滚」断言改为「同一步业务写入失败回滚」。
- **12-8 对照（与 HEAD 同机同库）**：首轮两种模式各新增 2 条失败，都在 `WalkInRecoveryIT`（后台恢复把无预约到店推进到「待标到店」时没定到店时刻）。修法：进入「待标到店」只经 `WalkInPayload.arrivalPending(版本, 时刻)` 一处，接待协调与后台恢复共用。修后 `WalkInRecoveryIT` 37/37、`ReceptionBusinessIT` 22/22 两种模式全过；`WorkOrderScopeIT` 剩 1 条 `legacyContextIsReadOnly…` 在 HEAD 上同样失败。独立库模式少了 2 条失败（`openScopeRollsBackWhenAuditFails` 与一条并发提交），操作日志相关的失败已不再出现。


**风险**：
- 同一毫秒两次接待标同一张预约到店：第二次在交易事务里因版本不符被拒，不会误判为「这次标的」。
- 接待异常申请号格式变化：端上只回显、不解析，影响限于展示。
- 运营审计看不到美业操作：现状本来就看不到（日志表没有读方），不是退步。

### 2.7 服务定义经商品接口写（用户 2026-11-28 定，§8-Q2 ②）

> **2026-11-28 改**：按「设计简约、抽象合理」重定为**服务 = 商品 + SKU + 独立的服务规格表**，见 §2.8。本节下面「执行定义放 `industry_ext` + 商品修订快照 + 扩展 `ServiceSpec` + 结转」的做法**作废**，只保留作决策记录；端口的四个方法、免审、美业保留发布入口仍有效。

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

**落库细化（2026-11-28 读商品代码后）**：
- **当前生效的执行定义**放 `prd_goods.industry_ext`，内含它所属的修订号。商家在商品编辑里改标题、价格时，商品领域不碰这一列，执行定义不会被冲掉。所以 `expectedRevision` 与 `current` 都看 `industry_ext` 里的修订号（没发布过为 0），不看商品最新线上修订。
- **每一版的快照**放 `prd_goods_revision.payload`。payload 是商品保存指令 `SaveCommand` 的整份 JSON，商品侧用默认的 Jackson 解析（不认识的字段就解析失败，差异展示和取回旧版都会坏）。所以执行定义要放进 `SaveCommand` 已有的服务规格 `ServiceSpec(durationMin, storeName)`：
  - 加可选字段：前后缓冲、步骤模板、注意事项、服务要求，「不传 = 不改」，与 `SaveCommand` 其余可选字段同一口径；
  - 这是商品保存接口 JSON 的**契约扩展**（只加可选字段）。
- **发布一版执行定义**：
  1. 在商品事务里锁住商品行，`expectedRevision` 与 `industry_ext` 里的修订号比对；
  2. 以当前线上修订的 payload 为底，替换服务规格，写一行**直接上线**的修订（免审，用户定 Q3 ①），原线上修订改为被替换；
  3. 更新 `industry_ext` 与 `duration_min`；
  4. 按新修订号写 `prd_fulfillment_requirement`。

  商家若正有一份未发布的草稿，它的「编辑时的商品版本」会对不上，发布时按商品领域原有规则提示「有人中途改过」。
- **服务要求**：`requirement_type` 由资源类型推出（人员 → `ROLE`，其余 → `RESOURCE`）。

**商品会话（「零售」）的核对意见（2026-11-28）**：方向可行，由本会话实现商品侧，提交前把 diff 发给它看。它目前没在改 `MerchantGoodsService`、`GoodsRevisionServiceImpl`、`PrdGoods`。要改的几处：

- **粒度（Q6）** ✅ 用户 2026-11-28 定：服务用独立的服务规格表，「商品默认行 + 按 SKU 覆盖行」（§2.8）。原问题：`industry_ext` 是商品级的一列，而端口按「门店 + SKU」取。
  - 现状：服务商品本就按门店建（`prd_goods.store_no`），时长是商品级（`prd_goods.duration_min`，美业发布时也要求定义的时长等于商品时长）。同一商品下各 SKU 能不一样的只剩缓冲、步骤和服务要求。
  - 可选：① 执行定义定为商品级，门店、SKU 只落在服务要求行上；② 按 SKU 区分，就不能用 `industry_ext` 存。
- **(a) 放在 `ServiceSpec` 里**，不另起位置。但**商家保存这条路**（`BizGoodsController → saveGoods`）要忽略这几个新字段，只有执行定义的发布能改。否则商家用旧编辑页存的草稿一发布，会把缓冲、步骤回滚成旧值（编辑页带回的是整份，「不传 = 不改」挡不住）。
- **(b) 不绕过 `GoodsRevisionService`**，在它里面加一个方法（如 `publishIndustryRevision`），让规则集中在一处。要守的：
  1. 两类未发布行都不动：`status=DRAFT` 的保存历史（至多一行），以及 `EDITING` / `SUBMITTED` 的商家草稿。
  2. **结转**（最要紧）：之后 `recordPublished` / `publishDraft` 把商家那份未发布的转成线上时，要把当前 `industry_ext` 的执行定义并进新线上行的 payload，并按新修订号复制一份服务要求。否则按新修订号取，拿到的是旧定义，或者取不到。
  3. 修订号照用 `nextRevisionNo`，`(goods_no, revision_no)` 唯一键挡并发；线上行不一定是号最大的，现有代码已经接受这一点。
  4. 发布让 `prd_goods.version` 加一，商家草稿才会按现有规则报 `GOODS_DRAFT_STALE`。锁行用 PR #59 加的 `mine(merchant, goods, true)`，加锁顺序与商家保存一致。
  5. `review_no` 留空（免审）；`entry_source` 用新常量（如 `INDUSTRY`），不写字面量。
- **(c)** 实体字段与 `prd_fulfillment_requirement` 的 Mapper 由本会话加：
  - 列在定稿 05 与 .61 的 `n_trade_v2` 上都已存在；
  - Mapper 继承 `ProductTableMapper`，走商品句柄；
  - 用 `backend/scripts/gen-product-test-schema.py` 把表加进测试库，重跑生成；
  - 行业取值用枚举或常量。05 里写的是 `RETAIL / FNB / BEAUTY`，计价表用 `FOOD`，FNB 还是 FOOD 要和 V2 确认；美业只用 `BEAUTY`。
- **(d)** 部署时商品句柄与交易句柄绑同一个数据源名、同库。两个事务管理器共用同一个 DataSource 实例时，Spring 会复用已绑定的连接。美业自己的表在行业库，和商品写入不可能同事务，所以 publish 要幂等：期望修订不对就拒，重放同一请求返回同一修订号。美业在商品返回成功后再记录，失败时能对账补齐。
- **另**：补一条测试，旧 payload（没有新字段）和新 payload 都能解析。

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

### 2.8 服务定义方案与修改计划（用户 2026-11-28：按「服务 = 商品 + SKU + 服务定义」制定修改计划）

**口径**（详见《商品域-多业态商品方案》，P0 时同步改）：
- **商品与 SKU**：服务是 `prd_goods`（`type=SERVICE`，`inv_mode=OFF`，履约方式为 `APPOINTMENT` 预约或 `STORE_VERIFY` 到店核销）+ 至少一个 `prd_sku`（按时长或技师级别分档；不记库存）。
- **服务定义**：独立的 `prd_service_definition` 表，存「做这一档要多久、前后缓冲、步骤、注意事项」。按商品写默认行（`sku_no` 为空），按 SKU 写覆盖行。
- **版本**：服务定义有**自己的版本号**，与商品修订脱钩，不需要结转，不扩展 `ServiceSpec`。
- **服务要求**：`prd_fulfillment_requirement` 的版本列改指服务定义版本。
- **加项与名额**：加项用下单选配；名额用限量（`prd_sales_quota`）；资源空闲由平台预约 `rsv_*` 决定。
- **引用**：预约项目行、服务任务记「SKU 号 + 服务定义版本号」（`definition_version_no` 只存版本号）。
- **时长同一事实一处**：有服务定义的商品，时长只由规格发布写，并镜像到 `prd_goods.duration_min` 供展示和现有读方；商家保存商品时忽略时长。没有服务定义的服务（零售到店核销）沿用商品自己的时长。

**新表 `prd_service_definition`（P1 已定稿，结构真源 `docs/technical/design/schema/retail-ddl/service-definition-tables.sql`）**：
- 列：
  - `id`、`tenant_no`、`entity_no`、`store_no`、`goods_no`；
  - `sku_no`（空 = 商品默认）；
  - `definition_version`（按商品递增）；
  - `duration_min`、`buffer_before_min`、`buffer_after_min`；
  - `steps`（JSON）、`notes`（JSON）；
  - `schema_version`、`snapshot_hash`；
  - `status`（`ACTIVE` / `INACTIVE`）；
  - `source_ref`：调用方的操作号，用于防重；
  - `published_by`、`published_at`；
  - 公共列。
- 唯一约束：
  - `(tenant_no, goods_no, definition_version, sku_no)`：同一版内每个 SKU 至多一行，默认行用空串代替 NULL，或用生成列；
  - `(tenant_no, goods_no, source_ref, sku_no)`：同一操作重放返回同一版本（一个版本是一组行，共用一个操作号，所以带上 `sku_no`）。
- 检查约束：时长大于 0，缓冲不小于 0。

**修改计划**：

| 步 | 内容 | 谁做 | 产出与验证 |
|---|---|---|---|
| **P0 文档与对齐** | 改《商品域-多业态商品方案》§3.4、§3.5、§3.6-4；本节定稿；把方案发给商品会话「零售」确认（加表方式、端口、时长镜像、发布是否让 `prd_goods.version` 加一） | 本会话 | 商品会话同意后才进 P1 |
| **P1 定稿 DDL** | 05 加 `prd_service_definition`，改 `prd_fulfillment_requirement.revision_no` 的注释。生成器没有「新增交易表」的机制：加一个，或按交接文档在 ai-shop-slim 的生成源里加（与商品会话定）。重新生成 05 与派生文档（03-表名对照、01-清单、库、字段）；`gen-product-test-schema.py` 重跑；美业测试副本 `db/n_trade/V1__trade_design.sql` 同步 | 本会话 | `check-generated-docs` 全绿；产品测试库多一张表 |
| ✅ **P2 .61 建表**（2026-11-28 已执行，记在商品交接 §5） | `n_trade_v2` 上建 `prd_service_definition`、改注释。新表不涉及数据备份，改注释前导出结构；root 执行；前后只读核对；记入商品交接 §5 | 本会话，**执行前请用户批准** | 逐列核对与定稿一致 |
| **P3 商品侧代码** | `shop-base` 加 `ServiceDefinitionPort`（发布 / 当前 / 按版本 / 按时刻列表）；`shop-core` 加实体与 Mapper（`PrdServiceDefinition`，`PrdFulfillmentRequirement` 继承 `ProductTableMapper`）和端口实现，详见下方 P3 说明；同步改造现有时长读写，详见下方 P3 说明 | 本会话，提交前发 diff 给商品会话 | 商品测试：发布、版本冲突、按 SKU 覆盖、重放、商家保存忽略时长、镜像时长 |
| **P4 独立部署** | `ServiceDefinitionInternalApi` + 宿主内部控制器 + `beauty-server` 客户端，照 `ServiceProductFactsInternalApi` 的写法 | 本会话 | 美业 server 装配测试；宿主内部接口测试 |
| **P5 美业侧** | 详见下方 P5 说明 | 本会话 | 美业全套（共用库、独立库两种模式）不新增失败；约束测试照旧 |
| ~~**P6 数据迁移**~~ | **取消**（用户 2026-11-28：`ai_shop_ume` 已作废，预定义数据与测试数据后面重新生成）；脚本留作工具，见下方 P6 记录 | — | — |
| **P7 收尾** | 重新生成旧库调用清单；记录本 TDD 与商品交接；把 diff 发商品会话；征得同意后推送 v2 | 本会话 | 推送前 fetch 并合并，跑生成文档检查 |

**P3 商品侧代码说明**：
- 端口实现的发布流程：
  1. 在商品事务里，用 `mine(merchant, goods, true)` 锁住商品行，加锁顺序与商家保存一致；
  2. 核对期望版本：等于当前版本，没有规格时为 0；
  3. 写默认行与覆盖行，版本取下一个；
  4. 按同一版本写服务要求；
  5. 把默认时长镜像到 `prd_goods.duration_min`；
  6. 按 `source_ref` 防重，重放返回原版本。
- 现有时长读写的改造：
  - `ServiceProductFactsPortImpl`、`ServiceCatalogFactsPortImpl` 按 SKU 取时长：有规格读规格（含覆盖），没有读商品；
  - `MerchantGoodsServiceImpl` 保存时，对已有服务定义的商品忽略 `ServiceSpec.durationMin`；
  - 商家编辑页上，美业服务的时长改为只读；如果改了 `b-app`，跑 `vue-tsc`。

**P5 美业侧说明**：
- 6 个类改用端口：服务定义发布、目录查询、预约匹配、开单准备、提交查询、事实来源；服务定义三张表的 Mapper 退役。
- 发布入口、幂等、操作日志留在美业，以美业的操作号作为 `source_ref`。
- 开单核对服务定义时，改成按版本号做乐观核对。
- 测试：
  - 美业测试库按定稿 DDL 建 `prd_service_definition` 与 `prd_fulfillment_requirement`；
  - 加一个用 JDBC 读写这两张表的测试版端口；
  - 原来往 `bty_service_definition*` 写种子数据的地方，改用种子数据辅助方法写新表。

**P6 数据迁移说明**：
- 旧库 `bty_service_definition*`、`bty_service_requirement` 搬到 `prd_service_definition` 与 `prd_fulfillment_requirement`：定义按 SKU 找到所属商品，同一商品下各 SKU 的定义合成默认行加覆盖行，版本按发布时间重新编号。
- 同时把预约项目行、服务任务、工单冻结范围里的旧版本号（`BTDV…`）换成新版本号。
- 并入商品迁移脚本 `migrate-product-to-n-trade.py` 或单独成脚本（与商品会话定）。

**顺序与依赖**：
- P0 → P1 → P3 → P5 是主线；
- P2 可在 P1 之后任何时候做，生产绑定 `n_trade_v2` 之前必须完成；
- P4 在 P3 之后；
- P6 已取消（数据重新生成）。

**商品会话的核对（2026-11-28，同意简化后的方向）**：
- **(a) 加表方式**：在 `gen-retail-ddl.py` 加 `TRADE_ADDED`，写法照 `INDUSTRY_ADDED`；基础 DDL 固定在 slim e4a007c00，不改。
  - 生成后在 .61 `n_trade_v2` 上由 root 建表：先问用户，执行后在《商品领域迁移-交接》§5 追加记录。
  - 把表加进 `gen-product-test-schema.py`，重跑生成。
  - 发消息给 V2 会话，补 slim 的生成源 `db-target-fields.json`，否则下次用 slim 重新生成会把这张表抹掉。
- **(b) 不让 `prd_goods.version` 加一**，但要满足两条：
  - 商家保存有规格的商品时，`duration_min` **显式不写**：实体字段置空，或用更新条件排除。商家那条路是读出整行再 `updateById`，只「忽略请求值」会把旧时长写回去。
  - 镜像用单独的定向更新（只设 `duration_min`），在同一把行锁 `mine(…, true)` 之后执行，不碰 `version`。
- **(c) 默认行**：`sku_no NOT NULL DEFAULT ''`，唯一键 `(goods_no, sku_no, 版本)`。与商品侧现有约定一致（`prd_spec_usage.value_no=''` 等），不用生成列。
- **(d) 表名建议换掉**：`prd_spec_*` 在商品域专指 SKU 的规格维度和取值，建议改叫 `prd_service_definition`（服务定义），端口叫 `ServiceDefinitionPort`。（待用户定）
- **服务要求的版本列**：`prd_fulfillment_requirement.revision_no` 按商品类型表示两种意思，读方容易解读错。建议加一列 `definition_version`，服务的要求挂这一列，非服务留空。（改表，待用户定）
- **状态取值**：走枚举或常量。
- **diff**：商品侧代码提交前发它看。

**用户定（2026-11-28）**：
- 表名用 `prd_service_definition`（服务定义），端口 `ServiceDefinitionPort`。
- `prd_fulfillment_requirement` **加一列 `definition_version`**：服务的要求挂这一列，非服务留空；`revision_no` 仍只表示商品修订号。
- 商品会话的 (a)(b)(c) 照它的意见做：生成器加 `TRADE_ADDED`；不让 `prd_goods.version` 加一，商家保存显式不写时长，镜像用定向更新；默认行 `sku_no=''`。

**P1 完成（2026-11-28）**：
- `gen-retail-ddl.py` 加 `TRADE_ADDED`，新表原样接在 `prd_fulfillment_requirement` 之后；后者加 `definition_version int NULL` 与索引 `(goods_no, definition_version)`。
- 基础 DDL 仍取 slim e4a007c00：`gen-retail-ddl.py <e4a007c00 的 01/05/06> --check` → n_trade 68 张、n_industry 38 张。
- `gen-product-test-schema.py` 重跑（28 张表），H2 MySQL 模式执行通过；美业测试副本 `db/n_trade/V1__trade_design.sql` 已同步。
- 本机 MySQL 9.7 建 68 张表通过；默认行加覆盖行可写，同版本重复、时长 0、负缓冲分别被唯一键与检查约束挡住。
- slim 的 `db-target-fields.json` 等派生件由 V2 会话补（已发 DDL）。

**P3 完成（2026-11-28，商品会话已核对）**：
- `shop-base` 加 `ServiceDefinitionPort`（发布 / 当前 / 按版本 / 按时刻列表）；`shop-core` 加 `PrdServiceDefinition`、`PrdFulfillmentRequirement` 与两个 Mapper，「这个 SKU 用哪一行」只在 `ServiceDefinitionMapper.applicable` 判。
- 「主体 + 门店 + SKU → 服务商品」抽成 `ServiceGoods`，服务事实与服务定义共用；服务事实的时长有定义取定义（按 SKU），否则取商品。
- 时长同一事实一处：`PrdGoods.durationMin` 标 `updateStrategy = NEVER`，整行 `updateById` 永远不写（商品会话指出上下架、评价回写评分、规格合并三处整行写回会盖掉镜像）；写入方只有两个定向更新：商家保存（商品还没有服务定义时）与定义发布的镜像。
- 测试 `ServiceDefinitionPortTest`（7 条，H2 跑定稿结构）：发布并镜像且商品版本不变、整行更新不写回旧时长、版本冲突、覆盖行、重放、无定义回落、商家保存不写已定义的时长。
- 商家编辑页把美业服务时长改为只读：要给 `GoodsVO` 加标记（契约变更），按商品会话建议单独做，不在 P3。

**P4 完成（2026-11-28）**：
- `ServiceDefinitionInternalApi`（`/internal/core/products/service-definitions/{publish,current,by-version,list}`）与宿主 `InternalServiceDefinitionController`；「没有定义」用 `Found.definition` 为空表达（空响应在内部接口约定里是 503）。
- 美业 `RemoteServiceDefinitionPort`，由 `BeautyProductClientConfiguration` 在独立部署时装配；400/403/404/409 还原业务错误的写法收到 `PlatformCalls` 一处，两个商品类远程端口一并改用。
- 测试：`BeautyProductClientTest` 加服务定义往返（当前、无此版本为空、列表、发布冲突 409）；美业 server 装配与部署约定测试照过。

**P5 美业改用端口（2026-11-28）**：
- **字段不动、换值（用户定）**：美业接口里 `definitionNo` = 商品号，`definitionVersionNo` = 商品内的定义版本号（十进制字符串，如 `"3"`），`revision` = 同一个版本号（列表里 `revision` 是现在的版本、`effectiveRevision` 是查询时刻那一版）。换算只在 `ServiceDefinitions`（美业读写服务定义的唯一入口）一处；定义摘要（SKU + 时长 + 要求 + 注意事项）也只在这里算，冻结与核对用同一公式。
- **按 SKU 发布**：美业按 SKU 发布，端口的 `skuOverride` 传空，由商品域按 SKU 数定 —— 商品只有一个 SKU 或还没有定义时写默认行，否则写这个 SKU 的覆盖行。端口的要求读出时带 `requirementNo`（同一版本内稳定，预约分派按它对应）。
- **六处改用端口**：发布（幂等、操作日志留在美业，幂等键摘要作 `source_ref`；不再要求请求时长等于商品时长，时长归定义）、目录查询（在美业读事务之外取定义）、预约匹配（按版本取要求）、开单准备（按版本冻结；`DEFINITION` 头改记 SKU 现在的版本）、开单核对与新建核对（不再锁定义头，按版本号乐观核对）、提交查询（按版本取那一版核对摘要与时长）。
- **退役**：`BtyServiceDefinition` / `BtyServiceDefinitionVersion` / `BtyServiceRequirement` 三个实体与 Mapper、数据范围登记。旧表仍在美业私有 Flyway 里（随第 15 项退役），数据由 P6 搬。
- **发布与美业事务**：定义写在商品域自己的事务里；美业事务（幂等、操作日志）回滚时定义已提交，同一幂等键重试按 `source_ref` 回到同一版，不会多发一版。
- **测试**：美业测试库按定稿 DDL 建 `prd_service_definition` / `prd_fulfillment_requirement`；测试版端口 `TestServiceDefinitionPort`（JDBC，单 SKU 简化）挂在共用测试 runner 上；种子改用 `definition(...)`。原「恢复等在定义头行锁上」的用例改为直接在跨过开始时刻后恢复（锁已不存在，结论不变）；只测旧定义头的两条持久层用例删除。宿主的原生模式用例（需 `beauty.*.test.native`）同步改了种子与查询，未运行。

**P6 数据迁移脚本（2026-11-28，已取消：不在正式库执行）**：
- **取消原因（用户 2026-11-28）**：旧库 `ai_shop_ume` 已作废，要保住的只有 11-24/25 的测试数据；预定义数据与测试数据后面重新生成，服务定义经美业发布入口重新发布即可。`n_trade_v2` 里引用旧版本号的 12 条测试预约项目随测试数据一起重建。
- 以下是取消前写好的脚本与演练记录，留作以后真有旧数据要搬时的工具。
- 单独成脚本 `scripts/migrate-service-definitions.py`（不并入商品迁移：那份要求目标表全空、已在 .61 执行过）。`--plan` 只读打印；执行是一个事务，检查表 `svc_migrate_guard` 任何一项不为 0 整批回滚。
- 规则：同一商品下各 SKU 的旧版本按发布时间排成一条序列，每个旧版本对应新的一版；第一个发布的 SKU 是默认行，其余 SKU 内容不同的写覆盖行。要求编号沿用旧的（预约分派、任务冻结里记的就是它）。`source_ref='MIGRATED:<旧版本号>'`，发布人与时间取旧版本（`--source-tz` 默认 UTC）。默认时长定向镜像到 `prd_goods`。
- 引用改写：交易库与 `--rewrite` 给出的库里 `ord_appointment*`、`bty_*` 表（旧定义三张与迁移历史除外）：`definition_version_no` 列等值改，其余文本 / JSON 列把带引号的旧版本号换成新版本号；改完仍有旧版本号就回滚。摘要公式未变，冻结在任务里的定义摘要无需改。
- 本机演练（单 SKU 一版 + 双 SKU 三次发布）：版本对照、覆盖行、要求编号、时长镜像、引用改写都对；重跑被检查挡下。
- 正式库现状（2026-11-28 只读查）：`ai_shop_ume` 旧定义 1 版（`BTDV26112443BH98F7B2`，门店 `ST2611247EB2YDAA8E`、SKU `SK261124HBK83ACNDX`→商品 `G261124T7GRQWEVF9`）、要求 1 条；引用它的有 `ai_shop_ume.bty_service_task` 6 行、`n_trade_v2.ord_appointment_item` 12 行（均为 11-24/25 的测试数据）。
- 上线顺序（取消 P6 后）：P2 建表 → 部署含 P5 的包 → 重新生成预定义数据与测试数据（服务定义经美业发布入口写）。

**P8 商家编辑页：时长归服务定义时只读（2026-11-28，档位 1：响应字段 + i18n 词条）**：
- 依据：本节「时长同一事实一处」与 P3 的「商家编辑页上，美业服务的时长改为只读」；商品会话建议从 P3 拆出单独做。
- 契约：B 端商品详情 `GoodsVO` 末尾加 `durationByDefinition`（Boolean，只在 B 端详情填：服务商品已发布过服务定义为 `true`，否则不下发）；`packages/shared` 的 `Goods` 同步声明。b-app 加词条 `goods.durationByDefinition`（简中、英、阿三语）。

| AC | 落点 | 测试 |
|---|---|---|
| 已发布服务定义的服务，B 端详情带 `durationByDefinition=true`；没有定义或非服务不下发 | `MerchantGoodsServiceImpl.durationByDefinition` → `merchantView` / `toVOWithoutStoreContext` 的 `.withDurationByDefinition(...)` | `ServiceDefinitionPortTest#merchantSaveLeavesDefinedDurationAlone`；`GoodsVO` 的 9 个 `with*` 复制方法都透传标记（商品会话指出，与运费模板同样处理）：`#everyGoodsViewCopyKeepsTheDurationFlag`（10/10 通过） |
| 编辑页据此把时长显示为只读（点了不弹选择），下方提示「时长由服务设置发布」 | `b-app/src/pages/goods-edit/index.vue`：`durationByDefinition`、`pickDuration` 提前返回、`sh-hint` | `vue-tsc`：本改动零错误（工作树缺 `pinyin-pro` 依赖的那 1 条与本改动无关） |
| 保存不再带时长；恢复草稿时不用草稿里的旧时长 | 同上：保存体 `durationMin` 为空；草稿回填保留线上值 | 同上；后端本就不写（P3 `durationOwnedByDefinition`） |

- 闸门：`check-app-contract.py --check` 通过；共享守卫（借主工作区 `node_modules` 跑）的 9 条失败与推送前的 `v2`（`e2bf8424c`）完全相同，均与本改动无关。

**原待定（已定，留作记录）**：
- 新表的加法：生成器加机制，还是改 ai-shop-slim 生成源；
- 发布服务定义是否让 `prd_goods.version` 加一（会让商家手上未发布的商品草稿提示「有人中途改过」）；
- 默认行的 `sku_no` 用空串还是生成列；
- 表名 `prd_service_definition` 是否合适。

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
