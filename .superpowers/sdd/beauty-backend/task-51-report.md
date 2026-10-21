# Task 51 implementation report

Status: Task51 service/source slice implemented. HTTP/delegated actor transport belongs to Task52; deployed external-owner integration belongs to Task53. No deployment, shared database, network dependency download, or push was performed.

## Delivered behavior

- Real appointment plans come from current store policy, service catalog, product master revisions, published definition/requirements, resource/personnel qualification, schedule windows, and capacity facts. Clients supply SKU/quantity and optional requested assignments, never trusted frozen bytes or hold IDs.
- P1 quantities expand to sequential service instances. Preparation/cleanup buffers and five-minute bucket rounding separate adjacent items, including non-five-minute services. STAFF quantity requires distinct canonical people. Capacity accounting sums every source group per canonical resource/bucket. Explicit item service times, assignment intervals and overall service span expose the padded arrangement.
- Enumeration is bounded to 50 resources and availability to 48 slots. `complete=false` means search exhaustion cannot establish unavailability. A failed preview over a truncated pool raises the distinct `AppointmentMatching.IncompleteSearch`; Task52 must preserve this distinction. Matching remains advisory until real owner reservation.
- Persisted preview captures canonical customer/guest reference, actor namespace, policy version, product goods/SKU revisions, definition version, quote/duration, immutable assignments and exact normalized source bytes. Legacy product fact constructors return unknown revisions (null); appointment planning requires observed nonnegative revisions, including legitimate zero. New CREATE/CHANGE revalidates observed price/duration/master revisions outside transactions. Master revisions do not promise an atomic store/campaign quote.
- Preparation writes the operation, stable owner key and exact accept payload before remote acceptance. The initial root is HELD at revision1 with a separate arrangement pointer0. A lost acceptance response replays identical key/bytes. Real returned receipt is bound to the persisted intent and fence before source authorization.
- RESERVE and CONFIRM are independent durable owner commands. Source root reaches CONFIRMED only after original CONFIRM terminal proof and matching confirmed hold facts. Pending/unknown responses remain operations; no fresh identifiers are generated to escape ambiguity.
- Source authorization compares the complete persisted original binding and frozen bytes, locks root/fence/intent in order, and persists immutable decision/window plus original fence metadata atomically. Same binding replays the exact original decision before and after phase3. Expired permission does not release a fence. Missing/corrupt V4 decision metadata fails closed.
- CHANGE/REPLACE failure preserves the current arrangement and old immutable history. Success appends history and moves the independent arrangement pointer. CANCEL/NO_SHOW require actual RELEASE success before changing root status. CONFIRM rejection after successful RESERVE retains HELD and RELEASE_REQUIRED until authenticated cancellation releases the real hold.
- B actors retain MERCHANT/MCH or MERCHANT/USR namespaces and need current real booking permissions. C actors require CONSUMER/USR and exact canonical source ownership before trusted owner reads. CustomerIdentityPort resolves member references to userNo; guests receive local server references. Existing merchant-only ResourceCommandFactsPort was not loosened. Booking permission labels are assignable; built-in role grants were not changed.
- Current actor checks reject caller transactions before remote authorization. No owner/fact RPC runs under source/resource locks. Recovery never creates SecurityContext/LoginUser from stored audit identity.
- Recovery job uses existing JobHandler, bounded scanning, durable retry/lease fields and version CAS. Background recovery only reads original bound command/hold facts and reconciles known terminals. Unbound acceptance, ACCEPTED execution, or a prepared next CONFIRM waits for authenticated replay. An explicit same-store currently authorized manager may resume a pending operation through recoverOperation, retaining the original initiator, key, payload and command. GET is read-only.
- Existing bty_action_log stores preparation audit evidence atomically with the operation: cancellation/change reasons and no-show evidenceRef are retained; CREATE records previewNo rather than transient member tickets.
- Nonempty financial sourceRefs and consent remain explicitly closed. BM03 does not create orders, invent buyers, assert payments, or claim entitlement evidence.

## Interfaces for Task52

Public beauty API package: `ai.neargo.shop.beauty.service`.

| Interface | Methods |
| --- | --- |
| AppointmentService | previewAppointment, createAppointment, listAppointments, getAppointment, previewAppointmentChange, changeAppointment, cancelAppointment, recordNoShow; previewMyAppointment, createMyAppointment, listMyAppointments, getMyAppointment, previewMyAppointmentChange, changeMyAppointment, cancelMyAppointment |
| AppointmentAvailabilityService | queryAvailability, queryPublicAvailability |
| ServiceOperationQueryService | getOperation |
| ServiceOperationRecoveryService | recoverOperation(entityNo, currentStoreNo, actorNo, operationNo) -> OperationVO |

The original Req/Query/VO names are in beauty-api. B calls take canonical entity/store/current actor; C calls take authenticated userNo. C cancellation/change reuse the original CancelAppointmentReq/ChangeAppointmentReq contracts. Mutation idempotencyKey is a separate method argument. HTTP, especially permitAll `/mp/**`, must still enforce actual authentication and preserve realm/subjectKind. The service enforces this independently. Add an explicit recovery POST; do not advance workflows from operation GET.

Owner-neutral contracts: `ai.neargo.shop.spi.resource.ResourceBookingPort` and `ResourceCandidatesPort`. Actual adapters: `ai.neargo.shop.merchant.port.ResourceBookingPortImpl` and `ResourceCandidatesPortImpl`. These trusted server contracts must not be exposed as generic public command/hold lookup APIs. `reconcile`/`reconcileHold` require an already persisted verified original binding from trusted source code and cannot accept/execute commands.

Source provider: `ai.neargo.shop.beauty.port.AppointmentSourceAuthorityPortImpl`; internal authority: `AppointmentSourceAuthority`. Public facade: `AppointmentServiceImpl`; supporting focused services: `AppointmentMatching`, `AppointmentPreparationService`, `AppointmentCoordinator`, `AppointmentPersistence`, `AppointmentRecovery`, `AppointmentAccess`. Manual recovery facade: `ServiceOperationRecoveryServiceImpl`. Job handler: `AppointmentRecoveryJob`.

## Schema and generated sources

V1/V2 unchanged. Frozen V3 adds preparation, operation, immutable arrangement and current-pointer tables. V4 adds original decision fence metadata. Latest readiness is4; strict tables/columns/indexes and hashes are checked. Historical V2 upgrade remains tested.

- `docs/technical/design/schema/beauty-appointment-business-schema.json` -> `scripts/gen-beauty-appointment-business-schema.mjs` -> V3.
- `docs/technical/design/schema/beauty-appointment-decision-schema.json` -> `scripts/gen-beauty-appointment-decision-schema.mjs` -> V4.
- New BaseEntity/mapper/data-scope registrations and strict BeautySchemaValidator manifest included.
- Main TDD and progress ledger are controller-owned and excluded from this implementation commit.

## Verification evidence

All Maven runs use Java21 and `-o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2`. Native wrappers use a private tmpfs MySQL8.4 container with `--pull never`, TCP readiness and final cleanup. No shared database was used.

| Verification | Result | Evidence |
| --- | --- | --- |
| Initial contract RED | 2 tests, 1 failure + 1 error (missing permission/service) | /tmp/task51-red.log |
| Final business native MySQL | 12 tests, 0 failures/errors | /tmp/task51-native-final3.log |
| Actual merchant adapter native MySQL | 2 tests, 0 failures/errors | /tmp/task51-owner.log |
| Migrations/strict validation native MySQL | 17 tests, 0 failures/errors | /tmp/task51-migrations.log |
| Protocol/wiring/beauty architecture | 22 tests, 0 failures/errors | /tmp/task51-gates-final2.log |
| Actual product/catalog adapter focused SQL/Spring tests | 9 tests, 0 failures/errors | /tmp/task51-product-tests.log |
| Existing public catalog native compatibility | 5 tests, 0 failures/errors | /tmp/task51-catalog.log |
| Ownership ablation | 11 tests, exactly 1 expected ownership failure | /tmp/task51-ablation.log |
| V3/V4 generator --check; SQL portability; git diff --check | Passed, no new SQL dialect dependency | final command output |

The quiet Maven logs are supported by these exact Surefire XML reports (each has failures=0, errors=0, skipped=0):

- `backend-plugins/shop-industry-beauty/beauty-core/target/surefire-reports/TEST-ai.neargo.shop.beauty.scenario.AppointmentBusinessContractTest.xml`: tests=2.
- `backend-plugins/shop-industry-beauty/beauty-core/target/surefire-reports/TEST-ai.neargo.shop.beauty.scenario.BeautyPersistenceWiringTest.xml`: tests=5.
- `backend-plugins/shop-industry-beauty/beauty-core/target/surefire-reports/TEST-ai.neargo.shop.beauty.scenario.BeautyWriteGuardHostWiringTest.xml`: tests=2.
- `backend-plugins/shop-industry-beauty/beauty-server/target/surefire-reports/TEST-ai.neargo.shop.beauty.architecture.BeautyArchitectureTest.xml`: tests=13; contract/wiring/architecture total22.
- `backend/shop-app/target/surefire-reports/TEST-ai.neargo.shop.scenario.ServiceProductFactsPortTest.xml`: tests=6.
- `backend/shop-app/target/surefire-reports/TEST-ai.neargo.shop.scenario.ServiceCatalogFactsPortTest.xml`: tests=3; product/catalog total9.

Wiring WARN about missing DataSource is expected evidence of the negative fail-closed context test. Mockito/ByteBuddy dynamic-agent warnings are test environment notices. Product test Spring context also emits existing H2-version, duplicate-mapper and development configuration warnings; these are neither claimed deployment validation nor hidden test failures. Native MySQL8.4 Flyway version-support warnings likewise do not replace the actual zero-failure test results.

Business cases cover sequential non-five-minute/buffer matching, canonical STAFF quantity, source spoofing, realm mismatch, exact C ownership, current member revocation, B canonical member/guest references, optimistic root revision, independent arrangement revision, lost acceptance replay, concurrent same-root operations during blocked owner RPC, failed change preservation, successful history append, terminal reconciliation without a user, current-manager recovery after initiator revocation, immutable expired window/fence, exact original decision replay after release, CONFIRM failure/required real RELEASE, financial-source rejection, missing vs real zero product revisions, changed quote rejection, and persisted no-show evidence.

Ablation removed only the exact consumer/root ownership guard. The ownership scenario failed with an expected-throw assertion; the guard was restored and final 12-case suite passed.

Reproduction commands:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home node scripts/verify-beauty-persistence.mjs --appointment-business-only
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home node scripts/verify-beauty-persistence.mjs --appointment-migrations-only
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home node scripts/verify-beauty-persistence.mjs --public-catalog-only
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home node scripts/verify-resource-booking-persistence.mjs --adapter-only
```

## Precise remaining boundaries

- Task51 business native suite uses explicitly controlled external owner/facts fixtures around real private SQL, source authority, fences, matching and business services. It is not evidence of a fully deployed real external owner chain.
- Separate merchant native cases exercise actual ResourceCommandService/ResourceBookingService/admission and the new adapters with a registered source fixture. They validate real adapter owner behavior, not end-to-end deployed beauty-to-owner transport.
- Task52 remains responsible for HTTP controllers, error mapping (including incomplete search), actual C authentication on permitAll paths, delegated actor transport and explicit recovery POST. Task53 remains responsible for packaging/startup/real external-owner chain, job scheduling configuration and deployed authorization evidence.
- No fresh automatic user authority is minted for recovery. Authorized manual replay is required when execution/new next-step authorization is genuinely needed. Expiry never silently frees a protected hold.
- P1 matching is bounded deterministic greedy matching, not exhaustive optimization. It makes no reservation guarantee. Pricing observations are revalidated but are not an atomic commercial quote or financial proof.
- Full work-order/payment/entitlement lifecycle remains BM04/BM05 by the approved plan.
- Unrelated repository-wide architecture baseline debt was not changed or claimed fixed.

## Review round 1: six Important findings

Completed against base `1293e87e6`; controller-owned main TDD and `progress.md` are excluded from this commit. Task52/Task53 and deployed external-owner evidence remain later work.

| Review finding | Change and regression evidence |
| --- | --- |
| 1. Atomic source outbox | `AppointmentCoordinator.finish` publishes `BeautyAppointmentOperationCompleted` via existing `beautyOutboxPublisher` in the same source transaction as terminal operation/root/history/intent/fence changes. Safe fields are appointment/entity/store/operation/action/state/status/revisions; no customer, money or raw plan. Stable operation identity plus the locked reconciled-intent gate prevents terminal replay publication. `terminalSourceOutboxRollsBackWithFenceAndReplaysExactlyOnce` injects a before-commit failure after outbox flush, proves root/fence/outbox rollback, then replays to exactly one source event. Existing explicit archive mode accepts this immutable event; normal delivery still requires a registered platform route. |
| 2. Overlapping replacement capacity | Source-owned root/current arrangement and the latest successful CREATE/CHANGE original receipt establish an exact `Replacement(Binding, Target)` after current hold verification. Owner revalidates the complete original command binding, scoped current owner head/revision, hold version/lease/source revision/plan hash, and subtracts only that hold's unreleased bucket units. Business regression permits the 5-minute overlap and still rejects another occupied hold. Native owner regression checks exact self-exclusion, other-hold preservation, foreign hold, forged source, stale hold/lease and released original rejection. |
| 3. Effective staff fact time | `CapacityQuery.factAt` is independent of `from/to`. Matching supplies actual appointment start as fact time while retaining widened buffer/schedule/capacity lookback. `candidateCapacityUsesEffectiveServiceTimeInsteadOfLookback` exercises an actual staff assignment effective on the service day. Existing four-argument callers retain their previous nonreplacement semantics. |
| 4. Recovery isolation | Each claimed operation catches and reports its own runtime failure, persists a 60-second failed-attempt backoff versus normal 30 seconds, clears the lease by CAS and continues. `recoveryFailureDoesNotStarveLaterOriginalTerminal` makes the first original lookup fail and reconciles the second, preserving the first fence. It also asserts persisted backoff/lease clearing and captures/asserts the safe warning without the raw owner error. |
| 5. Legacy upgraded roots | No synthetic pointer, arrangement, hold or original command is inserted. Summaries expose nullable `arrangementRevision`; details show real root state/time with empty verified arrangement/actions; mutation requires real evidence. `preservedLegacyRootReadsWithoutInventingArrangementEvidence` seeds the appointment at actual tracked V2, upgrades to V4, reads B list/detail and C detail, preserves the legacy hold bytes, and rejects cancellation without new operation/current rows. No V5 is needed. |
| 6. Shared frozen SQL renderer | V3/V4 generators retain separate schema inputs/targets and call shared `renderBeautyAppointmentTables` in the existing helper. Eight renderer tests include byte-exact V3/V4 output and rejection of unsafe identifiers/key/checks. All V1–V4 `--check` commands and direct byte comparison to the base pass; migration SQL files are unchanged. |

The Minor default-suite omission is fixed: unflagged `verify-beauty-persistence.mjs` includes `AppointmentBusinessIT`.

### RED, ablation and corrected harness evidence

- `/tmp/task51-r1-red.log`: 16 business tests, 1 failure + 3 errors, reproducing missing outbox rollback, overlap conflict, recovery starvation and legacy-pointer NPE before these fixes.
- `/tmp/task51-r1-owner-red.log`: 3 actual owner adapter tests, 1 error (`unknown scoped resources`) reproducing the effective staff/lookback mismatch.
- `/tmp/task51-r1-owner-ablation.log`: controlled removal of only the own-hold capacity subtraction, 4 tests with exactly 1 expected failure in `replacementCapacityExcludesOnlyExactCurrentOriginalUnreleasedUnits` (expected used=1, actual=2). Production source restored in `finally`; final full owner suite passed.
- First default-suite attempt `/tmp/task51-r1-full-beauty.log` stopped at test compilation because the strengthened upgrade fixture lacked a `JdbcTemplate` import; corrected before rerun.
- Next full default run `/tmp/task51-r1-full-beauty-final.log` ran 122 tests and exposed an existing `BeautyCatalogIT` reflection fixture defect: `getConstructors()[0]` chose the additive 8-argument Fact constructor while the fixture supplied 6 arguments, causing 2 failures + 6 errors. The precise fixture fix selects the explicitly retained 6-argument signature. Production Fact and its legacy null revision semantics are unchanged. This harness defect is distinct from the business RED cases above. Final default run below is green.

### Final verification

All Maven runs use Java21 at `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`, offline mode and isolated `/tmp/codex-beauty-bm01-m2`. Changed shared artifacts were installed into that cache before the independent beauty reactor (`/tmp/task51-r1-shared-install.log`). Native wrappers own private tmpfs MySQL8.4 containers, `--pull never`, TCP readiness and `finally` cleanup; no shared DB or dependency download.

| Command | Final result | Log |
| --- | --- | --- |
| `node scripts/verify-beauty-persistence.mjs` | 122 tests, 0 failures/errors/skips; includes 16 business and 17 migration tests | `/tmp/task51-r1-full-beauty-final2.log` |
| `node scripts/verify-resource-booking-persistence.mjs` | 50 tests, 0 failures/errors/skips (17 persistence + 33 execution) | `/tmp/task51-r1-full-owner.log` |
| `mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml test` | 72 tests, 0 failures/errors/skips across API/core/web/channel/server; includes 13 beauty architecture tests | `/tmp/task51-r1-reactor.log` |
| `node --test scripts/tests/beauty-schema-sql.test.mjs` | 8 passed | `/tmp/task51-r1-renderer-final.log` |
| V1/V2/V3/V4 generators `--check`, direct base byte comparison, SQL portability, `git diff --check` | Passed; no new SQL dialect dependency | `/tmp/task51-r1-static.log` |

Final XML reports remain under the existing module `target/surefire-reports` paths; native IT reports and normal reactor Test reports all record zero failures/errors. The normal reactor module totals are API2/core24/web15/channel10/server21.

Remaining output warnings are explicitly not claimed pristine: pinned Flyway support metadata knows MySQL through8.1 while fixtures intentionally use8.4; MyBatis Sequence can report slow local ID initialization; the owner reactor compilation reports pre-existing deprecated APIs; normal reactor negative-context tests intentionally report invalid configuration/missing primary datasource or manager, and Mockito/ByteBuddy reports dynamic-agent notices. The new recovery warning is expected, captured and asserted. No warnings are treated as deployed configuration validation. Prior unrelated product/H2/duplicate-mapper warnings were not used as evidence for this fix round.

Capacity remains advisory, legacy roots remain read-only without real arrangement/original command evidence, and normal appointment event delivery requires a registered platform route. Task52 transport and Task53 startup/scheduling/real external-owner chain are still unverified here. No deployment, push, merge or financial lifecycle claim is made.
