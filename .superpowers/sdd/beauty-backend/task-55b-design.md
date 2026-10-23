# Task55B implementation plan

Execution: sole dispatched implementer, native execution under approved Task55 design and binding brief; no subagents. Base: `0028130d103611d19d056581bf638126b16dd401`.
Spec: task-55b-brief.md and task-55-design.md §2/3/5/6/8/9/10.
Goal: verified B/C arrival, append-only reception exceptions, and internal frozen-scope REPLACE.
Architecture: reuse AppointmentPersistence root/fence/current transactions, AppointmentCoordinator durable source saga and one bounded AppointmentMatching Slot allocator. Every permission/identity/catalog/resource call stays outside local transactions. V1–V6 frozen, no migration planned.

## 0. Acceptance mapping and review focus

| Acceptance | Test |
| --- | --- |
| CONFIRMED→CHECKED_IN, unchanged schedule/hold and zero tasks/work orders | ReceptionBusinessIT.checkInKeepsScheduleHoldAndNoTasks |
| Server UTC EARLY/ON_TIME/LATE; now>=end requires reschedule | earlyLateAndEndedBoundaries |
| B current CUSTOMER+BOOKING_MANAGE, strict cr1/formal guest verify; C own mc1 and same-store live challenge | consumerNeedsOwnIdentityAndLiveStoreChallenge; BeautyReceptionIdentityIT actual host consumption |
| Root→fence→current→operation; missing/active fence/current unknown reject; cancellation race | activeFenceAloneRejectsCheckIn; checkInRacesCancel |
| Current permissions before original-key replay; no duplicate action/event; atomic rollback | exceptionReplayIsAtomicWithActionAndOutbox |
| Frozen old duration/buffer/requirements/policy/display price; expanded full indexes, new item numbers/mapping, future grid | reassignsCheckedInWithFrozenRules |
| Current unavailable catalog rejects; failure preserves old hold; UNKNOWN preserves fence/current/original operation | replacementRejectsWithoutChangingOldArrangement; replacementUnknownKeepsOriginalCommandAndFence |

## 1. Exact contracts and owned implementation units

API package ai.neargo.shop.beauty.dto: ReceptionIdentityProof, CheckInReq, CheckInProof, MyCheckInReq, ArrivalVO, MyArrivalVO, ReceptionExceptionReq, ReceptionExceptionVO follow exact Task55 §3 fields; redact proof-bearing toString. ReceptionService adds only checkIn, checkInMyAppointment, coordinateReceptionException; no 052/public wire.
Core package ai.neargo.shop.beauty:
- policy.ReceptionIdentity: verify B strict customer/formal guest token and verifyConsumerProof; reuse CustomerOwnership for full scoped receipt matching.
- service.impl.ReceptionPreparation: generalized find(entity,store,actor,endpoint,key,requestHash), begin(...,VerifiedIdentity,ReceptionPayload), sealed arrival/exception payloads; preserve claim methods/namespace.
- service.impl.ReceptionCoordinator: checkIn, checkInMine, coordinateException; immutable lock-outside hold evidence, root/fence/current/operation transaction; internal reassign for later 052 consumption. No work order/task creation.
- service.impl.AppointmentCoordinator.currentArrangementEvidence(root)→HoldEvidence(binding,hold,arrangementRevision,arrangementHash): current revision→arrangement→successful CREATE/CHANGE/RECEPTION_CHANGE operation with exact preview. Validate source/plan/receipt/result/hold. replacement/hold use this single path; retain explicit HELD reserve-cancel fallback. replacementForReception validates CHECKED_IN/no work order. Success preserves checkedInAt/status.
- service.impl.WorkOrderPreparation.fromAppointment(root)→PreparedWorkOrderScope: load immutable original definition/requirements/policy with scoped parent/hash, duration/qty/buffer/old-plan checks; verifyLocked only local observed heads. FrozenAppointmentScope/ObservedHead/HoldEvidence conform Task55 §8.3; task snapshot DTOs conform §3 but no task service.
- service.impl.AppointmentMatching.matchFrozen(scope,frozen,assignments,startAt,replacement): factor item input source, share original Slot/allocate/occupied loop; new interval facts from current resource/qualification/schedule/capacity, old rules/price preserved. Full expanded indexes, no implicit time movement; before buffer future; old→new item map in durable request evidence.
- AppointmentPreparationService.prepare adds internal RECEPTION_CHANGE→REPLACE with CHECKED_IN/no order/task and permanent fence checks; ordinary CHANGE remains CONFIRMED. AppointmentSourceAuthority repeats current state/no-order checks before permission, current CUSTOMER validation outside transaction.
- BeautyReceptionConfiguration wires helper DAG, existing injectable Clock. Mapper changes only local guarded writes/scoped reads; no schema changes.

## 2. Execution checklist

- [x] Add exact DTO/interface seams and real native business RED for arrival/reassignment (not compile/setup failure).
- [x] Implement current arrangement evidence, B/C arrival and exception; targeted GREEN.
- [x] Frozen preparation/shared matching/internal RECEPTION_CHANGE; success/reject/UNKNOWN GREEN.
- [x] Host real member cr1/guest/mc1 consumption; install shared/beauty first.
- [x] Remove service and SQL fence checks together: isolated active fence RED, restore GREEN.
- [x] Affected BM03/native, matching and normal reactor; relevant schema/architecture/i18n/SQL/generated checks.
- [x] Report exact commands/logs/counts/native-v-fixture boundary and owned paths; precise UTF8 staging excluding controller docs; commit and stop for independent review.

## 5. Environment and boundaries

JDK21 /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; offline m2 /tmp/codex-beauty-bm01-m2. BEAUTY_TEST_DISK=1; single cached mysql:8.4 --pull never --rm UUID container, own writable datadir, 64MB buffer/performance_schema off, localhost TCP readiness/finally own cleanup. Never touch other containers. Core external resource/catalog/personnel are controlled fixture boundaries; host member bridge uses actual services/tickets. Real owner deployment belongs Task60. No full backend/unchanged Task54 sweep.

Implementation detail: existing ActionLog.action VARCHAR(32) requires `RECEPTION_CHANGE_PREPARED` (25 chars), not generic APPOINTMENT_ prefix plus action. Internal reassign returns null for verified unchanged arrangement; otherwise the original BtyAppointmentOperation. It creates no work order. No migration needed.

Native integration refinement: existing fence acquire/intent authorize SQL only accepts HELD/CONFIRMED. Add a constrained CHECKED_IN branch only when intent.action=REPLACE and the scoped current pointer joins a PENDING RECEPTION_CHANGE operation, with no scoped work order or linked task. This is mapper SQL behavior, not a migration/schema change; ordinary CHANGE permissions/status remain unchanged. The expanded native REPLACE tests reached this exact acquire rejection before the change.
