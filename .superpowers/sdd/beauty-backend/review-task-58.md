# Task58 independent review

**SPEC FAIL · QUALITY NEEDS_FIXES**

**Critical: 0 · Important: 5 · Minor: 1.** This is the Task58 local-kernel gate, not the Task60 branch/deployment gate. No product, index, HEAD, migration, build, deployment, or test execution was changed by this review.

## Scope and evidence

Reviewed the brief and design before implementation, the implementer report, project CLAUDE/Java standards, and the task-reviewer template. Reviewed all Task58 changes in the supplied immutable diff, including production code, DTOs, SQL, conditional wiring, tests, native runners, schema generators, and generated documents. Independently checked the package: **732600 bytes; SHA256 `3e4c62f835b45fa62a953bc46b7d0ee0607d934c0b34cec66194bd32a84f17f9`**. Scope is the supplied 68-file change from base `8cc2482adaa8e3f3df469307e92e78fadd5b58b3` to supplied staged tree `63140e3b010e605c53c716f1a992f1c95c9485d0`; no git commands were run.

Read the implementation diff in bounded chunks, recovered truncated output, and extracted short finding locations from that same immutable package. Required brief/design/report were read directly. No changed implementation file was separately reread; the one mapper inspection described below concerned an unchanged method omitted from the diff.

Independently parsed the archived XML and verified each XML SHA against its summary:

- `final-core`: **92 / 0 failures / 0 errors / 0 skipped**.
- `final-host`: **4 / 0 / 0 / 0**.
- `final-gates`: **68 / 2 / 0 / 0** in aggregate: plugin selections 45 passing; platform selections 23 with 2 architecture failures.

No suite was rerun. The passing XML is valid evidence for its executed scenarios; it does not cover the missing terminal-owner and EXISTING recovery-receipt cases below. The bill test currently asserts incomplete for a BLOCKED source, so that green assertion does not establish the required financial-completeness separation.

## Spec compliance

The implementation substantively delivers the authorized local Service/persistence scope: real consumer resolution, immutable endpoint receipts, durable original intent, lock-free owner reads, canonical observation fencing, local quantity guards and binding CAS, source-level bills, and conditional beans. BM05 remains closed and `canStart=false`; there is no new order/payment/price ledger or B-side confirm/place/pay implementation. Append-only V8, the ready8/28 proposal, and Task59/Task60 boundaries are represented consistently in the diff.

It nevertheless misses explicit contracts for definitive owner rejection, recovery receipt identity, known-source invalidation, historical projection during availability failures, and financial completeness. These are observable behavior gaps, not merely documentation preferences.

## Important findings

### I1. Definitive owner rejection strands the submission in a recoverable lease forever

**Location:** [ServiceSubmissionServiceImpl.java:882](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSubmissionServiceImpl.java:882), with [BeautyMappers.java:761](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/mapper/BeautyMappers.java:761) and [BeautyMappers.java:795](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/mapper/BeautyMappers.java:795).

`resume` commits a RECOVERING lease before reading the owner. `verifyResult` accepts only QUOTED/CONFIRMED/PLACING/CREATED; a genuine REJECTED or REQUOTE_REQUIRED result throws CONFLICT. The catch does not finalize this result, and the new finish mapper admits only SOURCE_PENDING or BOUND. The real owner returns REJECTED for a RELEASED create intent and propagates stored REQUOTE_REQUIRED phases. This is therefore reachable with the actual owner, not only a hypothetical malformed port response.

**Impact:** the row remains RECOVERING until lease expiry, then every authorized retry repeats the same failure. Active-submission selection still includes it, and overlapping new PREPARE submissions are rejected. The definitive failed purchase cannot reach the designed terminal result or free that active-intent exclusion. Design phase table explicitly requires actual owner REJECTED/REQUOTE_REQUIRED to become a reasoned REJECTED local outcome, distinct from UNKNOWN.

**Fix:** verify owner identity independently of phase; handle the actual terminal phases explicitly with a current-lease/version CAS to REJECTED and a safe persisted reason, preserving any historical facts and invalidating known source evidence as required. Add a terminal-owner recovery test asserting lease termination, immutable replay, and subsequent active-selection behavior. Do not infer rejection from transport errors.

### I2. EXISTING-source recovery receipts return the wrong operation identifier

**Location:** [ServiceSubmissionServiceImpl.java:616](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSubmissionServiceImpl.java:616); consumer [ServiceOperationRecoveryServiceImpl.java:47](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceOperationRecoveryServiceImpl.java:47).

The immutable RecoveryRequestVO sets `operationNo` to `command.operationNo()` (the checkout operation). The design specifies the original local `submissionNo`; the generic operation query/recovery dispatcher looks up that local identifier. PREPARE masks this because its identifiers coincide. An EXISTING submission has a distinct local submission number and existing checkout number.

**Impact:** following the accepted receipt's operationNo cannot recover/query that submission through the unified dispatcher; it normally falls through to an unrelated legacy lookup and NOT_FOUND. The incorrect identifier is permanently stored in the acceptance receipt and replayed.

**Fix:** write the local `number` into operationNo, keeping checkout identity in its existing checkout field elsewhere. Test an EXISTING submission whose identifiers differ, receipt replay, and dispatch using the receipt's returned operationNo.

### I3. Binding eligibility incorrectly controls financial completeness and currency totals

**Location:** [WorkOrderBillServiceImpl.java:381](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderBillServiceImpl.java:381), [WorkOrderBillServiceImpl.java:399](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderBillServiceImpl.java:399), [WorkOrderBillServiceImpl.java:522](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderBillServiceImpl.java:522).

After validating current canonical money and attribution, the implementation adds SOURCE_OWNER_BLOCKED whenever `observed.ready()` is false, then defines source completeness as `reasons.isEmpty()`. Currency aggregation requires every source to be complete. Thus a fully attributed, current, financially complete historical source that is BLOCKED after refund/cancellation forces all its currency totals to null even though every required owner monetary fact is known and verified.

**Impact:** the bill conflates operational bind eligibility with financial completeness. This fails the explicit separation in the design's source-completeness criteria and hides valid refunded/received/outstanding currency totals for historical orders. The source-row amounts remain visible, so this is not fabricated money; it is an incorrect incomplete result.

**Fix:** compute financial completeness from canonical pair validity, financial/receipt consistency, amount availability, and attribution. Preserve the operational blocker and `canStart=false` independently. Add a fully attributed, complete BLOCKED/refunded-source test asserting correct complete currency totals; retain null/incomplete behavior for UNKNOWN and partial allocation.

### I4. A findOperation NOT_FOUND bypasses the required known-source fence

**Location:** [ServiceSubmissionServiceImpl.java:815](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSubmissionServiceImpl.java:815).

The inner readQuote NOT_FOUND path invalidates known evidence, but after readQuote succeeds a NOT_FOUND from `findOperation` reaches the outer BizException catch. That catch only handles CHECKOUT_DEPENDENCY_UNAVAILABLE and immediately rethrows NOT_FOUND. No `invalidateKnown` call is made and the lease is left RECOVERING. The accepted design explicitly covers original absence from either readQuote or findOperation before source() is reached.

**Impact:** an already known canonical observation can remain READY with its previous epoch, so pre-existing tickets are not fenced by this failed original-operation lookup. Independently of the lease left behind, another pending local binding using that canonical observation can still pass the old observation check. The required outcome is UNKNOWN with preserved historical watermarks until a fresh complete owner read.

**Fix:** handle authenticated original-operation absence in this recovery path by invalidating the known canonical observation and recording the appropriate original-number pending outcome. Preserve propagation of auth/programming errors. Add a focused case with readQuote succeeding, findOperation returning NOT_FOUND, and a prior ticket proven unusable afterward.

### I5. Two bill failure branches discard the historical source projection they just preserved

**Location:** [WorkOrderBillServiceImpl.java:263](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderBillServiceImpl.java:263) and [WorkOrderBillServiceImpl.java:298](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderBillServiceImpl.java:298).

The source reader loads historical order/suborder/observation and, on a missing provider or non-CREATED owner phase, calls invalidateKnown and computes an unavailable historical projection. Both branches then call `pending` with null order/suborder and null observation instead of those preserved values. The transport/NOT_FOUND catch branches do preserve the history, making equivalent availability outcomes inconsistent.

**Impact:** an already known/bound source loses its order/source identity and lastVerified pair in bill/trace output precisely when current owner evidence is unavailable. The database history remains intact, but the required historical visibility disappears. This violates the explicit pre-source failure contract at design lines 864–871; it also projects every non-CREATED phase as AWAITING_CUSTOMER without preserving the known history.

**Fix:** pass the stored historical order/suborder and the newly invalidated observation projection into pending results in both branches, while keeping current monetary fields null. Test a previously bound source after provider removal and after loss of CREATED-phase evidence.

## Minor finding

### M1. The cross-action idempotency race test assumes which contender wins

**Location:** [ServiceSubmissionIT.java:894](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ServiceSubmissionIT.java:894); invoked for derived actions at [ServiceSubmissionIT.java:1066](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ServiceSubmissionIT.java:1066).

The race helper is used with one SUBMIT_SOURCE and one ADD_SOURCE contender sharing an endpoint key, but unconditionally asserts exactly one SUBMIT_SOURCE row. Its concurrent scheduling permits ADD_SOURCE to win legitimately; in that execution the correct permanent winner is ADD_SOURCE and the assertion fails.

**Impact:** the gate can fail nondeterministically despite correct endpoint-wide conflict behavior; the recorded passing run exercised only a favorable winner.

**Fix:** assert exactly one owned source submission across both actions and that it matches the winning response/canonical receipt. If a particular winner is necessary for a different test, explicitly order that scenario rather than relying on scheduling.

## Code quality assessment

Strengths: observation and binding are separated from orchestration; persisted verified lines contain identity/quantity rather than a money ledger; lock ordering and version predicates are explicit; receipt replay compares full namespace/payload hashes; current authorization is rechecked rather than replaying the old actor; native concurrency tests exercise real database constraints; generated source contracts and conditional wiring are included.

The two large new orchestration/query classes (1085 and 660 lines) deserve continued decomposition when fixing these paths. Size alone is not the reason for this verdict: the concrete problem is inconsistent phase/error handling and projection construction across recovery, query, and bill paths, demonstrated by I1/I4/I5. A small shared, explicit outcome model or projection helper would reduce those divergent branches without introducing a repository abstraction or circular Service calls. The listed behavioral defects require fixes before approval.

## Warnings and verification limits

- **Warning, nonblocking within this scoped review:** the report at lines 462–469 records Flyway MySQL9.7 versus verified8.1/H2 compatibility warnings, Mockito/ByteBuddy/CDS notices, existing mapper-scan/alias notices, and HOST auxiliary configuration/dev fallback notices. These are not asserted to be new Task58 defects, and no warning is treated as a test pass. The required native version itself explains the Flyway compatibility warning; changing the prescribed environment is outside this review.
- **Known failed gate, not cleared:** archived ArchitectureTest XML confirms `noFullyQualifiedReferences` fails on `OpsPointsInspectionController.java` and `topLevelPackagesMustBeRegistered` fails on `store`. Those locations are not changed in this Task58 diff; the brief independently lists these debts. This supports the report's attribution, but the platform gate remains FAILED and cannot support branch/deployment approval.
- **⚠ Cannot verify:** the complete historical order of first-phase read-only work, implementation release, first SQL execution, RED, and restoration cannot be independently reconstructed from a final diff and final XML. The report and root-provided release/pin facts are evidence, not a reviewer-observed execution chronology. No claim that every RED/ablation command was rerun is made.
- **⚠ Cannot verify:** root supplied the staged-tree/index/HEAD and frozen old-SQL checks; this reviewer intentionally ran no git commands. The immutable package hash was independently verified, not the live index identity. No deployment, live environment, Task59 transport, BM05 execution, or full refund/reverse API acceptance is claimed.

## Focused outside-diff inspection

Only named dependencies needed to resolve concrete risks were inspected:

1. `AppointmentAccess.java:20–63`: checked whether callers could enter source/identity work under an existing transaction despite no explicit guard on every new public method. The current-access boundary rejects an active transaction; this risk was resolved.
2. Unchanged `BeautyMappers.java:504–537` task query: checked full work-order/task/appointment-item parent filtering omitted from the displayed query hunks. It enforces scoped/live parents and scope bounds; no finding for that suspected omission.
3. `CheckoutServiceImpl.java:322–330,674–710,769–781` and targeted phase locations: checked whether REJECTED/REQUOTE_REQUIRED are real owner outcomes and whether readQuote/findOperation can expose them. They are; this supports I1. No owner code was changed.
4. `WorkOrderPreparation.java:180–247`: checked whether multi-item additions preserve ordered frozen expansion, since the new append path consumes prepared item/definition lists. The existing helper preserves request order and expands quantities into individual tasks; no defect was attributed to that helper.

## Assessment

**Do not pass the Task58 gate yet.** Fix I1–I5, correct the race assertion, and add focused behavioral coverage for the missed branches. Re-review the resulting scoped diff and its relevant fresh evidence. The current green suites remain useful evidence but do not override these concrete contract failures. No claim is made about readiness of the whole historical BM04 branch or deployment.
