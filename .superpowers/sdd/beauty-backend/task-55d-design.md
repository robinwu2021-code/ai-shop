# Task55D 直客接待、原号恢复与有界对账精确设计

状态：根技术核对已通过，进入本人inline RED→实现→GREEN。编码BASE=612b8c08144d0aaa81959d4c455ab1dcc3d4404d。2026-10-23。

## 0. 绑定、范围与现状

- 执行目录 `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`，分支 `codex/beauty-foundation`；只读核对 HEAD/BASE=`1608709c3e35318b750449641af83b7c8e3c35e0`，前置 C 产品 `22e1d8938a6ad2a45b2c44e40480d7a7d64668bf` 已被根验收。
- 规格真源：`task-55d-brief.md`（含根2026-10-23追加“启动前技术核对补充”，本设计已逐条吸收）；上位绑定主 TDD `docs/technical/TDD-美业系统-后端开发计划.md` Task55（§2.22 内仅 Task55 条目），`task-55-design.md` §3 / §7.2 / §8 / §9 / §10 中 D；`docs/technical/ADR/ADR-045-共用临客身份与本人核验归属.md`。未读/执行整个主计划。
- 规范：`.claude/skills/ai-shop-dev-standards/SKILL.md` 与 `references/java.md`，零售 Java 约定；契约档位 1。此附件补当前源码可实施方案，不回改根 TDD/progress/brief/总体 design/review，不新建 docs/plans。
- D 仅 BAPI-048 本地 Service/DTO、本域辅助、原号 GET/recover、资源对账 Job。公开 HTTP/remote 真接线归59；交易/支付/开工/部署归56–60。ready7、27私表；V1–V7冻结，无新迁移/新表。
- 本阶段只读生产源码，唯一写入本设计和初始 report；不测试/容器/schema/stage/commit。根技术放行后本人原生 RED→实现→GREEN，不再向用户申请已授权业务范围，不派子 agent。

### 0.1 真实代码与旧草案差异

| 核对点 | BASE 实际 | D 最小选择及理由 |
|---|---|---|
| ReceptionService / DTO | 仅047/154/053/challenge；无 receiveWalkIn、ReceiveWalkInReq、WalkInVO | 按 brief 追加真实048，不能以 JSON 设计条目假称已存在 |
| AppointmentPreparationService.prepareReception | 两个重载，参数为 preview/target/expected/key/hash/PreparedWorkOrderScope/WorkOrderPreparation/requestEvidence[/receptionOperationNo]，action 固定 RECEPTION_CHANGE | 保留此已审重载；新增 CREATE 专用重载，抽单一本域写体，不能拿 REPLACE 冒充 CREATE |
| save / prepare | save 单独持久 preview；prepare 自己 db.tx，AppointmentPersistence.tx 为 REQUIRES_NEW/READ_COMMITTED | 直客不调 save 再外层包 prepare；所有新 root/preview/operation/外层链接一次短 TX |
| WorkOrderCoordinator | private openPrepared / persist 是 C 唯一最终写体，依赖 ReceptionReassignment，无 openReception | 最小加 openReception 入口，仍调用 openPrepared→persist，不复制任务/草稿/事件代码 |
| PreparedWorkOrderScope | 已有第4字段 ResourceBookingPort.Scope，另保留3参构造器 | D 始终传真实可信 Scope，不再按旧草案造3字段版本 |
| 原号 ServiceOperation | 当前仅 db.operation→预约路径；OperationVO 仍5字段 | scoped SQL 查 reception first，类型分派，预约路径保留；DTO尾增2字段+旧构造器 |
| claim recover | GuestRegistrationServiceImpl.recoverClaim(entity,store,actor,operationNo) 返回 GuestClaimVO | 复用真实四参方法；不发明单参或返回 OperationVO 的 owner API |
| WorkOrderQuery 成功父链 | selectOpenedForWorkOrder 仅 action=OPEN_WORK_ORDER | 最小允许同样安全父链的 RECEIVE_WALK_IN，避免新直客工单建成却无法读 |
| CONFIRM拒绝 | finish 把原 op置RELEASE_REQUIRED，clearCurrent，releaseReconciled清activeIntent；fence行、root.holdNo和owner hold仍存在 | 根已裁决：保留永久fence行/原hold；UNKNOWN保留active pointer/current；明确终态对账清pointer合法，不改BM03协议（§7） |
| recovery runner | --reception-only 目前只 GuestClaimReceiptIT+迁移；--include-host 目前是 BeautyPolicyEndToEndIT | 用已存在 BEAUTY_TESTS / BEAUTY_RECEPTION_TESTS 精确选择，不把旧参数当四套/身份新链 |

## 1. 公共合同、权限与幂等

### 1.1 准确 DTO / Service

位置均 `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/`。

```java
// service.ReceptionService（新增，无HTTP接线）
WalkInVO receiveWalkIn(String entityNo, String currentStoreNo, String actorNo,
        ReceiveWalkInReq req, String idempotencyKey);
// dto（新增；顺序冻结）
record ReceiveWalkInReq(String storeNo, String customerRef, String guestNo,
        List<AppointmentItemReq> items, List<AppointmentAssignmentReq> assignments, OffsetDateTime startAt) { }
record WalkInVO(String operationNo, String operationState, String appointmentNo,
        WorkOrderVO workOrder, OffsetDateTime startAt, String recoveryAction) { }
// dto.OperationVO（追加；原五字段/语义/构造器保留）
record OperationVO(String operationNo, String appointmentNo, String state, String action,
        String recoveryAction, String workOrderNo, String failureCode) {
    public OperationVO(String operationNo, String appointmentNo, String state,
            String action, String recoveryAction) {
        this(operationNo, appointmentNo, state, action, recoveryAction, null, null);
    }
}
```

Req 校验：storeNo非空≤32且等于可信currentStore；customerRef与guestNo恰一，前者票据≤256、后者业务号≤32；items非空、防御复制/拒null元素，各 AppointmentItemReq 原验证保留，展开qty总量≤10；assignments缺省空、防御复制/拒null元素，保留原显式分配≤30和重复输入拒绝规则。startAt可null，非空按UTC瞬间处理，整5分钟且秒/nano=0。actor≤64，idempotencyKey非空≤128沿 IdempotencyScope.requireKey，entity/store/operation业务号≤32。Req.toString=REDACTED，不保存customerRef票据、claimToken/contact、新授权或其可还原表示。公开日期UTC。

WalkInVO.PENDING的workOrder=null，startAt为原selectedStartAt（尚未成功冻结计划时可null）；成功只能由原最终工单写体提交。WorkOrderVO沿C：OPEN、UNBOUND、task PENDING、done/voided=0、actual=null，OPEN/RECEIVED submission仅本域草稿，无外送。

### 1.2 次序及第二员工

1. 每次新动作、原键重放、显式恢复先锁外 `AppointmentAccess.merchant(entity,store,actor,true)` + `ReceptionAccess.customer(entity,store,actor)`；真实SecurityUtils当前用户必须是MAIN、MERCHANT/MCH或USR并等于actor，核当前BOOKING_MANAGE和CUSTOMER。
2. 原key先完整 namespace 查找：tenant/entity/store/realm/kind/actor/endpoint=BAPI-048/keyHash；requestHash含同完整namespace与原Req JSON。发现同键改参即CONFLICT；SUCCEEDED直接投影原冻结结果，不要求原短票据再次有效。
3. 仅首次：customerRef经 `ReceptionIdentity.customer`→真实resolveClaimReference；guestNo经 `ReceptionIdentity.guest`→真实owner.get；冻结 `VerifiedIdentity(kind,reference)`。048无guest token字段，使用已授权当店登记引用，不能套用047的verifyGuest持有证明。
4. 首次owner核验成功后，先独立持久RECEIVED及派生key摘要，再调用目录/匹配/资源。若新范围owner明确拒绝，在无资源副作用前可明确失败；网络/目录暂不可用保持原行可恢复，不伪REJECTED。
5. 原号恢复按trusted scope读取原行，第二当前有权员工可继续；不调用ReceptionPreparation.find为其重新造命名空间，不重新生成旧actor的derived，不替换原key/requestHash/payload/canonical。所有资源accept/query/execute/queryHold及用户owner看到真实当前actor；历史actor仅审计字段，不set SecurityContext/DelegatedIdentity。
6. pending GUEST即使后来claim，仍沿已冻结GUEST，不重建hash/payload；首次已CLAIMED可冻结USER。若C准备时需要把原GUEST桥接为USER，只允许同scope CustomerOwnership/不可变receipt证明，且只影响新的prepared/context；不回写历史预约/旧pending canonical。
7. 031无本域operation、operationNo=null，未知登记仍仅原登记actor持原请求重放，不进入此分派或后台。

## 2. 内部类型与辅助签名（明确新增/沿用）

除policy.VerifiedIdentity既有外，下列业务辅助均在 `beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl`（完整包 `ai.neargo.shop.beauty.service.impl`），不建Repository，不以Lazy/ObjectProvider隐藏本域依赖。

### 2.1 新增冻结payload

`ReceptionPreparation.ReceptionPayload`的sealed permits加 `WalkInPayload`；begin按类型精确action=RECEIVE_WALK_IN/phase=RECEIVED，不沿现有fallback误记RECEPTION_EXCEPTION；WalkInPayload特例使外层appointmentNo/appointmentOperationNo列初始null，不能直接沿用当前`op.setAppointmentNo(payload.appointmentNo())`把候选号伪成已有链接。

```java
// nested in ReceptionPreparation；均无proof/contact
record WalkInPayload(int schemaVersion, String appointmentNo, String previewNo,
        String appointmentOperationNo, List<AppointmentItemReq> items,
        List<AppointmentAssignmentReq> assignments, OffsetDateTime requestedStartAt,
        OffsetDateTime selectedStartAt, AppointmentMatching.Matched matched,
        String appointmentKey, String appointmentRequestHash, Long arrivalRevision,
        Long openingRevision) implements ReceptionPayload { }
```

schemaVersion=1；appointmentNo/previewNo/appointmentOperationNo在begin前一次生成并随RECEIVED持久，分别BTA/BTPR/BTOP；此时外层对应链接列仍null，payload候选号不代表已有预约。items/assignments防御复制。requestedStartAt保存原时间（非票据）；selectedStartAt/matched/appointmentRequestHash/arrivalRevision/openingRevision初始null。`appointmentKey=requestHash("BEAUTY_WALK_IN_CREATE_V1",outer.derivedKeyHash)`，是稳定派生key，不是新凭证。可先生成outerNo/derived再组payload，不能用当前第二员工重派生。

冻结匹配后一次更新selectedStartAt、完整Matched（scope/policy/start/end/plan bytes/Snapshot项目与分配/价格观测及cancelRule/complete）、appointmentRequestHash；该hash固定为`requestHash("BEAUTY_WALK_IN_CREATE_V1",outer.operationNo,outer.requestHash,previewNo,matched.plan)`。后续不可替换。DB JSON的格式化不作为新command输入；重试始终读原appointment op.acceptPayload，frozenPayload字符串必须逐字相同。

成功外层result必须是WalkInVO。C唯一persist在同一最终TX先构造WorkOrderVO，再按真实action调用新增私有`String serializeOpeningResult(BtyReceptionOperation op, WorkOrderVO workOrder)`：OPEN_WORK_ORDER序列化原WorkOrderVO；RECEIVE_WALK_IN序列化`new WalkInVO(op.operationNo,"SUCCEEDED",op.appointmentNo,workOrder,原payload.selectedStartAt,null)`。在同TX写result、workOrderNo、SUCCEEDED及工单/任务/草稿/日志/outbox；不先提交C结果再另TX覆写。原private result(op)按准确action返回WorkOrderVO：OPEN_WORK_ORDER读原WorkOrderVO，RECEIVE_WALK_IN读原WalkInVO.workOrder；未知action failclosed。这样openPrepared锁内发现并发winner已SUCCEEDED时也能安全返回typed原结果；D公共成功入口直接读保存的WalkInVO。WorkOrderQuery不解析此result，仍按原成功父链读真实子行。

同键并发的SUCCEEDED防回退沿C已审修复：find/begin/freeze/prepare/openPrepared每个返回点发现同一原outer已成功立即返回原WalkInVO；锁外观测过期或本域CONFLICT时仅回读完整原namespace/hash/operationNo，同原行SUCCEEDED才能救回原结果。owner权限/明确能力错误不被广捕吞成成功或PENDING；异key成功不能救本pending；任何phase更新均在锁内先判终态，不能把SUCCEEDED降回RESOURCE_PENDING。

### 2.2 拟新增入口与本域写体

```java
// ReceptionPreparation（既有begin/find不改公开语义）
BtyReceptionOperation freezeWalkIn(BtyReceptionOperation observed, AppointmentMatching.Matched matched);
// AppointmentMatching（新增，抽公共load输入和allocatePlan，不复制算法）
Matched matchWalkIn(ResourceBookingPort.Scope scope, List<AppointmentItemReq> items,
        List<AppointmentAssignmentReq> assignments, OffsetDateTime requestedStartAt, Instant observedNow);
// AppointmentPreparationService（新增CREATE重载；自身拥有唯一db.tx，调用者无TX）
BtyAppointmentOperation prepareReception(String entity, String store, String actor, String receptionOperationNo);
// AppointmentCoordinator（新增只读取证，复用private verify/terminalHold）
ResourceBookingPort.Hold releasedHold(BtyAppointmentOperation release, String expectedHoldNo);
// ReceptionCoordinator（新增；现有047/154/053/reassign保留）
WalkInVO receiveWalkIn(String entity, String store, String actor, ReceiveWalkInReq req, String key);
WalkInVO recoverWalkIn(String entity, String store, String actor, String operationNo);
// WorkOrderCoordinator（新增；自身仍调用原openPrepared→persist）
WorkOrderVO openReception(String entity, String store, String actor, String receptionOperationNo);
WorkOrderVO recover(String entity, String store, String actor, String receptionOperationNo);
// ReceptionRecovery（新类）
int scan();
```

`freezeWalkIn`只锁scoped外层行，先重核完整原namespace/hash/候选号/identity/RECEIVED；若winner已冻结或已链接则返回winner。它不调RPC、不取任何root/fence/current锁。并发计算丢掉输家match，后续只读winner；绝不把winner覆盖为新时段。

CREATE prepare不调用公开save、own(preview)、customer(...)，授权来自当前权限及已持久048完整binding；普通预约save/create/own原规则原样保留。锁外对matched真实商品观测和本域版本作准备校验；锁内只本域mapper核scope、冻结版本、future及幂等链接。复用/抽取：

- `preparationRow(Matched matched, VerifiedIdentity identity, String previewNo, String actor)`：纯构造preview字段，普通save也可使用；直客无需额外owner身份查询，不能把原actor塞当前上下文。
- `prepareLocked(BtyAppointmentPreparation preview, BtyAppointment root, String action, String key, String hash, ResourceBookingPort.Hold hold, String requestEvidence, String operationNo)`：无TX包装/无RPC，只插原预约operation、Accept bytes、日志并更新current。普通prepare与直客在各自唯一db.tx内调用。普通生成opNo，D使用payload固定opNo。
- `createRoot`改可接固定appointmentNo并保持普通调用生成号；新root HELD/version1，fence/current内容保持。不能改已有资源RESERVE→CONFIRM saga。

上述两个private提取签名为本设计选定的实现形态；不引入新的公共服务/Port。资源Accept仍为Scope(MAIN,entity,store)、action=RESERVE、key=`operationNo+":RESERVE"`、sourceType=BEAUTY_APPOINTMENT、sourceNo=固定appointmentNo、expectedOwnerVersion=0、sourceRevision=1、target=null、原matched.plan。RESERVE成功后原Coordinator冻结CONFIRM原Accept，恢复不得重建。

## 3. 默认时间与冻结边界

matchWalkIn复用当前matchComplete的P1项目/规则读取、catalog可售检查、同一个allocatePlan；将输入读取提成公共私有准备步骤，一次加载，不循环生成preview/预约去试时间。目录与resource/personnel/schedule/capacity调用均锁外。

计算所有展开项目相对服务起点：第一项relativeServiceStart=0；下一项按当前算法`ceil5(previousServiceEnd+previous.after+next.before)`，每条需求相对资源起点delta=relativeServiceStart+offset-before。取 `minDelta=min(0,所有delta)`。null start为严格晚于`observedNow-minDelta`的第一个5分钟服务网格（floor/300+1，不能等号，observedNow保留精度）；然后沿原匹配生成完整计划，要求每一资源区间from严格晚于最新now。这里只求时间安全最早网格；该网格资源不足仍沿原CONFLICT/SEARCH_INCOMPLETE，不发明自动搜整天或30分钟业务限制。

指定start也要求start>now、整5分钟、每一from>now和既有bookingAheadDays；沿当前catalog policy而非新上限。新strictFuture分支仅用于048/既有frozen接待，不能顺便改变普通预约已审规则。匹配返回Scope必须等于trusted MAIN/entity/store，不能仅信请求store。

冻结成功后selectedStartAt和plan不重选。已链接后任何恢复先看原命令对账；若原安排已过期（任一预留from不再严格未来，或进入签到/开单时end已结束），保存PENDING/failureCode=ARRIVAL_REQUIRES_RESCHEDULE、停止新accept/签到/开单，回显原start。对账仍允许获知旧命令副作用，但不把过期计划解释为当前可用资源。成功已提交重放跳过过期判断，返回原结果。

## 4. 原子CREATE、锁序与无环DAG

### 4.1 事务序列

A. 锁外当前权限/真实身份核验；begin在短TX插唯一RECEIVED+固定derived和候选号，无root锁；DuplicateKey在TX外回读完整原namespace/hash winner。

B. 锁外匹配；freezeWalkIn单独短TX仅锁外层、单次冻结完整计划/selectedStartAt；提交即释放外层锁。该事务永不再取root锁，故不构成outer→root等待链。

C. `prepareReception(entity,store,actor,outerNo)`锁外读scoped原行/冻结payload并校验当前权限/观测事实；已链接直接返回原appointment op（核完整tenant/entity/store/root/preview/action/keyHash/requestHash/acceptPayload绑定）。新CREATE唯一短TX：

1. 用已冻结appointmentNo INSERT新root，再建fence、current、preview；这些行尚不可见且仅本外层拥有候选号，写入顺序root→fence→current，预览无共享根竞争。
2. 锁/核root→fence→current（或首次插入已持有其排他锁），再 `receptions.selectScopedForUpdate(entity,store,outerNo)`；检查锁内仍为同一outer namespace/hash/identity/payload及RECEIVED、链接列为空。不得先锁outer再等待已有root。
3. 同一TX调无TX的prepareLocked插CREATE operation/acceptPayload，current.operationNo=原opNo；原子将outer.appointmentNo、outer.appointmentOperationNo列及payload原previewNo/derived引用重核，phase=RESOURCE_PENDING，nextRetryAt=now（后续receipt过滤决定是否后台可取）。日志同TX。所有根/fence/current/preview/预约op与链接任一步失败全部回滚，外层保留上一步已冻结RECEIVED，无半链接或新owner副作用。
4. 两个当前员工对同outer同时C时，第二INSERT稳定root唯一键等待winner；若DuplicateKey，整个TX先结束，再锁外重读outer。winner已链接则沿原号返回；未链接且root异常存在视数据冲突failclosed，不用外层锁后补root。不得在失败TX里吞DuplicateKey继续写。不存在root的SELECT FOR UPDATE在READ_COMMITTED不充当串行化依据。

新root在C提交前外界看不到；已链接的一切后续路径总是root→fence→current→outer，不可能被REPLACE形成反向锁。严禁调用者db.tx包此入口，严禁持root/outer锁再调内部REQUIRES_NEW。需要共用的是无TX私有写体，不是再套一层事务。

D. C返回提交后，锁外调用原AppointmentCoordinator.advance。它自身bind/finish仍各自短TX，外层不持锁。拿到结果后另短TX按root→fence→current→outer对账推进phase。

### 4.2 接待签到与开单

只有原CREATE op明确SUCCEEDED、receipt CONFIRM/SUCCEEDED、原arrangement/current/preview/plan/hash/hold完整绑定且当前root CONFIRMED、无activeIntent/current.operationNo、无existing workOrder/actual task，才可RESOURCE_PENDING→ARRIVAL_PENDING。

受限内部签到只由recoverWalkIn调用；不调用公开checkIn去索取新proof，也不创建047外层。复用ReceptionCoordinator已有arrive的锁内markArrived/arrangement核验/安全log+outbox写体（抽无TX私有方法），依据当前048冻结规范身份、成功HoldEvidence、完整outer→root/appointment op绑定；root→fence→current→048 outer短TX内markArrived并置outer.OPEN_PENDING，同TX安全日志/outbox。若已OPEN_PENDING则不重签；并发winner成功先返回。普通052仍严格CHECKED_IN，普通047/C154仍原proof/challenge规则。

`openReception`锁外重新当前授权，scoped读action=RECEIVE_WALK_IN/OPEN_PENDING及root，调用原WorkOrderPreparation.fromAppointment，preparedForIdentity用048冻结canonical（不调用guest.get、不重写原canonical）；与C同样有receipt才可桥接。其原openPrepared锁root→fence→current→outer并复核同原行/payload/phase/revision/hold/future及无工单/actual task；调用唯一persist一次写工单/context/tasks/submission/log/outbox并将同048 outer置SUCCEEDED/result=WalkInVO（包内含原WorkOrderVO），同一个TX完成原子结果包装。不能新增第二OPEN_WORK_ORDER外层，context.sourceReceptionOperationNo必须是048原号。

WalkInPayload.arrivalRevision在资源成功→ARRIVAL_PENDING的同TX保存当时root.version，内部签到要求精确匹配；openingRevision在签到TX保存markArrived后的revision，OPEN_PENDING时必非null。两字段初始null，按phase只写一次；绝不从后续任意root.version猜测。这样介入cancel/reassign不被当作048成功。

### 4.3 构造依赖方向

```text
ReceptionServiceImpl → ReceptionCoordinator → WorkOrderCoordinator → WorkOrderPreparation
                              ├→ AppointmentMatching
                              ├→ AppointmentPreparationService
                              └→ AppointmentCoordinator
WorkOrderCoordinator → ReceptionReassignment → AppointmentMatching / AppointmentPreparationService
WorkOrderPreparation → AppointmentCoordinator
ServiceOperationRecoveryServiceImpl → ReceptionCoordinator / WorkOrderCoordinator / GuestRegistrationServiceImpl
ServiceOperationQueryServiceImpl → AppointmentPersistence / ReceptionOperationMapper（纯读投影）
ReceptionRecovery → AppointmentCoordinator / AppointmentPersistence / ReceptionOperationMapper
所有辅助 → 本域Mapper/JSON/Clock/短TX；外部owner仅已有唯一SPI
```

ReceptionReassignment不依赖ReceptionCoordinator；WorkOrderCoordinator不反向依赖ReceptionCoordinator/ReceptionService；AppointmentPreparationService不依赖上层Coordinator/WorkOrderCoordinator。新增Job/内域Bean强构造注入，ObjectProvider仅继续用于外部Port。此DAG无需Lazy。

### 4.4 持久phase与结果精确表

| phase | 链接/结果 | 用户下一步 | 后台资格 |
|---|---|---|---|
| RECEIVED | identity/derived/候选号已持久；列链接空；selected/matched可未冻结；result=null | 原号冻结计划/原子CREATE | 不选 |
| RESOURCE_PENDING | 原appointment/preview/op已原子绑定；原Accept持久；result=null | 原号advance原命令；UNKNOWN保持PENDING | 仅§6receipt/binding可reconcile且due项 |
| ARRIVAL_PENDING | 原资源成功/arrangement完整；arrivalRevision固定；result=null | 当前授权受限内部签到 | 不选，不自动签到 |
| OPEN_PENDING | root已CHECKED_IN/openingRevision固定；result=null | 当前授权openReception共用最终写体 | 不选，不自动开单 |
| SUCCEEDED | workOrderNo+WalkInVO及工单/task/context/submission/log/outbox同TX | 只回原冻结结果 | 不选，不回退 |
| REJECTED | 无剩余资源副作用或真实拒绝并完成必要RELEASE；安全failureCode/原号WalkInVO | 只读原拒绝结果 | 不选 |

RESOURCE_PENDING+RESOURCE_RELEASE_REQUIRED和任一非终态+ARRIVAL_REQUIRES_RESCHEDULE均为operationState=PENDING；nextRetryAt=null，不能伪装终态。失败恢复/终态竞态更新均带原phase、version、binding条件，winner SUCCEEDED永不被PENDING/REJECTED覆写。未知不写工单/task/submission。

## 5. 原操作读取、类型恢复与安全VO

新增ReceptionOperationMapper `selectScoped(entityNo,storeNo,operationNo)`，SQL包含tenant_no='MAIN'/entity/store/opNo/deleted=0（SELECT无FOR UPDATE）；GET先当前BOOKING_VIEW或MANAGE后查它，存在即按action分派，不存在才走原appointment `db.operation`及scope核验。无prefix推断、无catch NOT_FOUND/SQL异常fallback；跨tenant/store行均不能进入投影。

GET严格纯读，无owner/RPC/新票据/推进。OperationVO.state是真实操作状态：RECEIVED/RESOURCE_PENDING/ARRIVAL_PENDING/OPEN_PENDING统一PENDING；SUCCEEDED/REJECTED同值。action使用实际持久action（CLAIM_GUEST/CHECK_IN/RECEPTION_EXCEPTION/OPEN_WORK_ORDER/RECEIVE_WALK_IN），不把异常业务result.state=PENDING_REVIEW伪作operation.state。appointmentNo/workOrderNo仅原列；failureCode仅安全已持久码，不回显proof/计划/reason/guest token。

恢复Service原签名保持，reception项先CUSTOMER+BOOKING_MANAGE，然后：

| action | 用户恢复调用 | 投影/限制 |
|---|---|---|
| CLAIM_GUEST | 真实 `GuestRegistrationServiceImpl.recoverClaim(entity,store,actor,operationNo)` | GuestClaimVO.operationState/recoveryAction，原result不重认领；REGISTERED需REQUIRES_FRESH_IDENTITY_PROOF |
| RECEIVE_WALK_IN | `ReceptionCoordinator.recoverWalkIn(...)` | §4原行续行，PENDING无工单，成功WorkOrderVO来自C写体 |
| OPEN_WORK_ORDER | `WorkOrderCoordinator.recover(...)` | 原OpenPayload/identity/hash；appointment原REPLACE沿链接；unassigned调用原unassigned准备并严格原scopeHash相等；不接收新req/proof |
| CHECK_IN / RECEPTION_EXCEPTION | 原本是本域同TX完成，无持久中间窗口 | 成功/拒绝只读原结果；异常残留PENDING不给假成功、不代造proof，回REQUIRES_FRESH_IDENTITY_PROOF |
| 未知action/endpoint组合 | failclosed CONFLICT | 不把任意JSON强转成一个成功VO |

OPEN_WORK_ORDER的第二员工恢复不能走原key新namespace查询。新增recover负责scoped原行+当前授权后直接resume原行；其中已存在REPLACE的校验从`actor.equals(changed.actorNo)`改为“changed关联原outer及原操作namespace/原derived key/hash、scope/source/冻结bytes全部相等”，真实执行仍传当前actor。首次内部REPLACE准备锁内外层检查亦不再把“当前actor等于原outer.actor”当作恢复授权；改为当前授权在锁外已核、锁内重核同一原outer完整binding/payload/phase，普通预览own仍不放宽。与第二员工有关的变更只在有真实原outer绑定的内部路径，公开普通预约own规则保持。

recoveryAction冻结：成功/拒绝null；一般待用户续行AUTHENTICATED_OPERATION_RECOVERY；未知accept同样原号用户恢复（原资源GET仍原AUTHENTICATED_SAME_KEY_REPLAY语义）；RESOURCE_RELEASE_REQUIRED对应AUTHENTICATED_CANCEL；ARRIVAL_REQUIRES_RESCHEDULE对应REQUEST_RESCHEDULE；claim待proof沿既有REQUIRES_FRESH_IDENTITY_PROOF。GET不能靠调用恢复方法求action。

原预约路径保持原OperationVO五字段语义及BOOKING权限；增加尾字段为null。D不借泛用恢复给普通预约新加CUSTOMER要求；资源CREATE若是048原链接，恢复必须先分派048外层而非绕开其完整绑定。

## 6. 后台纯对账、公平扫描与Job

新增 `ai.neargo.shop.beauty.service.impl.ReceptionRecovery`；新增 `ai.neargo.shop.beauty.job.ReceptionRecoveryJob implements JobHandler`，name=`beauty-reception-recovery`。BeautyReceptionConfiguration沿现有@ConditionalOnBeautyEnabled导入；job Bean和JobDeclaration均@Profile("api")，声明沿现有AppointmentRecovery配置形态：cron `0 * * * * *`、enabled=true、60/120秒及原两个boolean值；描述明确“仅对账原资源，不接受新命令、不自动到店开单”。无内置timer，无伪staff。顶层store→job.config迁移及beauty域登记仍Task60。

Mapper拟增：`List<BtyReceptionOperation> selectResourceRecoverable(LocalDateTime now)`、`int claimResource(String entityNo,String storeNo,String operationNo,long version,LocalDateTime now,LocalDateTime until)`。唯一SQL筛选在LIMIT50之前：

- r.tenant_no='MAIN'/deleted=0，r.action=RECEIVE_WALK_IN，r.endpoint=BAPI-048，r.phase=RESOURCE_PENDING；due next_retry_at非空≤now、lease null或<now。
- JOIN appointment op：同tenant/entity/store、r.appointment_operation_no=op.operation_no、r.appointment_no=op.appointment_no、op.deleted=0、action=CREATE、receipt非null；receipt JSON必须含完整非空binding（tenant/entity/store/sourceType/sourceNo/action/commandNo/sourceRevision/commandHash/planHash），scope/source原预约一致，action为RESERVE/CONFIRM；匹配原Accept.scope/source/action与plan。JOIN原preview按tenant/entity/store/previewNo及其plan_hash，要求receipt.binding.planHash=preview.planHash、receipt.frozenPayload=accept.frozenPayload=preview.frozenPlan；receipt状态仅ACCEPTED/SUCCEEDED/REJECTED，binding字段类型/非空与原scope/source严格校验。SQL排除op.state=RELEASE_REQUIRED及outer.failureCode=RESOURCE_RELEASE_REQUIRED；REJECTED仅原RESERVE拒绝且root.holdNo IS NULL。不可对账/缺receipt项不得先占50再Java过滤。JSON有效由列约束保障；结构异常failclosed排除并单独报告，不改原payload。
- ORDER BY r.next_retry_at ASC,r.id ASC LIMIT 50。claim含同tenant/entity/store/opNo/version/phase/due/lease CAS，lease30秒，version+1；claim成功才计一次扫描。

claim TX只改外层lease，不等待root或调用owner，提交后锁释放。后台唯一业务外调 `AppointmentCoordinator.recover(originalAppointmentOp)`→ResourceBookingPort.reconcile/reconcileHold；没有SecurityContext、merchant/customer/guest.get、accept/execute/新CONFIRM权限。recover本身只完成原已接受命令，RESERVE成功会把原op转为待接受CONFIRM且receipt=null，下一批因SQL过滤排除，用户必须继续。原op已被AppointmentRecovery提前终态也允许此D扫描对账outer（已存在receipt且SUCCEEDED或RESERVE/REJECTED无hold），不能仅筛op.state=PENDING。SQL在LIMIT前排除op.state=RELEASE_REQUIRED或outer.failureCode=RESOURCE_RELEASE_REQUIRED，因为这类原intent已完成、需要当前人CANCEL/RELEASE，不再是可后台reconcile项；用户显式恢复负责识别并给出安全待协调原因。

对账写outer另起短TX，按root→fence→current→outer锁序重读，核lease与原完整binding，SUCCEEDED只能→ARRIVAL_PENDING/nextRetryAt=null；不得签到/OPEN_PENDING/建单。REJECTED且明确无残余hold可置outer.REJECTED；RELEASE_REQUIRED保持RESOURCE_PENDING/PENDING及failureCode=RESOURCE_RELEASE_REQUIRED，保留原hold/fence事实，移出自动扫描（nextRetryAt=null）交当前人协调。若仍PENDING且receipt被清则仅等用户，不能接受新命令。

finally：仅在仍归本次lease且outer未被用户推进/终态时，未终态nextRetryAt=now+30秒，异常=now+60秒，显式UpdateWrapper清lease/null字段（不能依赖updateById忽略null）；ARRIVAL_PENDING/REJECTED/SUCCEEDED或需人工release保持nextRetryAt=null，清lease。成功/失败CAS不得覆盖用户并发SUCCEEDED或新phase。每项异常单独捕获安全类名+operationNo，无payload，继续后方；return int=本次成功claim并尝试对账项数，与原AppointmentRecovery统计一致。

65待用户+1eligible必须在同schema不同业务号一次fixture中验证eligible第一批处理；60eligible均未终态时第一批50推进retry，第二批至少后10先被取，未领取项不饥饿；活lease在LIMIT前排除。60项保留原独立binding/commandNo，不能用同一业务号假造公平性。

## 7. 资源未知、拒绝及技术裁决点

- 无receipt accept响应丢失：用户原号读取固定appointmentOperationNo/原acceptPayload，当前actor重放原accept，owner回同commandNo。重启只重建Service/上下文，数据库不重置。后台不处理该项。
- 已receipt且confirm响应丢失：优先调用原Coordinator.recover查询/reconcile原binding；终态已可核即对账完成，不新accept。仍ACCEPTED时用户原Coordinator.advance仅继续同commandNo的既有执行，绝不创建替代CONFIRM。原Coordinator默认可幂等execute原命令，这是既有saga行为；D不复制其分支或签发后台权限。
- RESERVE明确拒绝、root无hold且无未知副作用：outer.REJECTED，安全failureCode=RESOURCE_REJECTED，绝不创建工单。CONFIRM拒绝：outer.PENDING/RESOURCE_RELEASE_REQUIRED，不能当资源已释放。
- **根已裁决（2026-10-23）**：保留fence指永久fence row+原hold/未决占用。UNKNOWN仍保留active_intent_no/current，不允许TTL清除；明确终态且intent.phase=3/terminal hash已核后，既有releaseReconciled清active pointer合法，才能当前授权prepare CANCEL/RELEASE。D不重挂已完成intent，不改BM03已审协议。测试分别断言这两类状态，不能用“fence仍有行”替代UNKNOWN active pointer断言。
- 原048遇到RELEASE_REQUIRED后，保持原appointmentOperationNo（拒绝CONFIRM的CREATE op），failureCode=RESOURCE_RELEASE_REQUIRED、nextRetryAt=null；后台资格SQL直接排除。用户通过现有真实AppointmentService.cancelAppointment(entity,store,actor,appointmentNo,CancelAppointmentReq,key)沿原hold显式协调；其原prepare/advance执行RELEASE，D不新建释放saga，也不替人自动取消。
- **释放后048收口**：显式recoverWalkIn发现该状态，锁外从原CREATE.acceptPayload.target取得原holdNo，查同scope/appointment、action=CANCEL、state=SUCCEEDED的真实release op（其Accept.action=RELEASE且target.holdNo=原hold）。新增AppointmentCoordinator只读取证辅助`ResourceBookingPort.Hold releasedHold(BtyAppointmentOperation release, String expectedHoldNo)`，内部复用原verify与terminalHold(receipt,false)：核原Accept/Receipt完整Binding、SUCCEEDED/RELEASE、原target、result.holdNo/status/ownerRevision，当前真实actor queryHold回RELEASED。只读取证，无accept/execute；根CANCELLED、current空、永久fence无active，原release intent.phase=3且terminal hash与receipt fingerprint精确相等。随后root→fence→current→outer短TX重核原048/op/hold/释放op/intent终态一致、无工单/actual task，才置原048.REJECTED/failureCode=RESOURCE_REJECTED，result为原号/原selectedStartAt的REJECTED WalkInVO，nextRetryAt=null，记录安全拒绝事实。
- 没有SUCCEEDED RELEASE、queryHold不可达/非RELEASED、只有手工清pointer/root状态、release绑定/target/terminal hash不合或其它原命令仍UNKNOWN：继续PENDING/RESOURCE_RELEASE_REQUIRED，保留原hold与原链，不把CANCELLED字符串当资源释放证明。当前cancel尚在PENDING则由它的原操作恢复，不重建；D recover只观察其原号结果。新测试`releasedOriginalHoldClosesRejectedWalkInOnlyAfterVerifiedRelease`覆盖成功收口、释放响应未知/错hold/手工清指针仍PENDING及零新D释放命令。
- confirm响应丢失沿同原命令：048恢复先原Coordinator.recover对账，已终态仅query/reconcile；仍ACCEPTED未执行时当前用户可原Coordinator.advance继续同commandNo，不能新建替代CONFIRM。该边界遵循已有saga，并在未知测试分别记录accept/execute/query/reconcile次数。
- 其它选择均在既有Task55授权内，无新业务裁决或新owner接口。

## 8. AC → 真实测试与RED/GREEN计划

本阶段没有运行任何测试，以下“新增”方法尚未存在；现有回归名均由真实源码查出，禁止把规划当结果。新 `WalkInRecoveryIT extends ReceptionFixture`（不是继承其他IT），继续真实MySQL/Mappers/本域TX/outbox；复用ControlledOwner并最小加当前Actor调用记录/故障开关，所有RPC断言无本域事务。为公平性在一个测试方法内批量seed同schema；不120次schema初始化。

| AC | 拟新增 WalkInRecoveryIT 方法 / 断言 |
|---|---|
| 原子prepare/同原号并发 | atomicCreatePreparationRollsBackWithoutHalfLinks：在preview/root/fence/current/op/outer更新处故障，全回滚；固定候选号仍RECEIVED；并发第二员工仅一组root/preview/op/Accept |
| 时间合同 | defaultStartIncludesEveryBeforeBufferAndOffset：null求严格未来最早网格；指定同边界、off-grid/超bookingAhead拒，含负delta/后续项目before更大的输入 |
| 完整服务范围 | confirmedWalkInCreatesOneAppointmentScope：真实展开qty/多定义/容量多份同resource；单appointment/order/context/submission/逐项目task；OPEN/UNBOUND/PENDING及执行子行空；同TX result必须为完整WalkInVO（注入Outbox/结果序列化失败全部回滚），成功重放字节语义不变；WorkOrderQuery B/C可读048工单，错action/endpoint/原op/context/root/identity链仍NOT_FOUND |
| accept/confirm未知+重建 | unknownAcceptAndConfirmRecoverOriginalNumbersAfterRestart：原外层/root/preview/op号、key/payload bytes、commandNo恒定；未知无order/task；confirm终态仅原binding查询 |
| 当前第二员工/无冒用 | authorizedSecondEmployeeContinuesWithoutImpersonation：撤原人权限，第二员工原号恢复从RECEIVED/无receipt/ARRIVAL_PENDING/OPEN_PENDING各窗口，owner所见实际actor；原namespace/hash/canonical不变 |
| 成功幂等/授权 | originalReplayChecksRequestNamespaceAndCurrentGrants：相同原键原VO、改参CONFLICT、同号不同realm/kind/异店/tenant拒、当前撤权拒；异键新直客不复用他人scope，单预约异键转单竞争保持C唯一守卫 |
| 冻结不重计划 | expiredSelectedPlanRequiresRescheduleWithoutRematching：推进时钟后原start/plan/key/号不变、PENDING/ARRIVAL_REQUIRES_RESCHEDULE、零新accept/单/tasks |
| 明确拒绝/保守release | reserveRejectionIsFinalButConfirmRejectionRequiresRealRelease；releasedOriginalHoldClosesRejectedWalkInOnlyAfterVerifiedRelease：reserve无hold才REJECTED，confirm保hold/fence原绑定+PENDING/RESOURCE_RELEASE_REQUIRED；UNKNOWN指针保留/已对账终态指针合法清除两断言；真实CANCEL/RELEASE验证后才REJECTED，手工清指针/错hold仍PENDING |
| RPC锁外 | allOwnerCallsRunOutsideLocalTransactions：identity/access/catalog/resource/personnel/schedule等均assert无activeTX；Outbox例外仅本域写入 |
| 纯读/原号安全分派 | operationQueryIsPureScopedReadAndRecoveryDispatchesRealAction：GET零owner推进，claim四参恢复/旧预约回归/异常PENDING_REVIEW投影仍operation SUCCEEDED；SQL失败不得fallback |
| 后台边界 | backgroundReconcilesOnlyAcceptedOriginalCommands：缺receipt/RECEIVED/ARRIVAL/OPEN/claim/031排除；零guest.get/accept/execute/签到/工单；RESERVE终态后停待用户CONFIRM |
| 公平性 | recoverySkipsSixtyFiveUserBlockedRows；resourceRetriesRotateBeyondFirstFifty：同schema 65+1/60业务号，lease/due/排序/30秒与异常60秒、并发用户SUCCEEDED不回退 |
| C原号第二员工 | openOperationRecoveryRetainsOriginalNamespaceAndCanonical：已pending C原OpenPayload、REPLACE绑定/UNASSIGNED两路，不重造原hash，不因claim/目录变更静默改变scope |

真实HOST：在既有 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautyReceptionIdentityIT.java` 新增 `walkInConsumesRealCustomerGuestAndScopedClaimReceipt`，复用当前fixture与真实member cr1/guest/claim/receipt；新增048消费及第二当前manager原号恢复、GUEST历史不回写/本人SQL归属。资源准入仍显式ArrivalHoldFixture（须最小扩展支持真实Matching所需catalog/personnel/schedule/candidate事实边界，不伪称真实容量owner部署）；beauty-core不得引入member实现生产依赖。

新增用例按一个行为一轮RED→最小实现→GREEN分批：DTO/权限与原子CREATE；原资源两未知；内部签到/单一开单；原号分派/第二员工；后台与公平性。编译缺符号/fixture/schema/连接失败独立记setup错误，不作业务RED；新DTO或方法先让源码可编译的测试边界落地再记录行为断言失败。一次有效消融选择“原子链接”：临时去掉同TX outer绑定（保留其它检查），`atomicCreatePreparationRollsBackWithoutHalfLinks`应出现真实半链接断言失败，再恢复同selector GREEN；如该消融被更早检查拦住则不计，选仅移除后台LIMIT前receipt资格过滤并令65待用户项先占队，公平性用例必须行为RED。报告分别列前置TDD RED与后验消融。

### 8.1 已存在回归selector（非旧草案名）

- B：`ReceptionBusinessIT#checkInKeepsScheduleHoldAndNoTasks+replacementUnknownKeepsOriginalCommandAndFence+replacementBeforeBufferMustAlsoRemainInFuture+receptionRetainsRepeatedResourceUnits+checkInRacesCancelOnIndependentConnections`。
- C：`WorkOrderScopeIT#replacementAcceptUnknownReusesDurableOperationAfterRestart+replacementExecuteUnknownReusesDurableOperationAfterRestart+pendingGuestIntentRetainsCanonicalIdentityAfterClaim+successfulConcurrentReplaySkipsOldRevisionAndDoesNotRegressPhase+preservesExpandedItemsAndEveryResourceCapacityUnit+scopedReadRequiresOriginalSucceededOperation+appointmentWithOrphanActualTaskRejectsOpening+fullAppointmentWithOrphanActualTaskRejectsOpening`。
- A：`GuestClaimReceiptIT#sharedClaimCommitThenLocalRollbackRecoversReceiptWithCurrentEmployee+unknownClaimKeepsOriginalIntentAndRegisteredNeedsFreshCurrentProof+receiptCannotRebindAndSuccessReplayNeedsCurrentPermissionButNoFreshTicket`。
- BM03：`AppointmentBusinessIT#lostAcceptResponseReplaysIdenticalPersistedKeyAndPayload+recoveryReconcilesOriginalWithoutMintingUserThenAwaitsAuthenticatedConfirm+anotherCurrentlyAuthorizedManagerRecoversRevokedInitiatorsExactCommand+confirmRejectionRequiresActualReleaseAndNoMoneySourceMayBypassProof+guestCreationReplaysOriginalOperationAfterClaim`。
- 严格升级：`BeautySchemaMigrationIT#migratesValidatesAndDoesNotRepeat+v6AssignmentUnitsUpgradeWithoutRewrite+v7AssignmentIndexDriftCannotPassValidation+validV5IdentityHistoryUpgradesWithoutRewrite+droppingV6GuardCannotPassValidation`（方法名v6AssignmentUnits...真实存在，内容是V6→V7容量份数升级，不改名伪造v1ToV7）。正式最终补全BeautySchemaMigrationIT整类以确认当前V1→V7/全部漂移面。
- HOST既有：`BeautyReceptionIdentityIT#workOrderOpeningUsesRealCustomerAndGuestFacts+omittedGuestIdentityUsesCurrentOwnerAndScopedReceipt+realClaimUnknownRecoversImmutableReceiptUnderCurrentManagerWithoutOldProofOrImpersonation`；与新方法一起跑。
- Wiring：`BeautyConditionalWiringTest`真实存在`workOrderWiringHasNoCycle`、disabled/default/invalid部署用例；新增reception Job api profile/无循环反例后跑整类。
- 架构：BeautyArchitectureTest位于beauty-server的architecture包，`moduleGraphHasNoCycles`等扫插件模块；不是core/scenario的不存在类。后端BackendI18nParityTest与ConditionalBeanWiringTest在shop-app/arch；既有OpsPoints/V506/[beauty,store]欠账归60，不放宽棘轮。

### 8.2 实施后命令模板（现在不执行）

```bash
# 每批先只读当前Colima /proc/meminfo，MemAvailable >= 512MiB；不足停止本批并报告。
# 不启BEAUTY_TEST_LOW_MEMORY=1，不用128MiB校准；cached mysql:9.7.2默认DISK64M。
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  BEAUTY_TEST_DISK=1 BEAUTY_TEST_LOW_MEMORY=0 BEAUTY_TESTS='WalkInRecoveryIT' \
  node scripts/verify-beauty-persistence.mjs
# 回归用上表Class#method+method作为BEAUTY_TESTS；类之间逗号。确认实际Tests run统计。
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  BEAUTY_TEST_DISK=1 BEAUTY_RECEPTION_TESTS='BeautyReceptionIdentityIT#walkInConsumesRealCustomerGuestAndScopedClaimReceipt' \
  node scripts/verify-member-guest-registration.mjs --beauty-reception
# host前先离线install插件当前产物，不测旧.m2：
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 \
  -f backend-plugins/shop-industry-beauty/pom.xml install -DskipTests
```

单UUID容器串行、pull never、随机localhost、TCP readiness、finally只清自有；不删他人/重启VM/改全局配置。现runner默认DISK64M没有512MiB gate，实施前采用同批明确只读preflight或给runner补默认gate，不能依赖旧low-memory 160MiB gate。日志完整放/tmp task55d专属目录并在report登记。最终必要plugin reactor含server架构/wiring、相关backend host与i18n；按变化跑SQL/生成/Java规则，不无关全backend反复重跑。无HTTP/BM04部署成功声明。

生成物：同步真实 `docs/technical/design/schema/beauty-java-method-design.json`、`beauty-api-design.json`、必要database描述（ready7/27不变），运行`python3 scripts/gen-beauty-java-method-design.py` / `gen-beauty-api-design.py` / `gen-beauty-database-design.py`及各--check；`node scripts/check-sql-portability.mjs`、`git diff --check`。不运行会重写冻结DDL的schema生成器；V1–V7逐文件无diff/SHA核对。新错误若需要ErrorCode按实际新增并补三语，不把failureCode私自当ErrorCode/国际化码。

## 9. 文件责任与准出

本设计阶段owned仅 `.superpowers/sdd/beauty-backend/task-55d-design.md`、`task-55d-report.md`。以下是技术放行后产品实施候选清单，最终report必须逐文件实际对账，不代表本轮已改：

| 模块/完整包 | 类/文件 | 职责 |
|---|---|---|
| beauty-api / ai.neargo.shop.beauty.service | ReceptionService | 追加真实048 |
| beauty-api / ai.neargo.shop.beauty.dto | ReceiveWalkInReq、WalkInVO、OperationVO | 固定DTO和兼容构造器 |
| beauty-core / ai.neargo.shop.beauty.service.impl | ReceptionServiceImpl、ReceptionCoordinator、ReceptionPreparation | 委托、直客状态机/受限内部签到、冻结原payload |
| 同上 | AppointmentMatching、AppointmentPreparationService、AppointmentCoordinator | 共用匹配/最早future网格、单CREATE原子prepare/链接、复用verify/terminalHold取证真实RELEASE；无资源saga复制 |
| 同上 | WorkOrderCoordinator、ReceptionReassignment | openReception复用C写体、原号C恢复真实当前actor；仅必要绑定核验 |
| 同上 | ServiceOperationQueryServiceImpl、ServiceOperationRecoveryServiceImpl、ReceptionRecovery（新增） | scope分派/只读投影/有界对账 |
| beauty-core / ai.neargo.shop.beauty.mapper | BeautyMappers.ReceptionOperationMapper | scoped read/eligibility/lease SQL，工单成功父链允许048 |
| beauty-core / ai.neargo.shop.beauty.config | BeautyReceptionConfiguration、BeautyAppointmentConfiguration | 强注入DAG、原分派器配置/Job与api声明 |
| beauty-core / ai.neargo.shop.beauty.job | ReceptionRecoveryJob（新增） | 已有JobHandler能力，无timer |
| beauty-core / ai.neargo.shop.beauty.scenario（test） | WalkInRecoveryIT（新增）、ReceptionFixture、BeautyConditionalWiringTest | 真实native行为/fault/当前actor/公平性/装配 |
| backend shop-app / ai.neargo.shop.scenario（test） | BeautyReceptionIdentityIT | 真实member消费新链 |
| scripts | verify-beauty-persistence.mjs / verify-member-guest-registration.mjs（仅确有需要） | 默认512MiB preflight/精确选择；不用失败128MiB路径 |
| docs/technical/design/schema及生成输出 | beauty-java-method-design.json、beauty-api-design.json、beauty-database-design.json及生成器实际输出 | 契约/方法/数据库对账，无新迁移 |

当前不存在RemoteReceptionService实现，无需新建空Remote/Controller；若后续实编发现真实接口实现被增量破坏，只作最小failclosed兼容并明确59未交付，不计default能力。本D不改根mainTDD/progress/brief/总体design/review，不push/merge/deploy；最终先给未commit审查tree/diff和owned名单，根放行后精确产品commit，独立review通过前不标55D/Task55/BM04完成。

## 10. 根技术放行补充（实施绑定）

当前恢复actor、原outer.actor、linked预约op实际creator可各不相同；历史按完整原绑定核验，当前仅沿既有AppointmentAccess/ReceptionAccess，不另造realm/kind白名单。selectOpenedForWorkOrder动作/endpoint精确配对OPEN_WORK_ORDER→BAPI-052/054、RECEIVE_WALK_IN→BAPI-048。后台SQL仅排队资格，完整Java binding/hash/receipt/intent及写前锁内版本重核仍必须。根已放行，后续无需第二设计门；不提交产品直至根审查。
