# 餐饮后端一次性编码：待开发 API、数据库与代码清单

> 2026-10-29；设计基线 `4886f460b`。本文件将 01～04 的设计收敛为同一批实施台账；新增项均未实现，已有类只列需扩展的职责。详细字段和规则分别见[数据库](./01-数据库审查与设计.md)、[API](./02-API审查与清单.md)、[代码](./03-代码清单与开发方案.md)及[同步方案](./04-可选进销存库存同步方案.md)。本文件对此前“条件新增”的同步业务状态存储给出明确实施选择，实施前仍须核对实际分支是否已新增等价能力。

## 1. 编码范围和交付规则

- 餐饮本地库存承担默认库存权威；可选同步开关按商家门店配置，默认关闭，开启后异步同步到进销存的餐饮来源库存镜像。
- 复用基础商品、SKU、价格、统一试算、共享订单和账号/主体/门店体系。库存权威冻结与同步开关分别管理；切换同步开关不切换订单库存权威。
- 所有范围内业务代码、迁移、权限、客户端、装配、恢复和测试用例在同一编码批次补齐；报表代码放最后。先完成整批编码、编译，再集中执行相关测试；失败后只复测影响范围。
- 支付资金执行、真实外送平台、打印厂商、PMS、前端页面、离线收款和库存，以及进销存反向驱动餐饮不纳入本批。
- 旧订单保持原库存归属，未知结果按原键查回；不修改已执行迁移，不将默认成功 Mock 接入内部订单/库存。

## 2. 数据库待开发清单

### 2.1 已有表的增量

| 编号 | 数据库/对象 | 待开发内容 | 约束 |
| --- | --- | --- | --- |
| DB-01 | FOOD `fnb_stock_balance` | 增加门店级 `tracking_mode,status`，完成既有字段实体/Mapper 与 Schema validator 对齐 | 主体+门店+物料唯一；未配置与零库存区分；不追踪不伪造数量；原余额约束保留。 |
| DB-02 | FOOD `fnb_stock_reservation_item` | 增加 `definition_snapshot,returned_qty`；消费、释放及返还累计更新 | 快照与 hash 对应；返还非负且不超消耗；历史行无真实快照时不得编造。 |
| DB-03 | FOOD `fnb_stock_movement` | 增加 `original_movement_no` 及索引 | 返还关联原消耗；按原流水与叶子分别控制累计量，流水追加不可覆盖。 |
| DB-04 | CORE 创建意图、`ord_industry_order_source`、`ord_channel_preparation` | 增加或复用等价的持久化 `inventory_owner` 和原始引用；同步实体、写入、查询与恢复谓词 | 首次预留之前冻结；保留 `stockAuthority` 原协议；旧值只按原历史事实解释，不按当前开关猜测。 |
| DB-05 | FOOD 现有 `fnb_stock_item/reservation/command` | 补实体、Mapper 和业务约束，无明确缺口不追加重复字段 | SKU 归属、单位、来源版本、计划 hash、长期幂等结果与状态完整。 |
| DB-06 | FOOD `fnb_action_log` | 复用记录启停、映射、调整、重试、对账操作 | 本批不创建 `fnb_stock_sync_audit`，避免重复审计表。 |

### 2.2 本批计划新增表

下表是待开发结构，不是已执行迁移。FOOD 使用餐饮业务库；INVENTORY 使用进销存自己的库。各库沿用自身公共审计、主键、scope、版本及逻辑删除规范。

| 编号 | 表 | 关键字段 | 唯一键 / 查询索引 |
| --- | --- | --- | --- |
| DB-07 | `fnb_stock_sync_config` | `entity_no,store_no,target_system,status,generation,baseline_cursor,version,last_error_code` | 主体+门店+目标唯一；按状态/恢复时间索引。 |
| DB-08 | `fnb_stock_sync_mapping` | `entity_no,store_no,item_no,target_system,target_owner_ref,target_location_ref,target_item_ref,source_unit,target_unit,mapping_revision,status` | 来源门店物料+目标唯一；目标冲突检查；映射变更先停用旧 generation，再建新基线。 |
| DB-09 | `fnb_stock_sync_item_state` | `entity_no,store_no,item_no,source_version,snapshot_hash,updated_at` | 门店物料唯一；所有可同步事实变化同事务推进版本，禁止直接假设余额 version 覆盖物料变化。 |
| DB-10 | `fnb_stock_sync_delivery` | `sync_no,event_no,entity_no,store_no,item_no,generation,source_version,payload_hash,status,attempts,next_attempt_at,last_error_code,ack_ref` | event ID 唯一，来源+generation+版本唯一；门店/状态/重试索引。 |
| DB-11 | `fnb_stock_sync_reconciliation` | `reconciliation_no,entity_no,store_no,generation,request_hash,status,cursor,checked_count,difference_count,started_at,finished_at,last_error_code` | 任务号唯一；scope+幂等键唯一（原键字段按平台规范存储）；按状态/更新时间恢复。 |
| DB-12 | `fnb_stock_sync_difference` | `difference_no,reconciliation_no,item_no,generation,source_version,target_version,difference_type,source_snapshot,target_snapshot,resolution_status` | 对账任务+物料+差异类型唯一；任务+分页 ID 索引。 |
| DB-13 | `inv_food_stock_projection` | 来源主体/门店/物料、目标引用、单位、追踪/启用状态、`on_hand,reserved_qty,source_version,generation,received_at` | 来源主体+门店+物料唯一；数量使用精确小数；镜像与原生余额分别查询，不相加。 |
| DB-14 | `inv_food_stock_sync_receipt` | `source_system,event_no,scope,generation,payload_hash,result,applied_version,received_at` | 来源+event ID 唯一；与镜像更新同事务写入，长期查回。 |
| DB-15 | `inv_food_stock_sync_session` | 来源主体/门店、`generation,status,activated_at,disabled_at` | 来源门店会话唯一；接收与启停屏障串行，旧 generation 拒绝。 |

实施选择：保留 DB-10 为业务同步状态与进销存回执关联，统一 Outbox 负责事件运输；DB-10 不另建一套独立队列调度器。DB-11/12 支撑异步对账进度、差异分页和重启恢复，避免 API 只有内存状态。计划新增共 9 张表；已有 6 张本地库存表继续复用。

同步不得直接修改 `inv_stock_balance` 或伪造进销存过账单。单位转换默认只允许相同基本单位；本批若支持转换，必须有明确持久化的精确转换规则及版本，缺失时拒绝映射，不做隐式转换。

### 2.3 复用与后置结构

桌台、菜品/餐牌、套餐、小料、订单、厨房、退餐、打印和日结复用已有表。通用渠道复用 `cim_*`。报表使用独立报表库已有 `rpt_*` 表，本批先补真实本地库存事实和现有聚合口径；成本未知不按零处理。发现实际结构缺口再补最小增量，不能为待验收功能预造表。

迁移不固定占用 V34 或 V618：编码前检查 FOOD、CORE、INVENTORY 实际最新迁移、其他分支和本地库 Flyway 历史，分配各自可用新版本；迁移、实体、Mapper、Schema validator、测试版本要求必须同批对齐。

## 3. 待开发 API 清单

### 3.1 餐饮本地库存管理：11 项

前缀 `F=/biz/x/food/stores/{storeNo}`；写操作使用 `Idempotency-Key`，主体和操作者来自认证上下文。输入输出详细字段沿 02，不接受客户端指定库存 owner。

| 编号 | 方法与路径（相对 F） | 请求 → 响应 | 代码归属 |
| --- | --- | --- | --- |
| API-01 | POST `/stock-items` | 物料类型、SKU?、名称、单位、默认追踪模式 → 物料号/状态/版本 | StockItemController/Service |
| API-02 | GET `/stock-items` | 分页/关键字/类型/状态 → 物料分页 | 同上 |
| API-03 | GET `/stock-items/{itemNo}` | 路径 → 物料详情 | 同上 |
| API-04 | PUT `/stock-items/{itemNo}` | 名称/状态/默认模式/版本 → 物料号/新版本 | 同上；不普通修改历史基本单位 |
| API-05 | POST `/stock-balances` | 物料/门店模式/初始量/原因 → 余额与原命令回执 | StockBalanceController/Service |
| API-06 | PUT `/stock-balances/{itemNo}/policy` | 模式/状态/版本/原因 → 策略和命令回执 | 同上 |
| API-07 | POST `/stock-balances/{itemNo}:adjust` | delta/版本/原因 → 余额/版本/流水/命令号 | 同上 |
| API-08 | GET `/stock-balances/{itemNo}` | 路径 → 余额、可售数量、单位与状态 | 同上 |
| API-09 | GET `/stock-balances` | 分页/筛选 → 余额分页 | 同上；无阈值配置不提供 lowStock 假结论 |
| API-10 | GET `/stock-movements` | 物料?/动作?/时间/分页 → 流水分页 | 同上 |
| API-11 | GET `/stock-reservations/{reservationNo}` | 路径 → 预留头与各叶计划/消费/释放/返还 | 同上调用 ReservationService |

### 3.2 可选同步及进销存查询：11 项

| 编号 | 方法与路径 | 请求 → 响应 |
| --- | --- | --- |
| API-12 | GET `F/stock-sync/config` | 无 → 开关、状态、generation、版本、积压和错误 |
| API-13 | PUT `F/stock-sync/config` | enabled/版本/原因 → 状态、generation、操作号；可为启用中/关闭中 |
| API-14 | POST `F/stock-sync:preflight` | 目标系统 → 可否启用/问题/映射摘要，只读 |
| API-15 | GET `F/stock-sync/mappings` | 分页/物料?/状态? → 映射分页 |
| API-16 | PUT `F/stock-sync/mappings/{itemNo}` | 目标物料/库位引用、单位、版本 → 映射修订/状态 |
| API-17 | GET `F/stock-sync/deliveries` | 分页/状态/物料/时间 → 同步记录与回执状态 |
| API-18 | POST `F/stock-sync/deliveries/{syncNo}:retry` | 原因? → 原事件投递状态，不换原键 |
| API-19 | POST `F/stock-sync:reconcile` | itemNos? → 对账任务号/状态 |
| API-20 | GET `F/stock-sync/reconciliations/{reconciliationNo}` | page/size → 任务进度及差异分页；同步配置、对账范围和时间水位返回明确 |
| API-21 | GET `/biz/inventory/food-stock-projections` | 授权来源门店、分页、筛选 → 餐饮镜像分页 |
| API-22 | GET `/biz/inventory/food-stock-projections/{sourceItemNo}` | 授权来源门店 → 来源/单位/状态/余额/版本/同步时间 |

### 3.3 内部合同（按本次实际代码对齐）

| 合同 | 方法数量 | 输入 → 输出 | 路径 |
| --- | --- | --- | --- |
| FoodLocalStockInternalApi | 13 | Scope + 来源/原命令/冻结计划/累计目标 → ReservationView、Receipt、Lookup、Availability | `/internal/food/local-stock/*` |
| FoodStockManagementInternalApi | 19 | 可信主体/门店/操作人 + 既有业务 DTO/分页 → 管理、同步和对账原 Service 响应 | `/internal/food/stock-management/*` |
| InventoryFoodStockProjectionInternalApi | 6 | Scope + generation/原事件/hash/快照或映射 → 会话/回执/来源镜像/逐条预检 | `/internal/inventory/food-stock-projection/*` |
| FoodProductionStockInternalApi | 4 | 原生产命令或明确处置的 Reverse → 冻结 owner 或真实生产回执 | `/internal/food-stock/production/*` |

生命周期方法：`getReservation,findReservation,findAvailability,reserve,confirm,consumeSupplement,withdraw,expire,issue,issueDelta,release,returnIssued,findCommand`。镜像方法：`validateMappings,activate,apply,queryReceipt,deactivate,queryProjection`。生产桥接：`owner,consume,query,reverse`。管理方法直接对应 10 项物料/余额业务和 9 项同步/对账业务；外部预留查询使用生命周期合同，不再造一个同义方法。

- Java InternalApi 为内部 URL 和输入输出字段的唯一代码定义；Controller/客户端不重复定义通用 JSON 动作参数。
- 集成部署直调同一 Service；独立部署使用既有令牌、FOOD scope 签名及 InternalHttp。
- 预留查无结果和镜像回执查无结果使用带 nullable 字段的非空响应对象；超时和空 HTTP 响应不等于不存在。
- `validateMappings` 只读预检是建议性结果；实际接收再次核验目标身份/单位，应对预检后目标发生变化。

新增部分退餐接口（共 3 项）：`POST/GET /biz/x/food/stores/{storeNo}/returns/{returnNo}/stock-dispositions`、`POST .../stock-dispositions/{operationNo}:retry`。POST 输入原退餐明细、原制作 START 事件、菜品数量、RESALE/LOSS、原因分类/详情和凭据引用；输出永久处置号、原库存命令号及 PENDING/UNKNOWN/COMPLETED 状态。要求 AFTERSALE 和 STOCK 双权限；不代表退款完成。

独立部署增加 `FoodReturnStockInternalApi` 3 项（apply/list/retry）和 `FoodStockReportInternalApi` 1 项（local-stock-facts）；均采用现有签名 scope。库存内部接口增加原子增量 `issueDelta`，与普通累计 `issue` 区分，防止并行制作丢失一次消耗。

新增 FOOD V36 冻结预留叶追踪模式、FOOD V37 `fnb_return_stock_disposition`。后者独立保存原退餐、原生产事件、数量/处置、请求 hash、冻结库存请求及最终回执，按门店原键唯一，未知结果保留额度并按原键恢复。CORE V621 为原先重复 V607 的未应用资源占用迁移，部署前必须核对目标历史，禁止更改已应用 checksum。

### 3.4 已有接口的待修改部分

已有 C/B 点餐、购物车、餐牌/组合发布、厨房事件、退餐、打印、日结和通用渠道接口继续复用，修改内部库存接线、快照与恢复。B 端结算创建/确认入口若经调用链核对确有缺失，再补与共享结算等价的合同；当前只核实到 B 端结算查询，不能把 C 端路径计为 B 端实现。离线重放和营销 fence 按实际缺陷修改，不预增一套 API。报表已有实时、日报/周期、排行、库存和导出接口，补来源和口径，不重复建同义端点。

## 4. Java 待开发代码清单

统一根包 `ai.neargo.shop`；类名为拟定名称，实施先检查是否已有等价类。B/C/运营 Controller 留在餐饮模块，商家接口复用现有权限和门店范围。

| 编号 | 模块与包 | 新增/修改对象 | 关键方法 / 对应任务 |
| --- | --- | --- | --- |
| CODE-01 | `food-api/dto,service,policy` | `FoodStockContracts`、`FoodStockItemService`、`FoodStockBalanceService`、`FoodStockReservationService`；库存生命周期/返还策略 | 管理 create/update/query/initialize/adjust；内部 reserve/confirm/issue/release/return；数量为 BigDecimal，长期回执重放。 |
| CODE-02 | `food-core/entity,mapper` | 新增 `FnbStockItem/Reservation/ReservationItem/Command/Movement`；扩展已有 `FnbStockBalance` 和 `FoodMappers.StockBalanceMapper`，补其他嵌套 Mapper | 锁定、条件更新、唯一插入、叶级累计及原消耗流水查询；DB-01～05。 |
| CODE-03 | `food-core/service/impl,port` | 三个库存 ServiceImpl、`FoodStockCommandExecutor`、`FoodLocalStockPortImpl` | 原子保存余额/预留/命令/流水、固定物料加锁顺序、长期幂等与查回。 |
| CODE-04 | `food-web/api/biz` | `BizFoodStockItemController`、`BizFoodStockBalanceController` | API-01～11；认证取主体/actor，门店 ACL，禁止 DTO 覆盖实体。 |
| CODE-05 | `shop-base/spi/food`、`food-server/api`、`food-web/foodclient` | `FoodLocalStockPort/InternalApi`、内部库存 Controller、`RemoteFoodStockItemService/BalanceService` 及必要远程生命周期适配 | 12 个内部方法中的本地库存部分；CORE 到独立 FOOD 的客户端放允许的宿主层，不引入模块循环依赖。 |
| CODE-06 | `shop-core/trade` | `StockOwnerRouter`、原创建意图/订单来源/渠道准备实体及 Mapper；改 `IndustryOrderServiceImpl`、`IndustryChannelStockPreparation`、`IndustryFulfillmentServiceImpl`、相关恢复服务 | 在首次预留前冻结 owner；履约/取消/查回沿原 owner；不直接注册第二个全局库存 SPI。 |
| CODE-07 | `food-core/service/impl,policy` 与 `food-commerce/port` | 修改配方、配置发布、组合需求编译、订单权威、厨房消耗、退餐和渠道履约来源类 | 配料定价与库存来源分离，成品/成员/不追踪清晰，原快照不可变，防双扣、超退及旧单重算。 |
| CODE-08 | `food-api`、`food-core/entity,mapper,service/impl` | `FoodStockSyncContracts/Service`、六类 `FnbStockSync*` 实体及对应 Mapper；`FoodStockSyncServiceImpl` | DB-07～12，配置/映射/预检/对账/查询；API-12～20。 |
| CODE-09 | `food-core` 事件与恢复 | `FoodStockSyncEvent/Publisher/Dispatcher/RecoveryService`，接现有 Outbox | baseline、publish、queryReceipt、retry、reconcile、disableBarrier；不新增每门店/每物料 cron。 |
| CODE-10 | `food-web/api/biz,foodclient`、`food-server/api` | `BizFoodStockSyncController`、远程同步管理 Service、内部 Controller | 独立和集成配置一致；异步任务必须落 DB-11/12 并可恢复。 |
| CODE-11 | `shop-base/spi/inventory`、`shop-inventory/entity,mapper,service,api` | `InventoryFoodStockProjectionPort/InternalApi`、三个镜像/回执/会话实体与 Mapper、`FoodStockProjectionServiceImpl`、内部接收和 `BizFoodStockProjectionController` | DB-13～15，API-21/22；activate/apply/deactivate 同 scope 串行；不改原生库存账本。 |
| CODE-12 | `commerce-*`、`food-commerce` | 复用并修正原 Catalog/Availability/Order/Fulfillment/AfterSale/Recovery 服务及 Mock 平台 | 菜单、库存、回调、订单、履约和售后集中贯通；超时/重复/乱序恢复。 |
| CODE-13 | 原业务与共享营销 | 桌台、结算、日结、打印等原服务；`OfflineDraftServiceImpl` 和购物车；`CounterCampaignFence` 相关类 | 仅补实际缺口；已记录失败先定位，离线事务外取事实、事务内复核租约/fence；不预造同义服务。 |
| CODE-14 | `shop-report`、`shop-app/reportbridge`、FOOD facts adapter | 现有 `FoodReportDailyJob`、来源采集、存储、查询、利润投影、导出及库存事实适配 | 报表最后编码：每日汇总独立库、周月聚合、Top N、实时与库存；成本缺失标未知。 |
| CODE-15 | FOOD/CORE/INVENTORY 配置与资源 | Flyway、Schema validator、DataScope 注册、权限/API 清单、自动装配 imports、服务认证与客户端配置 | 所有迁移/字段/DTO/端点/权限一并接线，默认同步 OFF，避免缺 Bean 或跨源事务。 |

测试代码同批准备：本地库存 service/并发/生命周期、跨行业 owner、同期基线与事件、接收幂等/乱序/启停屏障、权限与 HTTP、独立装配及报表事实专项。按用户要求编码后集中执行；不把单测通过代替集成验收。

## 5. 可执行任务顺序和完成条件

- [x] T01 数据库与合同：完成 DB-01～15、CODE-01/05/15 的字段、DTO、权限、内部合同及迁移；冻结兼容规则。
- [x] T02 本地物料与余额：完成 CODE-02～04、API-01～11；初始化和调整产生原子回执与流水。
- [x] T03 生命周期与订单路由：完成 CODE-03/05/06；新餐饮走本地、旧单与其他行业保持原权威，未知结果可恢复。
- [x] T04 营业主链路：完成 CODE-07，补菜品组合、厨房、退餐和订单接线；核对桌台、AA、非支付结算、打印和日结。
- [x] T05 可选进销存同步：完成 CODE-08～11、API-12～22；开启/关闭、初次基线、增量、乱序、对账和进销存镜像查询闭环。
- [x] T06 渠道及既有问题：完成 CODE-12/13；收口 Mock、分次交付、重放与已确认健康故障。
- [x] T07 报表：完成 CODE-14；使用真实本地库存事实，缺成本或费用明确未知。
- [x] T08 编译：JDK 21 下检查受影响完整 reactor，集成与独立 FOOD/INVENTORY/渠道装配；修复编译与契约阻断后记录实际命令及结果。
- [x] T09 本批相关集中测试（真实资金/厂商/前端和生产完整运行不计入）：本地 MySQL、接口、权限、并发、历史兼容和恢复；默认 OFF 无同步依赖、ON 最终一致且不修改进销存原生余额；缺陷定向复测。
- [ ] T10 审查与交付（代码审查、文档及预检已完成；生产发布另记）：整理功能/API/数据库/测试映射、迁移和回退，按授权提交推送及部署；发布版本、健康和关键接口验证独立记录。

本批“编码完成”以所有新增合同有实现、迁移与 Mapper 对齐、管理和独立部署链路接通、恢复与权限齐备且编译成功为界；“可发布”还需 T09/T10 通过，不在编码期间提前标记通过。
