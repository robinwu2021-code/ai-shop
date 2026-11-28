# 餐饮表迁到行业库：.61 `n_industry` 变更

依据 [TDD-餐饮域迁移新库](../../../docs/technical/TDD-餐饮域迁移新库.md) P1-5（AC7）。本批只改 `n_industry` 里的**餐饮表与餐饮在用的行业公用表**；美业表（`bty_*`）、提成规则 `ind_commission_rule`、门店交易库 `n_trade`、旧主库 `ai_shop_ume` 都不动。

## 做法

.61 的 `n_industry` 还没有任何应用写入（行业句柄未切换，见 [厨显迁移记录](../food-kds-cutover/README.md)），餐饮相关表都是空表。所以不逐列 `ALTER`，而是**确认全部为空 → 备份 → 按定稿结构删表重建**：

- 定稿结构 = 测试建表脚本 `db/n_industry/V1`、`V2` 执行后的结构，也就是 `FoodIndustryDatabaseTest` 在本机 MySQL 上验证过的那一份。`gen.py` 在一次性库里执行 V1、V2 后逐表 `SHOW CREATE TABLE`，产出重建脚本与期望结构，两者与代码测过的结构逐字一致。
- 任何一张相关表有数据，`run.sh apply` 拒绝执行，不会覆盖数据。
- 执行后按期望结构逐表核对列（名、类型、可空、默认值）与唯一键，不一致即报错并给出备份位置。

| 文件 | 用途 |
|---|---|
| `run.sh` | `check`：只看，列出现有表、行数与和定稿的差异；`apply`：核对为空 → 备份 → 执行 → 核对 |
| `02-rebuild-food-tables.sql` | 删 3 张退役表；餐饮 27 张表按定稿结构重建（`gen.py` 生成，不要手改） |
| `expected-structure.tsv` | 27 张表的期望列与唯一键（`gen.py` 生成） |
| `tables.txt`、`retired.txt` | 本批涉及的表；来自 `FoodIndustryTables.NAMES` 与退役名单 |
| `gen.py` | 重新生成以上产物；`--check` 核对是否最新 |

## 变更清单

对照基准：.61 现场按设计稿 `06-n_industry.sql`（ai-shop-slim 字段草案）建库，再执行过 [`01-industry-kds.sql`](../food-kds-cutover/01-industry-kds.sql)，共 17 张（2026-11-27 目录核对）。本机按这个基准模拟后逐表比对，结果如下；**以现场 `run.sh check` 的输出为准**。

**公共差异**：设计稿上的表都要改的几列——`created_at`、`updated_at` 改为 `datetime(3)`（`updated_at` 原为 `timestamp`），`created_by`、`updated_by` 放宽到 `varchar(64)`，`version` 改为 `bigint`；数量、人数列改为非空、默认 0。下表「公共差异」即指这些。

### 新建（14 张）

| 表 | 来源 | 依据 |
|---|---|---|
| `ind_store_device` | 平台库设计稿 `mch_store_device` 搬来 | 设备出口 D-5 |
| `ind_assignment` | 交易库设计稿 `ord_assignment` 移来；并入服务交接、交接明细、服务责任 | 合并 ⑦ |
| `fnb_kitchen_event` | 服务事件 `fnb_service_event`，取代厨显事件 | 合并 ⑥、D-1 |
| `fnb_kitchen_task` | 制作任务（设计稿原拟进交易库） | ADR-068 修订一 |
| `fnb_queue_ticket` | 排队号（交易库设计稿移来），补请求防重列与唯一键 | 方案 B3、ADR-068 第 4 条 |
| `fnb_custody`、`fnb_custody_movement` | 寄存（原 `fnb_bottle_custody`、`fnb_bottle_movement` 改名），补流水防重列与唯一键 | P1-2 改名 |
| `fnb_table_occupancy`、`fnb_table_operation` | 占台、换台记录 | ADR-068 修订二 |
| `fnb_reservation`、`fnb_reservation_table` | 订位、订位占桌 | ADR-068 修订二 |
| `fnb_service_slot`、`fnb_capacity_claim`、`fnb_service_calendar` | 时段、容量占用、营业日历 | ADR-068 修订二 |

### 重建：结构有变（11 张）

| 表 | 变化 | 依据 |
|---|---|---|
| `fnb_kds_subscription` | 精简为「哪台厨显看哪些工位」：留 `device_no`（唯一）、`stations`、`version`，加 `tenant_no`；去掉订阅号、登录人、设备版本、到期、状态、开通与撤销的 8 列及两条唯一键 | D-5 |
| `fnb_kitchen_ticket` | 加 `dispatch_key`（派单防重，唯一）、`next_attempt_at`（定时下达的认领与重试）；加出品单号唯一键；公共差异 | 合并 ⑤ |
| `ind_store_policy` | 唯一键改为「租户 + 主体 + 门店 + 行业」，加策略号唯一键；公共差异 | 合并 ④ |
| `ind_config_revision` | 加两条唯一键（策略号 + 修订序号、版本号）；公共差异 | 合并 ②③ |
| `fnb_service_entitlement` | 加权益号、到店单人头两条唯一键；数量非空；公共差异 | 合并 ① |
| `fnb_table_area`、`fnb_table`、`fnb_course`、`fnb_banquet_plan`、`fnb_banquet_attendance_confirmation`、`fnb_service_entitlement_event` | 公共差异 | 定稿 DDL 规则 D5、D6 |

### 重建：结构不变（2 张）

`fnb_station`（`01-industry-kds.sql` 已补齐；开停改写 `runtime_status`，D-2）、`fnb_kds_voice_command`。一并重建，保证与定稿逐字一致。

### 删除（3 张，退役）

| 表 | 原因 |
|---|---|
| `fnb_kds_event` | 厨显不再按门店序号拉事件，改为收刷新后拉当前状态；厨房动作的事实在 `fnb_kitchen_event`（D-1、D-3） |
| `fnb_kds_cursor` | 门店序号游标随之退役（D-3） |
| `fnb_station_runtime` | 开停写在工位上（D-2） |

### 不在本批

- `bty_service_step` 与其余美业表、`ind_commission_rule`：随美业对齐处理。
- `n_platform.mch_store_device`：设计已搬到行业库；若 .61 平台库建过这张表且为空，由平台库负责人决定是否删除。
- `n_trade`：餐饮交易簇随零售簇乙（P2），门店交易库的批次与渠道准入见 [`02-trade-admission.sql`](../food-kds-cutover/02-trade-admission.sql)。
- 旧主库 `ai_shop_ume` 的 139 张 `fnb_*` 不改，切换验收后再按退役计划处理。

## 执行

须用有建表与删表权限的账号（root）；应用账号没有 DDL 权限。`mysql.cnf` 为 MySQL `[client]` 格式。

```sh
# 1. 只看：现有表、行数、与定稿的差异（应与上面的清单一致）
deploy/ume/food-industry-cutover/run.sh check "$MYSQL_CNF" n_industry

# 2. 执行：全部为空才继续；备份到 $BACKUP_DIR（默认 ./backups）后重建并核对
BACKUP_DIR=/data0/mysql9/backups deploy/ume/food-industry-cutover/run.sh apply "$MYSQL_CNF" n_industry

# 3. 再看一次：应输出「无差异」
deploy/ume/food-industry-cutover/run.sh check "$MYSQL_CNF" n_industry
```

可重复执行：表仍为空时再跑一次 `apply` 结果相同。

**回滚**：执行前的表结构在备份目录的 `n_industry.sql`，`mysql n_industry < 备份文件` 即恢复原有的表；本批新建的 14 张表需另行删除。

**之后**：库结构就绪不等于切换。生产把 `industry` 句柄绑到 `n_industry` 前，还要等美业对齐（TDD §1.4）与订单域门店锁换成交易库内的锁（TDD §8-Q3）。配了消息中间件时，平台发件箱须为统一模式，且若用 `SHOP_EVENTS_OUTBOX_TYPES` 覆盖了名单，要补上餐饮后厨的五个事件（D-4）。

## 重新生成与核对

改了 `db/n_industry` 的 V1、V2 或 `FoodIndustryTables` 后：

```sh
python3 deploy/ume/food-industry-cutover/gen.py          # 重新生成
python3 deploy/ume/food-industry-cutover/gen.py --check  # 只核对产物是否最新
```

需要本机 MySQL（默认 `~/.local/opt/mysql/bin/mysql -uroot -h127.0.0.1`，可用 `MYSQL`、`MYSQL_ARGS` 改）。

## 本机演练（2026-11-28，MySQL 9.7.4）

按上面的基准建模拟库（17 张）：

- `check`：列出 16 张相关表、全部 0 行，差异与本清单一致。
- 第一次 `apply`：备份、重建，核对通过（27 张表的列与唯一键与定稿一致，3 张退役表已删）；之后 `check` 无差异。
- 第二次 `apply`：同样通过。
- 往 `ind_store_device` 插一行后 `apply`：列出该表 1 行并拒绝执行，数据保留。
