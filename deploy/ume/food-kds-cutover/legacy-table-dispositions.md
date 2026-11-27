# 餐饮其余 25 张旧表的目标

2026-11-27 实施建议。以下逐表选定承接对象或退役路径，补齐[核对清单](legacy-table-inventory.tsv)。建议依据 [ADR-068](../../../docs/technical/ADR/ADR-068-门店交易库按耦合簇换表与行业插件公用部分并回.md)、[数据库划分清单](../../../docs/technical/design/schema/db-split-manifest.csv)、[库存库表设计](../../../docs/technical/design/数据库设计/库/08-库存库.md)与现有业务实现。新表名和增列均为提案，未应用 DDL。现场 25 张源表均为空，在线调用、配置、在途工作及事务边界仍需核对。

## 库存事实与操作（9 张）

采用 2026-11-26 库存库设计中统一实物账的方向，`n_inventory` 是物料、余额、占用和实物变动的权威。保留餐饮来源身份、门店范围、原始权威和小数数量。先将可信的餐饮库存路由接到库存模块，再停止旧账写入。2026-10-29 的[本地库存方案](../../../docs/technical/餐饮本地库存-需求与切换方案.md)记录当前运行口径和旧订单约束，历史订单仍必须用冻结的原权威查询和恢复。店内简单可售限额与销售锁量继续按交易设计处理。

| 旧表 | 建议目标 | 要保留的语义与切换条件 |
| --- | --- | --- |
| `fnb_stock_item` | `n_inventory.inv_item`、`inv_item_ref` | 物料归货主，外部关联记为餐饮物料号及可选 SKU，保留基础单位、跟踪策略与状态。原料允许无 SKU；按原主体/物料键去重。 |
| `fnb_stock_balance` | `n_inventory.inv_stock_balance`，门店定位用 `inv_location` | 门店先解析到货主及确定的库位。原现存、预留和版本按库存口径对账，保留同一事务锁行与非负约束。新账初始化以经确认的库存事实为准。 |
| `fnb_stock_movement` | `n_inventory.inv_ledger` | 源流水号及命令号＋行号唯一；保留操作类型、前后量、实际增减、原出库或冲销凭证与经办时间。按实际出入库关联单据，流水只追加。 |
| `fnb_stock_reservation` | `n_inventory.inv_reservation` | 增补来源系统、来源编号、版本和冻结计划引用，保留该组合的唯一约束、生命周期、有效期和可重复查回的占用号。 |
| `fnb_stock_reservation_item` | `n_inventory.inv_reservation_line` | 增补 `leaf_ref`、销售组件、阶段、单位，以及分次处置所需的冻结计划引用。计划、已发、已释放数量统一为可承载原精度的 decimal；按占用号＋叶子键约束。现有 `inv_demand_leaf` 可保存计划展开，实际占用进度由占用行承担。 |
| `fnb_stock_command` | 新 `n_inventory.inv_stock_operation` | 保存库存操作号、来源作用域、动作、请求指纹、实际结果或失败原因。将业务操作号及来源键设唯一。与实际余额、占用、流水在库存事务中提交。 `FoodReturnStockServiceImpl` 以原命令号核验已发行的流水证据，必须保留查回接口。提交时有单据的操作关联既有库存单据，建档、释放、无变动等也能返回原操作结果。 |
| `fnb_production_consumption` | 新 `n_inventory.inv_production_operation` | 保留制作、重做、调整、冲销的来源事件号＋用途，及原请求、冻结计划、原/反向操作、调度重试、结果状态。已过账通过实际库存单据和流水引用确认。现有 `inv_demand_plan` 负责物料展开，`inv_document` 负责过账事实；未追踪、无需扣减、未知结果和待冲销仍须能查回。 |
| `fnb_production_consumption_line` | 新 `n_inventory.inv_production_operation_line` | 与头表同事务冻结来源叶子、原料、配方版本、单位、计划与实发、追踪状态，按操作号＋行号唯一。核对发料流水、部分执行及冲销对原行引用。已过账行关联 `inv_document_line`、`inv_ledger`。 |
| `fnb_return_stock_disposition` | 新 `n_inventory.inv_return_disposition` | 按退菜单/行、原消耗和操作号记录请求、实际可售返库/不可售/损耗、结果及可恢复状态；原流水＋累计数量须防超退。以库存查询恢复未知结果。实际返库关联 `inv_document_line` 与 `inv_ledger`；无库存变动处置也须可查。 |

三个运行边界需要分别验收。库存账自己的物料、占用和流水在同一个库存模块事务提交。交易退款和厨房出品通过稳定业务编号调用库存接口，未知结果按原编号查询并可续做。消费与退菜状态应逐一映射到真实库存凭证，只有已过账的操作才改变余额。

## 旧账单向镜像（6 张）

设计划分清单将这六张与餐饮第二套库存账一起标记退役。当前 [可选进销存同步方案](../../../docs/technical/design/餐饮后端统一实施/04-可选进销存库存同步方案.md) 和 `FoodStockSyncDispatchServiceImpl`、`FoodStockReconciliationServiceImpl` 仍有启停、补投、回执与可查询的对账行为。目标是在库存模块提供单一权威视图后退役同域镜像。切换前先替换查询与管理端行为、核对配置是否启用、收敛在途投递和未完成对账；如果确有独立外部消费者，需先由库存 owner 确定其外部投影合同。接收端投影会话或回执无法承担旧发送端的认领、快照和状态字段。

| 旧表 | 建议目标 | 退役条件 |
| --- | --- | --- |
| `fnb_stock_sync_config` | 无新表 | 关闭同步配置入口，逐门店核对最后一代启停回执；消费者现存配置先转换为库存权威读法。 |
| `fnb_stock_sync_mapping` | 无新同步表。独立外部物料编号若仍有效，承接到 `n_inventory.inv_item_ref` | 核验每个外部标识仍被实际读取，并保留作用域。纯 FOOD 到本库存镜像的临时映射随镜像退役。 |
| `fnb_stock_sync_item_state` | 无新表 | 确认最后 generation/source version 的接收结果和旧消费者停用，失效的投递不可回写权威余额。 |
| `fnb_stock_sync_delivery` | 无新表 | 封存或完成在途发送，核对事件号、同代版本及接收回执；停止生产和消费旧事件后关停投递。 |
| `fnb_stock_sync_reconciliation` | 无新表 | 在途对账按原游标完成或显式终结，先替换可查询结果及附属接口。对账任务只校验旧镜像与权威差异。 |
| `fnb_stock_sync_difference` | 无新表 | 随原对账任务封存或到期。在途差异处理须落定；权威库存自身盘点使用库存盘点业务流程。 |

## 审计、幂等和实际协作事实（6 张）

| 旧表 | 建议目标 | 要保留的语义与边界 |
| --- | --- | --- |
| `fnb_action_log` | `n_common.sys_audit_log`，设计名称为 `pay_operation_audit` | 接入统一审计模块写入口，保留原动作号、主体/门店、对象、操作者、时间、前后状态和原因并按平台规则脱敏。生产目录当前有 `sys_audit_log`。审计跨库失败需要按业务编号补写。 |
| `fnb_business_command` | 公共 `RequestIdempotencyPort`，由业务表唯一键兜底。当前实现承载表为 `ai_shop_ume.sys_idempotent` | 保留原命令作用域、请求指纹、操作者及首次返回事实。公共回执表在 `.61` 的目标域尚未建。启用跨库前须按 owner 接入同源事务或采用持久化业务键回查，处理业务已提交但公共回执未写、首次未完成、同键异参三种情况。 |
| `fnb_identity_operation` | 同一公共幂等能力。设备/临时会话的授权结果从平台身份事实查回 | 命令范围保留主体、门店、操作者、动作，业务事实由设备和会话 owner 在本事务以唯一键防重。核对首次响应中仍需重放的凭据，采用专责受约束的查询与有效期。统一审计只承接可脱敏的动作信息。 |
| `fnb_offline_audit` | 新 `n_trade.ord_offline_receipt`、新 `n_platform.mch_store_device_lease`、`n_common.sys_audit_log` | 草稿接单、转正和拒绝回执随草稿业务入 `ord_offline_receipt`；`ACQUIRE` 是设备写入租约授予，原回执及世代、围栏、有效期随设备授权写入 `mch_store_device_lease`；统一审计只收脱敏动作摘要。旧实现用相同请求查回 `result_json`，并保留签名验证、原始摘要、拒绝原因及回执号。回执与各自业务事实同事务，按旧请求业务键和回执号唯一。 |
| `fnb_operation_case` | 独立审核与工单服务的 `ops_work_order`（建议逻辑表名，物理库待该服务定） | 按旧案件号和主体/门店/来源唯一约束留住一个当前工单。保留责任人、证据版本和 OPEN/RECOVERING/RESOLVED；工单协调器调用交易批次与餐饮排队的恢复接口。只有来源权威确认收口后才结案。普通审批 `sys_approval` 与通用定时任务均无法承担现有案件状态与人工责任。 |
| `fnb_table_operation` | 新 `n_trade.ord_visit_operation` | 保留公开查询的操作号、原/目标到店单、占用资源操作回执、期望版本、请求指纹和待恢复进度。到店单/占用的业务操作同交易模块决策，保留待处理唯一键和可重试回执。当前查询用待完成或外部资源操作阻止进一步处理，到店单当前状态和通用幂等记录只覆盖部分语义。 |

`fnb_table_operation` 和 `fnb_offline_audit` 在划分清单分别归为幂等和审计。现行 `CheckServiceImpl#getTableOperation`、`TableOperationMapper#externalOrPendingForCheck` 与 `OfflineDraftServiceImpl#acquire/capture` 表明它们还负担可查询的业务操作和证据。`inv_stock_operation` 同样为可追溯的真实库存操作。新增提案以业务身份承担唯一约束，公共请求幂等继续复用平台能力。

## 预建的边缘旧表（4 张）

| 旧表 | 建议目标 | 依据及退役条件 |
| --- | --- | --- |
| `fnb_edge_lease` | 无新表 | 未发现生产 Java/XML 的表名引用，现场为空，划分清单指定不新建。已运行离线草稿的写入协议由设备租约方案接手。原边缘离线扣库与围栏若立项，另按正式协议设计。 |
| `fnb_external_mapping` | 无新表 | 未发现生产 Java/XML 的表名引用，功能清单标为预建。现行渠道绑定另走 `n_trade.chn_resource_mapping` 的业务路径。旧原型的编号映射只有在发现真实外部消费者时才适用逐条语义对账。 |
| `fnb_integration_exception` | 无新表 | 未发现生产 Java/XML 的表名引用，现场为空。现行业务异常由对应来源 owner 管理。涉及人工案件时复用工单服务的真实责任与恢复语义。 |
| `fnb_sync_batch` | 无新表 | 未发现生产 Java/XML 的表名引用，功能清单标为预建。旧渠道设计确认这是边缘离线恢复批次，渠道任务不承接。现行草稿接单另有签名回执与设备围栏。 |

其余四张预建表在删除旧结构或移除启动要求前，还须核对外部工具、部署任务和直接 SQL 消费者。仅目录为空与仓库文本扫描无法覆盖这些调用。
