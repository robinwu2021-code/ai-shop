# BM05 source-audited preparation

Status: PREPARATION_ONLY; no BM05 product code or acceptance claimed. Source snapshot: beauty-data worktree, Task59 implementation base40541bb74 plus ongoing uncommitted Task59; read-only preparation must be rechecked at accepted Task59/60A handoff. Owner: root.

## Authority and scope

Main TDD §0.2 governs: P1–P4 code/test/review/compile first, actual deployments last. This preflight does not change existing API/Java/DB contracts or mark later modules implemented. Expand exact design under TDD §2 before BM05 coding; retain independent task review.

Read: docs/technical/TDD-美业系统-共用商品订单与服务工作流.md §state/flows, TDD-共用资源容量与组合占用-SHR02b.md §6, design/美业系统-Controller-Service-Mapper方法清单.md, beauty-development-plan.json.

Task mapping from current49-task source:
- TASK-01: BAPI057/058/059/060/159 — listTasks, acceptTask, startTask, completeStep, getTask.
- TASK-02: BAPI027/028/061/062/063/105 — previewResourceChange, changeResources, previewHandover, handoverTask, interruptTask, listParticipations.
- TASK-03: BAPI064/065/066 — completeTask, previewClosure, closeWorkOrder.
- BAPI067 stage-plans is P4; do not count it as BM05 P1 closure.
- CAL-01 BAPI025/026/117/124 stays BM06. Task reads become its real task-summary input.

## Verified reusable code

- BtyServiceTask/ServiceStep/ServiceParticipation, WorkOrder and BeautyMappers already exist. ServiceTaskMapper.changeStatus/recordCompletion/switchBindingRevision, ServiceStepMapper.completeStep, ServiceParticipationMapper.endParticipation and WorkOrderMapper.freezeScope/closeFrozenScope are persistence operations, not a delivered task business service.
- WorkOrderServiceImpl currently provides open/read/self-read/source submit/bill; ServiceTaskService/Impl is not present. Implement actual Service with current-scope authorization, operation/audit/outbox transaction, checked mapper affected rows; no new Repository.
- Existing task status storage supports PENDING/IN_SERVICE/PAUSED/DONE/VOIDED. Acceptance must not create a fictitious IN_SERVICE transition or actual participation start.
- CheckoutPort has quote/order/payment/source reads; source observation is not an execution permit. ManagedServiceCompletionPolicy currently deliberately blocks generic COMPLETED for SERVICE_EXECUTION. Do not remove this block to make BM05 work.
- ResourceBookingPort provides booking command/hold reconciliation; current spi/resource inventory has no ResourceOccupationPort implementation contract. Add actual occupancy under shared resource owner and expose neutral typed API, never store shared capacity in bty tables.
- AppointmentAccess checks BOOKING_VIEW/BOOKING_MANAGE; task execution permissions and personnel-account resolution must be source-audited rather than reusing booking grants by accident. Personnel may have no account; actor identity is distinct from designated/arranged/actual personnel.

## Required design decisions before coding

1. Source execution authority under real trade/asset owner: exact request/result/state, durable original operation/fence, bound task/scope/binding revisions, quantity and contrary refund/cancel writer lock protocol. A paid observation plus subsequent task write is not race-safe.
2. Shared actual occupation: finite confirmed-hold unit handoff without increasing confirmedQty; original unit exclusive consumption and immutable history; dedicated amend/close/recovery; source/owner/unit parent scope checks.
3. Normal open mode only capacity1 with all future unreleased reservations checked under resource root lock. Expected end is display only. No limited calendar-window search in place of future conflict proof.
4. Overrun is independent persistent multi-reason block, including capacity>1; preserve future confirmed holds and create coordination facts. Command-time checks complement job scan; removing one reason cannot remove other active reasons.
5. Prepare/service/cleaning group intervals stay independent; pause/early service finish never silently releases. Late/early start outside reserved coverage first adjusts capacity.
6. Handover/amend locks old+new resource union, retains original participants/assignments and failed old capacity; same-resource extension only adds new interval units. Old released/bound units never reused.
7. Completion: actual performed quantity and owner fulfillment receipt are distinct durable facts; operation replay/callback cannot increment twice. Closing freezes scope and rechecks all task/step/participation/source versions and pending unknown actions.
8. New schemas append only after checking latest beauty/platform migration versions; no rewrites of beautyV1–8 or platformV537–540. Unknown writer/history cannot be papered over with generic IF NOT EXISTS.

## Proposed implementation boundaries (not released)

A. Shared execution source authority and contrary-writer fence.
B. Shared finite/open actual occupation, unit relations, amendments, cleaning and overrun.
C. Task scoped reads/acceptance/start with durable intents and real owners.
D. Ordered steps, participant handover, pause/resume/early end and resource change.
E. Quantity completion, owner fulfillment/recovery and frozen work-order close.
F. Typed public/private/remote routes, strict responses and assembly; reuse Task59 accepted neutral binding.
G. Compile/test/review/docs reconciliation. Actual deploy execution deferred to unified final gate.

Each released task must name exact files/types/methods and AC→tests. Above boundaries are preparation, not empty implementation scaffolds or guaranteed current owners. Need failing tests for paid-vs-refund race, same unit double start, resource change rollback, multiple overruns, cleaning preservation, duplicate completion, stale close scope, revoked grants and process-loss original-number recovery. Actual integration coverage requires real owners; fixture-only tests must be labelled.

No compilation/tests/deployment performed for this preflight; not a BM05 readiness or delivery claim.
