# 统一库存接入：API、Java模型与数据库调整明细

状态：实施方案，尚未编码。基线：`92342744b`，2026-10-30。
配套：[实施计划](./2026-10-30-unified-inventory-integration.md)、[已批准设计](../specs/2026-10-30-unified-inventory-integration-design.md)。路径均相对仓库根目录；类名与接口在此冻结，调整须同步实施计划。

## 1. 业务实体的抽象原则

区分四类Java对象，不能把数据库Entity直接当API：

| 对象 | 意义 | 实现位置 |
|---|---|---|
| 领域实体/聚合 | 有持续身份、版本和业务不变量 | `inventory-domain/model`；既有 `entity/Inv*` 作为持久化映射保留 |
| 值对象 | 不可变的身份、数量、来源和证明 | `inventory-api/model`，Java record，构造时校验 |
| Command/Query/Receipt | 一次业务意图、查询条件和真实业务结果 | `inventory-api/<能力>`；不得用 Map/Object 替代确定业务合同 |
| 接入适配 | 将商城SPI/认证身份映射为独立库存合同 | `shop-inventory/integration`；不进入库存领域 |

不新增 `RetailStock`、`BeautyStock`、`FoodStock` 三套相同余额实体。`InvStockBalance` 和 `InvLedger` 继续是进销存数量权威；餐饮镜像是来源事实，不是另一种进销存余额。

### 1.1 核心模型

以下record放在 `ai.neargo.inventory.api.model`，每个公开类型一个文件：

```java
record InventoryOwnerRef(String systemCode, String externalRef) {}
record InventoryItemRef(ReferenceKind kind, String value) {} // INTERNAL / EXTERNAL / SKU
record InventoryLocationRef(ReferenceKind kind, String value) {}
record StockQuantity(BigDecimal amount, String unit) {}
record InventoryOperation(String operationNo, String idempotencyKey) {}
record InventoryRequestContext(InventoryOwnerRef owner, String actor,
        String traceId, Long expectedAuthorityRevision) {}
record OwnerRoute(String ownerId, InventoryOwnerRef owner, Writer writer,
        long authorityRevision, String instanceCode) {}
enum Writer { EMBEDDED, SVC, FLIPPING }
enum ReferenceKind { INTERNAL, EXTERNAL, SKU }
```

约束：OwnerRef 的 systemCode 属于凭证系统，不允许正文覆盖；当前商城 tenant 仍严格沿用 `MAIN`，本期不扩展多租户。`SKU` 只允许ItemRef使用，LocationRef拒绝；外部编号最长64，库存内部编号最长32，按已有字段更窄的地方收紧。unit最长16，不允许缺单位或隐式换算。批量输入有明确上限，初定200行，现有更严格合同优先。

`StockQuantity` 采用 `DECIMAL(24,6)` 对应的BigDecimal，必须精确表示，超精度拒绝，不用double、不四舍五入。正数约束放在具体业务命令，余额/差异可按各自语义允许零或负数。**零售历史原单仍用整数 `DiscreteStockNeed`**，不能用小数DTO替换历史manifest；仅在确认整数与范围合法后精确转换。

`InventoryOperation` 在业务受理时确定，恢复时保持原号。新业务幂等键是稳定业务指令标识，最长64；过长复合身份用固定规范序列化的SHA-256，不能简单拼接截断。旧合同已有指令/哈希则原样保留。traceId每次传输可变，不参与业务请求摘要。

### 1.2 聚合与持久化关系

| 聚合/业务实体 | 身份与不变量 | 复用的持久化对象 |
|---|---|---|
| `InventoryOwner` | ownerId；系统归属固定；写入方与revision受控切换 | `InvOwner`、`inv_business_profile` |
| `Material` | itemId；外部引用唯一；有流水后基础单位/跟踪模式不可改 | `InvItem`、`InvItemRef` |
| `InventoryLocation` | locationId；属于同业主；来源库位不形成链 | `InvLocation` |
| `StockPosition` | owner/item/location；变动必须和不可变流水同事务 | `InvStockBalance`、`InvLedger` |
| `Reservation` | owner+externalRef；原始来源行不可重绑；占用/分配/履约数量守恒 | `InvReservation`、行、履约单 |
| `OriginalStockDisposition` | 原单+manifest+确认指令；必须有完整真实owner行证明 | V16原单字段、现有处置规则 |
| `ReturnReceipt` | receiptNo；真实原单、质量与累计退回上限 | `inv_return_receipt`及行、`inv_refund_restore` |
| `ExecutionRetention` | retentionNo/componentNo；来源/授权/消耗证明不可替换 | V17两张留存表与现有触发器 |
| `DemandPlan` | planNo+hash；叶子精确数量，delta/累计目标语义分离 | V14计划、叶子、operation表 |
| `ProductionOperation` | owner/store/eventNo；正向与原号撤回幂等 | V14生产operation及原出入库单据 |
| `StockProjectionSession` | source/entity/store+generation；旧代次不得覆盖新代次 | V18三张镜像/会话/回执表 |

聚合类只包含校验、状态转换和必要成员；逐项从现有Service提取规则，禁止先制造一套贫血Entity层再把原Service整体包一遍。`StockPosition` 不加载业主全部流水，`Reservation` 不加载商家全部订单；Repo按本次操作最小范围查询。

表中聚合表示业务边界，并非要求一次性新增十个同名包装类。本期只有需要从宿主迁出的规则才提取行为对象，已有稳定实体/Service继续复用；不为每个String创建一个无约束ID类，也不新增万能BaseCommand、BaseService或通用业务字段Map。

### 1.3 原单及美业证明模型

新增 `inventory-api/original/OriginalStockContracts.java`：独立定义与既有 `StockDispositionPort` 同构的 `OriginalStock`、`DiscreteStockNeed`、`OriginalReservation`、`ReservationObservation`、`DispositionCommand`、`DispositionObservation`、`OwnerLineProof`。保留tenant/entity/order/authority/lifecycleVersion/manifestHash和完整来源行，不删字段，不以当前SKU映射替换原单映射。

将 `domain/support/SourceStock` 的规范manifest算法提取至API可复用工具，保留兼容委派。`STOCK_MANIFEST_V1`、长度前缀、空值、排序及整数格式逐字不变；用商城 `StockDispositionContract` 做金样逐项比对，不能只验证“双方都调用了新算法”。

新增 `inventory-api/retention/ExecutionRetentionContracts.java`：将现有ComponentDescriptor、RetentionLookup、ComponentReceipt、HeadProof、LineProof、LedgerProof、OutboundProof、RefundClaimProof、ReturnClaimProof、MirrorOriginalProof、ResourceSealLookup及必要ExecutionReference子结构完整表达为库存中立合同。原schema名称和哈希算法不改；用版本化映射器转换，不通过JSON Map兜底。

**人的授权不是actor字符串。** 美业当前校验当前HUMAN SELF、源执行证据、已接受意图和DUAL镜像权威。远程化必须保留全部校验：PLATFORM在现有授权边界构造 `DelegatedActorProof(tenantNo, realm, subjectKind, actorNo)`，库存服务只接受已认证SHOP系统请求中的受限委托；通过专用回查口验证当前授权、原始执行、持久已接受意图/封存事实。系统凭证本身或 `X-Inventory-Actor` 不能替代这些证明。机器恢复仅允许已有原指令对应的查回/继续，不能伪造新的人工授权。

## 2. Java接口与代码布局

### 2.1 基础调用层

```java
// ai.neargo.svc.client；复用现有HTTP实现
interface ServiceEndpointResolver {
    Optional<URI> resolve(String serviceName);
}
enum ResolutionMode { DIRECT, DISCOVERY }
```

返回empty表示“无候选地址”，与没有配置适配器分开分类。发现实现负责健康实例选择；没有自动直连fallback。允许基址带路径前缀，但拒绝非HTTP(S)、userinfo、query、fragment；只拼接受控业务路径，保留编码查询串。`ServiceClientSpec`保留旧构造器，通过适配器桥接现有Function<String,Optional<String>>；`ServiceLocator`通过桥接复用既有ConfigServiceLocator，不要求PAY/FOOD等同批升级。

基础结果继续使用现有CallOutcome，新增NO_INSTANCE表示发现无健康实例；缺适配器/凭证仍是NOT_CONFIGURED，网络TIMEOUT/UNREACHABLE不合并。审查所有switch的兼容行为。库存SDK可把这些归为库存UNAVAILABLE并保留具体transport outcome；对写请求额外标记“执行结果未知”，不伪造库存服务已拒绝的结论。

### 2.2 库存协议层与业务入口

```java
// ai.neargo.inventory.client
interface InventoryServices {
    InventoryOwnerService owners();
    InventoryCatalogService catalog();
    InventoryStockService stock();
    InventoryReservationService reservations();
    InventoryOriginalService originals();
    InventoryReturnService returns();
    InventoryExecutionRetentionService retentions();
    InventoryDemandPlanService demandPlans();
    InventoryProductionService production();
    InventoryProjectionService projections();
    InventoryOperationsService operations();
    InventoryEventService events();
}
interface InventoryCatalogService {
    MaterialReceipt sync(InventoryRequestContext ctx, MaterialSnapshotCommand cmd);
    MaterialPage query(InventoryRequestContext ctx, MaterialQuery query);
}
interface InventoryOriginalService {
    ReservationObservation reserve(InventoryRequestContext ctx, OriginalReservation cmd);
    ReservationObservation reservation(InventoryRequestContext ctx, OriginalReservation query);
    ReleaseObservation release(InventoryRequestContext ctx, OriginalReservation cmd);
    DispositionObservation confirm(InventoryRequestContext ctx, DispositionCommand cmd);
    DispositionObservation observe(InventoryRequestContext ctx, DispositionQuery query);
}
interface InventoryReturnService {
    ReturnReceiptView receive(InventoryRequestContext ctx, ReceiveReturnCommand cmd);
    ReturnReceiptView receipt(InventoryRequestContext ctx, ReturnReceiptQuery query);
    OriginalRestoreReceipt restore(InventoryRequestContext ctx, RestoreOriginalCommand cmd);
    OriginalRestoreReceipt restoration(InventoryRequestContext ctx, OriginalRestoreQuery query);
}
```

其余接口方法与§3逐行对应；预留和镜像保留已有方法集合，需求计划保留reserve/query/withdraw/acceptUnpaid/allocatePaid/issue/issueTo/releaseTo/supplement/reverse，生产保留consume/query/reverse，执行留存保留retainComponent/queryOriginalComponent/releaseAfterNoEffect/acknowledgeIssued/continueAcceptedRetention。

新增 `InventoryClientTransport`（建立代理、超时、寻址）、`InventoryCallExecutor`（共享熔断与错误解码）、`InventoryCallOptions`（凭证Supplier与超时）、`DefaultInventoryServices`（装配能力服务）。它们不读取 `shop.*`，不依赖领域VO；宿主配置通过Options传入。旧 `InventoryReservationClient`、`InventoryConsole`、`InventoryProjection`逐步变为兼容委派，旧方法签名不删除。

服务端Controller只鉴权、校验和映射，不实现库存规则；应用Service协调幂等、远程来源预检和本地事务；领域Service执行状态转换；Mapper只做持久化。来源回查必须在取得库存锁之前，不能持有inv事务等待PLATFORM。

## 3. HTTP API调整清单

表中除特别标注外均是 **拟新增接口**，不是现有可调用能力。库存前缀为 `/api/inventory/v1`。写请求须系统/业主凭证、可信actor、稳定Idempotency-Key；新SDK写请求携带 `X-Inventory-Authority-Revision`。JSON结构由同名Command/Receipt定义，查询多字段证明采用POST且不写业务/幂等记录。

| ID | 方法与相对路径 | Command/Query → Receipt | 业务Service/规则 |
|---|---|---|---|
| A01 | GET `/system/capabilities` | 无 → `ServiceCapabilities` | 契约版本、已装配能力、实例标识；不是仅检查进程存活 |
| A02 | GET `/system/owners/by-ref/{ownerRef}/route` | ownerRef → `OwnerRoute` | 只认系统凭证；可读取本系统EMBEDDED/FLIPPING/SVC元信息；不返回余额或凭据，不创建业主 |
| A03 | POST `/system/owners/routes` | `OwnerRoutesQuery` → `OwnerRoutePage` | 仅给定ownerRefs批量查，最多200；无全量跨系统枚举 |
| A04 | PUT `/catalog/materials/by-ref/{itemRef}/source-snapshot` | `MaterialSnapshotCommand` → `MaterialReceipt` | 完整主数据快照、来源版本；含受控invManaged/onSale/retired状态 |
| A05 | POST `/catalog/materials/query` | `MaterialQuery` → `MaterialPage` | 限定业主及显式refs，基础单位/跟踪/可用状态；查询不建物料 |
| A06 | POST `/catalog/locations/resolve` | `LocationResolveQuery` → `LocationResolution` | 只查询，缺失明确NOT_FOUND；创建/更新用已有仓库写接口的类型化封装 |
| A07 | POST `/stock/availability/query` | `StockAvailabilityQuery` → `StockAvailabilityPage` | 同时返回onHand/reserved/held/safety及可供口径，不把不同available语义合并 |
| A08 | POST `/originals/reservations/reserve` | `OriginalReservation` → `ReservationObservation` | 原冻结来源，不能按今日映射重建 |
| A09 | POST `/originals/reservations/query` | `OriginalReservation` → `ReservationObservation` | 真实完整owner行证据，UNKNOWN不伪装RESERVED |
| A10 | POST `/originals/reservations/release` | `OriginalReservation` → `ReleaseObservation` | 完整终态证明，缺行不可返回RELEASED |
| A11 | POST `/originals/dispositions/confirm` | `DispositionCommand` → `DispositionObservation` | 原单处置确认，为真实原单回补提供证据 |
| A12 | POST `/originals/dispositions/query` | `DispositionQuery` → `DispositionObservation` | 不制造占用/确认记录 |
| A13 | POST `/return-receipts` | `ReceiveReturnCommand` → `ReturnReceiptView` | receipt/售后/订单/履约/来源SKU/门店/质量/数量全部绑定 |
| A14 | GET `/return-receipts/{receiptNo}` | receiptNo → `ReturnReceiptView` | 按当前凭证业主限定，不能仅按全局单号查 |
| A15 | POST `/original-restores` | `RestoreOriginalCommand` → `OriginalRestoreReceipt` | 原来源、purposeNo、累计上限，不能无来源直接加库存 |
| A16 | POST `/original-restores/query` | `OriginalRestoreQuery` → `OriginalRestoreReceipt` | 查已提交claim与回执，未知不转成功 |
| A17 | POST `/execution-retentions/retain` | `ComponentRetainCommand` → `ComponentReceipt` | 完整原执行/当前授权/原库存证明 |
| A18 | POST `/execution-retentions/query` | `ComponentLookupQuery` → `ComponentReceipt` | 原组件查回 |
| A19 | POST `/execution-retentions/release-after-no-effect` | `ComponentReleaseCommand` → `ComponentReceipt` | 先验证无生效/封存事实，再放留存 |
| A20 | POST `/execution-retentions/acknowledge-issued` | `ComponentIssuedCommand` → `ComponentReceipt` | 实际出库观察哈希必须一致 |
| A21 | POST `/execution-retentions/continue-accepted` | `AcceptedRetentionCommand` → `ComponentReceipt` | 只延续已持久接受的原意图 |
| A22 | POST `/demand-plans/{action}` | §2的方法对应独立DTO → `DemandPlanReceipt/WithdrawalReceipt` | action固定为reserve/query/withdraw/accept-unpaid/allocate-paid/issue-delta/issue-to/release-to/supplement/reverse；每项为显式Controller方法 |
| A23 | POST `/production-operations/{action}` | `ProductionCommand/Query/Reverse` → `ProductionReceipt` | action为consume/query/reverse；沿用原事件身份和精确数量 |
| A24 | POST PLATFORM `/internal/inventory/execution-evidence/query` | `ExecutionEvidenceQuery` → `ExecutionEvidenceSnapshot` | 专用服务令牌+受限SHOP委托；回查源执行、当前授权、已接受意图、镜像与封存事实；库存不反向依赖shop-base |
| A25 | POST `/offline-sales` | `OfflineSaleCommand` → `OfflineSaleReceipt` | 原线下卖出语义；不收款、不计销售额；稳定业务operation防重 |
| A26 | GET `/offline-sales` | `storeRef/date/cursor/limit` → `OfflineSalePage` | 按业主、门店、日期分页，不枚举其他业主 |
| A27 | POST `/offline-sales/{docNo}/revoke` | `RevokeOfflineSaleCommand` → `OfflineSaleReceipt` | 原出库单绑定，原号撤销入库，不删单 |
| A28 | POST `/stock/alignments` | `ApplyStockAlignmentCommand` → `StockAlignmentReceipt` | 原商城对齐流程仅将进销存一侧的盘点调整送服务；批次审批/商城规则仍留原应用 |
| A29 | POST `/stock/reconciliation/query` | `StockReconciliationQuery` → `StockReconciliationPage` | 有界物料/库位集合的真实数量/预留/版本事实；商城配额差异由平台计算，不跨库JOIN |

A02/A03走既有system路径的“仅系统身份”鉴权，不能复用普通业主路径中“非SVC读也拒”的分支；只能读路由元数据。未知业主返回明确不存在，不默认为EMBEDDED并自动建账。A24的合同位于 `inventory-api/evidence/ExecutionEvidenceContracts.java`，PLATFORM负责映射既有SPI；库存服务通过基础调用层访问，独立token不复用管理员令牌。

### 3.1 复用/兼容的既有API

- `/reservations`及accept-unpaid/allocate-paid/issue/cancel/release-allocated-to、`/system/reservations/*`、`/system/fulfillments/*`：路径保持，DTO从client/Controller提到API；原整数语义不变。
- `PUT /items/by-ref/{externalRef}`、GET `/items`、现有仓库/库存/单据/盘点/报表：兼容；新增类型化SDK映射。A04不是把现有6字段ItemSpec误当完整商城SKU快照。
- GET `/commands/{requestId}`：复用inv_command。新增SDK提供确定类型的 `CommandLookup<T>` 映射；原404仅表示当前查询未看到已提交结果，不证明请求从未执行。
- `/stock-projections` 的validate-mappings/activate/deactivate/apply/receipt/query/list/get：复用92342744b合同与generation，不新增平行镜像API。
- `/api/inventory/admin/v1/owners`、凭证、profile、flip/reconciliation：仍只给运维。商城不能带admin token开通业主。A01 capability列出实际已装配合同版本，缺依赖的美业证据能力必须报不可用。

### 3.2 请求/回执关键字段

`MaterialSnapshotCommand(operation, sourceRevision, snapshotHash, snapshot)`；snapshot含skuNo、名称、规格、条码、商家货号、baseUnit、invManaged、onSale、retired、successorRef，不能把未知旧状态默认true。版本是同一SKU投影流版本，不是market维度的PrdSku.version。

`ReceiveReturnCommand(operation, receiptNo, afterSaleNo, original, fulfillmentRef, lines)`；每行保留skuRef/sourceStore/quality/整数quantity；`RestoreOriginalCommand(operation,purposeNo,original,sourceLines)`。只有真实业务证明完整才返回COMPLETED；处理中、未知、业务拒绝是不同结果。

`ExecutionEvidenceSnapshot` 必须完整覆盖现有SPI读取的字段与哈希验证，不以一个 `boolean authorized` 替代；包括委托作用域、原始引用、完整源证据、当前身份授权、已接受意图/权威和镜像/封存事实，各子结构独立类型。缺失任一必需来源保持PENDING/UNAVAILABLE，禁止先写库存后补证据。

证据传输新增中立 `ExecutionEvidenceEnvelope(binding,query)`，binding固定schemaVersion、endpoint、action、targetNo、委托身份、requestDigest；requestDigest按现有InternalBindings的canonical规则生成/核对，不自创第二种摘要协议。PLATFORM适配器将中立binding映射为现有InternalCall后交BoundDelegatedCalls校验；库存模块仅使用IA类型，不import商城安全类。证据返回也绑定同一digest/原执行引用，错误主体、动作或target不能复用证明；普通恢复查询不因持有旧授权就获得新HUMAN写权限。

`OfflineSaleCommand(operation,storeRef,occurredAt,remark,List<SaleLine>)`，SaleLine使用原整数SKU数量；`RevokeOfflineSaleCommand(operation,storeRef)`；回执包含原docNo、撤销docNo（可空）、业务状态。`ApplyStockAlignmentCommand(operation,batchNo,storeRef,List<AlignmentLine>)`，行包含itemRef、expectedBalanceVersion、targetOnHand和reason，复用原COUNT/MALL规则，不允许借对齐偷偷释放预留；冲突返回409由平台重新预览。A29每页最多200个明确位置，返回原事实及游标，查询不得补建库位或物料。

`InventoryOperationsService` 明确提供 `sell(ctx,OfflineSaleCommand)`、`sales(ctx,OfflineSaleQuery)`、`revoke(ctx,String docNo,RevokeOfflineSaleCommand)`、`align(ctx,ApplyStockAlignmentCommand)`、`reconcile(ctx,StockReconciliationQuery)`；`InventoryEventService.pull(ctx,Long afterSeq,int limit)` 和 `ack(ctx,long seq)` 直接映射既有事件API，事件页保留seq/eventId/resourceVersion，不以获取页成功代替处理成功。

## 4. 数据库调整

**不新建统一库存余额表、统一库存流水表、三行业库存表或第二套幂等命令表。** 保留原字段和哈希、触发器；以下新增仅为接入正确性元数据。

### 4.1 复用表

| 范围 | 表 | 复用方式 |
|---|---|---|
| 主体/物料/库位 | inv_owner、inv_item、inv_item_ref、inv_location | 源引用映射与本域身份保持 |
| 数量与账 | inv_stock_balance、inv_ledger、入出库/盘点/调拨表 | 一套事务和账，不跨库JOIN |
| 预留/售后 | inv_reservation及行、inv_return_receipt及行、inv_refund_restore | 原单字段V16、累计claim上限与锁复用 |
| 美业留存 | inv_execution_retention、inv_execution_retention_claim | V17原始证明与不可变触发器复用 |
| 计划/生产 | inv_demand_plan/leaf/operation、inv_production_operation | delta、累计及原号撤回复用 |
| 镜像 | inv_food_stock_projection、inv_food_stock_sync_session/receipt | 不因统一命名而重命名表或复制数据 |
| 幂等/审计/事件 | inv_command、inv_audit、inv_outbox，平台sys_outbox及已有恢复任务 | 保持“业务写与幂等/事件同事务”，不额外造通用任务中心 |

### 4.2 拟新增迁移

基线最新库存V25、平台V621。以下先占规划号，实施前检查远端并按真实空闲号顺延；**不修改已执行迁移**。

```sql
-- inventory-domain/.../V26__owner_write_authority_revision.sql
ALTER TABLE inv_owner
  ADD COLUMN authority_revision BIGINT NOT NULL DEFAULT 1,
  ADD CONSTRAINT ck_inv_owner_authority_revision CHECK (authority_revision >= 1);

-- inventory-domain/.../V27__source_master_revision.sql
CREATE TABLE inv_source_master_revision (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  owner_id VARCHAR(32) NOT NULL,
  source_system VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  resource_kind VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
  external_ref VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  source_revision BIGINT NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  operation_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  UNIQUE KEY uk_source_master_revision(owner_id,source_system,resource_kind,external_ref),
  CHECK (source_revision >= 1),
  CHECK (resource_kind IN ('MATERIAL')),
  CHECK (payload_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- shop-app/.../V622__product_inventory_projection_state.sql
CREATE TABLE prd_inventory_projection_state (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
  entity_no VARCHAR(32) NOT NULL,
  sku_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  source_revision BIGINT NOT NULL,
  snapshot_json JSON NOT NULL,
  snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_product_inventory_projection(tenant_no,entity_no,sku_no),
  CHECK (source_revision >= 1),
  CHECK (snapshot_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

`authority_revision` 只表示物理写入权威版本，不是订单业务库存权威。进入FLIPPING和完成/回滚切换均递增；新写命令锁owner行并核对revision，过期请求返回409；正确重放可按原幂等记录查回。旧客户端缺revision期间仍靠事务内实时写入守卫，不能先关保护来兼容。

`inv_source_master_revision` 不存数量、不拥有物料主数据，只记录最后已接受源版本/哈希，与物料更新和inv_command在同一inv事务：高版本应用，低版本STALE（不修改），同版本同hash重放，同版本异hash冲突。OWNER先锁，再按资源键锁版本，唯一键并发冲突后重新读取，不覆盖。

`prd_inventory_projection_state` 是SKU完整主数据投影快照，解决PrdSku存在market维度、上下架与退休又分事件的问题。所有SKU主数据发布点在同一商品事务中，按同一tenant/entity/sku推进revision并写完整快照和sys_outbox；不以时间戳、随机UUID、Outbox全局id或单一market版本冒充来源顺序。保持既有商品锁顺序，投影状态锁置于相关商品写锁之后。

新快照事件名 `SKU_INVENTORY_SNAPSHOT_CHANGED`，旧事件保持兼容。旧无版本事件抵达时，在PLATFORM业务事务中取得/生成该SKU当前完整版本快照，提交后调用库存；不把旧载荷补一个假版本覆盖新事实。原有确认停用、在途阻断和退休规则仍由原商品服务决定，快照发布器不自行推断。

两张新表均为接入元数据，不回填或重算库存。源快照可按真实现存SKU批量建立，初次revision=1，由权威商品查询生成；不能以当前市场某一行直接覆盖全部来源。源版本记录首次接收时建，不默认为已投递成功。不得从空的neargo_inv自动推断“所有商城业主已迁移”。

### 4.3 迁移与运维要求

升级前记录各库迁移历史、owner数、余额/预留/claim与留存对账。新库可以完成独立部署，但**商城现有ai_shop_inv与云端空neargo_inv不是同一本物理账**；实际切流前必须显式选择同账原地升级或另行批准的迁移/对账流程，本计划不自动搬账。

新数据库权限限本库；触发器等需要额外权限的迁移使用一次性迁移流程并确保撤销，运行账户不保留SUPER。迁移成功后逐项验证schema与约束，失败不篡改success伪造完成。不将Nacos地址、服务密钥或行业开关存入余额表；密钥继续服务器私有环境/密钥设施。
