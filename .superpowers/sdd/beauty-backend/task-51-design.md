# Task51 BM03 appointment service design

档位 2; requirements task-51-brief and original BAPI010/038–045/146–153/160, AVL01/APT01–03.
Controller owns main TDD §2.18 and ledger; this increment records implementation details only.

## Files and boundaries

- shop-base spi.resource.ResourceBookingPort: owner-neutral Scope/Actor/Accept/Receipt/Hold records and accept/query/execute/queryHold. Authenticated operations retain realm/subjectKind. Separate reconcile(binding) is trusted read-only recovery of an already persisted original binding; it cannot accept or execute. No consumer access added to ResourceCommandFactsPort.
- shop-base spi.resource.ResourceCandidatesPort: bounded scoped resource/personnel candidate enumeration plus interval capacity facts. Merchant port adapters own joins and capacity accounting; public enumeration excludes hidden personnel. Beauty never imports merchant implementation.
- shop-base-auth BizPerms: BOOKING_VIEW and BOOKING_MANAGE labels and assignable codes; built-in roles unchanged.
- beauty-api dto: original endpoint-named Req/Query/VO records; Service interfaces AppointmentService, AppointmentAvailabilityService, ServiceOperationQueryService retain generated method signatures. Items include SKU/quantity; requested assignments identify item/requirement/resource/personnel, never carry a trusted plan or hold.
- beauty-core policy AppointmentAccess: current LoginUser tenant/realm/subjectKind and live MerchantOperatorAccessPort; consumer exact owner; scoped CustomerIdentityPort resolution; guests receive server-generated reference.
- beauty-core service.impl AppointmentMatching: current store/product/policy/definition/requirements, candidate facts/schedules/capacity; bounded deterministic specified/automatic assignment, all quantities/requirements, buffers, immutable plan and item snapshots.
- AppointmentPreparationService: persistent preview and operation idempotency, stable owner accept bytes/key before external call. Source authority service depends only on local persistence/access, preventing executor/provider bean cycles.
- AppointmentSourceAuthorityPortImpl: exact persisted owner binding and frozen bytes, root→fence→intent lock, current permissions before locks, no remote calls in transaction.
- AppointmentCoordinator: short local transactions around remote calls; verifies original receipt and hold before finalization; no transaction across owner IO. Public facade delegates matching/preparation/coordinator/read functions.
- AppointmentRecoveryJob: existing JobHandler framework, bounded CAS lease; only queries original bound commands and reconciles terminals. Stored username is audit metadata, never used to construct LoginUser. ACCEPTED/unbound/next CONFIRM waits for authenticated same-key replay.

## Persistence V3

Append only V3, separate schema source/generator and strict manifest. V1/V2 frozen. V3 introduced readiness3; the approved V4 increment below makes latest readiness4 while preserving historical V2 validation.

- bty_appointment_preparation: previewNo, scoped owner actor realm/kind/user, canonical customer/guest, optional target appointment and expected root revision; policy version, start/end/timezone, exact plan TEXT/hash, immutable arrangement snapshot TEXT, expiry. Unique preview.
- bty_appointment_operation: stable operationNo plus unique scoped actor namespace/idempotency key hash, request hash, appointment/preview, requested action, expected root revision, current step, exact accept JSON TEXT, bound original receipt TEXT, operation state/result, version, nextRetryAt/leaseUntil. Durable before owner.accept; replay compares request hash and uses identical current accept params.
- bty_appointment_arrangement: appointment+immutable arrangementRevision, preview snapshot/current hold. Explicit current arrangement pointer in separate bty_appointment_current table; root.version is not arrangementRevision. Old items/assignments survive changes.

Initial create writes HELD root(version=1), permanent empty fence and operation in one local transaction, before reserve accept; HELD is not successful booking. RESERVE sourceRevision=1. Receipt binding then intent persisted and fence acquired. Successful reserve finalization sets root hold and persists a separate CONFIRM accept with new stable command key, expected owner version/hold revisions obtained from authoritative original result/hold. CONFIRM is independently frozen and executed; only matching CONFIRMED hold results in root CONFIRMED. Pending operation is returned throughout uncertainty.

Changes use REPLACE with old hold revisions, preserve old current arrangement until original success and new confirmed hold evidence; rejected replace leaves root and current arrangement untouched. Cancellation/no-show use RELEASE and only terminal proof allows status mutation. Reconciled intent, root mutation, arrangement switch and fence release are atomic under the same root lock. Fence does not expire with permission deadline.

## Transactions and authorization

Preview current facts calls precede insert transaction. Every public read/write authenticates before querying scoped facts. B requires view or manage for reads, manage for all writes/freeze/query. C requires CONSUMER/USR exact owner; original owner query only follows trusted source ownership. B MCH and USR namespaces never collapse.

Source provider freeze/query compares exact complete Binding against owner-originated receipt stored by coordinator, not public input. Existing fence remains through permission timeout; same binding always returns the immutable original decision/window; expired authorization must first obtain original owner terminal rejection and reconcile before a new authenticated operation can proceed. Concurrent operations serialize on root and durable pending operation; unique key handles first-create race.

Recovery read capability is limited to existing persisted binding and original terminal facts. It never creates a new command, obtains fresh permission, or impersonates original actor. Missing acceptance response requires current authenticated same-key replay because no accepted receipt has yet been bound. After reserve reconciliation the already persisted CONFIRM accept waits for authenticated replay. Query endpoints never advance workflows.

Nonempty sourceRefs/consent fail closed until payment/entitlement source contracts exist. BM03 never creates order/buyer/payment facts.

## Verification

TDD: DTO/permission contracts, candidate matching, native merchant adapter cases, private MySQL preview/idempotency/fence/lifecycle tests using explicitly controlled external owner fixtures. Scenarios cover spoofed source/actor, old revision, revoke, parallel same-root, lost accept and execute response, restart query reconciliation, failed replace history, confirmed owner alignment and no financial proof bypass. Ablation removes a core binding/ownership check and must fail. Actual deployed external owner chain remains Task53; Task52 owns HTTP and delegated actor transport.

CONFIRM rejected/expired after successful RESERVE leaves root HELD and operation RELEASE_REQUIRED; authenticated cancellation must execute actual RELEASE before root cancellation. No automatic capacity freedom or fresh actor authority.

## Approved increment V4 and matching semantics

V1/V2/V3 remain byte-frozen. V4 adds bty_appointment_resource_decision (entity, intentNo, commandNo, decisionNo, original fenceNo/fenceRevision) with unique scoped intent/command/decision. Authorization inserts metadata in the same root/fence/intent transaction; reads never synthesize missing metadata. Phase3 still replays the exact original decision after current actor authorization; the real owner's immutable terminal gate prevents reuse. Latest readiness is4; historical V2 upgrade remains tested.

P1 item qty expands sequential service instances. Each item has explicit serviceStartAt/serviceEndAt; next item begins after prior cleanup rounded up to a5minute bucket plus the next preparation buffer. Root service span includes these inter-item gaps; assignment intervals expose preparation/cleanup beyond service times. STAFF qty requires distinct people; physical capacity may fill multiple units with separately accounted groups. Every proposed group increments canonical resource/bucket usage. Candidate enumeration cap50 and search cap48 slots are explicit through AvailabilityVO.complete; incomplete candidate exhaustion raises IncompleteSearch rather than definitive unavailable. Preview is consumed once by CREATE, under its row lock; same-key replay returns the existing operation.

Authenticated manual recovery: ServiceOperationRecoveryService.recoverOperation(entityNo,currentStoreNo,actorNo,operationNo) returns OperationVO. A different current same-store manager with BOOKING_MANAGE can resume exact persisted bytes/key/command, without changing original actor namespace, request hash or initiator. Coordinator requires the operation still be the current pending operation; GET remains read-only. Task52 owns the new explicit POST transport.

Product version evidence: additive nullable goodsRevision/skuRevision fields on ServiceProductFactsPort.Fact and ServiceCatalogFactsPort.Service preserve legacy constructors as explicit null. Product owner selects real BaseEntity.version values, including legitimate0. Booking requires observed nonnull nonnegative master revisions, freezes them with actual price/duration, and revalidates all four before preparing a new CREATE/CHANGE. These revisions do not version store/campaign quote overlays and do not imply an atomic or locked quote; money/order source remains closed.

Request audit evidence uses existing bty_action_log in the same operation-prepare transaction: cancel/change reasons and no-show evidenceRef are preserved with operationNo; CREATE records previewNo only, never a transient member ticket. Caller transactions are rejected before live actor checks, keeping remote authorization outside source locks.
