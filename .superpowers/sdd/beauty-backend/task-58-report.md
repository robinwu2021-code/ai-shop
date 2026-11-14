# Task58 design report

Status: IMPLEMENTATION_READY_FOR_REVIEW — kernel implemented and verified within the evidence below; independent root review pending.
The two known platform architecture failures remain open Task60 debt. No stage/commit/deploy.

The initial audit, candidate and NOT RUN sections below are archived design-phase facts. Implementation checkpoints
and the actual AC mapping supersede their pending-release status. No shared/original workspace was changed.

## Scope and base

- Assigned worktree: /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex
- Branch: codex/beauty-foundation
- DESIGN_BASE: eb9ec7734334214fe04d5b7d4e6f2593cc2cafd2
- Accepted Task57 product: 741b38178f01a6f4159fc618d57a45d75a3601e0
- Requirements authority: .superpowers/sdd/beauty-backend/task-58-brief.md
- Design artifact: .superpowers/sdd/beauty-backend/task-58-design.md
- Report artifact: .superpowers/sdd/beauty-backend/task-58-report.md

Only these two Markdown files are owned in this phase. No product/test/SQL/native/build/stage/commit actions
have been performed. No shared/original workspace file was changed. No helper/subagent was used.

## Read-only audit completed

The design audit table gives actual file:line evidence for:
- Task55C/55D trusted current B, USER/guest claim receipt, frozen scope and original OPEN draft;
- the delivered WorkOrderService open/get/getMy surface and actual Task56/57 eight-method CheckoutPort;
- PREPARE actor namespace, recovery limits, true current C checkout and current cashier receipt;
- sourceSnapshot RR, financial canonical receipts, UNKNOWN fields, per-checkout-operation source pair;
- V539 unique canonical order/subOrder identity and immutable original checkout operation;
- beauty source guards, task/binding/submission indexes, mapper predicates and transaction ownership;
- provider/Bean graph, actual shared owner bridges and fixture limitations;
- beauty V1–V7/ready7/27 and platform migration maximum V540 with frozen V539/V540 hashes;
- current native selectors/toolchain/resources and the scoped prospective owned paths.

Commands used in this phase were read-only inspection (git status/rev-parse, rg, nl/sed/cat,
Python text/hash inspection). No test/build/native SQL result is claimed by this report.

## Design decisions

1. Task58 allocates observed quantities within beauty, under its real local guard. It does not establish
   shared atomic consumption, an execution permit, or work start. canStart remains false and BM05 is closed.
2. A canonical source observation watermark is required. Existing source_qty >= allocated_qty CHECK prevents
   lowering a historical guard after a refund below held quantity; preserving the guard alone would allow
   a late old observation to be reused. V8 separates highest observed owner pair, last complete verified facts,
   and a local query epoch. A higher local epoch with a lower owner revision remains BLOCKED. A superseded
   response cannot restore READY, but a fully verified complete higher revision advances highest and BLOCKED
   under the same observation lock; this prevents delayed current lower facts from reviving refunded capacity.
   A superseded trusted owner UNKNOWN also preserves/raises highest and atomically increments epoch to invalidate
   all in-flight tickets, even without a last pair; recovery requires a new post-invalidation read. Foreign/malformed
   responses and transport failures do not get that authority. No V8 column or literal change is needed.
3. sourceRevision belongs to ord_checkout_operation. Canonical subOrder uniqueness, checkout_caller=BEAUTY,
   checkout_operation_no and original buyer/store/order identity are frozen and checked before comparing pairs.
   Before prepare resolves, the intent holds the original local operation identity and derived original owner key;
   it must not invent a canonical subOrder or trust a caller-supplied source alias.
4. Source reads deliberately claim a fresh epoch and set UNKNOWN before owner IO. Slow/failing reads temporarily
   suspend binding availability but preserve highest and last verified facts/history. GET may update this
   observation projection; it never automatically binds, confirms, places, pays, or starts work.
5. The owner source hash excludes response observedAt. A same-revision different-hash response is an owner
   contract inconsistency. READY can return only on a trusted matching highest pair or a newer revision.
6. Another current B can query original operation IDs but cannot recreate original PREPARE proof. A missing
   operation number plus NOT_FOUND in another B's namespace is not definitive absence. Recovery can require
   original actor and original prepare request; no C token or short customer reference is stored/replayed.
7. Bill amounts come from one trustworthy source response: deduplicated successful gross receipt minus net
   received can describe refunded money. UNKNOWN due=0 is nullable/untrusted. Currency totals are incomplete
   when a source is unknown or cannot be attributed wholly to this work order; there is no quantity prorating,
   cross-currency summation, due-minus-net collection authority, or new funds ledger.
8. Transactions contain only local DB work. Owner IO occurs after commit and before a separate fenced apply.
   Binding lock order is observation -> sorted line guards -> work order -> sorted tasks -> submission.

The design specifies concrete DTOs/service/helper/Mapper/Bean ownership, exact recovery phases, lock predicates,
upgrade checks, AC selectors, and a monotonic-revision ablation. No generic scheduler/background retry is proposed.

## Candidate migration (literal only)

- Proposed migration: V8__beauty_source_observation.sql
- Proposed beauty schema ready version/table count: 8 / 28
- New table: bty_source_observation (nonmonetary source facts and watermarks)
- Future source: docs/technical/design/schema/beauty-source-observation-schema.json
- Future generator: scripts/gen-beauty-source-observation-schema.mjs
- Future output: backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V8__beauty_source_observation.sql
- Candidate exact UTF-8 bytes: 4582, LF, final LF included
- Candidate SHA-256: c5b54d574624a5265366cf4bf7897ec949d107bd302f171539c461a89a9d8343

The SQL appears ONLY as a literal in the design. No SQL file or schema source/generator file has been created.
Root must independently extract/read/hash and pin the actual generated bytes before execution. This candidate
hash is not release permission. No platform V541 or edit to beauty V1–V7/platform V539/V540 is proposed.

## Verification status

- Design document final content audit: completed, including parent counterexamples for superseded higher facts
  and trusted owner UNKNOWN (with/without last pair); exact DTO/Mapper/Bean contracts and named AC are recorded.
- Literal extraction after filesystem write: one ~~~sql block, 4582 bytes, SHA-256 matches the candidate above.
- Both artifacts: UTF-8/LF/final LF, no trailing whitespace; design 830 lines at final content audit.
- Owned-path check: the two files exist and are ignored by .gitignore:42 (.superpowers/); no force-add performed.
  Ordinary git status shows only root-owned progress.md modified by another actor. This implementer did not touch it.
  The two design/report writes are the only mutations performed by this implementer.
- Product compilation: NOT RUN.
- Unit/integration/native tests: NOT RUN.
- Business RED/GREEN/ablation: NOT RUN; design lists feasible selectors, not results.
- Native MySQL/container/VM operation: NOT RUN.
- SQL execution/migration: NOT RUN.
- Stage/commit: NOT PERFORMED.

Future execution is limited to the brief's JDK21/offline Maven and cached MySQL 9.7.2, DISK64M,
colima-hxkey-tests / hxkey-tests profile, one own UUID serial run, at least 512 MiB, own-resource finally cleanup.
No historical passing test is relabeled as Task58 evidence.

## Open items

No unresolved business question prevents design completion. Root technical review/release and actual SQL pin
are outstanding. Main technical review points are monotonic source observation across refunds/late responses,
canonical operation identity before first prepare resolution, original B recovery availability, and incomplete
money attribution for a source shared across work orders. Each has a concrete contract and named selector.

Next step is root independent technical review. Implementation must wait for root IMPLEMENTATION_BASE.

## Implementation checkpoint 1

IMPLEMENTATION_BASE: 8cc2482adaa8e3f3df469307e92e78fadd5b58b3, tracked clean verified.
Root actual-file V8 pin received 2026-10-23T20:11:33.275722Z; 4582 bytes / candidate SHA unchanged.
Generated JSON/generator/SQL; initial helper expected 28 fields instead of actual 26 and failed before writing.
Corrected helper, generated exact bytes. This preparation error is not business RED.

First test: ServiceSourceObservationIT#completeFactsEstablishObservedReadinessWithoutOwnerIoUnderLocks.
Minimal fail-closed scaffold returns UNKNOWN until observed business RED. Native result NOT RUN yet.
Additional owned path: BeautyExecutionFixture.java, read at lines45-50 before edit, solely reads a dedicated
BEAUTY_NATIVE_MYSQL_PASSWORD env for isolated runner credentials; existing default fixture remains compatible.
BeautySchemaMigrationIT receives same credential and expected table count29 (28 private + history).
Schema ready/validator V8 manifest and data-scope registration are bootstrap configuration, not behavior RED claims.

Checkpoint 2: API-cache compile setup failure /tmp/task58-source-first-red.log; own UUID cleaned.
Accepted shop-base installed offline with tests skipped (/tmp/task58-base-install.log), no owner product edit.
Second native bootstrap failed at closed CHECK parser JSON_TYPE/JSON_LENGTH (/tmp/task58-source-first-red-retry.log).
Parser behavioral RED: BeautyCheckExpressionTest#sourceArrayChecksRetainTypeLengthAndRejectOtherFunctions, 1/1failure/0errors/0skip; /tmp/task58-parser-red.log.
Minimal explicit two-function whitelist GREEN: full BeautyCheckExpressionTest 9/0/0/0, /tmp/task58-parser-green.log.
Additional owned parser + same-package test audited before edit; unknown functions/arity/type/threshold preserved.
First native service business RED: original readiness selector, 1/1failure/0errors/0skip, /tmp/task58-source-first-business-red.log.
Assertion reached real service: expected observed readiness true, actual fail-closed false. V1..V8 native migration succeeded.
Own UUID codex-beauty-jdbc-26cfee37-eea3-4426-8e7a-34b7f460511d cleaned.
Implement minimum source read/persist next; monotonic and competing-observation branches remain subsequent tests.
Ruling: all source projection writes use one explicit-null Mapper completeObservation(row, expectedVersion) under
the same canonical row lock, instead of three duplicated parameter lists. The service owns branch/epoch checks;
SQL still scopes immutable canonical chain + exact version. This keeps one atomic persistence path for all branches.

Checkpoint 3: initial service GREEN 1/0/0/0, /tmp/task58-source-first-green.log; own UUID cleaned.
Watermark RED six selectors in /tmp/task58-source-watermark-red.log, 6 failures/0 errors/0 skip.
Failures were READY vs BLOCKED/UNKNOWN, missing higher pair, or epoch2 vs expected3; no timeout/setup failure.
Own UUID codex-beauty-jdbc-5ac69681-2d08-4821-a2e1-30583bb46387 cleaned.
Ruling: after no-pair superseded UNKNOWN invalidates tickets, a later superseded complete R1 still raises highest
and BLOCKED under design6.2 step5; the test final state is BLOCKED (never READY), not UNKNOWN. Its initial RED
was epoch2 vs3; this expectation clarification does not replace or fabricate RED.

Checkpoint 4: watermark native GREEN 7/0/0/0, /tmp/task58-source-watermark-green.log, own UUID cleaned.
These are observation-layer assertions; no full work-order allocation claim yet.
Submission initial fixture compile found cached shop-base-auth missing accepted CHECKOUT_PREPARE; refreshed via
offline backend -pl shop-base-auth -am install -DskipTests (/tmp/task58-auth-install.log), no source edit.
Next submission run reached context bootstrap but BeautyWriteGuardConfiguration still required ready7; not business RED.
Necessary audited owned increments: BeautyWriteGuardConfiguration.java:46, BeautyWriteGuardHostWiringTest.java:39,
BeautyPersistenceWiringTest.java:33 only ready8 compatibility. All source test containers cleaned by finally.

Checkpoint 5: genuine submission RED in /tmp/task58-submission-business-red-2.log: 1 failure/0error/0skip,
assertion explicit selected source gets a real owner quote (actual null), after successful actual OPEN workflow.
Implemented minimum durable PREPARE selection; original OPEN untouched. Recovery/binding/additions remain next steps.

Ruling: BtyGuestClaimReceipt actual fields are guestNo/claimRevision/userNo/ownerReceiptHash, no receiptNo.
Frozen guest ownership therefore stores claimReceiptHash from the immutable scoped row, not a fabricated receipt ID.
Initial getReceiptNo compile failure in /tmp/task58-submission-first-green.log is implementation error, not RED.

Checkpoint 6: first explicit PREPARE service GREEN 1/0/0/0 in /tmp/task58-submission-first-green-2.log.
Next selectors cover durable original identity, response loss/recovery, actual local binding and native shared quantity.
The current boundResponseLoss selector presently proves repeated post-bind recovery, not injected postcommit response
loss; add an actual postcommit fault/reconstructed service before claiming that stronger AC.

Checkpoint 7: /tmp/task58-submission-recovery-binding-red.log reached 5 genuine behavior failures,
1 fixture error, 0 skipped. The V5 permanent context deleted=0 CHECK correctly rejected test setup.
Corrected context/reception negative to wrong-store parent chain, retained root/task soft-delete cases.
New root-approved owned helper ServiceSourceBinding.java contains only local short TX allocation and no port.
Its observation/guard/root/task/submission lock order is separate from orchestration owner IO.

Checkpoint 8: /tmp/task58-submission-recovery-binding-green.log executed 7: 5 pass, 1 failure, 1 error.
Both failing allocation paths reveal the same implementation defect: inserted guard entity did not receive
DB default version. Fixed by locking/rereading the persisted guard, not assuming an in-memory default.
Root review found SourceLine serialization persisted dueMinor. Added exact non-money projection assertion before fix.

Checkpoint 9: /tmp/task58-source-no-money-red.log: submission 7/0/0/0; new no-money assertion
1/1failure/0errors/0skip against actual persisted dueMinor. Changed observation to sorted QuantityLine identity/
ordered/refundable/uom only, and binding compares that exact projection. Financial data stays transient.

Checkpoint 10: /tmp/task58-source-binding-watermark-green.log: observation7 + submission7, all14 pass,
0 errors/skip; includes persisted no-money assertion. Own UUID cleanup confirmed. Added next full orchestrator
selectors for explicit scope addition/stale receipt and refund watermark blocking a later binding.

Checkpoint 11: /tmp/task58-submission-addition-red.log: 2 executed, addition behavior 1 assertion failure
(CONFLICT instead of accepted addition), refund/late-R1 full binding case passes; 0 errors/skip.
Implement addition using existing WorkOrderPreparation outside TX, locked definition/policy heads before root,
once-only advanceScope and fresh COUNT1 task snapshots. Original context/draft stay immutable.

Checkpoint 12: /tmp/task58-submission-addition-green.log 2/0/0/0 with own UUID cleanup.
Next bill tests use real local service/DB with explicit synthetic external payment aliases/UNKNOWN facts.
Added minimum compiling bill bean that truthfully reports UNOBSERVED until behavioral RED.

Checkpoint 13: /tmp/task58-bill-first-red.log 3 assertion failures/0errors/0skip. Two reach sources size0;
conflicting receipt test reaches actual BOUND vs expected SOURCE_PENDING. Later assertions were masked and
are not independently claimed as RED. Implemented RR keyset manifest, original owner reads outside TX,
nullable per-currency projections and pure ServiceSourceFacts dedup/checked arithmetic. Owner BLOCKED with
current verified pair retains known historical money/receipts; binding eligibility remains separate.

Checkpoint 14: /tmp/task58-bill-first-green.log 3/0/0/0; own UUID cleanup complete. This reaches
the previously masked receipt dedup/UNKNOWN/partial allocation assertions; only original first failures are RED.
Next tests cover 073 immutable acceptance/generic original dispatch, local work-order binding projection,
current BLOCKED historical money and task/submission trace (no new purchase).

Checkpoint 15: /tmp/task58-recovery-projection-red.log: submission2/2assertfail/0err/0skip,
bill2/0/0/0. Genuine new failures are 073 NOT_FOUND and bound task projected UNBOUND. Known BLOCKED retains
historical money/receipt; trace resolves task/submission. Implemented immutable hashed 073 acceptance,
original submission dispatch, and local binding/observation state (canStart remains false).

Checkpoint 16: /tmp/task58-recovery-projection-green.log: submission2 + bill2 all4/0/0/0, own UUID cleanup.
Added the planned WorkOrderService submitServices/getBill delegates; existing OPEN/get/getMy paths remain intact.
No Controller/Remote/HTTP surface added. Existing explicit-selection and first-bill tests now enter these delegates.

Checkpoint 17: /tmp/task58-bill-parent-boundaries-red.log: 2 behavior assertion failures (wrong-store
definition accepted; healthy CNY bucket became null after EUR failure), 1 paging fixture SQL error
(missing created_at default), 0skip. Paging setup corrected with explicit audit timestamps; not counted RED.
Tightened frozen OPEN identity/hash/task parent and actual definition-version scope checks; per-currency
completeness now follows affected source buckets rather than global SOURCE_INCOMPLETE.

Checkpoint 18: /tmp/task58-bill-parent-boundaries-green.log 3/0/0/0; own UUID cleanup. Paging
now reaches 101 local manifest aliases across 100-row keyset boundary and exactly two attempts on change;
these are controlled local alias fixtures, not 101 real accepted purchases.
Added planned HOST owned BeautySourceCheckoutIT.java and narrow --beauty-source mode in existing
verify-checkout-payment.mjs. HOST uses actual member/trade/payment/beauty beans and true C session; only
a post-owner-commit prepare response drop is injected. Real money is settled by TEST channel owner.
No HOST result claimed yet; plugin artifacts must first be refreshed in the explicit offline cache.

Checkpoint 19: offline plugin install /tmp/task58-host-plugin-install.log passed (tests skipped, no test claim).
HOST /tmp/task58-host-source-first.log executed3 but all were context errors: fixture checkoutClock and
production beautyReceptionClock made owner ObjectProvider<Clock> ambiguous. Not business RED. Own UUID
cleanup verified. New HOST-only TestConfiguration selects existing fixture checkoutClock as primary; no
production owner or shared fixture edited, no provider stub.

Checkpoint 20: /tmp/task58-host-source-retry.log 3 executed: original prepared-response-loss recovery
passes; two payment paths hit PAY_CHANNEL_UNAVAILABLE because new fixture omitted the real merchant route.
These two are fixture errors, not RED. Added exact actual DIRECT/TEST merchant route used by accepted
CheckoutPaymentAuthenticationFlowTest#placed; no owner product change. Own UUID cleanup verified.

Checkpoint 21: /tmp/task58-host-source-complete.log 3/0/0/0 fresh HOST XML and own UUID cleanup.
These first three used actual consumer sessions + owner services. Tightened happy path to existing HOST
HTTP read/confirmation/order/payment/lookup with actual issued token and response advice, no beauty HTTP.
/tmp/task58-host-http-cash.log 2 executed: HTTP online happy passes; cash fixture rejected OFFLINE pay mode
before purchase (1 error, not RED). Added real store offline switch, goods pay mode and business qualification,
matching accepted owner requirements. Cash refund will use explicit controlled owner refund-ledger facts,
not claim the full reverse API process. All preceding containers cleaned by their own finally path.

Checkpoint 22: /tmp/task58-durable-boundaries-red.log fresh 8 tests, 6 failures/0 errors/0 skips.
Failures: changed derived action escaped endpoint key; concurrent same payload returned CONFLICT; concurrent
changed payload leaked DuplicateKeyException (wrapped AssertionError by future test); PREPARE bound task omitted
actual sourceLineNo; owner UNAUTHORIZED was swallowed; currency aggregate overflow still marked bill complete.
Reconstructed prepare-response recovery and cross-work-order changed target already passed. Own UUID cleanup.
Minimal correction uses existing action_log unique actionNo BTSK+28hex and full namespace/payload comparison;
receipt + scope/tasks/submission commit together, duplicate rollback completes before winner read. Namespace excludes
derived action/workOrder; payload includes target and complete DTO. No DDL. Binding VO reads actual current binding.
Bill completeness now includes per-currency aggregate result; only explicit unavailable/notfound/contract-conflict
owner errors are projected incomplete, authentication and other business failures propagate.
Cash /tmp/task58-host-cash-retry.log has 1 assertion failure at HTTP code10400; downstream money assertions masked.
Cause is test frozen setup time precedes actual confirmation consumedAt; use real current millisecond receivedAt,
within unchanged authoritative lower/upper receipt-time checks. Not a Task58 business defect.

Checkpoint 23: /tmp/task58-host-cash-final.log fresh actual HOST 1/0/0/0, own cleanup. Current-C HTTP
read/confirm/order OFFLINE, current-B HTTP cash receipt and alias, source->beauty binding/bill all passed;
canonical PO/outTrade/RC, gross101/net101/refund0 then gross101/net0/refund101 verified. Refund facts were written
through real RefundLedgerMapper in a controlled owner TX, not a full refund API. Cash changed time respects
consumedAt lower bound; no owner gate modified.
/tmp/task58-v8-boundaries.log 4 executed: upgrade/null+unique/foreign-op 3 pass, CHECK tamper fixture error
because information_schema CHECK_CLAUSE escapes quote characters. Normalize only that fixture quote encoding
before native ALTER; validator is unchanged. No business RED claimed; own cleanup.
/tmp/task58-provider-boundaries-red.log 4 executed: 2 genuine assertion RED (unclaimed customer genericCONFLICT;
known dependency error escaped pending recovery), 1 test expectation error (caller TX correctly raised existing
IllegalStateException instead of expected BizException), disabled wiring passes. Corrected test expectation,
new actual customer-required code+three languages and narrow dependency handling; no broad exception catch.

Checkpoint 24: /tmp/task58-provider-schema-green.log 5/0/0/0 (3 submission,1 disabled wiring,1 native
CHECK tamper); own cleanup. Valid ALTER of JSON_TYPE wrapper and JSON_LENGTH threshold each succeeded before
validator rejection; original constraint restored and validate passes. Exact V8 file unchanged.
/tmp/task58-history-boundaries-red.log was only a new test accessor compilation error, not RED. Corrected actual
DTO source()/subOrderNo() names. /tmp/task58-history-behavior-red.log 3 executed,1 genuine assertion RED at missing
historical source; malformed-money and concurrent derived-action cases passed. /tmp/task58-history-boundaries-green.log
same3/0/0/0, own cleanup. Root then identified pre-source quote-read failure leaving durable READY despite UNKNOWN VO.
/tmp/task58-pre-source-failure-red.log 1 assertion failure READY vs UNKNOWN (later concurrent assertion masked).
/tmp/task58-pre-source-failure-green.log 2/0/0/0, own cleanup: persisted epoch invalidation and delayed earlier ticket
cannot restore READY, history source/binding/lastVerified remains available. This is a scoped short TX, no RPC under lock.
Formatting used verified local Google Java Format1.25.2 --aosp on new files and changed tracked hunks, normal imports;
large BeautyMappers/schema-scanner surrounding text remains intact. New script ownership gen-beauty-java-method-design.py
renders JSON sourceFoundation actual local methods/providers, explicitly not Task59 HTTP. Schema/docs generators passed.

## Implementation checkpoint 25: ablation and bounded final regression

The historical design-phase NOT RUN statements above describe that phase only. IMPLEMENTATION_BASE is
8cc2482adaa8e3f3df469307e92e78fadd5b58b3; root released product work and independently pinned the actual V8
before its first execution. No stage/commit/push/deploy occurred, and no helper/subagent was used.

Ablation removed only the lower-than-highest revision rejection in ServiceSourceObservation.complete. The native
selector ServiceSourceObservationIT#refundBelowHeldQuantityBlocksLateOldObservation then failed one assertion:
expected BLOCKED, actual READY; 1 test/1 failure/0 errors/0 skips. Exact original bytes were restored in finally;
the same selector passed 1/0/0/0. Evidence: /tmp/task58-ablation.py, /tmp/task58-ablation-red.log,
/tmp/task58-ablation-restored-green.log, /tmp/task58-ablation-restoration.json. Original/restored: 19816 bytes,
SHA256 1d904c9fdb5367ecc77caaa9b3a27c5b81e304bdaf5b0cad989c367a0f654df5. Mutated: 19530 bytes,
SHA256 51f1623e15f070f3b95455d94d14b9ba9d87f03ae68429bc749e96da7a71e0e6. The retained mutated artifact
was reconstructed by the deterministic mutation script; it is not described as a separately sampled pre-run hash.
Root independently recomputed the current restored file and reviewed the assertion/cleanup evidence.

Final core command (JDK21 selected by runner, offline cache fixed in runner):
~~~sh
BEAUTY_TESTS='ServiceSourceObservationIT,ServiceSubmissionIT,WorkOrderBillIT,BeautySourceWiringTest,BeautySchemaMigrationIT#v7HistoryUpgradesToV8WithoutRewritingIntentsOrHeldQuantity+v8RejectsHalfNullWatermarksAndConflictingCanonicalDomains+v8JsonFunctionAndThresholdTamperingFailStructuralValidation,SourceAllocationConcurrencyIT,ServiceSubmissionPersistenceIT,GuestClaimReceiptIT,AppointmentBusinessIT' node scripts/verify-beauty-persistence.mjs --service-source
~~~
/tmp/task58-final-core.log: 92 tests, 0 failures/errors/skips. Individual counts are observation7, submission21,
bill12, disabled wiring1, V8 schema3, prior allocation guard12, prior submission persistence9, guest claim6,
appointment21. These are one final snapshot, not cumulative independent cases added to earlier RED/GREEN runs.
Own UUID cleanup completed for codex-beauty-jdbc-978ed4d1-83f8-49a3-a08c-a648d177f819. Fresh XML copies and
per-file SHA256 are preserved in /tmp/task58-evidence/final-core/summary.json before ordinary tests can overwrite them.

Final plugin refresh command:
~~~sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin:$PATH mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml install -DskipTests
~~~
/tmp/task58-final-plugin-install.log: all six reactor modules BUILD SUCCESS. This is compile/package/install
validation only. HOST and remaining finite ordinary gates are recorded below when complete.

## Actual AC mapping and provider limits

The design's names were prospective. This table records the actual executable selectors; no renamed nonexistent
selector is counted as executed. The native 92-test run includes every current Observation/Submission/Bill test.
Some assertions first reached GREEN because an earlier assertion failed during RED; checkpoints retain the exact
first failing assertion instead of claiming a separate pre-implementation RED for every downstream defense.

| Requirement / prospective AC | Actual selector(s) | Evidence boundary |
| --- | --- | --- |
| OPEN is only a draft; explicit selected task count | ServiceSubmissionIT#openDraftDoesNotPurchaseAndExplicitSelectionIsExact | Real beauty DB/service, controlled CheckoutPort. No confirm/place/pay call by beauty. |
| Permanent endpoint namespace, same/different key, actor realm | ServiceSubmissionIT#originalNamespaceAndChangedPayloadArePermanent; #sameEndpointKeyCannotChangeFromSelectionToAddition; #sameEndpointKeyAcrossWorkOrdersCannotChangeTarget | SOURCE_SUBMITTED acceptance freezes full namespace/payload independent of derived action and WO. |
| Actual concurrent receipt winner/replay | ServiceSubmissionIT#concurrentSameEndpointKeyReplaysOneCommittedSubmission; #concurrentChangedEndpointPayloadHasOneWinner; #concurrentDerivedActionsShareOnePermanentEndpointKey | Independent executor threads/native transactions; duplicate loser rolls back before reading receipt. |
| Current B grants and complete original chain | ServiceSubmissionIT#currentGrantAndFullParentChainAreRequired; #immutableDefinitionAndOriginalDraftParentsAreRequired | Revoked grant, deleted root/task, wrong-store context/original operation/definition, missing context, changed OPEN hash. V5 permanent context cannot soft-delete; wrong-chain case used rather than disabling its CHECK. |
| Trusted USER, guest requires claim | ServiceSubmissionIT#unclaimedGuestHasExplicitCustomerRequiredBeforeAnyPurchase; BeautySourceCheckoutIT#realGuestClaimThenCurrentConsumerPurchaseFeedsBindingAndBill | Core controlled identity plus HOST real member/cr1/guest owner receipt/current token. |
| True C purchase and B cannot act as C | BeautySourceCheckoutIT#realGuestClaimThenCurrentConsumerPurchaseFeedsBindingAndBill | Existing HOST C HTTP quote/read/confirm/order/pay/lookup; B confirm/place/payment calls denied. No beauty HTTP59 introduced. |
| Lost prepare reply and reconstructed service | ServiceSubmissionIT#prepareResponseLossRetainsOriginalOperationAfterRestart; BeautySourceCheckoutIT#lostQuoteResponseRecoversRealOriginalUnderCurrentManager | Core new service instance after local intent and injected response loss; HOST actual shared prepare commits before throw, then current replacement B queries original op. Not OS/process kill testing. |
| Original absent prepare requires original actor/request | ServiceSubmissionIT#unacceptedPrepareNeedsOriginalRequestNotImpersonation | Another actor's NOT_FOUND is not original actor definitive absence. Raw cr1/C token not persisted. |
| Added scope immutable history and stale late result | ServiceSubmissionIT#addedScopePreservesOriginalAndOldReceiptCannotBind | Real root/task/submission rows; original draft/definitions retained, current scope increments, stale receipt does not bind. |
| Bind commit reply loss/reconstruction | ServiceSubmissionIT#boundResponseLossReplaysWithoutAllocatingAgain | Actual BOUND/allocation committed then caller discards response; new service instance replays, actual bound sourceLine retained. |
| Same source across WOs; same task two keys | ServiceSubmissionIT#sameSourceAcrossWorkOrdersHasOneQuantityWinner; #sameTaskDifferentKeysCannotCreateTwoAllocations | Full submission orchestrator plus native binding TX, not only mapper guard fixture. No shared atomic consumption claim. |
| Complete observed projection excludes money | ServiceSourceObservationIT#completeFactsEstablishObservedReadinessWithoutOwnerIoUnderLocks | verified_lines is QuantityLine identity/quantity only; serialized dueMinor absent. |
| Refund below held guard, old revision cannot revive | ServiceSourceObservationIT#refundBelowHeldQuantityBlocksLateOldObservation | Real watermark+guard rows; ablated defense produces exact READY failure. |
| Superseded complete higher revision | ServiceSourceObservationIT#supersededHigherRevisionBlocksDelayedCurrentLowerFacts | Controlled owner completion ordering, real local row locks/epochs. |
| Superseded genuine UNKNOWN with last pair | ServiceSourceObservationIT#supersededTrustedUnknownWithHigherPairInvalidatesCurrentRead | Raises highest/fences all outstanding tickets; last verified retained. |
| Superseded genuine UNKNOWN with no pair | ServiceSourceObservationIT#supersededTrustedUnknownWithoutPairRequiresFreshRead | Requires fresh epoch even when delayed complete response equals prior highest. |
| Same hash pair after unknown; foreign revision domain | ServiceSourceObservationIT#unknownWatermarkCannotBeClearedByOlderEpoch; #sameRevisionDifferentHashAndForeignOperationBlock | Same-revision conflict blocks; foreign checkout operation R99 cannot raise canonical highest. |
| Slow/unavailable read durable UNKNOWN and history | WorkOrderBillIT#sourceFailureRetainsHistoricalPairAndBoundLineWithoutPretendingReady; #ownerQuoteFailureInvalidatesEarlierSourceTicketAndRetainsHistory | Covers source failure and pre-source quote failure; persists epoch invalidation and fences a deliberately delayed old reply. Historical source/binding stays visible, money null. |
| Refund/closed/unknown do not release or start | ServiceSubmissionIT#refundCancellationAndUnknownNeverReleaseHistory; #workOrderProjectsActualBindingAndNeverExecutionPermission | Allocation and immutable binding survive; canStart=false, no execution/void writes. |
| BAPI073 immutable receipt and generic original dispatch | ServiceSubmissionIT#recoveryAcceptanceIsImmutableAndOriginalOperationDispatchesUnderCurrentB | Same-key replay/different payload, original submission operation, current authorization. |
| Alias dedup, UNKNOWN due0 is null, partial WHOLE_SOURCE | WorkOrderBillIT#canonicalReceiptAliasesCountOnceAndUnknownNeverBecomesZero; #partialAllocationShowsWholeSourceAndKeepsAggregateNullable; #conflictingReceiptIdentityCannotCreateMoneyOrObservedReadiness | Controlled shared money facts, real beauty aggregation; no amount prorating or funds ledger. |
| Known BLOCKED history remains money | WorkOrderBillIT#blockedCurrentPairPreservesHistoricalMoneyAndReceipt | Eligibility distinct from canonical financial completeness. |
| Currency separation and one unavailable owner | WorkOrderBillIT#currencyBucketsStaySeparateWhenOneOwnerReadFails | Healthy currency remains complete; unavailable currency null; no total across currencies. |
| Overflow, malformed money and receipt conflict | WorkOrderBillIT#aggregateOverflowNeverReportsACompleteBill; #negativeCurrencyMismatchAndReceiptOverflowNeverCreateKnownMoney | Exact arithmetic; negative/currency/overflow failures do not create known money. |
| Source enumeration pages and changing local watermark | WorkOrderBillIT#sourceManifestTraversesAllPagesAndBoundsChangingSnapshotRetry | 101 controlled local manifest aliases across 100-row page; bounded retry. Not 101 real purchases. |
| Trace and authorization | WorkOrderBillIT#traceResolvesOriginalTaskAndSubmissionWithoutPurchasing; #ownerAuthorizationFailureIsNotPartialFinancialAvailability | Scoped task/submission trace and owner UNAUTHORIZED propagation; no payment side effects. |
| Actual canonical offline receipt, refund and UNKNOWN | BeautySourceCheckoutIT#realOfflineCanonicalReceiptAndRefundBillRemainOwnerFacts | True C HTTP OFFLINE order, current B HTTP cash/alias, canonical PO/outTrade/RC. Refund is controlled TX through actual RefundLedgerMapper; payment FAILED perturbation tests actual owner UNKNOWN, not full reverse API. |
| Actual late receipt on closed source | BeautySourceCheckoutIT#closedSourceRetainsActualLateReceiptWithoutBindingOrExecution | Real C session through owner services (not HTTP in this case), actual cancel/late settle, real source/bill money retained with blocked binding. |
| All external IO outside beauty locks | Core SourceFixture probes + HOST Checkout spy + ServiceSubmissionIT#missingCheckoutOwnerIsExplicitAndCallerTransactionsCannotReachOwner | Explicit active TX rejected; actual bind TX pure local. HOST resource admission remains fixture, not a production resource owner delivery. |
| Missing owner/known dependency/disabled DAG | ServiceSubmissionIT#missingCheckoutOwnerIsExplicitAndCallerTransactionsCannotReachOwner; #knownOwnerDependencyFailureRetainsOriginalPendingOperation; BeautySourceWiringTest | Pending original operation, no fake success. Existing IllegalStateException for caller TX retained. |
| V7 upgrade, null-pair and canonical unique domain | BeautySchemaMigrationIT#v7HistoryUpgradesToV8WithoutRewritingIntentsOrHeldQuantity; #v8RejectsHalfNullWatermarksAndConflictingCanonicalDomains | Actual native V7 rows/history then frozen V8; original submissions/guard unchanged. |
| Exact JSON CHECK function/threshold validation | BeautySchemaMigrationIT#v8JsonFunctionAndThresholdTamperingFailStructuralValidation; BeautyCheckExpressionTest#sourceArrayChecksRetainTypeLengthAndRejectOtherFunctions | Legal native ALTER succeeds before validator rejects tampered function/threshold; original restored. Parser preserves function AST/arity/boolean structure. |

## Actual implementation audit and transaction owners

All paths below are relative to the assigned worktree; line references are the final implementation, not DESIGN_BASE.

| Actual file:line | Read/write and ownership evidence |
| --- | --- |
| backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/policy/ServiceSubmissionAccess.java:39 | Current merchant/grants before owner IO; buyer() at54 resolves trusted USER or current CLAIMED guest against scoped immutable receipt. |
| backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSubmissionQuery.java:58 | Root/context/original reception/OPEN scope hash/task/frozen definition parent checks, reused before local mutations. |
| backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSubmissionServiceImpl.java:146 | Endpoint acceptance and selection; action receipt full-hash replay at435; scope append at476; only after durable commit may prepare. |
| backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSubmissionServiceImpl.java:533 | GET observes only; recovery receipt at571; lease/resume at683; availability invalidation at828; current buyer recheck at855; safe projection at951. |
| backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSourceObservation.java:91 | Claim short TX -> owner source outside TX -> completion short TX. history129/invalidateKnown145 preserve scoped canonical evidence and invalidate epoch. |
| backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSourceObservation.java:218 | Canonical row lock, superseded higher and trusted UNKNOWN branches, monotonic highest/equal pair gates; QuantityLine301 excludes money; classify332 checks owner contract. |
| backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSourceBinding.java:66 | Pure-local bind transaction; locked81 orders observation, sorted guards, root, sorted tasks, submission. Exact epoch/verified facts, lease/scope and quantity CAS before immutable binding/task/action/root commit. |
| backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderBillServiceImpl.java:92 | Read-only RR local snapshot167, owner reads229 outside TX, bounded snapshot retry; nullable source sums and exact per-currency508; trace579. No payment/receipt write. |
| backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/policy/ServiceSourceFacts.java:37 | Pure current-source money validation/dedup/gross-net-refund; canonical receipt key131. No persisted amounts. |
| backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/mapper/BeautyMappers.java:752 | Scoped keyset100 original submission manifest; lease claim782 and finish801; local exact CAS, no port calls. |
| backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/mapper/BeautyMappers.java:978 | allocateObservedQuantity: held+increment <= historical source_qty AND current netQuantity AND exact READY epoch/highest=verified pair. |
| backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/mapper/BeautyMappers.java:1210 | Canonical index reads/row lock; completion1239 writes explicit NULL fields under identity+version CAS. |
| backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautySourceConfiguration.java:34 | Concrete DAG: observation/access/query/binding/submission/bill, primary named TX manager and reception Clock; lazy optional CheckoutPort. No future providers or OfflineReceiptPort write dependency. |

The actual shared owner methods/versions audited in design remain unchanged. Beauty calls prepare/readQuote/findOperation/
source only; the HOST test calls existing C HTTP for confirm/place/pay and B HTTP for cash. No beauty code calls
OfflineReceiptPort.confirm or stores a consumer token. QuantityLine is the sole persisted verified-line shape;
due/gross/net/refund/currency/receipts come from this call's owner response. Source hash excludes observedAt as
established by actual owner audit; immutable claimReceiptHash is not a fictional receiptNo and never appears in VO.

The index/lock proof is local: V8 canonical unique identity plus row lock serializes watermarks and all guards for a
source; existing line guard unique identity spans all WOs and does not include source revision. No decrease/release
of historical allocation occurs. A stale lease/root/task/pair or net ceiling failure rolls back all bind writes.
A delayed trustworthy higher revision can block a newer epoch; an UNKNOWN invalidates every outstanding epoch.
A final source read can still be stale relative to a subsequent shared refund: Task58 is observed local allocation,
not a shared reservation/execution permission. The hard public contract remains canStart=false and BM05 closed.

## Final verification, warnings and review handoff

HOST command: `node scripts/verify-checkout-payment.mjs --beauty-source`.
/tmp/task58-final-host.log: 4/0/0/0, BUILD SUCCESS, fresh XML, own UUID cleanup. This run used current installed
Task58 product and includes the actual shared owner UNKNOWN assertion added to the cash case. XML and SHA
are archived at /tmp/task58-evidence/final-host/summary.json. Only an isolated V538 structural fixture plus exact
V539/V540 and official beauty V1-V8 migration is claimed; it is not a replay of every platform historical migration.

Ordinary plugin gates used:
~~~sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin:$PATH mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml test -Dtest=BeautyCheckExpressionTest,BeautyPersistenceWiringTest,BeautyWriteGuardHostWiringTest,BeautyConditionalWiringTest,BeautySourceWiringTest,BeautyArchitectureTest,BeautyWebIsolationTest,BookingProtocolContractTest,BookingServerWiringTest -Dsurefire.failIfNoSpecifiedTests=false
~~~
/tmp/task58-final-plugin-gates.log: core25, web4, channel1, architecture13 pass. Two BookingServerWiringTest
context assertions failed because its existing explicit mapper-mock list omitted the newly required observation
mapper. This is a wiring fixture preparation failure, not business RED or proof of a production cycle. The already
prospective-owned fixture now includes observation/guard/tradeBinding mappers and names its existing transaction
manager mock transactionManager to match the actual qualifier. It does not inject a fake CheckoutPort/provider.
Only those two cases were rerun:
~~~sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin:$PATH mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml -pl beauty-server -am test -Dtest=BookingServerWiringTest -Dsurefire.failIfNoSpecifiedTests=false
~~~
/tmp/task58-server-wiring-green.log: 2/0/0/0, BUILD SUCCESS. Final selected plugin gate cases total45 with no
failures/errors/skips; they include one wiring case also present in native92 and are not summed as unique business AC.
No further native rerun was needed: post-core changes only wrapped test SQL literals/extracted an identical taskNo
local and updated this ordinary wiring fixture; product bytes were unchanged.

Platform command:
~~~sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin:$PATH mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test -Dtest=ArchitectureTest,BackendI18nParityTest,ConditionalBeanWiringTest -Dsurefire.failIfNoSpecifiedTests=false
~~~
/tmp/task58-final-platform-gates.log: 23 executed/2 failures/0 errors/0 skips. BackendI18nParityTest6 and
ConditionalBeanWiringTest1 pass. ArchitectureTest16 has precisely the brief's known old failures:
OpsPointsInspectionController @org.springframework.web.bind.annotation.PathVariable and unregistered top-level
store package. Neither affected file was changed. This gate remains FAILED; Task58 does not declare Task60
legacy debt cleared. Selected ordinary XML and SHA are preserved in /tmp/task58-evidence/final-gates/summary.json.

Generators/checks actually run after implementation:
- `python3 scripts/gen-beauty-api-design.py --check`: PASS (20 modules/118 features).
- `python3 scripts/gen-beauty-java-method-design.py --check`: PASS, actual sourceFoundation method signatures checked.
- `python3 scripts/gen-beauty-database-design.py --check`: PASS (41 table designs/10 generated files current).
- `node scripts/gen-beauty-source-observation-schema.mjs --check`: PASS, exact frozen V8.
- `node scripts/check-sql-portability.mjs --check`: PASS, 162 historical hits/51 files/54 known entries, no new hit;
  /tmp/task58-final-sql.log. This global script scans backend, not plugins.
- `/tmp/task58-targeted-sql-portability.mjs` uses the unchanged 10 original rules and masking on actual
  BeautyMappers.java and V8 SQL, zero baseline exemptions, and fails on any hit: 0 hits, exit0,
  /tmp/task58-targeted-sql-final.log. Initial temporary adaptation incorrectly compared this reduced target set
  to the global54 baseline and reported54 stale entries; that scanner preparation failure is retained in
  /tmp/task58-targeted-sql.log, not claimed as a product defect or a passing full gate. Repository checker and
  baseline were never changed. This is static rule coverage, not a MariaDB runtime test.
- `python3 /tmp/task58-style-scan.py`: 49 owned Java files, all new files plus added lines of changed tracked
  files, no >120-character line and no code FQN. Length checks include strings; only the FQN check masks
  comments/package/import/string/reflection literals. /tmp/task58-style-scan.json records exact paths/scope.
  GJF1.25.2 --aosp was applied to new files/necessary changed hunks; large scanner-sensitive old mapper/config
  surroundings were preserved. All new references use ordinary imports.
- `git diff --check`: PASS. No new line-ending/trailing-space issue.
- /tmp/task58-frozen-sql-final.json: V1-V7/V539/V540 byte-identical to IMPLEMENTATION_BASE; V8 remains4582 bytes,
  SHA256 c5b54d574624a5265366cf4bf7897ec949d107bd302f171539c461a89a9d8343. No V9/platform V541.

Warnings are distinguished from failures. Flyway reports MySQL9.7 newer than its verified8.1 range (and an H2
version warning during HOST auxiliary startup); tests still use the required isolated primary native MySQL.
Mockito/ByteBuddy dynamic agent and JVM CDS notices are test-toolchain warnings. HOST emits existing mapper-scan
aliases, absent logistics implementations/keys, disabled development OTP limits, dev seeding/shipping-key and TEST
currency fallback notices; these fixture/development defaults are not deployment validation. The appointment
regression intentionally logs deferred recovery in its failure scenario. Ordinary negative configuration tests log
expected context rejection; the two stale server-fixture failures above were corrected and rerun, not hidden.
No warning is used as evidence that a test passed. All native completed runner logs record finally own cleanup.

## Exact owned files and remaining boundaries

The 68 exact paths below are the implementation-owned handoff. Newly necessary paths beyond the initial list
were justified in checkpoints/root messages: ServiceSourceBinding pure-local TX helper; BeautyCheckExpression
and its test for strict JSON_TYPE/JSON_LENGTH AST; BeautyExecutionFixture credential injection; ready8 expectations
in write guard/wiring tests; existing Java-method generator renders actual kernel appendix. BookingServerWiringTest
was prospective-owned and only adapts its explicit dependency fixture. Generated Chinese docs are actual files,
not shell-escaped pseudo-paths. Root-owned progress.md is excluded and untouched by this implementer.

A final external manifest /tmp/task58-owned-manifest.json records each file's byte length/SHA256 and
IMPLEMENTATION_BASE; /tmp/task58-owned-paths.txt is the exact list. The report is included in that manifest after
its final write (no self-referential hash inside the report). /tmp/task58-owned.diff contains the complete tracked
owned diff plus new-file additions for independent review. No staged changes, commits, push, merge or deploy.

No unresolved business decision remains for this kernel. Independent root spec/quality review is pending.
Availability costs remain deliberate: source reads can transiently make an earlier READY row UNKNOWN; repeated
trusted UNKNOWN invalidates in-flight tickets; missing original B/reference can prevent preparing an absent
original op. Historical pairs and bindings remain readable without current money/execution authority. Shared
source refunds are not atomically locked by beauty; only local observed allocation is conserved. Full refund API,
real production resource admission, P2 assets, HTTP/Remote59, deployment60, BM05 execution and unrelated historical
owner/whole-backend suites are outside this evidence. All new local API/service/DTO methods are concrete; no blank
provider or second ledger has been added.

~~~text
.superpowers/sdd/beauty-backend/task-58-design.md
.superpowers/sdd/beauty-backend/task-58-report.md
backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/BusinessTraceVO.java
backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/CurrencyBillVO.java
backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/ReceiptSummaryVO.java
backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/RecoverSubmissionReq.java
backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/RecoveryRequestVO.java
backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/ServiceSubmissionVO.java
backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/SourceBillVO.java
backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/SourceObservationVO.java
backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/SubmissionTaskVO.java
backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/SubmitServicesReq.java
backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/WorkOrderBillVO.java
backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/service/ServiceSubmissionService.java
backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/service/WorkOrderBillService.java
backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/service/WorkOrderService.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyAppointmentConfiguration.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyCheckExpression.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyDataScopeRegistration.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyPersistenceConfiguration.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyReceptionConfiguration.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautySchemaReady.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautySchemaValidator.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautySourceConfiguration.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyWriteGuardConfiguration.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/entity/BtySourceObservation.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/mapper/BeautyMappers.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/policy/ServiceSourceFacts.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/policy/ServiceSubmissionAccess.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceOperationQueryServiceImpl.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceOperationRecoveryServiceImpl.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSourceBinding.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSourceObservation.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSubmissionQuery.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSubmissionServiceImpl.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderBillServiceImpl.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderQuery.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderServiceImpl.java
backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V8__beauty_source_observation.sql
backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/config/BeautyCheckExpressionTest.java
backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/BeautyExecutionFixture.java
backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/BeautyPersistenceWiringTest.java
backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/BeautySchemaMigrationIT.java
backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/BeautySourceWiringTest.java
backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/BeautyWriteGuardHostWiringTest.java
backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ServiceSourceObservationIT.java
backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ServiceSubmissionIT.java
backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/SourceFixture.java
backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/WorkOrderBillIT.java
backend-plugins/shop-industry-beauty/beauty-server/src/test/java/ai/neargo/shop/beauty/scenario/BookingServerWiringTest.java
backend/shop-app/src/main/resources/i18n/messages.properties
backend/shop-app/src/main/resources/i18n/messages_ar.properties
backend/shop-app/src/main/resources/i18n/messages_en.properties
backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautySourceCheckoutIT.java
backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java
docs/technical/design/schema/beauty-api-design.json
docs/technical/design/schema/beauty-java-method-design.json
docs/technical/design/schema/beauty-source-observation-schema.json
docs/technical/design/美业系统-API清单.md
docs/technical/design/美业系统-Controller-Service-Mapper方法清单.md
docs/technical/design/美业系统-Java类与方法清单.md
docs/technical/design/美业系统-数据库字段字典.md
docs/technical/design/美业系统-数据库结构设计.md
scripts/gen-beauty-database-design.py
scripts/gen-beauty-java-method-design.py
scripts/gen-beauty-source-observation-schema.mjs
scripts/verify-beauty-persistence.mjs
scripts/verify-checkout-payment.mjs
~~~

## ROUND1/5 — I1–I5 fix loop (ready for scoped re-review)

Root supplied the independent SPEC FAIL / NEEDS_FIXES review at
.superpowers/sdd/beauty-backend/review-task-58.md, with exact Important findings extracted in
/tmp/task58-r1-open-findings.md. This round fixes I1–I5 only. M1 is explicitly deferred by root to Task60 and its
race assertion is unchanged. The original 68-file manifest, full diff and review package remain immutable.
Fix base is staged tree63140e3b010e605c53c716f1a992f1c95c9485d0; HEAD remains
8cc2482adaa8e3f3df469307e92e78fadd5b58b3. Initial report evidence above is preserved, including its now-corrected
BLOCKED completeness result. I3 follows the binding financial-completeness design; prior broad acceptance of
BLOCKED=incomplete is superseded by root's review ruling.

### Per-finding changes and focused evidence

All source paths below share backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/;
test paths share the corresponding src/test/java/ai/neargo/shop/beauty/scenario/ directory.

| Finding | Actual correction | Actual native selector and assertions |
| --- | --- | --- |
| I1 | service/impl/ServiceSubmissionServiceImpl.java:539,695,785,856,902 now checks owner operation identity before the supported phase set, recognizes actual REJECTED/REQUOTE_REQUIRED, rechecks current B/buyer, invalidates known canonical evidence and completes the current lease to REJECTED with bounded OWNER_* reason and null nextRetryAt. mapper/BeautyMappers.java:795 extends only the existing DML CAS terminal predicate; status RECOVERING and version remain mandatory. Local terminal GET/replay does not re-enter owner recovery. service/impl/ServiceOperationQueryServiceImpl.java:65 gives terminal submissions no recovery action. | ServiceSubmissionIT#definitiveOwnerPhaseTerminatesLeaseAndAllowsNewSelection, two parameter cases (REJECTED and REQUOTE_REQUIRED), at1151. Assert no escaped exception, terminal status/reason, cleared lease deadline, preserved lastVerified with UNKNOWN, zero binding, original terminal replay/version, terminal dispatcher, and new explicit key can select the formerly excluded task. |
| I2 | service/impl/ServiceSubmissionServiceImpl.java:619 writes local submissionNo into immutable RecoveryRequestVO.operationNo. Shared checkout identity stays in existing checkout fields; no receipt rewrite or new identifier. | ServiceSubmissionIT#existingSourceRecoveryReceiptDispatchesLocalSubmissionIdentity at1225. EXISTING uses EXTERNAL-OP distinct from local BTSU; receipt/replay returns local ID and both unified query/recovery dispatch correctly, with one binding. |
| I3 | service/impl/WorkOrderBillServiceImpl.java:399 sets financially complete from verified current money plus full attribution, independently of SOURCE_OWNER_BLOCKED. Operational blocker and canStart=false remain; unknown money and partial-source aggregation stay nullable/incomplete. | WorkOrderBillIT#blockedCurrentPairPreservesHistoricalMoneyAndReceipt at200 now models fully refunded BLOCKED source: due1000/gross1000/refund1000/net0/outstanding1000, complete source/bill/currency totals and preserved operational blocker. Old incorrect incomplete assertion was replaced before RED. UNKNOWN and partial allocation selectors remain passing. |
| I4 | service/impl/ServiceSubmissionServiceImpl.java:829 includes authenticated NOT_FOUND in the narrow availability handling after successful quote verification. Exact original canonical observation is invalidated and current lease becomes SOURCE_PENDING/SOURCE_UNKNOWN. Authorization/programming errors still propagate. | ServiceSubmissionIT#absentFindAfterQuoteFencesKnownBindingTicketAndEndsLease at1285. readQuote succeeds and findOperation alone returns NOT_FOUND. Assert epoch advances, current UNKNOWN/lastVerified retained, SOURCE_PENDING, and real ServiceSourceBinding rejects the previously READY ticket as SOURCE_OBSERVATION_CHANGED. A subsequent fresh original read binds once, prepares remains1. |
| I5 | service/impl/WorkOrderBillServiceImpl.java:263,298 passes historical order/suborder plus invalidated observation into both missing-provider and non-CREATED pending results; known non-CREATED uses SOURCE_UNKNOWN, not a misleading new-customer wait. Current money remains null. | WorkOrderBillIT#missingOwnerPreservesBoundSourceHistoryWithUnknownMoney at791 removes the actual fixture bean after binding; #nonCreatedOwnerPreservesBoundSourceHistoryWithUnknownMoney at832 removes CREATED evidence. Both retain ORD-OP/SUB-OP and lastVerified/UNKNOWN; money and completeness remain unknown, DB state is fenced. |

Only existing owned files changed: the four production files named above, SourceFixture.java, ServiceSubmissionIT.java,
WorkOrderBillIT.java, and this report (8 paths total). SourceFixture adds precise external-owner phase and find-only
NOT_FOUND controls; terminal result order/suborder are null, matching actual owner output. No new owned path,
public DTO/signature, owner implementation, provider, exception, API, migration, generator or deployment was added.
The small completeLease helper shares the existing current-version completion path for pending and rejection;
it introduces no scheduler, repository, service cycle, or lock-held RPC.

### Executed commands and results

Common native runner remains the authorized cached mysql:9.7.2, DISK64M, colima-hxkey-tests/profile hxkey-tests,
MemAvailable>=524288KiB, one own UUID serial, random loopback database, credential env, finally own cleanup.
The runner chooses exact JDK21 and offline /tmp/codex-beauty-bm01-m2. No V8 bytes changed or re-pinned.

RED command (before any production correction):
~~~sh
BEAUTY_TESTS='ServiceSubmissionIT#definitiveOwnerPhaseTerminatesLeaseAndAllowsNewSelection+existingSourceRecoveryReceiptDispatchesLocalSubmissionIdentity+absentFindAfterQuoteFencesKnownBindingTicketAndEndsLease,WorkOrderBillIT#blockedCurrentPairPreservesHistoricalMoneyAndReceipt+missingOwnerPreservesBoundSourceHistoryWithUnknownMoney+nonCreatedOwnerPreservesBoundSourceHistoryWithUnknownMoney' node scripts/verify-beauty-persistence.mjs --service-source
~~~
/tmp/task58-r1-red.log: 7 tests/7 assertion failures/0 errors/0 skips, exit1. Exact first failures:
I1 both owner phases throw CONFLICT; I2 local expected BTSU versus EXTERNAL-OP; I4 unexpected NOT_FOUND;
I3 complete expected true but false; I5 both orderNo expected ORD-OP but null. Downstream assertions masked by
these first failures are not claimed as separate RED. No compile/setup/fixture error in this RED run. XML/SHA:
/tmp/task58-evidence/r1-red/summary.json. Own cleanup: codex-beauty-jdbc-5993059e-8582-4352-a382-eae36507d2eb.

First GREEN plus directly affected regression command:
~~~sh
BEAUTY_TESTS='ServiceSubmissionIT#definitiveOwnerPhaseTerminatesLeaseAndAllowsNewSelection+existingSourceRecoveryReceiptDispatchesLocalSubmissionIdentity+absentFindAfterQuoteFencesKnownBindingTicketAndEndsLease+recoveryAcceptanceIsImmutableAndOriginalOperationDispatchesUnderCurrentB+knownOwnerDependencyFailureRetainsOriginalPendingOperation,WorkOrderBillIT#blockedCurrentPairPreservesHistoricalMoneyAndReceipt+missingOwnerPreservesBoundSourceHistoryWithUnknownMoney+nonCreatedOwnerPreservesBoundSourceHistoryWithUnknownMoney+canonicalReceiptAliasesCountOnceAndUnknownNeverBecomesZero+partialAllocationShowsWholeSourceAndKeepsAggregateNullable+ownerAuthorizationFailureIsNotPartialFinancialAvailability' node scripts/verify-beauty-persistence.mjs --service-source
~~~
/tmp/task58-r1-green.log: 12/0/0/0, exit0, BUILD SUCCESS, fresh class counts6+6. Own cleanup:
codex-beauty-jdbc-6c4642b4-681c-4067-acfa-22e535f41933. This round's intermediate XML was overwritten by the
next deliberately narrowed run; the complete runner log retains its fresh execution summary. It is not presented
as independently archived XML or 12 additional unique cases beyond the final7.

After affected-hunk formatting and making terminal fixture order/suborder precisely null, reran the exact RED
command above against the fixed code. /tmp/task58-r1-final-green.log: 7/0/0/0, exit0, BUILD SUCCESS;
ServiceSubmission4 + Bill3. Fresh XML/SHA archived before any ordinary check at
/tmp/task58-evidence/r1-final-green/summary.json. Own cleanup:
codex-beauty-jdbc-37d66ab4-6e5a-49c6-8e03-3e8c2f544bb0.

One remaining125-character test SQL literal was then wrapped into two concatenated identical literals; no runtime
SQL/expectation/product behavior changed. Final compile command:
~~~sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin:$PATH mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml -pl beauty-core -am test-compile -DskipTests
~~~
/tmp/task58-r1-final-compile.log: BUILD SUCCESS, exit0; compile-only, not another test execution.

Additional scoped checks: actual method-design generator --check PASS; exact V8 generator --check PASS;
unchanged10-rule SQL scanner on actual mapper/V8 has0 hits (/tmp/task58-r1-sql.log); diff --check PASS.
/tmp/task58-r1-style-scan.json covers added lines of the7 changed Java files against original staged tree,
code FQN0 and >120 characters0. Existing import/comment/string exemptions apply only to FQN detection.
No broad92/HOST4, unrelated owner suite, platform gate, generator rewrite or second ablation was performed.
The initial known platform architecture failures remain unresolved. Existing Flyway9.7 compatibility and
Mockito/ByteBuddy/CDS notices remain toolchain warnings; no warning is a test-pass claim. Final compile notes
existing BeautyCatalogIT deprecated API/annotation processing, not a new failing test.

### Evidence limits and freeze

These new tests exercise actual local services/mappers/transactions and native constraints with a controlled
external CheckoutPort. Actual owner source confirms REJECTED for RELEASED create intent and stored
REQUOTE_REQUIRED at CheckoutServiceImpl.java:674–710; this round does not claim a new actual HOST terminal
purchase scenario. Historical canonical-to-non-CREATED transitions are deliberate external-contract perturbations
proving conservative recovery visibility, not claims about ordinary owner transition ordering. The previous actual
HOST evidence remains initial evidence and is not relabeled as an R1 execution. Financial totals describe known
owner facts; refunded outstanding is never collection authorization. canStart=false/BM05 closed remains unchanged.

Current all68 owned files and bytes are frozen in /tmp/task58-r1-owned-manifest.json; the exact8-file delta against
63140e3b010e605c53c716f1a992f1c95c9485d0 is /tmp/task58-r1-delta.diff. Initial /tmp/task58-owned-manifest.json,
/tmp/task58-owned.diff and root review package were not overwritten. The manifest is generated after this final
report append, with per-file SHA256 and delta SHA. Root-only progress/review archive/Task59 preparation/Task60
preflight are excluded. No index mutation, stage, commit, reset, shared workspace change, remote DB or foreign
resource action was performed. I1–I5 are ready for independent scoped re-review; M1 stays explicitly deferred.

## ROUND2/5 — NB1 fix loop (ready for scoped re-review)

The independent scoped review at .superpowers/sdd/beauty-backend/review-task-58-r1.md marks I1–I5 ADDRESSED
and supplies NB1 as the sole open Important finding. This round fixes only NB1. M1 remains explicitly deferred
to Task60. Fix base is the R1 staged tree ba8998e35b7d5cd94fc9270e285f4c1ef133766b; HEAD remains
8cc2482adaa8e3f3df469307e92e78fadd5b58b3. All initial and ROUND1 evidence above is preserved. In particular,
ROUND1's claimed narrow NOT_FOUND handling was too broad: the outer catch also consumed post-I/O identity errors.

### Cause, correction and coverage

ServiceSubmissionServiceImpl.resume used one outer NOT_FOUND catch for the original checkout lookup and later
current buyer/binding/view checks. For an original GUEST subsequently claimed as USER, the second owner-backed
guest lookup can throw NOT_FOUND if its live scoped guest disappears. The actual member owner throws that error
at MemberGuestRegistrationServiceImpl.java:181–187, reached through get at97 and ServiceSubmissionAccess.buyer
at62 / ReceptionIdentity.guest at24. Treating it as checkout availability improperly completed the lease to
SOURCE_PENDING/SOURCE_UNKNOWN and returned a normal view.

In backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/
ServiceSubmissionServiceImpl.java:780, NOT_FOUND normalization now surrounds only owner.findOperation for the
original operation. That exact absence still invalidates the known canonical observation and completes the lease
as SOURCE_PENDING/SOURCE_UNKNOWN. The existing original readQuote handling is retained. The outer catch at837
again handles only CHECKOUT_DEPENDENCY_UNAVAILABLE, so post-I/O access, buyer, binding and view NOT_FOUND errors
propagate. No current identity check is skipped and no new error type or API is introduced.

The new parameterized ServiceSubmissionIT#postCheckoutClaimedGuestAbsencePropagatesWithoutAvailabilityCompletion
at1360 covers CREATED and REJECTED owner results. Each opens an actual local GUEST work order, claims it through
the local GuestRegistrationService and verifies the persisted immutable receipt, then submits as its current USER.
The first recovery buyer check succeeds; after checkout reads, the second external guest lookup throws NOT_FOUND.
Both cases assert propagated NOT_FOUND, status remains RECOVERING, failure_code remains AWAITING_CUSTOMER,
version increases only for the lease claim, no binding is inserted, and prepare remains once. This establishes
no availability completion on either the binding or terminal path. The lease itself was already claimed before
the owner read; retaining RECOVERING is existing identity-failure behavior, not a new completion/retry mechanism.

Only two existing Java paths plus this report changed. The test uses ordinary imports and the existing controlled
external GuestRegistrationPort contract; it does not modify SourceFixture, actual owner code, DTOs, SQL, generator
output, providers, HTTP or deployment. There are no new owned paths. It exercises real local services, claim
receipt persistence, mappers and native constraints, with a controlled external guest disappearance matching the
actual owner NOT_FOUND contract. It is not a new HOST test or physical deletion of a real member-owner guest.

### Executed commands and results

Native execution used the same authorized cached mysql:9.7.2 / DISK64M / colima-hxkey-tests / hxkey-tests profile,
MemAvailable>=524288KiB, serial own UUID, random loopback database and credential environment, with finally own
cleanup. Exact JDK21 and offline /tmp/codex-beauty-bm01-m2 remain selected by the runner. No frozen SQL changed.

Focused RED before the production correction:
~~~sh
BEAUTY_TESTS='ServiceSubmissionIT#postCheckoutClaimedGuestAbsencePropagatesWithoutAvailabilityCompletion' node scripts/verify-beauty-persistence.mjs --service-source
~~~
/tmp/task58-r2-red.log: exit1, 2 tests / 2 assertion failures / 0 errors / 0 skipped. Both phases fail at
"Expected ai.neargo.shop.common.BizException to be thrown, but nothing was thrown." These are runtime behavior
failures, not compilation, setup or fixture errors. Later assertions were masked and are not separately claimed
as RED. Fresh XML is archived at /tmp/task58-evidence/r2-red/ with summary.json; ServiceSubmissionIT XML SHA256
278151f0ee22e5b00442759cfb146af71377cb21c72000d4b66011d689719b02.
Own cleanup: codex-beauty-jdbc-bd4b1def-85c7-4822-a935-f8349eeecbe7.

Focused GREEN plus the directly affected I4 positive and authorization propagation regression:
~~~sh
BEAUTY_TESTS='ServiceSubmissionIT#postCheckoutClaimedGuestAbsencePropagatesWithoutAvailabilityCompletion+absentFindAfterQuoteFencesKnownBindingTicketAndEndsLease,WorkOrderBillIT#ownerAuthorizationFailureIsNotPartialFinancialAvailability' node scripts/verify-beauty-persistence.mjs --service-source
~~~
/tmp/task58-r2-green.log: exit0, BUILD SUCCESS, 4 / 0 / 0 / 0 (ServiceSubmission3 + WorkOrderBill1).
The prior findOperation-only NOT_FOUND test still verifies fencing of a known ticket, original recovery and one
binding; the authorization case still propagates instead of returning financial availability. Fresh XML was
archived before compile at /tmp/task58-evidence/r2-green/summary.json:
- ServiceSubmissionIT: e0cae5c372d51aa40bfd88387d10809b5221e260c375abd7cf07171b88043c49.
- WorkOrderBillIT: 19417a7bc953242079f02736140132c1a59766e057d3d7a90c5aabf42d83b221.
Own cleanup: codex-beauty-jdbc-5ec6c622-74e3-4c69-b019-e0a923d564a3.
These are four unique focused cases; the two RED cases are not added again to the total.

After GREEN, one124-character test SQL literal was split into identical concatenated literals, with no SQL,
expectation or production behavior change. Final compile-only command:
~~~sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin:$PATH mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml -pl beauty-core -am test-compile -DskipTests
~~~
/tmp/task58-r2-compile.log: exit0, BUILD SUCCESS; no additional test execution is claimed.
Scoped checks: python3 scripts/gen-beauty-java-method-design.py --check PASS;
node scripts/gen-beauty-source-observation-schema.mjs --check PASS; git diff --check PASS.
/tmp/task58-r2-style-scan.json checks all added lines in the two changed Java files against R1 tree:
code FQN0 and >120 characters0. Import/comment/string exemptions apply only to FQN scanning; line length checks
include literals. Formatting was limited to affected hunks. There was no broad native, HOST, platform or owner
suite rerun. Existing toolchain notices and initial known platform gate failures retain their earlier classification.

### Immutable handoff and limits

The final all68 owned files are frozen in /tmp/task58-r2-owned-manifest.json with changedSinceR1 listing exactly
this report, ServiceSubmissionServiceImpl.java and ServiceSubmissionIT.java. The corresponding three-file delta
against ba8998e35b7d5cd94fc9270e285f4c1ef133766b is /tmp/task58-r2-delta.diff. The new manifest records current
per-file bytes/SHA256 and the delta bytes/SHA256. Initial and R1 manifests/diffs are retained byte-for-byte;
all10 frozen SQL files, HEAD and the owned index match their prior baselines. Root-only review/progress/Task59
preparation/Task60 preflight are excluded. This report appends to its R1 version without changing its prefix.
No stage, commit, reset, shared workspace modification, remote database or foreign resource action occurred.
NB1 is ready for fresh scoped re-review. All prior findings stay within their accepted review scope; M1, Task59,
Task60 and BM05 are not folded into this fix. canStart=false and the original owner boundaries remain unchanged.
