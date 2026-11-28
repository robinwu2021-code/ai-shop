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
创建：2026-11-27 · 最后更新：2026-11-28

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
| 端点 / JSON | 设备出口（§2.8）：`GET /biz/x/food/kitchen-events` 停用；`kitchen-stream` 只推 `refresh`、去掉 `after` 与 `Last-Event-ID`；状态用现成的后厨任务列表与排号列表；手工打印不再接受厨房变更、桌台变更、标签三种事件单（D-3 已做）。D-5：新增门店设备的登记、列表、停用恢复、厨显工位接口，停用订阅开通与吊销，事件流与语音按设备号。其余无 |
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
| 厨显 6 张（`fnb_kds_cursor`、`fnb_kds_event`、`fnb_kds_subscription`、`fnb_kds_voice_command`、`fnb_station`、`fnb_station_runtime`） | 已按 .61 现场结构建好 | 先沿用 `01-industry-kds.sql` 的现场落点写进 `V2`；按 §2.8 收为 4 张（删游标、工位运行表，厨显事件改为厨房事件，订阅精简） |

### 2.5 P1 行业库：9 组合并（用户 2026-11-27 定一次到位）

| 旧表 | 并入 | 映射要点 |
|---|---|---|
| `fnb_config_revision` | `ind_config_revision` | `config_no` → `policy_no`；`revision` → `revision_index`；`revision_no` → `policy_version_no`；`content_hash` → `snapshot_hash`；`content_schema` → `schema_version`；内容进 `definition_snapshot` |
| `fnb_banquet_plan_revision` | `ind_config_revision`（`config_kind = BANQUET_PLAN`） | `plan_no` → `policy_no`；计划人数、预算、币种、表单号、计划快照进 `definition_snapshot` |
| `fnb_store_policy` | `ind_store_policy`（`industry_code = FNB`） | 时区、切日、付款方式、能力码直接成列。**用户 2026-11-27 定：不保留历史**——每店每行业一行，改策略即原地更新、立即生效，乐观锁版本号；`effective_from` 不落库，未来时间拒绝（`BAD_REQUEST`）；`settings` 旧代码只写 `{}`，丢弃；`current_policy_version_no` 暂不用 |
| `fnb_dining_entitlement` | `fnb_service_entitlement` | `check_no` → `form_no`；`starts_at`/`expires_at` → `valid_from`/`valid_until`；类型、人群、人数、规则快照、状态成列；`core_order_ref` 经到店单查 |
| `fnb_scheduled_dispatch` | `fnb_kitchen_ticket` | 定时出品作为出品单的 `SCHEDULED` 状态加 `dispatch_at`；`attempts` → `dispatch_attempts`；`next_attempt_at` 与 `dispatch_key` 的用途实现时逐条核对，代码在用就在 `V2` 补回 |
| `fnb_service_event` | `fnb_kds_event`（按 §2.8 改名 `fnb_kitchen_event`，作厨房动作事实表，不再做推送流） | `action` → `event_type`；`task_no`、`qty`、`ready_loss_qty`、`reason`、`inventory_doc_no`、`custody_snapshot` 成列；`operation_key`、`source_request_hash` 是动作防重，按 ADR-068 第 4 条在 `V2` 补回并建唯一键 |
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

### 2.8 设备出口：厨显、厨房打印、叫号（用户 2026-11-28 定）

**原则**
- **业务不依赖设备。** 出品单、制作任务、排号是唯一真相；一家店的厨显、打印机、叫号屏可以有一台、多台或一台没有，业务流程都照跑，设备只是出口。
- **屏拉当前状态，不拉事件流。** 厨显屏、叫号屏、B 端 App 的后厨页与排队页调同一个状态接口（门店 + 工位 → 在制的出品单与制作任务；门店 → 当前排号），直接查业务表。MQ 只发「有变化、来刷新」；漏一条无妨，下一次通知、断线重连或定时刷新都会拉到最新。**不再要门店序号，也不要推送流表。**
- **打印必须打出来。** 厨房单丢了菜就没人做：走 ADR-067 的「发件箱 → MQ」必达路线，打印任务按「出品单号 + 工位 + 版本」防重，交给打印域现有的领取、回执、补打。

**有几台设备都一样**

| 情况 | 后厨 | 叫号 |
|---|---|---|
| 没有设备 | 店员用 B 端 App 后厨页看单、点出品（同一个状态接口） | 店员在 App 上叫号；线上取号的顾客收到通知（`notify_message`） |
| 一台 | 这台看全部工位、打全部单 | 一台叫号屏显示当前叫号 |
| 多台 | 每台屏配自己看的工位（`fnb_kds_subscription`）；打印机按打印路由分工位 | 多台屏同时收到刷新 |

**链路**（一条通道，三类设备共用）

```
业务事务提交（出品单或制作任务变化 / 叫号）
   └─ MQ：KITCHEN_CHANGED{门店, 工位[]} / QUEUE_CHANGED{门店}
        ├─ 打印消费者（必达）：按打印路由找打印机 → 打印任务
        └─ 设备通知消费者（直发、广播）：每个服务实例一条临时队列
              → 本实例上连着的、看这些工位的屏收到「刷新」 → 屏调状态接口
```

屏与 App 不直连 RabbitMQ，仍连服务端的事件流（现 `/biz/x/food/kitchen-stream`）。通知只带门店、工位、变化类型，不带业务数据，不要求保序与去重。

**设备归属：谁用就放谁那里**（不做「一张表装所有设备」的抽象）

| 设备 | 放哪 | 理由 |
|---|---|---|
| 厨显屏、叫号屏、点餐屏、离线收银机 | **行业库 `ind_store_device`**（由平台库设计稿的 `mch_store_device` 搬来；现无代码引用） | 门店现场设备，各行业共用 |
| 打印机、本地打印代理 | 打印域（基础服务库 `prn_printer`，代理用打印域自己的登记） | 打印是基础能力，零售也打小票；基础库不反向引用行业库 |
| 支付终端 | 支付库（`pay_terminal`） | 收银班次、终端配置都在那边 |

`ind_store_device` 相对 `mch_store_device`：设备类型只留 `ORDER_SCREEN`、`KDS`、`OFFLINE_POS`，新增 `CALL_SCREEN`（叫号屏）；去掉 `PRINTER`、`PRINT_AGENT`、`POS_TERMINAL`。删 `capabilities`、`device_config` 两个通用 JSON 列。配对、凭据、代数、最后在线保留；离线收银的签名密钥、写入授权、核验挑战几列保留（可空，联网补传时交易库按设备号只读）。厨显屏的身份改认这张表，不再借打印设备登记（`FoodKdsServiceImpl#liveDevice`）。

**行业库相关表整理**（厨显与工位 7 张 → 4 张）

| 表 | 问题 | 处理 |
|---|---|---|
| `fnb_station` + `fnb_station_runtime` | 工位已有 `runtime_status`，又另建运行状态表 | **删 `fnb_station_runtime`**，开停只改 `fnb_station.runtime_status`，用工位版本号 |
| `fnb_kds_subscription` | 写成授权单：登录人、到期、防重键与摘要、撤销键、原因、撤销人 | 设备身份与停用由设备表管后，只留「哪台屏看哪些工位」：`device_no`、`stations`、`version`，每台屏一行 |
| `fnb_kds_cursor` | 只发门店序号 | **删** |
| `fnb_kds_event` | .61 上序号、推送内容非空且序号唯一；另有一组与语音命令表重复的语音列 | **改名 `fnb_kitchen_event`，作厨房动作事实表**：并入服务事件（合并 ⑥），去掉 `sequence_no`、`payload` 与语音列（`device_no`、`voice_text`、`command_status`、`response_code`），补 `operation_key`、`source_request_hash` 与唯一键 |
| `fnb_service_event` | 与上一张记同一件事 | 并入后**删** |
| `fnb_kds_voice_command` | 屏 → 服务端的命令，带领取租约 | 不变 |
| `fnb_queue_ticket` | 叫号屏要显示的都在这里 | 不变，叫号屏直接读 |

**接口变化**：厨显事件按序号拉（`GET /biz/x/food/kitchen-events`）停用，改为工位状态接口；事件流只推「刷新」。工位语音指令与开停不再写厨显事件（`FoodStationRuntimeServiceImpl#publish` 改为发刷新通知）。`DiningKdsEventConsumer` 退役：接单、改单、账单变化也只发刷新。新增叫号屏的状态接口与配对。

**代价**：.61 已建的 `fnb_kds_event` 改结构，`fnb_kds_cursor`、`fnb_station_runtime`、`fnb_service_event` 删除，带 DDL，须 root 手工执行；厨显屏认证从打印设备登记换到设备表，配对流程补齐；屏端与 App 后厨页改为「收通知 → 拉状态」。

**实施步骤**（接在 P1-3 之后）

| # | 动作 | 说明 |
|---|---|---|
| D-1 | 合并 ⑥ | `fnb_service_event` → `fnb_kitchen_event`；厨房动作在行业事务里只写这一张；报表、耗料、日结、退货回库、外卖出餐信号改读它 |
| D-2 | 工位 | 删 `fnb_station_runtime`，运行状态改写 `fnb_station` |
| D-3 | 状态接口 + 刷新通知 | 后厨、叫号状态接口；MQ `KITCHEN_CHANGED`、`QUEUE_CHANGED` 广播到各实例推刷新；停用按序号拉；删 `fnb_kds_cursor`、`DiningKdsEventConsumer` |
| D-4 | 打印必达 | 厨房打印走发件箱 → MQ，按出品单号 + 工位 + 版本防重 |
| D-5 | 设备表 | 行业库建 `ind_store_device`，厨显订阅精简；厨显认证改认设备表；补叫号屏类型与配对 |
| D-6 | 设计稿对齐 | 06-n_industry、01-n_platform 的 DDL 与库/字段文档同步：设备表搬家、上述删表与改名 |

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
- **P1-3 合并 ②：宴会方案修订 → `ind_config_revision`（`config_kind=BANQUET_PLAN`）**。被修订的对象是到店单：`policy_no`=到店单号、`revision_index`=修订序号、`policy_version_no`=方案号；方案快照（已含币种、预算、人数）进 `definition_snapshot`，摘要进 `snapshot_hash`。读取按原列名取别名，服务不变。新表唯一键带 `tenant_no` 且可为空，写入显式填 `MAIN`（MySQL 唯一键不约束空值）。
- 测试工具 `IndustrySchema`：H2 场景测试按 `db/n_industry` 建表（清洗 MySQL 方言、索引名加表名前缀、JSON 列按文本建）。
- **P1-3 合并 ③：配置修订 → `ind_config_revision`**（工位、菜单、套餐、选项组、加料、配方的发布修订）。`revision_no`→`policy_version_no`、`config_no`→`policy_no`、`revision`→`revision_index`、`content_schema`→`schema_version`、`content_hash`→`snapshot_hash`；实体字段名不变，`ConfigRevisionMapper.COLUMNS` 按旧列名取别名，服务不变；写入显式 `tenant_no='MAIN'`。数据域登记改为新表。8 个测试里的直接 SQL 按同一规则改写，自建旧表改用 `IndustrySchema`。
- **P1-3 合并 ④：门店策略 → `ind_store_policy`（`industry_code='FNB'`），不保留历史**（用户 2026-11-27 定）。
  旧表每次发布追加一行、按 `effective_from` 取「当时生效」的那条；新表每店每行业一行：发布时锁当前行，版本号相符就原地改时区、切日、付款方式、能力码并加版本，没有就建（`tenant_no='MAIN'` 显式写）。
  请求里的 `effectiveFrom` 仍校验，晚于当前时刻拒绝（`BAD_REQUEST`），不再登记待生效版本；查询的时刻参数不再挑版本。
  读策略的地方统一走 `StorePolicyMapper.current` / `lockCurrent`：营业日、日结、报表事实、菜单时区、排队锚点、日历来源（只剩当前一条，下界取查询起点）。
  营业日自身冻结起止时间，旧日子不依赖策略历史。
  设计 DDL 的唯一键 `(tenant_no, entity_no, store_no)` 不含行业，餐饮与美业同店会冲突，`V2` 改为 `(tenant_no, entity_no, store_no, industry_code)`；校验器同步。
  测试：发布改为原地更新语义并加未来时间拒绝；依赖历史的两条查询用例与日历来源的多版本用例改写或删除。
- **P1-3 合并 ⑤：定时出品 → 出品单（`fnb_kitchen_ticket`）**。时段绑定批次后登记一张 `status='SCHEDULED'`、`kind='MAKE'` 的出品单（`dispatch_at` 计划时刻、`dispatch_key` 与接单建单同一个键，重复登记不多建）。
  到点时接单建单（`KitchenTaskServiceImpl#accepted`）沿用这张单的单号补制作任务，状态随任务变为排队；还没下达的定时单不当作「原单」（寄存来源查原单时排除）。
  `next_attempt_at` 代码在用（认领租约、失败重试），`V2` 补回；非空即待下达，下达成功清空。认领、完结、重试按读到的 `next_attempt_at` 做比较交换，**不加出品单版本号**，认领期间厨房动作改出品单不冲突；原批次已取消的定时单直接 `CLOSED`。旧状态 `RETRY` 不再单列，由 `last_error` 与 `dispatch_attempts` 体现。
  测试工具 `IndustrySchema` 同时执行 `V2` 对该表的补列、补键。新增 `ScheduledKitchenDispatchMapperTest`。
- **P1-3 合并 ⑦：服务交接、交接明细、服务责任 → `ind_assignment`**（`assignment_kind='RESPONSIBLE'`）。
  责任＝对象（`target_type`/`target_no`，沿用 `CHECK`、`BATCH`、`RESERVATION`、`QUEUE`、`PREORDER`）上 `status='ACTIVE'`、`ended_at` 为空的那一条。
  发起交接：每个对象一条 `PENDING` 指派（`personnel_no`=接收人、`created_by`=发起人、`handover_reason`），指派号由交接单号与对象派生，同一请求并发重放撞唯一键后按单号查回。
  接收：在行业事务里锁住该对象的全部责任指派（含别的交接的待接收条），锁内复核当前负责人仍是发起人，然后旧责任 `ENDED`、这条 `ACTIVE` 且 `previous_assignment_no` 指向旧条——同一对象上的接收因此串行，旧表靠唯一键保证的「一个对象一个负责人」由行锁保证。取消：待接收的条全部 `CANCELLED`。
  交接单不单独成行：由明细汇总（有待接收为 `PROPOSED`、已取消为 `CANCELLED`、否则 `ACCEPTED`；决定人按状态推出）。交接单的来源摘要改为各明细来源摘要的摘要（旧的是全部证据 JSON 的摘要，含义不变、取值变）。
  `V2` 补回 `handover_no`（归组）与 `source_hash`（接收时比对来源）两列及索引。测试：交接夹具改用 `IndustrySchema`，补链式交接与取消用例。
- **D-1 / P1-3 合并 ⑥：服务事件 → 厨房事件 `fnb_kitchen_event`**（§2.8）。厨房动作的事实只写这一张；`action` 列改名 `event_type`（实体字段仍叫 `action`，用 `@TableField` 映射），`operation_key`、`source_request_hash` 与两条唯一键（事件号、动作防重键）保留。
  实体与 Mapper 改名 `FnbKitchenEvent` / `KitchenEventMapper`；耗料来源的两条原生 SQL 改表名与列名；`FoodIndustryTables` 把旧表移入 `MERGED`，校验器登记新表的唯一键与列，数据域登记改新表。
  厨显推送流 `fnb_kds_event` 与游标暂不动，D-3 改为「拉当前状态」后删除。`V2` 去掉过渡的 `fnb_service_event`，过渡表至此清空。
  测试：按实体建表的两个夹具改为认 `@TableField` 列名；`IndustrySchema` 在一次性 MySQL 上保留 JSON 列（原先一律转成超长 VARCHAR，MySQL 建 `ind_config_revision` 失败，合并 ③ 起 `KitchenLifecycleAcceptanceTest` 在 MySQL 模式下跑不起来）。
  餐饮全量 763 个（跳过 55 个仅 MySQL 用例）无失败；本机 MySQL 上 `FoodIndustryDatabaseTest`、`ProductionConsumptionListMapperTest`、`KitchenLifecycleAcceptanceTest` 通过。
- **D-2 工位运行状态并回工位**（§2.8）。`fnb_station_runtime` 退役（`V2` 不再建，`FoodIndustryTables.MERGED` 登记）；开停写 `fnb_station.runtime_status`（`RUNNING` / `PAUSED`，空即 `RUNNING`，沿用接口现有取值；设计稿注释的 OPEN/CLOSED 在 D-6 对齐），版本号用工位自己的 `version`，按读到的版本比较交换，改不到即冲突。
  不再需要「先补一行运行状态」：`FoodStationRuntimeMapper#initialize` 删除。代价：开停会推进工位版本号，此后拿旧版本号改工位配置会冲突，需重新读取。
  测试：两个运行状态夹具的工位表补 `runtime_status`、`version`；补一条断言暂停后工位行状态与版本号。全量 763 个无失败，MySQL 新库测试通过。
- **D-3 屏改为收刷新、拉当前状态**（§2.8，用户 2026-11-28 定多实例广播这次一起做）。
  - **刷新信号** `FoodDeviceRefresh`（food-api）：只带门店、工位、变化类型（`KITCHEN` / `QUEUE` / `STATION`），提交后发。配了消息中间件时由 `BrokerFoodDeviceRefresh`（food-core）经**广播订阅**发到每个服务实例；没配时 `InProcessFoodDeviceRefresh` 只在本进程内传。发出时：厨房动作、接单、改单、账单变化（设备出口消费者收到发件箱事件后）、工位开停与语音指令（`FoodStationRuntimeServiceImpl`）、排号变化（`QueueServiceImpl`）。
  - **消息封装加广播订阅**（基础能力，ADR-067 第 9 条的实现语义补第 6 条）：`BrokerSubscription` 加 `broadcast`；RabbitMQ 实现给每个进程实例建一条非持久、最后一个消费者断开即删的队列 `<前缀>.<订阅名>.<实例号>`，不设投递上限与死信，处理失败只记日志。原三参构造保留，现有订阅不受影响。
  - **状态接口用现成的**：后厨任务列表 `GET /biz/x/food/stores/{storeNo}/kitchen/tasks?stationNo=`、排号列表 `GET /biz/x/food/queue-tickets`，不新增端点。
  - **事件流** `GET /biz/x/food/kitchen-stream?subscriptionNo=` 只推 `refresh` 事件（`kind` + 该看的 `stationNos`）：连上推一次全量；本店、涉及这台屏工位的信号到了就推；每 30 秒重新核验订阅并兜底推一次全量，订阅被吊销或过期即断开。`after` 参数与 `Last-Event-ID` 去掉。
  - **停用** `GET /biz/x/food/kitchen-events`（按序号拉）与内部接口 `/internal/food/kds/replay`、`/append`，新增内部 `/authorize`；`FoodKdsService.replay/append` 换成 `authorize`。`fnb_kds_event`、`fnb_kds_cursor` 退役（`V2` 删表、不建游标；`FoodIndustryTables.MERGED` 登记；校验器去掉）。语音领取与回执原先借游标做全店串行，改为只锁本台屏的订阅行。
  - **设备出口消费者**（`DiningKdsEventConsumer`，处理者编号不变）不再写厨显事件表：发刷新，并按打印路由打事件单据（厨房变更、标签、桌台变更）。单据内容直接由到达的事件定格（`FoodEventPrintDocuments`，从 food-core 的 `FoodEventPrintDocumentAssembler` 移来），经 `PrintPort` 入队。**契约变化**：手工 `POST /biz/x/food/print-jobs` 不再接受这三种事件单场景（`BAD_REQUEST`），补打走原打印任务的补打。
  - 测试：流的验收测试按刷新语义重写（首推、按店按工位过滤、兜底重新核验后断开、吊销的订阅连不上）；消费者测试改为断言刷新与由事件定格的标签；订阅测试改测核验；运行状态的 MySQL 测试改为断言提交后发信号、被拒不发；`RabbitMessageBrokerTest` 补广播拓扑。food-core 761 个（跳过 55）、food-web 258 个无失败；本机 MySQL 上新库、运行状态、厨房生命周期、耗料来源测试通过；网关接口清单重新生成（B 端少 1 个入口）。
  - 未做：独立部署（`shop.food.deployment=standalone`）且未配消息中间件时，网关侧收不到核心进程的开停与叫号信号，只靠 30 秒兜底；叫号屏的订阅与设备类型在 D-5。
- **D-4 打印必达**（§2.8）。核对后，厨房打印已经都在发件箱消费者上：接单建出品单后出单（`DiningKitchenAcceptedConsumer`）、定时出品到点出单（`ScheduledDiningDispatchServiceImpl`，与接单同一个键）、事件单据（设备出口消费者）。失败抛出即由发件箱重投。
  防重键沿用、不加版本：厨房单 `AUTO_PRINT_V1:批次:场景:工位`（每批次只有一张制作出品单，等同出品单号 + 工位），事件单据 `EVENT_PRINT:事件号:场景:工位`。改单、重做、损耗都是新事件，带新键，同一事件重投不会重打。
  缺的是中间件这一段：餐饮这五个事件（`DINING_ORDER_ACCEPTED`、`DINING_SERVICE_EVENT`、`DINING_CUSTODY_HANDOVER`、`FOOD_CHECK_CHANGED`、`DINING_ORDER_CHANGED`）不在「发件箱改由中间件投递」名单里。收它们的消费者（建出品单与出单、设备出口、渠道履约回传、报表冲正）收的餐饮类型都在这五个里，按「全进或全不进」整组加进 `shop.events.outbox-event-types` 默认值。`transport=rabbitmq` 时各消费者按自己的保序订阅（按流键分区）收；`local` 时名单不起作用、行为不变。新增 `DiningKitchenRelayListTest`。
  部署注意：餐饮要求平台发件箱为统一模式（`SHOP_OUTBOX_PLATFORM_MODE=unified`）；环境里若用 `SHOP_EVENTS_OUTBOX_TYPES` 覆盖了名单，要把这五个补进去。
- **D-5 门店设备进行业库**（§2.8；用户 2026-11-28 定：**屏用店员账号登录，设备只需在册**，不做设备凭据登录）。
  - **`ind_store_device`**（`V2` 新建）：列取自平台库设计稿 `mch_store_device`，去掉 `capabilities`、`device_config` 两个通用 JSON 列；类型 `ORDER_SCREEN`、`KDS`、`CALL_SCREEN`、`OFFLINE_POS`。配对、凭据、离线收银几列保留、先留空。设备号由登记请求键派生（业务键回查：同键重放得到同一台、同键异参冲突），登记即在用。
  - **`fnb_kds_subscription` 精简**为「哪台厨显看哪些工位」：`device_no` 唯一、`stations`、`version`；登录人、到期、防重键、撤销信息都去掉，停用设备即撤销。`V2` 删设计稿的表后重建；`IndustrySchema` 改为以最后一次建表为准。
  - **接口**：新增 `POST/GET /biz/x/food/devices`（登记、列表）、`POST /biz/x/food/devices/{deviceNo}:status`（停用、恢复，已是目标状态按重放返回）、`PUT /biz/x/food/devices/{deviceNo}/stations`（厨显工位，须是本店工位，按订阅版本比较交换）；停用 `kitchen-subscriptions` 的开通与吊销。事件流参数改为 `deviceNo`；语音领取、回执改为 `/biz/x/food/devices/{deviceNo}/voice:claim|acknowledge`。内部接口同步（`/internal/food/kds/register|status|stations|list|authorize`，语音的 `subscriptionNo` 改名 `deviceNo`）。网关接口清单重新生成（B 端净增 2 个入口）。
  - **核验**：连上来的屏须本店在册、在用；厨显还要设定了工位，叫号屏不需要；其他类型连不上。厨显只收后厨与工位的刷新，叫号屏只收排号的刷新。不再借打印设备的登记，`FoodKdsServiceImpl`、`FoodStationRuntimeServiceImpl` 去掉对打印域的依赖；语音播报指定的厨显也改查设备表。
  - 测试：设备服务测试（登记重放与冲突、工位须属本店与版本、停用即连不上、叫号屏与其他类型）；流测试补叫号屏只收排号；运行状态测试改用设备夹具 `KdsDeviceFixture`。food-core 762 个（跳过 55）、food-web 259 个无失败；本机 MySQL 上新库、运行状态、厨房生命周期测试通过。food-server 本机离线构建缺依赖（food-channel 的外部包，与本次无关），其内部接口已逐行核对。
- **D-6 设计稿对齐**（§2.8）。沿用本仓库的做法：DDL 不手改，由 `scripts/gen-retail-ddl.py` 在 ai-shop-slim 的基础 DDL 上套用定稿的改动（slim 那边不动）。
  - 新增 `docs/technical/design/schema/retail-ddl/food-device-tables.sql`（`ind_store_device`、精简后的 `fnb_kds_subscription`、`fnb_kitchen_event` 取自 `V2` 的定稿结构；`fnb_kds_voice_command` 按 `V2` 补注释）。生成器用它取代设计稿的 `fnb_kds_subscription`、`fnb_kds_event`，改 `fnb_station.runtime_status` 的注释（`RUNNING`/`PAUSED`），去掉 `fnb_kds_subscription.subscription_no` 唯一键；并开始产出 `01-n_platform.sql`：基础 DDL 原样，只去掉搬走的 `mch_store_device`。重新生成后行业库 30 张、`--check` 通过。
  - 库与字段文档：`库/06-行业库.md`、`字段/06-行业库.md`（出品与厨房改为 5 张：厨房事件取代厨显事件、语音指令独立；新增「行业公用·门店设备」一节）、`库/01-平台基础库.md`、`字段/01-平台基础库.md`（门店设备注明已搬走）、`01-清单.md`（表数随之调整）。
  - `V1__industry_design.sql` 不跟着重生成：它是 P1 开工时的设计稿原样、与 .61 现场一致，改动都在 `V2` 里，部署脚本（P1-5）也按这个差量写。
  - 顺带：`check-generated-docs` 发现 `旧库调用清单.md` 已与源码对不上（本周合并与本 TDD 的改名、换句柄），重跑 `gen-legacy-db-usage.py` 一并提交。
- 设备出口 D-1～D-6 全部完成。
- **P1-5 部署脚本**：[`deploy/ume/food-industry-cutover/`](../../deploy/ume/food-industry-cutover/README.md)。.61 的 `n_industry` 尚无应用写入，做法是「确认餐饮相关表全为空 → 备份 → 按定稿结构删表重建 → 逐表核对列与唯一键」，有数据即拒绝执行。定稿结构由 `gen.py` 在一次性库里执行 V1、V2 后 `SHOW CREATE TABLE` 生成，与 `FoodIndustryDatabaseTest` 测过的一致。
  清单（以 .61 = 设计稿 + `01-industry-kds.sql` 共 17 张为基准）：新建 14 张、结构有变重建 11 张、结构不变重建 2 张、删除退役 3 张（`fnb_kds_event`、`fnb_kds_cursor`、`fnb_station_runtime`）；美业表、`ind_commission_rule`、`n_trade`、旧主库不动。本机按该基准模拟：`check` 差异与清单一致，`apply` 两次均核对通过，有数据时拒绝执行。**现场尚未执行**，须 root 账号先 `check` 核对现场与清单一致再 `apply`。
- 待做：JDBC 行业 Mapper 与跨域流程的新库测试、P1-5 部署脚本、§8-Q3。

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

- **Q3 订单域的门店锁。** 下单（`DiningOrderServiceImpl`）、限量认领（`SalesQuotaClaimServiceImpl`）、限售规则（`SalesRestrictionServiceImpl`）在交易事务里锁门店策略行做同店串行；策略行已在行业库，行业句柄单独绑库后这把锁不在交易事务里，串行失效。
  现按原样改为 `lockCurrent`（未绑定时同库照常生效）。**行业句柄生产绑定前**须换成交易库内的锁（例如营业日行或限售规则行本身），待定。

- **Q4 服务事件并入厨显事件的方式。** ✅ 2026-11-28 用户定：先统一设备出口（§2.8）。屏改为拉当前状态，厨显事件不再做推送流，改名 `fnb_kitchen_event` 作厨房动作事实表，服务事件并入（下面三种都不采用）。原问题：现状一个厨房动作产出两行：`fnb_service_event`（事实：任务、数量、损耗、库存凭据、寄存快照，按 `operation_key` 防重）＋ 发件箱 `DiningServiceEvent` → `DiningKdsEventConsumer` → `fnb_kds_event` 的 `KITCHEN_ACTION`（推送：门店内序号、推送内容，厨显按序号拉）。`fnb_kds_event` 在 .61 已按推送流建（序号、来源、内容非空且序号唯一）。
  ① 一行两用：厨房动作在行业事务里直接写 `fnb_kds_event`（事实列＋取门店序号＋推送内容），消费者不再为 `KITCHEN_ACTION` 追加；少一张表、少一次经发件箱的转手，顺带了结「出品事件发件箱待改」。代价：厨房动作要锁门店游标取序号（同店厨房动作串行），推送内容在写事实时定格。
  ② 只并表不并行：事实行写入 `fnb_kds_event` 但不进推送流（序号可空、不同事件类型），推送仍由消费者追加；要放宽 .61 已建的非空与唯一约束，一张表两种语义。
  ③ 不并：`fnb_service_event` 作为行业库独立表保留（偏离设计稿）。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-27 | 方案给出；用户定：写 TDD，先做 P0+P1；P1 的 9 组合并一次到位 |
| 2026-11-27 | 用户定：行业为主的命令用业务键回查（§8-Q1） |
| 2026-11-27 | 用户定：出品整组迁到行业库，跨库场景降到最低，交易库只保留订单（ADR-068 修订一，§2.3） |
| 2026-11-27 | 用户定：订单域与业务域分开，跨域用补偿不用事务；占台、换台记录、订位、时段随桌台归行业库（ADR-068 修订二，§2.7） |
| 2026-11-27 | 用户定：不做同库不同池测试，改完直接在新库测试（P0-6 取消，AC1 由 P1 新库测试覆盖） |
| 2026-11-27 | 用户定：门店策略不保留历史，每店每行业一行原地更新、立即生效，未来生效时间拒绝（§2.5、合并 ④） |
| 2026-11-28 | 用户定：设备出口统一——业务不依赖设备，屏拉当前状态、MQ 只发刷新，打印必达；门店设备表 `mch_store_device` 搬到行业库 `ind_store_device`，打印机留打印域、支付终端留支付库；厨显与工位 7 张收为 4 张，不做过度抽象（§2.8，了结 §8-Q4） |
