# Task52 BM03 public ingress and independent bidirectional protocol

Status: implementation and Task52 verification complete, 2026-10-22 Asia/Shanghai. Task53 deployed module acceptance remains pending. Base: `2ae608265`; branch `codex/beauty-foundation`. Main TDD and progress are controller-owned and excluded from this commit. Approved design: `task-52-design.md`.

## Delivered behavior and ownership

- Five beauty-web controllers implement 18 original endpoints: BAPI-010, 038–045, 146–153, 160. Exact baseline methods/paths/DTOs retained. Explicit additive `POST /biz/x/beauty/operations/{operationNo}/recoveries` invokes existing authenticated recovery; GET only queries.
- B public ingress reads current MAIN/MERCHANT MCH or USR and BizContext, validates the raw X-Store-No through the existing StoreRequestScopeValidator and StoreDirectoryPort, then uses actual Task51 services for current VIEW/MANAGE and resource ownership. Every private C controller requires MAIN/CONSUMER/USR despite `/mp` being permitAll. Availability remains anonymous. Public requests cannot supply trusted actor/tenant/realm/entity metadata.
- BeautyBookingInternalApi has 19 actual typed methods. Four remote Service implementations implement the real AppointmentService, AppointmentAvailabilityService, ServiceOperationQueryService and ServiceOperationRecoveryService contracts. No C getMyOperation endpoint was invented.
- Shared neutral ResourceBookingInternalApi has eight owner routes; CustomerIdentityInternalApi adds one member-owner read route. Platform controllers call the actual ResourceBookingPort/ResourceCandidatesPort/CustomerIdentityPort beans. Customer identity remains the actual MemberCustomerIdentityPortImpl → requireReference scoped ticket contract; no guest fallback, debit or consent authority.
- BeautySourceInternalApi has freeze/query callbacks only for BEAUTY_APPOINTMENT. DelegatedIdentity carries tenant/realm/subjectKind/actor. DelegatedIdentityContext restores consumer/merchantByUser/merchant only after machine-token checks and restores the exact prior SecurityContext in finally. No stored username impersonation. Full Binding and actor remain authoritative on the real source service.
- Owner/source infrastructure exceptions return explicit 503; business forbidden/conflict return 403/409; malformed internal JSON returns 400 even with the real public GlobalExceptionHandler/ApiResponseWrapper installed. Explicit Lookup.found distinguishes absence from HTTP200 error envelopes. Remote adapters check full returned binding/object identity/required response fields. UNKNOWN never becomes permission. All new booking/source/owner/customer remote adapters reject caller transactions.
- Capacity transmits independent factAt and complete Replacement(binding,target), preserving original hold identity/version/lease. Product service transport preserves and requires observed goodsRevision/skuRevision and exact returned store/SKU identity.
- Bounded matching maps IncompleteSearch at its core boundary to shared BEAUTY_SEARCH_INCOMPLETE (80104), three locales, private HTTP422 and remote same ErrorCode. Anonymous availability retains complete=false, separate from proven CONFLICT.

## Configuration and Task53 handoff

| Shape | Public artifact / source provider | Owner adapters |
|---|---|---|
| embedded HOST | beauty-web + beauty-core; unique AppointmentSourceAuthorityPortImpl | actual local merchant/member owner ports |
| standalone HOST | beauty-web + beauty-channel, no beauty-core; RemoteAppointmentSourceAuthorityPort | local platform owners; actual reverse HTTP to BEAUTY |
| standalone SERVER | beauty-core + beauty-channel; unique local AppointmentSourceAuthorityPortImpl, no remote source provider | BeautyMerchantClientConfiguration remote booking/candidates/customer ports to PLATFORM |
| disabled | no new public controllers or source provider | unchanged shared owners |
| ops / no API profile | no booking public controllers or recovery Job | no public business ingress |

Public controllers require `api` profile and `shop.beauty.enabled=true`, explicit deployment embedded/standalone. AppointmentRecoveryJob requires api. beauty-server now defaults its profile to api while its artifact has no beauty-web and its security only permits internal beauty paths. Remote source/service beans and new controllers have beauty-prefixed names. Source/provider graph uses lazy ObjectProvider dependencies; actual configuration contexts start without resolving HTTP targets. Existing actual owner registry rejects duplicate sourceType providers (unchanged Task51 owner behavior).

Task53 JAR inputs: `beauty-api-0.1.0-SNAPSHOT.jar` throughout; embedded HOST adds beauty-web/core, standalone HOST adds beauty-web/channel with no core/server; independent executable is `beauty-server-0.1.0-SNAPSHOT.jar` with core/channel dependencies. HOST build profiles remain `beauty-embedded` / `beauty-standalone` in shop-app.

Set `shop.services.internal-token`, HOST `shop.services.targets.BEAUTY`, SERVER `shop.services.targets.PLATFORM`; both directions use typed InternalHttp and HTTP/1.1. Fixed local reserved ports may be used for cyclic URLs. Task53 must use a true channel-only HOST, not the old embedded owner: stop the source SERVER to prove callback cannot bypass HTTP through a local provider. Read task-53-deployment-notes.md for private DB/process/artifact requirements. This task does not claim published JAR/process delivery or shared/split-database deployment acceptance, automatic scheduled execution, or downstream notification delivery.

V1–V4 migrations are byte-unchanged. No schema migration, UI, BM04/06 or financial flow opened. API baseline remains 160; schemas separately list one additive public recovery and 30 actual machine routes. API/Java generated documents are regenerated, actual 18 baseline statuses and trusted header metadata reconciled.

## Verification evidence

All Maven commands use `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`, offline `-o`, and `-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2`. Shared contracts/auth were first installed via `mvn ... -f backend/pom.xml -pl shop-base-auth -am install -DskipTests`; no global snapshot repository was changed.

| Command / scope | Actual result | Log |
|---|---|---|
| `mvn ... -f backend-plugins/shop-industry-beauty/pom.xml test` final source | 89 tests, 0 failures/errors/skips across all five modules; includes beauty architecture tests | `/tmp/task52-reactor-final2.log` |
| `node scripts/verify-beauty-persistence.mjs` | actual tmpfs MySQL 8.4 cached `--pull never`, 122 tests, 0 failures/errors/skips; TCP readiness, unique schemas, finally container removal | `/tmp/task52-native.log` |
| `mvn ... -f backend/pom.xml -pl shop-app -am test -Dtest=BookingOwnerMachineTransportTest,BackendI18nParityTest -Dsurefire.failIfNoSpecifiedTests=false` | actual owner Tomcat/globalAdvice + i18n parity: 7 tests, 0 failures/errors/skips | `/tmp/task52-owner-final.log` |
| `BookingMachineTransportTest` | actual beauty Tomcat/controllers/advice, B-MCH/B-USR and private C route, wrong token no call, cross-scope/foreign source, UNKNOWN, explicit failure, wrong reply identity; context restoration on exception and sequential actors | final reactor, `/tmp/task52-http-green.log` earlier |
| `BeautyBookingIngressTest` | private C requires consumer, supplied userNo cannot override current user; public availability anonymous; raw B store header denied; GET never recovery | final reactor |
| `BookingFactsTransportTest` | real TCP: malformed HTTP200/envelopes/null, explicit absence, complete binding, disconnected owner, factAt+replacement, no RPC under TX, exact member metadata, source UNKNOWN/wrongBinding, observed product revisions | final reactor, `/tmp/task52-facts-green.log` |
| `BookingChannelWiringTest`, `BookingServerWiringTest` | actual channel-only/core+remote-owner config graphs; unique provider, disabled/embedded exclusion; ops/no-api no Job; no eager HTTP target lookup | final reactor, `/tmp/task52-wiring.log` |
| generators `--check`, SQL portability, `git diff --check` | generated artifacts current; no added SQL dialect debt; no whitespace errors | `/tmp/task52-sql-check.log`, command outputs |

Protocol HTTP tests use actual production controllers/global advice with service/port doubles to isolate transport and identity boundaries. Those doubles are test-only and are not deployed acceptance evidence. Native MySQL tests cover actual service/persistence rules. Source provider/remote topology tests use actual production configuration classes, without claiming Task53 process proof. Two class/route reflection tests are existence checks and do not substitute for the behavior tests above.

### RED/GREEN and ablation

1. Route and neutral protocol existence RED were missing classes (`task52-red.log`, `task52-protocol-red.log`), then implemented; existence-only scope.
2. Actual TCP wrong operation identity was accepted before response comparison: BookingMachineTransportTest 2 tests/1 failure (`task52-response-red.log`), then 2 green and final reactor green.
3. TCP product wrong SKU/missing revisions exposed response validation gap (`task52-facts-red.log`); fixed actual adapter, all five facts tests green. Three initial frozen `{}` fixture errors were corrected to authoritative ResourcePlanContract.encode, not by relaxing production validation.
4. Authenticated preview with missing durable previewNo RED (`task52-preview-red.log`), then require durable identity, final reactor green.
5. Required ablation: temporarily remove incomplete-search→ErrorCode translation. AppointmentIncompleteSearchTest failed at the expected throwable assertion with raw IncompleteSearch (`task52-incomplete-ablation-valid.log`: 1 test/1 failure). Exact production bytes restored in finally; focused test green (`task52-incomplete-restored-green.log`: 1/0/0). Final full reactor was already green with the identical restored source.

Test harness corrections excluded from production RED evidence: initial public availability used `quantity` instead of actual `qty` (400); initial new matcher fixture used nonexistent ALWAYS instead of UNRESTRICTED enum; first ablation attempt was blocked by JDK enforcer because its subprocess did not inherit JDK21 and was rerun correctly. None required relaxing contracts. Known root-wide Architecture FQN debt remains unclaimed; this task ran beauty architecture and affected platform i18n/controller checks, not every historical backend test.

## Owned files

Exact owned source/test/document/generator files below; controller-owned main TDD/progress excluded. The report and approved design are additionally forced into git because `.superpowers` is normally ignored.

- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/spi/BeautyBookingInternalApi.java`
- `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/spi/BeautySourceInternalApi.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/pom.xml`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/client/BeautyBookingResponses.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/client/BeautyServiceCalls.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/client/RemoteAppointmentAvailabilityService.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/client/RemoteAppointmentService.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/client/RemoteServiceOperationQueryService.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/client/RemoteServiceOperationRecoveryService.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/config/BeautyChannelAutoConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/config/BeautyMerchantClientConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteAppointmentSourceAuthorityPort.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteCustomerIdentityPort.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteResourceBookingPort.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteResourceCandidatesPort.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteServiceCatalogFactsPort.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/test/java/ai/neargo/shop/beauty/client/BookingResponseValidationTest.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/test/java/ai/neargo/shop/beauty/scenario/BookingChannelWiringTest.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/test/java/ai/neargo/shop/beauty/scenario/BookingFactsTransportTest.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/test/java/ai/neargo/shop/beauty/scenario/BookingProtocolContractTest.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyAppointmentConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentAvailabilityServiceImpl.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentMatching.java`
- `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/AppointmentIncompleteSearchTest.java`
- `backend-plugins/shop-industry-beauty/beauty-server/src/main/java/ai/neargo/shop/beauty/server/api/InternalBeautyBookingController.java`
- `backend-plugins/shop-industry-beauty/beauty-server/src/main/java/ai/neargo/shop/beauty/server/api/InternalBeautySourceController.java`
- `backend-plugins/shop-industry-beauty/beauty-server/src/main/resources/application.yml`
- `backend-plugins/shop-industry-beauty/beauty-server/src/test/java/ai/neargo/shop/beauty/scenario/BookingMachineTransportTest.java`
- `backend-plugins/shop-industry-beauty/beauty-server/src/test/java/ai/neargo/shop/beauty/scenario/BookingServerWiringTest.java`
- `backend-plugins/shop-industry-beauty/beauty-web/src/main/java/ai/neargo/shop/beauty/api/biz/BizAppointmentController.java`
- `backend-plugins/shop-industry-beauty/beauty-web/src/main/java/ai/neargo/shop/beauty/api/biz/BizAvailabilityController.java`
- `backend-plugins/shop-industry-beauty/beauty-web/src/main/java/ai/neargo/shop/beauty/api/biz/BizServiceOperationController.java`
- `backend-plugins/shop-industry-beauty/beauty-web/src/main/java/ai/neargo/shop/beauty/api/mp/MpAppointmentController.java`
- `backend-plugins/shop-industry-beauty/beauty-web/src/main/java/ai/neargo/shop/beauty/api/mp/MpAvailabilityController.java`
- `backend-plugins/shop-industry-beauty/beauty-web/src/test/java/ai/neargo/shop/beauty/scenario/BeautyBookingIngressTest.java`
- `backend-plugins/shop-industry-beauty/beauty-web/src/test/java/ai/neargo/shop/beauty/scenario/BeautyBookingRoutesTest.java`
- `backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalCustomerIdentityController.java`
- `backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalResourceBookingController.java`
- `backend/shop-app/src/main/resources/i18n/messages.properties`
- `backend/shop-app/src/main/resources/i18n/messages_ar.properties`
- `backend/shop-app/src/main/resources/i18n/messages_en.properties`
- `backend/shop-app/src/test/java/ai/neargo/shop/portal/internal/BookingOwnerMachineTransportTest.java`
- `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/DelegatedIdentityContext.java`
- `backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java`
- `backend/shop-base/src/main/java/ai/neargo/shop/spi/member/CustomerIdentityInternalApi.java`
- `backend/shop-base/src/main/java/ai/neargo/shop/spi/resource/ResourceBookingInternalApi.java`
- `backend/shop-base/src/main/java/ai/neargo/shop/spi/security/DelegatedIdentity.java`
- `backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalServiceResponses.java`
- `docs/technical/design/schema/beauty-api-design.json`
- `docs/technical/design/schema/beauty-java-method-design.json`
- `docs/technical/design/美业系统-API清单.md`
- `docs/technical/design/美业系统-Controller-Service-Mapper方法清单.md`
- `docs/technical/design/美业系统-Java类与方法清单.md`
- `scripts/gen-beauty-api-design.py`
- `scripts/gen-beauty-java-method-design.py`

## Review fix round 1/5 — 2026-10-22

Base `c5b63aaea`; review input `/tmp/review-bm03-protocol.md`. Both Important findings addressed without changing the baseline160, protocol DTO field types, frozen V1–V4, main TDD, progress, or Task53 scope.

### I1 — capacity must overlap the requested half-open window

RemoteResourceCandidatesPort now enforces the real owner's existing invariant: returned `from < query.to` and `to > query.from`. A populated row for the same resource in a wholly unrelated future/past window fails closed instead of being discarded by the matcher and implying zero occupancy. Touching only the beginning/end boundary is nonoverlap and is rejected. Legitimate partial overlap and exact-window rows remain accepted. The smaller concrete overlap guard was chosen; no new query-snapshot/envelope framework. The review's tentative missing numeric-field concern was disproved by the actual decoder and causes no DTO/type change.

Real TCP test `BookingFactsTransportTest.capacityRejectsWrongWindowFactsAndPreservesHalfOpenOverlapSemantics` sends nonempty facts with all numeric fields present: unrelated future, past, touching both boundaries, legitimate overlap on each side, and exact overlap.

- RED: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml -pl beauty-channel -am test -Dtest=BookingFactsTransportTest#capacityRejectsWrongWindowFactsAndPreservesHalfOpenOverlapSemantics -Dsurefire.failIfNoSpecifiedTests=false` → 1 test, 1 failure, 0 errors/skips (`/tmp/task52-r1-capacity-red.log`), expected “code to raise a throwable” assertion on wrongly accepted facts.
- GREEN before scope refactor: same toolchain, `-pl beauty-channel,beauty-web -am test -Dtest=BookingFactsTransportTest,BeautyBookingIngressTest -Dsurefire.failIfNoSpecifiedTests=false` → 12 tests, 0 failures/errors/skips (`/tmp/task52-r1-behavior-before-refactor.log`). Includes all six TCP facts tests and six ingress tests/invocations.

### I2 — single implementation of B booking store-scope validation

Extracted package-private, stateless `beauty-web / ai.neargo.shop.beauty.api.biz.BookingStoreScope`. The exact shared decision body continues to use SecurityUtils, BizContext, StoreDirectoryPort and StoreRequestScopeValidator; lazy ObjectProvider lookup preserves checking the realm before consulting the directory. BizAppointmentController, BizAvailabilityController and BizServiceOperationController now call that helper. No independent auth/permission model or per-endpoint permission move was introduced; actual services retain VIEW/MANAGE and object ownership.

`BeautyBookingIngressTest.everyBookingBizIngressRejectsRealmRawHeaderAndForeignScopeBeforeDelegating` characterizes each of the three actual ingress controllers before extraction and passes after extraction. Each rejects current CONSUMER realm, raw foreign X-Store-No, and a directory fact with foreign entity; verifies zero appointment/availability/operation/recovery service interactions. Wrong realm also performs no directory call. Existing GET-no-recovery and consumer-only/private-vs-anonymous behavior remains covered.

### Final amended verification and warnings

`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml test` → **93 tests, 0 failures/errors/skips**, all modules BUILD SUCCESS (`/tmp/task52-r1-reactor-green.log`). Breakdown API2/core25/web22/channel19/server25; includes actual TCP/controller/advice tests and 13 architecture tests. `git diff --check` passes. No core/service/schema/owner behavior changed in this fix round; the earlier native MySQL122 and owner/i18n7 evidence remains applicable and was not needlessly rerun.

Output is not pristine: inherited deprecated-API/annotation-processing compilation notices, Mockito dynamic agent warnings, expected negative-context startup WARN messages, and the intentional disconnected-TCP ServiceCalls warning remain visible in logs. Earlier native logs also contain Flyway's MySQL8.4-vs-verified8.1 compatibility warning. They are characterized separately from the zero test failures; this bounded round does not upgrade global test/agent/Flyway infrastructure or claim the warnings resolved. Task53 process/private-DB/real scheduling acceptance remains pending.

Round1 owned files: RemoteResourceCandidatesPort.java, BookingFactsTransportTest.java, BookingStoreScope.java, the three B booking controllers, BeautyBookingIngressTest.java, and this report. Main TDD/progress remain controller-owned and unstaged.
