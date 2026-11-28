# 美业表迁到行业库：.61 `n_industry` 变更

依据 [TDD-美业域迁移新库](../../../docs/technical/TDD-美业域迁移新库.md) B-6。本批只动 `n_industry` 里**美业的表**与**和餐饮共用的指派表 `ind_assignment`**；门店策略的两张共用表（`ind_store_policy`、`ind_config_revision`）餐饮的变更脚本已改好，这里不动；门店交易库、旧主库 `ai_shop_ume`（已作废）都不动。

## 做法

与[餐饮的变更](../food-industry-cutover/README.md)同一套：只读核对 → 备份 → 执行 → 按期望结构核对。

- 定稿结构 = 美业测试建表脚本 `db/n_industry/V1`（设计 DDL `06-n_industry.sql` 原样）、`V2`（触发器）执行后的结构，也就是 `BeautyIndustryDatabaseIT` 在本机 MySQL 上验证过的那一份。`gen.py` 在一次性库里执行 V1、V2 后逐表 `SHOW CREATE TABLE`，产出建表脚本与期望结构。
- 美业的表任何一张有数据，`run.sh apply` 拒绝执行；空表（或不存在）一律按定稿结构重建。
- 共用指派表 `ind_assignment` 只补美业的两列、对象类型注释加 `TASK`，不重建、不动数据；两列都已有时跳过。它不存在时停止（先跑餐饮的变更）。
- 执行后按期望结构逐表核对列（名、类型、可空、默认值）、唯一键与触发器，不一致即报错并给出备份位置。

| 文件 | 用途 |
|---|---|
| `run.sh` | `check`：只看；`apply`：核对为空 → 备份（美业表 + 指派表连数据，含触发器）→ 执行 02、03、04 → 核对 |
| `02-create-beauty-tables.sql` | 美业在行业库的 12 张表按定稿结构建（已有的先删） |
| `03-alter-shared-table.sql` | `ind_assignment` 补 `employment_assignment_no`、`planned_assignment_no`，改 `target_type` 注释 |
| `04-triggers.sql` | 来源观察、执行围栏、库存意向的 5 个触发器（设计 DDL 不收触发器） |
| `expected-structure.tsv` | 期望的列、唯一键、触发器 |
| `tables.txt` | 美业在行业库的表（取 `BeautyIndustryTables`） |
| `gen.py` | 重新生成以上产物；`--check` 核对是否最新 |

## 变更清单

对照基准：.61 现场（2026-11-28 只读核对）`n_industry` 28 张表，美业只有 `bty_service_step`（空表，按旧设计稿 `status varchar(10)`），没有美业触发器；`ind_assignment` 由餐饮的变更建好、0 行，没有美业两列。

| 表 | 变化 |
|---|---|
| `bty_work_order`、`bty_service_task`、`bty_trade_binding`、`bty_service_submission`、`bty_reception_operation`、`bty_guest_claim_receipt`、`bty_arrival_challenge`、`bty_source_observation`、`bty_source_allocation_guard` | 新建（A6 后留在行业库的美业执行表；工单已含并入的上下文列） |
| `bty_service_execution_fence` | 新建（原美业私有迁移 V10，表结构取服务端规范化写法） |
| `bty_service_execution_stock_intent` | 新建（原美业私有迁移 V11，§2.6 第 11 项从主库搬来） |
| `bty_service_step` | 重建：`status` 由 `varchar(10)` 放宽到 `varchar(24)`（代码用 `IN_PROGRESS`） |
| `ind_assignment` | 补两列、改注释（B-4 服务参与并入） |
| 触发器 | 新建 5 个：`trg_bty_source_observation_domain_guard`、`trg_bsef_immutable`、`trg_bsef_no_delete`、`trg_bsesi_immutable`、`trg_bsesi_no_delete` |

数据：美业旧数据在已作废的 `ai_shop_ume`，不搬（用户 2026-11-28 定，预定义数据与测试数据重新生成）。

**对餐饮变更脚本的影响**：餐饮的 `run.sh check` 把 `ind_assignment` 也列在它的期望结构里，执行本批后会显示这张表多出美业两列（`>` 行）。这是预期的，以本批为准。

## 本机演练（2026-11-28）

一次性库按旧设计稿建（只留 `bty_service_step`，`ind_assignment` 放 1 行数据）：`check` 列出差异 → `apply` 建表、补列、建触发器，核对通过、数据保留 → 再 `check` 无差异 → 再 `apply` 跳过补列、核对仍通过。

## 执行记录

### .61 · 2026-11-28 14:12:25–14:12:28Z（UTC）

- 执行：Claude 会话（用户批准），提交 `7ac7bf71b` 的脚本复制到 .61 `~/deploy/beauty-industry-cutover-7ac7bf71b/`，六个产物 sha256 与仓库一致；root 经容器内的 `mysqlw` / `mysqldumpw` 包装执行（口令不出容器，同餐饮）。
- 执行前：`read_only=0`，没有连接在用 `n_industry`；`check` 差异与上面变更清单一致（缺 11 张表、`bty_service_step` 旧结构、`ind_assignment` 缺两列、无触发器），没有多出的 `>` 行。
- `apply`：美业表全空（只有 `bty_service_step` 0 行）→ 备份 `~/backup/beauty-industry-20261128T141226Z/n_industry.sql`（`bty_service_step` + `ind_assignment` 结构，`ind_assignment` 0 行故无 INSERT）→ 02、03、04 → 核对通过（12 张美业表与 `ind_assignment` 的列、唯一键、触发器与定稿一致）。
- 执行后再 `check`：12 张美业表都 0 行，`ind_assignment` 美业两列 2 列，与定稿无差异。`n_industry` 由 28 张变 39 张。

