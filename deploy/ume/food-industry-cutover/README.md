# 餐饮表迁到行业库：.61 `n_industry` 变更

依据 [TDD-餐饮域迁移新库](../../../docs/technical/TDD-餐饮域迁移新库.md) P1-5（AC7）。本批只改 `n_industry` 里的**餐饮表与餐饮在用的行业公用表**；美业表（`bty_*`）、提成规则 `ind_commission_rule`、门店交易库 `n_trade`、旧主库 `ai_shop_ume` 都不动。

## 做法

.61 的 `n_industry` 里餐饮独有的表都是空表（餐饮的行业句柄未切换，见 [厨显迁移记录](../food-kds-cutover/README.md)）。所以这些表不逐列 `ALTER`，而是**确认全部为空 → 备份 → 按定稿结构删表重建**。与美业共用的 `ind_store_policy`、`ind_config_revision` 在 .61 上**已有美业数据**（见下面的现场核对），这两张只逐列改定义、补唯一键，不重建、不动数据：

- 定稿结构 = 测试建表脚本 `db/n_industry/V1`、`V2` 执行后的结构，也就是 `FoodIndustryDatabaseTest` 在本机 MySQL 上验证过的那一份。`gen.py` 在一次性库里执行 V1、V2 后逐表 `SHOW CREATE TABLE`，产出重建脚本与期望结构，两者与代码测过的结构逐字一致。
- 餐饮独有的表任何一张有数据，`run.sh apply` 拒绝执行，不会覆盖数据；共用表连数据一起备份，备份里必须有它们的数据行。
- 执行后按期望结构逐表核对列（名、类型、可空、默认值）与唯一键，不一致即报错并给出备份位置。

| 文件 | 用途 |
|---|---|
| `run.sh` | `check`：只看，列出现有表、行数与和定稿的差异；`apply`：核对为空 → 备份 → 执行 → 核对 |
| `02-rebuild-food-tables.sql` | 删 3 张退役表；餐饮独有的 25 张表按定稿结构重建（`gen.py` 生成，不要手改） |
| `03-alter-shared-tables.sql` | 共用的 2 张表逐列改成定稿定义（`MODIFY`，不加列不删列；`gen.py` 生成）；缺的唯一键由 `run.sh` 补 |
| `expected-structure.tsv` | 27 张表的期望列与唯一键（`gen.py` 生成） |
| `tables.txt`、`retired.txt`、`shared.txt` | 本批涉及的表、退役表、与美业共用只改不重建的表 |
| `gen.py` | 重新生成以上产物；`--check` 核对是否最新 |

## 变更清单

对照基准：.61 现场按设计稿 `06-n_industry.sql`（ai-shop-slim 字段草案）建库，再执行过 [`01-industry-kds.sql`](../food-kds-cutover/01-industry-kds.sql)，共 17 张。2026-11-28 现场只读核对确认如此（见文末），下表即现场的变更。

**公共差异**：凡有公共列的表（除厨显语音指令外的 26 张）都按设计规则——`created_at`、`updated_at` 改为 `datetime(3)` 默认当前时间（`updated_at` 原为 `timestamp`，随更新刷新），`created_by`、`updated_by` 放宽到 `varchar(64)`，`version` 改为 `bigint`；数量、人数列改为非空、默认 0。下表「公共差异」即指这些。

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

### 结构有变（12 张：10 张重建、2 张共用表原地改）

| 表 | 变化 | 依据 |
|---|---|---|
| `fnb_kds_subscription` | 精简为「哪台厨显看哪些工位」：留 `device_no`（唯一）、`stations`、`version`，加 `tenant_no`；去掉订阅号、登录人、设备版本、到期、状态、开通与撤销的 8 列及两条唯一键 | D-5 |
| `fnb_kitchen_ticket` | 加 `dispatch_key`（派单防重，唯一）、`next_attempt_at`（定时下达的认领与重试）；加出品单号唯一键；公共差异 | 合并 ⑤ |
| `ind_store_policy` | **原地改，不重建（有美业数据）**：补「租户 + 主体 + 门店 + 行业」与策略号两条唯一键（现场同名的是普通索引，先删再建成唯一）；公共差异 | 合并 ④ |
| `ind_config_revision` | **原地改，不重建（有美业数据）**：补策略号 + 修订序号、版本号两条唯一键（同上）；`actor_no` 放宽到 64；公共差异 | 合并 ②③ |
| `fnb_service_entitlement` | 加权益号、到店单人头两条唯一键；数量非空；公共差异 | 合并 ① |
| `fnb_table_area`、`fnb_table`、`fnb_course`、`fnb_banquet_plan`、`fnb_banquet_attendance_confirmation`、`fnb_service_entitlement_event` | 公共差异 | 定稿 DDL 规则 D5、D6 |
| `fnb_station` | 审计列放宽回设计（`01-industry-kds.sql` 曾收窄为 32 位、无默认）；业务列保持非空；运行状态注释改为 RUNNING/PAUSED | D-2，公共列对齐 |

### 重建：结构不变（1 张）

`fnb_kds_voice_command`。一并重建，保证与定稿逐字一致。

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

**回滚**：执行前的表结构（以及共用表的数据）在备份目录的 `n_industry.sql`，`mysql n_industry < 备份文件` 即恢复原有的表；本批新建的 14 张表需另行删除。

中途失败可直接重跑 `apply`：每一步都按现场状态判断（重建只针对空表，改列是幂等的，唯一键已对的跳过）。

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
- 共用表放入与现场相同的美业数据后 `apply`：只改列、补唯一键，数据保留，核对通过。

## 现场只读核对（2026-11-28，.61 `n_industry`）

经跳板机在 .61 的 MySQL 容器里执行 `check` 用的同一组目录查询（只读，未改任何东西），结果拿回本机比对：

- 现有 17 张，与基准一致；本批相关的 16 张中，餐饮独有的 14 张全部 0 行。
- **`ind_store_policy`、`ind_config_revision` 各 1 行，是美业数据**：美业门店策略 `BTP261124…` 及其修订（可约 30 天、取消规则），2026-11-24 13:54 由美业写入，`industry_code` 为空。说明 .61 上美业已在写行业库这两张共用表。按「有数据就拒绝」的原规则 `apply` 会停下，于是改成这两张原地改列、补唯一键（新的唯一键与这两行不冲突）。
- 相关表的列与唯一键与本机模拟**逐行一致**（318 行），与定稿的差异 519 行，即上面的清单。

本机按现场复刻（含这两行美业数据）演练：单次 `apply` 核对通过，美业数据保留；重跑、从中途失败处重跑也都通过。

## 现场执行（2026-11-28 11:33–11:35 上海时间，.61 `n_industry`）

用户确认后执行。脚本以提交 `bb245d08b` 的版本传到 .61 的 `~/deploy/food-industry-cutover-bb245d08b/`（逐文件校验和一致），经两个薄包装 `mysqlw`、`mysqldumpw` 在 MySQL 9 容器内以 root 执行（口令不出容器）。

- 执行前确认：实例可写（`read_only=0`、无复制、无从库；容器主机名 `app-mysql-slave` 只是名字），没有会话在用 `n_industry`；`check` 与上午只读核对一致。
- 第一次 `apply`：备份 `~/backup/food-industry-20261128T033355Z/n_industry.sql`（含两张共用表的 2 行美业数据）；重建 25 张、删 3 张退役表、改 2 张共用表的列都完成，但补唯一键的循环只补了 1 条就退出——经容器转发的客户端会读走循环剩下的输入（期望结构文件）。核对报缺 3 条唯一键并停止，库处于「只差这 3 条唯一键」的状态。
- 修 `run.sh`：循环里的查询改为不读标准输入（`< /dev/null`）。本机客户端带 `-e` 时不读标准输入，所以本机演练没暴露。
- 第二次 `apply`：备份 `~/backup/food-industry-20261128T033432Z`；补上其余 3 条唯一键，**核对通过**：27 张表的列与唯一键与定稿一致，3 张退役表已删。
- 之后 `check`：**无差异**。`n_industry` 现有 28 张（餐饮 27 张 + 美业 `bty_service_step`）；两行美业数据保留（门店策略 `BTP261124KRBG7R4Q3Q` 与其修订）。

库结构就绪，但应用的行业句柄仍未绑到 `n_industry`（见上文「之后」）。

