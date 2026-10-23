# Task55B — verified arrival, reception exceptions and frozen replacement

Base: `0028130d103611d19d056581bf638126b16dd401`; branch `codex/beauty-foundation`. Scope is the binding task-55b-brief.md and approved task-55-design.md §2/3/5/6/8/9/10. The implementation plan was recorded in task-55b-design.md before product behavior edits. Existing Tier2 design implementation substep, continuing main TDD/PRD/ADR045; this attachment is not a new Tier1 project; sole B implementer; no subagents, merge, push or shared deployment. Commit: the commit containing this report (full SHA supplied in handoff).

## Delivered behavior and boundaries

- `ReceptionService.checkIn`, `checkInMyAppointment`, `coordinateReceptionException` and exact DTOs now execute real local business behavior. B requires live CUSTOMER and BOOKING_MANAGE plus strict current-namespace cr1 or verified formal guest token. C requires CONSUMER/USR, own mc1 and a live same-store arrival challenge. Owner/fact calls run outside local transactions.
- Arrival locks root → permanent fence → current → reception operation, checks revision/current successful arrangement and observed hold/identity/times, then atomically writes CHECKED_IN, server UTC time, ActionLog and safe Outbox. No hold release, task or work-order creation. Current permissions precede successful original-key replay. End boundary returns localized BEAUTY_ARRIVAL_REQUIRES_RESCHEDULE (80105).
- `currentArrangementEvidence` selects the successful operation matching the current arrangement preview, avoiding later failed change receipts. Source, plan, receipt, hold and arrangement hash are checked. Root revision and resource source revision are intentionally separate.
- Internal `ReceptionCoordinator.reassign` is CHECKED_IN-only with no order/actual task. Frozen local immutable definition/requirements/policy, original display price/duration/buffers and complete expanded item indexes feed the existing bounded Slot allocator. Current service availability, resource/personnel/schedule/capacity remain live facts. New item numbers and old→new mapping accompany whole-plan durable REPLACE. UNKNOWN retains original command/current/fence; rejection preserves old arrangement; success preserves checkedInAt/CHECKED_IN. A verified unchanged assignment/start returns null for later C consumption.
- Exception coordination appends PENDING_REVIEW request, scoped actual task references, ActionLog and safe Outbox atomically; reason remains local. It does not execute the proposal or claim that a shared approval/todo owner exists. Active resource fence remains untouched.
- V1–V6 unchanged. Mapper acquire/authorize now permit only the constrained CHECKED_IN + REPLACE + current PENDING RECEPTION_CHANGE + no order/task case. Ordinary CHANGE stays CONFIRMED. Approved 55A strict claim/identity/idempotency behavior remains intact.
- No 052 public method, Controller, remote wire, work-order/task creation, walk-in, trade/payment or Job added. Default retail compiles through reflective HOST tests; existing disabled/standalone/embedded wiring tests pass. Real owner deployment remains Task60.

## AC → executed tests

All names below belong to ReceptionBusinessIT unless qualified. Initial complete suite has 17 invocations (four parameterized timing cases); two added boundary tests bring final distinct reception coverage to 19.

| Acceptance | Evidence |
| --- | --- |
| Arrival state/revision/time, schedule/hold unchanged, no tasks/orders, original replay | checkInKeepsScheduleHoldAndNoTasks |
| Own C proof and live store challenge, wrong/expired/foreign challenge denied | consumerNeedsOwnIdentityAndLiveStoreChallenge; consumerRejectsGuessedExpiredAndForeignStoreChallenges |
| EARLY/ON_TIME/LATE and now=end rejection | earlyLateAndEndedBoundaries (4 invocations) |
| Missing/active fence, unknown current, isolated fence guard | missingFenceOrUnknownCurrentRejectsCheckIn; activeFenceAloneRejectsCheckIn; dual-layer ablation below |
| Current namespace/scope/permissions even on replay | currentScopeAndPermissionAreRecheckedOnSuccessfulReplay; both actual HOST methods below |
| Atomic arrival/exception/action/outbox and original requestNo | exceptionReplayIsAtomicWithActionAndOutbox; arrivalAndExceptionOutboxFailuresRollBackAllLocalEffects |
| Actual scoped tasks, deleted/foreign tasks excluded, active fence preserved | exceptionReportsOnlyActualScopedTasksAndLeavesActiveFenceUntouched |
| Frozen old definition/rules/price/buffers despite newer publication; new item mapping | reassignsCheckedInWithFrozenRules |
| Deterministic failure retains old hold/arrangement and helper chooses current successful receipt | replacementRejectsWithoutChangingOldArrangement |
| UNKNOWN preserves original operation/command/current/fence | replacementUnknownKeepsOriginalCommandAndFence |
| Full indexes, future grid, past/disabled/existing order reject | replacementRejectsPartialOffGridPastDisabledAndExistingOrder |
| Resource before-buffer also strictly future | replacementBeforeBufferMustAlsoRemainInFuture |
| Real independent JDBC arrival/cancel competition | checkInRacesCancelOnIndependentConnections |
| Actual member owner cr1 namespace + guest token consumption | BeautyReceptionIdentityIT.receptionCheckInConsumesStrictCurrentReferenceAndRealGuestToken |
| Actual owner own mc1 + beauty challenge consumption | BeautyReceptionIdentityIT.consumerArrivalConsumesRealOwnMc1AndLiveStoreChallenge |
| Affected BM03 and shared allocator regressions | AppointmentBusinessIT 21; AppointmentMatchingSearchTest 10 |

## Execution environment and reproducible commands

All commands run from this worktree with `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`. Maven is offline with `-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2`. Native scripts use `BEAUTY_TEST_DISK=1`: one cached `mysql:8.4` container at a time, UUID, `--pull never --rm`, own writable `/var/lib/beauty-test`, 64 MB buffer, performance_schema OFF, localhost TCP readiness, finally cleanup only the owned container. Existing food/pricing containers are untouched.

```sh
# Shared artifacts before native and HOST consumption
mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-base -am install -DskipTests
# Scoped normal reactor + install before HOST
mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml install
# Native affected suite
BEAUTY_TEST_DISK=1 BEAUTY_TESTS='ReceptionBusinessIT,AppointmentBusinessIT,AppointmentMatchingSearchTest' node scripts/verify-beauty-persistence.mjs
# HOST new methods (B passed in this first run; C expectation corrected below)
BEAUTY_TEST_DISK=1 BEAUTY_RECEPTION_TESTS='BeautyReceptionIdentityIT#receptionCheckInConsumesStrictCurrentReferenceAndRealGuestToken+consumerArrivalConsumesRealOwnMc1AndLiveStoreChallenge' node scripts/verify-member-guest-registration.mjs --beauty-reception
# Affected HOST rerun + localization
BEAUTY_TEST_DISK=1 BEAUTY_RECEPTION_TESTS='BeautyReceptionIdentityIT#consumerArrivalConsumesRealOwnMc1AndLiveStoreChallenge,BackendI18nParityTest' node scripts/verify-member-guest-registration.mjs --beauty-reception
```

RED selections use the same native invocation with `BEAUTY_TESTS='ReceptionBusinessIT#<method>'`, or `ReceptionBusinessIT#checkInKeepsScheduleHoldAndNoTasks+consumerNeedsOwnIdentityAndLiveStoreChallenge+exceptionReplayIsAtomicWithActionAndOutbox` for the three first flows. Frozen replacement RED uses `reassignsCheckedInWithFrozenRules`. Logs below preserve actual outcomes, not inferred success.

## RED/GREEN history and actual failures

| Log under /tmp | Actual result and meaning |
| --- | --- |
| task55b-red.log | 1 test, 1 assertion failure, 0 errors: real confirmed appointment reaches unimplemented check-in before arrival implementation. |
| task55b-identity-exception-red.log | 3 tests, 3 failures, 0 errors: executable arrival/C/exception flow assertions before those implementations; unsupported seams are business RED, not compilation failures. |
| task55b-first-green.log | 3/3 GREEN. |
| task55b-replace-red.log | 1 test, 1 failure, 0 errors: real arrived appointment reaches unimplemented internal replacement before implementation. |
| task55b-replace-green.log | Compile failure: item.startAt/endAt corrected to actual serviceStartAt/serviceEndAt accessors. Not business RED. |
| task55b-replace-green2.log | 4 tests, 1 failure: actual ActionLog action exceeded VARCHAR(32); fixed label to RECEPTION_CHANGE_PREPARED, no schema change. |
| task55b-expanded-green.log | 14 tests, 1 failure, 2 errors: native REPLACE reached fence.acquire but old SQL rejected CHECKED_IN. Added narrowly guarded reception branch in acquire/authorize as planned. |
| task55b-expanded-green2.log | 14/14 GREEN. |
| task55b-native-regression.log | 48/48 GREEN, 0 failures/errors/skips: ReceptionBusinessIT17 + AppointmentBusinessIT21 + AppointmentMatchingSearchTest10. Preserved passing run; not repeated wholesale after resume. |
| task55b-reactor.log | 115 tests total; server 28 includes 2 failures from BookingServerWiringTest's hand-enumerated mock fixture omitting newly consumed definition/policy mappers. Fixed only the fixture's four mapper registrations. Not a production fallback/disabled-feature change. |
| task55b-reactor-green.log | 115/115 GREEN, 0 failures/errors/skips. Module summaries and current Test.xml agree: api2/core38/web22/channel25/server28; architecture13 included. Build/install successful. |
| task55b-host.log | 2 tests, 1 failure, 0 errors. Actual B strict cr1/formal guest consumption passed. C expected NOT_FOUND for forged/non-own mc1, but actual owner protocol returns MEMBER_CODE_INVALID; only assertions corrected after reading owner implementation. |
| task55b-host-consumer-green.log | 7/7 GREEN: affected C HOST1 + BackendI18nParityTest6; actual owner proof/arrival chain succeeds. |
| task55b-final-native-green.log | 3 tests, 0 failures, 1 fixture error. Restored fence and buffer tests passed; new exception fixture attempted two same-entity orders for one appointment, violating frozen uniqueness before behavior. Corrected foreign fixture to OTHER-E; subsequent exact WHERE refinement is recorded separately; no migration/production change. |
| task55b-final-native-green2.log | 3/3 GREEN, 0 failures/errors/skips: restored isolated fence + before-buffer + actual scoped exception tasks. |
| task55b-exception-fixture-final.log | 1/1 GREEN, 0 failures/errors/skips after exact tenant/entity/store/work-order WHERE refinement; no product change. |

Historical TDD limitation: `currentArrangementEvidence` was extracted after the general arrival RED, without a dedicated pre-extraction RED proving later failed receipt selection. The subsequent failed-REPLACE regression covers that behavior now; it is not retroactive RED evidence. HOST owner-consumption tests and the two final boundary regressions were also added after core feature implementation. No claim of finer-grained preimplementation RED for every later assertion.

## Effective fence ablation

Saved exact ReceptionCoordinator.java and BeautyMappers.java bytes to `/tmp/task55b-ablation-*.java`. Removed only the arrival service `fence.getActiveIntentNo()==null` condition and the `markArrived` SQL `NOT EXISTS active_intent_no` clause together. Ran:

```sh
BEAUTY_TEST_DISK=1 BEAUTY_TESTS='ReceptionBusinessIT#activeFenceAloneRejectsCheckIn' node scripts/verify-beauty-persistence.mjs
```

`/tmp/task55b-fence-ablation-red.log`: 1 test, 1 assertion failure, 0 errors, `Expecting code to raise a throwable.` The isolated active-fence arrival actually succeeded with both protections removed; no other guard masked the missing protection. Both original files restored byte-for-byte in finally. The restored active-fence case passed in the final targeted run (the separate third fixture error is disclosed above); all three restored/boundary tests GREEN in task55b-final-native-green2.log; final scoped fixture refinement GREEN in task55b-exception-fixture-final.log.

Restoration/boundary commands (same JDK21/offline environment):

```sh
BEAUTY_TEST_DISK=1 BEAUTY_TESTS='ReceptionBusinessIT#activeFenceAloneRejectsCheckIn+replacementBeforeBufferMustAlsoRemainInFuture+exceptionReportsOnlyActualScopedTasksAndLeavesActiveFenceUntouched' node scripts/verify-beauty-persistence.mjs
BEAUTY_TEST_DISK=1 BEAUTY_TESTS='ReceptionBusinessIT#exceptionReportsOnlyActualScopedTasksAndLeavesActiveFenceUntouched' node scripts/verify-beauty-persistence.mjs
```

## Guards, generation and migration evidence

`/tmp/task55b-guards.log`: all 12 checks exit0: Python gen-beauty-{database-design,java-method-design,java-project-design,development-plan,api-design}.py --check; Node gen-beauty-{schema,resource-intent-schema,appointment-business-schema,appointment-decision-schema,reception-schema,reception-identity-guard-schema}.mjs --check; node scripts/check-sql-portability.mjs. No new dialect dependency. Existing SQL baseline unchanged.

`/tmp/task55b-generation-final.log`: method source regenerated after adding exact `countWorkOrders`/`affectedTaskNos` mapper metadata, then --check PASS: 44 controller designs /28 services /101 methods /167 DTOs /25 mappers /40 custom mapper methods; these are design counts, not new public endpoint delivery. Both UTF-8 generated method lists included. `git diff --check` clean. All native fixtures applied V1–V6; no migration diff. V5 SHA256 `8e9aec144736ee3502fb9c0f9c4c95edf6f4a02c1cecbd597ff0c3b51aa4eab5`; V6 `220ed777c56a77c22cf835f2a5bcc42ccb41cc98a4348831df06e0e404b89f20`.

## Native versus fixture and self-review limits

Core tests execute actual MySQL8.4, Flyway, mapper SQL, Spring transactions, source authority decisions/fence/current, coordinator, ActionLog/Outbox and two independent JDBC connections. Resource admission/capacity/catalog/personnel are explicitly controlled test fixtures, asserting remote calls occur outside local transactions; this is not a real cross-service resource deployment. HOST executes actual member code issuer/resolver, strict namespace, consumer proof verifier, guest token service, merchant grants and beauty business writes against native MySQL. HOST resource hold evidence alone is a named controlled fixture; no resource accept is permitted. Root revision7 versus sourceRevision4 specifically validates independent version meanings. No new HTTP flow, standalone real remote owner chain or Task60 deployment was tested/claimed.

Self-review checked exact DTO contracts; proof/reason redaction and safe event payload; permission-before-replay; no RPC under local transaction; old successful arrangement selection; shared allocator; constrained SQL reception branch; no C/D scope; immutable migrations; default/disabled wiring. Remaining known process limitation is the dedicated helper RED history above. Independent spec/quality review is still required before C; this report is implementer evidence, not review approval.

## Owned paths and commit audit

Exact owned paths are listed below. Controller `progress.md`, main TDD, brief and overall task-55-design.md are excluded. Staging uses actual UTF-8 NUL-delimited paths and an explicit denylist; no directory-wide add. No generated migration or SQL baseline is staged.

Inherited platform tracking (not rerun or claimed verified by B): existing OpsPointsInspectionController fully-qualified @PathVariable Architecture failure; V506 pay_ent_exception_action old single-space indentation/parser mismatch; BM03 c338 JobLockConfiguration top-level store package registration issue, assigned to Task60 to move into the same module ai.neargo.shop.job.config and update two imports. These inherited items are distinct from this step’s executed beauty architecture13 and 115-test reactor.

Exact owned file count: 42.

- `.superpowers/sdd/beauty-backend/task-55b-design.md`
- `.superpowers/sdd/beauty-backend/task-55b-report.md`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/ArrivalVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/CheckInProof.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/CheckInReq.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/MyArrivalVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/MyCheckInReq.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/ReceptionExceptionReq.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/ReceptionExceptionVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/ReceptionIdentityProof.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/WorkOrderRequirementVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/WorkOrderStepTemplateVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/WorkOrderTaskSnapshotVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/service/ReceptionService.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyAppointmentConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyReceptionConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/mapper/BeautyMappers.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/policy/ReceptionIdentity.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentCoordinator.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentMatching.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentPreparationService.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentSourceAuthority.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/FrozenAppointmentScope.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/HoldEvidence.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ObservedHead.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/PreparedWorkOrderScope.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionCoordinator.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionPreparation.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionServiceImpl.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderPreparation.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ReceptionBusinessIT.java`
- `backend-plugins/shop-industry-beauty/beauty-server/src/test/java/ai/neargo/shop/beauty/scenario/BookingServerWiringTest.java`
- `backend/shop-app/src/main/resources/i18n/messages.properties`
- `backend/shop-app/src/main/resources/i18n/messages_ar.properties`
- `backend/shop-app/src/main/resources/i18n/messages_en.properties`
- `backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautyReceptionIdentityIT.java`
- `backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java`
- `docs/technical/design/schema/beauty-java-method-design.json`
- `docs/technical/design/美业系统-Controller-Service-Mapper方法清单.md`
- `docs/technical/design/美业系统-Java类与方法清单.md`
- `scripts/verify-beauty-persistence.mjs`
- `scripts/verify-member-guest-registration.mjs`
