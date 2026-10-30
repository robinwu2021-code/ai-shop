# 餐饮后端统一实施：API 审查与清单

> 依据 `4886f460b` 对 Controller 静态核对。“已有”只表示路由和代码存在，不代表全链路验收通过；“拟新增”是本方案合同，当前不能调用。路径沿项目 `biz/x`、`mp/x`、`ops/x` 与服务内部规范，最终实现以现有认证和权限为准。

## 1. 公共约束

- `storeNo` 是授权目标而非商家身份；主体由 `BizContext` 和门店目录解析并校验，操作人从登录态取，C 端从会话/设备凭据取。不得从 body、渠道回调接收 `entityNo`、操作人或 `inventoryOwner` 作为可信事实。
- 商家写操作须带 `Idempotency-Key`（非空、最多 128）；`version` 为乐观并发版本。库存数量为十进制字符串，最大 18 位整数、最多 6 位小数；金额沿既有试算/订单最小货币单位合同。
- 标识一般最多 32；明确错误语义：参数不合法、无权/不可见、找不到、冲突（版本/幂等/库存不足）、依赖不可用或结果未知；沿项目 `BizException`、`ErrorCode` 实现，不虚构新的具体 HTTP 状态。
- 查询列表沿项目 `PageData`、`page`、`size` 规范，默认 1/20、上限 100；响应不含第三方凭证、库存权威内部密钥。内部合同仅可信服务可用，带服务认证、主体/门店和原业务引用、操作键、请求 hash。

## 2. B 端拟新增：本地库存

路径前缀 `/biz/x/food/stores/{storeNo}`；`storeNo` 以现有门店范围校验。所有写操作需 `Idempotency-Key`。库存权限沿 `BizPerms` 及平台权限注册；实施时按现有权限域选择最小权限，不能默认以商品查看权授予库存调整权。

| 操作 | 路径 | 输入（除路径及幂等头外） | 输出 | 规则 |
| --- | --- | --- | --- | --- |
| 创建物料 | `POST /stock-items` | `itemKind,skuNo?,name,baseUnit,trackingMode` | `itemNo,version,status` | `skuNo` 关联真实商品；材料可无 SKU；主体级身份，门店路径用于授权，不暗示自动创建门店库存。 |
| 查询物料列表 | `GET /stock-items` | `page,size,keyword?,itemKind?,status?` | `PageData<StockItemVO>` | 主体范围查询，响应 `itemNo,itemKind,skuNo?,name,baseUnit,trackingMode,status,version`。 |
| 查询物料详情 | `GET /stock-items/{itemNo}` | 无 | `StockItemVO` | 验证主体归属。 |
| 修改物料 | `PUT /stock-items/{itemNo}` | `name,status,trackingMode,version` | `itemNo,version,status` | 基础单位/SKU 不通过普通修改接口随意变更；停用前检查在途预留。 |
| 初始化门店配置 | `POST /stock-balances` | `itemNo,trackingMode,status,initialQty,reasonCode,reasonDetail?` | `balanceNo,itemNo,trackingMode,onHand,reservedQty,availableQty?,version,commandNo` | 真实数量由商家提供；TRACKED 可为 0；UNTRACKED 数量按合同为空/不参与运算，不表示无限具体数字。余额已存在返回冲突。 |
| 修改门店策略 | `PUT /stock-balances/{itemNo}/policy` | `trackingMode,status,version,reasonCode,reasonDetail?` | `balanceNo,trackingMode,status,version,commandNo` | 有未完成预留时不得直接改变跟踪策略；追踪与非追踪转换需明确库存及权限。 |
| 手工调整 | `POST /stock-balances/{itemNo}:adjust` | `delta,version,reasonCode,reasonDetail?` | `commandNo,balanceNo,onHand,reservedQty,availableQty,version,movementNo` | delta 非零；调整后不能低于预留；有审计及同键重放。 |
| 查询余额 | `GET /stock-balances/{itemNo}` | 无 | `itemNo,baseUnit,trackingMode,status,onHand?,reservedQty?,availableQty?,version` | 行缺失返回明确未配置状态或 NOT_FOUND，不能当 0/无限库存。 |
| 列表查询 | `GET /stock-balances` | `page,size,itemKind?,status?,keyword?,lowStock?` | `PageData<StockBalanceVO>` | 不跨门店汇总；lowStock 阈值的来源须明确配置，未配置时不虚构低库存结论。 |
| 查询流水 | `GET /stock-movements` | `page,size,itemNo?,action?,from?,to?` | `PageData<StockMovementVO>` | 返回流水号、业务来源、数量、单位、前后余额、原因、操作者、时间；不可修改或删除。 |
| 查询预留 | `GET /stock-reservations/{reservationNo}` | 无 | `reservationNo,status,sourceRef,planHash,items[]` | 受主体/门店范围控制；明细含叶标识、计划/消耗/释放/返还数量与单位。 |

说明：上表 `/stock-items` 等均接在前缀后；没有对 C 端暴露库存调整 API。新增接口必须在 `food-web` Controller 和独立 FOOD 远程服务链路保持一致：管理入口参考 `food-web/foodclient/RemoteModifierService`，服务端提供认证内部端点。

## 3. 内部库存合同（拟新增，不作为外部公共 API）

`FoodLocalStockPort` 独立于现有两个全局库存 SPI，避免多 Bean 破坏零售。内部方法定义由 `food-api`/项目跨模块可依赖的 SPI 保存；远程 PATH 与服务令牌由 `food-server`/`food-channel` 按项目内部合同确定并注册，避免向公网开放。

| 方法 | 输入 | 输出与边界 |
| --- | --- | --- |
| `findAvailability(scope, itemRefs)` | 可信 `entityNo,storeNo`、物料引用及冻结单位 | 各项 `TRACKED/UNTRACKED/UNCONFIGURED/DISABLED` 和可用量；未配置/不可用不得视为可售。 |
| `reserve(scope, command, plan)` | 原业务号/版本、操作键、hash、不可变叶明细、生命周期/失效约束 | 预留号、计划 hash、状态、各叶数量和回执；多物料原子预留。 |
| `confirm(scope, command, reservationNo)` | 原预留、生命周期版本与操作键 | 确认回执；按订单模式推进，不制造支付成功。 |
| `issue(scope, command, reservationNo, targets)` | 叶引用、累计目标消耗量、来源事件 | 分次消耗回执及余额；重复目标重放；累计不超原量。 |
| `release(scope, command, reservationNo, targets)` | 叶引用、累计目标释放量、取消/退餐凭据 | 释放回执；不得释放已消耗部分。 |
| `returnIssued(scope, command, reservationNo, targets, disposition)` | 原消耗流水、叶引用、累计返还、退餐/处置凭据 | 返还或损耗凭据；仅允许符合处置规则的量返可售，不能超过已消耗量。 |
| `findCommand(scope, action, operationKey, requestHash)` | 原键及请求指纹 | 已完成原回执、处理中、未找到或冲突；调用超时先查回，不改变键。 |

返回的 `commandNo,reservationNo,planHash,owner,sourceRef,version,items[],movementRefs[]` 是原业务恢复凭据；不将前端传入 owner 视为权限。

## 4. 已有外部接口及对齐动作

| 场景 | 实际路由 / Controller | 输入输出核对与本批动作 |
| --- | --- | --- |
| 桌台 | `GET/POST /biz/x/food/stores/{storeNo}/tables`、`POST /biz/x/food/tables/{tableNo}:share`、`POST /biz/x/food/checks/{checkNo}:transfer`、`:combine-tables` | `BizTableController`、`BizTableSharingController`、`BizCheckController` 已有；复用现有 DTO，验收共享占用、转台/并台与 AA 的差异。 |
| 餐牌与组合 | `POST /biz/x/food/stores/{storeNo}/menus`、`PUT /biz/x/food/menus/{menuNo}/definition`、`POST /biz/x/food/stores/{storeNo}/menus/{configNo}:publish`、`/modifiers`、`/bundles`、`/recipes` | 现有 `CreateMenuReq`、`MenuDefinitionCommand` 和发布合同；补本地库存模式、版本快照与渠道价格校验，不重造菜单 API。 |
| C/B 点单 | `POST /mp/x/food/stores/{storeNo}/batches`、`POST /biz/x/food/stores/{storeNo}/batches`、对应 GET；B 端 `:cancel` | 现有服务与 `CreateStaffDiningOrderReq`、`CancelDiningOrderReq`；保留订单返回 `DiningOrderBatchVO`，内部补库存 owner 选择与凭据。 |
| 试算/最终确认 | `POST /biz/x/food/stores/{storeNo}/quotes`、`GET /biz/x/food/quotes/{quoteNo}`、`POST /biz/x/food/quotes/{quoteNo}:confirm`；C 端对应 `/mp/x/food/...` | `DiningQuoteReq`、`DiningQuoteConfirmReq` → `QuoteVO`；沿共享引擎校验互斥、税与人工原因；不代表实际扣款。 |
| 结算 | `POST /mp/x/food/batches/{batchNo}/settlements`、`GET /mp/x/food/settlements/{settlementNo}`、`:finalize`、`:cancel`；B 端目前明确有 `GET /biz/x/food/settlements/{settlementNo}` | 保留既有合同；若商家创建/确认结算缺实际入口，先核对既有其他 Controller 和调用关系，再补 B 端合同，不能把 C 端路由冒充 B 端。`:pay` 路由存在不代表真实支付纳入本批。 |
| 厨房 | `GET /biz/x/food/stores/{storeNo}/kitchen/tasks`、`POST /biz/x/food/tasks/{taskNo}:start/:ready/:serve/:hold/:fire/:void/:loss` | `stationNo,status?,page,size` → `PageData<TaskVO>`；动作 `TaskDeltaReq` + 幂等键 → `EventVO`。本批补原库存消费和回执。 |
| 退餐 | `POST /mp/x/food/stores/{storeNo}/returns`、`GET /biz/x/food/returns/{returnNo}`、`POST /biz/x/food/returns/{returnNo}/kitchen`、业务退餐 `POST /biz/x/food/batches/{batchNo}/business-returns` | 复用既有 DTO；核对厨房处置、退款与库存返还的原凭据，未支付成功时不伪造退款。 |
| 打印 | `POST/GET /biz/x/food/print-jobs`、`GET /biz/x/food/print-jobs/{jobNo}`、`:retry`、`:reprint` | `PrintDocumentReq` → `List<JobVO>`；重打 `reason` + 幂等键 → `JobVO`。共享打印任务和设备回执，Mock 成功不等于实际出纸。 |
| 日结 | `POST /biz/x/food/business-days/{date}:open`、`POST /biz/x/food/closes:preview/:sign/:adjust`、`GET /biz/x/food/closes/{closeNo}` | 现有 `PreviewReq/SignReq/AdjustmentReq` → `CloseVO/OperationVO`；签结与资金对账分离。 |
| 离线草稿 | `POST /mp/x/food/stores/{storeNo}/offline-drafts/{challenge|enroll|lease|capture|replay|state}` | 已有设备凭据和条件开关；修复重放的事务边界，关闭时不可用；不表示离线库存/支付已支持。 |
| 平台运营 | `GET /ops/x/food/support/readiness`、`/cases`、`/source`；`GET /ops/x/commerce/support/health` | 限既有运维支持权限，商家仍为库存和税配置主体；平台不代替商家调整库存。 |

## 5. 通用渠道与报表：复用已有路由

- 渠道：`GET /biz/x/commerce/providers`；`POST/GET /biz/x/commerce/stores/{storeNo}/connections`；`PUT/GET /biz/x/commerce/catalog-scopes/{scopeNo}/sync-policy`；`POST/GET /biz/x/commerce/stores/{storeNo}/sync-runs`；`GET /biz/x/commerce/sync-runs/{runNo}`、`/items`；`GET /biz/x/commerce/stores/{storeNo}/statistics/sync`、`/statistics/order-imports`、`/integration-health`；`GET /biz/x/commerce/stores/{storeNo}/orders`、`GET /biz/x/commerce/orders/{channelOrderNo}`、`:accept/:reject/:recover-import`；`POST /callback/commerce/{providerCode}/{endpointNo}`。输入输出 DTO 以对应已实现 Controller 为准，补渠道菜单/库存、回调与恢复的真实 owner 贯通；不为餐饮重新建外送平台 API。
- 报表：已有 `GET /biz/x/food/operating-reports/metrics`、`/realtime`、`/inventory/realtime`、`/{view:daily|period|trend|payments|discounts|refunds|profit|rankings|inventory|health}`，以及报表导出 POST、查询和下载。后期对齐过滤条件、业务日、时间水位、币种、成本可信度及门店权限，优先扩展现有接口。
- 本文未逐字段展开的已有路由和 DTO，以 [实际公开 API 对照](../餐饮系统-实际公开API对照.md)、[统一渠道 API 清单](../统一销售渠道接入-API清单.md)及当前 Controller 为准；实施时只补本批明确缺口，不能把未复核路由宣称可用。

## 6. 可选进销存同步 API

本批拟新增门店同步开关、预检、映射、投递状态、重试及对账 API，以及进销存侧餐饮来源镜像查询 API。默认关闭，开启不改变餐饮库存权威；详细路径、输入输出及内部接收合同见[可选进销存库存同步方案](./04-可选进销存库存同步方案.md#6-api-清单拟新增)。这些接口目前均为设计，尚不存在。

## 本轮 API 落地补充

内部方法及路径以 05 的 3.3 节和 Java `InternalApi` 为准，外部 22 项库存/同步/来源镜像 API 数量未变。新增内部 `validate-mappings` 只读检查现有目标身份及基本单位，不创建原生进销存物料或库存。

生产耗料既有反向接口的请求增加可选 `disposition=RESALE|LOSS`、`reasonCode`，原 `version/reason` 保留。本地库存 owner 必须显式提供处置和原因分类，操作人从既有可信 scope 获取。历史进销存请求仍按旧两字段合同重放，不新增空字段改变原 hash。输出 `DISPOSED` 表示损耗处置完成且实际耗料保留；`REVERSED` 才表示返还库存。

对账输出补充 `cursorId/endId/createdAt/startedAt/finishedAt/consistencyMode`。`PAGE_OBSERVED` 表示在固定成员范围内逐页比对当次观察到的版本，不保证全表在同一时刻一致，不自动修正目标库存。

独立部署中：库存/同步 API 通过 `RemoteFoodStock*` 调用 FOOD 同一个 Service；生产事件通过 `CoreFoodProductionStockPortImpl` 查询 CORE 的原订单权威。所有跨进程调用必须在本地业务事务结束后执行，超时继续保留原键重试。

## 部分退餐及内部合同追加

前缀 `R=/biz/x/food/stores/{storeNo}/returns/{returnNo}/stock-dispositions`。主体/操作人来自认证上下文，写操作必须提供 Idempotency-Key；同时要求售后和库存权限。

| 方法 | 路径 | 输入 | 输出 |
| --- | --- | --- | --- |
| POST | R | returnItemNo、productionEventNo、dishQty（精确小数）、disposition（RESALE/LOSS）、reasonCode、reason、evidenceRef | operationNo、return/item/event 引用、数量及处置、PENDING/UNKNOWN/COMPLETED、原 stockCommandNo、最后错误 |
| GET | R | page（>=1）、size（1～100） | PageData 的原处置记录；不输出伪造退款结果 |
| POST | R/{operationNo}:retry | 原路径及原幂等身份 | 原记录及查回结果；不能换原请求、原键或数量 |

内部生命周期增加 `/internal/food/local-stock/issue-delta`：输入 Scope、原 Command、reservationNo、叶增量；在预留头锁内累加并写永久回执。原累计 issue 接口继续保留。

`FoodReturnStockInternalApi` 3 个方法复用上述业务 DTO；`FoodStockReportInternalApi` 1 个 facts 方法接收 SourceQuery（partition scope、营业日证据、actorNo、来源引用和上限）返回 SourceSnapshot。内部令牌与签名主体/门店/操作人全部核验。报表来源未配置或不唯一返回依赖不可用，不能降级读取旧库存或按零生成统计。


## 2026-10-30 M04独立库存同步交接

新增POST `/internal/food/stock-sync/dispatch`，输入entityNo、storeNo、envelope（原OutboxEnvelope全部字段），输出原DeliveryResult(kind/errorCode/detail)。沿内部token及X-Food-Scope签名；外层范围与原payload一致，缺签名401、范围不符403、缺必填输入由MVC Validator返回400。handlerId=food-stock-sync-v1，原事件键/hash/payload不变。无新增公网入口、权限码、表或配置。详见[TDD](../../TDD-餐饮独立库存同步投递.md)。


## 2026-10-30 Outbox收缩实施

公网API及权限不变。库存投递可观察状态增加PROCESSING（90秒租约）；retry不能抢占活跃租约，终态返回原结果，映射阻断先修映射。新写由FOOD直接调用inventory-client，原内部dispatch和旧handler仅处理历史Outbox积压。打印、积分API不变；管理审计改读命令账本并保留历史id游标。


## 2026-10-30 E1生产耗用直接调用

E1无新增公网或内部API。KitchenTaskService.act保持输入输出，MAKE START提交后直接调用原ProductionConsumptionService.consume(entity,store,actor,eventNo)。既有PRODUCTION_RECOVERY入口仍调用recover(limit)；首次缺口预留至多半批（至少1）额度，已有未决使用剩余额度，无缺口时旧记录可用满批；总尝试数不超过limit。BLOCKED保持待处理，不冒报耗用完成。


## 2026-10-30 E2工位状态与KDS直接写入

E2无新增或改名API、参数、权限。暂停/恢复与语音control/claim/acknowledge返回保持；成功返回时对应KDS事件已一并持久化。STATION_CONTROL/VOICE_CALL事件的stationNo、sourceNo、sourceVersion、payload语义不变，eventNo使用原BizKey事件编号；原请求/完成回执重放不重复追加。KDS订阅/回放/流接口保持。无打印的事件不访问打印owner；READY等仍要求真实打印owner。


## 2026-10-30 E3渠道有效事实订阅

E3无新增公网API/字段。内部事件元数据新增FOOD_MENU_DISABLED（仅DISABLE_MENU）、DINING_CUSTODY_HANDOVER（仅CUSTODY_HANDOVER）；其余动作保留原FOOD_MENU_CHANGED/DINING_SERVICE_EVENT。新渠道订阅不再选择草稿/普通厨房动作，旧两类handle仍重放历史payload。KDS和ReportCorrectionConsumer均接收新旧厨房事实。SourceNoticeService.receive及ChannelFulfillmentTriggerPort.record输入输出不变，原业务事件幂等及WAITING_IMPORT/真实意图回执确认保持。
