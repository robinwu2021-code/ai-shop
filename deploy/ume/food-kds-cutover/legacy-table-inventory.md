# .61 餐饮旧表去向核对

2026-11-27 上海时间，使用 .61 的 MySQL 只读目录核对 `ai_shop_ume`。源库共有 **139** 张 `fnb_*` 基表。对 139 张表在同一个只读一致性快照内逐表执行 `COUNT(*)`，结果均为零，含 `fnb_flyway_history`。在现场 `n_trade`、`n_industry` 查找同名表，得到 **14** 张同名和 **125** 张名称缺口，14 张同名表都在 `n_industry`。查看其他域设计去向时也核对了相应的现场 `n_*` 库。

[逐表 TSV](legacy-table-inventory.tsv) 按源表名排序，每个源表恰有一行。源库统一是 `ai_shop_ume`。仓库依据是 [ADR-068](../../../docs/technical/ADR/ADR-068-门店交易库按耦合簇换表与行业插件公用部分并回.md)、[旧表新表对照](../../../docs/technical/零售域-旧表新表对照.md)、[2026-11-27 定稿调整](../../../docs/technical/零售域-第二段建议方案与表清单.md)及各库表说明和字段设计。涉及不同方向时采用已确认的 ADR 及定稿调整，同时保留 [KDS 已执行 SQL](01-industry-kds.sql) 的现场落点。取证未写入生产库。[剩余 25 张的承接提案](legacy-table-dispositions.md)给出待实施的目标、跨域边界与退役条件。

| `mapping_status` | 全部 | 名称缺口 | 含义 |
| --- | ---: | ---: | --- |
| `KDS_PREPARED` | 6 | 0 | 现场结构准备批次，运行绑定仍待切换。 |
| `DESIGN_TARGET` | 103 | 95 | 设计逐表指明去向。部分表需合并，部分新表尚未在 .61 建立。 |
| `DESIGN_RETIRED` | 4 | 4 | 设计裁定不建原表或旧勘查标记无业务引用。列出替代字段、业务事实或历史的拟承接表，保留是否存在外部读者的验证。 |
| `PROPOSED_TARGET` | 12 | 12 | 选定现有或拟新增的表及承接语义。 |
| `PROPOSED_RETIRED` | 10 | 10 | 建议随旧镜像或预建边缘协议退役。 |
| `PROPOSED_SHARED` | 2 | 2 | 公共幂等与业务唯一键承接，目标域物理回执位置仍待实施确认。 |
| `PROPOSED_SERVICE` | 1 | 1 | 独立审核与工单服务承接，物理库待其 owner 确认。 |
| `TECHNICAL_LEGACY` | 1 | 1 | Flyway 历史与应用启动校验。 |
| 合计 | 139 | 125 | |

## 读取清单

- `group` 和 `owner` 描述业务分组与设计归属或待确认方向。当前读写位置以代码与装配为准。
- `target_schema`、`target_table`、`live_target` 按 `|` 平行拆分。共享能力、服务承接与拟退役行的物理目标字段留空。`live_target` 取 `PRESENT` 或 `ABSENT`，来自现场目录。
- `name_status` 取 `SAME_NAME` 或 `NAME_GAP`，仅使用现场 `n_trade` 和 `n_industry` 的原名判断。例：`ai_shop_ume.fnb_queue_ticket` 是名称缺口，ADR 目标为缺表的 `n_industry.fnb_queue_ticket`，现场旧设计的 `n_trade.ord_queue_ticket` 说明结构版本有差异。
- `catalog_key_evidence` 列出源表非主键唯一索引及列序。存在目标时同时记录同名列数、其中类型及空性均同的列数，以及同列序唯一索引的交集计数。列重命名、默认值、表达式、排序规则、状态语义和代码事务需另行验证。
- `design_evidence` 和 `code_evidence` 中的 `路径#行号` 相对仓库根目录，勘查时基线为 `890ff36a3`。`code_evidence` 列实际 Java/XML 直接表名引用，优先 Mapper 与实体，或退回旧 DDL 和校验。直接匹配未覆盖间接引用、动态 SQL、外部消费者或完整事务调用。
- `blocker` 记录切换前剩余验证、设计工作或现场缺表。源表为空只说明这次无需导入旧行。

## 现场与设计的差异

现场 `n_trade` 共 74 张表，`n_industry` 共 17 张。仓库当前 [交易 DDL](../../../docs/technical/design/schema/ddl/05-n_trade.sql) 与 [行业 DDL](../../../docs/technical/design/schema/ddl/06-n_industry.sql) 则分别为 67 和 28 张。表数表示不同版本的结构范围。既有设计所列目标缺表为 `n_industry.fnb_queue_ticket`、`n_industry.fnb_custody`、`n_industry.fnb_custody_movement`、`n_industry.ind_assignment` 和 `n_platform.guest_session`。其中寄存流水另外承接拟退休的 `fnb_custody_entry`，桌码兼有已存在的 `n_platform.mch_store_code` 去向。新增提案另外列出 7 张现场尚无的表，见承接提案。

- KDS 六表已准备。原字段设计拟把 `fnb_station_runtime` 并入工位、语音命令并入事件。本批 SQL 在 `n_industry` 保留相应独立表及游标，符合现有 `FoodKdsMapper`、`FoodStationRuntimeMapper` 的行结构与事务。表级状态记录本批实际落点。
- 楼面与出品的同名表须按旧 Mapper 校验字段。`FoodMappers.TableMapper`、`TableAreaMapper`、`CourseMapper`、`KitchenTicketMapper`、`ServiceEventMapper`、`QueueTicketMapper`、`BottleCustodyMappers` 及显式装配的 `ConfigRevisionMapper` 已使用共享 `industry` 路由。桌台、票据、服务事件、排队、寄存和配置须检查同名列、旧 SQL 和调用事务，方可随句柄一起切换。
- `FoodBusinessOperationsMapper` 内有桌台、排队和交易表的直接 SQL，`ServiceEntitlementMapper` 仍按未限定名的 `DataSource` 装配。当前 `FoodSchemaValidator` 在主数据源查全套旧结构及有效 Flyway 历史。继续迁移时先明确这些调用、校验和事务管理器的运行边界，保留行业写入与交易发起／确认之间的幂等与恢复。
- `fnb_kitchen_ticket` 留行业库。制作任务 `fnb_kitchen_task` 的设计去向仍是交易库 `ord_execution`，以保留订单批次、营业日关账、退菜与渠道出餐信号的交易耦合。当前事务联动及工位业务唯一键需要单独处理。
- 并入到店、订单、购物车、渠道和资源预约的餐饮表仍使用现有 `fnb_*` 模型。按 ADR-068 把对应买卖逻辑并回交易模块，在同一交易簇上替换 SQL 和模型并演练。仅绑定目标数据源会使旧 SQL 继续查旧表名。
- 15 张库存表及另外 10 张旧表的承接与退役方向见单独提案。库存切换依赖实物账、操作回执和在途恢复的路由就绪。审计、幂等、离线证据和桌台协作依赖各目标 owner 的读写与事务验证。

## 现场目录复核

```sql
SELECT table_schema, table_name, table_type
FROM information_schema.tables
WHERE (table_schema = 'ai_shop_ume' AND LEFT(table_name, 4) = 'fnb_')
   OR LEFT(table_schema, 2) = 'n_'
ORDER BY table_schema, table_name;

SELECT table_schema, table_name, column_name, column_type, is_nullable
FROM information_schema.columns
WHERE (table_schema = 'ai_shop_ume' AND LEFT(table_name, 4) = 'fnb_')
   OR LEFT(table_schema, 2) = 'n_'
ORDER BY table_schema, table_name, ordinal_position;

SELECT table_schema, table_name, index_name, seq_in_index, column_name
FROM information_schema.statistics
WHERE non_unique = 0 AND index_name <> 'PRIMARY'
  AND ((table_schema = 'ai_shop_ume' AND LEFT(table_name, 4) = 'fnb_')
       OR LEFT(table_schema, 2) = 'n_')
ORDER BY table_schema, table_name, index_name, seq_in_index;
```

行数在 `START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY` 内使用目录所得 139 张表各自的 `SELECT COUNT(*)`，不使用 InnoDB 的估计行数。
