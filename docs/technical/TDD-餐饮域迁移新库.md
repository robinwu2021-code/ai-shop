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
交易相关的 66 张进门店交易库 `n_trade`，代码并回交易模块（P2，随零售簇乙一起切）；
行业相关的 27 张进行业库 `n_industry`，代码留在插件（P1，本篇先做）；
库存、支付、平台、审计等 32 张由各自的归属模块承接（P3）；14 张直接退役（P4）。
本篇先做 **P0（底座：句柄、校验、跨库语句与事务）** 和 **P1（行业库：按设计表结构对齐，含 9 组合并）**。

## §0 对账一 · 需求 → 设计

本篇是重构，不加功能。验收标准是「行为不变 + 归属落地」：

| AC | 验收标准（Given / When / Then） | 落点 |
|---|---|---|
| AC1 | Given `trade`、`industry` 各连同一个库的**另一个连接池** When 跑餐饮测试 Then 全部通过（原来靠同一事务的地方都已拆开或改成交易先、行业跟） | P0-4 事务拆分；`FoodSeparatePoolsTest` |
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
| 去 `n_industry` | 27 张旧表 → 设计中的 18 张餐饮表与共用表 | 同上 |
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
| P0-6 | 新增 | `food-core/src/test/.../FoodSeparatePoolsTest.java` | `trade`、`industry` 各绑同一库的另一池，跑跨句柄的代表流程（AC1） |

### 2.3 事务：三类跨句柄用法

| 类 | 现在 | 改成 | 恢复 |
|---|---|---|---|
| R 读 | 交易事务里读行业事实（门店策略、桌台、工位、配置修订） | 照读；不在交易事务里对行业行 `FOR UPDATE` | — |
| L 跨库锁 | 交易事务里锁行业行，或行业事务里锁交易行（如 `StationMapper#lockStore` 锁门店策略、占台锁桌台） | 锁本库的根行；对方的事实「读一次 + 版本比对」 | 版本对不上按冲突拒绝，调用方重试 |
| W 双写 | 一个事务里同时写行业表与交易表 | **交易先、行业跟**：交易提交后调行业写入（按业务键可重复执行）；行业侧失败由扫描补做 | 按交易状态扫描、业务键防重 |

逐个服务的归类与处理随实现写进 §6「事务拆分记录」。

### 2.4 P1 行业库：同构与改名的 18 张

| 旧表 | 新表 | 差异 |
|---|---|---|
| `fnb_table`、`fnb_table_area`、`fnb_course`、`fnb_banquet_plan`、`fnb_banquet_attendance_confirmation`、`fnb_service_entitlement_event` | 同名 | 列相同 |
| `fnb_service_entitlement` | 同名 | 新表多 5 列（来自堂食权益合并，见 §2.5） |
| `fnb_station` | 同名 | 多 1 列 |
| `fnb_kitchen_ticket` | 同名 | 新增 `store_no`、`dispatch_at`、`dispatch_attempts`、`last_error`（来自定时派单合并）；`dispatch_key` 见 §2.5 |
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
| P1-6 | 修改 | 餐饮测试夹具 | 用到行业表的测试改为在 `industry` 数据源上按 `db/n_industry` 建表 |

P1 之后，餐饮只能跑在「`industry` 绑定到按 `n_industry` 建的库」上（同平台基础库换表的前提）。

## §3 选型

| 方案 | 结论 |
|---|---|
| 9 组合并先原样搬进行业库，之后再合并 | ❌ 用户 2026-11-27 定一次到位：现场为空表，合并没有导数成本，分两次反而改两遍代码 |
| 行业库也按「同库不同池 → 逐表换」 | ❌ 行业库内部也有同事务写（出品单、厨显事件、工位），整组一次换 |
| P0 先拆事务与语句，P1 再换表 | ✅ P0 每步都能在旧库上跑和推送；P1 只剩表结构差异 |

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
| AC1 | `FoodSeparatePoolsTest` | 待实现 |
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

## §8 待定

- **Q1 行业为主的命令，永久命令回执放哪。** ✅ 2026-11-27 用户定：**业务键回查**（下面的 ①）。已用于交接与权益消耗，见 §6。原问题： `BusinessCommandJournal` 要求回执与业务写在同一事务里，回执表 `fnb_business_command` 现在在交易句柄上。
  交接、权益、宴会、厨显、桌台编辑这些命令的业务写在行业库，回执就不能和它同事务。备选：
  ① 回执退役，改「公共幂等短期认领 + 业务键回查」：命令的业务单号由请求键确定性派生（如交接单号），重放时按业务单号查回结果（对齐 ADR-068 第 4 条与「业务库不建技术件」）；
  ② 行业库里也放一份回执表（与「业务库不建技术件」冲突）；
  ③ 回执放基础能力库，先认领、后完成，中间崩溃靠业务键回查（①加一张公共表）。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-27 | 方案给出；用户定：写 TDD，先做 P0+P1；P1 的 9 组合并一次到位 |
