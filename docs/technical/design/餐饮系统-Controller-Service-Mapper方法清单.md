# 餐饮系统Controller、Service与Mapper方法清单

> 状态：目标与已实现后端方法对照 · 2026-10-21。按165个目标与实际API逐项登记56个Controller、38个Service及对应Impl、91个持久化接口/DAO；已实现范围以逐项状态及CAT-ALL验收记录为准，未实现目标不等于运行能力。
> 由 `node scripts/gen-food-code-inventory.mjs` 统一生成；输入为[方法用例计划](./schema/food-method-plan.json)、[类清单](./schema/food-module-code-inventory.json)。[API参数](./餐饮系统-API设计.md) · [模块代码清单](./餐饮系统-按业务模块代码清单.md) · [方法机器清单](./schema/food-method-inventory.json)。

## 一、签名及调用边界

Java片段为方法声明，省略import、HTTP Mapping及权限注解（HTTP方法/路径在标题，完整类型在机器清单）；Controller类应按实际方法加对应Mapping与既有权限规则。

Controller仅有路径、Body/Query和协议头；Service签名另加可信主体/当前门店/操作者或会话范围，不带HTTP注解。显式storeNo必须与已有BizContext获准目标匹配；非门店路径由对象归属或获准工单解析范围。entityNo不是客户端Body字段，顾客会话也不能自报替代认证。平台不得伪装商家身份，多店报表额外携带获准门店集合。公开目录与桌码建会话是启动例外：目录由StoreDirectory解析请求店，桌码由唯一哈希查验后得到主体门店，不要求调用方预先伪造BizContext。匿名主体未获权威合同支持时禁止下交易单。

下列Service接口与ServiceImpl具有相同签名；Impl负责事务、对象归属、状态/金额/数量守卫、转换及Port编排。外层沿ApiResponseWrapper，不在Controller返回类型再包一层ApiResult。

Mapper的insert/select/update是继承BaseMapper，不重写空方法；Wrapper只能由Service构建并追加主体、门店或受控父引用，UPDATE必须带版本/状态。物理id仅内部使用，不能将外部业务号传selectById。查询分页返回IPage并由MybatisPages转换PageData。报告DAO使用既有JdbcClient技术栈。

调用图列出一个用例的候选分支及恢复步骤，不表示一次调用必须全执行。create/upsert分支须按API语义新增或受控更新；已发布快照、成交源行与审计事实只追加。方法名不等于数据库已具备并发安全；LOCK必须命中稳定仲裁行，空查询不能锁出钱箱/租约唯一写者。

## 二、按模块的Controller与Service方法

| 模块 | 业务 | 本模块API方法数 |
|---|---|---|
| MOD-01 | 桌台与就餐管理 | 41 |
| MOD-02 | 菜品与餐牌管理 | 45 |
| MOD-03 | 点餐与订单管理 | 19 |
| MOD-04 | 厨房与履约管理 | 14 |
| MOD-05 | 收银与结账管理 | 9 |
| MOD-06 | 退餐与售后管理 | 5 |
| MOD-07 | 打印、通知与设备管理 | 3 |
| MOD-08 | 班次与现金管理 | 4 |
| MOD-09 | 营业日、日结与对账管理 | 5 |
| MOD-10 | 经营报表管理 | 4 |
| MOD-11 | 专项业态管理 | 7 |
| MOD-12 | 第三方接入与离线营业 | 5 |
| MOD-13 | 营业配置与权限治理 | 4 |

### MOD-01 桌台与就餐管理

#### FOOD-API-024 · POST /biz/x/food/stores/{storeNo}/tables

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
TableResultVO createTable(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateTableReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
TableResultVO createTable(String entityNo, String storeNo, String actorNo, CreateTableReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableAreaMapper.selectList`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableAreaMapper.selectScopedByKeyForUpdate`（LOCK）；`FoodMappers.TableMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`StoreDirectoryPort`、`ResourcePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-025 · POST /biz/x/food/tables/{tableNo}:rotate-token

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
RotateTokenTableResultVO rotateTokenTable(@PathVariable("tableNo") String tableNo, @Valid @RequestBody RotateTokenTableReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
RotateTokenTableResultVO rotateTokenTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, RotateTokenTableReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectOne`（READ）；`FoodMappers.TableTokenMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableTokenMapper.insert`（WRITE）；`FoodMappers.TableTokenMapper.update`（WRITE）；`FoodMappers.GuestSessionMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-026 · POST /mp/x/food/sessions

Controller：`ai.neargo.shop.food.api.mp.MpDiningSessionController`

```java
DiningSessionResultVO createSession(@Valid @RequestBody CustomerCreateSessionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningSessionService` / `ai.neargo.shop.food.service.impl.DiningSessionServiceImpl`

```java
DiningSessionResultVO customerCreateSession(CustomerCreateSessionReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.TableTokenMapper.selectValidByTokenHash`（READ）；`FoodMappers.GuestSessionMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 桌码只授予桌级短会话，不自动授予同桌所有台账。 启动会话尚无BizContext，先以不可逆桌码哈希作唯一令牌查验，再从有效记录解析主体/门店；不接受Body自报entityNo。

#### FOOD-API-027 · POST /biz/x/food/stores/{storeNo}/reservations

Controller：`ai.neargo.shop.food.api.biz.BizReservationController`

```java
ReservationResultVO createReservation(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateReservationReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ReservationService` / `ai.neargo.shop.food.service.impl.ReservationServiceImpl`

```java
ReservationResultVO createReservation(String entityNo, String storeNo, String actorNo, CreateReservationReq req, String idempotencyKey);
```

持久化：`FoodMappers.ReservationMapper.selectList`（READ）；`FoodMappers.ReservationMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`、`CashierPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 资源hold与订金/预付款分别恢复；不伪造资源或资金成功。

#### FOOD-API-028 · POST /biz/x/food/reservations/{reservationNo}:cancel

Controller：`ai.neargo.shop.food.api.biz.BizReservationController`

```java
OperationResultVO cancelReservation(@PathVariable("reservationNo") String reservationNo, @Valid @RequestBody CancelReservationReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ReservationService` / `ai.neargo.shop.food.service.impl.ReservationServiceImpl`

```java
OperationResultVO cancelReservation(String entityNo, String currentStoreNo, String reservationNo, String actorNo, CancelReservationReq req, String idempotencyKey);
```

持久化：`FoodMappers.ReservationMapper.selectOne`（READ）；`FoodMappers.ReservationMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`、`AfterSalePort`、`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-029 · POST /biz/x/food/reservations/{reservationNo}:no-show

Controller：`ai.neargo.shop.food.api.biz.BizReservationController`

```java
OperationResultVO noShowReservation(@PathVariable("reservationNo") String reservationNo, @Valid @RequestBody NoShowReservationReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ReservationService` / `ai.neargo.shop.food.service.impl.ReservationServiceImpl`

```java
OperationResultVO noShowReservation(String entityNo, String currentStoreNo, String reservationNo, String actorNo, NoShowReservationReq req, String idempotencyKey);
```

持久化：`FoodMappers.ReservationMapper.selectOne`（READ）；`FoodMappers.ReservationMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`、`AfterSalePort`、`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-030 · POST /biz/x/food/reservations/{reservationNo}:arrive

Controller：`ai.neargo.shop.food.api.biz.BizReservationController`

```java
ArriveReservationResultVO arriveReservation(@PathVariable("reservationNo") String reservationNo, @Valid @RequestBody ArriveReservationReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ReservationService` / `ai.neargo.shop.food.service.impl.ReservationServiceImpl`

```java
ArriveReservationResultVO arriveReservation(String entityNo, String currentStoreNo, String reservationNo, String actorNo, ArriveReservationReq req, String idempotencyKey);
```

持久化：`FoodMappers.ReservationMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.ReservationMapper.update`（WRITE）；`FoodMappers.CheckMapper.insert`（WRITE）；`FoodMappers.SeatMapper.insert`（WRITE）；`FoodMappers.TableOccupancyMapper.insert`（WRITE）；`FoodMappers.TableOperationMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 到店只生成一个台账，新占用失败保留原预约。

#### FOOD-API-031 · POST /biz/x/food/stores/{storeNo}/checks

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
CheckResultVO openCheck(@PathVariable("storeNo") String storeNo, @Valid @RequestBody OpenCheckReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
CheckResultVO openCheck(String entityNo, String storeNo, String actorNo, OpenCheckReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableOccupancyMapper.selectActiveByTableForUpdate`（LOCK）；`FoodMappers.CheckMapper.insert`（WRITE）；`FoodMappers.TableOccupancyMapper.insert`（WRITE）；`FoodMappers.SeatMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-032 · GET /biz/x/food/stores/{storeNo}/checks

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
PageData<CheckVO> listChecks(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListChecksQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
PageData<CheckVO> listChecks(String entityNo, String storeNo, ListChecksQuery query);
```

持久化：`FoodMappers.CheckMapper.selectPage`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-033 · POST /biz/x/food/checks/{checkNo}:transfer

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
OperationResultVO transferCheck(@PathVariable("checkNo") String checkNo, @Valid @RequestBody TransferCheckReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
OperationResultVO transferCheck(String entityNo, String currentStoreNo, String checkNo, String actorNo, TransferCheckReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.SettlementMapper.selectList`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableOccupancyMapper.endOccupancyIfActive`（WRITE）；`FoodMappers.TableOperationMapper.insertOperationIfAbsent`（WRITE）；`FoodMappers.TableOperationMapper.advanceOperationIfVersion`（WRITE）；`FoodMappers.TableMapper.update`（WRITE）；`FoodMappers.CheckMapper.update`（WRITE）；`FoodMappers.TableOccupancyMapper.insert`（WRITE）；`FoodMappers.TableOccupancyMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`、`CashierPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 仅调整桌台占用与服务状态，不移动批次归属、不改结清事实；清台不删除未结应收。

#### FOOD-API-034 · POST /biz/x/food/checks/{checkNo}:merge

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
OperationResultVO mergeCheck(@PathVariable("checkNo") String checkNo, @Valid @RequestBody MergeCheckReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
OperationResultVO mergeCheck(String entityNo, String currentStoreNo, String checkNo, String actorNo, MergeCheckReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.CheckBatchMembershipMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.selectList`（READ）；`FoodMappers.SettlementMapper.selectList`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.selectScopedByKeyForUpdate`（LOCK）；`FoodMappers.TableOperationMapper.insertOperationIfAbsent`（WRITE）；`FoodMappers.TableOperationMapper.advanceOperationIfVersion`（WRITE）；`FoodMappers.CheckBatchMembershipMapper.selectActiveByBatchForUpdate`（LOCK）；`FoodMappers.CheckBatchMembershipMapper.detachMembershipIfActive`（WRITE）；`FoodMappers.CheckMapper.update`（WRITE）；`FoodMappers.TableMapper.update`（WRITE）；`FoodMappers.TableOccupancyMapper.insert`（WRITE）；`FoodMappers.TableOccupancyMapper.update`（WRITE）；`FoodMappers.CheckRelationMapper.insert`（WRITE）；`FoodMappers.CheckBatchMembershipMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`、`CashierPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 锁定批次/台账及资源仲裁者；先占后放；服务归属协作不改核心资金归属。具体动作只写对应变化行。

#### FOOD-API-035 · POST /biz/x/food/checks/{checkNo}:split

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
OperationResultVO splitCheck(@PathVariable("checkNo") String checkNo, @Valid @RequestBody SplitCheckReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
OperationResultVO splitCheck(String entityNo, String currentStoreNo, String checkNo, String actorNo, SplitCheckReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.CheckBatchMembershipMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.selectList`（READ）；`FoodMappers.SettlementMapper.selectList`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.selectScopedByKeyForUpdate`（LOCK）；`FoodMappers.TableOperationMapper.insertOperationIfAbsent`（WRITE）；`FoodMappers.TableOperationMapper.advanceOperationIfVersion`（WRITE）；`FoodMappers.CheckBatchMembershipMapper.selectActiveByBatchForUpdate`（LOCK）；`FoodMappers.CheckBatchMembershipMapper.detachMembershipIfActive`（WRITE）；`FoodMappers.CheckMapper.update`（WRITE）；`FoodMappers.TableMapper.update`（WRITE）；`FoodMappers.TableOccupancyMapper.insert`（WRITE）；`FoodMappers.TableOccupancyMapper.update`（WRITE）；`FoodMappers.CheckRelationMapper.insert`（WRITE）；`FoodMappers.CheckBatchMembershipMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`、`CashierPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 锁定批次/台账及资源仲裁者；先占后放；服务归属协作不改核心资金归属。具体动作只写对应变化行。

#### FOOD-API-036 · POST /biz/x/food/checks/{checkNo}:clear

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
OperationVO clearCheck(@PathVariable("checkNo") String checkNo, @Valid @RequestBody ClearCheckReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
OperationVO clearCheck(String entityNo, String currentStoreNo, String checkNo, String actorNo, ClearCheckReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.SettlementMapper.selectList`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableOccupancyMapper.endOccupancyIfActive`（WRITE）；`FoodMappers.TableOperationMapper.insertOperationIfAbsent`（WRITE）；`FoodMappers.TableOperationMapper.advanceOperationIfVersion`（WRITE）；`FoodMappers.TableMapper.update`（WRITE）；`FoodMappers.CheckMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`、`CashierPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 仅调整桌台占用与服务状态，不移动批次归属、不改结清事实；清台不删除未结应收。

#### FOOD-API-092 · GET /biz/x/food/stores/{storeNo}/table-areas

Controller：`ai.neargo.shop.food.api.biz.BizTableAreaController`

```java
PageData<TableAreaVO> listTableAreas(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListTableAreasQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
PageData<TableAreaVO> listTableAreas(String entityNo, String storeNo, ListTableAreasQuery query);
```

持久化：`FoodMappers.TableAreaMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-093 · POST /biz/x/food/stores/{storeNo}/table-areas

Controller：`ai.neargo.shop.food.api.biz.BizTableAreaController`

```java
TableAreaResultVO createTableArea(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateTableAreaReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
TableAreaResultVO createTableArea(String entityNo, String storeNo, String actorNo, CreateTableAreaReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableAreaMapper.selectList`（READ）；`FoodMappers.TableAreaMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-094 · PUT /biz/x/food/table-areas/{areaNo}

Controller：`ai.neargo.shop.food.api.biz.BizTableAreaController`

```java
TableAreaResultVO saveTableArea(@PathVariable("areaNo") String areaNo, @Valid @RequestBody SaveTableAreaReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
TableAreaResultVO saveTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo, SaveTableAreaReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableAreaMapper.selectOne`（READ）；`FoodMappers.TableAreaMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-095 · POST /biz/x/food/table-areas/{areaNo}:set-status

Controller：`ai.neargo.shop.food.api.biz.BizTableAreaController`

```java
TableAreaResultVO setStatusTableArea(@PathVariable("areaNo") String areaNo, @Valid @RequestBody SetStatusTableAreaReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
TableAreaResultVO setStatusTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo, SetStatusTableAreaReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableAreaMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.TableAreaMapper.selectScopedByKeyForUpdate`（LOCK）；`FoodMappers.TableAreaMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-096 · GET /biz/x/food/stores/{storeNo}/tables

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
PageData<TableVO> listTables(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListTablesQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
PageData<TableVO> listTables(String entityNo, String storeNo, ListTablesQuery query);
```

持久化：`FoodMappers.TableMapper.selectPage`（READ）；`FoodMappers.TableAreaMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-097 · GET /biz/x/food/tables/{tableNo}

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
TableResultVO getTable(@PathVariable("tableNo") String tableNo);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
TableResultVO getTable(String entityNo, String currentStoreNo, String tableNo);
```

持久化：`FoodMappers.TableMapper.selectOne`（READ）；`FoodMappers.TableAreaMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-098 · PUT /biz/x/food/tables/{tableNo}

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
TableResultVO saveTable(@PathVariable("tableNo") String tableNo, @Valid @RequestBody SaveTableReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
TableResultVO saveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, SaveTableReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectOne`（READ）；`FoodMappers.TableAreaMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.TableAreaMapper.selectScopedByKeyForUpdate`（LOCK）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableMapper.updateCapacityIfVersion`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-099 · POST /biz/x/food/tables/{tableNo}:set-status

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
TableResultVO setStatusTable(@PathVariable("tableNo") String tableNo, @Valid @RequestBody SetStatusTableReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
TableResultVO setStatusTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, SetStatusTableReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectOne`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-100 · POST /biz/x/food/tables/{tableNo}:archive

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
OperationResultVO archiveTable(@PathVariable("tableNo") String tableNo, @Valid @RequestBody ArchiveTableReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
OperationResultVO archiveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, ArchiveTableReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectOne`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-101 · POST /biz/x/food/tables/{tableNo}:revoke-token

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
OperationResultVO revokeTokenTable(@PathVariable("tableNo") String tableNo, @Valid @RequestBody RevokeTokenTableReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
OperationResultVO revokeTokenTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, RevokeTokenTableReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectOne`（READ）；`FoodMappers.TableTokenMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableTokenMapper.update`（WRITE）；`FoodMappers.GuestSessionMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-102 · GET /biz/x/food/checks/{checkNo}

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
GetCheckResultVO getCheck(@PathVariable("checkNo") String checkNo);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
GetCheckResultVO getCheck(String entityNo, String currentStoreNo, String checkNo);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.SeatMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-103 · PUT /biz/x/food/checks/{checkNo}/seats

Controller：`ai.neargo.shop.food.api.biz.BizCheckSeatController`

```java
CheckResultVO saveSeat(@PathVariable("checkNo") String checkNo, @Valid @RequestBody SaveSeatReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
CheckResultVO saveSeat(String entityNo, String currentStoreNo, String checkNo, String actorNo, SaveSeatReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.SeatMapper.selectList`（READ）；`FoodMappers.CheckMapper.updatePartySizeIfVersion`（WRITE）；`FoodMappers.TableOccupancyMapper.update`（WRITE）；`FoodMappers.SeatMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-104 · POST /biz/x/food/checks/{checkNo}:combine-tables

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
OperationResultVO combineTablesCheck(@PathVariable("checkNo") String checkNo, @Valid @RequestBody CombineTablesCheckReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
OperationResultVO combineTablesCheck(String entityNo, String currentStoreNo, String checkNo, String actorNo, CombineTablesCheckReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.TableOperationMapper.insertOperationIfAbsent`（WRITE）；`FoodMappers.TableOperationMapper.advanceOperationIfVersion`（WRITE）；`FoodMappers.TableOccupancyMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-105 · POST /biz/x/food/tables/{tableNo}:share

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
CheckResultVO openSharedCheck(@PathVariable("tableNo") String tableNo, @Valid @RequestBody OpenSharedCheckReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
CheckResultVO openSharedCheck(String entityNo, String currentStoreNo, String tableNo, String actorNo, OpenSharedCheckReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectOne`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableOccupancyMapper.selectActiveByTableForUpdate`（LOCK）；`FoodMappers.CheckMapper.insert`（WRITE）；`FoodMappers.TableOccupancyMapper.insert`（WRITE）；`FoodMappers.SeatMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-106 · GET /biz/x/food/table-operations/{operationNo}

Controller：`ai.neargo.shop.food.api.biz.BizTableOperationController`

```java
OperationResultVO getTableOperation(@PathVariable("operationNo") String operationNo);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
OperationResultVO getTableOperation(String entityNo, String currentStoreNo, String operationNo);
```

持久化：`FoodMappers.TableOperationMapper.selectOne`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-107 · GET /biz/x/food/stores/{storeNo}/reservations

Controller：`ai.neargo.shop.food.api.biz.BizReservationController`

```java
PageData<ReservationVO> listReservations(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListReservationsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.ReservationService` / `ai.neargo.shop.food.service.impl.ReservationServiceImpl`

```java
PageData<ReservationVO> listReservations(String entityNo, String storeNo, ListReservationsQuery query);
```

持久化：`FoodMappers.ReservationMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-108 · GET /biz/x/food/checks/{checkNo}/actions

Controller：`ai.neargo.shop.food.api.biz.BizDiningActionLogController`

```java
PageData<ActionLogVO> listActions(@PathVariable("checkNo") String checkNo, @Valid @ModelAttribute ListActionsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.DiningAuditService` / `ai.neargo.shop.food.service.impl.DiningAuditServiceImpl`

```java
PageData<ActionLogVO> listActions(String entityNo, String currentStoreNo, String checkNo, ListActionsQuery query);
```

持久化：`FoodMappers.ActionLogMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-138 · GET /mp/x/food/checks/{checkNo}

Controller：`ai.neargo.shop.food.api.mp.MpCheckController`

```java
CustomerGetCheckResultVO getCheck(@PathVariable("checkNo") String checkNo);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
CustomerGetCheckResultVO customerGetCheck(String entityNo, String currentStoreNo, String checkNo, String authenticatedSessionNo);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.OrderBatchMapper.selectList`（READ）；`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.CoreLineAllocationMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.selectList`（READ）。

跨域：`PaymentQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-139 · POST /mp/x/food/sessions/{sessionNo}:end

Controller：`ai.neargo.shop.food.api.mp.MpDiningSessionController`

```java
OperationResultVO endSession(@PathVariable("sessionNo") String sessionNo, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningSessionService` / `ai.neargo.shop.food.service.impl.DiningSessionServiceImpl`

```java
OperationResultVO customerEndSession(String entityNo, String currentStoreNo, String sessionNo, String authenticatedSessionNo, String actorNo, String idempotencyKey);
```

持久化：`FoodMappers.GuestSessionMapper.selectOne`（READ）；`FoodMappers.GuestSessionMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 结束本次会话，不取消正式订单。

#### FOOD-API-157 · POST /biz/x/food/queue-tickets

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO issue(@Valid @RequestBody IssueReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO issue(String entityNo, String currentStoreNo, String actorNo, IssueReq req, String idempotencyKey);
```

持久化：`FoodMappers.QueueTicketMapper.selectList`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.QueueTicketMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 原排队票与可信当前门店范围。锁定原门店策略及排队票，CAS/持久幂等操作与票状态同事务；排队号不作取餐号，未送通知不宣称成功。

#### FOOD-API-158 · GET /biz/x/food/queue-tickets

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
ListQueueResultVO list(@Valid @ModelAttribute QueueListQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
ListQueueResultVO list(String entityNo, String currentStoreNo, QueueListQuery query);
```

持久化：`FoodMappers.QueueTicketMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 原排队票与可信当前门店范围。仅查持久票据，不补发通知/开台。

#### FOOD-API-159 · GET /biz/x/food/queue-tickets/{ticketNo}

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO get(@PathVariable("ticketNo") String ticketNo);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO get(String entityNo, String currentStoreNo, String ticketNo);
```

持久化：`FoodMappers.QueueTicketMapper.selectOne`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 原排队票与可信当前门店范围。仅查持久票据，不补发通知/开台。

#### FOOD-API-160 · POST /biz/x/food/queue-tickets:call-next

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO callNext(@Valid @RequestBody CallNextReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO callNext(String entityNo, String currentStoreNo, String actorNo, CallNextReq req, String idempotencyKey);
```

持久化：`FoodMappers.QueueTicketMapper.selectList`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.QueueTicketMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 原排队票与可信当前门店范围。锁定原门店策略及排队票，CAS/持久幂等操作与票状态同事务；排队号不作取餐号，未送通知不宣称成功。

#### FOOD-API-161 · POST /biz/x/food/queue-tickets/{ticketNo}:call

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO call(@PathVariable("ticketNo") String ticketNo, @Valid @RequestBody CallReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO change(String entityNo, String currentStoreNo, String ticketNo, String actorNo, CallReq req, String idempotencyKey);
```

持久化：`FoodMappers.QueueTicketMapper.selectOne`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.QueueTicketMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 原排队票与可信当前门店范围。锁定原门店策略及排队票，CAS/持久幂等操作与票状态同事务；排队号不作取餐号，未送通知不宣称成功。

#### FOOD-API-162 · POST /biz/x/food/queue-tickets/{ticketNo}:cancel

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO cancel(@PathVariable("ticketNo") String ticketNo, @Valid @RequestBody CancelReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO change(String entityNo, String currentStoreNo, String ticketNo, String actorNo, CancelReq req, String idempotencyKey);
```

持久化：`FoodMappers.QueueTicketMapper.selectOne`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.QueueTicketMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 原排队票与可信当前门店范围。锁定原门店策略及排队票，CAS/持久幂等操作与票状态同事务；排队号不作取餐号，未送通知不宣称成功。

#### FOOD-API-163 · POST /biz/x/food/queue-tickets/{ticketNo}:no-show

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO noShow(@PathVariable("ticketNo") String ticketNo, @Valid @RequestBody NoShowReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO change(String entityNo, String currentStoreNo, String ticketNo, String actorNo, NoShowReq req, String idempotencyKey);
```

持久化：`FoodMappers.QueueTicketMapper.selectOne`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.QueueTicketMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 原排队票与可信当前门店范围。锁定原门店策略及排队票，CAS/持久幂等操作与票状态同事务；排队号不作取餐号，未送通知不宣称成功。

#### FOOD-API-164 · POST /biz/x/food/queue-tickets/{ticketNo}:seat

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO seat(@PathVariable("ticketNo") String ticketNo, @Valid @RequestBody QueueSeatReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO seat(String entityNo, String currentStoreNo, String ticketNo, String actorNo, QueueSeatReq req, String idempotencyKey);
```

持久化：`FoodMappers.QueueTicketMapper.selectOne`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.QueueTicketMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 新就座锁定原票后，在同一food primary事务内调用CheckService.openQueuedCheck，仅限内部Java调用，不能经HTTP/remote；原开台、SEATED、queue动作与outbox同提交。冲突整体回滚，原CALLED票可重选；不把永久SEATING作为新流程。

#### FOOD-API-165 · POST /biz/x/food/queue-tickets/{ticketNo}:recover-seating

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO recover(@PathVariable("ticketNo") String ticketNo, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO recoverSeating(String entityNo, String currentStoreNo, String ticketNo, String actorNo, String idempotencyKey);
```

持久化：`FoodMappers.QueueTicketMapper.selectOne`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.QueueTicketMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 旧SEATING仅兼容原冻结命令/OPEN_CHECK原键回执恢复；未知保持未知，不创建替代开台。新seat走同primary原子事务。

### MOD-02 菜品与餐牌管理

#### FOOD-API-004 · POST /biz/x/food/stores/{storeNo}/menus

Controller：`ai.neargo.shop.food.api.biz.BizMenuController`

```java
CreateBundleResultVOVO createMenu(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateMenuReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.MenuService` / `ai.neargo.shop.food.service.impl.MenuServiceImpl`

```java
CreateBundleResultVOVO createMenu(String entityNo, String storeNo, String actorNo, CreateMenuReq req, String idempotencyKey);
```

持久化：`FoodMappers.MenuMapper.selectList`（READ）；`FoodMappers.MenuMapper.insert`（WRITE）；`FoodMappers.MenuStoreMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-005 · GET /biz/x/food/stores/{storeNo}/menus/{configNo}

Controller：`ai.neargo.shop.food.api.biz.BizMenuController`

```java
MenuConfigResultVO getMenu(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo);
```

Service / Impl：`ai.neargo.shop.food.service.MenuService` / `ai.neargo.shop.food.service.impl.MenuServiceImpl`

```java
MenuConfigResultVO getMenu(String entityNo, String storeNo, String configNo);
```

持久化：`FoodMappers.MenuMapper.selectOne`（READ）；`FoodMappers.MenuStoreMapper.selectList`（READ）；`FoodMappers.MenuContextMapper.selectList`（READ）；`FoodMappers.MenuScheduleMapper.selectList`（READ）；`FoodMappers.MenuExceptionMapper.selectList`（READ）；`FoodMappers.MenuCategoryMapper.selectList`（READ）；`FoodMappers.MenuItemMapper.selectList`（READ）；`FoodMappers.MenuItemSkuMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-006 · POST /biz/x/food/stores/{storeNo}/menus/{configNo}:publish

Controller：`ai.neargo.shop.food.api.biz.BizConfigRevisionController`

```java
PublishConfigResultVO publishMenu(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo, @Valid @RequestBody PublishConfigReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ConfigRevisionService` / `ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl`

```java
PublishConfigResultVO publishMenu(String entityNo, String storeNo, String configNo, String actorNo, PublishConfigReq req, String idempotencyKey);
```

持久化：`FoodMappers.MenuMapper.selectOne`（READ）；`FoodMappers.MenuStoreMapper.selectList`（READ）；`FoodMappers.MenuContextMapper.selectList`（READ）；`FoodMappers.MenuScheduleMapper.selectList`（READ）；`FoodMappers.MenuExceptionMapper.selectList`（READ）；`FoodMappers.MenuCategoryMapper.selectList`（READ）；`FoodMappers.MenuItemMapper.selectList`（READ）；`FoodMappers.MenuItemSkuMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.insertImmutableRevision`（WRITE）；`FoodMappers.MenuMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsCatalogQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 由所属资源Service校验，调用ConfigRevisionService发布；冻结子定义和依赖版本，短事务CAS头。

#### FOOD-API-007 · POST /biz/x/food/stores/{storeNo}/option-groups

Controller：`ai.neargo.shop.food.api.biz.BizOptionGroupController`

```java
CreateBundleResultVOVO createOptionGroup(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateOptionGroupReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.OptionGroupService` / `ai.neargo.shop.food.service.impl.OptionGroupServiceImpl`

```java
CreateBundleResultVOVO createOptionGroup(String entityNo, String storeNo, String actorNo, CreateOptionGroupReq req, String idempotencyKey);
```

持久化：`FoodMappers.OptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-008 · GET /biz/x/food/stores/{storeNo}/option-groups/{configNo}

Controller：`ai.neargo.shop.food.api.biz.BizOptionGroupController`

```java
ConfigResultVO getOptionGroup(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo);
```

Service / Impl：`ai.neargo.shop.food.service.OptionGroupService` / `ai.neargo.shop.food.service.impl.OptionGroupServiceImpl`

```java
ConfigResultVO getOptionGroup(String entityNo, String storeNo, String configNo);
```

持久化：`FoodMappers.OptionGroupMapper.selectOne`（READ）；`FoodMappers.OptionGroupItemMapper.selectList`（READ）；`FoodMappers.DishOptionGroupMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-009 · POST /biz/x/food/stores/{storeNo}/option-groups/{configNo}:publish

Controller：`ai.neargo.shop.food.api.biz.BizOptionGroupController`

```java
ConfigPublicationVO publishOptionGroup(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo, @Valid @RequestBody PublishConfigReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.OptionGroupService` / `ai.neargo.shop.food.service.impl.OptionGroupServiceImpl`

```java
ConfigPublicationVO publishOptionGroup(String entityNo, String storeNo, String configNo, String actorNo, PublishConfigReq req, String idempotencyKey);
```

持久化：`FoodMappers.OptionGroupMapper.selectOne`（READ）；`FoodMappers.OptionGroupItemMapper.selectList`（READ）；`FoodMappers.DishOptionGroupMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.insertImmutableRevision`（WRITE）；`FoodMappers.OptionGroupMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 由所属资源Service校验，调用ConfigRevisionService发布；冻结子定义和依赖版本，短事务CAS头。

#### FOOD-API-010 · POST /biz/x/food/stores/{storeNo}/bundles

Controller：`ai.neargo.shop.food.api.biz.BizBundleController`

```java
CreateBundleResultVOVO createBundle(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateBundleReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BundleService` / `ai.neargo.shop.food.service.impl.BundleServiceImpl`

```java
CreateBundleResultVOVO createBundle(String entityNo, String storeNo, String actorNo, CreateBundleReq req, String idempotencyKey);
```

持久化：`FoodMappers.BundleMapper.selectList`（READ）；`FoodMappers.BundleMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-011 · GET /biz/x/food/stores/{storeNo}/bundles/{configNo}

Controller：`ai.neargo.shop.food.api.biz.BizBundleController`

```java
GetBundleVOVO getBundle(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo);
```

Service / Impl：`ai.neargo.shop.food.service.BundleService` / `ai.neargo.shop.food.service.impl.BundleServiceImpl`

```java
GetBundleVOVO getBundle(String entityNo, String storeNo, String configNo);
```

持久化：`FoodMappers.BundleMapper.selectOne`（READ）；`FoodMappers.BundleGroupMapper.selectList`（READ）；`FoodMappers.BundleChoiceMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-012 · POST /biz/x/food/stores/{storeNo}/bundles/{configNo}:publish

Controller：`ai.neargo.shop.food.api.biz.BizBundleController`

```java
ConfigPublicationVO publishBundle(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo, @Valid @RequestBody PublishConfigReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BundleService` / `ai.neargo.shop.food.service.impl.BundleServiceImpl`

```java
ConfigPublicationVO publishBundle(String entityNo, String storeNo, String configNo, String actorNo, PublishConfigReq req, String idempotencyKey);
```

持久化：`FoodMappers.BundleMapper.selectOne`（READ）；`FoodMappers.BundleGroupMapper.selectList`（READ）；`FoodMappers.BundleChoiceMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.insertImmutableRevision`（WRITE）；`FoodMappers.BundleMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 由所属资源Service校验，调用ConfigRevisionService发布；冻结子定义和依赖版本，短事务CAS头。

#### FOOD-API-013 · POST /biz/x/food/stores/{storeNo}/stations

Controller：`ai.neargo.shop.food.api.biz.BizStationController`

```java
StationContracts.StationMutationVO createStation(@PathVariable("storeNo") String storeNo, @Valid @RequestBody StationContracts.CreateStationReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.StationService` / `ai.neargo.shop.food.service.impl.StationServiceImpl`

```java
StationContracts.StationMutationVO createStation(String entityNo, String storeNo, String actorNo, StationContracts.CreateStationReq req, String idempotencyKey);
```

持久化：`FoodMappers.StationMapper.selectList`（READ）；`FoodMappers.StationMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-014 · GET /biz/x/food/stores/{storeNo}/stations/{configNo}

Controller：`ai.neargo.shop.food.api.biz.BizStationController`

```java
StationContracts.GetStationVO getStation(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo);
```

Service / Impl：`ai.neargo.shop.food.service.StationService` / `ai.neargo.shop.food.service.impl.StationServiceImpl`

```java
StationContracts.GetStationVO getStation(String entityNo, String storeNo, String configNo);
```

持久化：`FoodMappers.StationMapper.selectOne`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-015 · POST /biz/x/food/stores/{storeNo}/stations/{configNo}:publish

Controller：`ai.neargo.shop.food.api.biz.BizConfigRevisionController`

```java
PublishConfigResultVO publishStation(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo, @Valid @RequestBody PublishConfigReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ConfigRevisionService` / `ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl`

```java
PublishConfigResultVO publishStation(String entityNo, String storeNo, String configNo, String actorNo, PublishConfigReq req, String idempotencyKey);
```

持久化：`FoodMappers.StationMapper.selectOne`（READ）；`FoodMappers.SkuStationMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.insertImmutableRevision`（WRITE）；`FoodMappers.StationMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsCatalogQueryPort`。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 由所属资源Service校验，调用ConfigRevisionService发布；冻结子定义和依赖版本，短事务CAS头。

#### FOOD-API-016 · PUT /biz/x/food/stores/{storeNo}/dishes/{goodsNo}

Controller：`ai.neargo.shop.food.api.biz.BizDishController`

```java
DishResultVO saveDish(@PathVariable("storeNo") String storeNo, @PathVariable("goodsNo") String goodsNo, @Valid @RequestBody SaveDishReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DishService` / `ai.neargo.shop.food.service.impl.DishServiceImpl`

```java
DishResultVO saveDish(String entityNo, String storeNo, String goodsNo, String actorNo, SaveDishReq req, String idempotencyKey);
```

持久化：`FoodMappers.DishMapper.selectOne`（READ）；`FoodMappers.DishMapper.insert`（WRITE）；`FoodMappers.DishMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsCatalogQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-017 · PUT /biz/x/food/menus/{menuNo}/definition

Controller：`ai.neargo.shop.food.api.biz.BizMenuController`

```java
Object replaceDefinition(@PathVariable("menuNo") String menuNo, @Valid @RequestBody MenuDefinitionCommand req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.MenuService` / `ai.neargo.shop.food.service.impl.MenuServiceImpl`

```java
Object replaceDefinition(String entityNo, String currentStoreNo, String menuNo, String actorNo, MenuDefinitionCommand req, String idempotencyKey);
```

持久化：`FoodMappers.MenuMapper.selectOne`（READ）；`FoodMappers.MenuStoreMapper.selectList`（READ）；`FoodMappers.MenuContextMapper.selectList`（READ）；`FoodMappers.MenuScheduleMapper.selectList`（READ）；`FoodMappers.MenuExceptionMapper.selectList`（READ）；`FoodMappers.MenuCategoryMapper.selectList`（READ）；`FoodMappers.MenuItemMapper.selectList`（READ）；`FoodMappers.MenuItemSkuMapper.selectList`（READ）；`FoodMappers.MenuItemOptionGroupMapper.selectList`（READ）；`FoodMappers.MenuMapper.update`（WRITE）；`FoodMappers.MenuStoreMapper.insert`（WRITE）；`FoodMappers.MenuStoreMapper.update`（WRITE）；`FoodMappers.MenuContextMapper.insert`（WRITE）；`FoodMappers.MenuContextMapper.update`（WRITE）；`FoodMappers.MenuScheduleMapper.insert`（WRITE）；`FoodMappers.MenuScheduleMapper.update`（WRITE）；`FoodMappers.MenuExceptionMapper.insert`（WRITE）；`FoodMappers.MenuExceptionMapper.update`（WRITE）；`FoodMappers.MenuCategoryMapper.insert`（WRITE）；`FoodMappers.MenuCategoryMapper.update`（WRITE）；`FoodMappers.MenuItemMapper.insert`（WRITE）；`FoodMappers.MenuItemMapper.update`（WRITE）；`FoodMappers.MenuItemSkuMapper.insert`（WRITE）；`FoodMappers.MenuItemSkuMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）；`FoodMappers.MenuItemOptionGroupMapper.update`（WRITE）。

跨域：`GoodsCatalogQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 ALL显式SCHEDULE/COMPOSITION/CONTEXT分支；原身份在事务外，CAS锁内重捕获；只替换本分段，不改发布历史

#### FOOD-API-018 · PUT /biz/x/food/bundles/{skuNo}/definition

Controller：`ai.neargo.shop.food.api.biz.BizBundleController`

```java
BundleMutationVOVO saveBundleDefinition(@PathVariable("skuNo") String skuNo, @Valid @RequestBody SaveBundleDefinitionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BundleService` / `ai.neargo.shop.food.service.impl.BundleServiceImpl`

```java
BundleMutationVOVO saveBundleDefinition(String entityNo, String currentStoreNo, String skuNo, String actorNo, SaveBundleDefinitionReq req, String idempotencyKey);
```

持久化：`FoodMappers.BundleMapper.selectOne`（READ）；`FoodMappers.BundleGroupMapper.selectList`（READ）；`FoodMappers.BundleChoiceMapper.selectList`（READ）；`FoodMappers.BundleMapper.update`（WRITE）；`FoodMappers.BundleGroupMapper.insert`（WRITE）；`FoodMappers.BundleGroupMapper.update`（WRITE）；`FoodMappers.BundleChoiceMapper.insert`（WRITE）；`FoodMappers.BundleChoiceMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-019 · PUT /biz/x/food/option-groups/{groupNo}/items

Controller：`ai.neargo.shop.food.api.biz.BizOptionGroupController`

```java
BundleMutationVOVO saveItem(@PathVariable("groupNo") String groupNo, @Valid @RequestBody SaveItemReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.OptionGroupService` / `ai.neargo.shop.food.service.impl.OptionGroupServiceImpl`

```java
BundleMutationVOVO saveItem(String entityNo, String currentStoreNo, String groupNo, String actorNo, SaveItemReq req, String idempotencyKey);
```

持久化：`FoodMappers.OptionGroupMapper.selectOne`（READ）；`FoodMappers.OptionGroupItemMapper.selectList`（READ）；`FoodMappers.DishOptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupMapper.update`（WRITE）；`FoodMappers.OptionGroupItemMapper.insert`（WRITE）；`FoodMappers.OptionGroupItemMapper.update`（WRITE）；`FoodMappers.DishOptionGroupMapper.insert`（WRITE）；`FoodMappers.DishOptionGroupMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-020 · PUT /biz/x/food/modifiers/{modifierNo}

Controller：`ai.neargo.shop.food.api.biz.BizModifierController`

```java
BundleMutationVOVO saveModifier(@PathVariable("modifierNo") String modifierNo, @Valid @RequestBody SaveModifierReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ModifierService` / `ai.neargo.shop.food.service.impl.ModifierServiceImpl`

```java
BundleMutationVOVO saveModifier(String entityNo, String currentStoreNo, String modifierNo, String actorNo, SaveModifierReq req, String idempotencyKey);
```

持久化：`FoodMappers.ModifierMapper.selectOne`（READ）；`FoodMappers.ModifierComponentMapper.selectList`（READ）；`FoodMappers.ModifierMapper.update`（WRITE）；`FoodMappers.ModifierComponentMapper.insert`（WRITE）；`FoodMappers.ModifierComponentMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-021 · PUT /biz/x/food/stores/{storeNo}/sku-routes/{skuNo}

Controller：`ai.neargo.shop.food.api.biz.BizStationController`

```java
OperationVO replaceRoutes(@PathVariable("storeNo") String storeNo, @PathVariable("skuNo") String skuNo, @Valid @RequestBody StationContracts.ReplaceStationRoutesReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.StationService` / `ai.neargo.shop.food.service.impl.StationServiceImpl`

```java
OperationVO replaceRoutes(String entityNo, String storeNo, String skuNo, String actorNo, StationContracts.ReplaceStationRoutesReq req, String idempotencyKey);
```

持久化：`FoodMappers.SkuStationMapper.selectOne`（READ）；`FoodMappers.StationMapper.selectList`（READ）；`FoodMappers.SkuStationMapper.insert`（WRITE）；`FoodMappers.SkuStationMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsCatalogQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-022 · POST /biz/x/food/stores/{storeNo}/sales-restrictions

Controller：`ai.neargo.shop.food.api.biz.BizSalesRestrictionController`

```java
SalesRestrictionVO manage(@PathVariable("storeNo") String storeNo, @Valid @RequestBody SalesRestrictionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.SalesRestrictionService` / `ai.neargo.shop.food.service.impl.SalesRestrictionServiceImpl`

```java
SalesRestrictionVO manage(String entityNo, String storeNo, String actorNo, SalesRestrictionReq req, String idempotencyKey);
```

持久化：`FoodMappers.SalesRestrictionMapper.selectList`（READ）；`FoodMappers.SalesRestrictionMapper.insert`（WRITE）；`FoodMappers.SalesRestrictionMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 CLEAR只解除指定限制；并发覆盖规则串行化，不更新实物库存。

#### FOOD-API-023 · GET /mp/x/food/catalog

Controller：`ai.neargo.shop.food.api.mp.MpCatalogController`

```java
CatalogVO read(@Valid @ModelAttribute CustomerReadQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.CatalogService` / `ai.neargo.shop.food.service.impl.CatalogServiceImpl`

```java
CatalogVO publicCatalog(String authenticatedSessionNo, CustomerReadQuery query);
```

持久化：`FoodMappers.MenuMapper.selectList`（READ）；`FoodMappers.MenuStoreMapper.selectList`（READ）；`FoodMappers.MenuContextMapper.selectList`（READ）；`FoodMappers.MenuScheduleMapper.selectList`（READ）；`FoodMappers.MenuExceptionMapper.selectList`（READ）；`FoodMappers.MenuCategoryMapper.selectList`（READ）；`FoodMappers.MenuItemMapper.selectList`（READ）；`FoodMappers.MenuItemSkuMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）；`FoodMappers.DishMapper.selectList`（READ）；`FoodMappers.OptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupItemMapper.selectList`（READ）；`FoodMappers.ModifierMapper.selectList`（READ）；`FoodMappers.ModifierComponentMapper.selectList`（READ）；`FoodMappers.SalesRestrictionMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectEffectiveByConfigAndTime`（READ）。

跨域：`StoreDirectoryPort`、`GoodsCatalogQueryPort`、`DishOfferQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 已发布冻结定义及服务器当前时点，读取当前原价格及PLATFORM物理可售；UNKNOWN/null不伪造值；复杂配置需报价且公开输出不含渠道账号/价格策略

#### FOOD-API-109 · GET /biz/x/food/stores/{storeNo}/menus

Controller：`ai.neargo.shop.food.api.biz.BizMenuController`

```java
PageData<MenuVO> listMenus(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListMenusQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.MenuService` / `ai.neargo.shop.food.service.impl.MenuServiceImpl`

```java
PageData<MenuVO> listMenus(String entityNo, String storeNo, ListMenusQuery query);
```

持久化：`FoodMappers.MenuMapper.selectPage`（READ）；`FoodMappers.MenuStoreMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-110 · PUT /biz/x/food/menus/{menuNo}

Controller：`ai.neargo.shop.food.api.biz.BizMenuController`

```java
MenuResultVO saveMenu(@PathVariable("menuNo") String menuNo, @Valid @RequestBody SaveMenuReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.MenuService` / `ai.neargo.shop.food.service.impl.MenuServiceImpl`

```java
MenuResultVO saveMenu(String entityNo, String currentStoreNo, String menuNo, String actorNo, SaveMenuReq req, String idempotencyKey);
```

持久化：`FoodMappers.MenuMapper.selectOne`（READ）；`FoodMappers.MenuMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-111 · POST /biz/x/food/menus/{menuNo}:disable

Controller：`ai.neargo.shop.food.api.biz.BizMenuController`

```java
MenuResultVO disableMenu(@PathVariable("menuNo") String menuNo, @Valid @RequestBody DisableMenuReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.MenuService` / `ai.neargo.shop.food.service.impl.MenuServiceImpl`

```java
MenuResultVO disableMenu(String entityNo, String currentStoreNo, String menuNo, String actorNo, DisableMenuReq req, String idempotencyKey);
```

持久化：`FoodMappers.MenuMapper.selectOne`（READ）；`FoodMappers.MenuMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-112 · GET /biz/x/food/stores/{storeNo}/dishes

Controller：`ai.neargo.shop.food.api.biz.BizDishController`

```java
PageData<DishCatalogItemVO> listDishes(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListDishesQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.DishService` / `ai.neargo.shop.food.service.impl.DishServiceImpl`

```java
PageData<DishCatalogItemVO> listDishes(String entityNo, String storeNo, ListDishesQuery query);
```

持久化：`FoodMappers.DishMapper.selectPage`（READ）；`FoodMappers.DishOptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupItemMapper.selectList`（READ）。

跨域：`GoodsCatalogQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-113 · GET /biz/x/food/stores/{storeNo}/dishes/{goodsNo}

Controller：`ai.neargo.shop.food.api.biz.BizDishController`

```java
GetDishResultVO getDish(@PathVariable("storeNo") String storeNo, @PathVariable("goodsNo") String goodsNo);
```

Service / Impl：`ai.neargo.shop.food.service.DishService` / `ai.neargo.shop.food.service.impl.DishServiceImpl`

```java
GetDishResultVO getDish(String entityNo, String storeNo, String goodsNo);
```

持久化：`FoodMappers.DishMapper.selectOne`（READ）；`FoodMappers.DishOptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupItemMapper.selectList`（READ）。

跨域：`GoodsCatalogQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-114 · GET /biz/x/food/stores/{storeNo}/catalog

Controller：`ai.neargo.shop.food.api.biz.BizCatalogController`

```java
CatalogVO read(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute CatalogQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.CatalogService` / `ai.neargo.shop.food.service.impl.CatalogServiceImpl`

```java
CatalogVO read(String entityNo, String storeNo, CatalogQuery query);
```

持久化：`FoodMappers.MenuMapper.selectList`（READ）；`FoodMappers.MenuStoreMapper.selectList`（READ）；`FoodMappers.MenuContextMapper.selectList`（READ）；`FoodMappers.MenuScheduleMapper.selectList`（READ）；`FoodMappers.MenuExceptionMapper.selectList`（READ）；`FoodMappers.MenuCategoryMapper.selectList`（READ）；`FoodMappers.MenuItemMapper.selectList`（READ）；`FoodMappers.MenuItemSkuMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）；`FoodMappers.DishMapper.selectList`（READ）；`FoodMappers.OptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupItemMapper.selectList`（READ）；`FoodMappers.ModifierMapper.selectList`（READ）；`FoodMappers.ModifierComponentMapper.selectList`（READ）；`FoodMappers.SalesRestrictionMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectEffectiveByConfigAndTime`（READ）。

跨域：`StoreDirectoryPort`、`GoodsCatalogQueryPort`、`DishOfferQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 已发布冻结定义及服务器当前时点，读取当前原价格及PLATFORM物理可售；UNKNOWN/null不伪造值；复杂配置需报价且公开输出不含渠道账号/价格策略

#### FOOD-API-115 · GET /biz/x/food/config-revisions

Controller：`ai.neargo.shop.food.api.biz.BizConfigRevisionController`

```java
PageData<ConfigRevisionVO> listConfigRevisions(@Valid @ModelAttribute ListConfigRevisionsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.ConfigRevisionService` / `ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl`

```java
PageData<ConfigRevisionVO> listConfigRevisions(String entityNo, String currentStoreNo, ListConfigRevisionsQuery query);
```

持久化：`FoodMappers.ConfigRevisionMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-116 · POST /biz/x/food/config-revisions/{revisionNo}:republish

Controller：`ai.neargo.shop.food.api.biz.BizConfigRevisionController`

```java
PublishConfigResultVO republishRevision(@PathVariable("revisionNo") String revisionNo, @Valid @RequestBody RepublishConfigReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ConfigRevisionService` / `ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl`

```java
PublishConfigResultVO republishRevision(String entityNo, String currentStoreNo, String revisionNo, String actorNo, RepublishConfigReq req, String idempotencyKey);
```

持久化：`FoodMappers.ConfigRevisionMapper.selectOne`（READ）；`FoodMappers.MenuMapper.selectList`（READ）；`FoodMappers.BundleMapper.selectList`（READ）；`FoodMappers.OptionGroupMapper.selectList`（READ）；`FoodMappers.ModifierMapper.selectList`（READ）；`FoodMappers.StationMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.insertImmutableRevision`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）；`FoodMappers.MenuMapper.update`（WRITE）；`FoodMappers.BundleMapper.update`（WRITE）；`FoodMappers.OptionGroupMapper.update`（WRITE）；`FoodMappers.ModifierMapper.update`（WRITE）；`FoodMappers.StationMapper.update`（WRITE）。

跨域：`GoodsQueryPort`、`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 按被冻结configKind的固定白名单分派，仅更新对应一种头；Mapper禁止动态SQL表名。以旧内容生成新发布，不覆盖旧快照、不形成Service互相注入循环。

#### FOOD-API-117 · POST /biz/x/food/modifiers/{modifierNo}:publish

Controller：`ai.neargo.shop.food.api.biz.BizModifierController`

```java
ConfigPublicationVO publishModifier(@PathVariable("modifierNo") String modifierNo, @Valid @RequestBody PublishModifierReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ModifierService` / `ai.neargo.shop.food.service.impl.ModifierServiceImpl`

```java
ConfigPublicationVO publishModifier(String entityNo, String currentStoreNo, String modifierNo, String actorNo, PublishModifierReq req, String idempotencyKey);
```

持久化：`FoodMappers.ModifierMapper.selectOne`（READ）；`FoodMappers.ModifierComponentMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.insertImmutableRevision`（WRITE）；`FoodMappers.ModifierMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-118 · GET /biz/x/food/stores/{storeNo}/option-groups

Controller：`ai.neargo.shop.food.api.biz.BizOptionGroupController`

```java
PageData<ConfigVO> listOptionGroups(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListOptionGroupsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.OptionGroupService` / `ai.neargo.shop.food.service.impl.OptionGroupServiceImpl`

```java
PageData<ConfigVO> listOptionGroups(String entityNo, String storeNo, ListOptionGroupsQuery query);
```

持久化：`FoodMappers.OptionGroupMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-119 · GET /biz/x/food/stores/{storeNo}/bundles

Controller：`ai.neargo.shop.food.api.biz.BizBundleController`

```java
PageData<BundleConfigVOVO> listBundles(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListBundlesQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.BundleService` / `ai.neargo.shop.food.service.impl.BundleServiceImpl`

```java
PageData<BundleConfigVOVO> listBundles(String entityNo, String storeNo, ListBundlesQuery query);
```

持久化：`FoodMappers.BundleMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-120 · GET /biz/x/food/stores/{storeNo}/modifiers

Controller：`ai.neargo.shop.food.api.biz.BizModifierController`

```java
PageData<ModifierConfigVOVO> listModifiers(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListModifiersQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.ModifierService` / `ai.neargo.shop.food.service.impl.ModifierServiceImpl`

```java
PageData<ModifierConfigVOVO> listModifiers(String entityNo, String storeNo, ListModifiersQuery query);
```

持久化：`FoodMappers.ModifierMapper.selectPage`（READ）；`FoodMappers.ModifierComponentMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-121 · GET /biz/x/food/stores/{storeNo}/stations

Controller：`ai.neargo.shop.food.api.biz.BizStationController`

```java
PageData<StationContracts.StationVO> listStations(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListStationsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.StationService` / `ai.neargo.shop.food.service.impl.StationServiceImpl`

```java
PageData<StationContracts.StationVO> listStations(String entityNo, String storeNo, ListStationsQuery query);
```

持久化：`FoodMappers.StationMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-122 · GET /biz/x/food/stores/{storeNo}/sales-restrictions

Controller：`ai.neargo.shop.food.api.biz.BizSalesRestrictionController`

```java
PageData<SalesRestrictionVO> list(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute SalesRestrictionQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.SalesRestrictionService` / `ai.neargo.shop.food.service.impl.SalesRestrictionServiceImpl`

```java
PageData<SalesRestrictionVO> list(String entityNo, String storeNo, SalesRestrictionQuery query);
```

持久化：`FoodMappers.SalesRestrictionMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-141 · POST /biz/x/food/stores/{storeNo}/modifiers

Controller：`ai.neargo.shop.food.api.biz.BizModifierController`

```java
CreateBundleResultVOVO createModifier(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateModifierReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ModifierService` / `ai.neargo.shop.food.service.impl.ModifierServiceImpl`

```java
CreateBundleResultVOVO createModifier(String entityNo, String storeNo, String actorNo, CreateModifierReq req, String idempotencyKey);
```

持久化：`FoodMappers.ModifierMapper.selectList`（READ）；`FoodMappers.ModifierComponentMapper.selectList`（READ）；`FoodMappers.ModifierMapper.insert`（WRITE）；`FoodMappers.ModifierComponentMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-142 · POST /biz/x/food/stores/{storeNo}/menu-channel-bindings

Controller：`ai.neargo.shop.food.api.biz.BizMenuChannelBindingController`

```java
MenuChannelBindingVO createBinding(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateMenuChannelBindingReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.MenuChannelBindingService` / `ai.neargo.shop.food.service.impl.MenuChannelBindingServiceImpl`

```java
MenuChannelBindingVO createBinding(String entityNo, String storeNo, String actorNo, CreateMenuChannelBindingReq req, String idempotencyKey);
```

持久化：`FoodMappers.MenuChannelBindingMapper.selectList`（READ）；`FoodMappers.MenuMapper.selectList`（READ）；`FoodMappers.MenuChannelBindingMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_WRITE**。共享任务持有导出/投影写入，本域只调用合同和保存明确的运行引用。 已实现草稿和授权启停；Keeta仅规划，缺真实提供方ACTIVE拒绝；公开目录禁止泄漏账号引用

#### FOOD-API-143 · GET /biz/x/food/stores/{storeNo}/menu-channel-bindings

Controller：`ai.neargo.shop.food.api.biz.BizMenuChannelBindingController`

```java
PageData<MenuChannelBindingVO> listBindings(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListBindingsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.MenuChannelBindingService` / `ai.neargo.shop.food.service.impl.MenuChannelBindingServiceImpl`

```java
PageData<MenuChannelBindingVO> listBindings(String entityNo, String storeNo, ListBindingsQuery query);
```

持久化：`FoodMappers.MenuChannelBindingMapper.selectPage`（READ）；`FoodMappers.MenuMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 已实现草稿和授权启停；Keeta仅规划，缺真实提供方ACTIVE拒绝；公开目录禁止泄漏账号引用

#### FOOD-API-144 · GET /biz/x/food/menu-channel-bindings/{bindingNo}

Controller：`ai.neargo.shop.food.api.biz.BizMenuChannelBindingController`

```java
MenuChannelBindingVO getBinding(@PathVariable("bindingNo") String bindingNo);
```

Service / Impl：`ai.neargo.shop.food.service.MenuChannelBindingService` / `ai.neargo.shop.food.service.impl.MenuChannelBindingServiceImpl`

```java
MenuChannelBindingVO getBinding(String entityNo, String currentStoreNo, String bindingNo);
```

持久化：`FoodMappers.MenuChannelBindingMapper.selectOne`（READ）；`FoodMappers.MenuMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 已实现草稿和授权启停；Keeta仅规划，缺真实提供方ACTIVE拒绝；公开目录禁止泄漏账号引用

#### FOOD-API-145 · PUT /biz/x/food/menu-channel-bindings/{bindingNo}

Controller：`ai.neargo.shop.food.api.biz.BizMenuChannelBindingController`

```java
MenuChannelBindingVO updateBinding(@PathVariable("bindingNo") String bindingNo, @Valid @RequestBody UpdateMenuChannelBindingReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.MenuChannelBindingService` / `ai.neargo.shop.food.service.impl.MenuChannelBindingServiceImpl`

```java
MenuChannelBindingVO updateBinding(String entityNo, String currentStoreNo, String bindingNo, String actorNo, UpdateMenuChannelBindingReq req, String idempotencyKey);
```

持久化：`FoodMappers.MenuChannelBindingMapper.selectOne`（READ）；`FoodMappers.MenuMapper.selectList`（READ）；`FoodMappers.MenuChannelBindingMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_WRITE**。共享任务持有导出/投影写入，本域只调用合同和保存明确的运行引用。 已实现草稿和授权启停；Keeta仅规划，缺真实提供方ACTIVE拒绝；公开目录禁止泄漏账号引用

#### FOOD-API-148 · POST /biz/x/food/stores/{storeNo}/recipes

Controller：`ai.neargo.shop.food.api.biz.BizRecipeController`

```java
RecipeResultVO createRecipe(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateRecipeReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.RecipeService` / `ai.neargo.shop.food.service.impl.RecipeServiceImpl`

```java
RecipeResultVO createRecipe(String entityNo, String storeNo, String actorNo, CreateRecipeReq req, String idempotencyKey);
```

持久化：`FoodMappers.RecipeMapper.selectList`（READ）；`FoodMappers.RecipeLineMapper.selectList`（READ）；`FoodMappers.RecipeAdjustmentMapper.selectList`（READ）；`FoodMappers.RecipeMapper.update`（WRITE）；`FoodMappers.RecipeLineMapper.update`（WRITE）；`FoodMappers.RecipeAdjustmentMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_WRITE**。共享任务持有导出/投影写入，本域只调用合同和保存明确的运行引用。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

#### FOOD-API-149 · GET /biz/x/food/stores/{storeNo}/recipes

Controller：`ai.neargo.shop.food.api.biz.BizRecipeController`

```java
PageData<RecipeVO> listRecipes(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListRecipesQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.RecipeService` / `ai.neargo.shop.food.service.impl.RecipeServiceImpl`

```java
PageData<RecipeVO> listRecipes(String entityNo, String storeNo, ListRecipesQuery query);
```

持久化：`FoodMappers.RecipeMapper.selectPage`（READ）；`FoodMappers.RecipeLineMapper.selectList`（READ）；`FoodMappers.RecipeAdjustmentMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

#### FOOD-API-150 · GET /biz/x/food/recipes/{recipeNo}

Controller：`ai.neargo.shop.food.api.biz.BizRecipeController`

```java
RecipeResultVO getRecipe(@PathVariable("recipeNo") String recipeNo);
```

Service / Impl：`ai.neargo.shop.food.service.RecipeService` / `ai.neargo.shop.food.service.impl.RecipeServiceImpl`

```java
RecipeResultVO getRecipe(String entityNo, String currentStoreNo, String recipeNo);
```

持久化：`FoodMappers.RecipeMapper.selectOne`（READ）；`FoodMappers.RecipeLineMapper.selectList`（READ）；`FoodMappers.RecipeAdjustmentMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

#### FOOD-API-151 · PUT /biz/x/food/recipes/{recipeNo}/definition

Controller：`ai.neargo.shop.food.api.biz.BizRecipeController`

```java
RecipeResultVO saveRecipeDefinition(@PathVariable("recipeNo") String recipeNo, @Valid @RequestBody SaveRecipeDefinitionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.RecipeService` / `ai.neargo.shop.food.service.impl.RecipeServiceImpl`

```java
RecipeResultVO saveRecipeDefinition(String entityNo, String currentStoreNo, String recipeNo, String actorNo, SaveRecipeDefinitionReq req, String idempotencyKey);
```

持久化：`FoodMappers.RecipeMapper.selectOne`（READ）；`FoodMappers.RecipeLineMapper.selectList`（READ）；`FoodMappers.RecipeAdjustmentMapper.selectList`（READ）；`FoodMappers.RecipeMapper.update`（WRITE）；`FoodMappers.RecipeLineMapper.update`（WRITE）；`FoodMappers.RecipeAdjustmentMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_WRITE**。共享任务持有导出/投影写入，本域只调用合同和保存明确的运行引用。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

#### FOOD-API-152 · POST /biz/x/food/recipes/{recipeNo}:publish

Controller：`ai.neargo.shop.food.api.biz.BizRecipeController`

```java
ConfigPublicationVO publishRecipe(@PathVariable("recipeNo") String recipeNo, @Valid @RequestBody PublishRecipeReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.RecipeService` / `ai.neargo.shop.food.service.impl.RecipeServiceImpl`

```java
ConfigPublicationVO publishRecipe(String entityNo, String currentStoreNo, String recipeNo, String actorNo, PublishRecipeReq req, String idempotencyKey);
```

持久化：`FoodMappers.RecipeMapper.selectOne`（READ）；`FoodMappers.RecipeLineMapper.selectList`（READ）；`FoodMappers.RecipeAdjustmentMapper.selectList`（READ）；`FoodMappers.RecipeMapper.update`（WRITE）；`FoodMappers.RecipeLineMapper.update`（WRITE）；`FoodMappers.RecipeAdjustmentMapper.update`（WRITE）；`FoodMappers.ConfigRevisionMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_WRITE**。共享任务持有导出/投影写入，本域只调用合同和保存明确的运行引用。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

#### FOOD-API-155 · GET /biz/x/food/stores/{storeNo}/sku-routes/{skuNo}

Controller：`ai.neargo.shop.food.api.biz.BizStationController`

```java
StationContracts.StationRoutesVO getRoutes(@PathVariable("storeNo") String storeNo, @PathVariable("skuNo") String skuNo);
```

Service / Impl：`ai.neargo.shop.food.service.StationService` / `ai.neargo.shop.food.service.impl.StationServiceImpl`

```java
StationContracts.StationRoutesVO getRoutes(String entityNo, String storeNo, String skuNo);
```

持久化：`FoodMappers.SkuStationMapper.selectOne`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 ALL-04实际只读当前关系；空路由保留整体关系计数，主体门店隔离

### MOD-03 点餐与订单管理

#### FOOD-API-037 · GET /biz/x/food/carts/{cartNo}

Controller：`ai.neargo.shop.food.api.biz.BizCartController`

```java
CartResultVO getCart(@PathVariable("cartNo") String cartNo,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartResultVO getCart(String entityNo, String storeNo, String cartNo);
```

持久化：`FoodMappers.CheckCartMapper.selectOne`（READ）；`FoodMappers.CheckCartItemMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。

#### FOOD-API-038 · POST /biz/x/food/stores/{storeNo}/carts

Controller：`ai.neargo.shop.food.api.biz.BizCartController`

```java
CartResultVO createCart(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateCartReq req, @RequestHeader("Idempotency-Key") String idempotencyKey,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartResultVO createCart(String entityNo, String storeNo, String actorNo, CreateCartReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.CheckCartMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。

#### FOOD-API-039 · PUT /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}

Controller：`ai.neargo.shop.food.api.biz.BizCartController`

```java
CartVersionVO replaceLine(@PathVariable("cartNo") String cartNo, @PathVariable("sourceLineRef") String sourceLineRef, @Valid @RequestBody ReplaceCartLineReq req, @RequestHeader("Idempotency-Key") String idempotencyKey,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartVersionVO replaceLine(String entityNo, String storeNo, String cartNo, String sourceLineRef, String actorNo, ReplaceCartLineReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectOne`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.SeatMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.history`（LOCK）；`FoodMappers.CheckCartItemMapper.retain`（WRITE）；`FoodMappers.CheckCartMapper.update`（WRITE）；`FoodMappers.CheckCartItemMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。事务外读取原已发布CatalogService；QR与原车来源目录交集校验。

#### FOOD-API-040 · POST /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive

Controller：`ai.neargo.shop.food.api.biz.BizCartController`

```java
CartVersionVO archiveLine(@PathVariable("cartNo") String cartNo, @PathVariable("sourceLineRef") String sourceLineRef, @Valid @RequestBody CartVersionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartVersionVO archiveLine(String entityNo, String storeNo, String cartNo, String sourceLineRef, String actorNo, CartVersionReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectOne`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.history`（LOCK）；`FoodMappers.CheckCartItemMapper.retain`（WRITE）；`FoodMappers.CheckCartMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。

#### FOOD-API-041 · POST /biz/x/food/stores/{storeNo}/quotes

Controller：`ai.neargo.shop.food.api.biz.BizDiningQuoteController`

```java
DiningQuoteResultVO quote(@PathVariable("storeNo") String storeNo, @Valid @RequestBody QuoteReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
DiningQuoteResultVO quote(String entityNo, String storeNo, String actorNo, QuoteReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）；`FoodMappers.SalesRestrictionMapper.selectList`（READ）。

跨域：`GoodsQueryPort`、`OrderPlacementPort`。

事务：**NO_LOCAL_WRITE**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 报价由核心返回，绑定车版本、源行与规则指纹；不能餐饮重算价格。

#### FOOD-API-042 · POST /biz/x/food/stores/{storeNo}/batches

Controller：`ai.neargo.shop.food.api.biz.BizOrderBatchController`

```java
OrderBatchResultVO place(@PathVariable("storeNo") String storeNo, @Valid @RequestBody PlaceReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
OrderBatchResultVO place(String entityNo, String storeNo, String actorNo, PlaceReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.selectList`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）；`FoodMappers.SalesRestrictionMapper.selectList`（READ）；`FoodMappers.CheckCartMapper.lockCartIfVersion`（LOCK）；`FoodMappers.CheckCartItemMapper.selectSelectedLinesForUpdate`（LOCK）；`FoodMappers.CheckCartItemMapper.archiveSelectedSubmittedLines`（WRITE）；`FoodMappers.OrderBatchMapper.insertBatchIfAbsent`（WRITE）；`FoodMappers.OrderBatchMapper.bindCoreResultIfVersion`（WRITE）；`FoodMappers.OrderBatchMapper.advancePlacementIfStatus`（WRITE）；`FoodMappers.CoreLineAllocationMapper.insertAllocationIfAbsent`（WRITE）；`FoodMappers.SalesRestrictionMapper.reserveQuotaIfCapacity`（WRITE）；`FoodMappers.SalesRestrictionMapper.consumeQuotaIfReserved`（WRITE）；`FoodMappers.SalesRestrictionMapper.releaseQuotaIfReserved`（WRITE）；`FoodMappers.SalesQuotaClaimMapper.insertClaimIfAbsent`（WRITE）；`FoodMappers.SalesQuotaClaimMapper.advanceClaimIfStatus`（WRITE）；`FoodMappers.OrderLineMapper.insert`（WRITE）；`FoodMappers.OrderComponentMapper.insert`（WRITE）；`FoodMappers.OrderModifierMapper.insert`（WRITE）；`FoodMappers.CoreOrderBindingMapper.insert`（WRITE）；`FoodMappers.CheckBatchMembershipMapper.insert`（WRITE）；`FoodMappers.CheckCartMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`OrderPlacementPort`、`InventoryCommitmentPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 意图与冻结源行本地提交后调用核心；UNKNOWN查同键；结果绑定再清理此次草稿，后付合同未齐禁用。

#### FOOD-API-043 · GET /biz/x/food/batches/{batchNo}

Controller：`ai.neargo.shop.food.api.biz.BizOrderBatchController`

```java
BatchDetailResultVO getBatch(@PathVariable("batchNo") String batchNo);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
BatchDetailResultVO getBatch(String entityNo, String currentStoreNo, String batchNo);
```

持久化：`FoodMappers.OrderBatchMapper.selectOne`（READ）；`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.OrderComponentMapper.selectList`（READ）；`FoodMappers.OrderModifierMapper.selectList`（READ）；`FoodMappers.CoreOrderBindingMapper.selectList`（READ）；`FoodMappers.CoreLineAllocationMapper.selectList`（READ）。

跨域：`OrderPlacementPort`、`PaymentQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-123 · GET /biz/x/food/stores/{storeNo}/batches

Controller：`ai.neargo.shop.food.api.biz.BizOrderBatchController`

```java
PageData<BatchVO> listBatchs(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListBatchsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
PageData<BatchVO> listBatchs(String entityNo, String storeNo, ListBatchsQuery query);
```

持久化：`FoodMappers.OrderBatchMapper.selectPage`（READ）；`FoodMappers.CheckBatchMembershipMapper.selectList`（READ）；`FoodMappers.CoreOrderBindingMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-124 · POST /biz/x/food/batches/{batchNo}:cancel

Controller：`ai.neargo.shop.food.api.biz.BizOrderBatchController`

```java
CancelBatchOrderBatchResultVO cancelBatch(@PathVariable("batchNo") String batchNo, @Valid @RequestBody CancelBatchReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
CancelBatchOrderBatchResultVO cancelBatch(String entityNo, String currentStoreNo, String batchNo, String actorNo, CancelBatchReq req, String idempotencyKey);
```

持久化：`FoodMappers.OrderBatchMapper.selectOne`（READ）；`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.SalesQuotaClaimMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.advancePlacementIfStatus`（WRITE）；`FoodMappers.SalesRestrictionMapper.releaseQuotaIfReserved`（WRITE）；`FoodMappers.SalesQuotaClaimMapper.advanceClaimIfStatus`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`OrderPlacementPort`、`InventoryCommitmentPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-125 · GET /biz/x/food/batches/{batchNo}/operations

Controller：`ai.neargo.shop.food.api.biz.BizOrderBatchOperationController`

```java
PageData<OperationVO> listOperations(@PathVariable("batchNo") String batchNo, @Valid @ModelAttribute ListOperationsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
PageData<OperationVO> listOperations(String entityNo, String currentStoreNo, String batchNo, ListOperationsQuery query);
```

持久化：`FoodMappers.OrderBatchMapper.selectPage`（READ）。

跨域：`OrderPlacementPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 查询原意图/操作状态；除显式恢复任务外GET不补写业务状态。

#### FOOD-API-126 · POST /biz/x/food/carts/{cartNo}:archive

Controller：`ai.neargo.shop.food.api.biz.BizCartController`

```java
OperationResultVO archiveCart(@PathVariable("cartNo") String cartNo, @Valid @RequestBody CartVersionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
OperationResultVO archiveCart(String entityNo, String storeNo, String cartNo, String actorNo, CartVersionReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectOne`（READ）；`FoodMappers.OrderBatchMapper.selectList`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.CheckCartMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。

#### FOOD-API-127 · GET /mp/x/food/carts/{cartNo}

Controller：`ai.neargo.shop.food.api.mp.MpCartController`

```java
CartResultVO customerGetCart(@PathVariable("cartNo") String cartNo, @RequestParam("sessionNo") String sessionNo, @RequestHeader("X-Food-Session") String token,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartResultVO customerGetCart(String cartNo, CartAccess access);
```

持久化：`FoodMappers.CheckCartMapper.selectOne`（READ）；`FoodMappers.CheckCartItemMapper.selectList`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。

#### FOOD-API-128 · POST /mp/x/food/stores/{storeNo}/carts

Controller：`ai.neargo.shop.food.api.mp.MpCartController`

```java
CartResultVO customerCreateCart(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateCustomerCartReq req, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader("X-Food-Session") String token,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartResultVO customerCreateCart(String storeNo, CartAccess access, CreateCustomerCartReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.CheckCartMapper.insert`（WRITE）；`FoodMappers.GuestSessionMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。

#### FOOD-API-129 · PUT /mp/x/food/carts/{cartNo}/lines/{sourceLineRef}

Controller：`ai.neargo.shop.food.api.mp.MpCartController`

```java
CartVersionVO customerReplaceLine(@PathVariable("cartNo") String cartNo, @PathVariable("sourceLineRef") String sourceLineRef, @Valid @RequestBody ReplaceCartLineReq req, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestParam("sessionNo") String sessionNo, @RequestHeader("X-Food-Session") String token,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartVersionVO customerReplaceLine(String cartNo, String sourceLineRef, CartAccess access, ReplaceCartLineReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectOne`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.SeatMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.history`（LOCK）；`FoodMappers.CheckCartItemMapper.retain`（WRITE）；`FoodMappers.CheckCartMapper.update`（WRITE）；`FoodMappers.CheckCartItemMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。事务外读取原已发布CatalogService；QR与原车来源目录交集校验。

#### FOOD-API-130 · POST /mp/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive

Controller：`ai.neargo.shop.food.api.mp.MpCartController`

```java
CartVersionVO customerArchiveLine(@PathVariable("cartNo") String cartNo, @PathVariable("sourceLineRef") String sourceLineRef, @Valid @RequestBody CartVersionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestParam("sessionNo") String sessionNo, @RequestHeader("X-Food-Session") String token,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartVersionVO customerArchiveLine(String cartNo, String sourceLineRef, CartAccess access, CartVersionReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectOne`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.history`（LOCK）；`FoodMappers.CheckCartItemMapper.retain`（WRITE）；`FoodMappers.CheckCartMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。

#### FOOD-API-131 · POST /mp/x/food/stores/{storeNo}/quotes

Controller：`ai.neargo.shop.food.api.mp.MpDiningQuoteController`

```java
DiningQuoteResultVO quote(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CustomerQuoteReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
DiningQuoteResultVO customerQuote(String entityNo, String storeNo, String authenticatedSessionNo, String actorNo, CustomerQuoteReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）；`FoodMappers.SalesRestrictionMapper.selectList`（READ）。

跨域：`GoodsQueryPort`、`OrderPlacementPort`。

事务：**NO_LOCAL_WRITE**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 本人/明确授权台账范围；禁止现金/审批/店长权限。报价由核心返回，绑定车版本、源行与规则指纹；不能餐饮重算价格。

#### FOOD-API-132 · POST /mp/x/food/stores/{storeNo}/batches

Controller：`ai.neargo.shop.food.api.mp.MpOrderBatchController`

```java
OrderBatchResultVO place(@PathVariable("storeNo") String storeNo, @Valid @RequestBody PlaceReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
OrderBatchResultVO customerPlace(String entityNo, String storeNo, String authenticatedSessionNo, String actorNo, PlaceReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.selectList`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）；`FoodMappers.SalesRestrictionMapper.selectList`（READ）；`FoodMappers.CheckCartMapper.lockCartIfVersion`（LOCK）；`FoodMappers.CheckCartItemMapper.selectSelectedLinesForUpdate`（LOCK）；`FoodMappers.CheckCartItemMapper.archiveSelectedSubmittedLines`（WRITE）；`FoodMappers.OrderBatchMapper.insertBatchIfAbsent`（WRITE）；`FoodMappers.OrderBatchMapper.bindCoreResultIfVersion`（WRITE）；`FoodMappers.OrderBatchMapper.advancePlacementIfStatus`（WRITE）；`FoodMappers.CoreLineAllocationMapper.insertAllocationIfAbsent`（WRITE）；`FoodMappers.SalesRestrictionMapper.reserveQuotaIfCapacity`（WRITE）；`FoodMappers.SalesRestrictionMapper.consumeQuotaIfReserved`（WRITE）；`FoodMappers.SalesRestrictionMapper.releaseQuotaIfReserved`（WRITE）；`FoodMappers.SalesQuotaClaimMapper.insertClaimIfAbsent`（WRITE）；`FoodMappers.SalesQuotaClaimMapper.advanceClaimIfStatus`（WRITE）；`FoodMappers.OrderLineMapper.insert`（WRITE）；`FoodMappers.OrderComponentMapper.insert`（WRITE）；`FoodMappers.OrderModifierMapper.insert`（WRITE）；`FoodMappers.CoreOrderBindingMapper.insert`（WRITE）；`FoodMappers.CheckBatchMembershipMapper.insert`（WRITE）；`FoodMappers.CheckCartMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`OrderPlacementPort`、`InventoryCommitmentPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 本人/明确授权台账范围；禁止现金/审批/店长权限。意图与冻结源行本地提交后调用核心；UNKNOWN查同键；结果绑定再清理此次草稿，后付合同未齐禁用。

#### FOOD-API-133 · GET /mp/x/food/batches/{batchNo}

Controller：`ai.neargo.shop.food.api.mp.MpOrderBatchController`

```java
BatchDetailResultVO getBatch(@PathVariable("batchNo") String batchNo);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
BatchDetailResultVO customerGetBatch(String entityNo, String currentStoreNo, String batchNo, String authenticatedSessionNo);
```

持久化：`FoodMappers.OrderBatchMapper.selectOne`（READ）；`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.OrderComponentMapper.selectList`（READ）；`FoodMappers.OrderModifierMapper.selectList`（READ）；`FoodMappers.CoreOrderBindingMapper.selectList`（READ）；`FoodMappers.CoreLineAllocationMapper.selectList`（READ）。

跨域：`OrderPlacementPort`、`PaymentQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 本人/明确授权台账范围；禁止现金/审批/店长权限。

#### FOOD-API-156 · POST /biz/x/food/checks/{checkNo}/cart-sessions

Controller：`ai.neargo.shop.food.api.biz.BizCartController`

```java
CartSessionGrantVO authorizeSession(@PathVariable("checkNo") String checkNo, @Valid @RequestBody AuthorizeCartSessionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartSessionGrantVO authorizeSession(String entityNo, String storeNo, String checkNo, String actorNo, AuthorizeCartSessionReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableAreaMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 复用原哈希会话；店员授权不会泄露凭证，不赋予支付或退款权限。

### MOD-04 厨房与履约管理

#### FOOD-API-044 · GET /biz/x/food/stores/{storeNo}/kitchen/tasks

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
PageData<TaskVO> listTasks(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListTasksQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
PageData<TaskVO> listTasks(String entityNo, String storeNo, ListTasksQuery query);
```

持久化：`FoodMappers.KitchenTaskMapper.selectPage`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.OrderLineMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-045 · POST /biz/x/food/tasks/{taskNo}:start

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
KitchenTaskResultVO startTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody StartTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
KitchenTaskResultVO startTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, StartTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.advanceTaskQuantityIfVersion`（WRITE）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 start/ready/serve推进数量；hold/fire/urge不增加销量；void必须退餐授权。

#### FOOD-API-046 · POST /biz/x/food/tasks/{taskNo}:ready

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
KitchenTaskResultVO readyTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody ReadyTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
KitchenTaskResultVO readyTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, ReadyTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.advanceTaskQuantityIfVersion`（WRITE）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 start/ready/serve推进数量；hold/fire/urge不增加销量；void必须退餐授权。

#### FOOD-API-047 · POST /biz/x/food/tasks/{taskNo}:serve

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
KitchenTaskResultVO serveTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody ServeTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
KitchenTaskResultVO serveTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, ServeTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.advanceTaskQuantityIfVersion`（WRITE）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 start/ready/serve推进数量；hold/fire/urge不增加销量；void必须退餐授权。

#### FOOD-API-048 · POST /biz/x/food/tasks/{taskNo}:hold

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
KitchenTaskResultVO holdTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody HoldTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
KitchenTaskResultVO holdTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, HoldTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.changePendingStateIfVersion`（WRITE）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 start/ready/serve推进数量；hold/fire/urge不增加销量；void必须退餐授权。

#### FOOD-API-049 · POST /biz/x/food/tasks/{taskNo}:fire

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
KitchenTaskResultVO fireTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody FireTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
KitchenTaskResultVO fireTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, FireTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.changePendingStateIfVersion`（WRITE）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 start/ready/serve推进数量；hold/fire/urge不增加销量；void必须退餐授权。

#### FOOD-API-050 · POST /biz/x/food/tasks/{taskNo}:urge

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
KitchenTaskResultVO urgeTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody UrgeTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
KitchenTaskResultVO urgeTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, UrgeTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 仅追加催菜事实/审计；不推进任务数量，不重复派工或计销量。

#### FOOD-API-051 · POST /biz/x/food/tasks/{taskNo}:void

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
KitchenTaskResultVO voidTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody VoidTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
KitchenTaskResultVO voidTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, VoidTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.ReturnMapper.selectList`（READ）；`FoodMappers.ReturnItemMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.advanceTaskQuantityIfVersion`（WRITE）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 start/ready/serve推进数量；hold/fire/urge不增加销量；void必须退餐授权。

#### FOOD-API-052 · POST /biz/x/food/tasks/{taskNo}:remake

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
RemakeTaskKitchenTaskResultVO remakeTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody RemakeTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
RemakeTaskKitchenTaskResultVO remakeTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, RemakeTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.KitchenTicketMapper.insertDispatchIfAbsent`（WRITE）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.KitchenTaskMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-053 · POST /biz/x/food/checks/{checkNo}/courses

Controller：`ai.neargo.shop.food.api.biz.BizCourseController`

```java
CourseResultVO createCourse(@PathVariable("checkNo") String checkNo, @Valid @RequestBody CreateCourseReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CourseService` / `ai.neargo.shop.food.service.impl.CourseServiceImpl`

```java
CourseResultVO createCourse(String entityNo, String currentStoreNo, String checkNo, String actorNo, CreateCourseReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.CourseMapper.selectList`（READ）；`FoodMappers.CourseMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-054 · POST /biz/x/food/courses/{courseNo}:fire

Controller：`ai.neargo.shop.food.api.biz.BizCourseController`

```java
OperationVO fireCourse(@PathVariable("courseNo") String courseNo, @Valid @RequestBody FireCourseReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CourseService` / `ai.neargo.shop.food.service.impl.CourseServiceImpl`

```java
OperationVO fireCourse(String entityNo, String currentStoreNo, String courseNo, String actorNo, FireCourseReq req, String idempotencyKey);
```

持久化：`FoodMappers.CourseMapper.selectOne`（READ）；`FoodMappers.KitchenTaskMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.advanceTaskQuantityIfVersion`（WRITE）；`FoodMappers.CourseMapper.update`（WRITE）；`FoodMappers.ServiceEventMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-055 · POST /biz/x/food/batches/{batchNo}:handover

Controller：`ai.neargo.shop.food.api.biz.BizOrderBatchController`

```java
HandoverBatchOrderBatchResultVO handoverBatch(@PathVariable("batchNo") String batchNo, @Valid @RequestBody HandoverBatchReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
HandoverBatchOrderBatchResultVO handoverBatch(String entityNo, String currentStoreNo, String batchNo, String actorNo, HandoverBatchReq req, String idempotencyKey);
```

持久化：`FoodMappers.OrderBatchMapper.selectOne`（READ）；`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.update`（WRITE）；`FoodMappers.ServiceEventMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`DiningFulfillmentPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 核心核销/配送交接为权威，部分交付与制作状态不能混写。

#### FOOD-API-153 · GET /biz/x/food/stores/{storeNo}/production-consumptions

Controller：`ai.neargo.shop.food.api.biz.BizProductionConsumptionController`

```java
PageData<ProductionConsumptionVO> list(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.ProductionConsumptionService` / `ai.neargo.shop.food.service.impl.ProductionConsumptionServiceImpl`

```java
PageData<ProductionConsumptionVO> list(String entityNo, String storeNo, ListQuery query);
```

持久化：`FoodMappers.ProductionConsumptionMapper.selectPage`（READ）；`FoodMappers.ProductionConsumptionLineMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 CODED_PENDING_VERIFICATION；可信主体/门店/actor；from/to营业日区间最多93日，SQL同过滤COUNT与分页；共享PageData<ConsumptionVO>真实回执递归，BLOCKED/UNKNOWN/未知成本保真；只读不补扣库存。

#### FOOD-API-154 · GET /biz/x/food/production-consumptions/{consumptionNo}

Controller：`ai.neargo.shop.food.api.biz.BizProductionConsumptionController`

```java
ProductionConsumptionResultVO getProductionConsumption(@PathVariable("consumptionNo") String consumptionNo);
```

Service / Impl：`ai.neargo.shop.food.service.ProductionConsumptionService` / `ai.neargo.shop.food.service.impl.ProductionConsumptionServiceImpl`

```java
ProductionConsumptionResultVO getProductionConsumption(String entityNo, String currentStoreNo, String consumptionNo);
```

持久化：`FoodMappers.ProductionConsumptionMapper.selectOne`（READ）；`FoodMappers.ProductionConsumptionLineMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

### MOD-05 收银与结账管理

#### FOOD-API-056 · POST /biz/x/food/stores/{storeNo}/settlements

Controller：`ai.neargo.shop.food.api.biz.BizDiningCheckoutController`

```java
DiningCheckoutResultVO createSettlement(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateSettlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
DiningCheckoutResultVO createSettlement(String entityNo, String storeNo, String actorNo, CreateSettlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.selectList`（READ）；`FoodMappers.CoreOrderBindingMapper.selectList`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.SettlementMapper.insertFrozenScope`（WRITE）；`FoodMappers.SettlementItemMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`CashierPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 跨结账/跨根应收独占由核心持有；本地唯一键不等于防双收。

#### FOOD-API-057 · GET /biz/x/food/settlements/{settlementNo}

Controller：`ai.neargo.shop.food.api.biz.BizDiningCheckoutController`

```java
GetSettlementDiningCheckoutResultVO getSettlement(@PathVariable("settlementNo") String settlementNo);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
GetSettlementDiningCheckoutResultVO getSettlement(String entityNo, String currentStoreNo, String settlementNo);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）。

跨域：`CashierPort`、`PaymentQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-058 · POST /biz/x/food/settlements/{settlementNo}:pay

Controller：`ai.neargo.shop.food.api.biz.BizDiningCheckoutController`

```java
PaySettlementDiningCheckoutResultVO paySettlement(@PathVariable("settlementNo") String settlementNo, @Valid @RequestBody PaySettlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
PaySettlementDiningCheckoutResultVO paySettlement(String entityNo, String currentStoreNo, String settlementNo, String actorNo, PaySettlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.CashShiftMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.insertIntentIfAbsent`（WRITE）；`FoodMappers.ChargeIntentMapper.confirmIntentFromCoreIfStatus`（WRITE）；`FoodMappers.CashPaymentLinkMapper.linkCorePaymentIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`CashierPort`、`PaymentQueryPort`、`MemberAssetPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-059 · POST /biz/x/food/settlements/{settlementNo}:cancel

Controller：`ai.neargo.shop.food.api.biz.BizDiningCheckoutController`

```java
OperationResultVO cancelSettlement(@PathVariable("settlementNo") String settlementNo, @Valid @RequestBody CancelSettlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
OperationResultVO cancelSettlement(String entityNo, String currentStoreNo, String settlementNo, String actorNo, CancelSettlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）；`FoodMappers.SettlementMapper.update`（WRITE）；`FoodMappers.ChargeIntentMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`CashierPort`、`PaymentQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 仅核心确认无成功/未知不可撤销资金才能解冻。

#### FOOD-API-060 · POST /biz/x/food/settlements/{settlementNo}:finalize

Controller：`ai.neargo.shop.food.api.biz.BizDiningCheckoutController`

```java
DiningCheckoutResultVO finalizeSettlement(@PathVariable("settlementNo") String settlementNo, @Valid @RequestBody FinalizeSettlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
DiningCheckoutResultVO finalizeSettlement(String entityNo, String currentStoreNo, String settlementNo, String actorNo, FinalizeSettlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）；`FoodMappers.SettlementMapper.finalizeIfCoreConfirmed`（WRITE）；`FoodMappers.CheckMapper.closeIfSettlementConfirmed`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`CashierPort`、`PaymentQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-061 · POST /biz/x/food/settlements/{settlementNo}/adjustments

Controller：`ai.neargo.shop.food.api.biz.BizDiningAdjustmentController`

```java
DiningAdjustmentResultVO createAdjustment(@PathVariable("settlementNo") String settlementNo, @Valid @RequestBody CreateDiningAdjustmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
DiningAdjustmentResultVO createAdjustment(String entityNo, String currentStoreNo, String settlementNo, String actorNo, CreateDiningAdjustmentReq req, String idempotencyKey);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`OrderAdjustmentPort`、`ApprovalPort`、`AccountsReceivablePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-134 · GET /mp/x/food/settlements/{settlementNo}

Controller：`ai.neargo.shop.food.api.mp.MpDiningCheckoutController`

```java
GetSettlementDiningCheckoutResultVO getSettlement(@PathVariable("settlementNo") String settlementNo);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
GetSettlementDiningCheckoutResultVO customerGetSettlement(String entityNo, String currentStoreNo, String settlementNo, String authenticatedSessionNo);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）。

跨域：`CashierPort`、`PaymentQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 本人/明确授权台账范围；禁止现金/审批/店长权限。

#### FOOD-API-135 · POST /mp/x/food/settlements/{settlementNo}:pay

Controller：`ai.neargo.shop.food.api.mp.MpDiningCheckoutController`

```java
PaySettlementDiningCheckoutResultVO paySettlement(@PathVariable("settlementNo") String settlementNo, @Valid @RequestBody CustomerPaySettlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
PaySettlementDiningCheckoutResultVO customerPaySettlement(String entityNo, String currentStoreNo, String settlementNo, String authenticatedSessionNo, String actorNo, CustomerPaySettlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.insertIntentIfAbsent`（WRITE）；`FoodMappers.ChargeIntentMapper.confirmIntentFromCoreIfStatus`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`CashierPort`、`PaymentQueryPort`、`MemberAssetPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 本人/明确授权台账范围；禁止现金/审批/店长权限。

#### FOOD-API-140 · POST /mp/x/food/checks/{checkNo}/settlements

Controller：`ai.neargo.shop.food.api.mp.MpDiningCheckoutController`

```java
DiningCheckoutResultVO createSettlement(@PathVariable("checkNo") String checkNo, @Valid @RequestBody CustomerCreateSettlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
DiningCheckoutResultVO customerCreateSettlement(String entityNo, String currentStoreNo, String checkNo, String authenticatedSessionNo, String actorNo, CustomerCreateSettlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.OrderBatchMapper.selectList`（READ）；`FoodMappers.CoreOrderBindingMapper.selectList`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.SettlementMapper.insertFrozenScope`（WRITE）；`FoodMappers.SettlementItemMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`CashierPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 顾客仅获准源行/应收范围；不接受任意核心单号。跨结账/跨根应收独占由核心持有；本地唯一键不等于防双收。

### MOD-06 退餐与售后管理

#### FOOD-API-062 · POST /biz/x/food/stores/{storeNo}/returns

Controller：`ai.neargo.shop.food.api.biz.BizDiningReturnController`

```java
DiningReturnResultVO createReturn(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateReturnReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReturnService` / `ai.neargo.shop.food.service.impl.DiningReturnServiceImpl`

```java
DiningReturnResultVO createReturn(String entityNo, String storeNo, String actorNo, CreateReturnReq req, String idempotencyKey);
```

持久化：`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.CoreLineAllocationMapper.selectList`（READ）；`FoodMappers.ReturnMapper.selectList`（READ）；`FoodMappers.ReturnItemMapper.selectList`（READ）；`FoodMappers.OrderLineMapper.selectScopedByKeyForUpdate`（LOCK）；`FoodMappers.ReturnItemMapper.selectActiveReturnQuantityByFoodLine`（READ）；`FoodMappers.ReturnMapper.insert`（WRITE）；`FoodMappers.ReturnItemMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 锁来源行后核对有效申请退量再插入；Mapper不按现菜单价算退款。

#### FOOD-API-063 · POST /biz/x/food/returns/{returnNo}:execute

Controller：`ai.neargo.shop.food.api.biz.BizDiningReturnController`

```java
ExecuteReturnDiningReturnResultVO executeReturn(@PathVariable("returnNo") String returnNo, @Valid @RequestBody ExecuteReturnReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReturnService` / `ai.neargo.shop.food.service.impl.DiningReturnServiceImpl`

```java
ExecuteReturnDiningReturnResultVO executeReturn(String entityNo, String currentStoreNo, String returnNo, String actorNo, ExecuteReturnReq req, String idempotencyKey);
```

持久化：`FoodMappers.ReturnMapper.selectOne`（READ）；`FoodMappers.ReturnItemMapper.selectList`（READ）；`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.selectList`（READ）；`FoodMappers.OrderLineMapper.selectScopedByKeyForUpdate`（LOCK）；`FoodMappers.ReturnItemMapper.selectActiveReturnQuantityByFoodLine`（READ）；`FoodMappers.ReturnMapper.update`（WRITE）；`FoodMappers.KitchenTaskMapper.update`（WRITE）；`FoodMappers.ServiceEventMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`AfterSalePort`、`OrderAdjustmentPort`、`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 核心财务与厨房两条轨道，部分退款能力未齐禁用。

#### FOOD-API-064 · GET /biz/x/food/returns/{returnNo}

Controller：`ai.neargo.shop.food.api.biz.BizDiningReturnController`

```java
GetReturnDiningReturnResultVO getReturn(@PathVariable("returnNo") String returnNo);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReturnService` / `ai.neargo.shop.food.service.impl.DiningReturnServiceImpl`

```java
GetReturnDiningReturnResultVO getReturn(String entityNo, String currentStoreNo, String returnNo);
```

持久化：`FoodMappers.ReturnMapper.selectOne`（READ）；`FoodMappers.ReturnItemMapper.selectList`（READ）。

跨域：`AfterSalePort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-136 · POST /mp/x/food/stores/{storeNo}/returns

Controller：`ai.neargo.shop.food.api.mp.MpDiningReturnController`

```java
DiningReturnResultVO createReturn(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateReturnReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReturnService` / `ai.neargo.shop.food.service.impl.DiningReturnServiceImpl`

```java
DiningReturnResultVO customerCreateReturn(String entityNo, String storeNo, String authenticatedSessionNo, String actorNo, CreateReturnReq req, String idempotencyKey);
```

持久化：`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.CoreLineAllocationMapper.selectList`（READ）；`FoodMappers.ReturnMapper.selectList`（READ）；`FoodMappers.ReturnItemMapper.selectList`（READ）；`FoodMappers.ReturnItemMapper.selectActiveReturnQuantityByFoodLine`（READ）；`FoodMappers.ReturnMapper.insert`（WRITE）；`FoodMappers.ReturnItemMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 本人/明确授权台账范围；禁止现金/审批/店长权限。锁来源行后核对有效申请退量再插入；Mapper不按现菜单价算退款。

#### FOOD-API-137 · GET /mp/x/food/returns/{returnNo}

Controller：`ai.neargo.shop.food.api.mp.MpDiningReturnController`

```java
GetReturnDiningReturnResultVO getReturn(@PathVariable("returnNo") String returnNo);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReturnService` / `ai.neargo.shop.food.service.impl.DiningReturnServiceImpl`

```java
GetReturnDiningReturnResultVO customerGetReturn(String entityNo, String currentStoreNo, String returnNo, String authenticatedSessionNo);
```

持久化：`FoodMappers.ReturnMapper.selectOne`（READ）；`FoodMappers.ReturnItemMapper.selectList`（READ）。

跨域：`AfterSalePort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 本人/明确授权台账范围；禁止现金/审批/店长权限。

### MOD-07 打印、通知与设备管理

#### FOOD-API-065 · POST /biz/print/jobs

Controller：`ai.neargo.shop.food.api.biz.BizPrintJobController`

```java
PrintJobResultVO createJob(@Valid @RequestBody CreateJobReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.printing.service.PrintService` / `ai.neargo.shop.printing.service.impl.PrintServiceImpl`

```java
PrintJobResultVO createJob(String entityNo, String currentStoreNo, String actorNo, CreateJobReq req, String idempotencyKey);
```

持久化：`PrintMappers.DeviceMapper.selectList`（READ）；`PrintMappers.RouteMapper.selectList`（READ）；`PrintMappers.TemplateMapper.selectList`（READ）；`PrintMappers.JobMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 共享打印持有队列，food只冻结票面；此段是共享模块目标方法，不在food-core实现其Mapper。 共享PrintService经受控票面提供者取得冻结内容，再本地写队列/outbox；不回调自己实现的PrintPort。 共享B端目标路径NOT_REGISTERED；以下目标方法/Req/VO为PLANNED_TRANSPORT_CONTRACT，不声称存在对应Java；实际FOOD打印Controller/DTO及delegate见actualImplementations。

#### FOOD-API-066 · GET /biz/print/jobs/{jobNo}

Controller：`ai.neargo.shop.food.api.biz.BizPrintJobController`

```java
GetJobPrintJobResultVO getJob(@PathVariable("jobNo") String jobNo);
```

Service / Impl：`ai.neargo.shop.printing.service.PrintService` / `ai.neargo.shop.printing.service.impl.PrintServiceImpl`

```java
GetJobPrintJobResultVO getJob(String entityNo, String currentStoreNo, String jobNo);
```

持久化：`PrintMappers.JobMapper.selectOne`（READ）；`PrintMappers.AttemptMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 共享B端目标路径NOT_REGISTERED；以下目标方法/Req/VO为PLANNED_TRANSPORT_CONTRACT，不声称存在对应Java；实际FOOD打印Controller/DTO及delegate见actualImplementations。

#### FOOD-API-067 · POST /biz/print/jobs/{jobNo}:reprint

Controller：`ai.neargo.shop.food.api.biz.BizPrintJobController`

```java
ReprintJobResultVO reprintJob(@PathVariable("jobNo") String jobNo, @Valid @RequestBody ReprintJobReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.printing.service.PrintService` / `ai.neargo.shop.printing.service.impl.PrintServiceImpl`

```java
ReprintJobResultVO reprintJob(String entityNo, String currentStoreNo, String jobNo, String actorNo, ReprintJobReq req, String idempotencyKey);
```

持久化：`PrintMappers.JobMapper.selectOne`（READ）；`PrintMappers.DeviceMapper.selectList`（READ）；`PrintMappers.JobMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 创建新补打任务并引用原任务，不再次派工。 共享PrintService经受控票面提供者取得冻结内容，再本地写队列/outbox；不回调自己实现的PrintPort。 共享B端目标路径NOT_REGISTERED；以下目标方法/Req/VO为PLANNED_TRANSPORT_CONTRACT，不声称存在对应Java；实际FOOD打印Controller/DTO及delegate见actualImplementations。

### MOD-08 班次与现金管理

#### FOOD-API-068 · POST /biz/x/food/stores/{storeNo}/cash-shifts

Controller：`ai.neargo.shop.food.api.biz.BizCashShiftController`

```java
CashShiftResultVO createCashShift(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateCashShiftReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CashShiftService` / `ai.neargo.shop.food.service.impl.CashShiftServiceImpl`

```java
CashShiftResultVO createCashShift(String entityNo, String storeNo, String actorNo, CreateCashShiftReq req, String idempotencyKey);
```

持久化：`FoodMappers.CashShiftMapper.selectList`（READ）；`FoodMappers.CashShiftMapper.selectActiveDrawerForUpdate`（LOCK）；`FoodMappers.CashShiftMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 钱箱无活动行时不能靠SELECT FOR UPDATE锁空结果；须共享资源/稳定钱箱仲裁者，否则禁用并发开班。

#### FOOD-API-069 · POST /biz/x/food/cash-shifts/{shiftNo}/entries

Controller：`ai.neargo.shop.food.api.biz.BizCashEntryController`

```java
CashEntryResultVO createEntry(@PathVariable("shiftNo") String shiftNo, @Valid @RequestBody CreateCashEntryReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CashShiftService` / `ai.neargo.shop.food.service.impl.CashShiftServiceImpl`

```java
CashEntryResultVO createEntry(String entityNo, String currentStoreNo, String shiftNo, String actorNo, CreateCashEntryReq req, String idempotencyKey);
```

持久化：`FoodMappers.CashShiftMapper.selectOne`（READ）；`FoodMappers.CashEntryMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-070 · POST /biz/x/food/cash-shifts/{shiftNo}:count

Controller：`ai.neargo.shop.food.api.biz.BizCashShiftController`

```java
CountCashShiftResultVO countCashShift(@PathVariable("shiftNo") String shiftNo, @Valid @RequestBody CountCashShiftReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CashShiftService` / `ai.neargo.shop.food.service.impl.CashShiftServiceImpl`

```java
CountCashShiftResultVO countCashShift(String entityNo, String currentStoreNo, String shiftNo, String actorNo, CountCashShiftReq req, String idempotencyKey);
```

持久化：`FoodMappers.CashShiftMapper.selectOne`（READ）；`FoodMappers.CashEntryMapper.selectList`（READ）；`FoodMappers.CashPaymentLinkMapper.selectList`（READ）；`FoodMappers.CashCountMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ReconciliationPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 先冻结权威收款水位核对后短事务记录实盘；盲盘前不泄露应有现金。

#### FOOD-API-071 · POST /biz/x/food/cash-shifts/{shiftNo}:handover

Controller：`ai.neargo.shop.food.api.biz.BizCashShiftController`

```java
HandoverCashShiftResultVO handoverCashShift(@PathVariable("shiftNo") String shiftNo, @Valid @RequestBody HandoverCashShiftReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CashShiftService` / `ai.neargo.shop.food.service.impl.CashShiftServiceImpl`

```java
HandoverCashShiftResultVO handoverCashShift(String entityNo, String currentStoreNo, String shiftNo, String actorNo, HandoverCashShiftReq req, String idempotencyKey);
```

持久化：`FoodMappers.CashShiftMapper.selectOne`（READ）；`FoodMappers.CashCountMapper.selectList`（READ）；`FoodMappers.CashShiftMapper.handoverIfVersion`（WRITE）；`FoodMappers.CashCountMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

### MOD-09 营业日、日结与对账管理

#### FOOD-API-072 · POST /biz/x/food/stores/{storeNo}/closes

Controller：`ai.neargo.shop.food.api.biz.BizBusinessCloseController`

```java
BusinessCloseResultVO createClose(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateCloseReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BusinessCloseService` / `ai.neargo.shop.food.service.impl.BusinessCloseServiceImpl`

```java
BusinessCloseResultVO createClose(String entityNo, String storeNo, String actorNo, CreateCloseReq req, String idempotencyKey);
```

持久化：`FoodMappers.BusinessDayMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）；`FoodMappers.ReturnMapper.selectList`（READ）；`FoodMappers.CashShiftMapper.selectList`（READ）；`FoodMappers.SyncBatchMapper.selectList`（READ）；`FoodMappers.BusinessDayMapper.insert`（WRITE）；`FoodMappers.CloseRunMapper.insert`（WRITE）；`FoodMappers.CloseBalanceMapper.insert`（WRITE）；`FoodMappers.CloseExceptionMapper.insert`（WRITE）。

跨域：`ReconciliationPort`、`DiningReportPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 事实冻结水位，未决资金/离线同步列明；不把报表结果当收款。

#### FOOD-API-073 · GET /biz/x/food/closes/{closeNo}

Controller：`ai.neargo.shop.food.api.biz.BizBusinessCloseController`

```java
BusinessCloseResultVO getClose(@PathVariable("closeNo") String closeNo);
```

Service / Impl：`ai.neargo.shop.food.service.BusinessCloseService` / `ai.neargo.shop.food.service.impl.BusinessCloseServiceImpl`

```java
BusinessCloseResultVO getClose(String entityNo, String currentStoreNo, String closeNo);
```

持久化：`FoodMappers.CloseRunMapper.selectOne`（READ）；`FoodMappers.CloseBalanceMapper.selectList`（READ）；`FoodMappers.CloseExceptionMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-074 · POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:carry

Controller：`ai.neargo.shop.food.api.biz.BizCloseExceptionController`

```java
OperationVO carryException(@PathVariable("closeNo") String closeNo, @PathVariable("exceptionNo") String exceptionNo, @Valid @RequestBody CarryExceptionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BusinessCloseService` / `ai.neargo.shop.food.service.impl.BusinessCloseServiceImpl`

```java
OperationVO carryException(String entityNo, String currentStoreNo, String closeNo, String exceptionNo, String actorNo, CarryExceptionReq req, String idempotencyKey);
```

持久化：`FoodMappers.CloseRunMapper.selectOne`（READ）；`FoodMappers.CloseExceptionMapper.selectList`（READ）；`FoodMappers.BusinessDayMapper.selectList`（READ）；`FoodMappers.CloseExceptionMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-075 · POST /biz/x/food/closes/{closeNo}:sign

Controller：`ai.neargo.shop.food.api.biz.BizBusinessCloseController`

```java
BusinessCloseResultVO signClose(@PathVariable("closeNo") String closeNo, @Valid @RequestBody SignCloseReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BusinessCloseService` / `ai.neargo.shop.food.service.impl.BusinessCloseServiceImpl`

```java
BusinessCloseResultVO signClose(String entityNo, String currentStoreNo, String closeNo, String actorNo, SignCloseReq req, String idempotencyKey);
```

持久化：`FoodMappers.BusinessDayMapper.selectOne`（READ）；`FoodMappers.CloseRunMapper.selectList`（READ）；`FoodMappers.CloseBalanceMapper.selectList`（READ）；`FoodMappers.CloseExceptionMapper.selectList`（READ）；`FoodMappers.CloseRunMapper.signIfVersionHashAndWatermark`（WRITE）；`FoodMappers.BusinessDayMapper.replaceCurrentCloseIfVersion`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ReconciliationPort`、`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-076 · POST /biz/x/food/closes/{closeNo}/adjustments

Controller：`ai.neargo.shop.food.api.biz.BizBusinessCloseController`

```java
CreateAdjustmentBusinessCloseResultVO createAdjustment(@PathVariable("closeNo") String closeNo, @Valid @RequestBody CreateCloseAdjustmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BusinessCloseService` / `ai.neargo.shop.food.service.impl.BusinessCloseServiceImpl`

```java
CreateAdjustmentBusinessCloseResultVO createAdjustment(String entityNo, String currentStoreNo, String closeNo, String actorNo, CreateCloseAdjustmentReq req, String idempotencyKey);
```

持久化：`FoodMappers.CloseRunMapper.selectOne`（READ）；`FoodMappers.BusinessDayMapper.selectList`（READ）；`FoodMappers.CloseAdjustmentMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ReconciliationPort`、`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

### MOD-10 经营报表管理

#### FOOD-API-077 · GET /biz/x/food/reports/{reportCode}

Controller：`ai.neargo.shop.food.api.biz.BizDiningReportController`

```java
DiningReportResultVO getReport(@PathVariable("reportCode") String reportCode, @Valid @ModelAttribute GetReportQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReportService` / `ai.neargo.shop.food.service.impl.DiningReportServiceImpl`

```java
DiningReportResultVO getReport(String entityNo, String currentStoreNo, String reportCode, Set<String> authorizedStoreNos, GetReportQuery query);
```

持久化：`FoodMappers.MetricDefinitionMapper.selectOne`（READ）；`FoodMappers.ReportRunMapper.selectList`（READ）。

跨域：`DiningReportPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-078 · POST /biz/x/food/report-exports

Controller：`ai.neargo.shop.food.api.biz.BizDiningReportExportController`

```java
DiningReportExportResultVO createReportExport(@Valid @RequestBody CreateReportExportReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReportService` / `ai.neargo.shop.food.service.impl.DiningReportServiceImpl`

```java
DiningReportExportResultVO createReportExport(String entityNo, String currentStoreNo, Set<String> authorizedStoreNos, String actorNo, CreateReportExportReq req, String idempotencyKey);
```

持久化：`FoodMappers.MetricDefinitionMapper.selectList`（READ）。

跨域：`DiningReportPort`。

事务：**SHARED_JOB**。共享任务持有导出/投影写入，本域只调用合同和保存明确的运行引用。 导出任务由共享任务/报表服务创建，餐饮没有导出任务表，不得用fnb_report_run冒充导出任务。

#### FOOD-API-079 · GET /biz/x/food/report-exports/{exportNo}

Controller：`ai.neargo.shop.food.api.biz.BizDiningReportExportController`

```java
GetReportExportDiningReportExportResultVO getReportExport(@PathVariable("exportNo") String exportNo);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReportService` / `ai.neargo.shop.food.service.impl.DiningReportServiceImpl`

```java
GetReportExportDiningReportExportResultVO getReportExport(String entityNo, String currentStoreNo, String exportNo, Set<String> authorizedStoreNos, String actorNo);
```

持久化：无餐饮本地表写入/查询。

跨域：`DiningReportPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 查询共享导出任务，验证本人/授权范围。

#### FOOD-API-080 · POST /ops/x/food/report-rebuilds

Controller：`ai.neargo.shop.food.api.ops.OpsDiningReportRebuildController`

```java
DiningReportRebuildResultVO createReportRebuild(@Valid @RequestBody CreateReportRebuildReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReportService` / `ai.neargo.shop.food.service.impl.DiningReportServiceImpl`

```java
DiningReportRebuildResultVO createReportRebuild(String entityNo, String currentStoreNo, Set<String> authorizedStoreNos, String actorNo, CreateReportRebuildReq req, String idempotencyKey);
```

持久化：`FoodMappers.MetricDefinitionMapper.selectList`（READ）；`FoodMappers.ReportRunMapper.selectList`（READ）；`FoodMappers.ReportRunMapper.insert`（WRITE）；`FoodMappers.ReportRunMapper.update`（WRITE）。

跨域：`DiningReportPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 报表投影写入在shop-report由DAO执行，food只跟踪运行引用；不重开营业日。

### MOD-11 专项业态管理

#### FOOD-API-085 · POST /biz/x/food/checks/{checkNo}/entitlements

Controller：`ai.neargo.shop.food.api.biz.BizDiningEntitlementController`

```java
DiningEntitlementResultVO createEntitlement(@PathVariable("checkNo") String checkNo, @Valid @RequestBody CreateEntitlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningEntitlementService` / `ai.neargo.shop.food.service.impl.DiningEntitlementServiceImpl`

```java
DiningEntitlementResultVO createEntitlement(String entityNo, String currentStoreNo, String checkNo, String actorNo, CreateEntitlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.DiningEntitlementMapper.selectList`（READ）；`FoodMappers.DiningEntitlementMapper.insert`（WRITE）。

跨域：`OrderPlacementPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-086 · POST /biz/x/food/entitlements/{entitlementNo}:extend

Controller：`ai.neargo.shop.food.api.biz.BizDiningEntitlementController`

```java
OperationVO extendEntitlement(@PathVariable("entitlementNo") String entitlementNo, @Valid @RequestBody ExtendEntitlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningEntitlementService` / `ai.neargo.shop.food.service.impl.DiningEntitlementServiceImpl`

```java
OperationVO extendEntitlement(String entityNo, String currentStoreNo, String entitlementNo, String actorNo, ExtendEntitlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.DiningEntitlementMapper.selectOne`（READ）；`FoodMappers.DiningEntitlementMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`OrderAdjustmentPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-087 · POST /biz/x/food/stores/{storeNo}/banquet-plans

Controller：`ai.neargo.shop.food.api.biz.BizBanquetPlanController`

```java
BanquetPlanResultVO createBanquetPlan(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateBanquetPlanReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BanquetPlanService` / `ai.neargo.shop.food.service.impl.BanquetPlanServiceImpl`

```java
BanquetPlanResultVO createBanquetPlan(String entityNo, String storeNo, String actorNo, CreateBanquetPlanReq req, String idempotencyKey);
```

持久化：`FoodMappers.ReservationMapper.selectList`（READ）；`FoodMappers.BanquetPlanMapper.insert`（WRITE）。

跨域：`OrderPlacementPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-088 · POST /biz/x/food/banquet-plans/{planNo}:confirm-attendance

Controller：`ai.neargo.shop.food.api.biz.BizBanquetPlanController`

```java
OperationVO confirmAttendanceBanquetPlan(@PathVariable("planNo") String planNo, @Valid @RequestBody ConfirmAttendanceBanquetPlanReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BanquetPlanService` / `ai.neargo.shop.food.service.impl.BanquetPlanServiceImpl`

```java
OperationVO confirmAttendanceBanquetPlan(String entityNo, String currentStoreNo, String planNo, String actorNo, ConfirmAttendanceBanquetPlanReq req, String idempotencyKey);
```

持久化：`FoodMappers.BanquetPlanMapper.selectOne`（READ）；`FoodMappers.BanquetPlanMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`OrderPlacementPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-089 · POST /biz/x/food/stores/{storeNo}/custodies

Controller：`ai.neargo.shop.food.api.biz.BizBottleCustodyController`

```java
BottleCustodyResultVO createCustody(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateCustodyReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BottleCustodyService` / `ai.neargo.shop.food.service.impl.BottleCustodyServiceImpl`

```java
BottleCustodyResultVO createCustody(String entityNo, String storeNo, String actorNo, CreateCustodyReq req, String idempotencyKey);
```

持久化：`FoodMappers.BottleCustodyMapper.selectList`（READ）；`FoodMappers.BottleCustodyMapper.insert`（WRITE）；`FoodMappers.CustodyEntryMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-090 · POST /biz/x/food/custodies/{custodyNo}/entries

Controller：`ai.neargo.shop.food.api.biz.BizBottleCustodyController`

```java
OperationVO createEntry(@PathVariable("custodyNo") String custodyNo, @Valid @RequestBody CreateCustodyEntryReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BottleCustodyService` / `ai.neargo.shop.food.service.impl.BottleCustodyServiceImpl`

```java
OperationVO createEntry(String entityNo, String currentStoreNo, String custodyNo, String actorNo, CreateCustodyEntryReq req, String idempotencyKey);
```

持久化：`FoodMappers.BottleCustodyMapper.selectOne`（READ）；`FoodMappers.BottleCustodyMapper.withdrawIfRemaining`（WRITE）；`FoodMappers.CustodyEntryMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-091 · POST /biz/x/food/settlements/{settlementNo}/room-charges

Controller：`ai.neargo.shop.food.api.biz.BizRoomChargeController`

```java
RoomChargeResultVO createRoomCharge(@PathVariable("settlementNo") String settlementNo, @Valid @RequestBody CreateRoomChargeReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.RoomChargeService` / `ai.neargo.shop.food.service.impl.RoomChargeServiceImpl`

```java
RoomChargeResultVO createRoomCharge(String entityNo, String currentStoreNo, String settlementNo, String actorNo, CreateRoomChargeReq req, String idempotencyKey);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.RoomChargeIntentMapper.selectList`（READ）；`FoodMappers.RoomChargeIntentMapper.update`（WRITE）；`FoodMappers.ChargeIntentMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`AccountsReceivablePort`、`RoomAccountPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

### MOD-12 第三方接入与离线营业

#### FOOD-API-082 · PUT /biz/x/food/integrations/{provider}/mappings

Controller：`ai.neargo.shop.food.api.biz.BizExternalMappingController`

```java
OperationVO saveMapping(@PathVariable("provider") String provider, @Valid @RequestBody SaveMappingReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ExternalMappingService` / `ai.neargo.shop.food.service.impl.ExternalMappingServiceImpl`

```java
OperationVO saveMapping(String entityNo, String currentStoreNo, String provider, String actorNo, SaveMappingReq req, String idempotencyKey);
```

持久化：`FoodMappers.ExternalMappingMapper.selectOne`（READ）；`FoodMappers.ExternalMappingMapper.insert`（WRITE）；`FoodMappers.ExternalMappingMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-083 · GET /ops/x/food/integration-exceptions

Controller：`ai.neargo.shop.food.api.ops.OpsIntegrationExceptionController`

```java
PageData<Map<String, Object>> listIntegrationExceptions(@Valid @ModelAttribute ListIntegrationExceptionsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.IntegrationExceptionService` / `ai.neargo.shop.food.service.impl.IntegrationExceptionServiceImpl`

```java
PageData<Map<String, Object>> listIntegrationExceptions(String entityNo, String currentStoreNo, ListIntegrationExceptionsQuery query);
```

持久化：`FoodMappers.IntegrationExceptionMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-084 · POST /internal/food/sync-batches

Controller：`ai.neargo.shop.food.api.internal.InternalSyncBatchController`

```java
SyncBatchResultVO createSyncBatch(@Valid @RequestBody CreateSyncBatchReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.SyncBatchService` / `ai.neargo.shop.food.service.impl.SyncBatchServiceImpl`

```java
SyncBatchResultVO createSyncBatch(String entityNo, String currentStoreNo, String authenticatedDeviceNo, String actorNo, CreateSyncBatchReq req, String idempotencyKey);
```

持久化：`FoodMappers.EdgeLeaseMapper.selectList`（READ）；`FoodMappers.SyncBatchMapper.selectList`（READ）；`FoodMappers.ExternalMappingMapper.selectList`（READ）；`FoodMappers.EdgeLeaseMapper.selectLeaseByStoreEpochForUpdate`（LOCK）；`FoodMappers.SyncBatchMapper.insertSyncBatchIfAbsent`（WRITE）；`FoodMappers.SyncBatchMapper.selectAcceptedThrough`（READ）；`FoodMappers.IntegrationExceptionMapper.insert`（WRITE）。

跨域：`ExternalRestaurantPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 同步入口仅验签/认领/排入恢复；消费合法事件须走业务Service，不能直接批写ord/prd/pay。

#### FOOD-API-146 · POST /biz/x/food/menu-channel-bindings/{bindingNo}/syncs

Controller：`ai.neargo.shop.food.api.biz.BizCatalogSyncController`

```java
CatalogSyncResultVO createCatalogSync(@PathVariable("bindingNo") String bindingNo, @Valid @RequestBody CreateCatalogSyncReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CatalogSyncService` / `ai.neargo.shop.food.service.impl.CatalogSyncServiceImpl`

```java
CatalogSyncResultVO createCatalogSync(String entityNo, String currentStoreNo, String bindingNo, String actorNo, CreateCatalogSyncReq req, String idempotencyKey);
```

持久化：`FoodMappers.CatalogSyncJobMapper.selectOne`（READ）；`FoodMappers.CatalogSyncItemMapper.selectList`（READ）；`FoodMappers.MenuChannelBindingMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）；`FoodMappers.CatalogSyncJobMapper.update`（WRITE）；`FoodMappers.CatalogSyncItemMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_WRITE**。共享任务持有导出/投影写入，本域只调用合同和保存明确的运行引用。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

#### FOOD-API-147 · GET /biz/x/food/catalog-syncs/{syncNo}

Controller：`ai.neargo.shop.food.api.biz.BizCatalogSyncController`

```java
CatalogSyncResultVO getCatalogSync(@PathVariable("syncNo") String syncNo);
```

Service / Impl：`ai.neargo.shop.food.service.CatalogSyncService` / `ai.neargo.shop.food.service.impl.CatalogSyncServiceImpl`

```java
CatalogSyncResultVO getCatalogSync(String entityNo, String currentStoreNo, String syncNo);
```

持久化：`FoodMappers.CatalogSyncJobMapper.selectOne`（READ）；`FoodMappers.CatalogSyncItemMapper.selectList`（READ）；`FoodMappers.MenuChannelBindingMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

### MOD-13 营业配置与权限治理

#### FOOD-API-001 · GET /biz/x/food/stores/{storeNo}/policy

Controller：`ai.neargo.shop.food.api.biz.BizStoreOperationPolicyController`

```java
StoreOperationPolicyResultVO getPolicy(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute GetPolicyQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.StoreOperationService` / `ai.neargo.shop.food.service.impl.StoreOperationServiceImpl`

```java
StoreOperationPolicyResultVO getPolicy(String entityNo, String storeNo, GetPolicyQuery query);
```

持久化：`FoodMappers.StorePolicyMapper.selectList`（READ）。

跨域：`StoreDirectoryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-002 · POST /biz/x/food/stores/{storeNo}/policies

Controller：`ai.neargo.shop.food.api.biz.BizStoreOperationPolicyController`

```java
OperationVO createPolicy(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreatePolicyReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.StoreOperationService` / `ai.neargo.shop.food.service.impl.StoreOperationServiceImpl`

```java
OperationVO createPolicy(String entityNo, String storeNo, String actorNo, CreatePolicyReq req, String idempotencyKey);
```

持久化：`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.StorePolicyMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`StoreDirectoryPort`、`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-003 · PUT /biz/x/food/stores/{storeNo}/calendar/{localDate}

Controller：`ai.neargo.shop.food.api.biz.BizServiceCalendarController`

```java
OperationVO saveCalendar(@PathVariable("storeNo") String storeNo, @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @PathVariable("localDate") LocalDate localDate, @Valid @RequestBody SaveCalendarReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.StoreOperationService` / `ai.neargo.shop.food.service.impl.StoreOperationServiceImpl`

```java
OperationVO saveCalendar(String entityNo, String storeNo, LocalDate localDate, String actorNo, SaveCalendarReq req, String idempotencyKey);
```

持久化：`FoodMappers.StorePolicyMapper.selectOne`（READ）；`FoodMappers.ServiceCalendarMapper.selectList`（READ）；`FoodMappers.ServiceCalendarMapper.insert`（WRITE）；`FoodMappers.ServiceCalendarMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-081 · PUT /ops/x/food/stores/{storeNo}/capabilities

Controller：`ai.neargo.shop.food.api.ops.OpsDiningCapabilityController`

```java
OperationVO saveCapability(@PathVariable("storeNo") String storeNo, @Valid @RequestBody SaveCapabilityReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCapabilityService` / `ai.neargo.shop.food.service.impl.DiningCapabilityServiceImpl`

```java
OperationVO saveCapability(String entityNo, String storeNo, String actorNo, SaveCapabilityReq req, String idempotencyKey);
```

持久化：`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.StorePolicyMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 平台授权工单与CAS，能力变更发布新策略版本，不覆盖旧营业快照。

## 三、逐Mapper / DAO的方法签名

共享prn_/rpt_为待建设能力的目标方法，不能在food-core创建其副本。下列原子写方法返回int时必须处理0行冲突；不能把核心金额/库存/资金守卫伪装成餐饮表单条SQL。

### fnb_dish

`ai.neargo.shop.food.mapper.FoodMappers.DishMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbDish selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDish> queryWrapper);
// inherited BaseMapper
List<FnbDish> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDish> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbDish> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbDish> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDish> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDish> queryWrapper);
// inherited BaseMapper
int insert(FnbDish row);
// inherited BaseMapper
int update(FnbDish changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDish> updateWrapper);
```



### fnb_bundle

`ai.neargo.shop.food.mapper.FoodMappers.BundleMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbBundle selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundle> queryWrapper);
// inherited BaseMapper
List<FnbBundle> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundle> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBundle> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBundle> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundle> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundle> queryWrapper);
// inherited BaseMapper
int insert(FnbBundle row);
// inherited BaseMapper
int update(FnbBundle changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundle> updateWrapper);
```



### fnb_bundle_group

`ai.neargo.shop.food.mapper.FoodMappers.BundleGroupMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbBundleGroup selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleGroup> queryWrapper);
// inherited BaseMapper
List<FnbBundleGroup> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleGroup> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBundleGroup> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBundleGroup> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleGroup> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleGroup> queryWrapper);
// inherited BaseMapper
int insert(FnbBundleGroup row);
// inherited BaseMapper
int update(FnbBundleGroup changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleGroup> updateWrapper);
```



### fnb_bundle_choice

`ai.neargo.shop.food.mapper.FoodMappers.BundleChoiceMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbBundleChoice selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleChoice> queryWrapper);
// inherited BaseMapper
List<FnbBundleChoice> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleChoice> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBundleChoice> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBundleChoice> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleChoice> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleChoice> queryWrapper);
// inherited BaseMapper
int insert(FnbBundleChoice row);
// inherited BaseMapper
int update(FnbBundleChoice changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleChoice> updateWrapper);
```



### fnb_modifier

`ai.neargo.shop.food.mapper.FoodMappers.ModifierMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbModifier selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifier> queryWrapper);
// inherited BaseMapper
List<FnbModifier> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifier> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbModifier> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbModifier> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifier> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifier> queryWrapper);
// inherited BaseMapper
int insert(FnbModifier row);
// inherited BaseMapper
int update(FnbModifier changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifier> updateWrapper);
```



### fnb_modifier_component

`ai.neargo.shop.food.mapper.FoodMappers.ModifierComponentMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbModifierComponent selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifierComponent> queryWrapper);
// inherited BaseMapper
List<FnbModifierComponent> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifierComponent> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbModifierComponent> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbModifierComponent> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifierComponent> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifierComponent> queryWrapper);
// inherited BaseMapper
int insert(FnbModifierComponent row);
// inherited BaseMapper
int update(FnbModifierComponent changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifierComponent> updateWrapper);
```



### fnb_option_group

`ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbOptionGroup selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroup> queryWrapper);
// inherited BaseMapper
List<FnbOptionGroup> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroup> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbOptionGroup> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbOptionGroup> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroup> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroup> queryWrapper);
// inherited BaseMapper
int insert(FnbOptionGroup row);
// inherited BaseMapper
int update(FnbOptionGroup changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroup> updateWrapper);
```



### fnb_option_group_item

`ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbOptionGroupItem selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroupItem> queryWrapper);
// inherited BaseMapper
List<FnbOptionGroupItem> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroupItem> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbOptionGroupItem> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbOptionGroupItem> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroupItem> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroupItem> queryWrapper);
// inherited BaseMapper
int insert(FnbOptionGroupItem row);
// inherited BaseMapper
int update(FnbOptionGroupItem changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroupItem> updateWrapper);
```



### fnb_dish_option_group

`ai.neargo.shop.food.mapper.FoodMappers.DishOptionGroupMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbDishOptionGroup selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDishOptionGroup> queryWrapper);
// inherited BaseMapper
List<FnbDishOptionGroup> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDishOptionGroup> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbDishOptionGroup> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbDishOptionGroup> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDishOptionGroup> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDishOptionGroup> queryWrapper);
// inherited BaseMapper
int insert(FnbDishOptionGroup row);
// inherited BaseMapper
int update(FnbDishOptionGroup changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDishOptionGroup> updateWrapper);
```



### fnb_menu

`ai.neargo.shop.food.mapper.FoodMappers.MenuMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenu selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenu> queryWrapper);
// inherited BaseMapper
List<FnbMenu> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenu> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenu> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenu> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenu> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenu> queryWrapper);
// inherited BaseMapper
int insert(FnbMenu row);
// inherited BaseMapper
int update(FnbMenu changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenu> updateWrapper);
```



### fnb_menu_store

`ai.neargo.shop.food.mapper.FoodMappers.MenuStoreMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuStore selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuStore> queryWrapper);
// inherited BaseMapper
List<FnbMenuStore> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuStore> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuStore> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuStore> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuStore> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuStore> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuStore row);
// inherited BaseMapper
int update(FnbMenuStore changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuStore> updateWrapper);
```



### fnb_menu_context

`ai.neargo.shop.food.mapper.FoodMappers.MenuContextMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuContext selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuContext> queryWrapper);
// inherited BaseMapper
List<FnbMenuContext> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuContext> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuContext> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuContext> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuContext> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuContext> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuContext row);
// inherited BaseMapper
int update(FnbMenuContext changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuContext> updateWrapper);
```



### fnb_menu_schedule

`ai.neargo.shop.food.mapper.FoodMappers.MenuScheduleMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuSchedule selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuSchedule> queryWrapper);
// inherited BaseMapper
List<FnbMenuSchedule> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuSchedule> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuSchedule> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuSchedule> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuSchedule> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuSchedule> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuSchedule row);
// inherited BaseMapper
int update(FnbMenuSchedule changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuSchedule> updateWrapper);
```



### fnb_menu_exception

`ai.neargo.shop.food.mapper.FoodMappers.MenuExceptionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuException selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuException> queryWrapper);
// inherited BaseMapper
List<FnbMenuException> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuException> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuException> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuException> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuException> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuException> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuException row);
// inherited BaseMapper
int update(FnbMenuException changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuException> updateWrapper);
```



### fnb_menu_category

`ai.neargo.shop.food.mapper.FoodMappers.MenuCategoryMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuCategory selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuCategory> queryWrapper);
// inherited BaseMapper
List<FnbMenuCategory> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuCategory> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuCategory> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuCategory> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuCategory> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuCategory> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuCategory row);
// inherited BaseMapper
int update(FnbMenuCategory changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuCategory> updateWrapper);
```



### fnb_menu_item

`ai.neargo.shop.food.mapper.FoodMappers.MenuItemMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuItem selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItem> queryWrapper);
// inherited BaseMapper
List<FnbMenuItem> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItem> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuItem> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuItem> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItem> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItem> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuItem row);
// inherited BaseMapper
int update(FnbMenuItem changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItem> updateWrapper);
```



### fnb_menu_item_sku

`ai.neargo.shop.food.mapper.FoodMappers.MenuItemSkuMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuItemSku selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemSku> queryWrapper);
// inherited BaseMapper
List<FnbMenuItemSku> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemSku> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuItemSku> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuItemSku> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemSku> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemSku> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuItemSku row);
// inherited BaseMapper
int update(FnbMenuItemSku changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemSku> updateWrapper);
```



### fnb_station

`ai.neargo.shop.food.mapper.FoodMappers.StationMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbStation selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStation> queryWrapper);
// inherited BaseMapper
List<FnbStation> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStation> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbStation> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbStation> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStation> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStation> queryWrapper);
// inherited BaseMapper
int insert(FnbStation row);
// inherited BaseMapper
int update(FnbStation changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStation> updateWrapper);
```



### fnb_sku_station

`ai.neargo.shop.food.mapper.FoodMappers.SkuStationMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbSkuStation selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSkuStation> queryWrapper);
// inherited BaseMapper
List<FnbSkuStation> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSkuStation> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbSkuStation> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbSkuStation> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSkuStation> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSkuStation> queryWrapper);
// inherited BaseMapper
int insert(FnbSkuStation row);
// inherited BaseMapper
int update(FnbSkuStation changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSkuStation> updateWrapper);
```



### fnb_store_policy

`ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbStorePolicy selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStorePolicy> queryWrapper);
// inherited BaseMapper
List<FnbStorePolicy> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStorePolicy> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbStorePolicy> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbStorePolicy> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStorePolicy> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStorePolicy> queryWrapper);
// inherited BaseMapper
int insert(FnbStorePolicy row);
// inherited BaseMapper
int update(FnbStorePolicy changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStorePolicy> updateWrapper);
```



### fnb_service_calendar

`ai.neargo.shop.food.mapper.FoodMappers.ServiceCalendarMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbServiceCalendar selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceCalendar> queryWrapper);
// inherited BaseMapper
List<FnbServiceCalendar> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceCalendar> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceCalendar> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceCalendar> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceCalendar> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceCalendar> queryWrapper);
// inherited BaseMapper
int insert(FnbServiceCalendar row);
// inherited BaseMapper
int update(FnbServiceCalendar changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceCalendar> updateWrapper);
```



### fnb_table

`ai.neargo.shop.food.mapper.FoodMappers.TableMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbTable selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTable> queryWrapper);
// inherited BaseMapper
List<FnbTable> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTable> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbTable> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbTable> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTable> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTable> queryWrapper);
// inherited BaseMapper
int insert(FnbTable row);
// inherited BaseMapper
int update(FnbTable changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTable> updateWrapper);
FnbTable selectForUpdateByStoreAndTableNo(String entityNo, String storeNo, String tableNo);
int updateCapacityIfVersion(String entityNo, String storeNo, String tableNo, int capacity, long expectedVersion);
```

- `selectForUpdateByStoreAndTableNo`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。
- `updateCapacityIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_table_token

`ai.neargo.shop.food.mapper.FoodMappers.TableTokenMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbTableToken selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableToken> queryWrapper);
// inherited BaseMapper
List<FnbTableToken> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableToken> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbTableToken> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbTableToken> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableToken> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableToken> queryWrapper);
// inherited BaseMapper
int insert(FnbTableToken row);
// inherited BaseMapper
int update(FnbTableToken changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableToken> updateWrapper);
FnbTableToken selectValidByTokenHash(byte[] tokenHash, Instant now);
```

- `selectValidByTokenHash`：仅允许桌码会话启动：按唯一哈希精确查未吊销且未过期令牌，限制速率和失败重试；不返回明文令牌。查验成功后从记录解析主体/门店再访问其他表，不是通用无主体查询入口。

### fnb_reservation

`ai.neargo.shop.food.mapper.FoodMappers.ReservationMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbReservation selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservation> queryWrapper);
// inherited BaseMapper
List<FnbReservation> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservation> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbReservation> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbReservation> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservation> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservation> queryWrapper);
// inherited BaseMapper
int insert(FnbReservation row);
// inherited BaseMapper
int update(FnbReservation changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservation> updateWrapper);
```



### fnb_check

`ai.neargo.shop.food.mapper.FoodMappers.CheckMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCheck selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheck> queryWrapper);
// inherited BaseMapper
List<FnbCheck> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheck> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCheck> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCheck> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheck> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheck> queryWrapper);
// inherited BaseMapper
int insert(FnbCheck row);
// inherited BaseMapper
int update(FnbCheck changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheck> updateWrapper);
int updatePartySizeIfVersion(String entityNo, String storeNo, String checkNo, int partySize, long expectedVersion);
int closeIfSettlementConfirmed(String entityNo, String storeNo, String checkNo, Instant closedAt, long expectedVersion);
```

- `updatePartySizeIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `closeIfSettlementConfirmed`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_table_occupancy

`ai.neargo.shop.food.mapper.FoodMappers.TableOccupancyMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbTableOccupancy selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOccupancy> queryWrapper);
// inherited BaseMapper
List<FnbTableOccupancy> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOccupancy> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbTableOccupancy> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbTableOccupancy> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOccupancy> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOccupancy> queryWrapper);
// inherited BaseMapper
int insert(FnbTableOccupancy row);
// inherited BaseMapper
int update(FnbTableOccupancy changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOccupancy> updateWrapper);
List<FnbTableOccupancy> selectActiveByTableForUpdate(String entityNo, String tableNo);
int endOccupancyIfActive(String entityNo, String occupancyNo, Instant endedAt, long expectedVersion);
```

- `selectActiveByTableForUpdate`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。
- `endOccupancyIfActive`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_check_relation

`ai.neargo.shop.food.mapper.FoodMappers.CheckRelationMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCheckRelation selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckRelation> queryWrapper);
// inherited BaseMapper
List<FnbCheckRelation> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckRelation> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckRelation> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckRelation> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckRelation> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckRelation> queryWrapper);
// inherited BaseMapper
int insert(FnbCheckRelation row);
// inherited BaseMapper
int update(FnbCheckRelation changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckRelation> updateWrapper);
```



### fnb_guest_session

`ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbGuestSession selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbGuestSession> queryWrapper);
// inherited BaseMapper
List<FnbGuestSession> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbGuestSession> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbGuestSession> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbGuestSession> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbGuestSession> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbGuestSession> queryWrapper);
// inherited BaseMapper
int insert(FnbGuestSession row);
// inherited BaseMapper
int update(FnbGuestSession changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbGuestSession> updateWrapper);
```



### fnb_seat

`ai.neargo.shop.food.mapper.FoodMappers.SeatMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbSeat selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSeat> queryWrapper);
// inherited BaseMapper
List<FnbSeat> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSeat> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbSeat> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbSeat> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSeat> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSeat> queryWrapper);
// inherited BaseMapper
int insert(FnbSeat row);
// inherited BaseMapper
int update(FnbSeat changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSeat> updateWrapper);
```



### fnb_check_cart

`ai.neargo.shop.food.mapper.FoodMappers.CheckCartMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCheckCart selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCart> queryWrapper);
// inherited BaseMapper
List<FnbCheckCart> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCart> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckCart> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckCart> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCart> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCart> queryWrapper);
// inherited BaseMapper
int insert(FnbCheckCart row);
// inherited BaseMapper
int update(FnbCheckCart changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCart> updateWrapper);
FnbCheckCart lockCartIfVersion(String entityNo, String storeNo, String cartNo, long expectedVersion);
int closeCartIfNoPlacingBatch(String entityNo, String storeNo, String cartNo, long expectedVersion);
```

- `lockCartIfVersion`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。
- `closeCartIfNoPlacingBatch`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_check_cart_item

`ai.neargo.shop.food.mapper.FoodMappers.CheckCartItemMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCheckCartItem selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCartItem> queryWrapper);
// inherited BaseMapper
List<FnbCheckCartItem> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCartItem> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckCartItem> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckCartItem> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCartItem> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCartItem> queryWrapper);
// inherited BaseMapper
int insert(FnbCheckCartItem row);
// inherited BaseMapper
int update(FnbCheckCartItem changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCartItem> updateWrapper);
List<FnbCheckCartItem> selectSelectedLinesForUpdate(String entityNo, String cartNo, List<String> sourceLineRefs);
int archiveSelectedSubmittedLines(String entityNo, String cartItemNo, long expectedVersion);
FnbCheckCartItem history(String entityNo, String cartNo, String sourceLineRef);
int retain(String entityNo, String cartNo, String sourceLineRef, BigDecimal qty, String seatNo, String courseNo, String selection, int deleted, String actorNo, LocalDateTime at);
```

- `selectSelectedLinesForUpdate`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。
- `archiveSelectedSubmittedLines`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `history`：原逻辑归档行也读取；显式主体/车/源行与FOR UPDATE，源行身份不可重绑商品或SKU。
- `retain`：保留原源行/addedBy，按已锁owner/cart CAS更新或复活墓碑；影响行数必须为1。

### fnb_order_batch

`ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbOrderBatch selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderBatch> queryWrapper);
// inherited BaseMapper
List<FnbOrderBatch> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderBatch> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderBatch> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderBatch> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderBatch> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderBatch> queryWrapper);
// inherited BaseMapper
int insert(FnbOrderBatch row);
// inherited BaseMapper
int update(FnbOrderBatch changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderBatch> updateWrapper);
int insertBatchIfAbsent(FnbOrderBatch row);
int bindCoreResultIfVersion(String entityNo, String storeNo, String batchNo, long expectedVersion);
int advancePlacementIfStatus(String entityNo, String storeNo, String batchNo, String expectedStatus, String targetStatus, long expectedVersion);
FnbOrderBatch selectScopedByKeyForUpdate(String entityNo, String storeNo, String batchNo);
```

- `insertBatchIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `bindCoreResultIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `advancePlacementIfStatus`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `selectScopedByKeyForUpdate`：必须在当前餐饮本地事务内调用，锁到的是已存在父/实体行；空结果不能充当稳定仲裁者。

### fnb_core_order_binding

`ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCoreOrderBinding selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreOrderBinding> queryWrapper);
// inherited BaseMapper
List<FnbCoreOrderBinding> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreOrderBinding> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCoreOrderBinding> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCoreOrderBinding> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreOrderBinding> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreOrderBinding> queryWrapper);
// inherited BaseMapper
int insert(FnbCoreOrderBinding row);
// inherited BaseMapper
int update(FnbCoreOrderBinding changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreOrderBinding> updateWrapper);
```



### fnb_order_line

`ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbOrderLine selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderLine> queryWrapper);
// inherited BaseMapper
List<FnbOrderLine> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderLine> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderLine> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderLine> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderLine> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderLine> queryWrapper);
// inherited BaseMapper
int insert(FnbOrderLine row);
// inherited BaseMapper
int update(FnbOrderLine changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderLine> updateWrapper);
FnbOrderLine selectScopedByKeyForUpdate(String entityNo, String foodLineNo);
```

- `selectScopedByKeyForUpdate`：必须在当前餐饮本地事务内调用，锁到的是已存在父/实体行；空结果不能充当稳定仲裁者。

### fnb_core_line_allocation

`ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCoreLineAllocation selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreLineAllocation> queryWrapper);
// inherited BaseMapper
List<FnbCoreLineAllocation> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreLineAllocation> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCoreLineAllocation> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCoreLineAllocation> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreLineAllocation> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreLineAllocation> queryWrapper);
// inherited BaseMapper
int insert(FnbCoreLineAllocation row);
// inherited BaseMapper
int update(FnbCoreLineAllocation changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreLineAllocation> updateWrapper);
int insertAllocationIfAbsent(FnbCoreLineAllocation row);
List<FnbCoreLineAllocation> selectAllocationsByFoodLine(String entityNo, String foodLineNo);
```

- `insertAllocationIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `selectAllocationsByFoodLine`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。

### fnb_order_component

`ai.neargo.shop.food.mapper.FoodMappers.OrderComponentMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbOrderComponent selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderComponent> queryWrapper);
// inherited BaseMapper
List<FnbOrderComponent> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderComponent> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderComponent> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderComponent> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderComponent> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderComponent> queryWrapper);
// inherited BaseMapper
int insert(FnbOrderComponent row);
// inherited BaseMapper
int update(FnbOrderComponent changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderComponent> updateWrapper);
```



### fnb_order_modifier

`ai.neargo.shop.food.mapper.FoodMappers.OrderModifierMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbOrderModifier selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderModifier> queryWrapper);
// inherited BaseMapper
List<FnbOrderModifier> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderModifier> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderModifier> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderModifier> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderModifier> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderModifier> queryWrapper);
// inherited BaseMapper
int insert(FnbOrderModifier row);
// inherited BaseMapper
int update(FnbOrderModifier changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderModifier> updateWrapper);
```



### fnb_course

`ai.neargo.shop.food.mapper.FoodMappers.CourseMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCourse selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCourse> queryWrapper);
// inherited BaseMapper
List<FnbCourse> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCourse> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCourse> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCourse> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCourse> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCourse> queryWrapper);
// inherited BaseMapper
int insert(FnbCourse row);
// inherited BaseMapper
int update(FnbCourse changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCourse> updateWrapper);
```



### fnb_kitchen_ticket

`ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbKitchenTicket selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTicket> queryWrapper);
// inherited BaseMapper
List<FnbKitchenTicket> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTicket> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbKitchenTicket> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbKitchenTicket> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTicket> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTicket> queryWrapper);
// inherited BaseMapper
int insert(FnbKitchenTicket row);
// inherited BaseMapper
int update(FnbKitchenTicket changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTicket> updateWrapper);
int insertDispatchIfAbsent(FnbKitchenTicket row);
```

- `insertDispatchIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。

### fnb_kitchen_task

`ai.neargo.shop.food.mapper.FoodMappers.KitchenTaskMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbKitchenTask selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTask> queryWrapper);
// inherited BaseMapper
List<FnbKitchenTask> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTask> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbKitchenTask> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbKitchenTask> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTask> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTask> queryWrapper);
// inherited BaseMapper
int insert(FnbKitchenTask row);
// inherited BaseMapper
int update(FnbKitchenTask changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTask> updateWrapper);
int advanceTaskQuantityIfVersion(String entityNo, String taskNo, String action, BigDecimal qty, long expectedVersion);
int changePendingStateIfVersion(String entityNo, String taskNo, String targetState, long expectedVersion);
```

- `advanceTaskQuantityIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `changePendingStateIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_service_event

`ai.neargo.shop.food.mapper.FoodMappers.ServiceEventMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbServiceEvent selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceEvent> queryWrapper);
// inherited BaseMapper
List<FnbServiceEvent> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceEvent> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceEvent> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceEvent> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceEvent> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceEvent> queryWrapper);
// inherited BaseMapper
int insert(FnbServiceEvent row);
// inherited BaseMapper
int update(FnbServiceEvent changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceEvent> updateWrapper);
int appendEventIfAbsent(FnbServiceEvent row);
```

- `appendEventIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。

### fnb_settlement

`ai.neargo.shop.food.mapper.FoodMappers.SettlementMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbSettlement selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlement> queryWrapper);
// inherited BaseMapper
List<FnbSettlement> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlement> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbSettlement> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbSettlement> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlement> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlement> queryWrapper);
// inherited BaseMapper
int insert(FnbSettlement row);
// inherited BaseMapper
int update(FnbSettlement changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlement> updateWrapper);
int insertFrozenScope(FnbSettlement row);
int finalizeIfCoreConfirmed(String entityNo, String storeNo, String settlementNo, long expectedVersion);
```

- `insertFrozenScope`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `finalizeIfCoreConfirmed`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_settlement_item

`ai.neargo.shop.food.mapper.FoodMappers.SettlementItemMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbSettlementItem selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlementItem> queryWrapper);
// inherited BaseMapper
List<FnbSettlementItem> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlementItem> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbSettlementItem> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbSettlementItem> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlementItem> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlementItem> queryWrapper);
// inherited BaseMapper
int insert(FnbSettlementItem row);
// inherited BaseMapper
int update(FnbSettlementItem changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlementItem> updateWrapper);
```



### fnb_charge_intent

`ai.neargo.shop.food.mapper.FoodMappers.ChargeIntentMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbChargeIntent selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbChargeIntent> queryWrapper);
// inherited BaseMapper
List<FnbChargeIntent> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbChargeIntent> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbChargeIntent> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbChargeIntent> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbChargeIntent> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbChargeIntent> queryWrapper);
// inherited BaseMapper
int insert(FnbChargeIntent row);
// inherited BaseMapper
int update(FnbChargeIntent changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbChargeIntent> updateWrapper);
int insertIntentIfAbsent(FnbChargeIntent row);
int confirmIntentFromCoreIfStatus(String entityNo, String intentNo, String expectedStatus, String coreStatus, String coreReference, long expectedVersion);
```

- `insertIntentIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `confirmIntentFromCoreIfStatus`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_return

`ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbReturn selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturn> queryWrapper);
// inherited BaseMapper
List<FnbReturn> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturn> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbReturn> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbReturn> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturn> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturn> queryWrapper);
// inherited BaseMapper
int insert(FnbReturn row);
// inherited BaseMapper
int update(FnbReturn changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturn> updateWrapper);
```



### fnb_return_item

`ai.neargo.shop.food.mapper.FoodMappers.ReturnItemMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbReturnItem selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturnItem> queryWrapper);
// inherited BaseMapper
List<FnbReturnItem> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturnItem> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbReturnItem> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbReturnItem> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturnItem> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturnItem> queryWrapper);
// inherited BaseMapper
int insert(FnbReturnItem row);
// inherited BaseMapper
int update(FnbReturnItem changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturnItem> updateWrapper);
BigDecimal selectActiveReturnQuantityByFoodLine(String entityNo, String foodLineNo);
```

- `selectActiveReturnQuantityByFoodLine`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。

### fnb_action_log

`ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbActionLog selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbActionLog> queryWrapper);
// inherited BaseMapper
List<FnbActionLog> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbActionLog> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbActionLog> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbActionLog> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbActionLog> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbActionLog> queryWrapper);
// inherited BaseMapper
int insert(FnbActionLog row);
// inherited BaseMapper
int update(FnbActionLog changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbActionLog> updateWrapper);
```



### fnb_business_day

`ai.neargo.shop.food.mapper.FoodMappers.BusinessDayMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbBusinessDay selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessDay> queryWrapper);
// inherited BaseMapper
List<FnbBusinessDay> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessDay> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBusinessDay> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBusinessDay> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessDay> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessDay> queryWrapper);
// inherited BaseMapper
int insert(FnbBusinessDay row);
// inherited BaseMapper
int update(FnbBusinessDay changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessDay> updateWrapper);
int replaceCurrentCloseIfVersion(String entityNo, String storeNo, String dayNo, String currency, String closeNo, long expectedVersion);
```

- `replaceCurrentCloseIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_cash_shift

`ai.neargo.shop.food.mapper.FoodMappers.CashShiftMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCashShift selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashShift> queryWrapper);
// inherited BaseMapper
List<FnbCashShift> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashShift> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCashShift> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCashShift> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashShift> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashShift> queryWrapper);
// inherited BaseMapper
int insert(FnbCashShift row);
// inherited BaseMapper
int update(FnbCashShift changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashShift> updateWrapper);
FnbCashShift selectActiveDrawerForUpdate(String entityNo, String storeNo, String drawerNo, String currency);
int handoverIfVersion(String entityNo, String storeNo, String shiftNo, String countNo, String acceptedBy, long expectedVersion);
```

- `selectActiveDrawerForUpdate`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。
- `handoverIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_cash_entry

`ai.neargo.shop.food.mapper.FoodMappers.CashEntryMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCashEntry selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashEntry> queryWrapper);
// inherited BaseMapper
List<FnbCashEntry> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashEntry> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCashEntry> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCashEntry> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashEntry> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashEntry> queryWrapper);
// inherited BaseMapper
int insert(FnbCashEntry row);
// inherited BaseMapper
int update(FnbCashEntry changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashEntry> updateWrapper);
```



### fnb_cash_payment_link

`ai.neargo.shop.food.mapper.FoodMappers.CashPaymentLinkMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCashPaymentLink selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashPaymentLink> queryWrapper);
// inherited BaseMapper
List<FnbCashPaymentLink> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashPaymentLink> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCashPaymentLink> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCashPaymentLink> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashPaymentLink> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashPaymentLink> queryWrapper);
// inherited BaseMapper
int insert(FnbCashPaymentLink row);
// inherited BaseMapper
int update(FnbCashPaymentLink changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashPaymentLink> updateWrapper);
int linkCorePaymentIfAbsent(String entityNo, String linkNo, long expectedVersion);
```

- `linkCorePaymentIfAbsent`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_cash_count

`ai.neargo.shop.food.mapper.FoodMappers.CashCountMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCashCount selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashCount> queryWrapper);
// inherited BaseMapper
List<FnbCashCount> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashCount> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCashCount> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCashCount> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashCount> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashCount> queryWrapper);
// inherited BaseMapper
int insert(FnbCashCount row);
// inherited BaseMapper
int update(FnbCashCount changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashCount> updateWrapper);
```



### fnb_close_run

`ai.neargo.shop.food.mapper.FoodMappers.CloseRunMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCloseRun selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseRun> queryWrapper);
// inherited BaseMapper
List<FnbCloseRun> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseRun> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseRun> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseRun> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseRun> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseRun> queryWrapper);
// inherited BaseMapper
int insert(FnbCloseRun row);
// inherited BaseMapper
int update(FnbCloseRun changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseRun> updateWrapper);
int signIfVersionHashAndWatermark(String entityNo, String closeNo, String snapshotHash, String watermark, String signedBy, Instant signedAt, long expectedVersion);
```

- `signIfVersionHashAndWatermark`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_close_balance

`ai.neargo.shop.food.mapper.FoodMappers.CloseBalanceMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCloseBalance selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseBalance> queryWrapper);
// inherited BaseMapper
List<FnbCloseBalance> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseBalance> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseBalance> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseBalance> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseBalance> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseBalance> queryWrapper);
// inherited BaseMapper
int insert(FnbCloseBalance row);
// inherited BaseMapper
int update(FnbCloseBalance changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseBalance> updateWrapper);
```



### fnb_close_exception

`ai.neargo.shop.food.mapper.FoodMappers.CloseExceptionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCloseException selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseException> queryWrapper);
// inherited BaseMapper
List<FnbCloseException> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseException> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseException> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseException> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseException> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseException> queryWrapper);
// inherited BaseMapper
int insert(FnbCloseException row);
// inherited BaseMapper
int update(FnbCloseException changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseException> updateWrapper);
```



### fnb_close_adjustment

`ai.neargo.shop.food.mapper.FoodMappers.CloseAdjustmentMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCloseAdjustment selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseAdjustment> queryWrapper);
// inherited BaseMapper
List<FnbCloseAdjustment> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseAdjustment> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseAdjustment> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseAdjustment> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseAdjustment> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseAdjustment> queryWrapper);
// inherited BaseMapper
int insert(FnbCloseAdjustment row);
// inherited BaseMapper
int update(FnbCloseAdjustment changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseAdjustment> updateWrapper);
```



### fnb_metric_definition

`ai.neargo.shop.food.mapper.FoodMappers.MetricDefinitionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMetricDefinition selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMetricDefinition> queryWrapper);
// inherited BaseMapper
List<FnbMetricDefinition> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMetricDefinition> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMetricDefinition> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMetricDefinition> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMetricDefinition> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMetricDefinition> queryWrapper);
// inherited BaseMapper
int insert(FnbMetricDefinition row);
// inherited BaseMapper
int update(FnbMetricDefinition changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMetricDefinition> updateWrapper);
```



### fnb_report_run

`ai.neargo.shop.food.mapper.FoodMappers.ReportRunMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbReportRun selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReportRun> queryWrapper);
// inherited BaseMapper
List<FnbReportRun> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReportRun> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbReportRun> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbReportRun> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReportRun> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReportRun> queryWrapper);
// inherited BaseMapper
int insert(FnbReportRun row);
// inherited BaseMapper
int update(FnbReportRun changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReportRun> updateWrapper);
```



### fnb_external_mapping

`ai.neargo.shop.food.mapper.FoodMappers.ExternalMappingMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbExternalMapping selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbExternalMapping> queryWrapper);
// inherited BaseMapper
List<FnbExternalMapping> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbExternalMapping> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbExternalMapping> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbExternalMapping> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbExternalMapping> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbExternalMapping> queryWrapper);
// inherited BaseMapper
int insert(FnbExternalMapping row);
// inherited BaseMapper
int update(FnbExternalMapping changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbExternalMapping> updateWrapper);
```



### fnb_sync_batch

`ai.neargo.shop.food.mapper.FoodMappers.SyncBatchMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbSyncBatch selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSyncBatch> queryWrapper);
// inherited BaseMapper
List<FnbSyncBatch> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSyncBatch> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbSyncBatch> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbSyncBatch> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSyncBatch> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSyncBatch> queryWrapper);
// inherited BaseMapper
int insert(FnbSyncBatch row);
// inherited BaseMapper
int update(FnbSyncBatch changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSyncBatch> updateWrapper);
int insertSyncBatchIfAbsent(FnbSyncBatch row);
Long selectAcceptedThrough(String entityNo, String storeNo, String edgeNo, long epoch);
```

- `insertSyncBatchIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `selectAcceptedThrough`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。

### fnb_edge_lease

`ai.neargo.shop.food.mapper.FoodMappers.EdgeLeaseMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbEdgeLease selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbEdgeLease> queryWrapper);
// inherited BaseMapper
List<FnbEdgeLease> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbEdgeLease> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbEdgeLease> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbEdgeLease> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbEdgeLease> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbEdgeLease> queryWrapper);
// inherited BaseMapper
int insert(FnbEdgeLease row);
// inherited BaseMapper
int update(FnbEdgeLease changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbEdgeLease> updateWrapper);
int renewLeaseIfEpoch(String entityNo, String storeNo, String leaseNo, long epoch, Instant expiresAt, long expectedVersion);
FnbEdgeLease selectLeaseByStoreEpochForUpdate(String entityNo, String storeNo, String edgeNo, long epoch);
```

- `renewLeaseIfEpoch`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `selectLeaseByStoreEpochForUpdate`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。

### fnb_integration_exception

`ai.neargo.shop.food.mapper.FoodMappers.IntegrationExceptionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbIntegrationException selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIntegrationException> queryWrapper);
// inherited BaseMapper
List<FnbIntegrationException> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIntegrationException> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbIntegrationException> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbIntegrationException> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIntegrationException> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIntegrationException> queryWrapper);
// inherited BaseMapper
int insert(FnbIntegrationException row);
// inherited BaseMapper
int update(FnbIntegrationException changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIntegrationException> updateWrapper);
```



### fnb_dining_entitlement

`ai.neargo.shop.food.mapper.FoodMappers.DiningEntitlementMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbDiningEntitlement selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDiningEntitlement> queryWrapper);
// inherited BaseMapper
List<FnbDiningEntitlement> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDiningEntitlement> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbDiningEntitlement> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbDiningEntitlement> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDiningEntitlement> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDiningEntitlement> queryWrapper);
// inherited BaseMapper
int insert(FnbDiningEntitlement row);
// inherited BaseMapper
int update(FnbDiningEntitlement changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDiningEntitlement> updateWrapper);
```



### fnb_banquet_plan

`ai.neargo.shop.food.mapper.FoodMappers.BanquetPlanMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbBanquetPlan selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBanquetPlan> queryWrapper);
// inherited BaseMapper
List<FnbBanquetPlan> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBanquetPlan> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBanquetPlan> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBanquetPlan> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBanquetPlan> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBanquetPlan> queryWrapper);
// inherited BaseMapper
int insert(FnbBanquetPlan row);
// inherited BaseMapper
int update(FnbBanquetPlan changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBanquetPlan> updateWrapper);
```



### fnb_bottle_custody

`ai.neargo.shop.food.mapper.FoodMappers.BottleCustodyMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbBottleCustody selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBottleCustody> queryWrapper);
// inherited BaseMapper
List<FnbBottleCustody> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBottleCustody> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBottleCustody> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBottleCustody> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBottleCustody> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBottleCustody> queryWrapper);
// inherited BaseMapper
int insert(FnbBottleCustody row);
// inherited BaseMapper
int update(FnbBottleCustody changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBottleCustody> updateWrapper);
int withdrawIfRemaining(String entityNo, String storeNo, String custodyNo, BigDecimal qty, long expectedVersion);
```

- `withdrawIfRemaining`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_custody_entry

`ai.neargo.shop.food.mapper.FoodMappers.CustodyEntryMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCustodyEntry selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCustodyEntry> queryWrapper);
// inherited BaseMapper
List<FnbCustodyEntry> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCustodyEntry> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCustodyEntry> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCustodyEntry> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCustodyEntry> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCustodyEntry> queryWrapper);
// inherited BaseMapper
int insert(FnbCustodyEntry row);
// inherited BaseMapper
int update(FnbCustodyEntry changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCustodyEntry> updateWrapper);
```



### fnb_room_charge_intent

`ai.neargo.shop.food.mapper.FoodMappers.RoomChargeIntentMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbRoomChargeIntent selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRoomChargeIntent> queryWrapper);
// inherited BaseMapper
List<FnbRoomChargeIntent> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRoomChargeIntent> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbRoomChargeIntent> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbRoomChargeIntent> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRoomChargeIntent> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRoomChargeIntent> queryWrapper);
// inherited BaseMapper
int insert(FnbRoomChargeIntent row);
// inherited BaseMapper
int update(FnbRoomChargeIntent changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRoomChargeIntent> updateWrapper);
```



### prn_device

`ai.neargo.shop.printing.mapper.PrintMappers.DeviceMapper`；文件：`backend/shop-core/src/main/java/ai/neargo/shop/printing/mapper/PrintMappers.java`。

```java
// inherited BaseMapper
PrnDevice selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnDevice> queryWrapper);
// inherited BaseMapper
List<PrnDevice> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnDevice> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<PrnDevice> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<PrnDevice> page, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnDevice> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnDevice> queryWrapper);
// inherited BaseMapper
int insert(PrnDevice row);
// inherited BaseMapper
int update(PrnDevice changes, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnDevice> updateWrapper);
```



### prn_template

`ai.neargo.shop.printing.mapper.PrintMappers.TemplateMapper`；文件：`backend/shop-core/src/main/java/ai/neargo/shop/printing/mapper/PrintMappers.java`。

```java
// inherited BaseMapper
PrnTemplate selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnTemplate> queryWrapper);
// inherited BaseMapper
List<PrnTemplate> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnTemplate> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<PrnTemplate> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<PrnTemplate> page, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnTemplate> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnTemplate> queryWrapper);
// inherited BaseMapper
int insert(PrnTemplate row);
// inherited BaseMapper
int update(PrnTemplate changes, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnTemplate> updateWrapper);
```



### prn_route

`ai.neargo.shop.printing.mapper.PrintMappers.RouteMapper`；文件：`backend/shop-core/src/main/java/ai/neargo/shop/printing/mapper/PrintMappers.java`。

```java
// inherited BaseMapper
PrnRoute selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnRoute> queryWrapper);
// inherited BaseMapper
List<PrnRoute> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnRoute> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<PrnRoute> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<PrnRoute> page, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnRoute> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnRoute> queryWrapper);
// inherited BaseMapper
int insert(PrnRoute row);
// inherited BaseMapper
int update(PrnRoute changes, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnRoute> updateWrapper);
```



### prn_job

`ai.neargo.shop.printing.mapper.PrintMappers.JobMapper`；文件：`backend/shop-core/src/main/java/ai/neargo/shop/printing/mapper/PrintMappers.java`。

```java
// inherited BaseMapper
PrnJob selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnJob> queryWrapper);
// inherited BaseMapper
List<PrnJob> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnJob> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<PrnJob> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<PrnJob> page, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnJob> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnJob> queryWrapper);
// inherited BaseMapper
int insert(PrnJob row);
// inherited BaseMapper
int update(PrnJob changes, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnJob> updateWrapper);
```



### prn_attempt

`ai.neargo.shop.printing.mapper.PrintMappers.AttemptMapper`；文件：`backend/shop-core/src/main/java/ai/neargo/shop/printing/mapper/PrintMappers.java`。

```java
// inherited BaseMapper
PrnAttempt selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnAttempt> queryWrapper);
// inherited BaseMapper
List<PrnAttempt> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnAttempt> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<PrnAttempt> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<PrnAttempt> page, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnAttempt> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnAttempt> queryWrapper);
// inherited BaseMapper
int insert(PrnAttempt row);
// inherited BaseMapper
int update(PrnAttempt changes, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnAttempt> updateWrapper);
```



### rpt_food_metric_daily

`ai.neargo.shop.report.dao.ReportFoodMetricDailyDao`；文件：`backend/shop-report/src/main/java/ai/neargo/shop/report/dao/ReportFoodMetricDailyDao.java`。

```java
List<ai.neargo.shop.report.dto.DailyFoodMetricRow> queryByMetricVersionAndWatermark(String entityNo, String storeNo, LocalDate from, LocalDate to, int metricVersion, String watermark);
int upsertProjectionIfSourceVersion(ai.neargo.shop.report.dto.DailyFoodMetricRow row);
```

- `queryByMetricVersionAndWatermark`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `upsertProjectionIfSourceVersion`：只由shop-report使用，按投影唯一键和水位幂等更新，不改营业日签核事实。

### fnb_table_area

`ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbTableArea selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableArea> queryWrapper);
// inherited BaseMapper
List<FnbTableArea> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableArea> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbTableArea> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbTableArea> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableArea> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableArea> queryWrapper);
// inherited BaseMapper
int insert(FnbTableArea row);
// inherited BaseMapper
int update(FnbTableArea changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableArea> updateWrapper);
FnbTableArea selectScopedByKeyForUpdate(String entityNo, String storeNo, String areaNo);
```

- `selectScopedByKeyForUpdate`：必须在当前餐饮本地事务内调用，锁到的是已存在父/实体行；空结果不能充当稳定仲裁者。

### fnb_table_operation

`ai.neargo.shop.food.mapper.FoodMappers.TableOperationMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbTableOperation selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOperation> queryWrapper);
// inherited BaseMapper
List<FnbTableOperation> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOperation> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbTableOperation> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbTableOperation> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOperation> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOperation> queryWrapper);
// inherited BaseMapper
int insert(FnbTableOperation row);
// inherited BaseMapper
int update(FnbTableOperation changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOperation> updateWrapper);
int insertOperationIfAbsent(FnbTableOperation row);
int advanceOperationIfVersion(String entityNo, String storeNo, String operationNo, String targetStatus, long expectedVersion);
```

- `insertOperationIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `advanceOperationIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_config_revision

`ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbConfigRevision selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbConfigRevision> queryWrapper);
// inherited BaseMapper
List<FnbConfigRevision> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbConfigRevision> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbConfigRevision> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbConfigRevision> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbConfigRevision> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbConfigRevision> queryWrapper);
// inherited BaseMapper
int insert(FnbConfigRevision row);
// inherited BaseMapper
int update(FnbConfigRevision changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbConfigRevision> updateWrapper);
int insertImmutableRevision(FnbConfigRevision row);
FnbConfigRevision selectEffectiveByConfigAndTime(String entityNo, String configKind, String configNo, Instant at);
```

- `insertImmutableRevision`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `selectEffectiveByConfigAndTime`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。

### fnb_sales_restriction

`ai.neargo.shop.food.mapper.FoodMappers.SalesRestrictionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbSalesRestriction selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesRestriction> queryWrapper);
// inherited BaseMapper
List<FnbSalesRestriction> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesRestriction> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbSalesRestriction> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbSalesRestriction> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesRestriction> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesRestriction> queryWrapper);
// inherited BaseMapper
int insert(FnbSalesRestriction row);
// inherited BaseMapper
int update(FnbSalesRestriction changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesRestriction> updateWrapper);
int reserveQuotaIfCapacity(String entityNo, String storeNo, String restrictionNo, BigDecimal qty, long expectedVersion);
int consumeQuotaIfReserved(String entityNo, String storeNo, String restrictionNo, BigDecimal qty, long expectedVersion);
int releaseQuotaIfReserved(String entityNo, String storeNo, String restrictionNo, BigDecimal qty, long expectedVersion);
```

- `reserveQuotaIfCapacity`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `consumeQuotaIfReserved`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `releaseQuotaIfReserved`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_check_batch_membership

`ai.neargo.shop.food.mapper.FoodMappers.CheckBatchMembershipMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCheckBatchMembership selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckBatchMembership> queryWrapper);
// inherited BaseMapper
List<FnbCheckBatchMembership> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckBatchMembership> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckBatchMembership> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckBatchMembership> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckBatchMembership> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckBatchMembership> queryWrapper);
// inherited BaseMapper
int insert(FnbCheckBatchMembership row);
// inherited BaseMapper
int update(FnbCheckBatchMembership changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckBatchMembership> updateWrapper);
FnbCheckBatchMembership selectActiveByBatchForUpdate(String entityNo, String batchNo);
int detachMembershipIfActive(String entityNo, String membershipNo, Instant detachedAt, long expectedVersion);
```

- `selectActiveByBatchForUpdate`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。
- `detachMembershipIfActive`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_sales_quota_claim

`ai.neargo.shop.food.mapper.FoodMappers.SalesQuotaClaimMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbSalesQuotaClaim selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesQuotaClaim> queryWrapper);
// inherited BaseMapper
List<FnbSalesQuotaClaim> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesQuotaClaim> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbSalesQuotaClaim> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbSalesQuotaClaim> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesQuotaClaim> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesQuotaClaim> queryWrapper);
// inherited BaseMapper
int insert(FnbSalesQuotaClaim row);
// inherited BaseMapper
int update(FnbSalesQuotaClaim changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesQuotaClaim> updateWrapper);
int insertClaimIfAbsent(FnbSalesQuotaClaim row);
int advanceClaimIfStatus(String entityNo, String claimNo, String expectedStatus, String targetStatus, long expectedVersion);
```

- `insertClaimIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `advanceClaimIfStatus`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_menu_channel_binding

`ai.neargo.shop.food.mapper.FoodMappers.MenuChannelBindingMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuChannelBinding selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuChannelBinding> queryWrapper);
// inherited BaseMapper
List<FnbMenuChannelBinding> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuChannelBinding> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuChannelBinding> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuChannelBinding> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuChannelBinding> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuChannelBinding> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuChannelBinding row);
// inherited BaseMapper
int update(FnbMenuChannelBinding changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuChannelBinding> updateWrapper);
```



### fnb_menu_item_option_group

`ai.neargo.shop.food.mapper.FoodMappers.MenuItemOptionGroupMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuItemOptionGroup selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemOptionGroup> queryWrapper);
// inherited BaseMapper
List<FnbMenuItemOptionGroup> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemOptionGroup> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuItemOptionGroup> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuItemOptionGroup> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemOptionGroup> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemOptionGroup> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuItemOptionGroup row);
// inherited BaseMapper
int update(FnbMenuItemOptionGroup changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemOptionGroup> updateWrapper);
```



### fnb_recipe

`ai.neargo.shop.food.mapper.FoodMappers.RecipeMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbRecipe selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipe> queryWrapper);
// inherited BaseMapper
List<FnbRecipe> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipe> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbRecipe> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbRecipe> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipe> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipe> queryWrapper);
// inherited BaseMapper
int insert(FnbRecipe row);
// inherited BaseMapper
int update(FnbRecipe changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipe> updateWrapper);
```



### fnb_recipe_line

`ai.neargo.shop.food.mapper.FoodMappers.RecipeLineMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbRecipeLine selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeLine> queryWrapper);
// inherited BaseMapper
List<FnbRecipeLine> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeLine> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbRecipeLine> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbRecipeLine> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeLine> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeLine> queryWrapper);
// inherited BaseMapper
int insert(FnbRecipeLine row);
// inherited BaseMapper
int update(FnbRecipeLine changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeLine> updateWrapper);
```



### fnb_recipe_adjustment

`ai.neargo.shop.food.mapper.FoodMappers.RecipeAdjustmentMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbRecipeAdjustment selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeAdjustment> queryWrapper);
// inherited BaseMapper
List<FnbRecipeAdjustment> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeAdjustment> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbRecipeAdjustment> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbRecipeAdjustment> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeAdjustment> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeAdjustment> queryWrapper);
// inherited BaseMapper
int insert(FnbRecipeAdjustment row);
// inherited BaseMapper
int update(FnbRecipeAdjustment changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeAdjustment> updateWrapper);
```



### fnb_production_consumption

`ai.neargo.shop.food.mapper.FoodMappers.ProductionConsumptionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbProductionConsumption selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumption> queryWrapper);
// inherited BaseMapper
List<FnbProductionConsumption> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumption> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbProductionConsumption> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbProductionConsumption> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumption> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumption> queryWrapper);
// inherited BaseMapper
int insert(FnbProductionConsumption row);
// inherited BaseMapper
int update(FnbProductionConsumption changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumption> updateWrapper);
```



### fnb_production_consumption_line

`ai.neargo.shop.food.mapper.FoodMappers.ProductionConsumptionLineMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbProductionConsumptionLine selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumptionLine> queryWrapper);
// inherited BaseMapper
List<FnbProductionConsumptionLine> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumptionLine> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbProductionConsumptionLine> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbProductionConsumptionLine> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumptionLine> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumptionLine> queryWrapper);
// inherited BaseMapper
int insert(FnbProductionConsumptionLine row);
// inherited BaseMapper
int update(FnbProductionConsumptionLine changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumptionLine> updateWrapper);
```



### fnb_catalog_sync_job

`ai.neargo.shop.food.mapper.FoodMappers.CatalogSyncJobMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCatalogSyncJob selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncJob> queryWrapper);
// inherited BaseMapper
List<FnbCatalogSyncJob> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncJob> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCatalogSyncJob> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCatalogSyncJob> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncJob> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncJob> queryWrapper);
// inherited BaseMapper
int insert(FnbCatalogSyncJob row);
// inherited BaseMapper
int update(FnbCatalogSyncJob changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncJob> updateWrapper);
```



### fnb_catalog_sync_item

`ai.neargo.shop.food.mapper.FoodMappers.CatalogSyncItemMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCatalogSyncItem selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncItem> queryWrapper);
// inherited BaseMapper
List<FnbCatalogSyncItem> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncItem> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCatalogSyncItem> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCatalogSyncItem> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncItem> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncItem> queryWrapper);
// inherited BaseMapper
int insert(FnbCatalogSyncItem row);
// inherited BaseMapper
int update(FnbCatalogSyncItem changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncItem> updateWrapper);
```



### fnb_queue_ticket

`ai.neargo.shop.food.mapper.FoodMappers.QueueTicketMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbQueueTicket selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbQueueTicket> queryWrapper);
// inherited BaseMapper
List<FnbQueueTicket> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbQueueTicket> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbQueueTicket> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbQueueTicket> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbQueueTicket> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbQueueTicket> queryWrapper);
// inherited BaseMapper
int insert(FnbQueueTicket row);
// inherited BaseMapper
int update(FnbQueueTicket changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbQueueTicket> updateWrapper);
```



### fnb_reservation_table

`ai.neargo.shop.food.mapper.ReservationTableMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/ReservationTableMapper.java`。

```java
// inherited BaseMapper
FnbReservationTable selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservationTable> queryWrapper);
// inherited BaseMapper
List<FnbReservationTable> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservationTable> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbReservationTable> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbReservationTable> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservationTable> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservationTable> queryWrapper);
// inherited BaseMapper
int insert(FnbReservationTable row);
// inherited BaseMapper
int update(FnbReservationTable changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservationTable> updateWrapper);
```



### fnb_business_share_plan

`ai.neargo.shop.food.mapper.BusinessSharePlanMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/BusinessSharePlanMapper.java`。

```java
// inherited BaseMapper
FnbBusinessSharePlan selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessSharePlan> queryWrapper);
// inherited BaseMapper
List<FnbBusinessSharePlan> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessSharePlan> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBusinessSharePlan> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBusinessSharePlan> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessSharePlan> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessSharePlan> queryWrapper);
// inherited BaseMapper
int insert(FnbBusinessSharePlan row);
// inherited BaseMapper
int update(FnbBusinessSharePlan changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessSharePlan> updateWrapper);
```



## 四、Service内部门面与恢复方法

这些方法由同域Service/事件/任务调用，不额外开放HTTP接口；ServiceImpl实现同一签名。内部参数来自已验证对象，不能让Controller直接接收Entity或任意snapshot。

- `ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`：

```java
OperationVO recoverTableOperation(String entityNo, String storeNo, String operationNo);
```

查询原Resource操作，短事务应用占用；失败保留恢复状态。

- `ai.neargo.shop.food.service.ConfigRevisionService` / `ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl`：

```java
ConfigRevisionVO publishRevision(String entityNo, String configKind, String configNo, long expectedVersion, java.util.Map<String,Object> validatedDefinition, java.time.Instant effectiveAt, String idempotencyKey);
```

输入是所属资源Service完成校验的定义，冻结不可变内容及依赖版本。

- `ai.neargo.shop.food.service.SalesRestrictionService` / `ai.neargo.shop.food.service.impl.SalesRestrictionServiceImpl`：

```java
java.util.List<String> reserveBatchQuota(String entityNo, String storeNo, String batchNo, java.util.Map<String,java.math.BigDecimal> restrictionQuantities);
```

按批次/限制唯一键预占；额度数量来自已验证源行。

- `ai.neargo.shop.food.service.SalesRestrictionService` / `ai.neargo.shop.food.service.impl.SalesRestrictionServiceImpl`：

```java
void consumeBatchQuota(String entityNo, String storeNo, String batchNo);
```

仅已受理批次从RESERVED一次转CONSUMED。

- `ai.neargo.shop.food.service.SalesRestrictionService` / `ai.neargo.shop.food.service.impl.SalesRestrictionServiceImpl`：

```java
void releaseBatchQuota(String entityNo, String storeNo, String batchNo);
```

核心确认失败/取消才释放，UNKNOWN保留。

- `ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`：

```java
BatchDetailVO recoverPlacement(String entityNo, String storeNo, String batchNo);
```

从冻结请求查原键结果，不能另造请求。

- `ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`：

```java
java.util.List<String> dispatchAcceptedBatch(String entityNo, String storeNo, String batchNo, String acceptedEventNo);
```

受理合法后按冻结路由派工；无组件任务也可去重。

- `ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`：

```java
OperationVO recoverChargeIntent(String entityNo, String storeNo, String intentNo);
```

查询核心资金状态再条件回写，不换键重复收费。

- `ai.neargo.shop.food.service.DiningReturnService` / `ai.neargo.shop.food.service.impl.DiningReturnServiceImpl`：

```java
OperationVO recoverReturn(String entityNo, String storeNo, String returnNo);
```

财务与厨房独立恢复。

- `ai.neargo.shop.food.service.DiningAuditService` / `ai.neargo.shop.food.service.impl.DiningAuditServiceImpl`：

```java
void appendAction(String entityNo, String storeNo, String actorNo, String objectType, String objectNo, String action, String reason);
```

真实操作者与敏感原因，接共享审计，不包含口令令牌。

- `ai.neargo.shop.food.service.RoomChargeService` / `ai.neargo.shop.food.service.impl.RoomChargeServiceImpl`：

```java
OperationVO recoverRoomCharge(String entityNo, String storeNo, String intentNo);
```

查原PMS/应收凭据，UNKNOWN不标结清。

## 五、辅助类的方法

### ai.neargo.shop.food.policy.TableOccupancyPolicy

容量、先占后放、跨台协作守卫。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateCapacity(FnbTable table, java.util.List<FnbTableOccupancy> activeOccupancies, int requestedSeats);
```

### ai.neargo.shop.food.statemachine.FoodCheckStateMachine

服务台账状态；不接管核心资金状态。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void assertTransition(String from, String to);
```

### ai.neargo.shop.food.job.TableOperationRecoveryJob

按原操作号恢复资源与占用。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.assembler.TableViewAssembler

桌态、占用和资金摘要分别投影。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
TableVO toTableVO(FnbTable table, java.util.List<FnbTableOccupancy> authorizedOccupancies);
```

### ai.neargo.shop.food.policy.MenuAvailabilityPolicy

门店/时段/商品可售求交。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
boolean isAvailable(FnbMenu menu, java.time.Instant at, String orderSource, String fulfillment);
```

### ai.neargo.shop.food.policy.SelectionRulePolicy

必选互斥、组合循环及单位校验。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validate(SelectionRules rules, java.util.List<SelectionReq> selections);
```

### ai.neargo.shop.food.policy.ConfigPublicationPolicy

不可变发布及回滚新修订。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateSnapshot(String configKind, java.util.Map<String,Object> validatedDefinition);
```

### ai.neargo.shop.food.policy.SalesQuotaPolicy

额度预占、消费、释放幂等。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateRemaining(FnbSalesRestriction restriction, java.math.BigDecimal requestedQty);
```

### ai.neargo.shop.food.job.FoodSalesRestrictionExpiryJob

限制到期，不回填实存。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.policy.OrderPlacementPolicy

报价指纹、源行与车版本。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateSubmission(CartVO cart, QuoteVO quote, java.util.List<OrderLineReq> selectedLines);
```

### ai.neargo.shop.food.statemachine.OrderBatchStateMachine

建单/UNKNOWN/受理/取消状态。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void assertTransition(String from, String to);
```

### ai.neargo.shop.food.policy.OrderLineAllocationPolicy

源行和核心成交行数量金额守恒。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateConservation(java.util.List<FnbOrderLine> sourceLines, java.util.List<FnbCoreLineAllocation> verifiedAllocations);
```

### ai.neargo.shop.food.job.FoodPlacementRecoveryJob

同键查询原核心结果。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.event.CoreSubOrderPaidConsumer

可信支付事实只受理一次。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void onSubOrderPaid(String eventNo, String storeOrderNo, java.time.Instant occurredAt);
```

### ai.neargo.shop.food.policy.KitchenTaskPolicy

制作上菜撤菜数量守卫。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateAction(FnbKitchenTask task, String action, java.math.BigDecimal qty);
```

### ai.neargo.shop.food.event.KitchenDispatchConsumer

只由正式受理派工，事件去重。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void onBatchAccepted(String eventNo, String batchNo, long batchVersion);
```

### ai.neargo.shop.food.policy.SettlementPolicy

范围冻结、防重复收款、未知资金守卫。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateFinalization(FnbSettlement settlement, java.util.List<FnbChargeIntent> intents, boolean coreConfirmedSettled);
```

### ai.neargo.shop.food.job.FoodChargeRecoveryJob

查原资金意图恢复。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.policy.FoodReturnPolicy

可退数量、审批、财务/厨房分轨。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateQuantity(java.math.BigDecimal originalQty, java.math.BigDecimal activeReturnQty, java.math.BigDecimal requestedQty);
```

### ai.neargo.shop.food.job.FoodReturnRecoveryJob

原退款请求查询恢复。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.assembler.FoodPrintDocumentAssembler

food-core冻结内容交共享打印，不另造打印队列。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
java.util.Map<String,Object> freezeDocument(String scene, java.util.Map<String,Object> verifiedSnapshot, long contentVersion);
```

### ai.neargo.shop.food.policy.CashDrawerPolicy

活跃钱箱班次、实收找零与盘点差异。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateCount(FnbCashShift shift, long countedMinor, long expectedMinor);
```

### ai.neargo.shop.food.policy.BusinessClosePolicy

资金证据、水位指纹与正式版本。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateSignature(FnbCloseRun close, String expectedHash, String expectedWatermark);
```

### ai.neargo.shop.food.job.FoodBusinessDayRolloverJob

营业日滚动不等于签核。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.job.FoodClosePrecheckJob

预结巡检和异常清单。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.job.FoodReportWatermarkJob

投影水位、幂等重算。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.policy.DiningEntitlementPolicy

限时入场、频控与最后点单。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateOrdering(FnbDiningEntitlement entitlement, java.time.Instant at);
```

### ai.neargo.shop.food.policy.CustodyQuantityPolicy

寄存领用/报损上限。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateWithdrawal(FnbBottleCustody custody, java.math.BigDecimal qty);
```

### ai.neargo.shop.food.job.FoodRoomChargeRecoveryJob

原PMS/应收意图查询恢复。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.policy.EdgeWriterLeasePolicy

租约代次与单写者隔离；正式协议仍待专项。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateWriter(FnbEdgeLease lease, String edgeNo, long epoch, java.time.Instant now);
```

### ai.neargo.shop.food.job.FoodSyncRecoveryJob

连续同步水位和冲突认领。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.config.FoodAutoConfiguration

启用/禁用与自动装配。配置类以条件/Import/Properties声明为主，不为了清单增加空业务方法；迁移Bean复用主DataSource且只能有一个执行者。

无新增业务方法；仅声明装配/属性职责。

### ai.neargo.shop.food.config.FoodProperties

沿主库的餐饮配置。配置类以条件/Import/Properties声明为主，不为了清单增加空业务方法；迁移Bean复用主DataSource且只能有一个执行者。

无新增业务方法；仅声明装配/属性职责。

### ai.neargo.shop.food.config.FoodPersistenceConfiguration

主DataSource与独立fnb迁移记录，唯一执行者。配置类以条件/Import/Properties声明为主，不为了清单增加空业务方法；迁移Bean复用主DataSource且只能有一个执行者。

无新增业务方法；仅声明装配/属性职责。

### ai.neargo.shop.food.config.FoodPortConfiguration

本地/远程适配互斥。配置类以条件/Import/Properties声明为主，不为了清单增加空业务方法；迁移Bean复用主DataSource且只能有一个执行者。

无新增业务方法；仅声明装配/属性职责。

Entity/DTO方法：实体沿Lombok及BaseEntity的getXxx/setXxx；record沿字段名访问器，不在数据对象上增加收退款/计价/授权方法。字段与完整类名见模块机器清单dtoTypes/persistence。

## 六、实现核对

1. 按模块选择API用例后，再实现相应Controller、Service/Impl与Mapper；先桌台，再菜品、订单。
2. 校验同名Service方法参数/返回不冲突、同一Mapper方法定义唯一、跨域只走Port；每个Service方法的请求字段和返回结构以API册为准。
3. 核心能力缺口继续门控：源行、后付库存、应收拆合、部分退款、离线租约和真实设备均需合同测试。
4. Entity沿Lombok/BaseEntity访问器，DTO record沿字段访问器；不额外生成一套领域实体/Repository。辅助Policy/Job及内部门面方法已在方法机器清单逐项列出，不改变现有HTTP目标。
5. 已应用的V1冻结；方法设计不更新数据库。文档守卫通过不等于Java编译、SQL事务或业务测试通过。
