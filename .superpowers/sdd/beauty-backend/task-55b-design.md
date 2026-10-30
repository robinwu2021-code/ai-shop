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

## Fix round 1 — I1 quantity-preserving arrangement comparison

FIX_BASE `115d1cb74a71ef90dcbbdd9b232d15f18eb40522`; review Important I1 verified against supported ROOM qty and capacity accounting. Before product edits:
- Add parameterized `ReceptionBusinessIT.receptionRetainsRepeatedResourceUnits` (KEEP, CHANGE_RESOURCE, CHANGE_TIME). Publish STAFF qty1 + ROOM qty2 through actual ServiceDefinitionService, preview/create through actual bounded matching and durable saga; controlled room ROOM1 capacity2 yields persisted [staff,ROOM1,ROOM1]. Each case asserts fixture shape before reception. KEEP empty requests returns null with no REPLACE; explicit [staff,ROOM1,ROOM2] same start executes REPLACE; empty requests/new time executes REPLACE retaining two ROOM1 units. Assert explicit duplicate requests remain rejected.
- `ReceptionCoordinator.reassign`: separate null/empty implicit retention from explicit requests; reject exact explicit duplicates before unchanged shortcut. Replace independent old-row count with one-to-one consumption of an existing-assignment multiset (resource-constrained requests first, then personnel-only requests). Unchanged requires all requested and existing units consumed plus original start. All scope/permission/evidence guards remain before shortcut.
- `AppointmentMatching.matchFrozen`: retain explicit duplicate guard on existing public method; add package-private overload with server-derived retained-assignment flag for coordinator only. Both paths use the same allocatePlan/Slot allocator. Change allocatePlan consumed bookkeeping from value Set to number of matched specified units so trusted retained duplicates count separately; ordinary matchComplete explicitly retains duplicate rejection before allocation. No new public request/wire or matcher copy.
- RED first against current product, then targeted GREEN: 3 new quantity invocations + affected existing reception frozen/reject/unknown/partial/buffer/permission cases and AppointmentMatchingSearchTest10. No full115/native48/HOST sweep. No migration or C/D scope changes. M1 only classify existing environment/negative-test warnings in report.

Investigation gate: the first proposed I1 correction above is pending controller ruling. Native real CREATE cannot persist repeated same-resource units because frozen assignment index2 rejects them, while existing AppointmentMatchingSearchTest explicitly accepts their allocation. No product changes may proceed on a fabricated reachable-state assumption. `/tmp/task55b-fix1-red2.log` records the persistence conflict, not reception RED. Candidate semantic choices and impact have been reported to the controller; V1–V6 remain untouched.

### Controller ruling: preserve shared capacity units; additive V7

Controller selected the established BM03 capacity-unit contract. V7 replaces only `bty_appointment_assignment.uk_bty_appointment_assignment_2` with ordinary `idx_bty_appointment_assignment_1` on the same ordered columns. `planned_assignment_no` uniqueness, all CHECKs, every existing row and V1–V6 bytes remain unchanged; every unit keeps its distinct business assignment number. No dedup/collapse, ordinal/backfill or resource uniqueness restriction.

Truth source `beauty-appointment-assignment-units-schema.json` plus a frozen additive generator creates `V7__beauty_appointment_assignment_units.sql`. Before the first execution, send its actual path/SHA to controller and await hash-record release. Then extend BeautySchemaValidator exact hashed V7 manifest transform, readiness/write guards to7; strict native upgrade from6 must preserve existing rows and business-key uniqueness, admit repeated resource units, and reject index drift. Relevant generator/docs reflect V7 without rewriting V1 inputs. Only after this database correction, run the unchanged reception comparison/matcher against the three quantity cases to obtain actual I1 RED, then implement the comparison/retained-unit plan above. Run targeted matching/capacity and reception regression; no full-suite sweep.

Allocator refinement before final fix: consumed accounting must be a Set of specified-list indexes, not an integer. Original ordinary item.qty expansion legitimately reuses the same explicit requested-item choice across expanded items; count it once by input index. Trusted repeated retained assignment records have different indexes and therefore remain distinct units. Add `ordinaryExpandedQtyReusesOneExplicitSelection` to demonstrate the intermediate integer-count bug before correcting it; retain `physicalCapacityCanBeSharedAcrossDemandUnits` unchanged and add cross-item unit/capacity/explicit-duplicate checks. V7 native cases: preserve complete V6 row and prior checksums, same resource UNIT2 allowed, duplicate business UNIT1 denied; missing/nonunique→unique/reordered index, missing business unique and V7 history-checksum drift all reject strict validation.
