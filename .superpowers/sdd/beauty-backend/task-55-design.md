# Task55 BM04 接待、临客引用与工单范围设计

状态：DESIGN_READY，已纳入控制器本轮裁决，待正式冻结；仅设计，未写产品代码、未执行 SQL/测试。

**绑定规格：** `.superpowers/sdd/beauty-backend/task-55-brief.md`（包含控制器 2026-10-22 原登记 actor 恢复裁决）。Task54 已独立复审通过，控制器文档提交 `04f0df2b0`。正式产品实现 BASE 由控制器在设计冻结后记录。本报告不修改主 TDD、Task54 brief、ADR、progress，也不把它们计为实现文件。

**工作流：** 按 brainstorming/writing-plans 完成架构核对与自审。主 TDD 是正式规格/计划真源，本文件是供控制器归并的 Task55 精确设计附件；下列四个子步骤建议逐个 RED→GREEN→独立 spec/quality review，不新建子代理。公开 Controller/wire 归59，owner HTTP/容量与部署验收归60，交易来源/金额/支付归56–58。

**审查修复增量：** task-55a-fix-design.md及主TDD当前方案优先：新接待claim/证明使用共享严格resolveClaimReference，旧resolveReference只保留兼容；V5四表仍冻结，追加V6身份NULL完整性guard，当前manifest/ready=6、27私表。原V5结构描述是首次基础迁移，不代表已修复的最新校验版本；后续55B–D按V6基线继续，不回改旧迁移。

## 1. 已核实的代码与设计选择

- `beauty-api/.../service/AppointmentService.java` 已实现十五个预约方法，尚无052方法；ReceptionService、GuestRegistrationService、WorkOrderService 尚不存在，不能把方法 JSON 当已实现代码。
- `AppointmentPersistence.tx` 是本数据源 `REQUIRES_NEW + READ_COMMITTED`，用 `DataScopeContext.executeWithoutScope`；不能在它里面再次调用另一个会开事务的 preparation/coordinator 公共方法并声称原子。
- `AppointmentPreparationService.save/prepare` 当前以原 preview actor 检查；生成本域 BTG 匿名号，资源 acceptPayload 先落库。`AppointmentCoordinator.advance/recover` 已支持原命令恢复，所有 owner IO 在锁外；recover 只对已有 receipt 读取终态，不能发新授权。`current.operationNo` 与永久 fence 同时保护根。
- 现有 `AppointmentMapper.markArrived(...)` 有 CONFIRMED/CAS/无 active fence 条件，但尚不检查 current 操作指针、安排不变、hold 事实；这些必须由短事务重核并补 SQL 防线。缺 fence 或 current 的 legacy 根不具备安全执行条件。
- `AppointmentMatching` 把 qty 展开为每行 qty=1，bounded `Slot` 搜索上限10,000、bucket物化上限100,000，最多查询50资源。`Snapshot` 只含 items/assignments/priceMinor/cancelRule，没有 buffer 或完整 requirement。原 version 表和 requirement 是不可变事实；P1 发布固定 `[{"stepCode":"SERVICE","sequenceNo":0}]`。
- 原 `bty_work_order` 已有 `(tenant_no,entity_no,appointment_no)` 唯一键；appointmentNo 为NULL允许多个未预约工单。`customer_ref` 是非空 VARCHAR(64)，采用 `USER:<userNo>` / `GUEST:<guestNo>`，不改V1。
- Task54 `GuestRegistrationPort` 的 register/get/verify/claim 和 `CustomerIdentityPort` 的 resolveReference/verifyConsumerProof 都有实际本地及远程实现。`get` 只返回同店 guest/state/user/revision，要求当前 CUSTOMER。claim 原键重放还比对原 actor 命名空间、原 claimToken/verifiedIdentity 的摘要。
- 原登记成功但丢失 guestNo 时，Port 没有“按原登记键只读查询”能力。按已裁决规则：031直接委托owner，不新增本域登记operation或联系方式摘要；未知返回 PENDING、operationNo=null、`recoveryAction=REQUIRES_ORIGINAL_ACTOR`，仅原登记人携原请求重放。不扩张 Task54，不把第二员工伪装为旧 actor，不声称generic operation能查询这个未知窗口。
- 现有 `ServiceOperationQueryService.getOperation` / `ServiceOperationRecoveryService.recoverOperation` 仅支持预约操作。扩展同域分派接待 operationNo 是恢复所需的最小合同补充，保留原签名与旧行为；详见§8。

选择：复用预约 preparation/coordinator/fence，加同域接待与工单辅助。拒绝复制第二套资源 saga；拒绝绕过 owner 直接写 assignment；不在本任务扩大共享顾客安全合同。共享表继续归 member/resource owner。

## 2. 公共 Service 合同（原方法 JSON 归属保持）

所有接口位于 `beauty-api/src/main/java/ai/neargo/shop/beauty/service`，实现位于 `beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl`，接口与实现同名加Impl。以下省略标准 Java import，参数名保持 JSON。

```java
// GuestRegistrationService — 031/032
GuestRegistrationVO registerGuest(String entityNo, String currentStoreNo, String actorNo,
        RegisterGuestReq req, String idempotencyKey);
GuestClaimVO claimGuest(String entityNo, String currentStoreNo, String actorNo,
        String guestNo, ClaimGuestReq req, String idempotencyKey);

// ReceptionService — 047/048/053/154
ArrivalVO checkIn(String entityNo, String currentStoreNo, String actorNo,
        CheckInReq req, String idempotencyKey);
WalkInVO receiveWalkIn(String entityNo, String currentStoreNo, String actorNo,
        ReceiveWalkInReq req, String idempotencyKey);
ReceptionExceptionVO coordinateReceptionException(String entityNo, String currentStoreNo, String actorNo,
        ReceptionExceptionReq req, String idempotencyKey);
MyArrivalVO checkInMyAppointment(String userNo, MyCheckInReq req, String idempotencyKey);
// 原160条目标外补充，59再接POST /biz/x/beauty/arrival-challenges
ArrivalChallengeVO issueArrivalChallenge(String entityNo, String currentStoreNo, String actorNo,
        String idempotencyKey);

// AppointmentService — 052 additive
WorkOrderVO openWorkOrderFromAppointment(String entityNo, String currentStoreNo, String actorNo,
        String appointmentNo, OpenAppointmentWorkOrderReq req, String idempotencyKey);

// WorkOrderService — 054/055/155
WorkOrderVO openWorkOrder(String entityNo, String currentStoreNo, String actorNo,
        OpenWorkOrderReq req, String idempotencyKey);
WorkOrderDetailVO getWorkOrder(String entityNo, String currentStoreNo, String actorNo, String workOrderNo);
MyWorkOrderVO getMyWorkOrder(String userNo, String workOrderNo);
```

不得为049–051、065–067、056/071生成空方法。新增 AppointmentService 方法影响现有 `beauty-channel/.../client/RemoteAppointmentService.java` 实现：55采用接口 default 明确抛 `UnsupportedOperationException("reception wire is owned by Task59")`，本地Impl必须覆盖；59连线后去掉 default。该 default 不计远程能力、不返回成功，控制器已采纳；55不补远程协议，不把default计为方法实现交付。

## 3. DTO精确字段与兼容

全部位于 `beauty-api/.../ai/neargo/shop/beauty/dto`。日期对外 `OffsetDateTime`（统一UTC），内部 `LocalDateTime UTC`；业务号≤32、actor≤64、票据≤256、原key≤128，proof类以及含contact/claimToken的Req/VO覆盖toString为REDACTED。列表防御复制、拒null元素，禁Map代替公开合同。

| DTO | record字段，按顺序 | 校验与语义 |
|---|---|---|
| RegisterGuestReq | `String contact, String displayName, boolean consent` | 与owner一致：contact≤128/displayName≤80，可null；consent必须true |
| ClaimGuestReq | `String verifiedIdentity, String claimToken` | cr1与真实43字符guest令牌；不接受自报userNo |
| ReceptionIdentityProof | `String customerRef, String guestNo, String claimToken` | USER路径仅customerRef；GUEST路径guestNo+claimToken；二者互斥。GUEST已认领且本域receipt存在时允许customerRef核对映射USER |
| CheckInReq | `String appointmentNo, ReceptionIdentityProof identityProof, Long expectedRevision` | revision必填且≥0；proof必填 |
| CheckInProof | `String memberCode, String arrivalChallenge` | 两项必填；mc1通过真实owner验证；门店码43字符 |
| MyCheckInReq | `String appointmentNo, CheckInProof checkInProof, Long expectedRevision` | C严格CONSUMER/USR本人 |
| ReceiveWalkInReq | `String storeNo, String customerRef, String guestNo, List<AppointmentItemReq> items, List<AppointmentAssignmentReq> assignments, OffsetDateTime startAt` | store匹配trusted store；customerRef/guestNo恰一；startAt可null；qty展开总量≤10 |
| OpenAppointmentWorkOrderReq | `Long expectedRevision, List<AppointmentAssignmentReq> assignments, OffsetDateTime startAt` | expected必填；保留两参数构造器委托startAt=null；无assignments表示确认现安排，非清空；startAt仅接待REPLACE用 |
| OpenWorkOrderReq | `String customerRef, String storeNo, List<AppointmentItemReq> items, String appointmentNo, String guestNo, Long expectedRevision, List<AppointmentAssignmentReq> assignments, OffsetDateTime startAt` | 保留四参数构造器。appointmentNo非空走052语义、expected必填，items空或与完整冻结范围严格一致且不允许覆盖；客户输入可省略，提供则核同一客户。appointmentNo为空时customerRef/guestNo恰一、items必填，拒非空assignments/startAt以免假占资源 |
| ReceptionExceptionReq | `String appointmentNo, String kind, String proposal, String reason, Long expectedRevision` | kind与proposal严格brief枚举；reason非空≤500；revision必填 |
| CustomerIdentityVO | `String kind, String reference` | USER/GUEST/UNKNOWN；只给规范号，不给票据；C视图不回显他人标识 |
| GuestRegistrationVO | `String operationNo, String guestNo, String claimToken, String state, Long revision, String operationState, String recoveryAction` | operationNo始终null；成功token只从owner响应临时透传；UNKNOWN返回PENDING无guest/token，recoveryAction=REQUIRES_ORIGINAL_ACTOR；无本域登记operation/result/log/outbox |
| GuestClaimVO | `String operationNo, String guestNo, String userNo, Long claimRevision, String operationState, String recoveryAction` | PENDING时userNo仅已核验当前目标的规范号；成功来自receipt |
| ArrivalChallengeVO | `String challengeNo, String storeNo, String code, OffsetDateTime issuedAt, OffsetDateTime expiresAt` | 32字节SecureRandom→base64url无padding；TTL固定90秒，无可调TTL |
| ArrivalVO / MyArrivalVO | `String appointmentNo, long revision, String status, OffsetDateTime checkedInAt, String timing, OffsetDateTime plannedStartAt, OffsetDateTime plannedEndAt, String operationNo, String operationState` | timing=EARLY/ON_TIME/LATE；无工单/支付字段 |
| WorkOrderVO | `String workOrderNo, String appointmentNo, long scopeRevision, String status, String submissionNo, String sourceState, String operationNo, String operationState, List<String> taskNos, OffsetDateTime plannedStartAt` | 初始sourceState=UNBOUND；资源待决时workOrderNo/submissionNo空、taskNos空、scopeRevision=0/status=PENDING；不能伪返回OPEN |
| WalkInVO | `String operationNo, String operationState, String appointmentNo, WorkOrderVO workOrder, OffsetDateTime startAt, String recoveryAction` | startAt回显实际选定网格；PENDING时workOrder=null |
| ReceptionExceptionVO | `String requestNo, String appointmentNo, String state, List<String> affectedTaskNos, String operationNo` | requestNo=本次actionNo；state=PENDING_REVIEW；不声称待办owner已建单或已投递 |
| WorkOrderDetailVO | `WorkOrderVO workOrder, CustomerIdentityVO customer, long originalScopeRevision, List<WorkOrderTaskVO> tasks, List<String> availableActions` | originalScopeRevision来自context；legacy未知为0且动作空 |
| MyWorkOrderVO | `WorkOrderVO workOrder, long originalScopeRevision, List<WorkOrderTaskVO> tasks, List<String> availableActions` | 先WHERE授权，返回本人视图 |
| WorkOrderTaskVO | `String taskNo, String appointmentItemNo, long scopeRevision, String skuNo, String definitionVersionNo, BigDecimal qty, BigDecimal doneQty, BigDecimal voidedQty, String uom, String status, long bindingRevision, String assignmentState, String sourceState, boolean canStart, OffsetDateTime plannedStartAt, OffsetDateTime plannedEndAt, OffsetDateTime actualStartedAt, List<AppointmentAssignmentVO> assignments, WorkOrderTaskSnapshotVO taskSnapshot, List<WorkOrderStepVO> steps, List<WorkOrderParticipationVO> participations, List<String> availableActions` | 初始PENDING/0/UNBOUND/false/null；未安排任务assignmentState=UNASSIGNED；实际子行不存在返回[] |
| WorkOrderTaskSnapshotVO | `int schemaVersion, String goodsNo, String skuNo, String definitionVersionNo, String definitionHash, int durationMin, int bufferBeforeMin, int bufferAfterMin, List<WorkOrderRequirementVO> requirements, List<WorkOrderStepTemplateVO> stepsTemplate, List<String> notes` | schemaVersion=1；冻结定义/规则原值，无价格/付款结论 |
| WorkOrderRequirementVO | `String requirementNo, String roleCode, String resourceType, String skillGoodsNo, int qty, int sequenceNo, int offsetMin, int durationMin, String selectionRule` | 原definitionVersion的全部要求 |
| WorkOrderStepTemplateVO | `String stepCode, int sequenceNo` | P1仅SERVICE/0 |
| WorkOrderStepVO | `String stepNo, String stepCode, int sequenceNo, String status, OffsetDateTime startedAt, OffsetDateTime finishedAt` | 仅投影真实本域step行，55不创建步骤执行事实 |
| WorkOrderParticipationVO | `String participationNo, String personnelNo, String roleCode, OffsetDateTime startedAt, OffsetDateTime endedAt` | 仅投影真实本域参与行，账号不推技师 |

**预约DTO additive：** `PreviewAppointmentReq` 在现有5字段末尾加 `String guestNo`，保留原5参数构造器；`CreateAppointmentReq` 在现有4字段末尾加 `String guestNo`，保留原4参数构造器。customerRef与guestNo至多一；两者皆空保持旧BTG。显式guestNo用owner.get当前事实；REGISTERED写真实guestRef，CLAIMED写规范customerRef(USER)，禁止回填BTG。create需与preview规范身份一致；preview期间发生claim导致身份变化，返回冲突并要求新preview，不能静默换人。C预约构造器及现有wire字段保持。

`OperationVO` 在末尾追加 `String workOrderNo, String failureCode` 并保留原五参数构造器，以使接待原号查询/恢复能拿到成功工单或安全恢复原因；其原五字段语义不变。控制器已采纳该增量；031原登记未知窗口不在generic operation分派范围。

## 4. V5 四表与准确约束

新增真源 `docs/technical/design/schema/beauty-reception-schema.json`；生成器 `scripts/gen-beauty-reception-schema.mjs`；迁移 `beauty-core/src/main/resources/db/industry/beauty/V5__beauty_reception_work_order.sql`。不改V1–V4，不ALTER旧表。字符编码/业务号列沿utf8mb4_bin，表ENGINE=InnoDB收尾单行。

共通列沿BaseEntity：id BIGINT auto increment PK；tenant_no VARCHAR(32) NOT NULL DEFAULT MAIN；entity_no VARCHAR(32) NOT NULL；created_at/updated_at DATETIME(3) NOT NULL；created_by/updated_by VARCHAR(64) NULL；version BIGINT NOT NULL DEFAULT0且CHECK>=0；deleted INT NOT NULL DEFAULT0且CHECK=0（四表均禁止删除覆盖）；store_no VARCHAR(32) NOT NULL。SQL中所有唯一键均保留tenant/entity/store语义；没有跨域FK。

### 4.1 bty_reception_operation / BtyReceptionOperation

| 列 | 类型/可空 |
|---|---|
| operation_no | VARCHAR(32) NOT NULL，前缀BTRO |
| actor_no / actor_realm / actor_kind | VARCHAR(64)/VARCHAR(32)/VARCHAR(32) NOT NULL |
| endpoint / action | VARCHAR(64)/VARCHAR(32) NOT NULL |
| key_hash / request_hash | CHAR(64) NOT NULL |
| appointment_no / work_order_no | VARCHAR(32) NULL |
| identity_kind / identity_ref | VARCHAR(16)/VARCHAR(32) NULL；规范USER/GUEST；无需客户的异常登记可同时NULL |
| phase | VARCHAR(24) NOT NULL |
| appointment_operation_no / derived_key_hash | VARCHAR(32)/CHAR(64) NULL |
| frozen_payload | JSON NOT NULL，schemaVersion=1的按action判别内部record |
| result | JSON NULL，仅安全VO/引用，无cr1/mc1/claimToken/contact |
| next_retry_at / lease_until | DATETIME(3) NULL |
| failure_code | VARCHAR(64) NULL，稳定安全码不存异常message |

唯一 `(tenant_no,entity_no,operation_no)`；幂等唯一 `(tenant_no,entity_no,store_no,actor_realm,actor_kind,actor_no,endpoint,key_hash)`；重试索引 `(tenant_no,phase,next_retry_at,lease_until,id)`；按关联预约/工单的scope索引。CHECK phase IN RECEIVED/RESOURCE_PENDING/ARRIVAL_PENDING/OPEN_PENDING/SUCCEEDED/REJECTED；identity两列同空或kind IN USER/GUEST且ref非空。REJECTED只能表示确定无成功副作用或确定owner拒绝，网络UNKNOWN保持原phase及原号。

frozenPayload具体内部records位于 `ReceptionPreparation`（不含REGISTER；031不创建本域operation）：`ClaimPayload(schemaVersion,guestNo,targetUserNo)`；`ArrivalPayload(schemaVersion,appointmentNo,expectedRevision,identity,observedArrangementRevision,holdEvidence,challengeNo)`；`OpenPayload(schemaVersion,appointmentNo,expectedRevision,identity,assignments,startAt,preparedScope)`；`WalkInPayload(schemaVersion,identity,items,assignments,requestedStartAt,selectedStartAt,previewNo,preparedScope)`；`ExceptionPayload(schemaVersion,appointmentNo,expectedRevision,kind,proposal)`。proof只参与临时核验/requestHash，不写payload。reason只进本域ActionLog.reason。持久化typed JSON，不把请求全体序列化。

### 4.2 bty_work_order_context / BtyWorkOrderContext

`work_order_no VARCHAR(32) NOT NULL`；`identity_kind VARCHAR(16) NOT NULL`；`identity_ref VARCHAR(32) NOT NULL`；`source_reception_operation_no VARCHAR(32) NOT NULL`；`appointment_arrangement_revision BIGINT NULL`；`frozen_scope JSON NOT NULL`（version1、originalScopeRevision1、policyVersionNo可空、原policy规则可空、逐任务原项目号/完整definition snapshot/安排/hold引用）。唯一 `(tenant_no,entity_no,work_order_no)`，索引 `(tenant_no,entity_no,store_no,identity_kind,identity_ref)` 与sourceReceptionOperationNo；CHECK kind IN USER/GUEST；arrangement revision为NULL或>0。按workOrder根校验store/tenant/entity父链；来源operation和workOrder同事务创建，不以软删释放唯一号。

原workOrder.customer_ref严格编码 `USER:<id>` 或 `GUEST:<id>`，以context为授权真源，两者必须一致。缺context的legacy单只允许B当前同店只读、customer=UNKNOWN、安排/来源未知、动作空；C不凭旧字符串获得归属。Task58消费前必须resolve到可信USER，不能将GUEST当buyer。

### 4.3 bty_guest_claim_receipt / BtyGuestClaimReceipt

`guest_no VARCHAR(32) NOT NULL`；`claim_revision BIGINT NOT NULL`；`user_no VARCHAR(32) NOT NULL`；`owner_receipt_hash CHAR(64) NOT NULL`；`recorded_at DATETIME(3) NOT NULL`。唯一 `(tenant_no,entity_no,store_no,guest_no)`；本人查询索引 `(tenant_no,entity_no,store_no,user_no,guest_no)`；CHECK claim_revision>0。摘要由可信owner响应的tenant/entity/store/guestNo/CLAIMED/userNo/revision计算，不是自行签发的身份凭证。行不可update改绑：重复完全一致返回既有行，revision/user/hash异值冲突。无联系方式/原令牌。

### 4.4 bty_arrival_challenge / BtyArrivalChallenge

`challenge_no VARCHAR(32) NOT NULL`；`code VARCHAR(43) NOT NULL`；`issued_at/expires_at DATETIME(3) NOT NULL`；`actor_no/actor_realm/actor_kind`类型同operation；`key_hash CHAR(64) NOT NULL`。唯一 `(tenant_no,entity_no,challenge_no)`、`(tenant_no,code)`、`(tenant_no,entity_no,store_no,actor_realm,actor_kind,actor_no,key_hash)`；索引 `(tenant_no,entity_no,store_no,expires_at)`；CHECK expires_at>issued_at、CHAR_LENGTH(code)=43。它是门店可展示公开码，原文只存此表和即时VO，不入操作result/log/outbox。C验证通过预约根定位同店码，expiresAt必须晚于事务内serverNow；同键返回原码与原到期，不延长90秒。随机碰撞重试生成不改变幂等行。

### 4.5 Mapper与schema接线

`BeautyMappers`增加四个BaseMapper嵌套接口，名称对应Entity去Bty；custom方法：

- `ReceptionOperationMapper.selectScopedForUpdate(entityNo,storeNo,operationNo)`、`claim(entityNo,storeNo,operationNo,expectedVersion,now,until)`，claim只改lease/version，不授权；lookup原键使用完整actor命名空间+endpoint；不存在返回null由Service明确处理。
- `WorkOrderContextMapper.selectScoped(entityNo,storeNo,workOrderNo)` JOIN活跃workOrder根，不能单context号越父链。
- `GuestClaimReceiptMapper.selectScoped(entityNo,storeNo,guestNo)`，所有授权exists附tenant/entity/store/deleted；只insert，不提供改绑方法。
- `ArrivalChallengeMapper.selectScoped(entityNo,storeNo,code)`；store/entity来自已核实预约，不接受C自报。
- AppointmentMapper补 `markArrived` 的current无操作 EXISTS 防线；增加 `markWorkOrderOpened(entityNo,storeNo,appointmentNo,expectedVersion,actorNo,updatedAt)`，状态仍CHECKED_IN且version+1，无current op/active fence，checkedInAt非空；其作用仅推进转换版本，不伪造履约状态。
- 工单/任务/step/participation scoped查询强制根父链；C分页查询在SQL里OR USER/receipt，不能先分页再Java过滤。

BeautyDataScopeRegistration增四表。BeautySchemaValidator manifest严格23+4=27私表、版本5、V5 SHA256；BeautySchemaReady、BeautyPersistenceConfiguration、BeautyWriteGuardConfiguration硬编码版本一并更新；迁移测试总表28（27+bty_flyway_history）。生成器数据库字典/方法清单追加V5增量，不把原目标41表/160接口改称已交付。严格漂移检查包含CHECK/列/唯一键，不放宽解析器。

## 5. 权限、规范身份与持久幂等

### 5.1 实际权限

复用 `AppointmentAccess.current/merchant/consumer` 验证实时SecurityUtils身份；新增 `ReceptionAccess`（`beauty-core/.../policy`）组合它与ObjectProvider<MerchantOperatorAccessPort>检查CUSTOMER，不重新实现认证。

- 031/032与任何guest owner get/verify：当前同店CUSTOMER。
- 047/048/052/054及接待恢复新动作：当前CUSTOMER+BOOKING_MANAGE；issueArrivalChallenge和053仅BOOKING_MANAGE（异常可用于缺身份者）。055/operation只读BOOKING_VIEW或MANAGE；操作不返回contact/proof。
- 154/155：当前CONSUMER/USR，userNo等于SecurityUtils；不接受MCH或其他realm借同字面userNo。
- 原键成功重放先检查当前身份与当前权限，然后读取原结果。仅成功结果重读无需让过期原cr1/mc1重新有效；未验证的新动作不能用旧缓存逃过权限撤销。

`ReceptionIdentity`（core policy helper）只返回 `VerifiedIdentity(kind,reference)` 和必要不可变receipt证据：B USER用CustomerIdentityPort.resolveClaimReference(EntitlementContext,ReferenceQuery)且owner结论必须等于预约规范USER；C用verifyConsumerProof(context,memberCode)且结果等于当前user和授权根。B GUEST用GuestRegistrationPort.verify(scope,guestNo,token)；guestNo须等于预约正式guestRef。未claim维持GUEST；已claim可与本域receipt映射USER核对。receipt缺失不自动授权C，必须显式032核验/补齐。

WalkIn/无预约open的guestNo由当前CUSTOMER调用owner.get核实同店实际guest；REGISTERED得到GUEST，CLAIMED得到USER。此授权是员工登记接待，不宣称该guest已提供C本人凭证。旧BTG未在owner存在时明确BEAUTY_IDENTITY_REQUIRED，不猜号、不迁移假guest。

### 5.2 幂等与恢复次序

`keyHash=IdempotencyScope.requestHash(requireKey(rawKey))`。endpoint固定BAPI-031等或新增ARRIVAL_CHALLENGE；032/047/048/052/053/054/154的requestHash=长度前缀的schema/endpoint/作用域/标准化业务字段/原凭证的临时摘要，拒同键改参。031登记不计算/保存contact或displayName的本域摘要：低熵输入的幂等冲突判定完全由Task54 owner的既有HMAC承担。数字/列表顺序保持业务语义；null显式编码，不能直接传null给requestHash。

`derivedKeyHash=requestHash("BEAUTY_RECEPTION_V1",tenant,entity,store,actorRealm,actorKind,actorNo,endpoint,keyHash,purpose)`；owner用该64字符稳定值，不存rawKey。已派生后冻结，不随重试者变化。submission.requestKeyHash同样包含原完整命名空间+endpoint+keyHash+"OPEN_DRAFT"，适配旧 `(tenant,entity,store,action,request_key_hash)`唯一索引。

除031直接owner委托外，每次新操作：权限/证明锁外→得到规范身份→短事务insert RECEIVED及无凭证payload/派生号→后续外调。读取成功重放优先于expectedRevision检查，使052原键在创建推进版本后仍返回原工单。重复insert靠唯一键取胜者并比requestHash；不能捕获所有异常当成功。

operation行记录原发起actor永不改写；审计updatedBy/ActionLog.operatorRef记录当前真实操作者。第二员工通过可信operationNo恢复，不拿原员工raw key在自己的命名空间冒充重放；仅允许同tenant/entity/store当前获权者。lease只是并发工作分配，不是权限或fence TTL。

### 5.3 登记、认领的精确窗口

1. 031由GuestRegistrationServiceImpl检查当前CUSTOMER，计算仅含tenant/entity/store/当前完整actor命名空间/endpoint/原key的稳定派生key，直接调用真实GuestRegistrationPort.register。不创建BtyReceptionOperation，不保存contact/displayName或它们的无密钥摘要；共享owner已有加密联系方式、HMAC requestFingerprint、完整actor namespace幂等，原样承担同键变参冲突和令牌重放。成功GuestRegistrationVO.operationNo=null，guestNo/token/state/revision来自owner即时响应；不写本域result/log/outbox。
2. shared register成功而guestNo未知：返回operationState=PENDING、operationNo=null、recoveryAction=`REQUIRES_ORIGINAL_ACTOR`，安全错误语义`REGISTRATION_RESULT_UNKNOWN`；只原actor携原请求重放。第二员工/无用户后台不能查generic operation、更不能重新register或猜guestNo，不声称全窗口跨员工恢复。确定FORBIDDEN/BAD_REQUEST/CONFLICT等owner业务拒绝原样返回；仅通信/结果未知映射此状态；owner明确MEMBER_GUEST_UNAVAILABLE等能力不可用错误仍原样拒绝，不能把确定未执行伪装成登记未知。即使原actor离职，也不自动越权恢复。
3. 032新claim先解析当前cr1目标USER并verify guest token，持久guestNo+targetUserNo+derivedKeyHash，再owner.claim。owner原始结果到达后同一短事务insert不可变receipt、operation SUCCEEDED、ActionLog和安全Outbox。
4. owner成功本域回滚/丢响应：当前同店CUSTOMER员工恢复已知operationNo，owner.get该guest，仅state=CLAIMED且userNo与本域先验target一致、revision可信时补同receipt；不能对仍REGISTERED以旧actor/旧proof发新claim。该情形返回PENDING/`REQUIRES_FRESH_IDENTITY_PROOF`，员工通过新032请求提供自身cr1与guest token。
5. 本域没有原operation但shared已CLAIMED（例如其他行业完成）：显式032仍须当前cr1+verify guest token，目标必须等于owner.userNo；只补receipt，不调用claim改绑。两侧规范USER不同确定冲突。
6. receipt成功只读恢复不再验证原过期票据，但仍检查当前操作权限；新identity变化永远需新证明。receipt不会在普通C读/get guest路径临时写入。

## 6. 预约根、fence、签到与接待REPLACE

### 6.1 锁序与RPC边界

已有预约的所有本域写事务统一：预约根 `selectScopedForUpdate` → 永久fence `selectScopedForUpdate` → current/intent（有则锁）→ 本次reception operation → workOrder根/context/task/submission → ActionLog/Outbox。不可先锁reception operation再争预约根；claim scanner的lease短事务须先提交，之后再进业务锁。新直客无预约时只锁本次reception operation，再创建全新root/current/fence和链接；其他线程此时拿不到未提交新根，不形成逆序。

所有权限/CustomerIdentity/GuestRegistration/catalog/personnel/schedule/resource调用均在事务外。锁外观察只作为证据，锁内重核版本、current指针、arrangement revision、holdNo/planHash/owner结果绑定、已占用工单等本域事实；不得在锁内调用access.root或coordinator.hold（它们会RPC）。缺任一父链/根/fence fail closed。

### 6.2 签到

1. 验证当前身份权限、appointment完整scope。检查原操作成功重放；新动作核expectedRevision、CONFIRMED、current.operationNo为空、arrangement有效、无active fence。
2. 锁外读取当前成功arrangement及其成功CONFIRM/REPLACE owner binding，而不是盲用最新失败operation。通过真实ResourceBookingPort.queryHold校验CONFIRMED、holdNo、planHash、ownerVersion/holdVersion/leaseRevision/source绑定。将全部观察证据放在内存/安全payload，不能把根version当hold.sourceRevision（签到等本域动作会单独推进根）。
3. C额外核真实mc1与同店challenge；B按USER或正式GUEST proof核验。serverNow<start为EARLY；==start为ON_TIME；start<now<end为LATE；now>=end拒绝BEAUTY_ARRIVAL_REQUIRES_RESCHEDULE。
4. 短事务按锁序重锁、核expectedRevision/current空/fence空/arrangement与证据不变、仍无工单、end仍晚于serverNow；C再查challenge到期。调用markArrived CAS=1，用server UTC写checkedInAt；不改计划start/end、不动hold、不创建workOrder/tasks、不调用trade/pay。
5. 同事务operation SUCCEEDED/result、ActionLog、`BeautyReceptionStateChanged`安全事件。根锁与现有cancel/change/no-show互斥；已发起而UNKNOWN的current/fence禁止签到。事务提交失败整组回滚，重复原键不重复event。

### 6.3 接待重分配

052仅允许CHECKED_IN且无workOrder/actual tasks。assignments为空或逐个解析后等于现有完整安排且startAt没变化时确认原安排，不发REPLACE。显式assignments必须覆盖全部冻结项目要求；拒多余/重复/缺项，itemIndex按当前已展开snapshot.items顺序，不重用最初qty输入索引。

变化时：原开始已过去必须显式未来5分钟网格startAt；原开始仍未来且不提供startAt时可沿原startAt，但所有before buffer的资源起点也必须未来。不能静默平移。end已过去时同样要求新未来计划。当前商品不再可售/ACTIVE，返回明确冲突要求服务变更；不以最新definition替换原定义。

`WorkOrderPreparation`的冻结读取部分在55B先实施（不要求工单Service存在），加载原arrangement snapshot、原不可变definitionVersion/requirements、原policyVersion/cancelRule，并核tenant/entity/store/definition父链、snapshotHash、原duration、原plan各间隔含原buffer一致。内部 `FrozenAppointmentScope` 保存原定义、完整rule和已观察头版本；只有服务端加载能构造，不公开接收frozenPlan。

`AppointmentMatching.matchFrozen(Scope scope, FrozenAppointmentScope frozen, List<AppointmentAssignmentReq> assignments, OffsetDateTime startAt, Replacement replacement)` 复用原同一个Slot构造/allocate/occupied算法，仅替换项目输入来源；资格/排班/容量取新时段真实facts。提取输入事实构造与共用分配循环，禁止复制整matcher。旧定义/时长/buffer/数量不改变；只刷新资源与时间。REPLACE新arrangement仍生成新BTAI号（现有arrange逐次insert，复用旧号会撞唯一键），内部保存old→new项目映射；任务最终指向新成功arrangement的项目，规则与原范围一致。

新增内部action `RECEPTION_CHANGE`：AppointmentPreparationService在有权CHECKED_IN且无工单时允许，commandAction=REPLACE；普通CHANGE仍只CONFIRMED。AppointmentCoordinator.replacement抽取私有共同检查，新增 `replacementForReception(root)` 仅上述状态；成功finish保留CHECKED_IN/checkedInAt，arrange更新计划/hold/current.arrangementRevision；失败保留旧安排和旧hold，清本操作指针仅在原owner确定终态后。UNKNOWN继续保持fence与current操作，绝不创建工单。SourceAuthority在发放许可前同根核RECEPTION_CHANGE状态及无单，防止新分支误让普通更改越界。

replacement/签到hold查询必须定位“当前arrangement对应的成功operation”，包括RECEPTION_CHANGE，不能选最新REJECTED change。提取 `AppointmentCoordinator.currentArrangementEvidence(root)` 作为唯一取证路径，原普通取消/改约回归一并证明。

## 7. 工单范围与直客流程

### 7.1 WorkOrderPreparation与一次转单

`WorkOrderPreparation.fromAppointment(root)` 只从已成功current arrangement取得全部冻结项目、definition、requirements、policy、安排与hold。`unassigned(entity,store,items)` 锁外读真实catalog/门店可售事实与当前已发布P1单步骤SERVICE定义，qty展开≤10。事务内按稳定sku/definition顺序锁本域定义/政策头并重核已观察version/current引用，确认父链仍有效；外域fact只是当前观察，不假设分布式锁。

`WorkOrderCoordinator.openFromAppointment(entity,store,actor,appointmentNo,req,key,endpoint)` 是052/054/048唯一实现。先真实权限与原键查询，已SUCCEEDED返回冻结原WorkOrderVO；同键不同参数冲突。新操作先创建RECEIVED/OPEN_PENDING，在需要REPLACE时先完成§6.3；如果OWNER未知返回WorkOrderVO PENDING，不能存在任何工单/任务。

最终短事务：预约根→fence→current→operation，核CHECKED_IN、expectedRevision（若本次REPLACE已成功则使用operation记录的确切新根版本）、无活动current/fence、成功arrangement/hold证据未变、无既有workOrder/actual task；原计划已结束且未成功REPLACE时拒绝开单，不能从过去hold得出当前已安排。执行markWorkOrderOpened CAS推进根版本；insert workOrder OPEN/scopeRevision1/scopeFrozen=false/startedAt=null，context，按冻结snapshot每一原预约项目一task（qty直接原快照，BM03通常1），任务PENDING/bindingRevision0/doneQty0/voidedQty0/startedAt=null，执行内容逐行冻结。

同事务insert `bty_service_submission` OPEN/RECEIVED草稿，frozenCommand为 `OpenSubmissionDraft(schemaVersion=1,scopeRevision=1,scopeHash,VerifiedIdentity,taskRefs)`；taskRefs包含taskNo/skuNo/definitionVersionNo/qty/uom，不含凭证/外部下单指令。nextRetryAt=null，55恢复器不把RECEIVED草稿送外部owner。Task58必须当前合法人明确submit后才进入来源阶段。

同事务ActionLog、安全Outbox和operation SUCCEEDED/result。不同键并发时预约根/唯一appointment索引确保一单；输家返回CONFLICT，不挪用赢家operation结果。相同原键成功即使根版本推进仍返回原单。没有真实source allocation/binding，所有task canStart=false/sourceState=UNBOUND；已有appointmentNo不表示来源或履约完成。

无预约054同一创建逻辑省预约根/资源操作，operation行串行化，直接冻结本域规则并创建UNASSIGNED安排范围。实际task.status始终写PENDING，UNASSIGNED只写入冻结安排状态/VO，不作为V1 task状态值；原V1目前未枚举CHECK task.status，这也不是随意新增生命周期状态的理由。它不调用Matching/ResourceBooking、不生成appointment/hold/实际参与；planned时间/arrangement revision为空。items有效不等于有资源。

### 7.2 直客完整计划与durable链接

048首先核同店CUSTOMER+BOOKING_MANAGE及customerRef/guestNo，得到规范身份，然后insert RECEIVED+稳定派生键（外调资源前）。项目与规则锁外读取；startAt为空时，以第一个项目全部要求最早资源起点（offset-buffer）为约束，求使所有预留区间起点严格晚于now的最早未来5分钟服务开始网格。指定startAt须同样未来、5分钟整网格，且≤既有bookingAheadDays；不发明30分钟上限。选定时间保存并回显；后续重试不重算时间/项目/派生号。

真实预约prepare必须与接待链接原子：新增 `AppointmentPreparationService.prepareReception(String receptionOperationNo, AppointmentMatching.Matched matched, BtyAppointment target, long expectedRevision, String action, ResourceBookingPort.Hold hold)`。该入口先在锁外校验当前授权与catalog事实，后在一个db.tx中按§6.1锁序重核接待规范身份/状态；保存preview、root/current/fence、appointment operation及acceptPayload，并将previewNo/appointmentNo/appointmentOperationNo/derivedKeyHash写回reception payload/列，phase=RESOURCE_PENDING。抽取原prepare本域写体供双方调用，不嵌套db.tx；它不调用owner、不把旧actor塞SecurityContext。对于已存在链接直接返回原appointmentOperation，不新建preview/root/hold。

该内部入口允许当前第二员工代表同店继续已验证身份的原接待操作，不走公开preview.own的“必须原actor”限制；权威来自接待持久operation和当前权限，原普通预约own规则不变。appointment operation的原actor是实际创建该资源操作的人；稳定derivedKey仍由原接待命名空间生成；恢复execute始终使用当前access.actor。

随后原AppointmentCoordinator.advance推进RESERVE→CONFIRM，原key/原bytes/原operation固定。accept响应丢失：当前授权重放原accept取得相同commandNo；CONFIRM响应丢失：查询/reconcile原命令。成功后phase=ARRIVAL_PENDING，用已持久规范身份和成功hold证据执行内部签到；该内部入口仅接待operation绑定到本预约且身份匹配时可用，不是普通052隐式签到。再phase=OPEN_PENDING复用§7.1一次转单，最终SUCCEEDED。

若CONFIRM确定失败产生现有`RELEASE_REQUIRED`：接待仍PENDING，failureCode=`RESOURCE_RELEASE_REQUIRED`，交由当前授权者沿原预约cancel/release流程，不能把已held资源遗忘或开启任务；原资源未明确终态不把operation置REJECTED、不清fence。最终明确资源拒绝且无剩余hold副作用才能REJECTED。

若恢复时保存start/end已过期：不得重选计划，返回PENDING/`ARRIVAL_REQUIRES_RESCHEDULE`，需当前有权者显式协调/新安排；不得将旧hold当当前资源。恢复到ARRIVAL_PENDING/OPEN_PENDING可由新当前有权员工继续；后台无用户只读取owner已知终态并写本域对账，不签发新授权、不偷偷签到/开单。

## 8. 读取、异常、恢复与Bean DAG

### 8.1 SQL授权与读取

预约C读/list条件：root.tenant=MAIN/deleted=0且 `(customer_ref=:currentUser OR (customer_ref IS NULL AND EXISTS receipt r WHERE r.tenant=root.tenant AND r.entity=root.entity AND r.store=root.store AND r.guest_no=root.guest_ref AND r.user_no=:currentUser AND r.deleted=0))`。所有子查询/分页count用同一条件。AppointmentAccess.root消费端委托本域 `CustomerOwnership` 查询该谓词；改变get/list与cancel/change等根归属判定的一致性，普通权限和状态不扩宽。

工单C：INNER JOIN context on tenant/entity/store/workOrderNo且双方deleted=0，再匹配直接USER或GUEST+同scope receipt；root.customer_ref类型编码必须与context相等。任务/step/participation/submission只从已授权工单按tenant/entity/workOrderNo及活跃父链读取。无context的legacy工单C拒绝；B同店只读UNKNOWN。

WorkOrderQuery逐任务返回真实计划、冻结definition/taskSnapshot和来源状态。Task55无start/complete能力，availableActions仅已实现的READ（只读详情），不返回未来动作。steps/participations不存在时[]；新单actualStartedAt=null。禁止读取/getBill做假金额；财务权限ORDER_VIEW由58单独处理。

### 8.2 异常与安全事件

053短事务预约根→fence→operation核expectedRevision，写append-only BtyActionLog：aggregate=BEAUTY_APPOINTMENT/action=RECEPTION_EXCEPTION_REQUESTED/requestRef=operationNo/reason仅本域；safeSnapshot含kind/proposal/影响task引用，不能含proof或整frozenPlan。受影响task引用经workOrder父链真实查询，没任务则[]。operation/result及安全Outbox同事务；fence活跃可以记录请求，但不释放/改变它。

新增平台事件record `backend/shop-base/src/main/java/ai/neargo/shop/spi/beauty/BeautyReceptionStateChanged.java`，仅 `businessNo,entityNo,storeNo,revision,state`；对应类型BEAUTY_RECEPTION_STATE_CHANGED。预约到店、工单建成、认领回执补记、异常登记均使用此安全投影；event去重由operation成功原子提交与原键重放保障。BeautyWriteGuardConfiguration及standalone Outbox路由添加此真实类型；ARCHIVED仅归档，不声称通知送达。不新造审批/待办Port。

### 8.3 恢复分派与内部辅助签名

保留原ServiceOperation接口签名，get/recover先按scope查reception operation，存在则由本域处理；不存在再走原appointment operation。不要靠捕获任意NOT_FOUND/DB失败作为降级，不可缺表静默空。

新增内部值对象字段冻结（core内，无公共入参）：`VerifiedIdentity(String kind,String reference)`；`HoldEvidence(ResourceSourceAuthorityPort.Binding binding,ResourceBookingPort.Hold hold,long arrangementRevision,String arrangementHash)`；`ObservedHead(String kind,String businessNo,long version,String selectedVersionNo)`；`FrozenAppointmentScope(List<AppointmentPlanItemVO> items,List<WorkOrderTaskSnapshotVO> definitions,String policyVersionNo,PublishStorePolicyVersionReq.CancellationRule cancelRule,List<ObservedHead> observedHeads,HoldEvidence holdEvidence)`；`PreparedWorkOrderScope(VerifiedIdentity identity,FrozenAppointmentScope frozen,List<AppointmentAssignmentVO> assignments)`。items与definitions严格按同索引一一对应，旧数据缺definition/rule不产生可执行计划。无预约scope无hold/arrangement，政策不存在时仅相关引用为空而不伪造。

新增辅助（均 `beauty-core/ai.neargo.shop.beauty.service.impl`，不建Repository）：

- `ReceptionPreparation.begin(String entity,String store,String actor,String endpoint,String key,String requestHash,VerifiedIdentity identity,ReceptionPayload payload)` → BtyReceptionOperation；只验证/持久初始状态与稳定摘要。`find(String entity,String store,String actor,String endpoint,String key,String requestHash)`返回BtyReceptionOperation（不存在为null），完整命名空间查键；专有payload是sealed内部record。
- `ReceptionCoordinator.checkIn(String entity,String store,String actor,CheckInReq req,String key)` → ArrivalVO；`checkInMine(String user,MyCheckInReq req,String key)` → MyArrivalVO；`receiveWalkIn(String entity,String store,String actor,ReceiveWalkInReq req,String key)` → WalkInVO；`advance(String operationNo)` → OperationVO。负责流程次序，调用真实mapper/Appointment辅助/WorkOrderCoordinator，不负责顾客主档。
- `WorkOrderPreparation.fromAppointment(BtyAppointment root)` / `unassigned(String entity,String store,VerifiedIdentity identity,List<AppointmentItemReq> items)` → `PreparedWorkOrderScope`；该内部record包含verified frozen lines、policy、observed本域heads、arrangement/hold证据。`verifyLocked(PreparedWorkOrderScope scope)`仅本域mapper检查，不RPC。
- `WorkOrderCoordinator.openFromAppointment(String entity,String store,String actor,String appointmentNo,OpenAppointmentWorkOrderReq req,String key,String endpoint)` / `openUnassigned(String entity,String store,String actor,OpenWorkOrderReq req,String key)` → WorkOrderVO；`openReception(String operationNo)`处理048持久操作，使用同一个私有最终写体。
- `WorkOrderQuery.get(entity,store,actor,workOrderNo)` → WorkOrderDetailVO；`getMine(user,workOrderNo)` → MyWorkOrderVO。
- `GuestRegistrationServiceImpl`直接操作ReceptionOperationMapper/GuestClaimReceiptMapper/ActionLogMapper+真实GuestRegistration/CustomerIdentity；新增包内 `recoverClaim(String operationNo)` → OperationVO，不依赖ReceptionService。
- `ReceptionRecovery.scan()` → int，只选phase=RESOURCE_PENDING、nextRetryAt到期/lease可用，且JOIN原appointment operation已有非空receipt、原绑定能走reconcile的项；WHERE在LIMIT50之前排除待原actor/新proof/ARRIVAL_PENDING/OPEN_PENDING等需用户项，031根本无本域行。排序nextRetryAt ASC,id ASC；claim lease30秒，finally将已扫描未终态项nextRetryAt推进30秒（失败60秒）并清lease，防止前50反复垄断。只调用AppointmentCoordinator.recover读取已绑定原owner终态；终态对账后可把接待标记ARRIVAL_PENDING并nextRetryAt=null，绝不自动签到/开单、不调用需CUSTOMER的guest.get。缺receipt的accept未知由当前授权用户重放，后台不接受新命令。增加“65个待用户项+至少1个可对账项”以及“60个可对账未终态项跨批推进”的公平性回归。

依赖箭头（文本DAG；仓库图形要求SVG，本报告不生成Mermaid图）：

```text
AppointmentServiceImpl ──> WorkOrderCoordinator ──> WorkOrderPreparation
WorkOrderServiceImpl ────> WorkOrderCoordinator / WorkOrderQuery
ReceptionServiceImpl ────> ReceptionCoordinator ──> WorkOrderCoordinator
                                           └───> AppointmentMatching / PreparationService / Coordinator
GuestRegistrationServiceImpl ──> CustomerIdentityPort / GuestRegistrationPort / 本域Mappers
ServiceOperationQuery/RecoveryImpl ──> ReceptionCoordinator / GuestRegistrationServiceImpl(包内恢复)
CustomerOwnership ──> GuestClaimReceiptMapper / scoped根查询
AppointmentAccess ──> CustomerOwnership / MerchantOperatorAccessPort
所有辅助 ──> AppointmentPersistence或直接本域Mappers、同数据源TX、Json、Clock、OutboxPublisher
```

WorkOrderCoordinator不依赖AppointmentService/ReceptionService；AppointmentMatching不依赖WorkOrderCoordinator。AppointmentPreparationService仅增加ReceptionOperationMapper及ReceptionAccess，后者不依赖Service，避免绕回Coordinator。GuestRegistrationService独立，无ReceptionCoordinator依赖；分派器可以消费二者但不反向注入。不创建为装配而用的fake owner。

`BeautyReceptionConfiguration` 用原 `@ConditionalOnBeautyEnabled`，由BeautyBusinessAutoConfiguration显式import；ObjectProvider外部Port按调用取用，内域持久Bean/mapper强依赖有schema先决条件。业务Bean不在关闭/错误部署形态装配；到店码Clock用 `Clock.systemUTC()` 命名bean，测试固定Clock。新增恢复Job沿原`@Profile("api")`的JobDeclaration/Job类模式，不起线程内置timer；无执行授权只对账。ServiceImpl保留直接本域Mapper依赖用于其本域记录，不建立Repository层。

## 9. AC→测试，RED/GREEN与验证边界

所有IT先写精确断言并跑RED（缺行为或错误断言，不是只缺外部环境），每子步实现后跑GREEN。四个核心套件在 `beauty-core/src/test/java/ai/neargo/shop/beauty/scenario`，真实MySQL/Mappers/事务/Outbox；资源/目录事实采用明确ControlledOwner边界fixture（沿AppointmentBusinessIT），不能冒称真实owner网络与容量并发已验收。

真实mc1/cr1/GuestRegistration不得用lambda声称验证过。新增 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautyReceptionIdentityIT.java`（beauty-embedded测试依赖已存在）在真实member服务/票据/guest存储+当前授权事实下调用本地beauty Services，复用Task54 owner用例fixture模式，避免beauty-core生产依赖shop-core。核心套件身份桩只验证调用/参数与事务边界；真实身份断言全部由该host原生桥接套件覆盖。Task54已审专项未变，仅补新消费链，不重跑无关整域专项。该测试落点拆分已获控制器确认。

| brief AC | 测试方法（新建，意图明确） | 关键断言 |
|---|---|---|
| 1/2/12/14 | BeautyReceptionIdentityIT.currentActorTicketsAndGuestFactsAreRequired | 真mc1→cr1→USER等于预约；guest verify真实token；旧BTG/伪串/无provider failclosed；账号非技师 |
| 2/到店码 | ReceptionBusinessIT.consumerNeedsOwnIdentityAndLiveStoreChallenge；BeautyReceptionIdentityIT.consumerProofCannotBeReplacedByMerchantReference | 两项缺一/他人mc1/异店/过期/猜码拒绝；同key码不续期；C本人成功 |
| 3 | ReceptionBusinessIT.checkInKeepsScheduleHoldAndNoTasks；earlyLateAndEndedBoundaries | EARLY/ON_TIME/LATE和end边界，checkedInAt server UTC，hold未释放、0workOrder/task/trade调用 |
| 3/19/22 | ReceptionBusinessIT.checkInRacesCancelAndRejectsUnknownFence | 两连接根锁并发只一合法转换；current挂起或旧fence未对账拒签到；缺fence根拒绝 |
| 4/7/8 | WorkOrderScopeIT.originalKeyReplaysAndDifferentKeysCreateOneScope | 一单/逐原项目task/数量及多定义精确/初始PENDING+0+null+UNBOUND；原键旧revision重放；异键冲突 |
| 5/补充 | ReceptionBusinessIT.reassignsCheckedInWithFrozenRules；replacementRejectsWithoutChangingOldArrangement | 发布新规则后仍旧duration/buffer/definition；未来网格；真实REPLACE调用；失败旧hold/安排保留；已开单拒绝 |
| 6/14/20 | WalkInRecoveryIT.confirmedWalkInCreatesOneAppointmentScope；unknownAcceptAndConfirmRecoverOriginalNumbersAfterRestart | 收到PENDING时无单/tasks；两丢响应窗口+重启仍原operation/appointment/hold/command；不重复物化 |
| 6/14/20 | WalkInRecoveryIT.authorizedSecondEmployeeContinuesWithoutImpersonation；allOwnerCallsRunOutsideLocalTransactions | 当前新actor真实传owner；旧actor不复活；无锁中RPC；before buffer默认最早未来网格；不自动重算过期计划 |
| 14/20 | WalkInRecoveryIT.recoverySkipsSixtyFiveUserBlockedRows；resourceRetriesRotateBeyondFirstFifty | WHERE先排除需用户项；后方原资源终态得到处理；60个eligible未终态项轮转不饥饿，scanner不调用guest.get或构造旧actor |
| 7/8/16 | WorkOrderScopeIT.unassignedOrderHasNoResourceOrExecutableTask；catalogChangesCannotOverwriteFrozenScope | 无预约不造hold/assignment；冻结范围详情可读；规则版本重核避免TOCTOU；不返回start/complete |
| 9–11/14/21 | GuestClaimReceiptIT.sharedClaimCommitThenLocalRollbackRecoversReceipt；BeautyReceptionIdentityIT.managerCompletesKnownClaimReceipt | 初次保存规范USER后注入回滚；第二当前CUSTOMER get事实后唯一receipt；不同USER/异scope冲突 |
| 12/13 | GuestClaimReceiptIT.anonymousBtgCannotBecomeClaimed；receiptOwnershipIsAppliedBeforePaging | SQL分页total/items均只本人；USER与已receipt guest并集；软删/父链不符不漏；共享已claim无本地receipt仍不可见 |
| 登记裁决 | BeautyReceptionIdentityIT.registrationUnknownRequiresOriginalActorAndHasNoLocalContactHash | shared成功响应丢失：operationNo=null/PENDING/REQUIRES_ORIGINAL_ACTOR；原人同请求恢复；同key改contact由owner HMAC冲突；本域无登记行/低熵摘要/旧actor伪装 |
| 10/13/16 | WorkOrderScopeIT.scopedReadsRejectDeletedAndForeignParents；legacyContextIsReadOnlyUnknown | task/step/participation外scope父链拒绝；legacy B只读/C无归属 |
| 15/17/22 | WorkOrderScopeIT.exceptionReplayIsAtomicWithActionAndOutbox | PENDING_REVIEW原requestNo/影响task一致；outbox故障全回滚；重复key仅一日志/事件，事件无reason/proof/plan |
| 18/23 | BeautyConditionalWiringTest.receptionWiringHasNoServiceCycle；BeautySchemaMigrationIT.v1ToV5PreservesFrozenHistoryAndDetectsDrift | embedded/standalone业务装配边界及关闭时无Bean；V1–V5正式升级27私表；缺列/改CHECK/索引漂移拒绝 |

消融选择一项且真实RED：临时移除Service签到fence重核与Mapper对应fence防线（单删其中一处仍可能被另一处拦，不算有效消融），运行checkInRacesCancelAndRejectsUnknownFence中的孤立active-fence场景；或移除任务查询父链条件并用专门错父链数据证明泄露测试变红。恢复代码后同测试GREEN，记录原始命令/输出于task-55-report.md。

建议实施期命令（本设计阶段未执行）：

```bash
# 使用既有JDK21，离线隔离m2。每次原生跑前由既有脚本管理一次性DB。
node scripts/verify-beauty-persistence.mjs --reception-only
node scripts/verify-beauty-persistence.mjs --appointment-business-only
node scripts/verify-beauty-persistence.mjs --appointment-migrations-only
mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml test
node scripts/check-sql-portability.mjs
python3 scripts/gen-beauty-database-design.py
python3 scripts/gen-beauty-java-method-design.py
```

`--reception-only`是本任务将追加的显式四套IT选择（不是现有可用参数）；host真实身份桥接新增同一次性native DB runner入口并传入Task54所需配置，只跑BeautyReceptionIdentityIT，不能把H2执行误称原生。实际JDK/测试选项在实施报告写完整命令。结构/三语/生成物守卫按既有脚本范围跑，BeautyArchitectureTest明确扫插件模块，新ErrorCode三语同补；无finance金额断言。HTTP/controller覆盖不在55声称范围。

## 10. 文件责任与四个可审查子步骤

准确根：`backend-plugins/shop-industry-beauty`；API包 `ai.neargo.shop.beauty.service`、`dto`；core包 `ai.neargo.shop.beauty.service.impl`、`policy`、`entity`、`mapper`、`config`、`job`。§2/§3/§4枚举的新类均一文件一public类型；内部payload/准备record放相应helper内，不新造同义Command链。

**55A 身份引用、接待操作与到店码（可独立审查）**

- [ ] RED：GuestClaimReceiptIT与真实BeautyReceptionIdentityIT的登记/claim/receipt/C归属用例、challenge幂等/到期/schema升级测试。
- [ ] 冻结新增JSON字段与V5四表生成物；实现四Entity/Mapper/schema wiring、ReceptionPreparation/ReceptionAccess/ReceptionIdentity/CustomerOwnership，GuestRegistrationService/Impl；原子receipt需要的BeautyReceptionStateChanged/真实Outbox route与三语错误码在本步同时落地，不能等55C或用空publisher；ReceptionService先声明本子步实际已实现challenge方法，其余方法随所属子步增加，禁止空壳。
- [ ] 新增Appointment preview/create guestNo兼容构造器与真实owner规范化，C SQL归属前置过滤。
- [ ] GREEN+独立spec/quality review。交付可真实登记/认领/补回执/门店到店码，未知窗口限制有测试，不宣称接待已全实现。

**55B 到店与内部接待重排（依赖55A）**

- [ ] RED：ReceptionBusinessIT到店/竞争/规则冻结/REPLACE保旧，旧AppointmentBusinessIT基线。
- [ ] 实现047/154/053及ReceptionCoordinator，提取currentArrangementEvidence/共用Slot输入；新增matchFrozen/RECEPTION_CHANGE与根fence/CAS，预约旧API状态边界保持。
- [ ] GREEN、一次有效消融、独立spec/quality review。交付不会误释放资源或创建任务的到店及可恢复重排。

**55C 工单范围与未安排工单（依赖55B）**

- [ ] RED：WorkOrderScopeIT一次转单/原键/qty/冻结/父链/UNASSIGNED。
- [ ] 实现WorkOrderPreparation/Coordinator/Query、052/054/055/155、DTO与OPEN/RECEIVED submission；配置DAG与事务一次提交；复用55A安全event/Outbox并补本步错误码。
- [ ] GREEN+独立spec/quality review。交付真实冻结工单范围，UNBOUND不可开工，不调用trade/pay。

**55D 直客恢复与最终回归（依赖55C）**

- [ ] RED：WalkInRecoveryIT accept/confirm丢响应、重启、第二员工、无锁RPC、before-buffer网格。
- [ ] 实现原子prepareReception链接与048、generic operation原号查询/恢复分派、恢复Job及V5生成/字典收口；schema/装配/API方法JSON同步实际能力。
- [ ] GREEN四套+host真实身份新链+BM03回归+V1→V5严格升级、architecture/i18n/SQL/生成物守卫；报告精确覆盖/未覆盖边界。
- [ ] 本任务产品文件逐个diff，只精确staging本任务文件；控制器允许后提交，独立spec/quality最终通过再进入56。当前设计阶段不staging/commit。

## 11. 控制器裁决收口

本轮六项均已采纳并落实到上文，无待决业务问题：

1. **052编译兼容：** 新接口方法暂fail-closed default，本地Impl真实覆盖；不计远程交付，59去default并完成wire。
2. **恢复查询：** 既有getOperation/recoverOperation按可信scope分派接待，OperationVO追加workOrderNo/failureCode并保留旧构造器；031未知登记没有本域operation，明确排除。
3. **真实身份测试：** core四套MySQL+受控资源owner边界，shop-app BeautyReceptionIdentityIT覆盖真实Task54票据/guest新消费链，不加生产跨域依赖。
4. **四个review gate：** 55A/B/C/D连续完成同一Task55；V5合同在A冻结；receipt事件/Outbox在A真实落地；FrozenAppointmentScope在B先提取供C复用。
5. **登记低熵摘要：** 031直接委托owner，原key派生不包含contact/displayName，本域不创建登记operation或联系方式摘要，幂等/HMAC只归已审owner；guestNo未知仅原actor重放，operationNo=null。
6. **后台扫描公平性：** 只扫描后台真实可reconcile的资源项，LIMIT前排除待用户项，按nextRetryAt/id排序并推进重试时刻；不后台guest.get、不伪造actor；>50/65项回归明确列入。

已先行确认的安全边界继续有效：known guest claim先持久已核验USER后允许当前同店员工读owner事实补receipt；旧匿名BTG不认领；冻结buffer/rules从原不可变version重建并核原plan。实际task.status=PENDING，未安排仅assignmentState=UNASSIGNED；submission为OPEN/RECEIVED。

控制器已同步主TDD并批准依次实施55A–D。本报告不声称产品实现、测试、HTTP或部署验收已通过。
