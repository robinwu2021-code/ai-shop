# Task 55C 工单范围精确设计与实施步骤

状态：控制器已技术放行；修正scopeHash排除随机业务号、context保留完整冻结范围后按步骤实施。

档位：2。沿用主TDD Task55、既有美业PRD与ADR045；本文件是主设计 `task-55-design.md` §2/3/7/8/9/10 的 C 精确附件，不另起 docs/plans，不改主TDD/progress/brief/总体design。

- 实施BASE：`f1d5daf809bbc481c71402fef172e37c012ae7d6`，branch `codex/beauty-foundation`。
- 工作树：`/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`。
- 55B产品与修复：`115d1cb74`、`7145421152938f09624d25a3f1a9204a392b6e66`，已独立复审通过。
- schema仍ready7/27私表，V1–V7不动，不生成V8。V7 SHA256 `8894786048bf04ca45eff129a83fa7776da867994d72dff2343a91b215d6d3f2`。
- 本步只交付052/054/055/155及其真实本地实现。无048、执行/来源绑定/交易/支付/通用恢复/Job/公开wire；Remote052临时fail-closed default由59移除。

## 1. 目标、现状与取舍

顾客已签到预约由当前获权员工一次转成冻结工单范围；无预约只创建未安排范围。两条路径都写OPEN工单、scopeRevision=1、逐项目PENDING任务、可信context、OPEN/RECEIVED提交草稿，以及同事务成功operation/ActionLog/安全Outbox。没有来源绑定，故sourceState=UNBOUND、canStart=false、实际开始为空。

已实际核对：

1. `WorkOrderPreparation.fromAppointment(BtyAppointment)`已有真实冻结定义/规则加载器，包含成功安排/hold取证；`verifyLocked`目前从hold.binding取scope，不能直接用于无预约。
2. `PreparedWorkOrderScope`已存在，原字段identity/frozen/assignments。保留该类型并扩展可信scope，不能新造同义准备DTO或复制冻结定义加载器。
3. `ReceptionCoordinator.reassign(...)`已有已审重排体，但未来D需ReceptionCoordinator→WorkOrderCoordinator；C不能反向依赖ReceptionCoordinator。
4. `AppointmentPersistence.tx`使用READ_COMMITTED + REQUIRES_NEW。不能外包一个tx后调用内部再开tx的prepare流程。最终写体和链接写体只在各自唯一现有短事务执行。
5. `AppointmentMapper`尚无markWorkOrderOpened；V1已具工单唯一appointment索引，context/submission/reception operation所需字段均存在。
6. 预约项目持久行有qty/uom，预约成功写者用COUNT。不能从requirements数量推导task数量，也不能按resourceNo去重。

选择：提取单一`ReceptionReassignment`承接现有重排体，增加持久外层operation链接重载；WorkOrderCoordinator/ReceptionCoordinator均向下调用。直接反调ReceptionCoordinator会成环；复制重排会让未来修复分叉；两者均不采用。无预约复用WorkOrderPreparation中的私有规则读取/校验过程，新增可信Scope和无hold分支，不伪造预约/hold。

## 2. 公共合同与精确DTO

模块根统一为 `backend-plugins/shop-industry-beauty`。公共接口包 `ai.neargo.shop.beauty.service`；所有下列DTO包 `ai.neargo.shop.beauty.dto`。

```java
// beauty-api/.../service/AppointmentService.java，052 additive
WorkOrderVO openWorkOrderFromAppointment(String entityNo, String currentStoreNo, String actorNo,
        String appointmentNo, OpenAppointmentWorkOrderReq req, String idempotencyKey);
// beauty-api/.../service/WorkOrderService.java，仅这三个实际能力
WorkOrderVO openWorkOrder(String entityNo, String currentStoreNo, String actorNo,
        OpenWorkOrderReq req, String idempotencyKey);
WorkOrderDetailVO getWorkOrder(String entityNo, String currentStoreNo, String actorNo, String workOrderNo);
MyWorkOrderVO getMyWorkOrder(String userNo, String workOrderNo);
```

052接口临时default明确抛`UnsupportedOperationException("reception wire is owned by Task59")`，本地AppointmentServiceImpl真实覆盖；RemoteAppointmentService继承default只为编译兼容，不计远程已实现。不给WorkOrderService添加056/065–067/071等空方法。

新增DTO的record字段按以下顺序固定：

| 类 | 精确字段 |
|---|---|
| OpenAppointmentWorkOrderReq | `Long expectedRevision, List<AppointmentAssignmentReq> assignments, OffsetDateTime startAt` |
| OpenWorkOrderReq | `String customerRef, String storeNo, List<AppointmentItemReq> items, String appointmentNo, String guestNo, Long expectedRevision, List<AppointmentAssignmentReq> assignments, OffsetDateTime startAt` |
| WorkOrderVO | `String workOrderNo, String appointmentNo, long scopeRevision, String status, String submissionNo, String sourceState, String operationNo, String operationState, List<String> taskNos, OffsetDateTime plannedStartAt` |
| WorkOrderDetailVO | `WorkOrderVO workOrder, CustomerIdentityVO customer, long originalScopeRevision, List<WorkOrderTaskVO> tasks, List<String> availableActions` |
| MyWorkOrderVO | `WorkOrderVO workOrder, long originalScopeRevision, List<WorkOrderTaskVO> tasks, List<String> availableActions` |
| WorkOrderTaskVO | `String taskNo, String appointmentItemNo, long scopeRevision, String skuNo, String definitionVersionNo, BigDecimal qty, BigDecimal doneQty, BigDecimal voidedQty, String uom, String status, long bindingRevision, String assignmentState, String sourceState, boolean canStart, OffsetDateTime plannedStartAt, OffsetDateTime plannedEndAt, OffsetDateTime actualStartedAt, List<AppointmentAssignmentVO> assignments, WorkOrderTaskSnapshotVO taskSnapshot, List<WorkOrderStepVO> steps, List<WorkOrderParticipationVO> participations, List<String> availableActions` |
| WorkOrderStepVO | `String stepNo, String stepCode, int sequenceNo, String status, OffsetDateTime startedAt, OffsetDateTime finishedAt` |
| WorkOrderParticipationVO | `String participationNo, String personnelNo, String roleCode, OffsetDateTime startedAt, OffsetDateTime endedAt` |

复用已有CustomerIdentityVO、AppointmentItemReq、AppointmentAssignmentReq/VO、WorkOrderTaskSnapshotVO、WorkOrderRequirementVO、WorkOrderStepTemplateVO。不改已有公开冻结快照字段。

校验：业务号≤32、actor≤64、原key≤128；OffsetDateTime输出UTC；列表防御复制且拒null元素。OpenAppointmentWorkOrderReq保留`(Long,List<AppointmentAssignmentReq>)`构造器，startAt=null；revision必填且≥0。OpenWorkOrderReq保留原四参数构造器`(String customerRef,String storeNo,List<AppointmentItemReq> items,String appointmentNo)`，扩展字段默认null/空列表；带appointment时expectedRevision必须提供，旧构造器仅无预约有效。customerRef含cr1，toString必须REDACTED。

054带预约：store必须等于当前可信店；转成052的同一内部写体，但原hash覆盖**完整054请求**，endpoint仍BAPI-054，不能丢弃items/customerRef/guestNo后用052 hash。items为空表示确认原完整范围；非空必须与成功安排展开后的SKU顺序/数量完全一致，不覆盖定义。客户字段可省；提供customerRef或guestNo时锁外验证且与原范围规范身份一致。无预约：customerRef/guestNo恰一、items非空、qty展开总数≤10；拒非空assignments、startAt和无意义expectedRevision。

新单VO：OPEN/1/UNBOUND，operationState=SUCCEEDED。REPLACE待决VO：workOrderNo/submissionNo=null、scopeRevision=0、status=PENDING、taskNos=[]、operationState=PENDING，保留原operationNo和appointmentNo，不伪报OPEN。READ为唯一availableActions；legacy动作[]。

## 3. 文件责任、实际辅助签名与Bean DAG

以下辅助均在 `beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl`，FQN前缀 `ai.neargo.shop.beauty.service.impl`。Mapper仍 `ai.neargo.shop.beauty.mapper.BeautyMappers`，不建Repository。配置在core的 `ai.neargo.shop.beauty.config`。

### 3.1 WorkOrderPreparation：唯一冻结加载器

保留并真实扩展：

```java
PreparedWorkOrderScope fromAppointment(BtyAppointment root);
PreparedWorkOrderScope unassigned(String entity, String store, VerifiedIdentity identity,
        List<AppointmentItemReq> items);
void verifyLocked(PreparedWorkOrderScope scope);
```

`PreparedWorkOrderScope`在既有identity/frozen/assignments后追加 `ResourceBookingPort.Scope scope`。所有构造点由server持久root或已核验catalog店事实提供，禁止调用方直接提供冻结内部对象。既有B的三字段构造仅可由非空hold.binding安全派生Scope；无hold必须用四字段构造，不能默认MAIN/任意store。无预约使用既有FrozenAppointmentScope，holdEvidence=null；items每份COUNT对应一条内部AppointmentPlanItemVO，appointmentItemNo与计划时间=null。此内部数据不落appointment表、不发预约号，不当作执行事实。

私有规则加载提取为同一`loadDefinition(scope,item,heads)`，负责definition→version→requirements父链/hash/P1步骤/notes；原预约和无预约共享，无二次抄写。`verifyLocked`从prepared.scope取可信scope，与非空hold.binding相等；按去重后稳定`kind/businessNo`顺序锁heads，核tenant/entity/store/deleted/status/version/currentVersion引用。预约的selected frozen version可旧于current，比较的是观察head版本；不以current新定义覆盖原冻结定义。

### 3.2 ReceptionReassignment：现有B重排的单一归属

新类的真实接口：

```java
BtyAppointmentOperation reassign(String entity, String store, String actor, String appointment,
        long revision, List<AppointmentAssignmentReq> assignments, OffsetDateTime startAt, String key);
BtyAppointmentOperation reassign(String entity, String store, String actor, String appointment,
        long revision, List<AppointmentAssignmentReq> assignments, OffsetDateTime startAt, String key,
        String receptionOperationNo);
```

第一重载供已存在B测试/内部使用，委托第二重载receptionOperationNo=null；从ReceptionCoordinator移动整个既有reassign/matchesExistingAssignments/ItemMapping/ReplacementSnapshot，不复制匹配循环。ReceptionCoordinator.reassign保留短委托以避免已有内部调用断裂。新重载只增加外层原operation持久链接，不改变冻结匹配/容量分配/失败保旧语义。

`AppointmentPreparationService.prepareReception`保留现签名，并增加末尾`String receptionOperationNo`重载；构造器注入ReceptionOperationMapper。其私有prepare现有单一db.tx中，在root→fence→current之后锁原接待op，验证scope/actor/appointment/phase和稳定derived key；把新appointment operation_no与外层RESOURCE_PENDING关联在**同一事务**插入/更新。链接已存在必须验证并返回绑定原op，不能再建op。外层传入的key由已持久的derivedKeyHash稳定派生，不重算随机值；owner接受前已提交绑定。prepare不能被WorkOrderCoordinator外包tx。

无变更返回null只表示已比较确认原完整安排；外层只在本次确已取得并比较现安排证据后允许后续最终事务；不能把任何null/异常/UNKNOWN当作安排成功。

### 3.3 WorkOrderCoordinator：两入口共享最终写体

```java
WorkOrderVO openFromAppointment(String entity, String store, String actor, String appointmentNo,
        OpenAppointmentWorkOrderReq req, String key, String endpoint);
WorkOrderVO openUnassigned(String entity, String store, String actor, OpenWorkOrderReq req, String key);
WorkOrderVO open(String entity, String store, String actor, OpenWorkOrderReq req, String key);
```

open负责054分支与完整054 hash；openFromAppointment负责052。共享私有`openPrepared(...)`做最终工单/context/task/submission/log/outbox/operation写入，不让AppointmentService/ReceptionService互调。未来D接入同一真实最终写体，C不预建openReception等空方法。

内部持久record放此helper，精确结构：

- `OpenPayload(int schemaVersion,String appointmentNo,long expectedRevision,List<AppointmentItemReq> items,List<AppointmentAssignmentReq> assignments,OffsetDateTime startAt,String scopeHash,Long openingRevision)` implements ReceptionPreparation.ReceptionPayload。客户仅放operation.identityKind/identityRef，不保存cr1/guestToken；完整请求已进入requestHash。scopeHash绑定原经核验冻结范围，openingRevision为原revision或REPLACE明确成功应得revision。
- `TaskExecutionSnapshot(int schemaVersion,String assignmentState,WorkOrderTaskSnapshotVO taskSnapshot,List<AppointmentAssignmentVO> assignments,OffsetDateTime plannedStartAt,OffsetDateTime plannedEndAt)`：task.execution_snapshot真实冻结执行内容，无价格/资金结论。
- `WorkOrderScopeSnapshot(int schemaVersion,long originalScopeRevision,String scopeHash,String policyVersionNo,PublishStorePolicyVersionReq.CancellationRule cancelRule,FrozenAppointmentScope frozen,List<AppointmentAssignmentVO> assignments,List<TaskReference> taskRefs)`：context.frozen_scope完整原定义/规则/安排/hold引用及实际任务映射；originalScopeRevision=1，无预约holdEvidence=null，不存票据。
- `OpenSubmissionDraft(int schemaVersion,long scopeRevision,String scopeHash,VerifiedIdentity identity,List<TaskReference> taskRefs)`，`TaskReference(String taskNo,String skuNo,String definitionVersionNo,BigDecimal qty,String uom)`；无source/trade命令、票据、支付号。

ReceptionPreparation.sealed payload增加OpenPayload并显式action=OPEN_WORK_ORDER，不能让现有二分类型判断落成RECEPTION_EXCEPTION；begin/find全命名空间保持，begin在当前事务直接insert，事务外才开初始tx。新增原键竞态只捕获DuplicateKeyException，重读同namespace/hash；非唯一异常向上传播。

### 3.4 WorkOrderQuery与Services

```java
// WorkOrderServiceImpl owns access checks and SQL root authorization through WorkOrderMapper.
// The projector receives that authorized persisted root, never an untrusted client number.
WorkOrderDetailVO WorkOrderQuery.detail(BtyWorkOrder authorizedRoot);
```

WorkOrderServiceImpl实现open/get/getMine并直接消费本域WorkOrderMapper：get先AppointmentAccess.merchant(...,false)，再db.read下selectScoped(entity,store,workOrderNo)取得SQL授权根；getMine先access.consumer(user)，再selectOwned(user,workOrderNo)取得SQL归属根。空根一律NOT_FOUND，随后在同一读取封装内调用WorkOrderQuery.detail(BtyWorkOrder authorizedRoot)，C再裁剪成MyWorkOrderVO，不重复根SQL、不先加载全量后Java过滤。AppointmentServiceImpl注入WorkOrderCoordinator实现052，旧预约方法保持。两个Service不相互引用。WorkOrderQuery.detail只负责Service已SQL授权根的本域子行读取/投影（直接根读取在ServiceImpl），不调用资源/身份owner，不创建step/participation。

DAG（当前C实线，D待实施关系仅说明，不生成空Bean）：

```text
AppointmentServiceImpl -> WorkOrderCoordinator -> WorkOrderPreparation -> AppointmentCoordinator
WorkOrderServiceImpl   -> WorkOrderCoordinator -> ReceptionReassignment
                      -> WorkOrderQuery         -> 本域Mapper
ReceptionServiceImpl   -> ReceptionCoordinator -> ReceptionReassignment
ReceptionReassignment  -> AppointmentMatching / AppointmentPreparationService / AppointmentCoordinator
                       -> WorkOrderPreparation
WorkOrderCoordinator   -> ReceptionPreparation / ReceptionIdentity / AppointmentAccess / ReceptionAccess
各辅助                  -> 本域Mapper / AppointmentPersistence / Clock / Json / beautyOutboxPublisher
未来D ReceptionCoordinator -> WorkOrderCoordinator（无反向边，因重排已经向下提取）
```

WorkOrderPreparation不依赖WorkOrderCoordinator/Reassignment。AppointmentPreparationService只新增Mapper依赖，不依赖WorkOrderCoordinator。内域Bean全部强依赖，不以ObjectProvider/@Lazy/Service反调隐藏循环；外部Port保留现有按需真实provider方式。BeautyReceptionConfiguration注册新helper/Service；BeautyAppointmentConfiguration更新AppointmentServiceImpl/AppointmentPreparationService装配。ReceptionBusinessIT、AppointmentBusinessIT及现有构造处同步真实装配。

## 4. 原键、预约转单与UNKNOWN恢复

### 4.1 权限与hash必须在重放前

052/054每次先`AppointmentAccess.merchant(entity,store,actor,true)`及`ReceptionAccess.customer(entity,store,actor)`，核SecurityUtils当前actor。然后计算完整endpoint/tenant/entity/store/realm/kind/actor/完整规范请求hash，按原keyHash查ReceptionPreparation.find。同key变参CONFLICT；SUCCEEDED直接反序列化原result，不校旧expectedRevision，不重验已经过期的原cr1，也不再次调用资源。

只有新操作/未成功动作继续做外部身份事实校验。052规范USER来自已持久可信预约身份；GUEST须owner.get核真实同店guest。若规范化为USER，须已有同scope不可变receipt证明与原guest关系，否则不能自动认领。054提供cr1时resolveClaimReference，提供guestNo时owner.get，核与预约同一身份。无预约的guest CLAIMED可使用owner规范USER；不得把GUEST编码当buyer。

fingerprint用既有长度前缀requestHash及JSON确定序列，null显式编码；054所有字段包含在内。保存的payload不含原凭证。derivedKeyHash在外层operation创建时保存；REPLACE key=`requestHash("BEAUTY_OPEN_REPLACE_V1",op.derivedKeyHash)`，OPEN_DRAFT请求键=`requestHash("BEAUTY_OPEN_DRAFT_V1",op.derivedKeyHash)`，命名空间已包含endpoint/actor/key。不是operation随机号重生成，不随重试者变化。

### 4.2 新操作与REPLACE持久窗口

1. 锁外读同scope根，要求CHECKED_IN/checkedInAt非空及请求revision正确，加载当前成功安排冻结证据，核真实项目行与COUNT、相应原定义/规则。禁止隐式签到。
2. 短事务按root→fence→current核当前观察；插入外层RECEIVED/OPEN_PENDING操作及OpenPayload/derivedKeyHash，保存规范身份和冻结范围hash。事务提交后才调用重排。无需重排可直接进入最终事务；多key仍由最终根锁仲裁。
3. ReceptionReassignment用稳定key找原appointment op。匹配确有变化才走同一已审matchFrozen。prepareReception在根/fence/current/接待op同事务写绑定后才advance。无RPC处于本域事务/锁内。
4. 待决或UNKNOWN保留外层RESOURCE_PENDING及appointmentOperationNo；返回PENDING，无工单/task/submission。accept响应丢失时原op.acceptPayload/原key继续；已receipt则按原command恢复，不能另发无关新命令。
5. 确定REJECTED外层REJECTED/failureCode=CONFLICT，旧安排/hold不变；重放同key仍返回该确定拒绝，不自动回退建单。
6. 确定SUCCEEDED：核绑定原op.action=RECEPTION_CHANGE/appointment/scope、持久receipt.status=SUCCEEDED/action=REPLACE/sourceRevision等于原expectedRevision。成功本域finish只推进根一次，openingRevision固定为receipt.binding.sourceRevision+1；将该确切值与绑定op/phase写入原payload。不能把任意当前root.version当新expected。再锁外fromAppointment加载成功新安排及当前hold，任务引用新成功item号，原规则仍冻结。
7. 重启/同原key重试首先找到外层op。有关联则只推进关联原appointment op；没有关联则用原derived key和冻结请求重试准备。无变更无绑定时只允许原revision/原scopeHash再次确认，不把别的员工后来重排当作本次成功。

只实现052/054原请求重放。第二员工generic operation恢复/后台Job/048归D，此处不增加公开恢复入口。

### 4.3 最终短事务与并发防线

同一db.tx内：

1. appointment root FOR UPDATE（无预约略）→永久fence FOR UPDATE→current FOR UPDATE→原reception operation FOR UPDATE。
2. 原op SUCCEEDED则返回原冻结结果；若异key根已有workOrder或实际task则CONFLICT。不开第二草稿，不借用赢家result。
3. 重核root.status=CHECKED_IN/checkedInAt、版本等于openingRevision、current.operationNo=null、fence.activeIntentNo=null、current arrangementRevision/arrangement hash/holdNo/identity/start/end与锁外观察一致。必须有真实fence/current，缺失拒绝。now>=end拒绝未重排的旧计划。
4. 按稳定顺序`verifyLocked`重核本域定义/政策heads及完整父链（都是本域SQL，不RPC）。
5. `AppointmentMapper.markWorkOrderOpened(entity,store,appointment,expectedVersion,actor,updatedAt)`条件CAS：状态仍CHECKED_IN，仅version+1，checkedInAt非空、存在空current/空永久fence、无任何已存在appointment工单。代码检查影响行=1。实际task/安排证据核对仍在同根锁事务内。不能写IN_SERVICE或startedAt。
6. 创建workOrder→context→tasks→submission→ActionLog→safeOutbox→op SUCCEEDED/result，任一步失败全部回滚。先持久的待处理外层op允许保留OPEN_PENDING，但绝不能保留半个范围或成功结果。

唯一索引`uk_bty_work_order_2(tenant,entity,appointment_no)`与根锁共同守一单，包括软删历史不允许复用appointment。相同原键并发在op锁下重放唯一result；不同key输家CONFLICT。最终写体不调用ReceptionPreparation.begin的事务外分支，也不嵌套db.tx。

无预约没有根锁，按原operation行锁→稳定heads→新工单写体串行同key；不同key是独立请求，可各建一单。若定义观察失效，CONFLICT，不重取最新定义悄悄换范围。

## 5. 无预约真实准备与落库细节

无预约不调用AppointmentMatching.match/matchFrozen或ResourceBooking。WorkOrderPreparation.unassigned锁外通过ServiceCatalogFactsPort.store/service核店ACTIVE、entity/store/目录scope一致、sku/goods引用、商品服务事实存在、可售窗口、非负price与duration/master revisions；SERVICE类型保证边界是该真实owner的service查询（其实现必须只返回SERVICE），core fixture如实标注。

不依赖`ServiceCatalogQueryService.getPublicService`强制要求门店policy存在的路径：本步只冻结可售SERVICE与当前已发布定义，并不预约。直接使用真实ServiceCatalogFactsPort，然后在本域读取ACTIVE definition/head.currentDefinitionVersionNo，version父链和schema/hash/P1 stepsTemplate=`[SERVICE/0]`严格重核；qty每份展开1，最多10。店policy存在时读取当前有效版本/原完整cancelRule及ObservedHead；完全不存在时两个引用null，不创建默认政策、不伪造hold。定义/政策的重复head只锁一次，保持确定顺序。

预约路径继续从原arrangement snapshot加载，不走最新目录替换定义；若请求REPLACE，现有matchFrozen负责当前可售检查。fromAppointment补核真实appointment item行的tenant/entity/appointment/itemNo/sku/definition/qty/uom与snapshot严格一致；COUNT不转换EA/空单位。所有资源容量份额完整保留，包括同requirement qty2同一capacity2资源两行及跨item共享。

持久值：

| 记录 | 精确值 |
|---|---|
| BtyWorkOrder | 业务号BTWO；appointmentNo有则原号否则null；customerRef=`USER:`或`GUEST:`+规范reference；OPEN、scopeRevision=1、scopeFrozen=false、startedAt/finishedAt/closedAt=null |
| BtyWorkOrderContext | 同tenant/entity/store/workOrderNo；identityKind/ref；sourceReceptionOperationNo原外层op；appointmentArrangementRevision为成功安排revision，无预约null；frozenScope含原scopeRevision/hash/完整policy引用及rule/taskNos |
| BtyServiceTask | 业务号BTST；每个原安排item一行或无预约每份一行；scopeRevision=1；原qty/uom=COUNT（不按资源份额造task）；doneQty/voidedQty=0、PENDING、bindingRevision=0、startedAt/finishedAt=null；无预约appointmentItemNo=null |
| task.executionSnapshot | schema1、ASSIGNED或UNASSIGNED、完整原WorkOrderTaskSnapshotVO、按item保留全部assignment份额、真实planned时间；无预约assignments=[]/planned时间null |
| BtyServiceSubmission | 业务号BTSU；action=OPEN/status=RECEIVED，唯一requestKeyHash派生于原完整namespace；payloadHash=受控draft序列化摘要，frozenCommand仅OpenSubmissionDraft；nextRetryAt/failureCode=null |
| BtyActionLog | aggregateType=BEAUTY_WORK_ORDER、aggregateNo工单号、action=WORK_ORDER_OPENED、requestRef原op；operatorRef当前真实actor、expectedRevision=0/resultRevision=1；safeSnapshot仅state/scopeRevision/taskNos安全引用 |
| BeautyReceptionStateChanged | 现有真实outbox路由，businessNo工单号/entity/store/revision1/state=OPEN；dedupe=`BEAUTY:RECEPTION:`+原operationNo；不含identity/proof/plan/支付 |

scopeHash是schema、规范identity、scopeRevision=1、原预约itemNo或无预约展开lineIndex+SKU/definition/qty/uom、原定义快照、原完整policy/rule、安排及时间的稳定摘要；排除最终随机taskNo/workOrderNo/submissionNo，因此准备阶段可稳定计算；最终draft保存真实taskRefs并引用同一hash。不能只hash SKU。生成业务号在被串行化的最终事务内一次生成，回滚后未成功号码不对外承诺。提交草稿不含任何外域下单指令，nextRetryAt=null确保不会被普通submission scanner自动发送；58要求当前合法人明确submit。

## 6. SQL授权、父链与投影

所有自定义SQL显式tenant=MAIN（与当前Beauty部署及SecurityUtils租户限制一致）、实体、门店、deleted=0；使用当前可信上下文，不采信请求自报entity。新增Mapper签名：

```java
// WorkOrderMapper
BtyWorkOrder selectScoped(String entityNo,String storeNo,String workOrderNo);
BtyWorkOrder selectOwned(String userNo,String workOrderNo);
// WorkOrderContextMapper
BtyWorkOrderContext selectScoped(String entityNo,String storeNo,String workOrderNo);
// ServiceTaskMapper
List<BtyServiceTask> selectForWorkOrder(String entityNo,String storeNo,String workOrderNo);
// ServiceStepMapper / ServiceParticipationMapper
List<BtyServiceStep> selectForTask(String entityNo,String storeNo,String workOrderNo,String taskNo);
List<BtyServiceParticipation> selectForTask(String entityNo,String storeNo,String workOrderNo,String taskNo);
// ServiceSubmissionMapper
BtyServiceSubmission selectOpenForWorkOrder(String entityNo,String storeNo,String workOrderNo);
// AppointmentCurrentMapper
BtyAppointmentCurrent selectScopedForUpdate(String entityNo,String storeNo,String appointmentNo);
```

055先BOOKING_VIEW或MANAGE，SQL同店root；正常context须同tenant/entity/store/workOrderNo活跃，root.customer_ref必须等于`CONCAT(context.identity_kind,':',context.identity_ref)`。context存在但错scope或编码不匹配是损坏/越权，不降为legacy。真正无任何该工单context时仅B返回customer UNKNOWN/reference=null、originalScopeRevision=0、source UNKNOWN、动作[]；保守只返回可证明的真实父链子行，不能猜来源/冻结定义。根软删B/C均NOT_FOUND。

155先当前CONSUMER/USR且userNo等于SecurityUtils，再单条SQL INNER JOIN上述有效context，WHERE：

```sql
(c.identity_kind='USER' AND c.identity_ref=#{userNo})
OR (c.identity_kind='GUEST' AND EXISTS (
 SELECT 1 FROM bty_guest_claim_receipt r
 WHERE r.tenant_no=w.tenant_no AND r.entity_no=w.entity_no AND r.store_no=w.store_no
   AND r.guest_no=c.identity_ref AND r.user_no=#{userNo} AND r.deleted=0))
```

没有context或只有外部owner CLAIMED无本域不可变receipt均不可见。根选择结果为空即NOT_FOUND，之后才读任务；不先加载全部再Java过滤。C DTO不回显顾客身份。

task查询JOIN活跃workOrder，同tenant/entity/workOrderNo及store，scope_revision>0且≤root.scope_revision。有appointmentItemNo须EXISTS同tenant/entity原预约item父链且item.appointment_no=root.appointment_no；task若引用另一预约/软删item不返回。step/participation查询再JOIN相同活跃task及root，约束指定workOrderNo/taskNo，不凭全局task号查询；错实体/删task/错工单均不返回。submission同样JOIN根且action=OPEN。查询不从当前appointment arrangement重建执行快照，使用任务已冻结snapshot；soft-delete/wrong parent测试直接插脏子行证明SQL防线。

WorkOrderVO读取从真实工单/context/原operation/submission构造；taskNos来自有效task查询。初始sourceState=UNBOUND/canStart=false；不因appointment/hold存在推断已绑定。steps/participations只投影实际表行；初始均[]，不能用stepsTemplate生成执行事实。原始scopeRevision从context JSON取1，不使用当前scopeRevision代替。legacy坏/缺snapshot不能伪造可执行任务。

## 7. AC→测试与有效RED计划

核心新套件 `beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/WorkOrderScopeIT.java` 使用真实MySQL/mappers/事务/Outbox。资源/目录为受控fixture，回报边界；身份新消费链补真实host `backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautyReceptionIdentityIT.java`，不在core生产反向依赖member。

| C AC | 精确测试方法 | 断言 |
|---|---|---|
| 052一次转单/并发 | originalKeyReplaysAndDifferentKeysCreateOneScope | 两连接同预约不同key仅一OPEN、逐原item task、一context/submission/log/event；输家CONFLICT；同key旧revision返回原结果 |
| hash/当前权限 | replayChecksFullRequestAndCurrentPermissionBeforeFrozenSuccess | 改items/customer/assignments/time/revision任一同key冲突；权限撤销拒绝；成功原cr1过期可重放；不同endpoint不混键 |
| 非隐式到店/旧计划 | requiresCheckedInAndLiveOriginalArrangement | CONFIRMED/缺checkedInAt/过期end/缺fence/current或active fence均拒，0范围写入 |
| 项目数量与资源份额 | preservesExpandedItemsAndEveryResourceCapacityUnit | qty2服务展开2task、多定义分别冻结；一个item ROOM qty2两份同resource保留；跨item同capacity资源不去重；任务数不随资源份额变动 |
| 原冻结规则 | catalogChangesCannotOverwriteFrozenScope | 后续发布新duration/buffer/requirements/notes，开单仍原version/hash/规则；重核间head变化拒绝；错version父链拒绝 |
| 原键REPLACE恢复 | replacementAcceptUnknownReusesDurableOperationAfterRestart / replacementExecuteUnknownReusesDurableOperationAfterRestart | accept/confirm丢响应返回PENDING且0单/0草稿；重建服务实例原key原outer/appointment op/key/command绑定；确定成功后仅一单；确定拒绝保旧hold/安排 |
| 开单后重排禁止 | openedAppointmentRejectsReceptionReassignment | C真实开单后B重排被拒，无新owner调用/安排 |
| 未安排 | unassignedOrderHasNoResourceOrExecutableTask | 真实店/可售服务/发布定义、qty≤10；无policy亦可；0appointment/hold/assignment/step/participation；PENDING/UNASSIGNED/UNBOUND/false/null |
| 未安排可信scope | unassignedPreparationRejectsForeignInactiveAndChangedHeads | 跨店目录事实、inactive服务/定义、错definition父链、head变更均拒；不造假hold；owner调用都不在本域TX |
| SQL授权父链 | scopedReadsRejectDeletedAndForeignParents | B异店、C他人/跨realm、错context编码/parent、删根/task/item、错step/participation父链不泄露；真实子行才投影 |
| USER/receipt/legacy | legacyContextIsReadOnlyUnknownAndReceiptOwnershipIsScoped | 无context B UNKNOWN/动作[]，C拒；同scope receipt授权，异scope/无receipt拒；C不含identity |
| 原子性 | openScopeRollsBackWhenOutboxFails | 注入真实outbox publish失败，workOrder/context/tasks/submission/log/success结果全部回滚，预约version不推进；原op可重试一次成功 |
| 装配 | BeautyConditionalWiringTest.workOrderWiringHasNoCycle | 当前真实config Bean构造无环，关闭/remote形态不冒出本地业务bean；已存在B回归构造更新 |
| 真实新消费身份 | BeautyReceptionIdentityIT.workOrderOpeningUsesRealCustomerAndGuestFacts | 真cr1/guest事实新消费链，伪串/过期新动作/旧BTG拒，guest CLAIMED规范化与receipt边界；成功原键过期cr1重放 |

RED必须是可执行测试已完成真实fixture/数据库装配后对业务结果的失败；编译错误、缺bean、Docker/JDBC故障不计。先加完整合同DTO与052明确失败的既定兼容default使测试可编译，测试通过真实本地AppointmentService调用新052，期望OPEN范围却触发尚未覆盖的fail-closed缺行为，记录失败堆栈；这只作为第一条能力RED，后续每条范围/持久/父链断言在具体防线实现前继续取得业务RED。不得以反射method-not-found、无schema或空DTO构造失败宣称业务RED。控制器核对该首RED策略后执行。

有效消融选SQL子行父链：在正常真实工单及正常step返回的基准上插一条跨entity同taskNo step；临时移除step查询中entity父链条件，运行scopedReadsRejectDeletedAndForeignParents精确断言，必须返回污染step并RED。恢复条件同测试GREEN。若其他防线先挡住而仍绿，则重选/撤除实际生效的冗余条件后取得真实RED；不能把无效消融记通过。

## 8. 实施步骤与验证命令

执行方式：本agent原地实施，精确owned变更；控制器技术核对本设计后放行。不spawn agent、不另开工作树。采用writing-plans与TDD顺序，实施前读取test-driven-development/verification-before-completion技能。

- [ ] 步骤1：落完整新增DTO/052明确fail-closed兼容合同、WorkOrderScopeIT第一条真实开单断言；固定fixture已成功签到，跑首业务RED，记录真实失败。当前设计阶段不做此步。
- [ ] 步骤2：从ReceptionCoordinator移动重排体到ReceptionReassignment，加入同TX原接待op链接重载与装配，补UNKNOWN/原号恢复RED→GREEN；受影响B精确回归保持绿。
- [ ] 步骤3：补WorkOrderPreparation可信Scope/共用冻结加载器/真实unassigned、真实item数量单位校验，写数量与未安排RED后实现GREEN；不变更schema。
- [ ] 步骤4：实现WorkOrderCoordinator共享最终写体、markWorkOrderOpened/current锁/operation状态、context/tasks/submission/log/outbox；一次转单/同key/异key/回滚用例RED→GREEN。
- [ ] 步骤5：实现WorkOrderServiceImpl/Query及SQL父链、全部详情DTO；所有归属/软删/真实子行用例RED→GREEN，执行一次有效父链消融并还原GREEN。
- [ ] 步骤6：新增host真实身份消费链、装配与受影响B/预约回归；方法JSON/生成真源同步，受影响常规reactor/架构/三语/SQL/生成一致性检查。不会跑无关整个backend专项。
- [ ] 步骤7：记录task-55c-report.md包含基线、精确RED/GREEN/消融命令输出、文件stat与范围边界；逐文件diff，精确stage owned文件并commit；报告commit停止独立review，不进入D。

已存在native脚本支持BEAUTY_TESTS环境选择；直接使用，不新增不存在的runner参数。JDK/Maven：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
BEAUTY_TEST_DISK=1 BEAUTY_TESTS='WorkOrderScopeIT#originalKeyReplaysAndDifferentKeysCreateOneScope' \
node scripts/verify-beauty-persistence.mjs

JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 \
-f backend-plugins/shop-industry-beauty/pom.xml test

node scripts/check-sql-portability.mjs
python3 scripts/gen-beauty-java-method-design.py
python3 scripts/gen-beauty-java-method-design.py --check
python3 scripts/gen-beauty-database-design.py --check
```

native按实际fixture建库量每批≤3case分组，使用现成runner的cached mysql:8.4 / pull never / UUID / --rm / localhost TCP / readiness / finally仅清本容器；BEAUTY_TEST_DISK=1对应64MB buffer和performance_schema OFF，无host/shared mount。一个自己的容器，不碰food/pricing。已绿未改套件不反复重跑；大套件连接EOF此前无OOM证据，不强归因。

host测试先install受影响共享/beauty模块到隔离m2，再使用已有native配置或增加精确host测试选择（不能把默认BeautyPolicyEndToEndIT当作新链已验）。BeautyArchitectureTest明确扫插件，BackendI18nParityTest扫共享错误码/三语；新增错误优先复用BAD_REQUEST/FORBIDDEN/NOT_FOUND/CONFLICT/既有美业identity与reschedule码，若确需新增则三语同补。

方法生成真源为 `docs/technical/design/schema/beauty-java-method-design.json`，对应生成两份Java/方法清单；DTO字段相关API真源同步后重生成，不把目标160个接口宣称已交付。数据库不变，字典只校验一致性，不改V1–V7字节。三项继承global guard（旧OpsPoints FQN、旧V506 parser单空格、JobLockConfiguration顶层store登记）如实列未清，不扩生产修复，不声称全部backend绿。

## 9. 设计自检与交接

- 范围闭合：052/054/055/155各有接口、实现归属、持久/查询动作及AC测试；没有D/交易/支付空壳。
- DAG闭合：唯一重排实现向下依赖；WorkOrderCoordinator没有向ReceptionCoordinator或Service反边。
- 原键闭合：身份权限→完整hash→成功原结果；未成功必须原outer/derived/appointment绑定，未知不建单。
- 数据闭合：COUNT按项目保留、资源份额完整、context及安全草稿可读、执行子行不伪造。
- 事务闭合：所有RPC锁外；prepare原单TX链接；最终root/fence/current/op与全部范围写入同TX；无嵌套REQUIRES_NEW。
- 验证闭合：先业务RED，后受影响GREEN、实际父链消融，报告区分core资源/目录fixture与host真实身份。

控制器已放行后实施；当前准确状态为INCOMPLETE，已取得与未取得的证据及环境收口见task-55c-report.md，不能把原计划清单当完成。

## 10. 验证环境适配（2026-10-23，控制器裁决）

普通reactor、host新消费链及父链有效消融已取得真实结果；新的UNKNOWN参数化三case批次中首个multi-definition用例成功，随后第二schema断链。只针对本UUID的Docker events证明容器在Maven仍运行时die(exit137)/destroy，无kill/oom事件，原因未证实。保留原3tests/1pass/2errors历史，不计业务RED。

按控制器裁决改为每个新UUID只执行一个UNKNOWN窗口case，最多ACCEPT、EXECUTE各一次。将参数化方法拆为replacementAcceptUnknownReusesDurableOperationAfterRestart与replacementExecuteUnknownReusesDurableOperationAfterRestart两个@Test，共用private runReplacementUnknown(String lostResponse)，不复制测试逻辑。每次仍cached mysql8.4/pull-never/UUID/磁盘/64MB/performance_schema OFF/local TCP/readiness/finally只清自己；其余业务容器不动。任一case再环境失败即停止新增容器，未达AC明确留待环境恢复，不标C已完成。

### 10.1 原开单操作读取收口

沿§6真实事实合同新增`BtyReceptionOperation ReceptionOperationMapper.selectOpenedForWorkOrder(String entityNo,String storeNo,String workOrderNo,String operationNo)`：SQL限定MAIN/entity/store/活跃原operation，action=OPEN_WORK_ORDER/phase=SUCCEEDED；JOIN活跃完整context与root，context.sourceReceptionOperationNo=operation.operationNo、operation.workOrderNo=context/root.workOrderNo，canonical identity与context相等、root.customer_ref与context编码相等。正常新工单错原operation父链一律NOT_FOUND，不能借别的operation或硬编码成功。无context的B legacy路径保持UNKNOWN，C仍由根SQL拒绝。

WorkOrderQuery构造器增加真实ReceptionOperationMapper依赖；detail(authorizedRoot)在非legacy路径先从上述SQL读取原operation，WorkOrderVO.operationNo/operationState直接取真实operationNo/phase。对应单case scopedReadRequiresOriginalSucceededOperation先污染原op.workOrderNo与phase取得业务RED，再修复SQL/投影并GREEN。

### 10.2 同原键成功竞态收口

current权限与完整endpoint请求hash保持在成功重放之前。若同key调用已取得OPEN_PENDING后，另一连接先提交成功，后续旧revision/安排分支发生CONFLICT时，只允许在同一完整namespace/hash重读原op且phase=SUCCEEDED返回其原结果；原op未成功或hash不同仍抛原冲突，禁止借异key赢家结果。openingRevision持久更新的锁内分支须先识别已SUCCEEDED，不得将成功原op回退OPEN_PENDING。

真实DB测试successfulConcurrentReplaySkipsOldRevisionAndDoesNotRegressPhase只对ReceptionPreparation.begin加调用真实方法的spy时序闩锁：输家已持久begin拿到原pending对象，赢家通过真实服务提交范围，再恢复输家。断言两者同原结果、phase仍成功且仅一工单/草稿；不替代授权、数据库、owner或业务返回事实。

### Task55C测试容器显式低内存opt-in（控制器裁决，2026-10-23）

只在BEAUTY_TEST_LOW_MEMORY=1且BEAUTY_TEST_DISK=1时启用，普通默认64MB路径参数不变。启动前通过既有测试VM只读/proc/meminfo核MemAvailable≥163840KiB(160MiB)，不足不创建容器；LIMA_HOME/BEAUTY_TEST_LIMA_INSTANCE仅测试显式指定，不改全局配置。

仅自己的UUID容器memory=memory-swap=128m（硬限134217728字节、无swap），innodb_buffer_pool_size16M、innodb_log_buffer_size4M、innodb_redo_log_capacity16M、key_buffer_size1M、max_connections16、thread_cache_size0、table_open_cache64、table_definition_cache400、mysqlx关闭、performance_schema仍OFF；磁盘datadir/事务持久性不变。不调oom_score_adj或优先级，不操作其他容器。不下载或安装，使用缓存mysql8.4/pull-never；实际启动错误或SHOW VARIABLES验证支持，读取自身cgroup peak/events并保存本UUID Docker终态事件。

先仅校准单case scopedReadsRejectDeletedAndForeignParents（parent-full），通过且自身未OOM/有余量后沿相同配置一case/新UUID完成pending/raceRED→修复→GREEN、B与必要host收口。128MiB仍不足或再次globalOOM立即停新容器、交准确pending，不试其它参数，不换H2。代价是更慢IO，但自身资源有硬上界。

校准实际结论：128MiB opt-in在VM可用197316KiB时自身cgroup OOM（UUIDb7298766，容器IDece2a700...，启动3秒Docker oom/die1/destroy），未readiness/SHOW VARIABLES/Maven。按裁决停止新增容器，无其它参数尝试；当前C未验收完成，精确pending与真实统计见task-55c-report.md。两同key竞态生产修复未取得RED、尚未实施，不得把本节设计当实现已绿。

## 11. Fix round1（统一审查 I1–I5，2026-10-23）

修复基线为不可变 index tree b06f773aadb588d65b40ce7dae31853c270e2155；不创建产品 commit，不修改控制器 brief/main TDD/progress/overall/review。当前 VM 资源未恢复（根复核可用约233MiB，低于512MiB恢复门槛），禁止新建真实容器、调整内存参数或改用 H2。I1–I4 必须等待真实 MySQL 业务 RED 后才修改生产代码；现阶段只准备确定调度测试并编译。旧低内存校准失败历史保留，不称已验证可运行。

### 11.1 I1 同原键成功竞争

文件 WorkOrderCoordinator.java：入口仍先当前权限，再以完整 entity/store/endpoint/idempotencyKey/requestHash 找原 operation。begin 返回 SUCCEEDED 时立即返回原 result，不做当前目录/scope 的后续比较。对从最初 absence 或旧 pending 出发、后续根 revision/安排/准备事实已因同原键赢家成功而过期的窗口，只能重读同完整 namespace/key/hash 且同一 outerOperationNo 的 SUCCEEDED；找不到完全匹配成功仍抛原异常。禁止借用异 key、异 endpoint、异 hash 或其他 outer 的结果，禁止广 catch 绕过 owner/权限失败。未安排 begin 若返回成功同样先回原冻结结果。

真实 DB 测试：保留 successfulConcurrentReplaySkipsOldRevisionAndDoesNotRegressPhase 的真实 begin 后 pending 窗口；新增 successfulReplaySurvivesInitialFindAbsence 控制实际 find 返回 absence 后赢家提交；successfulReplayReturnsAlreadySucceededBegin 控制 begin 调用前赢家成功；unassignedReplayIgnoresChangedPreparationAfterWinnerSucceeds 控制准备期间赢家成功并发布新定义。spy 只暂停真实调用前后，不替代 SQL/owner/业务返回。每个原键都断言同一 result、单份范围/草稿及 SUCCEEDED；原有改参/撤权/异 key 冲突回归保持。

### 11.2 I2 成功终态不可回退

文件 WorkOrderCoordinator.java openingRevision 短事务按 root→fence→current→outer 锁序。锁内先识别同 outer 的 SUCCEEDED 并返回其原 result，成功 phase/result/frozenPayload 均不可修改。首次成功 REPLACE 续行允许 RESOURCE_PENDING→OPEN_PENDING：必须先核同完整 namespace/key/hash 的原 outer、已链接 appointmentOperationNo、成功 receipt 的完整 binding 及 sourceRevision=原 expectedRevision，openingRevision 仅取该已核 sourceRevision+1。已 OPEN_PENDING 的恢复须幂等核对同一完整 binding 与已保存的相同 openingRevision，不覆盖成其他值；其余未成功前态拒绝。不以当前根版本猜 openingRevision。保留 successfulReplacementReplayNeverRegressesSucceededPhase，真实 advance 成功后暂停输家，赢家开单后恢复；除单份结果外核最终原 phase/result/payload 不退且 B/C 详情仍可读。此生产修复仍待真实 RED。

### 11.3 I3 预约 GUEST 当前 owner

文件 WorkOrderCoordinator.java、必要的 WorkOrderPreparation.java/装配：成功原键返回之后、新 intent 之前，052 与054省略身份字段也必须在锁外调用已有 ReceptionIdentity.guest(entity,store,guestNo) 的真实 owner.get，核当前完整 scope，并与预约原身份相交。REGISTERED/revision1/userNo为空且 guestNo 与请求相等的完整 owner fact 保持原 GUEST；CLAIMED/revision2/userNo非空非blank且 guestNo 相等的完整 owner fact 规范 USER 仅在同 scope 不可变 claim receipt 对原 GUEST→当前 USER 的桥接成立时允许，复用已有 CustomerOwnership/receipt 查询，不自动认领、不用 root 字符串充证明。最终 prepared/context/operation 用相同规范身份；显式客户字段也必须相交。成功原 key 仍只先当前权限与完整 hash，不重验已过期原 proof。

host BeautyReceptionIdentityIT 新增直接052及054省略身份字段边界：真实 REGISTERED/revision1/userNo为空的完整 owner fact 成功、owner scope 错误拒、CLAIMED/revision2/userNo非空非blank的完整 owner fact 有/无同 scope receipt；断言新动作确实调用真实 owner，新失败无范围/草稿。core 测试只受控 owner，不冒充真实部署；资源恢复后先真实业务 RED。

### 11.4 I4 已有实际 task 防线

文件 WorkOrderCoordinator.java 最终同 root/fence/current/outer 短事务，在写新范围前复用 AppointmentMapper.countWorkOrders(entity,store,appointmentNo)（已有 workOrder 或通过 appointment_item 归属的实际 task），普通无 REPLACE 分支同样确定冲突。保留 markWorkOrderOpened 的历史软删 CAS 和现有唯一键，不加 schema。appointmentWithOrphanActualTaskRejectsOpening 插入真实原预约 item 关联的 task、其 workOrder 缺失，确认合法安排仍在；052/054均拒且无新范围、草稿或 owner 变更调用。先实际业务 RED 再生产 fix。

### 11.5 I5 runner 启动纳入 finally

文件 scripts/verify-beauty-persistence.mjs：Lima只读前置检查仍在创建前；docker run 与 readiness/Maven 全部纳入同一 try/catch/finally。记录原始启动/主体异常，finally 无论 run 成败都尝试 docker rm --force --volumes <本 UUID>；诊断仅本 UUID 且失败不可绕过清理，清理失败不能覆盖原异常；没有原异常时清理失败仍让 runner 失败。保留默认64MB参数/低内存显式opt-in参数，不称失败opt-in运行有效。

新增 scripts/tests/beauty-persistence-runner.test.mjs 使用真实 node 子进程启动原 runner，PATH 下仅临时 fake docker/limactl/mvn。fake run 先落盘模拟 daemon 已创建，再报错或实际等待超过 runner 10秒 timeout；fake rm 真正删除该自身 UUID 状态，另一个模拟 foreign container 状态必须原样保留。先在旧 runner 取得 cleanup 缺失断言 RED，再修复并 GREEN；覆盖 low/default run错误、真实客户端 timeout、清理与诊断错误不掩盖原始错误，以及无主错误时清理错误必须失败。整个测试不启动 Docker/Lima/Maven，不读写任何真实容器。

M1 仅在 report 分类预期 negative context、Mockito/JDK agent/Flyway8.4依赖提示、host重复mapper/H2夹具/测试OTP提示，不修改生产日志。I5完成后精确stage owned，生成新index tree及相对 b06 的fixdiff，冻结写入交根审查；I1–I4继续waitingENV，不能标 C 完成。

### 11.6 Fix round2：仅方案文档纠正（2026-10-23）

完整读取 /tmp/review-bm04-55c-r1-i5.md，I5 scoped Approved/ADDRESSED。R1-N1按实际 AppointmentPreparationService 的 outer链接写者（写RESOURCE_PENDING）纠正§11.2，保留SUCCEEDED优先和终态不可回退；R1-N2按真实MemberGuestRegistrationServiceImpl的get/注册/claim及ReceptionIdentity.validFact纠正§11.3为REGISTERED/revision1/user为空与CLAIMED/revision2/user非空合同，CLAIMED仍须同scope不可变receipt桥接。本轮仅文档，不修改Java、runner或测试；无新产品/测试证据，I1–I4继续OPEN/waitingENV，C仍INCOMPLETE。

### 11.7 用户指定本机 MySQL9.7 基线重试（2026-10-23）

用户最新明确“统一用最新的mysql9.7，现在在本机再尝试一次”。本次将 scripts/verify-beauty-persistence.mjs 镜像改为项目现行、本机已缓存的 mysql:9.7.2，保持 --pull never，不推测补丁、不下载。此前8.4的真实RED/GREEN、失败校准和环境诊断属于历史证据，完整保留，不改写成9.7结果；后续本runner验证基线采用9.7.2。仅此runner及design/report三文件，不扩展为整个项目迁移或远程尝试。

按最新授权仅本机一次 LOW_MEMORY=1/DISK=1、单case scopedReadsRejectDeletedAndForeignParents，仍先通过既有测试VM MemAvailable≥160MiB gate，保持本UUID容器memory=memory-swap=128m及其余既定参数，不降低门槛/调参。根刚读可用31820KiB/Swap0；若gate拒绝，准确记录未启动MySQL/未进入Maven，不计业务RED/GREEN。日志 /tmp/task55c-mysql97-local-retry.log。I1–I4不修改Java，仍待真实业务RED；本次后仅复验原6个fakeCLI子进程测试，不加镜像字符串测试。

### 11.8 Fix round3：环境恢复后的真实修复与验证（2026-10-23）

用户授权本机清理后，根确认测试VM可用3653792KiB且0 running。解除此前waitingENV；本轮统一cached mysql:9.7.2，普通DISK=1/64MB路径，显式不启用LOW_MEMORY，放弃失败128MiB校准。先运行pending父链单case确认native环境，再按§11.1–11.4逐项真实DB业务RED→最小fix→GREEN与必要B/host/wiring回归。不改历史8.4证据、V1–V7或进入D。

scripts/verify-member-guest-registration.mjs唯一镜像同改9.7.2供真实host I3及最终身份新消费链验证，新增为本轮owned；其他项目脚本不扩改。单一UUID容器串行、pull never、localhost/readiness/finally只清自身。I5已闭环保持不回退，先前9.7低内存gate拒绝和fake6test结果仅按实记录。

#### Round3 I3 身份续行精确归属

新intent在成功原键fast path之后、begin之前锁外调用真实owner.get。REGISTERED保持GUEST；CLAIMED归一USER须复用CustomerOwnership.owns(originalAppointment,user)的同scope不可变receipt。只对本次prepared的identity归一，不修改原预约customer_ref/guest_ref或其冻结安排。WorkOrderCoordinator构造器显式注入既有CustomerOwnership，配置同一强构造依赖，不新造receipt算法。

已有pending outer的canonical identity、原scopeHash和payload均为原已验证事实；后续guest claim不重新查询owner/认领/生成新identity，resume只将准备的原冻结范围与原outer canonical结合。成功原键仍当前权限→完整hash→冻结result，不重验过期proof。新增host omittedGuestIdentityUsesCurrentOwnerAndScopedReceipt(false/true)用真实memberget/claim与本域receipt覆盖052/省略身份054、REGISTERED成功、CLAIMED有/无/错scope receipt、错owner scope、原成功key不再get、原预约GUEST不被重写。core pendingGuestIntentRetainsCanonicalIdentityAfterClaim以真实Outbox回滚保留pending，当前owner变化后恢复仍保原GUEST/原scopeHash，不伪称真实owner部署。

#### Round3 实施收口与精确签名

I1已落地 private completedOrThrow(entity,store,actor,endpoint,key,hash,original,BizException)：仅CONFLICT进入成功重读，operations.find仍完整namespace/hash，且已知original必须sameOperation全部tenant/entity/store/actor realm-kind/endpoint/key/hash/operationNo相同、SUCCEEDED才返回原result；其他错误原样抛。初次预约revision/冻结准备边界和已验证identity后的resume分开保护，显式identity owner校验不在catch内；begin返回成功立即回原result。ReceptionPreparation.begin新对象显式设置当前tenantNo，避免DB默认MAIN而本地对象null导致严格绑定误拒，不放宽比较。openPrepared锁内也以同sameOperation核原行。

I2已落地 resumeAppointment(...) 的openingRevision事务：同root/fence/current/outer锁序，先sameOperation及SUCCEEDED返回原result；未成功核原appointmentOperationNo与成功receipt/sourceRevision，RESOURCE_PENDING原payload相等才推进；OPEN_PENDING仅同opening payload幂等恢复。phase/result/payload成功后均不回写。I4复用countWorkOrders放在verifyAppointmentLocked同短TX，在markWorkOrderOpened之前阻断实际task；原历史软删CAS/唯一键未动。

本轮9.7默认路径验证的实际统计和完整RED/GREEN顺序见report末尾round3；新增Java私有辅助签名与边界同步方法JSON及两份生成清单。独立审查尚待根安排，无C产品commit、不进入D。
