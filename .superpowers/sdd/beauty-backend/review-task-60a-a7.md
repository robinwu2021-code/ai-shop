# Task60A-A7 independent scoped review

Spec verdict: **PASS**.

Quality verdict: **PASS**.

No Critical, Important, or Minor findings in the reviewed A7 product diff. These verdicts apply only to A7 against BASE `3062d1b48d79d60e21c53113118aa6970c228a59`; they are not a whole-branch, BM04 completion, BM05, or deployment verdict.

## Scope and method

Reviewed the complete frozen three-product diff supplied at `/tmp/task60a-a7-review-package-8ab6dd46-462c-403b-83e9-fb42465e765e.diff`, package SHA-256 `65baf9782052fbda8d94d9d81775870c2ba6f3f87d61585ae11793c0a75b41c4`, against `.superpowers/sdd/beauty-backend/task-60a-a7-brief.md`. Read the implementer report, final manifest, ablation metadata, retained XML/sanitization metadata, relevant log evidence, cleanup evidence, and `/tmp/task60a-a7-root-evidence-gate.json`. A truncated tool presentation of the Java hunk was recovered for the omitted section; no changed source file was reread. No suite rerun, checkout/index/HEAD mutation, coding, or subagent delegation was performed. Only this report was written.

Focused source check outside the diff: risk that the inherited fixture introduces business-provider replacement or incompatible clock injection. Read `backend/shop-app/src/test/java/ai/neargo/shop/scenario/CheckoutFlowFixture.java`. It supplies the real Spring application, auth/goods SQL prerequisites, real catalog/member-code services, and a typed MutableClock bean; it does not supply the prohibited business spies/mocks or booking seeds. A7 retains that typed bean and selects its approved primary system-UTC Clock. No further external source search was needed.

## Spec assessment and strengths

- **Exact scope and reflection boundary:** the Java class extends CheckoutFlowFixture, is native-property gated, contains exactly the three named tests, and accesses beauty types only through its reflection helpers. Its only configuration changes are clock selection and the approved archive-only publication properties. Personnel creation and schedule publication use real services; SQL writes are limited to auth/goods prerequisites and the real grant change. The JDBC personnel lock does not seed business state. The root gate validates the exact product scope, production-source restoration, final compiled fingerprints, and default-retail compile.
- **Canonical identity and actual successful replacement:** `claimedGuestReplacementUsesCanonicalUserAndPreservesReceipts` performs registration, real guest appointment creation, claim through a manager-scoped member-code reference, fresh resolution for check-in, and opening with the original guestNo and explicit target-person assignments. It checks canonical USER work-order/context identity, one work order/task, REPLACE/SUCCEEDED, both replacement links and hold statuses, current assignment, released original units, six target units, and source plan/hash/revision/decision/fence reconciliation. It also checks the original appointment/claim history digests, opening/registration/claim replay receipts and effects, and unchanged financial-order/payment counts.
- **Live USR authorization:** `revokedUsrBookingGrantCannotReplaceClaimedGuestAppointment` uses a genuine nonowner USR account and store role, changes only the real permission list to remove BOOKING_MANAGE, demonstrates retained BOOKING_VIEW/CUSTOMER and successful real appointment read, requires FORBIDDEN for the same legal opening, and verifies the retained hold/resource/history facts and absence of replacement/work-order/task effects. The permission ablation fails at the intended thrown-exception assertion (`BeautyClaimedGuestResourceIT.java:276`), not at the later direct allowed=false assertion or a setup error.
- **Deterministic owner capacity refusal:** `failedClaimedGuestReplacementPreservesOriginalResourceHold` sorts actual personnel A<B, locks only A in an independent transaction, launches the real opening, waits for the real REPLACE ACCEPTED and source intent phase 1, then obtains a real confirmed peer appointment on B before releasing A. It requires opening CONFLICT plus command REJECTED/CAPACITY_UNAVAILABLE, retains both holds/assignments/units and bucket occupancy, checks source/reception terminal reconciliation and cleared fence, and excludes new holds/work orders/tasks. Rollback, bounded polling/future wait, cancellation, executor termination, and connection closure are present.
- **Runner preservation:** the runner adds only the exact new class and three method names to its finite selector machinery. The existing beauty default/class methods, payment selections, XML validation, credentials/redaction, serial lock, and resource lifecycle are unchanged by the diff. Added tests cover exact whole-class/method/plus selectors, dispatch arguments, unknown methods/class, payment-mode rejection, comma rejection, and three-method report inspection.
- **Sensitive facts:** registration/claim receipts and rows containing encrypted credentials/contact are compared as SHA-256 digests. Explicit logs and diagnostic projections expose business state rather than member codes, claim tokens, contact payloads, or session/internal credentials. Retained XML metadata records credential absence, freshness, and unchanged XML structure after sanitization.

## Verification evidence and interpretation

Independently parsed the retained XML and recalculated its SHA-256:

| Evidence | Tests/failures/errors/skipped | SHA-256 |
|---|---|---|
| native-three.xml | 3/0/0/0, exact three required methods | a4c2a4535510669d6ba0d65c659440de87dbbd4941e1b39b095a3d9990c7e755 |
| guard-ablation-red.xml | 1/1/0/0, exact revoked-grant method | 258ac934d9e07b41628117ba7dc828364907c4fa2aa737b7d35a9897be92bcdf |
| guard-restored-green.xml | 1/0/0/0, exact revoked-grant method | e0d71b55af2aa09159eb0c3d876e05c8dfc9d9da6b093f034e3d8ee936e34f99 |

The normal three-test run is distinct from the restored one-test run. The source/bytecode metadata and independent root gate support that only the allowed expression was ablated, exact production bytes were restored, and restored production source was freshly compiled. The gate additionally validates Node 20/20 pass, final default-retail compilation, exact hashes/scope and cleanup. `retail-final-compile.log:537` records BUILD SUCCESS. Cleanup evidence checks only the seven owned UUIDs and confirms their absence plus absence of the shared serial lock.

The earlier missing-publication configuration errors, HELD state failures, and approved same-command diagnostic replay are setup/diagnostic history, not successful business coverage or a second business RED requirement. The final code contains neither that diagnostic replay nor a business-provider replacement. The approved system-UTC selection addresses the fixture permit-time mismatch without production changes.

## Warnings and impact

The successful native output is not pristine. `native-three.log:579-639` retains duplicate mapper-scan notices, Flyway warnings for newer H2/MySQL versions, missing mapperLocations resources, optional logistics channel/phone-key warnings, test-profile OTP throttling disabled, a development ship-track key, Spring security auto-configuration warnings, macOS Netty DNS fallback, and the DEV-only seeder notice. `native-three.log:683-687` retains CDS/dynamic-agent warnings; the implementer also records Mockito self-attachment. The restored run has the corresponding categories at `guard-restored-green.log:607-667` and `709-713`.

These are application/test-environment/dependency warnings, not introduced behavior in the three-file A7 diff. Optional external logistics, SMS, payment and deployment flows are outside the exercised scenarios; native database-backed identity/reception/resource assertions passed despite these warnings. Flyway's newer-engine warning and development/security defaults limit the evidence to the specified isolated cached-MySQL development environment and do not establish deployment suitability. No warning was suppressed or reclassified as business success.

## Findings

Critical: none.

Important: none.

Minor: none.

## CannotVerify / limits

No A7 requirement remains unverifiable from the scoped source and supplied fresh artifacts. Runtime results were assessed from retained artifacts and the independent root evidence gate rather than rerun, as instructed. This review does not independently certify absent external effects beyond the owned-resource evidence, broader branch behavior, formal deployment configuration, BM05 execution, or P2-P4 capability.
