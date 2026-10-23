# Task55D — R2 scoped re-review

## Scope

Reviewed only open **N1** from `/private/tmp/review-bm04-55d-r1.md` and breakage introduced by the R2 fix diff. I1–I4 remain closed; M1 remains deferred. This is not a fresh review of Task55D or the branch.

Snapshot supplied and independently verified by the root: `8be377cc06b1e7320b469aa94b6ef448bdffd678` → `93eb6c32c37235bc52c87f48b935e3a7be6eae3b`; HEAD remains `612b8c08144d0aaa81959d4c455ab1dcc3d4404d`. Reviewed all 328 lines of `/private/tmp/task55d-r2-fix.diff` once, in non-truncated chunks. Supplied diff SHA256: `bb536c0958273719b8eac1f1c554e678a3e6924a77ef2a50d5d3a4663a148158`.

## Finding Verdicts

**N1 — “Important [P2]: Recheck CREATE time after the newly added head locks.” — ADDRESSED.**

- [AppointmentPreparationService.java:332](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentPreparationService.java:332) now reads the shared Clock and checks every frozen resource interval after `verifyCreateLocked(match)` and all preparation/link writes, immediately before the transaction callback returns. Equality with the earliest start is expired. The prior check before the blocking head verification is removed. No additional lock acquisition, RPC or business write follows the new check inside that callback.
- Expiry throws the dedicated unchecked `ReceptionPlanExpired` exception at line 335. It escapes the preparation transaction and rolls back the root/fence/current/preview/inner-operation and outer-link changes. [ReceptionCoordinator.java:122](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionCoordinator.java:122) catches only this exception outside preparation and returns the existing `ARRIVAL_REQUIRES_RESCHEDULE` original-operation failure path. Thus the subsequent command advance is not reached. The change does not turn head/version conflicts or authorization failures into expiry.
- The original namespace, frozen plan and identity remain intact; the failure update is separate from the rolled-back CREATE. The fix preserves existing lock ordering and keeps RPC outside the transaction. The new Clock is the same named reception Clock already used by the recovery/final-write paths.
- [WalkInRecoveryIT.java:52](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/WalkInRecoveryIT.java:52) exercises the requested unchanged-head lock wait: an independent connection holds the actual definition row with `FOR UPDATE`, recovery runs as authorized employee B after A is revoked, the clock crosses the earliest raw interval while the worker is in the production head query, then the blocker releases. Assertions cover no owner accept, PENDING/reschedule, no half-linked preparation rows, original phase and immutable original identifiers/key/request/payload/link fields.

The genuine pre-fix failure is independently present in [task55d-r2-red3.log:109](/private/tmp/task55d-r2-red3.log:109): 1 test, 1 assertion failure, 0 errors, 0 skipped; the empty-owner-accept assertion instead observes RESERVE and CONFIRM. The final run is [task55d-r2-green-final.log:489](/private/tmp/task55d-r2-green-final.log:489): 12/0/0/0, BUILD SUCCESS. This evidence supports the behavior change rather than merely a passing fixture.

## New Breakage in the Fix Diff

**None found. New Critical: 0. New Important: 0.**

The added constructor dependency is wired through `BeautyAppointmentConfiguration`. A specific root-raised risk about the older BM03 `AppointmentBusinessIT` fixture was checked without expanding the review: [AppointmentBusinessIT.java:136](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/AppointmentBusinessIT.java:136) loads `BeautyAppointmentConfiguration`; [BeautyAppointmentConfiguration.java:47](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyAppointmentConfiguration.java:47) imports `BeautyReceptionConfiguration`; [BeautyReceptionConfiguration.java:73](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyReceptionConfiguration.java:73) supplies `beautyReceptionClock`. Its inherited runner adds persistence, and the fixture does not replace the import chain. The suspected missing-Clock regression is therefore not present. Existing targeted wiring evidence is 8 conditional + 2 server wiring + 13 architecture = 23/0/0/0, BUILD SUCCESS ([task55d-r2-wiring.log:69](/private/tmp/task55d-r2-wiring.log:69), line 137, line 139).

## Test Quality and Strengths

The concurrency regression uses a real independently owned row lock and the actual mapper/helper call path. Its barrier requires the designated unfinished worker to have `selectScopedForUpdate`, `WorkOrderPreparation.verifyCreateLocked` and the JDBC socket wait in its stack before clock advancement; the blocker remains held until afterward. Even observing socket polling just before MySQL records a lock wait cannot allow that locking query to complete before the test releases its own row lock. Consequently this synchronization establishes the relevant pre-return stage and cannot silently pass by advancing time before the worker reaches head verification.

The volatile fixture clock makes the cross-thread time update visible. The test releases its blocking transaction in `finally`, waits for the worker and checks owner effects before accepting the result. The rollback table assertions and immutable-field comparison make it stronger than a return-code-only test. The production fix is localized and uses a dedicated exception to preserve ordinary failure classification.

The stack barrier is coupled to the JDK 21/JDBC stack shape, including `sun.nio.ch.Net.poll`. A future JDK/driver change may require adjusting the test; it should fail at the barrier rather than falsely establish coverage. This is a real, acknowledged maintenance limitation, not an Important defect in the current fix. The report accurately avoids claiming final GREEN was synchronized through a monitoring-table `LOCK_WAIT` observation.

## Out-of-Scope Observations

No new out-of-scope finding is raised. Original Minor M1 remains deferred to the whole-branch review. Original I1–I4 were not reopened.

## Checks and Evidence Boundaries

- Followed the scoped re-review method and the already-read binding brief/design; read the current report's R2 section, the complete R2 diff, the original N1 statement and the existing evidence logs. The report distinguishes the genuine RED3 from prior fixture/observation failures; those earlier runs are not counted as behavioral RED or GREEN here.
- Confirmed the existing 12-test native GREEN and 23-test wiring GREEN above. The native selector also covers prior expiry/final-write, changed-head, six atomic rollback, original-plan reschedule and current-employee recovery regressions. No suite, Docker or test command was rerun.
- [task55d-r2-checks.log:1](/private/tmp/task55d-r2-checks.log:1) records all three design-generator checks, the existing SQL gate and V1–V7 unchanged hashes. The R2 diff introduces no DDL. This does not expand the SQL gate beyond its stated coverage, nor re-prove migrations or deployment behavior.
- The only focused outside-diff source check this round was the specifically named Clock-fixture wiring risk described above, including the inherited runner and configuration import/provider. No broader repository crawl was performed. Root-supplied tree/ownership/SHA/whitespace verification was not duplicated; no git command, checkout/index/HEAD mutation, product write or subagent was used. Only this temporary report was written.
- Cannot independently claim from R2 evidence: a newly rerun full ordinary reactor, native BM03 suite, deployed owner HTTP faults, alternative JDK/driver stack shapes, or server monitoring-table LOCK_WAIT synchronization. Historical earlier runs remain historical. These are evidence boundaries, not identified code defects.

## Verdict

**Fix round: APPROVED. N1 ADDRESSED; 0 findings remain open in the R2 review scope.**

**Scoped spec compliance: PASS.** The newly introduced head-lock wait can no longer commit an already-expired original CREATE preparation and advance a new resource command; expiry rolls it back and returns the original reschedule-PENDING result.

**Scoped code quality: APPROVED.** The exception boundary, shared-clock wiring, atomic rollback and concurrency regression are coherent. No new Critical or Important issue was found. This conclusion does not accept or commit Task55D, reopen I1–I4, or close deferred M1.
