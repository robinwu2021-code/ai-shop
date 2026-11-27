# TDD-餐饮域迁移新库

状态：已确认（P0、P1 实施中）
关联：[ADR-068 门店交易库按耦合簇换表与行业插件公用部分并回](ADR/ADR-068-门店交易库按耦合簇换表与行业插件公用部分并回.md) ·
[ADR-066 业务模块按配置选数据源](ADR/ADR-066-业务模块按配置选数据源.md) ·
[TDD-零售域对齐门店交易库](TDD-零售域对齐门店交易库.md)（交易核心簇乙，餐饮 P2 随它）·
[TDD-平台基础库换表](TDD-平台基础库换表.md)（「只要在新库上能跑」的先例）·
[餐饮旧表去向核对](../../deploy/ume/food-kds-cutover/legacy-table-inventory.md) ·
[其余 25 张旧表的承接提案](../../deploy/ume/food-kds-cutover/legacy-table-dispositions.md) ·
行业库设计：[库](design/数据库设计/库/06-行业库.md) · [字段](design/数据库设计/字段/06-行业库.md) · [DDL](design/schema/ddl/06-n_industry.sql)
需求：[餐饮系统-完整需求文档](../requirements/餐饮系统-完整需求文档.md)（本篇不改功能，验收以现有行为不变为准）
创建：2026-11-27 · 最后更新：2026-11-27

档位：2 · 依据：ADR-068（已采纳，本篇不另开 ADR）· 产出：本文件

## 一句话

餐饮插件现在读写主库 `ai_shop_ume` 里的 139 张 `fnb_*` 表。按 ADR-068，这些表拆到新库：
交易相关的 65 张进门店交易库 `n_trade`，代码并回交易模块（P2，随零售簇乙一起切）；
行业相关的 28 张进行业库 `n_industry`，代码留在插件（P1，本篇先做）。出品整组（含制作任务）归行业库，交易库只保留订单（ADR-068 修订一）；
库存、支付、平台、审计等 32 张由各自的归属模块承接（P3）；14 张直接退役（P4）。
本篇先做 **P0（底座：句柄、校验、跨库语句与事务）** 和 **P1（行业库：按设计表结构对齐，含 9 组合并）**。

## §0 对账一 · 需求 → 设计

本篇是重构，不加功能。验收标准是「行为不变 + 归属落地」：

| AC | 验收标准（Given / When / Then） | 落点 |
|---|---|---|
| AC1 | Given `industry` 绑到按 `db/n_industry` 新建的库、`trade` 仍连主库 When 跑餐饮的跨域流程测试 Then 全部通过（补偿、补做在真实分库下生效） | P0-4 事务拆分；P1 新库测试（用户 2026-11-27 定：不做同库不同池，改完直接在新库测） |
| AC2 | Given 行业句柄下的任一 SQL When 静态扫描 Then 只碰行业库的表；交易句柄、主库句柄下的 SQL 不碰行业表 | P0-3 跨库语句拆分；`FoodIndustryTableAccessTest` |
| AC3 | Given 启动 When 校验表结构 Then 每个句柄只在自己连的库上校验自己那部分表；行业部分按 `n_industry` 的结构校验 | P0-1 / P1-4 `FoodSchemaValidator` |
| AC4 | Given 本机测试库按 `db/n_industry/V1`、`V2` 建 When 执行 Then 行业库全部表建成，餐饮 27 张旧表都有承接 | P1-1 建表脚本 |
| AC5 | Given `industry` 绑定到按 `n_industry` 建的库 When 跑餐饮的桌台、出品、厨显、权益、宴会、排队、寄存、交接、门店策略、配置修订用例 Then 行为与切换前一致 | P1-2、P1-3 Mapper 与服务改写；行业库场景测试 |
| AC6 | Given 9 组合并 When 写入与查回 Then 合并后的表能还原原来每张表承载的业务事实（状态、防重、恢复所需字段） | P1-3 合并映射（§2.5） |
| AC7 | Given .61 When 执行 `deploy/ume/food-industry-cutover/` 的脚本 Then `n_industry` 补齐缺表与缺列，可重复执行 | P1-5 部署脚本 |

**孤立项**：无没落点的 AC。P1 末尾的「生产绑定 `industry` → `n_industry`」不挂 AC，见 §1.4：它要等美业也对齐。

## §1 现状与影响面

### 1.1 规模

| 项 | 数 | 来源 |
|---|---:|---|
| 餐饮插件 `src/main` 文件 | food-api 381、food-core 287、food-web 159、food-server 66、food-commerce 48、food-channel 32 | `git ls-files` |
| 旧表 | 139（.61 全部为空，11-27 一致性快照） | [旧表核对](../../deploy/ume/food-kds-cutover/legacy-table-inventory.md) |
| 去 `n_industry` | 28 张旧表（含 ADR-068 修订一收回的制作任务）→ 设计中的 18 张餐饮表与共用表，加制作任务表 | 同上 |
| 其中同构或只改名 | 18 | §2.4 |
| 其中要合并 | 9 组（用户 2026-11-27 定：P1 一次合并到位） | §2.5 |
| 碰行业表的 Mapper | 注解 Mapper 12 个、JDBC Mapper 5 个（共 87 条语句） | §1.2 |
| 同时用行业与非行业 Mapper 的服务类 | 25 | §1.3 |

### 1.2 跨库的语句（2026-11-27 扫描）

句柄标记：`IND` 行业、`TRD` 交易、`主` 主库。

| 位置 | 句柄 | 碰到的库 | 语句 |
|---|---|---|---|
| `FoodMappers.TableMapper#liveOccupancies` | IND | 交易 | 查占台 `fnb_table_occupancy` |
| `FoodMappers.TableAreaMapper#liveOccupancies` | IND | 行业＋交易 | 桌台联占台 |
| `FoodMappers.KitchenTicketMapper#cancelledMainQty` | IND | 交易 | 查退菜 `fnb_return`、`fnb_return_item` |
| `FoodMappers.ServiceEventMapper#pending`、`#unconsumed` | IND | 行业＋交易＋库存 | 服务事件联制作任务、批次、出品耗料 |
| `FoodMappers.ConfigRevisionMapper`（显式装配） | IND | 行业＋交易 | 配置修订联菜单、菜单门店 |
| `FoodMappers.CheckMapper` | TRD | 交易＋行业 | 账单联桌台 |
| `FoodMappers.KitchenTaskMapper#activeBatchNos` | TRD | 交易＋行业 | 制作任务联出品单 |
| `FoodMappers.ProductionConsumptionMapper` | 主 | 库存＋交易＋行业 | 出品耗料按商品筛选时联服务事件 |
| `FoodBusinessOperationsMapper`（交易数据源） | TRD | 交易＋行业＋其他 | 87 条里有 18 条只碰行业表（交接、责任、定时派单、门店策略、桌台、堂食权益），1 条联桌台与占台 |
| `ServiceEntitlementMapper`（**未点名的 `DataSource`，即主库**） | 主 | 行业＋交易 | 11 条行业，3 条查购物车、批次与订单行、到店单 |
| `ServiceFormMapper`（交易数据源） | TRD | 交易＋行业＋会员 | 1 条查配置修订 |

### 1.3 混用两个句柄的服务类

25 个：`BusinessCloseServiceImpl`、`KitchenTaskServiceImpl`、`ProductionConsumptionServiceImpl`、`CheckCartServiceImpl`、`CheckServiceImpl`、
`TableServiceImpl`、`ReservationServiceImpl`、`DiningSessionServiceImpl`、`KitchenReturnServiceImpl`、`DiningOrderServiceImpl`、`MenuServiceImpl`、
`MenuWithdrawalSourceServiceImpl`、`ConfigRevisionServiceImpl`、`QueueServiceImpl`、`StationServiceImpl`、`StoreOperationServiceImpl`、
`SalesRestrictionServiceImpl`、`SalesQuotaClaimServiceImpl`、`StoreCalendarSourceServiceImpl`、`ServiceEntitlementServiceImpl`、
`FoodReturnStockServiceImpl`、`DiningKitchenReportFactsPortAdapter`、`DiningReportFactsPortAdapter`、`DiningChannelFulfillmentSignalPortImpl`、`StationPublicationSource`。
逐个归类见 §2.3。

### 1.4 一个前置：`industry` 句柄是餐饮与美业共用的

美业插件（`BeautyPersistenceConfiguration`、`BeautyMappers` 等 5 个文件）也把行业表收在 `industry` 句柄上。
**生产上把 `industry` 绑到 `n_industry`，美业的表也一起过去。**所以 P1 的生产绑定要等美业的行业部分也对齐 `n_industry`。
P1 本身照做：餐饮的行业 Mapper 对齐新结构，并在「`industry` 绑到按 `n_industry` 建的测试库」时验收（美业在这些测试里不装配）。

### 1.5 明确不受影响的

对外端点、JSON、权限码、i18n 词条：都不变。交易部分的 66 张表（P2）、库存 15 张及其他域（P3）、主库 Flyway 本身：本篇不改。

## §2 方案

### 2.1 契约变更

| 项 | 变更 |
|---|---|
| 端点 / JSON | 无 |
| 库表 | 行业库：按 [06-n_industry.sql](design/schema/ddl/06-n_industry.sql) 建；补充脚本 `V2` 只补代码真实需要、设计稿漏掉的列与唯一键（ADR-068 第 4 条：防重列与唯一约束保留），每处写依据。旧库：不改（「只要在新库上能跑」，同平台基础库换表） |
| 配置项 | 无新增。`shop.modules.industry.datasource` 已有；P1 后餐饮要求它指向按 `n_industry` 建的库 |
| 权限码 / i18n | 无 |

### 2.2 P0 底座（旧库上照样能跑，逐项提交）

| # | 动作 | 路径 | 说明 |
|---|---|---|---|
| P0-1 | 修改 | `food-core/.../config/FoodSchemaValidator.java` | 现在只在主库上校验全套旧结构。拆成：主库 Flyway 历史与非行业表照旧；行业表在 `industry` 数据源上按列校验；交易准入三张表已在交易数据源上校验（保持） |
| P0-2 | 修改 | `FoodServiceEntitlementAutoConfiguration`、`ServiceEntitlementMapper` | 改为点名 `industry` 数据源与 `industryTxManager`；碰交易表的 3 条移到交易一侧（`ServiceFormMapper` 或新建交易查询），权益服务经它取 |
| P0-3 | 修改 / 新增 | `FoodMappers`、`FoodBusinessOperationsMapper`、新 `FoodIndustryOperationsMapper` | §1.2 的跨库语句拆成两次查询；`FoodBusinessOperationsMapper` 里只碰行业表的 18 条搬到新的行业 JDBC Mapper（`industry` 数据源） |
| P0-4 | 修改 | §1.3 的服务类 | 按 §2.3 的三类处理 |
| P0-5 | 新增 | `food-core/src/test/.../FoodIndustryTableAccessTest.java` | 守卫：行业句柄下的语句只碰行业表，其他句柄不碰行业表（AC2） |
| ~~P0-6~~ | 取消 | — | 原拟「同库不同池」测试。用户 2026-11-27 定：迁移本来就是要上新库，改完直接在新库测试；分库下的事务行为由 P1 新库测试覆盖（AC1） |

### 2.3 事务：按耦合簇处理，不用分布式事务

**原则（用户 2026-11-27 定）**：先把耦合紧的表放在同一个库，让跨库的场景降到最少；剩下的跨库点用「本地事务 + 业务键 + 提交后补写」，
不用分布式事务（XA / Seata：ADR-066 已否跨库同事务；栈里没有 JTA；悬而未决的事务恢复靠人工；锁跨库长持）。

| 簇 | 现在 | 处理 |
|---|---|---|
| **出品**：制作任务、出品单、服务事件（并入厨显事件）、道次、定时出品 | 每次厨房动作同一事务改制作任务、记事件、改出品单；设计稿曾把制作任务放进交易库 | **整组归行业库**（ADR-068 修订一），厨房动作成为行业库内的本地事务。交易库只保留订单 |
| **桌台**：桌台、区域 | 开台、并台、换台、订位先锁桌台行作交易侧占台的锁；结束占台给桌台加版本 | 表留行业库、改为纯配置：并发锁改到交易库的占台或资源行（桌台已带 `resource_no`），翻台状态由占台推出，不再在交易事务里写桌台 |
| 门店策略、配置修订、工位 | 交易侧只读；工位锁门店策略（同在行业库） | 不动 |
| 排队、寄存 | 只写行业表 | 不动；排队转入座在交易提交后更新排队号 |
| 交接、人头权益 | — | 已按业务键处理（§6） |
| 关账 | 读出品、交接、策略 | 经接口只读，按摘要与版本比对，不跨库加锁 |

出品整组归行业库后剩下的三个跨库点：

| 场景 | 做法 | 补做 |
|---|---|---|
| 接单后建出品单与制作任务 | 交易提交后建（`IndustryFollowUp`），批次号为业务键可重复 | 任务按「已接单、无出品单」扫描补建 |
| 退菜取消制作 | 先在行业库按退菜行取消（可重复），再在交易库记退菜的厨房处理状态 | 处理状态未完成的由任务补做 |
| 交易侧看制作进度 | 经出品查询接口只读 | — |

另：出品服务每个动作发的 `DiningServiceEvent` 现在经发件箱与业务同事务；出品在行业事务里后不再同事务，改为提交后直接调用（零售 TDD §2.10 同一规则），消费者若只为取厨房事件流，改读行业库的厨显事件。

逐个服务的处理随实现写进 §6「事务拆分记录」。

### 2.4 P1 行业库：同构与改名的 18 张

| 旧表 | 新表 | 差异 |
|---|---|---|
| `fnb_table`、`fnb_table_area`、`fnb_course`、`fnb_banquet_plan`、`fnb_banquet_attendance_confirmation`、`fnb_service_entitlement_event` | 同名 | 列相同 |
| `fnb_service_entitlement` | 同名 | 新表多 5 列（来自堂食权益合并，见 §2.5） |
| `fnb_station` | 同名 | 多 1 列 |
| `fnb_kitchen_ticket` | 同名 | 新增 `store_no`、`dispatch_at`、`dispatch_attempts`、`last_error`（来自定时派单合并）；`dispatch_key` 见 §2.5 |
| `fnb_kitchen_task` | 同名（ADR-068 修订一，设计稿原拟并入 `n_trade.ord_execution`） | 设计 DDL 里没有，`V2` 按现结构建；状态值已 ≤10 字符 |
| `fnb_queue_ticket` | 同名 | 设计稿去掉了 `request_key`、`request_hash`、`seat_request_hash`。代码用它们做取号与入座的防重：按 ADR-068 第 4 条在 `V2` 补回，并加唯一键 |
| `fnb_bottle_custody` → `fnb_custody`、`fnb_bottle_movement` → `fnb_custody_movement` | 改名 | 新表列是旧表超集 |
| 厨显 6 张（`fnb_kds_cursor`、`fnb_kds_event`、`fnb_kds_subscription`、`fnb_kds_voice_command`、`fnb_station`、`fnb_station_runtime`） | 已按 .61 现场结构建好 | 沿用 `01-industry-kds.sql` 的现场落点（游标、语音命令、工位运行保留独立表），写进 `V2` |

### 2.5 P1 行业库：9 组合并（用户 2026-11-27 定一次到位）

| 旧表 | 并入 | 映射要点 |
|---|---|---|
| `fnb_config_revision` | `ind_config_revision` | `config_no` → `policy_no`；`revision` → `revision_index`；`revision_no` → `policy_version_no`；`content_hash` → `snapshot_hash`；`content_schema` → `schema_version`；内容进 `definition_snapshot` |
| `fnb_banquet_plan_revision` | `ind_config_revision`（`config_kind = BANQUET_PLAN`） | `plan_no` → `policy_no`；计划人数、预算、币种、表单号、计划快照进 `definition_snapshot` |
| `fnb_store_policy` | `ind_store_policy`（`industry_code = FNB`） | 时区、切日、付款方式、能力码直接成列；`effective_from`、`settings` 进当前修订（`ind_config_revision`，`config_kind = STORE_POLICY`） |
| `fnb_dining_entitlement` | `fnb_service_entitlement` | `check_no` → `form_no`；`starts_at`/`expires_at` → `valid_from`/`valid_until`；类型、人群、人数、规则快照、状态成列；`core_order_ref` 经到店单查 |
| `fnb_scheduled_dispatch` | `fnb_kitchen_ticket` | 定时出品作为出品单的 `SCHEDULED` 状态加 `dispatch_at`；`attempts` → `dispatch_attempts`；`next_attempt_at` 与 `dispatch_key` 的用途实现时逐条核对，代码在用就在 `V2` 补回 |
| `fnb_service_event` | `fnb_kds_event` | `action` → `event_type`；`task_no`、`qty`、`ready_loss_qty`、`reason`、`inventory_doc_no`、`custody_snapshot` 成列；`operation_key`、`source_request_hash` 是动作防重，按 ADR-068 第 4 条在 `V2` 补回并建唯一键 |
| `fnb_service_handover`、`fnb_service_handover_item`、`fnb_service_responsibility` | `ind_assignment` | 责任：`assignment_kind = RESPONSIBLE`，一人一条，`ended_at` 空表示在负责；交接：新条目 `PENDING`，接收后旧条目 `ENDED`、新条目 `ACTIVE`（`previous_assignment_no` 指向旧条），拒绝为 `REJECTED`；交接明细一行一条指派（`target_type`、`target_no`）。交接单号、来源摘要等防重与查回所需字段实现时核对，在用就在 `V2` 补回 |
| `fnb_custody_entry` | 退役（设计已裁定，无代码引用） | — |

合并后的状态值一律走枚举或常量（≤10 字符），见[零售域-状态短值换值表](零售域-状态短值换值表.md)的规则。

### 2.6 P1 实施步骤

| # | 动作 | 路径 | 说明 |
|---|---|---|---|
| P1-1 | 新增 | `food-core/src/test/resources/db/n_industry/V1__industry_design.sql`、`V2__industry_supplement.sql` | `V1` 为 06-n_industry.sql 原样；`V2` 是 §2.4、§2.5 的补充，每条注明依据 |
| P1-2 | 修改 | 18 张同构 / 改名表的实体与 Mapper | 改表名、补列映射 |
| P1-3 | 修改 / 新增 | 9 组合并涉及的实体、Mapper、服务 | 按 §2.5 |
| P1-4 | 修改 | `FoodSchemaValidator` | 行业部分改按 `n_industry` 结构在 `industry` 数据源上校验；主库不再要求行业旧表 |
| P1-5 | 新增 | `deploy/ume/food-industry-cutover/` | .61 的 `n_industry`：建缺表（`fnb_queue_ticket`、`fnb_custody`、`fnb_custody_movement`、`ind_assignment`）、补 `V2` 的列与唯一键；可重复执行；现场旧表为空，不导数 |
| P1-6 | 修改 | 餐饮测试夹具 | 用到行业表的测试改为在 `industry` 数据源上按 `db/n_industry` 建表。新库测试用本机 MySQL（`FOOD_TEST_DISPOSABLE`）：每次新建一次性的行业库，按 `V1`、`V2` 建表，`industry` 句柄绑到它，`trade` 仍连主库（P2 前）。这就是真实的两库，事务分开 |

P1 之后，餐饮只能跑在「`industry` 绑定到按 `n_industry` 建的库」上（同平台基础库换表的前提）。

### 2.7 订单域与业务域分开、跨域补偿（ADR-068 修订二）

**归属变化**：占台 `fnb_table_occupancy`、换台记录 `fnb_table_operation`、订位 `fnb_reservation` / `fnb_reservation_table`、时段与容量 `fnb_service_slot` / `fnb_capacity_claim` / `fnb_service_calendar` 改归行业库。
P1 的行业库表数随之增加（这几张在设计 DDL 里没有，`V2` 按现结构建，占台加状态见下）。

**补偿的做法**：
- 业务键：`CommandBusinessKey` 由请求键派生账单号、占台号等，重放得到同一个号。
- 状态在业务记录上：开台时占台先写成「已占、账单未确认」；P0 阶段旧表没有状态列，用「占台引用的账单在订单域是否存在」判断；P1 在 `V2` 给占台加状态列（预占 / 生效 / 已结束、结束原因）。
- 补做任务：开台补偿（超时仍无账单的占台结束）、清台补做（账单已结束而占台仍在的结束）、并单补做（占台仍挂旧账单的改挂）。

**实施顺序**：① Mapper 改挂行业句柄、拆跨库 SQL → ② 开台改为业务先、订单后、失败补偿 → ③ 结账清台、释放、并单改为订单先、业务补做 → ④ 补做任务与测试。

## §3 选型

| 方案 | 结论 |
|---|---|
| 9 组合并先原样搬进行业库，之后再合并 | ❌ 用户 2026-11-27 定一次到位：现场为空表，合并没有导数成本，分两次反而改两遍代码 |
| 行业库也按「同库不同池 → 逐表换」 | ❌ 行业库内部也有同事务写（出品单、厨显事件、工位），整组一次换 |
| P0 先拆事务与语句，P1 再换表 | ✅ P0 每步都能在旧库上跑和推送；P1 只剩表结构差异 |
| 跨库同事务用分布式事务（XA / Seata） | ❌ ADR-066 已否；没有 JTA；悬而未决的事务靠人工恢复 |
| 出品整组合并进交易库 | ❌ 厨显、工位、出品单都在行业库，搬过去要把厨显一起搬；交易侧对出品只读 |
| 出品整组留行业库，制作任务从交易库收回 | ✅ 用户 2026-11-27 定（ADR-068 修订一）：厨房动作成为库内本地事务，交易库只保留订单 |
| 桌台留行业库、交易库每桌一行资源做锁 | ❌ 桌台版本号契约要改；停用、区域与开台有竞态；依赖交易侧资源模型先落地 |
| 订单域与业务域分开，占台随桌台进行业库，跨域补偿 | ✅ 用户 2026-11-27 定（ADR-068 修订二）：桌台相关操作回到库内，跨域只剩占台挂账单一个连接点 |

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 美业共用 `industry` 句柄 | 餐饮单独完成也不能在生产绑定 | §1.4：测试中绑定验收；生产绑定排在美业对齐之后 |
| 设计稿去掉了代码在用的防重列 | 合并后防重失效 | `V2` 补回并建唯一键，逐条写依据；合并场景测试覆盖重放 |
| W 类拆事务后出现「交易已提交、行业未写」 | 出品单、厨显事件缺失 | 业务键可重复执行 + 扫描补做；同库不同池测试覆盖 |
| 测试夹具大量基于主库 Flyway V1–V37 | P1 改动面大 | 行业部分统一换成 `db/n_industry` 建表的夹具 |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 跑过 |
|---|---|---|
| AC1 | P1 新库测试（`industry` 绑一次性新库） | 待实现 |
| AC2 | `FoodIndustryTableAccessTest` | 待实现 |
| AC3 | `FoodSchemaValidatorTest`（扩展） | 待实现 |
| AC4 | 本机 MySQL 执行 `db/n_industry/V1`、`V2` | 待实现 |
| AC5、AC6 | 行业库场景测试（逐组） | 待实现 |
| AC7 | 部署脚本在本机副本上重复执行两次 | 待实现 |

## §6 对账二 · 设计 → 实现

（实现中逐步填写）

### 已完成：P0-2、P0-3 第一批（2026-11-27）

- `ServiceEntitlementMapper` 改点名 `industry` 数据源；碰交易表的 3 条（点菜行、购物车、延长到店单）移到 `ServiceFormMapper`。
- 新增 `FoodIndustryOperationsMapper`（`industry` 数据源）：从 `FoodBusinessOperationsMapper` 搬出 17 条只碰行业表的语句。
- 「占台联桌台」拆开：交易侧只查占台（`checkCollaborationSelectOccupiedTables`）。
- 新增 `IndustryFollowUp.afterTradeCommit`：W 类的行业写入延后到交易提交后。
- 测试：food-core 754 个通过（54 个要 MySQL 的跳过，与基线相同）。

### 已完成：行业为主命令改业务键回查（2026-11-27）

- 新增 `CommandBusinessKey.derive`：业务单号 = 前缀 + `K` + 「操作、主体、门店、操作人、请求键」摘要的前 20 位。
- 交接三条命令、权益消耗去掉永久命令回执；权益发放、延长、宴会方案、到场确认按「到店单为主」处理（见下表）。
- 新增测试 `FoodBusinessOperationsPersistenceTest#handoverReplaysByDerivedNumberAndRejectsSameKeyWithOtherContent`；
  消耗的重放由已有的 `ServiceEntitlementPersistenceTest#exactOriginalFoodLineConsumesOnceAcrossKeysAndCannotExceedCanonicalPersonQuota` 覆盖。
- 测试：food-core 755 个通过（54 个要 MySQL 的跳过）。

### 已完成：出品整组归行业句柄（2026-11-27）

- `KitchenTaskMapper` 改挂行业句柄。「进行中的批次」拆成：行业库取有未完结任务的批次号（`activeTaskBatchNos`），门店、接单状态、排序由出品服务回交易库过滤。
- 「已由厨房确认取消的主菜数量」是退菜的事实，从出品单 Mapper 移到 `ReturnItemMapper`。
- 出品耗料的待处理、漏处理扫描、按商品筛选：原是跨行业库、交易库、耗料表的一条联表 SQL，抽成 `ProductionSourceQuery` 分三次查询合并；耗料记录按主体、批次所在门店、事件号对上才算（原测试的跨租户、跨门店断言原样保留并通过）。
- 账单释放前的事实计数去掉堂食权益，改由 `FoodIndustryOperationsMapper#diningEntitlementFacts` 在行业库单独数。
- 出品服务的事务管理器改为 `industryTxManager`。
- 测试：food-core 755 个通过（54 个要 MySQL 的跳过）。

### 已完成：业务域的表改挂行业句柄（2026-11-27，修订二第 ① 步）

- 注解 Mapper `TableOccupancyMapper`、`TableOperationMapper`、`ReservationMapper`、`ReservationTableMapper`、`ServiceCalendarMapper` 改挂行业句柄。
- `FoodBusinessOperationsMapper`（交易句柄）里碰占台、时段与容量、订位、制作任务的 25 条语句搬到 `FoodIndustryOperationsMapper`；`ServiceFormMapper` 里的订位、菜单发布修订查询同样搬过去。
- 菜单发布的排他冲突检查原把发布修订（行业库）与菜单、门店上架（交易库）联表：拆成交易库 `MenuStoreMapper#activeMenuNos` 取菜单号，行业库按菜单号查修订。
- 按新划分重扫：注解 Mapper 里句柄与表的归属一致。
- 测试：food-core 755 个通过（54 个要 MySQL 的跳过）。
- 未完成：下列服务里业务域写与订单域写仍在一个交易事务里（未绑定时与原来等价），按 §2.7 第 ②③ 步改成补偿流程：开台、换台、并台、清台（`CheckServiceImpl`）、扫码会话（`DiningSessionServiceImpl`）、订位（`ReservationServiceImpl`）、时段容量的占用与确认（`ServiceSlotServiceImpl`，在下单与接单事务里调用）、结账放台（`CheckCollaborationServiceImpl`）。

### 已完成：开台改为先占桌、后建账单、失败补偿（2026-11-27，修订二第 ② 步，散客与拼桌）

- `CheckServiceImpl#openWithCompensation`：账单号与操作号由请求键派生（`CommandBusinessKey`）；业务域在行业事务里锁桌、分配座位、写占台、更新翻台（拼桌时写操作记录），重放时占台已在就跳过；
  订单域在交易事务里按同一账单号建账单与座位、记事件，账单已在就按它返回；订单域失败且账单不存在时结束本次占台（`compensateOpening`）。门店付款方式检查提到占桌之前，减少无谓的补偿。
- 开台补偿扫描 `CheckServiceImpl#recoverOpenings`：一天内开的、超过 5 分钟仍没有账单的占台按补偿结束；新增维护任务 `TABLE_RECOVERY`（`food-table-recovery`，每分钟）。
- 测试：`CheckServiceTest#eachWriteRollsBackAfterActualOutboxFlush` 的开台、拼桌两项改为验证补偿（订单域表不变，占台结束、翻台复原）；新增 `CheckServiceTest#openingRecoveryEndsOccupancyWithoutCheckAndKeepsRealOnes`。food-core 756 个、food-web 258 个通过。
- 未做：排队入座、订位到店仍在调用方事务里开台（`openQueuedCheck`、`openReservedCheck`）；换台、并台、清台、结账放台、扫码会话；时段容量。

### 已完成：换台、并台、改人数、清台、结账放台（2026-11-27，修订二第 ③ 步）

| 命令 | 顺序 | 失败时 |
|---|---|---|
| 换台、并台 | 业务域先挪占台、更新翻台、写操作记录（操作号由请求键派生，已有就跳过；账单只读比对版本）→ 订单域给账单加版本、记日志与事件 | 订单域失败不补偿，同一请求重试补上（日志里已有操作号就按重放返回） |
| 改人数 | 业务域先按新人数分座 → 订单域写座位、人数、版本、事件 | 订单域失败：按账单当前人数把座位分回（补偿） |
| 部分清台 | 业务域先结束选中的占台、其余重新分座 → 订单域加版本、记事件 | 同换台 |
| 全部清台（账单作废） | 订单域先作废账单、撤销扫码会话、记事件 → 业务域结束占台、更新翻台、写操作记录 | 业务域没做成的由清台补做扫描结束 |
| 结账放台 | 订单域先放台（服务状态）→ 业务域结束占台、给桌台加版本 | 同上 |

- 清台补做扫描 `CheckServiceImpl#recoverClosings`：账单状态或服务状态不再是 OPEN 而占台仍在的，结束占台；并入维护任务 `food-table-recovery`。
- 测试：`eachWriteRollsBackAfterActualOutboxFlush` 中换台、并台按向前补做、改人数按补偿验证；新增 `closingRecoveryEndsOccupancyOfFinishedChecks`。food-core 757 个、food-web 258 个通过。
- 扫码会话（`DiningSessionServiceImpl`）：令牌与顾客会话归平台（P3 迁），这里先去掉跨库锁桌台。令牌轮换、撤销与开会话靠令牌行自身的行锁排队；桌台只读一次比对版本与启用状态，版本号在提交后带原版本条件再加。
- 排队入座（`QueueServiceImpl#seat`）：① 行业事务里把排号标为入座中并记下请求 → ② 事务外补偿开台（请求键 `QUEUE_SEAT:排号`）→ ③ 标为已入座。开台被明确拒绝时退回已叫号并清掉请求；结果不明时保持入座中，由原有的 `recoverSeating` 继续。排队服务改用 `industryTxManager`。`DiningQueueContractTest` 两条按新顺序重写。
- 订位到店（`ReservationServiceImpl#arrive`）：① 行业事务里锁桌、核对时段与重叠、标为已到店 → ② 补偿开台（占桌按订位截止时间分配，请求键 `RSV-订位号`）→ ③ 挂账单号。开台被明确拒绝时退回原状态；结果不明时用同一请求重试接上。订位服务改用 `industryTxManager`。
- 时段容量（`ServiceSlotServiceImpl`，预占—确认）：
  - 预占、改数量先在行业库提交；调用方订单事务回滚时由 `IndustryFollowUp.compensateOnRollback` 撤回预占或改回数量。
  - 确认消耗、绑批次、建定时出品单、取消后释放：订单事务里只校验读取，写入在订单提交后执行，按目标状态可重复。
  - 去掉时段服务的永久命令回执：预占按来源号、转换按目标状态、建时段按请求键派生的时段号查回。
  - 补做：`recoverBindings`（来源购物车已接单、容量仍预占未绑批次超过两分钟的）随定时出品任务每轮执行。
  - 测试：新增 `FoodBusinessOperationsPersistenceTest#reservationIsReleasedWhenTheCallingOrderTransactionRollsBack`；`CatalogOperationsAcceptanceTest` 建时段不再写回执的断言改为 0。food-core 758 个通过。
  - 已知缺口：① 测试里两个句柄同库，回滚补偿与提交后写入的分库行为要等「同库不同池」测试（P0-6）覆盖；② 购物车放弃、到店单或预点单失效而没走释放的预占，目前没有按来源的释放扫描（来源有购物车、到店单、预点单、延时事件四种，需按来源分别判断）。

### 每个库一个数据源（2026-11-27）

原则（ADR-066）：每个库对应一个句柄（数据源 + 工厂 + 事务管理器）；代码只点名句柄，不点名主库。句柄未绑定时是主库的别名（过渡期行为不变），绑定后就是独立的连接池与事务。

| 库 | 句柄 | 餐饮现状 |
|---|---|---|
| 行业库 | `industry` | 桌台、区域、占台、换台记录、订位、时段容量、排队、寄存、出品整组、厨显、权益、交接、门店策略、配置修订都已点名。本次补：桌台管理 `TableServiceImpl`、门店策略 `StoreOperationServiceImpl`、工位 `StationServiceImpl` 由主库事务改为 `industryTxManager` |
| 门店交易库 | `trade` | 本次补：结账 `SettlementMapper`、收款意图 `ChargeIntentMapper`、订单绑定 `CoreOrderBindingMapper`、退菜 `ReturnMapper`/`ReturnItemMapper`/`FoodReturnAmendmentMapper` 由主库改挂交易句柄 |
| 平台基础库 | `platform`（已有） | 未接：门店设备、身份授权、桌台令牌、顾客会话、离线草稿签名（`DiningIdentityMappers`、`TableTokenMapper`、`GuestSessionMapper`、`OfflineDraftMapper` 的签名部分）——表要换成 `mch_store_device`、`guest_session`，随 P3 |
| 基础能力库 | `common`（已有） | 未接：动作日志 `ActionLogMapper` 要换成统一审计 `sys_audit_log`；命令回执 `fnb_business_command` 按 §8-Q1 逐步退役。随 P3 |
| 库存库 | 无（库存模块自有） | 未接：库存与同步镜像、配方、出品耗料、退菜回库处置，随 P3 由库存模块承接 |
| 账务结算库、支付库 | 无 | 未接：营业日关账的结算、余额、异常、调整，随 P3 |
| 主库 | `dataSource` | 只剩餐饮私有 Flyway 历史与启动校验（P0-1、P4 处理）、发件箱（按 §2.3 改为提交后直接调用） |

测试：共用测试配置 `FoodWriteGuardFixture.Shared` 引入 `ModuleDataSourceRegistrar`，句柄按应用同样的规则注册；同库不同池测试（P0-6）在它上面绑定即可。

### P1 进展（2026-11-27）

- **P1-1 建表脚本**：`food-core/src/test/resources/db/n_industry/V1__industry_design.sql`（设计 DDL 原样，去掉建库与 USE）、`V2__industry_supplement.sql`：
  ① 厨显沿用 .61 已执行的 `01-industry-kds.sql`；② 按 ADR-068 第 4 条补回出品单派单键、排队号请求防重列与唯一键；
  ③ 修订一、二收回的表（制作任务、占台、换台记录、订位、订位占桌、时段、容量、营业日历）按餐饮现结构建；④ 过渡：合并与改名完成前代码仍读写的 11 张旧表，每完成一组合并就删掉对应的表。本机 MySQL 9.7.4 上建成 50 张。
- **P1-4 启动校验（过渡版）**：新增 `FoodIndustryTables`（行业句柄负责的表，唯一来源）；`FoodSchemaValidator` 在行业句柄单独绑库时不再要求主库有这些表，改在行业库检查它们的列与唯一键。未绑定时行为不变。
- **P1-6 新库测试**：`FoodIndustryDatabaseTest`（本机 MySQL，`FOOD_TEST_DISPOSABLE`）：主库按餐饮旧迁移建表后删掉行业表，行业库按 V1、V2 新建，行业句柄绑到它；验证上下文能启动、行业句柄下全部注解 Mapper 按实体全字段在行业库上查询成功。已通过。
- **P1-2 改名**：寄存 `fnb_bottle_custody` → `fnb_custody`、`fnb_bottle_movement` → `fnb_custody_movement`（实体表名、校验器、测试）。按旧表最终结构比对出设计漏掉的流水防重列 `key_hash`、`request_hash` 与唯一键，`V2` 补回。`FoodIndustryTables.RENAMED` 登记旧名，校验器不再要求、也不当作未知表。其余同构表的列与唯一键已逐一比对，只差 `V2` 已补的三处。
- **P1-3 合并 ①：堂食权益 → 人头权益**。旧表 `fnb_dining_entitlement` 代码从未写入，只在放台检查与账单事实检查里计数（结果恒为 0）。并入后改为：交易库按账单取到店单号，行业库数这些到店单上 `entitlement_kind='BUFFET'` 的人头权益（放台检查只数 `status='ACTIVE'`）。旧代码不写放题类权益，行为不变。`FoodIndustryTables.MERGED` 登记退役的旧表；`V2` 删掉该表。测试 `CheckServiceTest`、`PhysicalTableMoveTest` 改按新模型造数据。
- 待做：P1-3 其余八组合并、JDBC 行业 Mapper 与跨域流程的新库测试、P1-5 部署脚本。

### 事务拆分记录

| 服务 | 类 | 处理 |
|---|---|---|
| `CheckCollaborationServiceImpl#releaseService` | R + W | 堂食权益只读；桌台加版本改到交易提交后（只加版本、可重复） |
| `ServiceSlotServiceImpl#create` | L → R | 原先锁门店策略行；改为只读确认门店已开餐饮 |
| `ServiceSlotServiceImpl#bindOrder` | W | 建定时出品单改到交易提交后；`ScheduledDiningDispatchServiceImpl#runDue` 每轮先按已绑定的时段占用（`boundScheduledBatches`，近 1 天）补建，建单按批次号可重复 |
| `ScheduledDiningDispatchServiceImpl` | 行业 | 认领与完结定时出品单改用 `industryTxManager` |
| `BusinessCloseServiceImpl` | R | 未决交接改从行业 Mapper 读 |
| `BusinessOperationsServiceImpl` 交接提议、接收、取消 | 行业为主 | 去掉命令回执，在行业事务里写；交接单号由请求键派生（`CommandBusinessKey`），重放按单号查回，同键异参拒绝；接收、取消以交接单本身为键，已是目标状态且同一人决定的按重放返回。来源只读、按摘要比对，不跨库加锁。账单上的服务员（交易冗余）在行业提交后写，只在不同时才改，重放接收时再补一次 |
| `ServiceEntitlementServiceImpl#consume` | 行业为主 | 去掉命令回执，在行业事务里锁权益、扣量、记事件；「批次 + 点菜行」为业务键，换不换请求键都查回首次结果；到店单、账单、点菜行只读 |
| `ServiceEntitlementServiceImpl` 发放、延长、宴会方案、到场确认 | 到店单为主 | 仍是交易事务 + 交易侧命令回执（回执与到店单同库同事务）。交易侧的校验与写（推进到店单版本、时段占用）先做，行业库的写放在最后、用行业事务单独提交，然后交易提交；行业单号由请求键派生，已写过就跳过。残余窗口：行业已提交而交易提交本身失败，留下一条孤立的行业行，客户端用同一请求键重试即接上 |
| `BusinessCloseServiceImpl` 建营业日 | L → R | 原在交易事务里锁门店策略只为读当天生效的策略；改为只读，建日由交易库的营业日行锁串行 |
| `KitchenTaskServiceImpl` | 行业 | 出品整组同在行业库，厨房动作是行业事务；对批次、点菜行、账单只读。冻结路由（下单时）只读行业配置 |
| 接单后建出品单 | 交易先、行业跟（已有） | 接单提交后由 `DiningKitchenAcceptedConsumer` 收事件调 `accepted`，带派单键可重复；定时出品由定时任务调 |
| `KitchenReturnServiceImpl` 退菜取消制作 | 行业先、交易后 | 退菜事务里调出品服务，出品在行业事务里先提交；请求键由退菜行与任务号确定，交易回滚后重试按键重放；事件上的动作键唯一兜底 |
| `ProductionConsumptionServiceImpl` | **随 P3** | 耗料记录（主库，将归库存库）与制作任务、服务事件（行业库）仍在一个主库事务里写；耗料按处置提案改到库存模块时拆，见 [其余旧表提案](../../deploy/ume/food-kds-cutover/legacy-table-dispositions.md) |
| 出品动作发的 `DiningServiceEvent`（发件箱） | **待改** | 发件箱写主库，出品在行业事务里后不再同事务；按 §2.3 改为提交后直接调用，行业句柄生产绑定前完成 |

## §8 待定

- **Q1 行业为主的命令，永久命令回执放哪。** ✅ 2026-11-27 用户定：**业务键回查**（下面的 ①）。已用于交接与权益消耗，见 §6。原问题： `BusinessCommandJournal` 要求回执与业务写在同一事务里，回执表 `fnb_business_command` 现在在交易句柄上。
  交接、权益、宴会、厨显、桌台编辑这些命令的业务写在行业库，回执就不能和它同事务。备选：
  ① 回执退役，改「公共幂等短期认领 + 业务键回查」：命令的业务单号由请求键确定性派生（如交接单号），重放时按业务单号查回结果（对齐 ADR-068 第 4 条与「业务库不建技术件」）；
  ② 行业库里也放一份回执表（与「业务库不建技术件」冲突）；
  ③ 回执放基础能力库，先认领、后完成，中间崩溃靠业务键回查（①加一张公共表）。

- **Q2 桌台的并发锁放哪。** ✅ 2026-11-27 用户定：订单域与业务域分开，占台、换台记录、订位、时段与容量随桌台归行业库，跨域用补偿（ADR-068 修订二，§2.7）。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-27 | 方案给出；用户定：写 TDD，先做 P0+P1；P1 的 9 组合并一次到位 |
| 2026-11-27 | 用户定：行业为主的命令用业务键回查（§8-Q1） |
| 2026-11-27 | 用户定：出品整组迁到行业库，跨库场景降到最低，交易库只保留订单（ADR-068 修订一，§2.3） |
| 2026-11-27 | 用户定：订单域与业务域分开，跨域用补偿不用事务；占台、换台记录、订位、时段随桌台归行业库（ADR-068 修订二，§2.7） |
| 2026-11-27 | 用户定：不做同库不同池测试，改完直接在新库测试（P0-6 取消，AC1 由 P1 新库测试覆盖） |
