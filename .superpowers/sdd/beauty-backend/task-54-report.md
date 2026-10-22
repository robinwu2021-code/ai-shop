# Task54 shared guest identity implementation report

Task54 only; ready for the controller's independent spec/quality review. This is not BM04 module acceptance and does not implement Task55 onward. BASE e90e12aab3acf4e336d66b0c3620ae7d981cbd1a, branch codex/beauty-foundation, worktree /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex. Controller-owned main TDD, progress, brief and ADR edits are excluded from this commit.

## Delivered behavior and ownership

- The sole neutral GuestRegistrationPort/InternalApi is in shop-base/spi/member. Member owns MbrGuestRegistration/BaseEntity, MemberGuestRegistrationMapper.lockGuest and MemberGuestRegistrationServiceImpl.register/get/verify/claim. No bty_guest, buyer, member/account/order creation, generic credential idempotency JSON, outbox or payment authorization was added.
- requireScope checks SecurityUtils tenant/realm/kind/actor exactly, MERCHANT identity, actual entity/store facts and the existing live MerchantOperatorAccessPort Query with exact BizPerms.CUSTOMER. Original actor namespace scopes REGISTER replay only. A different currently authorized same-store operator can get/verify/claim using their own reference and the original guest secret. Claim locks the same guest root; a winning real user cannot be replaced.
- Registration uses a unique tenant/entity/store/realm/kind/actor/keyHash index. contact <=128 UTF16 units, optional; displayName <=80, optional; consent=true required. Random 32-byte SecureRandom base64url claim tokens are hashed for comparison and AES-GCM encrypted for durable original-response replay. Contact ciphertext uses TEXT, sufficient for the maximum UTF8 value and authenticated-encryption overhead. DTO toString methods redact credentials/contact.
- MemberGuestCrypto.encrypt/decrypt uses random 12-byte IV, AES-GCM/128-bit tag, explicit 32-byte base64 shop.member.guest.credential-key and frozen v1 length-framed tenant/entity/store/guest/purpose AAD. Missing/invalid key disables this capability with MEMBER_GUEST_UNAVAILABLE 40042, key err.member.guest_unavailable in all three locales; startup and original member-code behavior remain available. Tampered ciphertext fails closed. No default deployment key.
- MemberGuestCrypto.requestFingerprint uses HmacSHA256 under the explicit owner secret and the separate member-guest-registration-request-v1 domain plus length-framed scope/actor/contact/name/consent. A public SHA(contact) would expose a low-entropy guessing oracle. Random-token and idempotency-key hashes remain SHA256. No raw contact/claim secret/reference/key is stored in replay JSON or emitted by application logging.
- V537 adds the shared guest table. Once verified it stayed frozen at SHA256 9c93c44dc328b4cbf47be8baae6972743e0922aca1a0cf62b84906905b4f0b0f. After checking max537, separate V538 adds actor_realm/actor_kind to the existing member identity ticket; SHA256 65f1536102d92776224a8542f12c1e90a4cab1a9e4e07540dffab1cab7a30183. No preexisting migration changed. Initial V537 comment wording was corrected for the schema generator before the final frozen hash and all final verification; no deployed/shared database migration was mutated.
- MemberCodeResolveServiceImpl.resolve binds authenticated reference issuance to the actual current MERCHANT namespace. requireGuestClaimReference checks the original cr1, complete current namespace/actor/store and active real user. Legacy namespace-less references retain requireReference/old resolve-reference compatibility but fail closed for new guest claims. MCH and USR with identical actorNo and current CUSTOMER permission cannot exchange cr1 credentials.
- A committed claim's exact original key/request hash/full claiming identity plus original guest secret returns the immutable stored Claim after cr1 expiration. Current CUSTOMER access is rechecked. Active claimed-user facts are read outside root locks, then matched to the same immutable user/revision under the lock. New claims still require a valid strict cr1. Changed key/parameters/namespace conflict; revoked current operators and inactive users cannot replay.
- CustomerIdentityPort remains functional: default verifyConsumerProof fails explicitly closed. MemberCustomerIdentityPortImpl delegates to MemberCodeResolveServiceImpl.requireConsumerProof, which requires current CONSUMER/USR, same tenant/user, original unexpired MEMBER_CODE and active real user. No login token/phone/string fallback.
- InternalGuestRegistrationController exposes register/get/verify/claim, and InternalCustomerIdentityController adds verify-consumer-proof. Token validation precedes owner execution; DelegatedIdentityContext restores context on success/failure. Typed responses echo complete identity/scope/command or proof request. RemoteGuestRegistrationPort and extended RemoteCustomerIdentityPort reject mismatched echoes, wrong/missing facts and HTTP200 error objects. InternalServiceResponses maps 400/401/403/404/409/503 honestly; invalid member code is400. Existing resolve-reference remains compatible. Independent server conditional configuration provides one remote guest port; embedded/default use the sole local provider.
- Additive implemented protocol registration lists five new machine routes separately from the original160 design baseline (previous30+5=35 machine contracts). BTG remains an anonymous reference; no format-based public guest fabrication.

## New versus reused

New: GuestRegistrationPort/InternalApi, shared guest entity/mapper/service/port, MemberGuestCrypto, guest internal controller/remote adapter, V537/V538, explicit guest capability key/error/translations and the new targeted tests/native runner. Existing types were extended additively for consumer proof and strict guest-claim reference validation.

Reused: BaseEntity audit/version/soft-delete convention; actual member MEMBER_CODE/CUSTOMER_REFERENCE ticket issuance/hash/expiry and active-user lookup; MerchantScopeFactsPort and MerchantOperatorAccessPort live CUSTOMER permission projection; BizKey business numbers; MyBatis mapper/data-scope bypass and short TransactionTemplate pattern; DelegatedIdentityContext and InternalServiceResponses; InternalHttp/ServiceCalls remote infrastructure; conditional server owner configuration and schema/glossary generators. No duplicated user/member/order owner or generic idempotency engine.

## RED, GREEN and ablation evidence

All Maven commands used JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home, -o and -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2. Changed shared dependencies were installed into that isolated repository before the final beauty reactor. No dependency version literals were added.

| Evidence | Command / cases | Exact result and log |
|---|---|---|
| Initial guest behavioral RED | node scripts/verify-member-guest-registration.mjs --red | Real application/native owner fixture reached missing guest stub:5 tests,1 assertion failure+4 unsupported-operation errors; no compile/environment failure in this final RED. /tmp/task54-native-red.log |
| Initial fixture correction | Native current schema seed replay | First attempt hit an unrelated historical sys_legal_form remark length error. Runner now uses current DDL only, without historical seed mutations. This attempt is not behavioral RED. |
| Consumer proof RED | MemberCodeResolveFlowTest#consumerProofRequiresCurrentConsumerAndOriginalLiveMemberTicket | Real provider default unsupported; /tmp/task54-proof-red.log. Then original member-code and new proof8/8 GREEN. |
| Owner TCP RED | GuestOwnerMachineTransportTest | Actual ingress returned503 for supported registration; /tmp/task54-tcp-red.log. Later real TCP/advice ingress passes. |
| Namespace/contact privacy RED | MemberGuestRegistrationIT#guestClaimReferencesCannotCrossMerchantSubjectNamespaceEvenForSameActorNumber+registrationRequestFingerprintIsStableButProtectedByOwnerSecret |2 assertion failures,0 errors:cross-namespace cr1 accepted; identical contact fingerprint under two owner keys. /tmp/task54-namespace-hmac-red.log. Strict reference and HMAC fixes made both green. |
| Committed claim replay RED | MemberGuestRegistrationIT#exactCommittedClaimReplaysAfterReferenceExpiryButCurrentPermissionAndIdentityStillApply | Actual expired cr1 made committed replay404; /tmp/task54-claim-replay-red.log. Stored exact-result replay fix made it green. |
| Identity ablation | Temporarily remove requireScope subjectKind comparison, finally restore | currentPermissionScopeAndNamespaceProtectEveryReplayAndRead failed with unauthorized access accepted:1 failure,0 errors; /tmp/task54-identity-ablation.log. Restored full suite passed /tmp/task54-native-restored.log. Current guard remains identical. |
| Final native owner + restart | node scripts/verify-member-guest-registration.mjs |13 distinct native scenarios pass, then a second separate JVM/application replays the original key against the same database:1/1; zero failures/errors/skips. /tmp/task54-native-complete.log. Last source formatting afterward only split long lines; final shared compile below includes it. |
| Final platform impact suite | mvn -f backend/pom.xml -pl shop-app -am test -Dtest=MemberCodeResolveFlowTest,MemberGuestCryptoTest,GuestOwnerMachineTransportTest,BookingOwnerMachineTransportTest,BackendI18nParityTest,ArchitectureTest,SchemaDriftTest,SchemaGeneratorTest,BeautyEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false |45 discovered,40 passed,2 profile skips,3 existing global guard failures named below. MemberCode8,crypto2,TCP2,i18n6,schema-generator1,embedded6 executed pass. /tmp/task54-platform-final.log |
| Final shared install / compile | mvn -f backend/pom.xml -pl shop-core,shop-merchant,shop-store-mybatis,shop-base-auth -am install -DskipTests | BUILD SUCCESS; /tmp/task54-shared-install-final.log |
| Final complete beauty reactor | mvn -f backend-plugins/shop-industry-beauty/pom.xml test |31 classes,109 tests,0 failures/errors/skips; includes GuestIdentityTransportTest3, existing protocol, conditional wiring, deployment and13 architecture cases. /tmp/task54-beauty-final.log |
| Generation / dialect | python3 backend/scripts/gen-test-schema.py; node scripts/gen-glossary.mjs --check; node scripts/check-sql-portability.mjs; git diff --check | All pass; /tmp/task54-schema-generation-final.log, /tmp/task54-glossary-check.log, /tmp/task54-sql-final.log. No known-* ratchet changed. |

Native scenarios, exact method names in MemberGuestRegistrationIT:

1. registrationReplayIsDurableEncryptedAndHasNoAccountOrOrderSideEffects
2. currentPermissionScopeAndNamespaceProtectEveryReplayAndRead
3. sameStoreManagerHandoffRequiresTheirOwnCurrentReferenceAndOriginalGuestSecret
4. concurrentClaimsHaveOneRealUserWinnerAndFailureLeavesNoSideEffects
5. invalidInputsAndTamperedCiphertextFailClosed
6. missingKeyDisablesOnlyGuestAndFreshOwnerReplaysPersistedSecret
7. currentCustomerPermissionIsRecheckedAfterRoleRevocationAndWrongStoreAssignment
8. nativeConsumerProofRejectsOtherGenuineUserExpiredForgedAndMerchantNamespaces
9. duplicateConcurrentRegistrationReplaysOneCredential
10. restartJvmReplaysOriginalKeyFromEncryptedStorage
11. guestClaimReferencesCannotCrossMerchantSubjectNamespaceEvenForSameActorNumber
12. registrationRequestFingerprintIsStableButProtectedByOwnerSecret
13. exactCommittedClaimReplaysAfterReferenceExpiryButCurrentPermissionAndIdentityStillApply

## Boundaries and remaining concerns

- Native runner owns one cached mysql:8.4 container at a time: --pull never, tmpfs /var/lib/mysql, random127.0.0.1 port, TCP readiness. Finally removes only its container and temporary restart credential fixture. Final inventory contains only the original six unrelated food-recipe MariaDB containers, untouched. No native/deployment concurrency.
- Native platform uses current generated DDL fixture, then reconstructs the pre-Task54 guest/ticket delta and executes the actual V537/V538 SQL. This is not full historical platform Flyway replay. H2 validates generated schema and the original member-code/proof flow; H2 is not cited as evidence for MySQL locking/unique races.
- Native tests use real member persistence, real active-user and merchant facts, and the real current CUSTOMER permission mapper. TCP owner tests use the actual controllers/token/status/global-advice/delegated-context machinery with explicit service doubles; beauty TCP uses an actual localhost HTTP server returning controlled owner envelopes. These transport tests do not claim full deployed-JAR end-to-end owner business proof. Native owner facts and transport evidence are deliberately identified separately.
- Existing global failures, all outside this task: ArchitectureTest.noFullyQualifiedReferences (OpsPointsInspectionController @org.springframework...PathVariable); ArchitectureTest.topLevelPackagesMustBeRegistered (store package/JobLockConfiguration: Task54-baseline-existing infrastructure registration omission introduced by BM03 commit c338e5722; controller scheduled cleanup before BM04 Task60 deployment, preferably moving into registered job.config); SchemaDriftTest.migrationAndTestSchemaMatch (pay_ent_exception_action V506 one-space column indentation causes its legacy parser to see only id/operation_kind, while generated fixture correctly has the preexisting full columns). Their source/migrations were not changed. SchemaGeneratorTest passed complete generation+H2 use. No claim of globally green backend. As explicitly directed, did not rerun unrelated full root backend/deployment suites or change historical debt/ratchets.
- Key rotation/retention policy remains an explicit follow-up operation; replacing the deployment key silently would invalidate encrypted registration replay and keyed payload fingerprints. No production key, deployment, UI, Task55 reception/BTG transition, Task56 work order or full BM04 acceptance is claimed. Independent review belongs to the controller; this implementer did not spawn agents/reviewers.

## Exact owned files

- `.superpowers/sdd/beauty-backend/task-54-design.md`
- `.superpowers/sdd/beauty-backend/task-54-report.md`
- `backend-plugins/shop-industry-beauty/README.md`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/config/BeautyMerchantClientConfiguration.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteCustomerIdentityPort.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteGuestRegistrationPort.java`
- `backend-plugins/shop-industry-beauty/beauty-channel/src/test/java/ai/neargo/shop/beauty/scenario/GuestIdentityTransportTest.java`
- `backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalCustomerIdentityController.java`
- `backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalGuestRegistrationController.java`
- `backend/shop-app/src/main/resources/db/migration/V537__shared_guest_registration.sql`
- `backend/shop-app/src/main/resources/db/migration/V538__member_reference_actor_namespace.sql`
- `backend/shop-app/src/main/resources/i18n/messages.properties`
- `backend/shop-app/src/main/resources/i18n/messages_ar.properties`
- `backend/shop-app/src/main/resources/i18n/messages_en.properties`
- `backend/shop-app/src/test/java/ai/neargo/shop/member/MemberGuestCryptoTest.java`
- `backend/shop-app/src/test/java/ai/neargo/shop/portal/internal/GuestOwnerMachineTransportTest.java`
- `backend/shop-app/src/test/java/ai/neargo/shop/scenario/MemberCodeResolveFlowTest.java`
- `backend/shop-app/src/test/java/ai/neargo/shop/scenario/MemberGuestRegistrationIT.java`
- `backend/shop-app/src/test/resources/schema-test.sql`
- `backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java`
- `backend/shop-base/src/main/java/ai/neargo/shop/spi/member/CustomerIdentityInternalApi.java`
- `backend/shop-base/src/main/java/ai/neargo/shop/spi/member/CustomerIdentityPort.java`
- `backend/shop-base/src/main/java/ai/neargo/shop/spi/member/GuestRegistrationInternalApi.java`
- `backend/shop-base/src/main/java/ai/neargo/shop/spi/member/GuestRegistrationPort.java`
- `backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalServiceResponses.java`
- `backend/shop-core/src/main/java/ai/neargo/shop/member/config/MemberGuestCrypto.java`
- `backend/shop-core/src/main/java/ai/neargo/shop/member/entity/MbrGuestRegistration.java`
- `backend/shop-core/src/main/java/ai/neargo/shop/member/entity/MbrIdentityTicket.java`
- `backend/shop-core/src/main/java/ai/neargo/shop/member/mapper/MemberGuestRegistrationMapper.java`
- `backend/shop-core/src/main/java/ai/neargo/shop/member/port/MemberCustomerIdentityPortImpl.java`
- `backend/shop-core/src/main/java/ai/neargo/shop/member/port/MemberGuestRegistrationPortImpl.java`
- `backend/shop-core/src/main/java/ai/neargo/shop/member/service/MemberCodeResolveService.java`
- `backend/shop-core/src/main/java/ai/neargo/shop/member/service/MemberGuestRegistrationService.java`
- `backend/shop-core/src/main/java/ai/neargo/shop/member/service/impl/MemberCodeResolveServiceImpl.java`
- `backend/shop-core/src/main/java/ai/neargo/shop/member/service/impl/MemberGuestRegistrationServiceImpl.java`
- `docs/technical/design/美业系统-API清单.md`
- `docs/technical/reference/glossary.json`
- `docs/technical/reference/中英文对照-实体与字典.md`
- `docs/technical/reference/中英文对照-词条.md`
- `docs/technical/reference/静态常量清单.md`
- `scripts/verify-member-guest-registration.mjs`
