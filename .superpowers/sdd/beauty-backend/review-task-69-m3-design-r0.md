# Task69 M3 independent design review R0
Reviewer: /root/review_task69_m3_design (gpt-6-astra/xhigh). Source HEAD ae4c10cc124f80925719449da15aada4a97ec1ea.
Spec: NEEDS_FIXES. Architecture: NEEDS_FIXES. 0 Critical, 2 Important, 0 Minor. M3 coding release remains closed.
Design/report SHA match supplied R0 freeze. Static inspection only; no compilation/tests/serializers/Node/SQL/application/network/writes/agents/index/commits.

## I1 Important — V10 and lazy capability handling conflict with the existing startup schema gate
Design task-69-m3-design.md:242 and A2 inventory:255 create bty_service_execution_fence through V10 and promise capability checks at execution use, retaining ordinary boot without execution capability. Finite inventory omits both existing startup components.
BeautySchemaValidator.java:82 rejects private tables outside V1–V9 manifest; :107 explicitly requires current version9. BeautyPersistenceConfiguration.java:31 invokes it during bean creation, returns readiness9, and :43 requires9 for mapper activation.
Concrete scenario: valid V10-installed beauty database has new table/history10. Unchanged startup rejects table/version before lazy verifier can govern execution. Bundling pendingV10 also needs explicit existing strict migration-state validation handling.
Remedy: add these exact files/methods to A2, specify existing base schema gate, V9 historical fixtures, V10 installed capability and post-install drift coexistence; freeze manifest/version/readiness and matching composition scenarios. Preserve V1–V9 bytes/existing validation; no broad unknown-table/migration relaxation.

## I2 Important — promised beauty-side suspension has no concrete producer in implementation inventory
Design :139 recovery, :180 contrary writers and :255 inventory require observation changes notify/reconcile original attempts; Core recovery copies a committed beauty suspension. A1–A5 omit ServiceSourceObservation.java; neither inspectOriginal nor recovery specifies a transaction comparing current observation and producing suspension.
Actual ServiceSourceObservation.invalidateManual:681, claimManualLocked:704, completeManual:885 only update observation. completeManual:966 persists BLOCKED for contradictory line facts. WorkOrderBillServiceImpl.java:511 directly calls invalidation.
Concrete scenario: frozen intent/PREPARED grant, later actual observation BLOCKED/SOURCE_LINE_MISMATCH. Defined original-fence reads have no writer to suspend intent, leaving Core nothing committed to copy. PubliccanStartfalse remains but promised invalidation/M4 proof incomplete.
Remedy: select exact producer: wire actual observation writers to fence coordinator OR make original inspection/recovery explicitly reconcile current observation before active intent return. Specify canonical lock order, CAS, durable suspension propagation, temporary observation UNKNOWN vs definitive invalidation. Add exact files/methods and actual-owner scenario reaching intent/Core grant while retaining quantity/forbidding machine human-action dispatch.

## Checks and strengths
Permanent task/unit ownership, integer-only adoption, disjoint quantity accounting, UNKNOWN retention, live SELF and resource-vs-human separation consistent. ORIGINAL envelope feasible against actual invokeBound digest/operation suppliers and package-local codec reuse without old hash change. M4 stock/lock and M5/M6 dependencies explicitly gated. Review scoped to M3 integration, no closedC02 reopening.
Root independently confirmed both source gaps before fix dispatch. Actual V1–V9 beauty CREATE TABLE count28; proposed new fence means29 (history excluded), separate original41 target registry.
