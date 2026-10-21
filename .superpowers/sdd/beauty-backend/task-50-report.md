# Task50 BM03 shared atomic capacity implementation report

Status: implemented; native/MySQL and affected-kernel verification results below. Base `7ed33ff72`, worktree `beauty-data`, branch `codex/beauty-foundation`. Implementer self-review only; no subagents/reviewers spawned. Controller owns main TDD/ledger and any independent review.

## Delivered contract and transaction protocol

- `shop-merchant: ai.neargo.shop.merchant.service.ResourceBookingService.execute(ResourceCommandService.Scope, String commandNo) -> ResourceCommandService.Receipt`. No HTTP/API/industry provider was added. Scope comes from an already-authorized owner adapter; knowing commandNo is not authority.
- The original `ResourceCommandService` contract, shared `ResourcePlanContract`, source authority contract, and V532–V535 migrations remain untouched. Existing `ResourceBookingPersistenceIT` remains its 17 foundation constraints/wiring cases; new `ResourceBookingExecutionIT` adds 16 actual executor cases. The existing tmpfs MySQL wrapper runs both, offline with the required isolated `/tmp/codex-beauty-bm01-m2` Maven repository.
- Owner-local immutable accepted command is read first. Registered real source authority is invoked outside any resource transaction. Original terminal commands return their unchanged Receipt before source IO. UNKNOWN/PENDING preserve ACCEPTED. Exact durable DENIED and AUTHORIZED+expired are queried through `recover` before journal root-locked finalization (`SOURCE_DENIED`, `SOURCE_PERMISSION_EXPIRED`); this waits out concurrent executors and returns any already-committed original terminal result. No timeout releases a source fence.
- Writable primary READ_COMMITTED transaction acquires existing journal scope/owner/command guards, private live source proof, canonical personnel roots, full sorted old/new resource roots, then hold root and sorted resource/time buckets. Old plan comes from the scoped original creating command, not a transactional source RPC. Admission runs only after the union is locked. Release/expire can retire inactive resources without pretending those resources can accept new bookings.
- Five-minute UTC buckets are initialized only under roots, with authoritative `MchResource.capacity` and version as capacityRevision. Bucket identity excludes store, so STAFF shares capacity across stores. Every net delta is validated before counters are written; SQL independently checks nonnegative quantities/capacity and version CAS. Quantities aggregate in long.
- Whole current-plan allocations are reconstructed from the immutable creation command and compared with every active unit, including deleted-unit rejection. Per-bucket totals must match all unreleased units and live owner/hold parent evidence. Orphan/deleted/terminal parents fail closed; no missing join silently means free capacity.
- Counts, hold/unit history, owner head CAS, immutable original command result, and platform outbox event commit together. Existing `PlatformPublicationPublisher` and `shop.resource.publication.archive-only` configuration are reused. New shared safe event `ai.neargo.shop.spi.resource.ResourceBookingChanged` carries owner aggregate, entity/store, command/hold/action/ownerRevision only; event type `RESOURCE_BOOKING_CHANGED`, aggregate type `RESOURCE_BOOKING`, stream `RESOURCE_BOOKING:<ownerNo>`. No source payload, qualification or permission credential is published.

## Actions

| Action | Implemented behavior |
|---|---|
| RESERVE | No current owner head/target; future complete plan; 15-minute HELD, leaseRevision=1, SOURCE_COORDINATED; all resources or rollback. |
| PROTECT / RENEW | Current live HELD, exact target hold/version/lease and complete plan hash; fresh source permission and admission; now+15 minutes, leaseRevision+1; source revision advances with owner. |
| CONFIRM | Current unexpired HELD, exact target/plan/source; fresh admission; held→confirmed once; same command returns original Receipt. |
| RECONCILE | Explicit controller ruling: recovery-confirm of SOURCE_COORDINATED HELD, including expired-but-unreleased; exact live RECONCILE source decision and fresh admission. Source-directed abort is separate RELEASE. CONFIRMED/terminal holds cannot resurrect. |
| RELEASE | Unstarted HELD or CONFIRMED; whole-unit release evidence retained, counters reduced and owner cleared atomically. |
| EXPIRE | HELD only, now>=expiresAt and exact lease target; source permission required; confirmed never reclaimed via TTL. |
| REPLACE | Full old/new canonical root union; net per-bucket deltas; old units/hold retained as released history and linked to a new hold; same prior HELD/CONFIRMED state; failure preserves old head, units, expiry and claim. New hold has fresh 15-minute lease and a new creation/current hash for its own complete plan. |

Revision ruling approved by controller: hold.sourceRevision must equal locked owner.sourceRevision; command.sourceRevision may be higher but never lower, and exact live source permission binds that command revision. Retained/new hold and owner move together. Source business revisions can change between booking operations.

REPLACE hash ruling approved by controller after the brief: old hold.creationPlanHash remains immutable; new hold.creationPlanHash=currentPlanHash=hash(new complete frozen plan), because the replacement is created by a new command. This supersedes interpreting “preserve original creationPlanHash” as copying the predecessor's creation hash onto the successor. The native test asserts both old immutability and the new independent hash.

Known local BookingRejected outcomes are journaled only after TransactionTemplate has completed rollback. SQL/transaction/commit/source/authorization exceptions are never guessed into REJECTED: find original command first, return a discovered terminal result, otherwise throw while preserving ACCEPTED. A rollback failure replaces the typed domain exception, so it cannot reach the domain-rejection catch. Original-number retry is the recovery path.

## RED/GREEN and real logs

Commands use `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` and the actual existing wrapper:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home node scripts/verify-resource-booking-persistence.mjs
```

| Evidence | Actual output | Log |
|---|---|---|
| Initial RED | Tests run:25, Failures:8, Errors:0, Skipped:0. Existing 17 foundation cases pass; new 8 assert missing executor. | `/tmp/task50-red.log` |
| First GREEN | Tests run:28, Failures:0, Errors:0, Skipped:0; BUILD SUCCESS | `/tmp/task50-green1.log` |
| Source liveness/hash/revision GREEN | Tests run:30, Failures:0, Errors:0, Skipped:0; BUILD SUCCESS | `/tmp/task50-green2.log` |
| Integrity RED | Tests run:33, Failures:3, Errors:0. Two intended REDs: missing old unit silently retired its claim; counter=0 falsely admitted another claim. Third was a JDBC fault fixture matcher: SQL formatting prevented fault from firing. Fixture corrected to match formatted SQL. | `/tmp/task50-edge-red.log` |
| Integrity / real commit-unknown GREEN | Tests run:33, Failures:0, Errors:0, Skipped:0; BUILD SUCCESS | `/tmp/task50-green3.log` |
| Guard ablation RED | Removed only counter agreement predicate; `bucketCounterMustAgreeWithAllUnreleasedHoldUnitsBeforeNewAllocation`: Tests run:1, Failures:1, Errors:0; “Expecting code to raise a throwable.” Original code restored in finally. This is a real capacity-integrity failure: without guard, a drifted bucket permits another claim while old units remain. | `/tmp/task50-ablation-red.log` |
| Restored guard + affected shared kernel | 202 total (shop-base9 + merchant31 + app162), 201 pass, 1 unchanged baseline ArchitectureTest.noFullyQualifiedReferences failure; all resource/kernel cases and BackendI18nParityTest6 pass | `/tmp/task50-shared-green.log` |

Ablation harness `/tmp/task50-ablation.py`/`.mjs` preserves/restores exact production bytes in finally. Combined shared runner `/tmp/task50-shared.mjs` is generated from the real booking wrapper, with its root fixed to this worktree and every existing `resource*.test.admin-url` bound to the same disposable MySQL instance; each test creates its own database. It uses `mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test -Dsurefire.failIfNoSpecifiedTests=false` and the following selection:

```text
ResourceBookingPersistenceIT,ResourceBookingExecutionIT,ResourceCommandPersistenceIT,
ResourceAdmissionPersistenceIT,ResourceSourceAuthorizationIT,ResourceRecoveryFactsIT,
ResourceFactsPersistenceIT,ResourceMaintenanceIT,ResourceRecoveryContractTest,
ResourceRecoveryControllerTest,MerchantFactsControllerTest,ResourceDemandNormalizerTest,
ResourceSourceContractTest,ResourcePlanContractTest,ArchitectureTest,BackendI18nParityTest
```

Architecture baseline details: `ArchitectureTest` ran16 with1 failure in `noFullyQualifiedReferences` (line237): `OpsPointsInspectionController.java: @RequestParam String typeCode, @org.springframework.web.bind.annotation.PathVariable String voucherNo) {`. File `backend/shop-app/src/main/java/ai/neargo/shop/portal/ops/pay/OpsPointsInspectionController.java` is unchanged versus Task50 base7ed33ff72; the same FQN annotation is present in that base. Controller identified this as existing at55d615a82. It was not changed or added to a known-failure list. Combined Maven command exits1 due solely to that baseline; no global release-green claim is made. Restored booking execution16 + foundation17 pass within this final combined run.

`git diff --check`: clean. `node scripts/check-sql-portability.mjs`: 162 matches/51 files, known debt54, no new dialect dependencies. No migration/known-* changes. Verification covers the affected shared-resource kernel and selected architecture/i18n gates, not the entire application's unrelated test suite or production deployment.

## Executor acceptance tests

All run actual Mappers, original journal, source gate, admission, platform outbox and MySQL. Test-only RegisteredSource persists immutable decisions keyed by exact registered Binding; unregistered intent denies, UNKNOWN/PENDING cannot grant. No production unconditional source is present.

| Acceptance | Native method in ResourceBookingExecutionIT |
|---|---|
| Complete two-resource reserve, confirm counters, same-number immutable replay | twoResourceReserveIsAtomicAndOriginalReceiptNeverChanges |
| Second resource shortage preserves all prior rows/head and rolls back new first-resource bucket | secondResourceShortageRollsBackFirstResourceAndAllNewRows |
| Two simultaneous canonical/CI alias STAFF commands, one successful | concurrentCommandsForSameStaffCannotOversell |
| 15-minute PROTECT/RENEW, lease and higher source revisions, confirm, confirmed TTL refusal, release history | protectRenewConfirmReleaseKeepRevisionsAndHistory |
| Early EXPIRE refusal, exact expiry boundary, expired confirm/renew/protect refusal | expirationRequiresCurrentLeaseAndHeldDeadline |
| Failed REPLACE preserves old, overlapping net replacement, history links, CONFIRMED successor, changed-plan hashes | replaceUsesNetCapacityKeepsOriginalHashAndFailedReplacementKeepsOldClaim |
| Lower source revision/started execution refusal | staleSourceRevisionAndStartedExecutionNeverRelease |
| Pending source, storage-trigger rollback and original-number retry | deniedOrExpiredSourceDoesNotWriteAndUnknownTransactionRemainsRecoverable |
| Protected expired RECONCILE, pending source protection and confirmed resurrection refusal | protectedExpiredHoldReconcilesOnlyWithLiveExactSourceDecision |
| Actual outbox INSERT trigger failure rolls back counts, history, owner and journal | outboxInsertFailureRollsBackCapacityHistoryOwnerAndOriginalResult |
| Two worker threads/cross-store canonical STAFF bucket reuse after fixture-only schedule handoff | crossStoreStaffCommandsShareOneBucketDuringFixtureScheduleHandoff |
| Queried durable DENIED/expired decision closes original without resource effects | durableDeniedAndExpiredPermissionsTerminalizeOriginalWithoutResourceEffects |
| Old hold/lease/owner version cannot mutate | staleOwnerHoldAndLeaseRevisionsRefuseAllCapacityMutations |
| Partial missing unit cannot retire head | missingUnitOrIncorrectBucketCountCannotSilentlyRetireAClaim |
| Drifted count cannot grant capacity; ablation catches missing guard | bucketCounterMustAgreeWithAllUnreleasedHoldUnitsBeforeNewAllocation |
| JDBC proxy throws SQLException(08007) AFTER actual MySQL commit; find returns one original success/hold/event | lostCommitAcknowledgementFindsOriginalSuccessAndNeverWritesASecondEffect |

Cross-store fixture qualification: ScheduleWindowAssembler correctly rejects overlapping active store rules, so simultaneous eligibility at two stores is impossible. The fixture switches its schedule under actual personnel/resource locks after the first successful reservation and before the second worker executes. This is deliberate fixture SQL to isolate shared bucket identity; production maintenance must reject that edit while active holds exist. Same-store synchronized contention independently proves capacity arbitration under simultaneous competing commands. No schedule validation was weakened.

## Self-review / next-task integration

- Production source authority is intentionally absent until Task51. This task proves the trusted kernel with explicitly registered test-only immutable source decisions; no complete beauty business/source-fence acceptance or public HTTP is claimed.
- Task51 must derive trusted scope/operator, accept persistent full plans, retain/query source fences until original terminal receipt is reconciled, and use original-number execute/find for unknown results. DENIED and permission expiry close commands only after source truth and journal locking; they do not release source-side fences by timeout.
- New safe event needs an explicit real PLATFORM route or the existing `shop.resource.publication.archive-only=true` opt-in; default publication fails closed without a route. Tests use real archive publication SQL, not a fake publisher.
- Stale owner/target exceptions from the frozen journal guard are not misclassified as storage rejection; they refuse mutations and can later be terminalized by exact source expired/denied recovery. No new typed exception was added to the frozen journal contract.
- No automatic internal deadlock retry was added: database exceptions preserve ACCEPTED, and bounded caller recovery uses the same command number. Neither new accepted commands nor altered payloads are invented on retry.
- Server current time is UTC via injected Clock (systemUTC fallback); only new RESERVE/REPLACE plans reject past business starts. The existing fixed contract has no store lead-time setting, so no unsupported setting was added.
- No public query/HTTP, industry-specific policy, occupation lifecycle, source adapter, deployment, dependency/network change, shared database or push is included.

## Owned files

1. backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/ResourceBookingService.java
2. backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/ResourceBookingServiceImpl.java
3. backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/mapper/MerchantMappers.java (only booking bucket/hold/unit methods and two DTO imports)
4. backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/dto/ResourceBookingAllocationRow.java
5. backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/dto/ResourceBookingCounterRow.java
6. backend/shop-base/src/main/java/ai/neargo/shop/spi/resource/ResourceBookingChanged.java (controller-authorized same-TX safe event)
7. backend/shop-app/src/test/java/ai/neargo/shop/scenario/ResourceBookingExecutionIT.java
8. scripts/verify-resource-booking-persistence.mjs
9. .superpowers/sdd/beauty-backend/task-50-report.md

Main beauty TDD and progress ledger modifications visible in the shared worktree belong to the controller and are excluded from this commit.
