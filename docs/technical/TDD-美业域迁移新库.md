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

待补：逐条处理 §1.1 与 §1.2；接待与开工单按 A6「核过的跨库点」拆成「交易（预约）」与「行业（工单）」两段。

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

保留不并：`bty_work_order`、`bty_service_task`、`bty_service_step`、`bty_trade_binding`、`bty_service_submission`、`bty_reception_operation`。
不在本篇：操作日志（归基础能力库审计）、库存意向（退役，随库存）、预约与资源占用、服务定义（交易库）。

### 2.4 实施步骤

待补。

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

- **Q1 A6 里放不进目标表的 4 组怎么处理。** 读代码后（§2.3）认领回执、到店核验、来源观察与分配仲裁、执行围栏按 A6 原文合并会丢掉防重、并发串行点或库级上限。可选：
  ① 这 4 组保留为独立表，留在行业库（与定稿 DDL 一致）；
  ② 换落点：认领回执归会员营销库（拆库清单原定 `mbr_guest_registration`）、到店核验归账号认证的一次性票据（原定 `auth_ticket`），来源观察与分配仲裁、执行围栏仍独立留在行业库；
  ③ 坚持并表，改成「一表多种行」（如绑定表加行类型承载观察与仲裁行，提交表加围栏列与生成列唯一键）：改动最大，触发器与结构校验要重写。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-28 | 用户定：美业对齐先做行业库部分，交易库部分随交易簇乙与商品领域迁移；行业库部分按 A6 全并 |
| 2026-11-28 | 逐表读代码：A6 的 7 组合并里 3 组可并（工单上下文、服务参与、门店策略），4 组放不进目标表（§2.3、§8-Q1），待定 |
