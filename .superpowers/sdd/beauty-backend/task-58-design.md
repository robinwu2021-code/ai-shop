# Task58 Source submission, observed binding, and per-source bill design

Status: DESIGN_READY for root technical review only. No implementation, test, build, SQL execution, staging or commit.
DESIGN_BASE: eb9ec7734334214fe04d5b7d4e6f2593cc2cafd2.
Accepted Task57 product: 741b38178f01a6f4159fc618d57a45d75a3601e0.
Worktree: /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex; branch codex/beauty-foundation.
Spec: .superpowers/sdd/beauty-backend/task-58-brief.md, the sole Task58 requirement source.
This document and task-58-report.md are the only first-phase owned files. IMPLEMENTATION_BASE is not issued.

> Execution after root technical release uses superpowers:executing-plans, one implementer, no subagents.
> The user already authorized the task. This design gate is root technical review, not a new generic permission request.

Goal: turn explicit currently authorized merchant selections into durable original source intents, bind verified
ordinary service source quantities once, and report authentic owner money without giving a merchant consumer authority.

Architecture: beauty owns selection, frozen task scope, observed allocation, recovery and trace. Shared checkout owns
quote/consumer confirmation/ordinary order; payment owns collection and receipt. A new local source observation fence
protects the highest owner revision and blocks late observations before the existing line guard/work-order/task CAS.

Tech: existing Java21, Spring, MyBatis-Plus Mappers, primary transaction manager, MySQL native tests, beauty V8 candidate.
No Repository, replacement trade/funds ledger, new consumer authentication, automatic compensation, or background poller.

## 1. Actual source audit, not historical draft signatures

Paths below are relative to the assigned worktree; line numbers are DESIGN_BASE lines.

| Actual file:line | Observed contract / design consequence |
|---|---|
| beauty-api/.../service/WorkOrderService.java:8-16 | Only openWorkOrder/getWorkOrder/getMyWorkOrder exist. submitServices/getBill below are additions, not delivered Task55 methods. Full prefix: backend-plugins/shop-industry-beauty. |
| beauty-core/.../service/impl/WorkOrderCoordinator.java:352-413 | One transaction creates OPEN root scopeRevision1, PENDING COUNT tasks bindingRevision0, immutable context, and OPEN/RECEIVED draft. Draft has no owner order or payment. |
| same:384-392,447-464 | Context freezes task definitions and original scope hash; draft has schema1/original revision/hash/identity/taskRefs. No replacement of this historical draft. |
| beauty-core/.../service/impl/WorkOrderPreparation.java:135-178,180-247 | Actual frozen definition/hash/steps validation; unassigned reads real catalog outside transactions. New added items use this current admission once; old tasks retain old snapshots. |
| same:248-319 | Local head locks/rechecks are reusable for newly admitted scope. No need to rematch original tasks or resource holdings. |
| beauty-core/.../service/impl/WorkOrderQuery.java:37-77 | Validates original successful reception operation; currently hardcodes UNBOUND and canStart=false. Add local binding projection, retain false. |
| beauty-core/.../policy/ReceptionIdentity.java:24-40,58-62 | Guest owner REGISTERED revision1 or CLAIMED revision2; strict cr1 resolution returns USER, not staff/BTG. |
| beauty-core/.../policy/CustomerOwnership.java:13-24; mapper/BeautyMappers.java:404-415 | Existing immutable scoped claim receipt is authorization bridge; receipt must match tenant/entity/store/guest/user, not mutable guest guess. |
| beauty-core/.../policy/AppointmentAccess.java:22-39,61-63; policy/ReceptionAccess.java:19-29 | Current MERCHANT MCH/USR and current grants required before IO/TX. Numeric actor equality without realm/kind is insufficient. |
| beauty-core/.../service/impl/ServiceOperationQueryServiceImpl.java:19-48 | Reception then appointment dispatch. New submission dispatch is added without changing existing signatures or endpoint/action validation. |
| beauty-core/.../service/impl/ServiceOperationRecoveryServiceImpl.java:29-66 | Current merchant authorizes recovery; original walk-in/reception dispatch retained. No scanner authority is reused to buy. |
| beauty-core/.../mapper/BeautyMappers.java:390-450,495-519 | Explicit scoped root/task reads and advanceScope CAS. Locks alone do not verify context/original reception or full appointment parent. |
| same:595-621 | switchBindingRevision validates complete quantity per task, but permits PENDING/IN_SERVICE/PAUSED. New service restricts first binding to PENDING, never-started, done=voided=0 and bindingRevision0. |
| same:715-794 | Submission unique intent/status/version/nextRetryAt/frozen_command exist; current progress SQL has only RECEIVED/SOURCE_PENDING/RECOVERING/BOUND/REJECTED. OPEN draft is not scheduled. |
| same:798-824 | Existing binding double-parent scoped read. Add current/history queries; do not loosen it. |
| same:829-905 | Genuine local canonical-line quantity guard, allocate CAS and immutable binding history. refresh refuses source_qty < allocated_qty: cannot alone retain refund blocking watermark. |
| same:857-885 | Existing release is conservative and checks all historical references. Task58 does not invoke release, void or switch an existing binding. |
| beauty-core/.../service/impl/AppointmentPersistence.java:53-60 | Existing TX helper is REQUIRES_NEW/READ_COMMITTED and read() removes data scope, not a DB snapshot. New local snapshot helper uses its own explicit read-only RR transaction. |
| beauty-core/.../config/BeautyReceptionConfiguration.java:36-106; BeautyAppointmentConfiguration.java:108-135 | Actual local Bean graph, lazy owner ports and original query/recovery assembly. No existing remote WorkOrderService or CheckoutPort implementation in beauty-channel. |
| beauty-core/.../config/BeautyDataScopeRegistration.java:17-19,30-34 | Submission/binding/guard/task/log already registered; new observation table must register MERCHANT -> entity_no. Every scoped SQL read and write runs without implicit data scope after service authorization. |
| beauty-core/.../config/BeautySchemaReady.java:4-8; BeautyPersistenceConfiguration.java:29-40 | Actual required ready version7. |
| beauty-core/.../config/BeautySchemaValidator.java:27-46,71,79-95 | Frozen hashes and actual 27-table manifest (16+2+4+1+4); V6/V7 change constraints/index only. |
| beauty-core/src/main/resources/db/industry/beauty/V1__beauty_p1_tables.sql:356-415,446-474 | Existing intent unique namespace hash; permanent binding unique task/revision/source/line; guard unique entity/source/line independent of revision and store. |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/CheckoutPort.java:22-55 | Eight actual methods: prepare/readQuote/confirm/place/findOperation/source/initiatePayment/findPayment. |
| same:192-248,260-280 | PREPARE requires strict customerRef, original key, scope revision/hash; lines have unique SKU and clientLineRef, integer COUNT1..999, <=20 lines. |
| same:283-328,418-504 | Quote/result/source actual types; source line has orderedQuantity/refundableQuantity, not reservation/consumption. Source amount received/outstanding nullable, due primitive. |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/OfflineReceiptPort.java:22-27,33-77 | confirm is actual full received cash declaration under current HOST cashier, find is original fact query. Task58 does not reimplement/automate confirmation. |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java:193-318,1208-1222 | PREPARE replay binds original B actor realm/kind/no, original key AND exact customerRef hash. Current other B may query operation but may not replay as original B. |
| same:322-343,669-708 | Merchant readQuote/findOperation use original OperationRef, current scope/grants; current buyer quote read uses authentic own identity. |
| same:784-885 | source rejects caller TX, does financial IO first, then operation-lock-first RR snapshot, checks order/sub/buyer/store/original sourceOperationNo/authority/item mapping. Do not alter. |
| same:887-975,1017-1053 | Gross receipt minus refund yields net received; stable historical ordered quantity, refundable qty=qty-refundedQty (0 when closed). BLOCKED is not current execution permission. |
| same:976-1016; backend/shop-base/.../spi/settle/SettlePort.java:159-160 | Source hash contains financial receipt (whose record has no observedAt), order/sub/item/refund work and canonical receipt identities. Neither CheckoutSourceFacts.observedAt nor PaymentFact.observedAt is hashed. Same unchanged facts keep same pair. |
| same:1057-1136 | Canonical paymentOperation/outTradeNo/receiptNo owner facts, aliases coalesce, proved unsent rejection skipped; contrary real late/duplicate receipts yield conservative UNKNOWN. |
| same:1176-1199 | UNKNOWN preserves owner last pair, has no consumable lines/receipts; dueMinor can be fallback0. Beauty must return nullable unknown due. |
| backend/shop-app/src/main/resources/db/migration/V539__checkout_source_foundation.sql:35-48 | Permanent unique (tenant,caller,operation), (tenant,order), (tenant,subOrder); deleted=0. One canonical sub-order belongs to exactly one checkout revision domain. |
| backend/shop-core/.../order/service/create/CheckoutQuoteGuard.java:123-174; service/impl/OrderServiceImpl.java:2321-2322 | Stable line CL business number and original op->order/sub mapping committed with consumed confirmation. sub.sourceOperationNo is checked again by source. Payment aliases do not create another checkout operation. |
| backend/shop-core/.../policy/CheckoutAccessPolicy.java:46-73,80-118,125-169 | True C requires current token-store session and active USER. B scope/namespace/grants are separately checked. |
| backend/shop-core/.../api/mp/MpCheckoutController.java:42-103 | Existing HOST C quote/confirm/order/payment/original lookup endpoints. Handoff uses operationNo, never a copied C token. |
| backend/shop-core/.../api/biz/BizOfflineReceiptController.java:48-100 | Existing HOST CASH_RECEIVE confirmation and ORDER_VIEW original receipt queries. |
| backend/shop-core/.../policy/ManagedServiceCompletionPolicy.java:17-24; order/mapper/StatusCasMapper.java:47-60 | SERVICE_EXECUTION legacy completion remains blocked at minimal CAS. No shared service reserve/consume/execution-permit owner exists in delivered code. |
| backend/shop-app/src/test/java/ai/neargo/shop/scenario/CheckoutFlowFixture.java:43-175 | Actual trade/member/catalog/native fixture and real token-store current-C helper available. |
| backend/shop-app/.../scenario/CheckoutSourceFactsFlowTest.java:35-57,60-128 | Existing source UNKNOWN/historical money/refund observations; alone does not test beauty consumption. |
| backend/shop-app/.../scenario/BeautyReceptionIdentityIT.java:67-107 | Real member/claim/receipt current-grant fixture; resource admission explicitly fixture. Reflection avoids optional beauty compile dependency. |
| scripts/verify-checkout-payment.mjs:12-24,39-89,92-111 | Exact V539/V540 pins, 512MiB check, own cached mysql9.7.2/disk64M, fresh known-class report checks; unknown-selector issue remains Task60 M1. |
| scripts/verify-beauty-persistence.mjs:20-27,54-70,88-109 | Actual core native runner supports BEAUTY_TESTS. Existing low-memory branch is NOT the approved Task58 mode. |
| docs/technical/design/美业系统-API清单.md:185,209-211,355 | 056/071/072/073/137 remain candidates. No invented source-confirmation endpoint: 056 EXISTING mode is the explicit source-selection confirmation. |
| docs/technical/design/美业系统-Java类与方法清单.md:685-698,768-773,1061-1076 | Historical proposed service/job/event names; use actual local contract below, no fake scheduler or event source. |
| docs/technical/design/美业系统-领域对象与聚合边界.md:74,87,130,157 | Submission orchestrates; TradeBinding owns allocation relation; bill is owner projection, not pricing or settlement authority. |
| .superpowers/sdd/beauty-backend/task-55-design.md:220-239; task-55c-report.md:205-206,223-246; task-55d-report.md:153-167 | Accepted initial scope/identity/original intent and final C/D corrections consumed. Historical design paragraphs do not override actual 741b source. |
| .superpowers/sdd/beauty-backend/task-56-report.md; task-57-report.md:361-470 | Read scoped final owner corrections. No rerun simply to relearn earlier evidence; no claim those tests ran for Task58. |

The beauty abbreviations above expand to:
- beauty-api/... = backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty.
- beauty-core/... = backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty.
- backend/shop-core/... = backend/shop-core/src/main/java/ai/neargo/shop/trade.
- backend/shop-base/... = backend/shop-base/src/main/java/ai/neargo/shop.

## 2. Selected approach and hard boundaries

Use real shared owner contracts plus local observed allocation. A read-only availableQty check alone cannot arbitrate
two work orders; existing local guard supplies that atomic boundary. It cannot arbitrate a concurrent remote refund;
the source observation fence ensures no already-observed newer/UNKNOWN result is ignored. A refund occurring AFTER
the latest owner read may still leave a historical local binding. This is explicitly observed binding, not shared
atomic consumption or current execution authorization. canStart=false everywhere and BM05 stays closed.

Rejected: implement shared reserve/consume now (requires a new real cross-domain refund/execution protocol outside
58); retain only old guard (fails qty2/allocated1 -> refund0 -> late R1 allocate1); store a second order/funds ledger
(wrong owner). Root accepted observed-binding boundary and requested the durable watermark counterexample closure.

PREPARE requires USER ownership now. Unclaimed guest returns existing draft view with WAIT_GUEST_CLAIM or a
BEAUTY_SOURCE_CUSTOMER_REQUIRED error before a new economic submission; it never calls checkout.prepare.
After real claim and same-scope immutable receipt, a new explicit submission resolves the same original GUEST
context to its USER buyer without rewriting context/draft. Missing receipt remains blocked.

No cancellation/refund/replacement/execution API is added. New binding is only first binding of unstarted PENDING
tasks; existing nonzero bindingRevision is immutable in this task. No release on source refund, UNKNOWN, scope
change, timeout, bill read or local rejection. Source quantity reservations become conservative held history.

No public Controller/Remote/typed SERVER protocol work. Existing HOST checkout/C/cash routes are consumed as-is.
Standalone missing CheckoutPort fails explicitly; no production fixture or success provider is installed.

## 3. Local API and DTO contract

All new DTOs are records in beauty-api / ai.neargo.shop.beauty.dto. Public service interfaces remain in
ai.neargo.shop.beauty.service. The service validates even without HTTP; Task59 will add @Valid/@Validated wiring.
No caller tenant/entity/store/buyer/actor/money/consent boolean fields inside request bodies. Unknown fields reject.

~~~java
// Add to actual WorkOrderService; retain all three current signatures unchanged.
ServiceSubmissionVO submitServices(String entityNo, String currentStoreNo, String actorNo,
        String workOrderNo, SubmitServicesReq req, String idempotencyKey);
WorkOrderBillVO getBill(String entityNo, String currentStoreNo, String actorNo, String workOrderNo);

// New ServiceSubmissionService (local interface; no default success).
ServiceSubmissionVO submitForWorkOrder(String entityNo, String currentStoreNo, String actorNo,
        String workOrderNo, SubmitServicesReq req, String idempotencyKey);
ServiceSubmissionVO getSubmission(String entityNo, String currentStoreNo, String actorNo, String submissionNo);
RecoveryRequestVO requestSubmissionRecovery(String entityNo, String currentStoreNo, String actorNo,
        String submissionNo, RecoverSubmissionReq req, String idempotencyKey);
ServiceSubmissionVO recoverPending(String entityNo, String currentStoreNo, String actorNo, String submissionNo);

// New WorkOrderBillService; no dependency on WorkOrderService or SubmissionService.
WorkOrderBillVO getBill(String entityNo, String currentStoreNo, String actorNo, String workOrderNo);
BusinessTraceVO getTrace(String entityNo, String currentStoreNo, String actorNo, String businessNo);
~~~

| DTO | Exact fields / validation |
|---|---|
| SubmitServicesReq | long expectedRevision, long expectedScopeRevision, List<TaskSelection> tasks, List<AddedItem> additions, SourceSelection source. expectedRevision>=0, scope>=1, lists nonnull, combined selected COUNT1..10; neither implicit all-tasks nor empty selection. |
| nested TaskSelection | String taskNo, long expectedRevision, long expectedBindingRevision. Unique taskNo1..32, nonnegative revisions; only stored whole task qty (integer COUNT), no client qty override. |
| nested AddedItem | String clientItemRef, String skuNo, String definitionVersionNo, int quantity. Unique ref1..48, SKU/version1..32, qty1..10; expected currently admitted version mandatory. Expansion creates qty separate COUNT1 tasks using stable persisted task IDs. |
| nested SourceSelection | String mode, String customerRef, String payMode, String couponNo, Map<String,String> activityChoices, String remark, String checkoutOperationNo, String subOrderNo, Long sourceRevision, String sourceHash, List<ExistingLineSelection> lines. mode PREPARE or EXISTING only. |
| PREPARE branch | customerRef1..4096 strict real cr1, ONLINE/OFFLINE, coupon null or1..64, nonnull choices with bounded64 keys/values, remark<=500. Existing operation/sub/pair/line selections absent. Same raw proof used only during this call; only its digest is persisted/redacted. |
| EXISTING branch | operation1..64, subOrder1..64, sourceRevision>=1, lowercase64hex sourceHash, explicit nonempty lines covering exactly each taskNo/clientItemRef. PREPARE-only fields absent (choices empty). Each sourceLineNo1..64, no client money/buyer. |
| nested ExistingLineSelection | String selectionRef, String sourceLineNo. selectionRef is exact existing taskNo or added clientItemRef; one line per selection. Added quantity may expand to multiple tasks sharing this canonical line. No implicit extra line. |
| RecoverSubmissionReq | long expectedRevision>=0, String reason1..500 (trimmed); no customerReference/token/source number/new key for checkout. |
| ServiceSubmissionVO | String submissionNo, String workOrderNo, String action, long revision, long originalScopeRevision, long targetScopeRevision, String status, String phase, String checkoutOperationNo, CheckoutPort.CheckoutQuote quote, String orderNo, String subOrderNo, SourceObservationVO source, List<SubmissionTaskVO> tasks, String recoveryAction, List<String> blockingReasons. Quote from current owner only, nullable; historical frozen money is not stored by beauty. |
| SubmissionTaskVO | String taskNo, String skuNo, String definitionVersionNo, BigDecimal qty, String uom, long taskRevision, long bindingRevision, String sourceLineNo, String sourceState. |
| SourceObservationVO | String state, long observationEpoch, Long sourceRevision, String sourceHash, Long lastVerifiedRevision, String lastVerifiedHash, Instant observedAt, String reasonCode, boolean canStart. canStart always false in58; highest pair and last complete pair distinct. |
| RecoveryRequestVO | String recoveryNo, String submissionNo, String operationNo, long acceptedRevision, String state, String recoveryAction. operationNo is original submissionNo, never new checkout/payment operation. Immutable accepted-request receipt; state=ACCEPTED does not assert binding success. |
| WorkOrderBillVO | String workOrderNo, long scopeRevision, String localWatermark, Instant observedAt, List<SourceBillVO> sources, List<CurrencyBillVO> currencies, boolean complete, List<String> blockingReasons. |
| SourceBillVO | String checkoutOperationNo, String orderNo, String sourceNo, String currency, Long dueMinor, Long receivedMinor, Long refundedMinor, Long netReceivedMinor, Long outstandingMinor, String amountScope, List<ReceiptSummaryVO> receipts, List<SubmissionTaskVO> tasks, SourceObservationVO observation, boolean complete, List<String> blockingReasons. amountScope=WHOLE_SOURCE; never prorate by task qty. |
| CurrencyBillVO | String currency, Long dueMinor, Long receivedMinor, Long refundedMinor, Long netReceivedMinor, Long outstandingMinor, boolean complete. No currencyless grand total. |
| ReceiptSummaryVO | String paymentOperationNo, String paymentNo, String outTradeNo, String receiptNo, String channel, String status, long amountMinor, String currency. No payParams/trade credentials/evidenceRef/cashier token/raw requestKey. |
| BusinessTraceVO | String businessNo, String workOrderNo, String appointmentNo, long scopeRevision, List<ServiceSubmissionVO> submissions, WorkOrderBillVO bill, boolean complete, List<String> blockingReasons. Only locally owned workOrder/task/submission starting points; arbitrary external order/payment IDs do not authorize a trace. |

ServiceSubmissionVO.phase derives from persisted status+safe failure reason and current original-owner observation,
not a second editable phase field. Lists copied and null members rejected; number parsing exact, no decimal->COUNT
coercion. Request toString redacts customerRef/key; no original request hash or private credentials in output.
Existing OperationVO is unchanged: submission mapping uses operationNo=submissionNo, appointmentNo from verified
root, action SUBMIT_SOURCE/ADD_SOURCE, state SUCCEEDED only for historical BOUND (source current state remains
separate), otherwise PENDING or explicit local REJECTED, recoveryAction original-operation only.

Compatibility: no RemoteWorkOrderService exists today. Existing reception/appointment dispatch and their typed
result semantics stay unchanged. New service lookup recognizes only actual submission action SUBMIT_SOURCE or
ADD_SOURCE with valid schema2 frozen command; OPEN draft is a read-only initial draft, never sent by generic recovery.

## 4. Identity, intent, scope and real C handoff

Authorization, before any TX or owner call:
- 056/new recovery: current AppointmentAccess.merchant(write=true), CUSTOMER and ORDER_VIEW. PREPARE also actual
  CHECKOUT_PREPARE. 071/137: current booking read/manage plus ORDER_VIEW; source details remain authorized by owner.
- 072/operation query: current booking read/manage plus CUSTOMER; financial source access additionally ORDER_VIEW.
  Lacking financial grant must not fetch source or expose money; return explicit forbidden for financial variants.
- Validate actual current realm MERCHANT, kind MCH/USR, actor equals SecurityUtils user. USER buyer never comes from
  this actor field. Recheck current grants on every replay/recovery. No replay of saved delegated actor/token.

Scoped local root audit joins MAIN/entity/store/live root/context and original succeeded reception operation with
matching endpoint/action/workOrder. Also validate live appointment root and any appointment-item parent when present,
task.scopeRevision>0 and <=root.scopeRevision, frozen definition version/SKU/hash/uom/qty matches task snapshot.
Legacy roots without valid context have no actionable source submission.

Effective customer:
USER context -> immutable identityRef; PREPARE strict cr1 must resolve same USER.
GUEST context -> current guest owner CLAIMED same guest/revision2/user AND immutable same-store receipt -> same USER;
strict PREPARE cr1 must match it. REGISTERED/unclaimed, no/wrong receipt, BTG, staff/MCH number, consent=true cannot
be used. Existing source buyer is checked against this effective USER, never supplied by the request.

Before quote/source IO create the durable submission under root/task locks, plus additions if any:
- OPEN draft remains byte-for-byte, original scope/identity/taskRefs retained.
- action SUBMIT_SOURCE if no additions, ADD_SOURCE if additions. Initial status RECEIVED, nextRetryAt=now+30s.
- namespace hash = requestHash("BEAUTY_SUBMISSION_V1", MAIN, entity, store, original realm/kind/actor,
  "BAPI-056", SHA256(requireKey(rawKey))). Same store/action/hash unique; helper lookup includes action.
- payloadHash covers workOrder, expected revisions, normalized exact selections, PREPARE fields with only
  customerRef digest, or EXISTING original op/sub/pair/line selections. Same namespace changed payload => CONFLICT.
- frozen_command schemaVersion2 includes original actor namespace, endpoint, raw-key digest (not raw key), initial
  OPEN submissionNo, original context scope/hash/identity, effective USER and receiptNo if used, old/target root scope
  revisions, selected stored task snapshots/revisions, additions and their allocated business IDs, unique SKU group
  mapping, source mode/original expected source pair, original checkout OperationRef, derived prepareKey, request hash.
  Derived prepareKey=hash(namespaceHash,"PREPARE",submissionNo); stored value is owner instruction key, never public.
  No C token, B raw cr1, claimToken, offline evidence key, private payment params, price ledger or contact data.
- PREPARE checkout OperationRef("BEAUTY", submissionNo). EXISTING freezes caller BEAUTY and supplied original
  checkout operation, after local selection acceptance and before external validation.
- For additions root.advanceScope exactly once, append PENDING/UNASSIGNED COUNT1 tasks using frozen definitions.
  Existing tasks/context/draft never change. Quote only the explicit selected old/new tasks. New definitions are
  current at this addition acceptance, verified again under definition/policy heads in stable order.
- Same unbound task cannot have two independent pending purchase operations: scan matching active submissions
  under root lock. A new PREPARE/different source is rejected while an earlier owner result is unresolved.
  A new explicit EXISTING bind to the SAME old checkout operation may be accepted under the current scope; this
  is a new local binding confirmation, not a second purchase or modification of old intent.

Group selected tasks by SKU because actual owner forbids duplicate SKU lines. Freeze sorted task IDs per group;
clientLineRef="BTSL"+first60hex(hash(submissionNo,SKU,sorted task IDs)). Sum exact integer quantity<=10.
A single owner line can bind multiple expanded tasks, with guard quantity equal their sum. Existing-line selection
must match every selected task's SKU/goods/uom/count; no unselected owner line enters a new purchase.

After intent commit, original current B may call prepare with the original raw cr1 currently in memory and frozen
derived key/selection. Owner quote response must exactly match buyer/entity/store/OperationRef/SKU/count/client refs/
COUNT/STORE_VERIFY/SERVICE_EXECUTION. It is read-only economics; no beauty price is stored.

Return the original operation and current quote so true C uses existing HOST:
GET /mp/checkout/{operationNo};
POST .../confirmations; POST .../orders; POST .../payments and original lookup.
Consumer confirmation/order/payment keys and session are owned by C and shared owner, never generated/persisted by
beauty. OFFLINE C still confirms/places original purchase; current authorized HOST cashier uses the existing
/biz/checkout/{operationNo}/offline-receipts endpoint. 58 does not call OfflineReceiptPort.confirm automatically,
nor wrap it in another beauty charge intent. source observes its receipt; optional original trace lookup may call
OfflineReceiptPort.find only by known paymentOperationNo, with payParams discarded.

## 5. Original-number recovery and phase table

No general scheduler in58; current-user explicit recovery only. A 30-second submission lease uses RECOVERING with
nextRetryAt=lease expiry and captured post-claim version as fencing token. No IO under root/submission locks.
On completion CAS requires exactly that version and RECOVERING. Expired lease may be reclaimed by another currently
authorized B; old worker can only lose its CAS, never bind. SOURCE_PENDING nextRetryAt is backoff (30s), not
automatic authority; an explicit authorized request can query again without inventing an owner write.

| Durable/local phase | Owner operation / permitted result |
|---|---|
| OPEN/RECEIVED draft | DRAFT; query only, original reception has not selected purchasing. |
| RECEIVED before prepare | Original intent exists; no owner result assumed. Original B same request may prepare; otherwise findOperation/readQuote of exact original OperationRef only. |
| SOURCE_PENDING + PREPARE_UNKNOWN | Query original operation. Accepted but response lost -> existing quote returned. Authenticated NOT_FOUND is not permission to mint a new operation. |
| SOURCE_PENDING + REQUIRE_ORIGINAL_PREPARE_REQUEST | Original operation genuinely absent but prepare replay requires original B + identical raw cr1/request. Another B cannot create it. Missing/expired original reference may keep intent unavailable; do not silently replace proof/key. |
| SOURCE_PENDING + AWAITING_CUSTOMER | QUOTED/CONFIRMED; show handoff. B does not confirm/place. |
| SOURCE_PENDING + OWNER_PLACING | Actual findOperation PLACING; query only. C resumes original confirmation/key via HOST. |
| SOURCE_PENDING + AWAITING_PAYMENT / SOURCE_UNKNOWN | CREATED but source UNKNOWN; no allocation and no new payment. Source may carry last pair, not money/consumable lines. |
| RECOVERING | Short explicit lease. Query original owner, establish/observe canonical source, then try short bind TX. A lease failure does not erase real owner results. |
| BOUND | Historical local commit completed; response loss returns same bindings and allocation without increments. Current source may later be BLOCKED/UNKNOWN; query reports that separately. |
| SOURCE_PENDING + SCOPE_CHANGED | Original targetScopeRevision no longer current; save safe owner result/audit, no bind. New explicit EXISTING same-original-source submission can target current scope; no new purchase caused by recovery. |
| REJECTED | Only definitive pre-owner/local invalidity or actual owner REJECTED/REQUOTE_REQUIRED with explicit reason. A local rejection does not cancel an already placed order; no unknown is relabeled rejected. |

Same original key replay validates namespace/payload/current permissions before returning historical result; successful
replay does not fail only because old task/root expected versions have since advanced. It may query current source
for a separate present-state field. No terminal BOUND downgrade.

073 uses its own full current actor namespace + endpoint + key digest. Persist immutable action_log acceptance under
the target submission lock. recoveryNo is "BTRQ"+first28hex(namespaceHash); unique action_no provides cross-target
key arbitration; safe_snapshot stores the full256-bit namespace hash and full request hash so a truncation collision
fails CONFLICT, never aliases another request. request_ref holds full namespaceHash. Different request with same key
fails; accepted receipt replay is immutable even after target progress. Duplicate insert is handled after rolled-back
TX by scoped read and full-hash equality. Raw reason remains controlled reason column; safe snapshot holds no secrets.

Generic recoverOperation dispatch invokes recoverPending on an existing schema2 submission with current permissions,
not a new 073 receipt. It observes the current row and claims its original lease, then returns OperationVO. Existing
appointment/reception code paths are not changed or routed through this helper.

Catch only known dependency-unavailable/transport outcomes into safe pending state. FORBIDDEN/UNAUTHORIZED/NOT_FOUND
authorization failures propagate; arithmetic/contract mismatch blocks explicitly; programming/SQL errors roll back
and remain errors. No broad catch into success or fake absence. Restart uses frozen original numbers and DB versions.

## 6. Canonical source watermark, locks and quantity proof

### 6.1 Canonical identity and revision domain

Before CREATED there is only original checkout OperationRef in frozen submission; do not manufacture sourceNo.
For CREATED: lock-free readQuote and findOperation exact original ref; require same ref, quote buyer/entity/store,
actual result order/sub, quote/source selection and expected existing sub if supplied. Only owner-returned canonical
subOrderNo may initialize the observation row. source(scope,SourceLookup(originalRef,canonicalSub)) independently
rechecks owner chain. Once created, observation caller/op/order/sub/buyer/entity/store are immutable.

V539 unique tenant+sub and tenant+order, deleted=0, consumed-confirmation writer and sub.sourceOperationNo check prove
one canonical checkout revision domain per sub. V8 adds both canonical-source unique and caller/op unique. No comparison
between different operation revision integers. Any conflict in either direction blocks identity, never rebinds row.
Payment canonicalOperationNo is not checkoutOperationNo; payment aliases affect receipts, not this source domain.

### 6.2 Observation algorithm (also used by bill/trace reads)

SourceObservation is a nonmonetary observation projection, not a new source/payment authority.

1. Current authorization and local parent/owner canonical checks happen outside TX. Short TX insert-if-absent
   canonical observation row; concurrent insert collision rolls back, reloads and verifies all immutable fields.
2. Lock only the observation row. Increase observation_epoch and version using Math.addExact/SQL expectedVersion;
   set state UNKNOWN, failureCode SOURCE_OBSERVATION_IN_FLIGHT, observedAt NULL; retain highest source pair and last
   complete verified pair/lines. Return ticket(epoch, canonical identity). Commit BEFORE source IO.
3. Call only original CheckoutPort.source outside every transaction/lock.
4. Purely validate returned canonical identity before treating it as owner evidence. Classify trusted COMPLETE,
   trusted owner UNKNOWN, or INVALID; a transport exception is not an owner UNKNOWN response. Lock the same observation
   row and recheck its immutable domain. COMPLETE requires exact pair (>0/64hex), complete unique lines, goods/SKU/
   COUNT/positive orderedQty/nonnegative refundableQty<=ordered, unchanged prior identities and valid known economics.
   Trusted UNKNOWN requires exact original op/order/sub/buyer/entity/store, financialState UNKNOWN, execution BLOCKED,
   empty lines/payments and null received/outstanding as actual unknownSource1176-1199 returns. Its pair is either
   absent (revision0/hash null) or a structurally valid positive revision/64hex last pair; malformed half-pairs are INVALID.
   UNKNOWN due/currency is not usable money. Wrong-domain/malformed results never change highest.
5. A superseded ticket cannot authorize READY or allocation, but it must not discard already learned newer facts.
   If a fully validated COMPLETE response has a revision greater than locked highest (including highest absent),
   atomically advance highest pair and set BLOCKED/SOURCE_NEWER_SUPERSEDED_OBSERVATION. Preserve current epoch,
   lastVerified pair/lines/at and current observedAt; increment version/updatedAt. A trusted complete same-highest-
   revision different-hash response instead keeps highest and sets BLOCKED/SOURCE_HASH_CONFLICT. An old lower/equal
   matching pair, transport failure or invalid/foreign result cannot overwrite the current projection.
   Trusted superseded owner UNKNOWN is handled by step7 rather than discarded. Never use a superseded ticket for
   money totals or binding.
6. For a current ticket, highest pair never decreases. A lower owner revision, or same revision/different hash,
   keeps old highest/verified pairs and becomes BLOCKED/SOURCE_REVISION_REGRESSION or SOURCE_HASH_CONFLICT.
   Same-revision/different-hash is actual contract incoherence, not a clock difference: source hash excludes observedAt.
7. Trusted owner UNKNOWN preserves last COMPLETE verified pair/lines and never lowers highest. A valid higher last
   pair advances highest only. A same-highest-revision different-hash last pair preserves highest and blocks with
   SOURCE_HASH_CONFLICT. For a CURRENT UNKNOWN, state remains UNKNOWN (or hash-conflict BLOCKED), no usable lines.
   For a SUPERSEDED UNKNOWN, there is no proof that the current in-flight read observed the uncertainty afterwards.
   Under this same row lock, increase observationEpoch AND version with overflow checks, set state UNKNOWN,
   failureCode SOURCE_SUPERSEDED_OWNER_UNKNOWN and observedAt NULL (or BLOCKED/SOURCE_HASH_CONFLICT), preserving or
   raising highest as above. This invalidates every already issued ticket, including a response not yet delivered.
   Even an absent or older last pair triggers invalidation: absence does not prove that the uncertainty is obsolete.
   Recovery requires a NEW explicit source read claimed after this invalidation; neither its own old ticket nor
   another old complete response can restore READY. Repeated genuine unknown observations may postpone readiness;
   that conservative availability cost is exposed, not hidden by an automatic retry loop.
8. Only a CURRENT complete equal-highest pair or higher pair may restore readiness after a trusted owner read.
   Record exact verified lines and owner observedAt. executionState BLOCKED -> BLOCKED; otherwise READY.
   READY means only eligible for local observed binding, never executable. Zero/refunded selected line still rejects
   allocation even if other lines leave owner state NOT_STARTED.
9. Source transport failure leaves the already committed UNKNOWN epoch; known failure completion may set safe reason
   only if ticket still current. No retry loop under locks. Explicit next read can recover. Slow/newer readers may
   temporarily starve binding because they invalidate previous tickets; this availability cost is accepted and visible.

All completions, including superseded higher facts, claim the SAME canonical observation row lock as binding.
For superseded completion use a dedicated mapper update with expectedVersion/currentEpoch read under that lock;
do not reuse an update requiring ticketEpoch=currentEpoch, which would silently lose the newer watermark.
The trusted superseded UNKNOWN update also uses this locked current epoch/version and atomically advances epoch,
sets UNKNOWN/BLOCKED and preserves last verified facts; it does not use a stale request epoch as its CAS target.
If ep2 has read R1 but its response is delayed, and old ep1 observes refund R2 and returns first, ep1 raises highest
to R2/BLOCKED under the row lock. ep2 then locks the row, sees R1<highestR2 and stays BLOCKED. If ep2 applied READY
before ep1 returns, ep1 still revokes READY under the same lock before any later binding can pass. A binding already
committed before R2 became known is the stated remote-observation race, not permission to forget the R2 response.
For the UNKNOWN variant, ep1 owner UNKNOWN (with R2 last pair or with no pair) atomically invalidates ep2's ticket;
ep2's later R1 cannot become READY even if it equals the preserved highest. The first eligible restore is a new
claim/read after that invalidation. Foreign/malformed responses and old transport failures do not use this branch.
No extra source lock or RPC is introduced. A fresh current complete R2 with the matching highest hash can restore
eligible state; its actual per-line net quantities still prevent allocation after a refund below held quantity.

GET071/072/137 may update ONLY this observation projection through the above steps and safe diagnostics; no bind,
quote prepare, confirm, place, initiate, receipt confirm, release or automatic repair. Owner source itself already
serializes its observation pair. Historical binding and last verified evidence remain readable when current state blocks.

### 6.3 Short binding transaction

Single source per submission, multiple selected source lines:
observation fence -> line guards sorted by (sourceKind,sourceNo,sourceLineNo) -> workOrder ->
selected taskNo lexical order -> submission -> immutable binding inserts/actionLog.
No future source lock is acquired after workOrder. Creation of missing line guards is inside this guard-first TX;
duplicate unique-key race rolls back and restarts the LOCAL transaction from the first lock at most once, with the
same observation ticket and no repeated owner call. Normal deadlocks/errors are not broadly hidden.

Under locks recheck:
- observation current epoch equals ticket, READY, highest pair == verified pair == exact response pair, canonical
  domain unchanged, current source lines match frozen selection; explicit EXISTING expected pair must match.
- complete live local context/original reception/appointment/task parents; root status OPEN or IN_SERVICE,
  scopeFrozen=false; root.targetScopeRevision exactly frozen target, and expected acceptance/root lineage unchanged.
- each first-bound task PENDING, startedAt=null, doneQty=voidedQty=0, exact stored qty/uom/definition/snapshot,
  expected task revision and bindingRevision0, no existing current binding; no replaced SKU/new frozen rule.
- original submission phase/version/lease fence and byte-identical frozen command/payload hash; nonzero binding
  rows already belonging to this submission return historical success only, never allocate again.
- guard owner pair cannot be newer than observation; same revision hash mismatch refuses. New guard sourceQty uses
  historical orderedQuantity, NOT refundableQuantity. Existing guard may refresh only monotonic pair with ordered
  capacity>=allocated. Old malformed/legacy incompatible guard is explicitly BLOCKED, not repaired automatically.

For each selected line:
historical capacity Q=orderedQuantity; held A=guard.allocatedQty (includes all historically bound service quantity,
including done quantities until an authorized future reverse path); owner net ceiling N=refundableQuantity.
New quantity delta=sum selected task COUNT. Require A+delta<=Q AND A+delta<=N in service/current-watermark SQL.
Atomic guard allocate CAS must include current observation epoch/state/pair plus net-ceiling predicate, not merely
a prior Java check. Then insert immutable bindings, switch task bindingRevision 0->1 via actual quantity CAS,
touch root.version, advance submission BOUND, log actual source binding in the same transaction.
Any zero update/constraint failure rolls back guards/bindings/task/root/status/log together.

Local invariants:
- task.qty = task.doneQty + task.voidedQty + task.remainingQty; remaining derived, never negative.
- active binding sum = task.qty-task.voidedQty for its current bindingRevision.
- guard.allocatedQty = total unreleased local allocations for the canonical line, including consumed history;
  it is NOT added to doneQty again.
- current owner net ceiling may become <held after a refund. Preserve held and historical Q; current source blocks
  new allocation. Do not rewrite held to0, delete binding, or invent a release to satisfy a lower ceiling.

Counterexample: R1 Q2/N2, A1. Refund R2 N0 arrives under current epoch, observation stores R2/BLOCKED or zero-net line
while guard Q2/A1 stays legal. Late R1 ticket cannot apply; a newly claimed epoch returning R1 cannot lower highestR2.
No second delta1 can pass current observation+N predicate. Existing A1 remains visible and non-executable.

Remote refund may race AFTER the owner observation and BEFORE commit; this task cannot atomically serialize that
remote mutation. Binding is explicitly historical observed allocation; next observation blocks it. Real execution
would require the future shared execution/refund protocol and remains closed. The watermark closes already-observed
regression, not a fictional distributed transaction.

No release/rebind in58. A future release must keep binding history, prove unstarted/fully voided task and exact
once-only released quantity under the same source fence/guard order; the existing coarse release helper is not
declared sufficient for per-binding future release. Do not change its existing tests/behavior to expand this task.

## 7. Bill, partial results and trace semantics

Enumerate only authorized local submissions and bound sources from full parent chains. OPEN drafts and pending
PREPARE intents are listed as pending, without counterfeit order facts. Multiple submissions referencing one
canonical checkout/sub produce one source row with linked task list and all relevant original intent references.

Capture a read-only local RR snapshot (no external calls) with root/version/scope, immutable source list and ordered
submission/binding versions. Internally keyset-page by submission id in batches100 up to captured maxId; no silent
page truncation. Commit snapshot before source reads. After lock-free owner reads and observation projection updates,
recapture local manifest and compare digest. One bounded full retry is allowed; another change yields
complete=false / LOCAL_SNAPSHOT_CHANGED. Observation pairs are a per-source vector, not an imaginary cross-owner
atomic financial instant. New source/submission after watermark is not silently counted as part of an earlier page.

A source row is current complete only when:
- exact authorized canonical buyer/entity/store/order/sub/op and verified source line identities match;
- source financialState != UNKNOWN, received/outstanding nonnull, all amounts nonnegative and currencies consistent;
- owner receipt identity unique/canonical. Dedup by receiptNo when present, otherwise exact legacy paymentNo+order+
  channel+trade identity from owner response. Repeated canonical alias with identical amount/currency contributes once;
  conflicting same receipt identity blocks. One receipt appearing under different canonical orders is inconsistency.
- mapped Task57 receipts preserve actual canonical paymentOperation/outTradeNo/receiptNo; legacy null values stay null,
  not forged. ReceiptSummary omits sensitive trade parameters/private evidence.
- current source pair passed watermark; no lower/hash-conflicting/superseded response feeds current money totals.

Money semantics, all in integer minor units:
due = owner due from complete source; gross received = sum(unique real SUCCESS PaymentFact.amountMinor);
netReceived = owner receivedMinor; refunded = Math.subtractExact(gross, netReceived) from SAME source snapshot;
outstanding = owner outstandingMinor exactly, never due-net as a new collection demand.
Validate gross>=net>=0, refund<=gross, exact currency, sums/subtractions with addExact/subtractExact. Current owner
emits CNY; contract tests may exercise distinct synthetic currencies to prove aggregation isolation, without claiming
real multi-currency checkout exists. UNKNOWN due0 is discarded and exposed null along with unknown monetary fields.

For source partially allocated to this work order (some line quantity elsewhere/unallocated or unselected lines),
source row can expose authenticated WHOLE_SOURCE money and linked quantities, but this is not an attributed work-order
charge. Set SOURCE_AMOUNT_NOT_ATTRIBUTABLE and currency aggregate complete=false/null. Never prorate source money by
task quantity or count the whole source as this work order's exclusively payable amount.

Aggregate only once per canonical source and currency. A missing source/owner failure/amount/currency conflict makes
affected currency totals null and incomplete, while successful source rows remain visible. Unknown currency yields a
separate incomplete source with currency=null, not "CNY" or a merged bucket. complete=false cannot masquerade as settled.
No grand total across currencies, mixed receipt sheet, collection side effect, or refund instruction.

BOUND current source changed/UNKNOWN shows historical bound pair plus current blocker and canStart=false. A late real
receipt on old CLOSED/CANCELLED source remains owner historical money but BLOCKED; screenshot/ordPAID/B statements
never promote it. Root trueC NOT_SENT/new explicit key rules remain owner-only; beauty neither closes UNKNOWN nor
generates a payment key.

Trace137 supports only authorized local workOrderNo/taskNo/submissionNo. Resolve root first, then use same source/
bill helper. It links actual appointment/root/task/submission/checkouts/receipts with safe states; no fake steps,
participation, verification code, print or notification success.

## 8. Service / mapper / helper and Bean matrix

All product files below are prospective owned after IMPLEMENTATION_BASE; no implementation now.
Every helper has a concrete responsibility, no Repository wrapper or port stub.

| Module / full class | Responsibility / dependency |
|---|---|
| beauty-api / ai.neargo.shop.beauty.service.WorkOrderService | Add submitServices/getBill, preserve existing3. |
| beauty-api / ai.neargo.shop.beauty.service.ServiceSubmissionService | Four exact signatures above. |
| beauty-api / ai.neargo.shop.beauty.service.WorkOrderBillService | Bill/trace local boundary. |
| beauty-core / ai.neargo.shop.beauty.service.impl.ServiceSubmissionServiceImpl | Direct local Mappers; durable intent, scope append, explicit lease, owner preparation/handoff/recovery, bind TX; depends helpers below, never WorkOrderService. |
| beauty-core / ai.neargo.shop.beauty.service.impl.WorkOrderBillServiceImpl | Local snapshot manifest, owner read aggregation/trace; direct scoped Mappers and SourceObservation, no SubmissionService dependency. |
| beauty-core / ai.neargo.shop.beauty.service.impl.ServiceSubmissionQuery | Shared authorized local intent/root/task projection and generic operation view; direct Mappers, no Service dependency or owner writes. |
| beauty-core / ai.neargo.shop.beauty.service.impl.ServiceSourceObservation | Concrete original CheckoutPort read + canonical identity and persisted epoch/watermark protocol; direct observation Mapper, primary TX helper and Clock. No task/WO locks while calling owner. |
| beauty-core / ai.neargo.shop.beauty.policy.ServiceSubmissionAccess | Current namespace + booking/customer/order/prepare grants via existing real operator port; invokes existing ReceptionIdentity/receipt facts only outside TX. |
| beauty-core / ai.neargo.shop.beauty.policy.ServiceSourceFacts | Pure canonical identity/selected-lines/money/owner-pair validator and nullable bill arithmetic; immutable internal evidence records. No IO or injectable future provider. |
| beauty-core / ai.neargo.shop.beauty.entity.BtySourceObservation | All V8 fields aligned, extends BaseEntity; no duplicate money. |
| beauty-core / ai.neargo.shop.beauty.mapper.BeautyMappers.SourceObservationMapper | Exact indexed canonical find/lock, ticket claim and fenced completion. |
| beauty-core / ai.neargo.shop.beauty.config.BeautySourceConfiguration | ConditionalOnBeautyEnabled; explicit helper/service Beans, import by BeautyReceptionConfiguration. Lazy real CheckoutPort/OfflineReceiptPort lookup preserves disabled/server wiring; no fabricated provider. |
| existing WorkOrderServiceImpl | Delegates only new submit/bill to interfaces; open/detail unchanged except query local binding projection. |
| existing WorkOrderQuery | Reads real binding revision/local source observation for BOUND/UNKNOWN/BLOCKED; never calls a Port inside existing db.read path, canStart=false. |
| existing ServiceOperationQuery/RecoveryImpl | Add original submission dispatch via Query / SubmissionService; reception and appointment branches retained. |

Transaction helper is kept in ServiceSourceObservation/ServiceSubmissionServiceImpl using primary manager and existing
DataScopeContext.executeWithoutScope; no new generic persistence abstraction. All public entry methods reject caller
transaction before authorization/IO. Internal snapshot TX explicit read-only RR; writes RC short local TX.

Exact mapper increments (all in BeautyMappers, @Param explicit; DTOs internal records under ServiceSubmissionQuery
or ServiceSourceObservation, not entities exposed to API):
- ServiceSubmissionMapper:
  BtyServiceSubmission selectSubmission(String entityNo,String storeNo,String submissionNo);
  BtyServiceSubmission selectActionKey(String entityNo,String storeNo,String action,String requestKeyHash);
  List<BtyServiceSubmission> selectWorkOrderPage(String entityNo,String storeNo,String workOrderNo,
      long afterId,long maxId,int limit);
  List<BtyServiceSubmission> selectActiveForWorkOrder(String entityNo,String storeNo,String workOrderNo);
  int claimSourceRecovery(String entityNo,String storeNo,String submissionNo,long expectedVersion,
      LocalDateTime now,LocalDateTime leaseUntil,String actorNo);
  int finishSourceRecovery(String entityNo,String storeNo,String submissionNo,long leaseVersion,
      String targetStatus,String failureCode,LocalDateTime nextRetryAt,LocalDateTime now,String actorNo).
  Both update predicates include schema2/action allowlist and parent chain. Existing OPEN draft progress helpers unchanged.
- WorkOrderMapper:
  int touchSourceBinding(String entityNo,String storeNo,String workOrderNo,long expectedVersion,
      long expectedScopeRevision,String actorNo,LocalDateTime updatedAt);
  binding touch increments root version only, never scope; actual advanceScope used only for addition.
- TradeBindingMapper:
  List<BtyTradeBinding> selectCurrentForWorkOrder(String entityNo,String storeNo,String workOrderNo);
  List<BtyTradeBinding> selectSubmissionBindings(String entityNo,String storeNo,String submissionNo);
  enforce exact double parent plus current task pointer, original definition parent in service.
- SourceObservationMapper:
  BtySourceObservation selectCanonical(String entityNo,String storeNo,String sourceKind,String sourceNo);
  BtySourceObservation selectCanonicalForUpdate(String entityNo,String sourceKind,String sourceNo);
  BtySourceObservation selectOperation(String entityNo,String caller,String operationNo);
  int claimObservation(String entityNo,String sourceNo,long expectedVersion,long nextEpoch,
      LocalDateTime now,String actorNo);
  int completeObservation(String entityNo,String sourceNo,long epoch,long expectedVersion,
      String state,Long sourceRevision,String sourceHash,Long verifiedRevision,String verifiedHash,
      String verifiedLines,LocalDateTime verifiedAt,LocalDateTime observedAt,String failureCode,String actorNo);
  int blockFromSupersededComplete(String entityNo,String sourceNo,long expectedVersion,long currentEpoch,
      Long sourceRevision,String sourceHash,String failureCode,LocalDateTime updatedAt,String actorNo);
  int invalidateFromSupersededUnknown(String entityNo,String sourceNo,long expectedVersion,long currentEpoch,
      long nextEpoch,String state,Long sourceRevision,String sourceHash,String failureCode,
      LocalDateTime updatedAt,String actorNo);
  The complete update preserves epoch/verified fields/observedAt and writes BLOCKED plus monotonic highest only
  after trusted-complete validation. The UNKNOWN update increases epoch, clears observedAt, preserves verified
  fields and writes UNKNOWN/BLOCKED plus monotonic highest after trusted-owner-UNKNOWN validation. Both require
  the locked current epoch/version, not the superseded ticket epoch. All use the same row lock and full scope.
  Explicit SQL sets nullable columns; do not rely on updateById skipping null.
- SourceAllocationGuardMapper:
  int allocateObservedQuantity(String entityNo,String guardNo,long expectedVersion,long expectedSourceRevision,
      String sourceHash,long observationEpoch,BigDecimal incrementQty,BigDecimal refundableQty,
      String actorNo,LocalDateTime updatedAt);
  SQL includes locked observation READY/pair/epoch and allocated+delta<=bound net ceiling, plus existing guard capacity.
  Caller net ceiling must equal selected line in the locked verified_lines; service validates exact structural line,
  SQL receives only this internal evidence. Original allocateQuantity kept for existing persistence tests, new service
  never uses it without observed guard.
- ActionLogMapper:
  BtyActionLog selectSourceRequest(String entityNo,String storeNo,String actionNo);
  List<BtyActionLog> selectSourceTrace(String entityNo,String storeNo,String submissionNo);
  full scope/aggregate/action allowlists; recovery deterministic ID full-hash check.
All selects/mutations explicitly tenant MAIN/entity/store via authorized root or canonical source. No implicit
DataScopeContext grant inference, no physical id in DTO. Source guard key intentionally has no store: canonical owner
store is checked on observation so cross-work-order allocations share the same guard.

Bean DAG:
WorkOrderServiceImpl -> SubmissionService / BillService / existing coordinator/query.
OperationQuery -> ServiceSubmissionQuery; OperationRecovery -> SubmissionService and existing recovery helpers.
SubmissionService -> Access, Query, WorkOrderPreparation, SourceObservation, pure Facts, Mappers.
BillService -> Access, Query, SourceObservation, pure Facts, Mappers.
SourceObservation -> ObjectProvider<CheckoutPort>, optional original OfflineReceiptPort read, Mappers, primary manager, Clock.
Owner real CheckoutPortImpl -> CheckoutServiceImpl -> existing owner services. There is no arrow back to beauty.
ObjectProvider absence returns explicit dependency-unavailable/pending on authorized operation; no "ready" mock bean.
No JobDeclaration, result consumer, timer or generic future-provider interface is introduced.

## 9. Candidate migration and frozen SQL

Actual max checked from filenames: beauty V7 (7 migration files), platform V540 (460 files). Current beauty27 private
tables/ready7. Candidate: beauty V8__beauty_source_observation.sql only, one new table, proposed ready8/28.
No V541. V1-V7, platform V539/V540 remain byte-for-byte frozen.

Why necessary: existing guard source_qty>=allocated_qty cannot persist qty0 after alloc1, and no source-level
monotonic UNKNOWN/epoch fence exists. A JSON note on an arbitrary submission cannot serialize the same source used
by multiple work orders. Unique source/op observation row provides that shared local serialization without money.

Candidate bytes below: UTF-8, LF, trailing newline, 4582 bytes.
SHA256 c5b54d574624a5265366cf4bf7897ec949d107bd302f171539c461a89a9d8343.
This is a candidate, not root execution pin. No SQL file has been created or executed.

Future exact source/generator:
docs/technical/design/schema/beauty-source-observation-schema.json (one-table fields/unique/index/check definitions);
scripts/gen-beauty-source-observation-schema.mjs, reuse writeFrozenMigration and render conventions, exact bytes below.
Generated output only after implementation release AND root preexecution pin:
backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V8__beauty_source_observation.sql.
Validator adds frozen V8 hash/table manifest, expected ready8; SQL template points to new version, not patched old SQL.
Do not relax CHECK parser to accommodate an invalid candidate.

~~~sql
-- Beauty source observation watermark. V1-V7 remain frozen.

CREATE TABLE `bty_source_observation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Physical key; never public',
    `tenant_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT 'Existing tenant',
    `entity_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'Authorized entity',
    `created_at` DATETIME(3) NOT NULL COMMENT 'Creation time',
    `created_by` VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT 'Actual actor',
    `updated_at` DATETIME(3) NOT NULL COMMENT 'Observation update time',
    `updated_by` VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT 'Actual observing actor',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT 'Local compare-and-set version',
    `deleted` INT NOT NULL DEFAULT 0 COMMENT 'Permanent identity; deletion forbidden',
    `store_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'Canonical owner store',
    `source_kind` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'STORE_ORDER only',
    `source_no` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'Canonical sub-order number',
    `checkout_caller` VARCHAR(16) COLLATE utf8mb4_bin NOT NULL COMMENT 'BEAUTY only',
    `checkout_operation_no` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'Unique owner revision domain',
    `order_no` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'Canonical ordinary payment root',
    `buyer_no` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'Verified USER identity',
    `observation_epoch` BIGINT NOT NULL DEFAULT 0 COMMENT 'Local monotonic source-call fence',
    `state` VARCHAR(16) COLLATE utf8mb4_bin NOT NULL COMMENT 'UNKNOWN or READY or BLOCKED',
    `source_revision` BIGINT NULL COMMENT 'Highest observed owner revision',
    `source_hash` CHAR(64) COLLATE utf8mb4_bin NULL COMMENT 'Hash paired with highest owner revision',
    `verified_revision` BIGINT NULL COMMENT 'Last complete verified owner revision',
    `verified_hash` CHAR(64) COLLATE utf8mb4_bin NULL COMMENT 'Hash paired with verified lines',
    `verified_lines` JSON NULL COMMENT 'Last verified identity and quantity lines; no money',
    `verified_at` DATETIME(3) NULL COMMENT 'Last complete owner observation time',
    `observed_at` DATETIME(3) NULL COMMENT 'Current epoch completion time; null while reading',
    `failure_code` VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT 'Safe current blocking reason',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_bty_source_observation_1` (`tenant_no`, `entity_no`, `source_kind`, `source_no`),
    UNIQUE KEY `uk_bty_source_observation_2` (`tenant_no`, `entity_no`, `checkout_caller`, `checkout_operation_no`),
    KEY `idx_bty_source_observation_1` (`tenant_no`, `entity_no`, `store_no`, `state`),
    CONSTRAINT `ck_bty_source_observation_1` CHECK (`version` >= 0 AND `observation_epoch` >= 0),
    CONSTRAINT `ck_bty_source_observation_2` CHECK (`deleted` = 0),
    CONSTRAINT `ck_bty_source_observation_3` CHECK (`tenant_no` = 'MAIN' AND `source_kind` = 'STORE_ORDER' AND `checkout_caller` = 'BEAUTY'),
    CONSTRAINT `ck_bty_source_observation_4` CHECK (`state` IN ('UNKNOWN', 'READY', 'BLOCKED')),
    CONSTRAINT `ck_bty_source_observation_5` CHECK ((`source_revision` IS NULL AND `source_hash` IS NULL) OR (`source_revision` IS NOT NULL AND `source_hash` IS NOT NULL AND `source_revision` > 0 AND CHAR_LENGTH(`source_hash`) = 64)),
    CONSTRAINT `ck_bty_source_observation_6` CHECK ((`verified_revision` IS NULL AND `verified_hash` IS NULL AND `verified_lines` IS NULL AND `verified_at` IS NULL) OR (`verified_revision` IS NOT NULL AND `verified_hash` IS NOT NULL AND `verified_lines` IS NOT NULL AND `verified_at` IS NOT NULL AND `verified_revision` > 0 AND CHAR_LENGTH(`verified_hash`) = 64 AND JSON_TYPE(`verified_lines`) = 'ARRAY' AND JSON_LENGTH(`verified_lines`) > 0 AND JSON_LENGTH(`verified_lines`) <= 20)),
    CONSTRAINT `ck_bty_source_observation_7` CHECK (`verified_revision` IS NULL OR (`source_revision` IS NOT NULL AND `verified_revision` <= `source_revision` AND (`verified_revision` <> `source_revision` OR `verified_hash` = `source_hash`))),
    CONSTRAINT `ck_bty_source_observation_8` CHECK ((`state` = 'READY' AND `observation_epoch` > 0 AND `source_revision` IS NOT NULL AND `verified_revision` IS NOT NULL AND `source_revision` = `verified_revision` AND `source_hash` = `verified_hash` AND `observed_at` IS NOT NULL AND `failure_code` IS NULL) OR (`state` IN ('UNKNOWN', 'BLOCKED') AND `failure_code` IS NOT NULL AND CHAR_LENGTH(`failure_code`) > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
~~~

Upgrade/null rules:
- V8 only CREATE new table; no ALTER/backfill/reinterpretation of existing scope, binding or guard.
- Existing rows remain unchanged; a source lacking a V8 row is UNKNOWN and cannot bind until authenticated owner
  canonical read + fresh epoch establishes it. Legacy source that cannot prove original checkout mapping stays blocked.
- Pair constraints reject all half-null variants. verified_revision/hash/lines/at must be all null or complete;
  complete lines array size1..20. verified<=highest and equal revisions require equal hashes.
- READY requires completed current observation and matched complete pair; UNKNOWN/BLOCKED require safe reason.
  Initial UNKNOWN epoch0 has all pairs/lines/times null. Claim UNKNOWN preserves last evidence and clears observed_at.
- On response failure after claim, UNKNOWN with prior evidence is valid. On higher owner UNKNOWN, highest may advance
  while verified remains lower. Lower/hashing conflict BLOCKED preserves both and no new capacity.
- Old guard with net-capacity historical values may refresh only via newer compatible source pair to original ordered
  capacity. Same-pair incompatible Q/hash or unprovable allocation history is blocked; no upgrade repair.
- Unique canonical source and unique caller/op prevent revision domains merging. soft delete forbidden by CHECK.
- Full bootstrap V1..V8, clean V7->V8 with existing OPEN/BOUND/guard rows, rerun validate, NULL combinations/constraint
  tampering/native indexes/entity alignment/row audit required. No IF NOT EXISTS, repair, clean or checksum bypass.

Frozen baseline hashes read this phase:
V1 35885 ac2a539664c42fdb7e4893f1359879fc5affbe4b24f95aae1809cd25f11061a4
V2 5785 5b6768928018b68635c9c081b200a2cb6202d40645c5db62c91c558cc49d4114
V3 8020 c8d54df07cabc5b0903e0dd4fba73e2b7fc4aa75539a148837cc5837deaa8da7
V4 1996 7df4e8d2e3aeeb57bf1e704004957d996912a17021c5f58842ac91152e7a003a
V5 9806 8e9aec144736ee3502fb9c0f9c4c95edf6f4a02c1cecbd597ff0c3b51aa4eab5
V6 445 220ed777c56a77c22cf835f2a5bcc42ccb41cc98a4348831df06e0e404b89f20
V7 417 8894786048bf04ca45eff129a83fa7776da867994d72dff2343a91b215d6d3f2
V539 8444 5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b
V540 7477 ca76b6512cf79c6f25fa71a4441f533e03cc609c6320611a0dcdc9634a10f745

## 10. AC tests, actual providers and valid RED selectors (planned, not run)

New core native classes in beauty-core/src/test/java/ai/neargo/shop/beauty/scenario:
ServiceSubmissionIT, ServiceSourceObservationIT, WorkOrderBillIT.
Reuse ReceptionFixture real local scope/Mappers/native DB; new dedicated SourceFixture supplies controlled
CheckoutPort/OfflineReceiptPort contracts only, counts calls and asserts no active transaction. Never describe that
fixture as real trade/payment. No production fixture beans. Existing SourceAllocationConcurrencyIT remains lower
level quantity/CAS coverage; new service tests must exercise real orchestrator paths.

New HOST class backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautySourceCheckoutIT extends/adapts actual
CheckoutFlowFixture with beauty embedded profile; real CheckoutPortImpl/CheckoutService/ordinary writer/member
reference/guest owner/payment ledger/canonical guards; calls beauty local services via reflection as existing optional
host tests. Real local definition/policy fixture admits service scope; resource admission only if used is explicitly
fixture. New HOST test uses existing actual cash owner/test gateway, never stubs real source or C authentication.
Use current-C helper/real HOST HTTP for confirmation/order/payment; B never invokes them under a forged session.

| AC / selector | Concrete assertion / expected business RED |
|---|---|
| ServiceSubmissionIT#openDraftDoesNotPurchaseAndExplicitSelectionIsExact | Opening creates only OPEN draft; explicit selected1 of2 prepares owner qty1 once, no other task/source, no confirm/place/pay calls; first valid failing assertion expects prepared quote vs fail-closed pending. |
| ServiceSubmissionIT#originalNamespaceAndChangedPayloadArePermanent | Same namespace/key returns same submission/op; changed selection rejects; MCH/USR realm collisions distinct; active same task different purchase rejected. |
| ServiceSubmissionIT#currentGrantAndFullParentChainAreRequired | Current revocation, wrong store/entity, deleted root/context/original op/item, orphan task all fail before owner IO; positive reaches bind. |
| BeautySourceCheckoutIT#guestRequiresRealClaimReceiptAndConsumerPurchase | Real cr1/guest claim receipt; registered guest/BTG/B kind cannot buy; claimed USER passes, true C confirms/places/pays; B attempts fail. |
| ServiceSubmissionIT#prepareResponseLossRetainsOriginalOperationAfterRestart | Real local intent committed then controlled prepare returns/response dropped; recreated service reads original op, no new key/order; second B query only. |
| BeautySourceCheckoutIT#lostQuoteResponseRecoversRealOriginalUnderCurrentManager | Fault drops response AFTER real owner prepare commits; second current manager readQuote/findOperation succeeds; revoked original namespace not replayed; owner row counts exact. |
| ServiceSubmissionIT#unacceptedPrepareNeedsOriginalRequestNotImpersonation | Absent original op + other B => REQUIRE_ORIGINAL_PREPARE_REQUEST; original exact proof/request can resume; no proof persisted; fresh differing proof conflicts. |
| ServiceSubmissionIT#additionsAppendScopeAndOldReceiptsCannotBind | Original task snapshots/context/draft unchanged; new admitted tasks scope+1; delayed old target receipt creates no binding; same original source can be explicitly selected at new scope, no purchase again. |
| ServiceSubmissionIT#boundResponseLossReplaysWithoutAllocatingAgain | Fault after commit/response loss, restart; one binding/guard increment, original terminal result. |
| ServiceSubmissionIT#sameSourceAcrossWorkOrdersHasOneQuantityWinner | Independent native connections actual two work orders share source Q1/N1; exactly one binds, other rejects; ordered locks observed, no negative/overallocated rows. |
| ServiceSubmissionIT#sameTaskDifferentKeysCannotCreateTwoAllocations | Two concurrent explicit EXISTING requests same task; one bindingRevision1/allocation, no double qty or overwritten original. |
| ServiceSourceObservationIT#refundBelowHeldQuantityBlocksLateOldObservation | R1 Q2/N2/A1 then R2 N0, persisted highestR2 despite guardA1; delayed R1/new-epoch regression cannot allocate another1. Critical root counterexample. |
| ServiceSourceObservationIT#supersededHigherRevisionBlocksDelayedCurrentLowerFacts | ep2 reads R1 but delays delivery; ep1 reads refund R2 and returns first. Under real observation-row locks ep1 raises highestR2/BLOCKED without READY/lastVerified changes; ep2 R1 cannot clear it or allocate. Also test ep2 READY first, then ep1 R2 blocks, and invalid/foreign-op cannot poison highest. |
| ServiceSourceObservationIT#supersededTrustedUnknownWithHigherPairInvalidatesCurrentRead | ep1 genuine owner UNKNOWN carries canonical lastR2; ep2 has delayed completeR1. ep1 retains lastVerified, raises highestR2 and atomically epoch++; ep2 cannot restore READY/allocate. Only subsequent fresh matchingR2 read may restore eligible state. |
| ServiceSourceObservationIT#supersededTrustedUnknownWithoutPairRequiresFreshRead | ep1 genuine canonical UNKNOWN has revision0/null hash; ep2 delayed same-highestR1 is invalidated despite matching pair. Preserve highest/verified, epoch++, stateUNKNOWN. New post-invalidation read may restore; malformed/foreign UNKNOWN and transport exception do not modify highest/epoch. |
| ServiceSourceObservationIT#unknownWatermarkCannotBeClearedByOlderEpoch | Owner UNKNOWN after prior completeR1, old sameR1 response arrives; highest/lastVerified retained, currentUNKNOWN, no allocation. Fresh current-epoch equalR1 may restore if genuinely owner verified. |
| ServiceSourceObservationIT#sameRevisionDifferentHashAndForeignOperationBlock | No clock-only revisions; sameR differentH => BLOCKED; op/sub mapping collision rejects without comparing revision integers. |
| ServiceSourceObservationIT#slowOrFailedReadLeavesRecoverableUnknown | Claim commits UNKNOWN, lock-free source stalls/fails; old ticket no bind; next explicit current observer restores through new epoch. |
| ServiceSubmissionIT#refundCancellationAndUnknownNeverReleaseHistory | Owner BLOCKED/UNKNOWN leaves bindings and allocatedQty, no void/release/execution writes; canStart=false. |
| WorkOrderBillIT#sameReceiptAliasesCountOncePerCurrency | Alias/same receipt dedup, different currency separate, conflicting receipt blocks; source shared between WO remains WHOLE_SOURCE and totals incomplete. |
| WorkOrderBillIT#partialOwnerFailureAndUnknownZeroAreIncomplete | One owner fails, sourceUNKNOWN due0 discarded/null; other source visible; no money invented or collect call. |
| WorkOrderBillIT#overflowMissingAmountsAndSnapshotChangesCannotLookSettled | addExact/subtractExact overflow, negative/receipt mismatch, missingcurrency/net, local source added between pages => incomplete; no crosscurrency sum. |
| WorkOrderBillIT#traceAndBillRecheckCurrentScopeWithoutSensitiveParameters | Current grants/soft-deleted parent denied; no requestKey/evidence/token/payParams; GET changes only observation row. |
| BeautySourceCheckoutIT#realOfflineCanonicalReceiptAndRefundBillRemainOwnerFacts | Real current cashier original cash, source canonical PO/outTradeNo/RC, actual owner refund update, beauty bill gross/net/refund; no task quantity pricing. |
| BeautySourceCheckoutIT#realUnknownAndLateReceiptBlockObservedBinding | Actual Task57 owner UNKNOWN/late/cancelled facts consumed; no payment replacement and no BM05 bypass. |
| BeautySourceCheckoutIT#realOwnerAndResourceCallsNeverRunUnderBeautyLocks | Real owner boundary plus transaction probes; binding TX contains no Port call; host owner currently rejects caller TX. |
| ServiceSourceObservationIT#v7UpgradePreservesOldAllocationsAndRejectsHalfNullPairs | Native V7 fixture existing rows -> exact V8; CHECK negative/half-null/hash pair/identity unique and ready8/28; no old DDL edit. |
| BeautySourceWiringTest#disabledEmbeddedAndMissingOwnerAreExplicit | Off => no beans; embedded => one of each service, DAG no cycle; owner missing => explicit unavailable, no success, original reception endpoints unchanged. |

RED protocol: tests first; minimal DTO/interface/throwing unavailable API scaffolding only if needed for compilation.
A missing class/method, compile error, setup failure, skipped or 0 tests is NOT business RED. The first behavior RED
must reach the real service branch and assert a desired result/side effect. Add one focused selector at a time,
record exact fresh XML count and error classification; no retrospective RED label on first-GREEN cases.

Minimal ablation after first GREEN:
remove ONLY the centralized incoming sourceRevision < highest sourceRevision rejection in observation completion,
in an isolated local edit. Use ServiceSourceObservationIT#refundBelowHeldQuantityBlocksLateOldObservation: first observe R1
(quantity 2), bind 1, then observe R2 (refundable quantity 0); start a NEW local epoch and return the old R1 facts.
The unmodified service must keep highest R2, enter BLOCKED, and reject a second allocation. The ablated service
incorrectly permits the old R1 to become READY, so the assertion on retained highest/blocked state must go RED
before a second allocation can occur. The test reaches the real observation service and mapper with a current epoch,
so epoch checks cannot mask this mutation. If an independent check prevents the behavior, report that masking and
select the precise monotonicity assertion instead of claiming a successful ablation. Restore exact product bytes,
verify hash restoration, rerun the SAME selector GREEN. No replacement with compiler/test setup failure.

## 11. Execution order and commands after release

No command in this section was run in design phase. One implementer, no agents.

- [ ] Root independently reads design/literal, confirms owner boundaries and exact candidate bytes/hash.
- [ ] Root issues IMPLEMENTATION_BASE after bookkeeping. Before first SQL execution root pins actual generated file.
- [ ] Add focused real service tests/contracts and obtain first business RED. Record no runtime success before run.
- [ ] Implement minimum intent/selection/current identity and real checkout handoff; focused GREEN.
- [ ] Implement V8 literal source/generator/validator/ready/entity/data-scope and native upgrade NULL/unique tests.
- [ ] Implement observation ticket/fence plus guard-first binding/concurrency; counterexample RED then GREEN.
- [ ] Implement original recovery / bill / trace / local read projection / Bean graph; test each distinct behavior.
- [ ] Real HOST bridge tests with actual trade/member/payment owner and current C, finite relevant regressions.
- [ ] Minimal monotonic-revision ablation, byte restore GREEN, generators/checks; frozen SQL byte comparison.
- [ ] Freeze owned diff/hash and hand root for independent spec+quality review, no stage/commit/push/deploy.

JDK21 exact /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home.
All Maven: -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2.
Native: local cached mysql:9.7.2 only; DISK64M; DOCKER_CONTEXT=colima-hxkey-tests; Colima profile hxkey-tests;
MemAvailable>=524288KiB checked before each run; one own UUID, random loopback DB, --pull never, credentials env,
finally cleanup only own container/database. No other containers/VM changes/remote/shared autotest.

Core concrete runner family (after fresh report removal and explicit 512MiB preflight):
~~~sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
DOCKER_CONTEXT=colima-hxkey-tests BEAUTY_TEST_DISK=1 \
BEAUTY_TESTS='ServiceSourceObservationIT#refundBelowHeldQuantityBlocksLateOldObservation' \
node scripts/verify-beauty-persistence.mjs
~~~
Reuse runner infrastructure but implement dedicated --source-submission selection with secrets/fresh nonzero0skip
verification only for Task58 known classes, or a narrow Task58 wrapper calling the same infrastructure. Existing
runner lowMemory160MiB mode is prohibited. Actual selected XML must be newer than run start and nonzero/0skip.
No runtime claim until native server/JDBC selection and exact class counts independently checked.

HOST runner: extend scripts/verify-checkout-payment.mjs with an explicit --beauty-source mode, known
BeautySourceCheckoutIT selector, -Pbeauty-embedded and source-profile properties; retain actual V539/V540 pin and
existing real owner preparation. Apply beauty via official validator to the isolated DB, not handrolled table fixture.
Reject no selected Task58 classes in this new mode; do not claim global M1 arbitrary-selector debt fixed. Example:
~~~sh
CHECKOUT_PAYMENT_TESTS='BeautySourceCheckoutIT#lostQuoteResponseRecoversRealOriginalUnderCurrentManager' \
node scripts/verify-checkout-payment.mjs --beauty-source
~~~
Before HOST run, offline install current beauty reactor with -DskipTests (build only, not test evidence).
Use exact new test names above; if fixture runtime reveals a necessary rename, record it in report, not pretend old
selector ran. Existing owner finite regression selectors:
CheckoutSourceFactsFlowTest#unknownKeepsLastVerifiedPairAndReturnsNoConsumableLines+
refundBetweenFactReadsAndSerializedObservationNeverMixesSnapshots;
OfflineReceiptFlowTest#sourceExposesOneCanonicalOriginalReceiptAndCallsFinancialOwnerOnlyOutsideSnapshot;
CheckoutPaymentAuthenticationFlowTest#definitivelyUnsentOriginalDoesNotHideNewKeySuccessOrContraryLateMoney+
lateSuccessfulOriginalBlocksOpenedOtherAttemptBeforeFirstSend.
Use comma between classes, + only between methods inside a class selector.

Core finite regressions: SourceAllocationConcurrencyIT, ServiceSubmissionPersistenceIT, WorkOrderScopeIT selected
identity/parent/replay/snapshot cases, WalkInRecoveryIT original namespace and scope facts. Reactor/wiring,
BeautyArchitectureTest, BackendI18nParityTest, relevant owner retail managed-lifecycle/food unaffected-boundary selectors,
SQL portability and new plugin SQL check, entity/schema alignment and three relevant generators. Do not repeat
unrelated full backend or declare Task60 known debts cleared.

## 12. Prospective owned paths and documented decisions

Exact roots:
A=backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty
C=backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty
T=backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario

Create:
A/service/ServiceSubmissionService.java; A/service/WorkOrderBillService.java.
A/dto/SubmitServicesReq.java; RecoverSubmissionReq.java; ServiceSubmissionVO.java; SubmissionTaskVO.java;
SourceObservationVO.java; RecoveryRequestVO.java; WorkOrderBillVO.java; SourceBillVO.java; CurrencyBillVO.java;
ReceiptSummaryVO.java; BusinessTraceVO.java (all latter filenames under A/dto).
C/service/impl/ServiceSubmissionServiceImpl.java; WorkOrderBillServiceImpl.java; ServiceSubmissionQuery.java;
ServiceSourceObservation.java (all under C/service/impl).
C/policy/ServiceSubmissionAccess.java; C/policy/ServiceSourceFacts.java; C/entity/BtySourceObservation.java;
C/config/BeautySourceConfiguration.java.
T/ServiceSubmissionIT.java; T/ServiceSourceObservationIT.java; T/WorkOrderBillIT.java; T/SourceFixture.java;
T/BeautySourceWiringTest.java.
backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautySourceCheckoutIT.java.
docs/technical/design/schema/beauty-source-observation-schema.json;
scripts/gen-beauty-source-observation-schema.mjs;
beauty-core/src/main/resources/db/industry/beauty/V8__beauty_source_observation.sql (full plugin prefix above).

Modify:
A/service/WorkOrderService.java;
C/service/impl/WorkOrderServiceImpl.java; WorkOrderQuery.java; ServiceOperationQueryServiceImpl.java;
ServiceOperationRecoveryServiceImpl.java (all latter under C/service/impl);
C/mapper/BeautyMappers.java;
C/config/BeautyReceptionConfiguration.java; BeautyAppointmentConfiguration.java; BeautyDataScopeRegistration.java;
BeautySchemaValidator.java; BeautySchemaReady.java; BeautyPersistenceConfiguration.java (all latter under C/config).
T/BeautySchemaMigrationIT.java; existing required schema/wiring expected-version assertions only (exact additional
paths must be audited/recorded before edit, not broad test rewrites).
backend-plugins/shop-industry-beauty/beauty-server/src/test/java/ai/neargo/shop/beauty/scenario/BookingServerWiringTest.java.
scripts/verify-beauty-persistence.mjs; scripts/verify-checkout-payment.mjs; targeted runner tests if changed.
docs/technical/design/schema/beauty-api-design.json; beauty-java-method-design.json (same schema directory);
generated docs/technical/design/美业系统-API清单.md; 美业系统-Java类与方法清单.md;
美业系统-Controller-Service-Mapper方法清单.md; 美业系统-数据库结构设计.md; 美业系统-数据库字段字典.md.
Database generators read new schema source alongside existing sources; modify scripts/gen-beauty-database-design.py
only as necessary to include V8, never rewrite frozen V1-V7.
backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java and existing three backend/shop-app/src/main/
resources/i18n/messages{,_en,_ar}.properties for new explicit source errors only.

Exact new exception codes proposed:
BEAUTY_SOURCE_CUSTOMER_REQUIRED, BEAUTY_SOURCE_UNAVAILABLE, BEAUTY_SOURCE_QUANTITY_EXCEEDED,
BEAUTY_SOURCE_IDENTITY_MISMATCH, BEAUTY_SOURCE_REVISION_CONFLICT, BEAUTY_SUBMISSION_SCOPE_CHANGED.
Other expected waiting/blocking reasons are safe response state codes documented above; provide three-language
display keys with parity if added, not raw exception messages. Existing FORBIDDEN/NOT_FOUND/BAD_REQUEST/CONFLICT reused.

Excluded: shared owner product files, V1-V7/V539/V540, HTTP/remote controllers, BM05 start/consume/complete, Job/timer,
funds/price/order ledger, Task59/60 deployment, main TDD/progress/brief/ADR/pins/review files.

Root-review decisions:
1. Observed allocation and conservative no-release boundary, not shared atomic consumption (root accepted).
2. New V8 watermark literal with current epoch and highest/verified pair split; root pin still required.
3. GET updates only observation projection; may temporarily turn READY to UNKNOWN (root allowed with explicit docs).
4. Original PREPARE absence can remain unavailable without original B/reference; another B reads by original op only.
5. Partial-source amount attribution stays incomplete; no quantity-based money.
No unresolved business question is required to finish this design. Technical release and actual SQL pin remain pending.
Review focus: stale same-pair response after UNKNOWN; refund ceiling below held quantity; one sub linked to wrong
operation revision domain; current employee recovery without old proof; mixed complete/unknown/shared-source money.
Each has a named selector above. All runtime results remain NOT RUN.

## 13. Self-review before design handoff

- [x] Brief1-15 mapped to API/scope/identity/fence/recovery/bill/schema/tests/ownership sections.
- [x] Actual WorkOrderService and real Task56/57 signatures distinguished from historical drafts.
- [x] No future execution permit/provider invented; root refund and superseded-higher-response counterexamples have atomic local proofs.
- [x] Owner source hash timing and unique checkout revision domain independently traced.
- [x] Candidate SQL literal includes explicit NULL combinations and exact candidate bytes/hash.
- [x] Real HOST vs beauty-core fixture limits and genuine business RED/ablation specified.
- [x] No implementation/test/native/SQL execution or product files written in design stage.
