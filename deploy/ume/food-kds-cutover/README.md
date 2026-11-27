# 餐饮厨显迁移到行业库

`ADR-068` 将餐饮厨显及工位运行归到 `n_industry`。本批处理工位、运行状态、事件、门店游标、设备订阅和语音命令。两组 JDBC Mapper 与事务管理器已经由 `FoodKdsAutoConfiguration` 装配到 `industry` 句柄。

## 准备与核对

1. 停止这六张表的写入并备份两侧数据。确认目标原有的 `fnb_station`、`fnb_kds_event`、`fnb_kds_subscription` 都是空表。若已有写入，先核对其业务键、来源和订阅请求号。
2. 在目标库执行一次 `01-industry-kds.sql`。它补齐三张运行表、设备订阅的原始授权与撤销请求字段，以及事件、游标和订阅的唯一性约束。
3. 使用仅有源库读取和目标表写入权限的账号运行同步。配置文件使用 MySQL `[client]` 格式。

```sh
python3 deploy/ume/food-kds-cutover/reconcile.py apply --config "$MYSQL_CNF" --source ai_shop_ume --target n_industry
python3 deploy/ume/food-kds-cutover/reconcile.py verify --config "$MYSQL_CNF" --source ai_shop_ume --target n_industry
```

同步逐列核对工位编号、门店事件序号、原授权请求、撤销和语音回执等原始字段。匹配行可重复执行，缺失行补齐，已有不一致时回滚本次同步。目标事件和订阅保留新库自增主键，原业务键保持唯一。

切换共享 `industry` 句柄前要补齐同句柄的其他调用，并核对交易过程触发厨显事件时的事务边界。当前餐饮启动校验仍通过主数据源验证完整旧餐饮结构，桌台、排队等其他行业 Mapper 也使用这条句柄。暂时保持当前宿主的数据源绑定。

## 本地 MySQL 演练

按 2026-11-27 的 .61 实际表结构建隔离库。源与目标在初查时对应的现有表均为零行。用每张表各一条合成数据验证首次新增 6 条、第二次新增 0 条、逐列比对 6 条。修改目标事件后，重复执行检测到冲突，之前尝试写入的工位运行状态也随同回滚。

使用只有目标访问权的账号运行现有 Java Mapper，验证了工位、订阅、事件和游标读取，门店隔离，重复事件拦截，以及事件与游标的事务回滚。

## .61 结构准备记录

2026-11-27 18:54（上海时间），核对 `ai_shop_ume` 的 139 张 `fnb_*` 表均为空。备份旧餐饮表与 `n_industry`，压缩包通过完整性检查。

- 备份目录 `/data0/mysql9/backups/food-kds-20261127T105425Z/`。
- `01-industry-kds.sql` 已在 .61 的 `n_industry` 执行，已核实六张表及五条厨显去重唯一键。
- 现场没有存量行需要导入。应用继续使用原数据源绑定，共享 `industry` 句柄尚未切换。

## 门店换绑检查

换绑时的餐饮历史检查与三个原写入 Mapper 共用 `foodTradeSqlSessionTemplate`，仍检查取消或软删除的历史和仅有导入防重记录的情况。仅更换连接归属，等待交易侧完成以下归并后再更换 SQL 表名。

| 原交易表 | .61 实际目标 | 切换前要保留的事实 |
| --- | --- | --- |
| `fnb_order_batch` | `n_trade.ord_sub_order` | 批次号、按门店防重的请求键、已进入餐饮但尚未接单的批次 |
| `fnb_channel_order_source` | `n_trade.chn_order` | 原渠道来源号、导入号、外部单号和批次绑定 |
| `fnb_channel_import_guard` | `n_trade.chn_order` | 只有准入、撤回或取消记录时也能查得，保留原文档摘要和导入请求唯一性 |

.61 现有两个目标表均无上述原业务键唯一索引。代码测试验证主库无餐饮表时，换绑检查会读取独立交易模板，并拒绝把其他 tenant 或门店的记录算入当前门店。另在本地 MySQL 断开旧库后验证相同行为。移植原查询契约的独立演练库可以查询，使用 .61 实际 `n_trade` 表结构的副本仍因缺少归并后的查询契约而失败。

同一环境直接调用厨显生产服务类，设备授予、语音领取、重复回执和事件重放均通过。完整餐饮启动校验采用当前实现时，交易库缺少 `fnb_flyway_history`，行业库缺少被旧校验要求的 `prd_goods`。后续须由各真实数据源验证其独立契约，再用两个独立本地连接池验证订单、库存和厨显等跨域流程。

## 门店交易库的批次与渠道准入

在隔离的 `n_trade` 副本执行 `02-trade-admission.sql`。它按 .61 旧表真实定义准备 `fnb_order_batch`、`fnb_channel_order_source` 和 `fnb_channel_import_guard`。三张表是餐饮提交、渠道补查与撤回所需的过程记录。该批保留尚未形成正式订单的独立记录，既有的 `ord_sub_order` 和 `chn_order` 继续承担正式订单及渠道订单职责。后续归并这些过程记录时，须保留前置状态、原请求快照和重放约束。

```sh
mysql --defaults-extra-file="$MYSQL_CNF" n_trade < deploy/ume/food-kds-cutover/02-trade-admission.sql
python3 deploy/ume/food-kds-cutover/reconcile.py apply --group trade-admission --config "$MYSQL_CNF" --source ai_shop_ume --target n_trade
python3 deploy/ume/food-kds-cutover/reconcile.py verify --group trade-admission --config "$MYSQL_CNF" --source ai_shop_ume --target n_trade
```

本地使用实际结构，验证待接单批次、已登记渠道来源和仅有撤回凭证的导入记录。首次导入 3 条，重复执行新增 0 条。去掉导入唯一键或改动原状态都会导致校验失败。

这三张过程表已由现有 Trade Mapper 和交易事务管理器读写。上线绑定前还须迁完同一事务内的购物车、堂食会话、订单绑定及明细。旧 SQL 中横跨交易与行业的厨显和服务事件查询也须改用各域接口。当前这批 SQL 只在本地副本执行。

启动校验在独立交易连接检查这三张表的必需列和九条业务唯一键。主库中即使还有同名表，也不能代替交易库校验。完整的 v37 旧结构校验仍用于尚未迁移的部分。

本地 MySQL 的 FoodFirstTradeMapperDatabaseTest 使用正式 MyBatis 扫描和交易事务管理器，在关闭主库连接池后，验证三张表分别写入和查询，并验证三个状态更新在同一事务内回滚。

其余 25 张表的逐项建议见 [落点与退役路径](legacy-table-dispositions.md)，包含拟新增的物理表、归属其他服务的事实和每条退役条件。建议尚未部署。
