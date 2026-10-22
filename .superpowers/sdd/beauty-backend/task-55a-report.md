# Task55A implementation report

> Status: implementation and scoped verification complete; awaiting controller independent review. 2026-10-22.

BASE: `2bf892b98225e6f71bee2921df5e4935f5494119` (`git rev-parse HEAD` at dispatch).
Implementation commit: the commit containing this report (controller receives its exact SHA at handoff).
Requirements: `task-55a-brief.md`, frozen `task-55-design.md`; controller-owned mainTDD/progress/brief/design are excluded from this implementation commit.
No subagents; no shared/production operations; no 55B–D implementation.

## Delivered behavior and boundaries

- BAPI-031/032 service contracts and typed DTOs are implemented. No new reception HTTP/controller/remote-service wire is claimed. Existing appointment preview/create DTOs append optional `guestNo` and retain old constructors. Old null customer references still create BTG anonymous references; explicit registered guests use actual owner facts; claimed guests become canonical USER; changed canonical identity requires a new preview.
- 031 checks the current employee's CUSTOMER permission, derives the key from complete tenant/entity/store/realm/subjectKind/actor/endpoint/original-key scope, and calls the shared owner directly. There is **no local REGISTER operation/contact/display-name hash/result/audit/event**. Owner HMAC alone checks low-entropy contact changes. A lost owner result returns PENDING, null operation/guest/token, REQUIRES_ORIGINAL_ACTOR. Only the original authorized actor with the original request can replay that owner namespace. A second actor's same textual key is a distinct registration, not recovery of the first result.
- 032 verifies current cr1 and guest secret, persists the canonical guest/USER intent and stable original derived key before the external call, then commits immutable receipt + operation success + ActionLog + safe shared Outbox atomically. External member calls occur outside transactions. Receipt completion locks the original scoped operation; receipt unique tenant/entity/store/guest resolves competing inserts, then verifies the same immutable USER/revision/hash. The local intent contains no raw ticket, claimToken, or contact; its request hash includes high-entropy proof material, unlike forbidden low-entropy REGISTER hashing.
- A current same-store CUSTOMER-authorized employee can recover an already verified known guest by reading the actual owner CLAIMED fact and matching the prior canonical USER. No owner call impersonates the original employee. REGISTERED yields REQUIRES_FRESH_IDENTITY_PROOF; mismatched USER/store/entity is rejected. `recoverClaim` is implementation-internal, not a new public endpoint or generic-operation dispatch.
- C appointment ownership is direct USER or a matching immutable tenant/entity/store/guest/USER receipt. The same predicate runs inside SQL before page/count; root access also checks the receipt. Deleted roots, foreign entity/store, other users and old anonymous references remain inaccessible.
- `issueArrivalChallenge` alone is implemented on ReceptionService: current BOOKING_MANAGE, 32 SecureRandom bytes/base64url43, UTC milliseconds matching DATETIME(3), fixed 90-second expiration, complete actor-namespace idempotency, no renewal. Code exists only in the dedicated row/immediate redacted VO; no operation, ActionLog or Outbox contains it. C check-in remains 55B.
- V5 creates the four frozen foundation tables/entities/mapper interfaces/data-scope registrations and strict ready/version5 manifest. WorkOrderContext is foundation storage only; there are no premature workorder/052/OperationVO/scanner/Job shells. Real `BeautyReceptionStateChanged` contains only businessNo/entityNo/storeNo/revision/state; actual shared Outbox handles the receipt transaction now.
- Wiring: AppointmentAccess -> CustomerOwnership -> receipt mapper; ReceptionAccess -> AppointmentAccess + lazy current permission owner; ReceptionIdentity -> ReceptionAccess + actual lazy member ports; ReceptionPreparation -> AppointmentPersistence + operation mapper; GuestRegistrationServiceImpl -> these helpers/receipt mapper/qualified actual beautyOutboxPublisher. No business/owner constructor cycle or production dependency on shop-core was introduced. Host actual member tests live in shop-app; default retail tests use reflection for optional beauty types.

## Reviewed implementation increments

1. The existing GuestRegistrationPort gains the narrow `ResultUnknown` marker (constant message, no cause/raw request/token). Core catches only this marker, with no svc-client dependency. Remote converts TIMEOUT/UNREACHABLE or REMOTE_ERROR with status0: the latter is actual ServiceCalls' unreadable-response category, including an HTTP200 body that cannot be decoded. It does **not** prove no HTTP response arrived. HTTP403/503 and NOT_CONFIGURED propagate as definite errors. No other Port or global ServiceCalls classifier changed. Controller accepted this precise classification.
2. V5 enum/CHAR_LENGTH CHECKs require extending the formerly numeric/time-only closed parser. It accepts literal tokens, narrowly documented `_utf8mb4`/`_latin1` ASCII enum presentation and CHAR_LENGTH; it preserves literal case and keeps unknown functions/syntax fail-closed. This is strict equivalence support, not ignored/unsupported-constraint exemption. Native JDBC `check_clause.codePoints()` records actual 92,39 backslash/quote delimiters after `_utf8mb4` and at enum end; this evidence is separate from CLI/JSON display escaping. Normalization is restricted to those charset-prefixed ASCII enums. Native removal of a CHECK/index, and changing only RECEIVED to received, still fail strict validation; V1–V4 SQL never changed.
3. Initial challenge VO exposed nanoseconds while MySQL stored milliseconds. A real native replay assertion failed; issuance now truncates to MILLIS before both persistence and first return, retaining exactly 90 seconds.

## Schema freeze

V5 SHA256: `8e9aec144736ee3502fb9c0f9c4c95edf6f4a02c1cecbd597ff0c3b51aa4eab5`.
First native execution applied all five migrations successfully; initial validation then failed on unsupported string CHECK grammar. The executed V5 was frozen then and has not been edited. Fixes were in strict parser/fixtures, never migration text. New source: `beauty-reception-schema.json`; renderer: `gen-beauty-reception-schema.mjs`; additive field dictionary and method-design output preserve original 41-table/160-ID/49-task target baselines. Actual private table total is 16+2+4+1+4=27, plus scoped history table.

## AC-to-test evidence

| AC | Actual tests and assertions | Result |
|---|---|---|
| 1 claim intent/atomic recovery | GuestClaimReceiptIT: sharedClaimCommitThenLocalRollbackRecoversReceiptWithCurrentEmployee; unknownClaimKeepsOriginalIntentAndRegisteredNeedsFreshCurrentProof; receiptCannotRebindAndSuccessReplayNeedsCurrentPermissionButNoFreshTicket | Actual MySQL and actual local Outbox; owner commit plus injected Outbox rollback leaves intent/no receipt/no outbox, then current employee recovers once; no rebind |
| 1 C ownership | receiptOwnershipIsAppliedBeforePagingAndRequiresCompleteScope | Direct + guest union across pages has total2; foreign entity/store/deleted/other/BTG denied; root get agrees |
| 2 actual member consumption | BeautyReceptionIdentityIT (3 methods) | Actual member/mc1/cr1/guest permissions/storage, original register replay/HMAC contact conflict, current manager claim recovery without expired original proof, actor-bound references, owner missing-key rejection |
| 2 communication categories | GuestIdentityTransportTest (5 total, 2 added) | Typed marker for exact unknown categories; actual loopback 403/503/missing-address remain errors; actual undecodable200 becomes sanitized marker |
| 3 challenge | arrivalCodeHasFixedTtlStableOriginalKeyAndNoAuditLeakage | 43-char random code, +90s, stable same key/time, distinct key/actor, cross-store/revoked denial, zero operation/log/outbox |
| 4 schema | BeautySchemaMigrationIT17 + ResourceIntentMigrationIT3 | V1/V2 tracked upgrade to5 with retained rows/fence, repeat validation, immutable checksums, strict columns/defaults/indexes/check/grouping/literal-case drift |
| 5 ablation | receiptOwnershipIsAppliedBeforePagingAndRequiresCompleteScope | Removing only SQL receipt store equality yields expected2 vs actual3, 1 failure/0 errors; exact source restored in finally; final full suite green |
| 6 appointments | AppointmentBusinessIT19 including explicitOwnerGuestIdentitySurvivesCreationAndClaimedGuestMapsToUser; GuestReferenceContractTest1 | Real core persistence/saga with documented controlled resource/member ports, explicit guest create, identity change conflict, unknown guest rejection, legacy DTO/BTG compatibility and existing BM03 regression |
| 6 reactor/platform/docs | Commands below | Scoped reactor native/host green; only characterized historical global guards fail |

## Commands and results

All Maven commands use `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`, `-o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2`, from the beauty-data worktree. Native scripts use those inherited settings and cached MySQL8.4 `--pull never`, tmpfs, random localhost ports, TCP readiness, and finally remove only their own UUID container. At most one native container ran at a time; no existing food container was modified.

| Command | Evidence/result |
|---|---|
| `mvn -f backend/pom.xml -pl shop-base -am install -DskipTests` | Shared marker/event installed before consuming modules; `/tmp/task55a-base-install.log` success |
| Target GuestReferenceContractTest before implementation | `/tmp/task55a-red.log`: 1 test,1 failure,0 errors; guestNo expected GUE1 versus absent. `/tmp/task55a-green-contract.log`:1/1 green |
| CHECK extension targeted test | `/tmp/task55a-check-red.log`: unsupported grammar regression. `/tmp/task55a-check-green.log`:7 parser +1 DTO green |
| First native reception run | `/tmp/task55a-native-first.log`:25 tests,0 failures,23 setup errors caused by the shared CHECK parser. **Not business RED.** V5 DDL actually applied/frozen |
| Second native reception run | `/tmp/task55a-native-second.log`:25 tests,1 failure,0 errors; only challenge precision replay failure. Fixed by millisecond issuance |
| Third native reception run | `/tmp/task55a-native-third.log`:26/26 green, contains raw JDBC CHECK codepoints |
| `node scripts/verify-member-guest-registration.mjs --beauty-reception` | `/tmp/task55a-host-first.log`:3/3 actual host bridge green; normal default build remains independent of beauty imports |
| Temporary store predicate ablation; `node scripts/verify-beauty-persistence.mjs --receipt-ownership-only` | `/tmp/task55a-receipt-ablation.log`:1 failure,0 errors, total3 versus2; `/tmp/task55a-ablation.py` restored exact original file in finally |
| `node scripts/verify-beauty-persistence.mjs --reception-with-appointments` after restoration | `/tmp/task55a-native-final.log`:45/45 green (AppointmentBusiness19, Schema17, GuestClaimReceipt6, ResourceIntentMigration3),0 skips |
| `mvn -f backend-plugins/shop-industry-beauty/pom.xml install` final source | `/tmp/task55a-reactor-final.log`:113/113 green,0 skips (api2/core37/web22/channel24/server28). Includes real TCP classification5 and beauty architecture13 |
| Same normal reactor earlier iterations | First rejected old ready4 fixture; second rejected wiring fixture missing new receipt/operation/challenge mocks. Fixtures updated to actual V5 bean dependencies; third and final reactors pass. These fixture errors are not feature RED |
| `mvn -f backend/pom.xml -pl shop-app -am test -Dtest=BackendI18nParityTest,ArchitectureTest,SchemaDriftTest,SchemaGeneratorTest,BeautyEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false` | `/tmp/task55a-platform-guards.log`:33 discovered,28 passed,2 profile skips,3 known failures below. i18n6 and schema-generator1 pass; embedded/default wiring6 execute pass |
| Five Python generators `gen-beauty-{database-design,java-method-design,java-project-design,development-plan,api-design}.py --check`; five V1–V5 JS renderers `--check` | `/tmp/task55a-generators-final.log`:all current; source/generation invariants unchanged |
| `node scripts/check-sql-portability.mjs`; `git diff --check`; V5 `shasum -a 256`; historical V1–V4 git diff | SQL no new dialect dependency; whitespace clean; SHA unchanged; no historical migration diff |

Known global guard failures, identical to Task54 baseline: ArchitectureTest.noFullyQualifiedReferences (`OpsPointsInspectionController` preexisting fully-qualified PathVariable); ArchitectureTest.topLevelPackagesMustBeRegistered (`store`/JobLockConfiguration preexisting unregistered infrastructure package); SchemaDriftTest.migrationAndTestSchemaMatch (`pay_ent_exception_action` V506 old one-space indentation confuses its parser). No ratchet, unrelated source, or fixture was edited to hide them. No claim of full backend green.

Fixture limits: core tests exercise real MySQL/local transactions/outbox with explicitly controlled member/resource boundaries; host bridge uses real shared member service/permissions/tickets and injected response loss **after real owner transaction commit**. Platform schema is current schema-test fixture with MySQL JSON_VALID adaptation, official V537/V538 applied; it is not full platform historical migration replay. Beauty V1–V5 is actual Flyway history. No new reception HTTP deployment or real resource network capacity acceptance is claimed in A.

## Owned files

Only the paths below plus this report are staged. Controller-owned mainTDD/progress/55brief/55Abrief/overall-design remain excluded; original-cwd unrelated work is untouched.

- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/ArrivalChallengeVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/ClaimGuestReq.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/CreateAppointmentReq.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/GuestClaimVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/GuestRegistrationVO.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/PreviewAppointmentReq.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/RegisterGuestReq.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/service/GuestRegistrationService.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/service/ReceptionService.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteGuestRegistrationPort.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/test/java/ai/neargo/shop/beauty/scenario/GuestIdentityTransportTest.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyAppointmentConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyCheckExpression.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyDataScopeRegistration.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyPersistenceConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyReceptionConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautySchemaReady.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautySchemaValidator.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyWriteGuardConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/entity/BtyArrivalChallenge.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/entity/BtyGuestClaimReceipt.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/entity/BtyReceptionOperation.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/entity/BtyWorkOrderContext.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/mapper/BeautyMappers.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/policy/AppointmentAccess.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/policy/CustomerOwnership.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/policy/ReceptionAccess.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/policy/ReceptionIdentity.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentPreparationService.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentServiceImpl.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/GuestRegistrationServiceImpl.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionPreparation.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionServiceImpl.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V5__beauty_reception_work_order.sql`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/config/BeautyCheckExpressionTest.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/AppointmentBusinessIT.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/BeautyPersistenceWiringTest.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/BeautySchemaMigrationIT.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/BeautyWriteGuardHostWiringTest.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/GuestClaimReceiptIT.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/GuestReferenceContractTest.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ResourceIntentMigrationIT.java`
- `backend-plugins/shop-industry-beauty/beauty-server/src/test/java/ai/neargo/shop/beauty/scenario/BookingServerWiringTest.java`
- `backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautyReceptionIdentityIT.java`
- `backend/shop-base/src/main/java/ai/neargo/shop/spi/beauty/BeautyReceptionStateChanged.java`
- `backend/shop-base/src/main/java/ai/neargo/shop/spi/member/GuestRegistrationPort.java`
- `docs/technical/design/schema/beauty-java-method-design.json`
- `docs/technical/design/schema/beauty-reception-schema.json`
- `docs/technical/design/美业系统-Controller-Service-Mapper方法清单.md`
- `docs/technical/design/美业系统-Java类与方法清单.md`
- `docs/technical/design/美业系统-数据库字段字典.md`
- `scripts/gen-beauty-database-design.py`
- `scripts/gen-beauty-java-method-design.py`
- `scripts/gen-beauty-reception-schema.mjs`
- `scripts/verify-beauty-persistence.mjs`
- `scripts/verify-member-guest-registration.mjs`
