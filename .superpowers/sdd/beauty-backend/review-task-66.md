# Task66 independent review — R0

Spec: **FAIL**  
Quality: **CHANGES_REQUIRED**  
Findings: **0 Critical / 1 Important / 0 Minor**

Independent reviewer: `/root/review_task66_counter_purchase`. Reviewed the frozen candidate, not the worker’s self-review verdict. This report does not authorize integration or deployment.

## Exact scope and integrity

Worktree: `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`.

- Base: `bc5d0646b4cc2eb354cafee8d278ddafee30b10f`.
- Candidate tree: `1b9b6bd7cff067dd80d13ce1971cbbb447c46587` (the supplied uncommitted review snapshot).
- Complete product/test/runner diff: 85 paths, 953582 bytes, SHA256 `cb0e6bd499e73b53f025ed472bfb328c3bb2e4a19a46e7e93d5be2fc10798e81`.
- Complete root documentation diff: 14 paths, 314915 bytes, SHA256 `9db737e3e2a8fc31f1fe44c157987b37dfab2a9b02912db2bfa169a3e45393e5`.

Read the full product diff and semantic root contract changes in chunks, recovering truncated portions. Mechanically checked opaque fingerprint tables. All 99 manifest files match their lengths/hashes; all 91 released inventory entries match; all 444 restored native-gate pins match; all 448 final provenance entries match. These checks used file reads and hashing, without Git commands or changes to source, index, or HEAD.

Binding requirements were the complete Task66 brief including every root addendum, accepted Task65 design §§1/2/5 M2a/6 A1–A8/7, repository development standards and Java reference, and the subagent-driven-development task-reviewer guidance. CLAIMED_GUEST, nested owner composition, frozen compensation, cashier handoff, nullable hydration repair, the two diagnostic-backed stock dependencies, and the exact ObjectMapper classification correction were reviewed as new Task66 requirements. They were not assumed covered by Task65 approval.

## I1 — Important: counter quote reads bypass the authenticated action proof at the shared owner

**Primary location:** [CheckoutServiceImpl.java:329](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java:329), specifically lines 330–338.

`readCounterQuote` calls `access.requireMerchant(scope, CHECKOUT_PREPARE)` and `access.requireMerchant(scope, ORDER_VIEW)`, then loads the operation/quote and returns its typed customer and quote. It never requires the complete `MANUAL_SERVICE_ADMISSION / COUNTER_READ` bound request, and never validates a genuine current local token when a bound action is absent.

**Concrete scenario:** a shared-owner invocation under `DelegatedIdentityContext.call` for a currently authorized merchant can call `ManualServiceAdmissionPort.readCounterQuote(scope, operation)` with no machine action binding and no validated local token. The installed principal satisfies `SecurityUtils.requireUser`; the unchanged `CheckoutAccessPolicy.requireMerchant` checks that principal, actor/scope and current merchant grants but does not authenticate a token or require any `BoundActionContext`. The operation scope and counter checks then allow the read. Similarly, a call made while a different valid action/body is bound can pass a same-scope operation argument that is absent from that proof. This is exactly the owner-side correspondence check performed for the other new counter actions but omitted for read.

**Impact:** the reusable quote owner treats a raw delegated identity or unrelated bound request as sufficient authority to disclose counter quote/customer data. The protected internal HTTP handler still validates its own `COUNTER_READ` route, so this finding does not claim an unauthenticated public HTTP endpoint or a cross-store SQL bypass. It identifies the missing fail-closed shared-owner boundary required by design §2.3 and A8, where the full original scoped request, target and purpose must agree with the action being performed.

**Fix intent:** introduce/use a read-specific policy that requires the exact reconstructed `InternalCall<Scoped<OperationRef>>`, current delegated identity, endpoint, action, target and full scope/body when bound; otherwise require a genuine validated current MERCHANT session/token. Retain live `CHECKOUT_PREPARE` and `ORDER_VIEW` authorization and existing operation scope checks. Preserve the ability of an authorized different cashier to query an original. Do not fix this by imposing receipt/acceptance permissions such as `CASH_RECEIVE` or `MANUAL_CONFIRM` on quote reads.

**Focused regression intent:** at the actual shared owner, reject raw unbound delegation, a different bound purpose, and a changed operation/full scope under an otherwise valid bound request. Retain positive exact-bound and genuine local-session reads with the existing read permission set. The present remote protocol test verifies the outbound READ binding, but its mocked owner does not exercise this missing service check; the real bridge hostile cases exercise PREPARE. Existing successful cashier-read coverage is not a negative proof test for READ.

## Spec and quality assessment of the remaining changes

The following are assessed from source plus the specific recorded evidence, not from summing disjoint runs into a new suite result.

| Area | Assessment |
| --- | --- |
| A1 shared USER/GUEST cash purchase | Common quote/order/stock/receipt/source flow is reused. No second beauty amount ledger or synthetic consumer is introduced. Actual A1 covers both parties and the original C manual control. |
| A2 acceptance and authority | Explicit quote/hash/revision/time acceptance is distinct from identity resolution. Original acceptance namespace remains immutable; place retries derive their key from the validated original namespace while current cashier authority is independently checked. Post-member account revocation has a meaningful ablation. I1 is the remaining read-action proof defect. |
| A3 canonical identity and claim races | USER resolves actual member→person→active user. Explicit CLAIMED_GUEST returns canonical USER without requiring or creating membership/person. The positive claim revision is selector evidence, not a fabricated USER revision. Issued GUEST quotes and existing originals remain frozen through later claim; a new claimed selection uses current owner facts. |
| A4 schema and hydration | V542 conditionally permits genuine GUEST shapes across six aggregates, retains ordinary defaults and legacy namespaces, and adds constraints without modifying applied V541/V14. Removing nullable field initializers is paired with explicit legacy revision 1 at real creation paths. Tests assert actual mapped NULL/revision values, not SQL alone. |
| A5 original stock and recovery | Stable original IDs, authority, manifest and plan survive retries. Core reserves/observes/releases outside root locks. Compensation persists COMPENSATING before release, preserves reservation and typed release evidence, and cannot turn release proof into a new reserve/finalize. Nonlocking committed-parent checks and the operation→quote→confirmation→intent arbitration avoid reverse parent locking. Actual two-connection tests cover the competition and CREATED retry versus receipt progress. |
| DUAL and inventory repairs | Frozen-authority dispatch uses the existing DUAL producer inside the platform owner transaction. Actual reserve/outbox evidence supports enqueue, not end-to-end mirror delivery. Inventory release uses loaded original ownerId/externalRef despite current mapping changes. HELD cancellation/expiry now writes real released quantities with balance/line/head affected-row checks and rollback, retaining tracked revision and terminal replay semantics. Null/blank category cache bypass is narrow. |
| A6 private reads | Current consumer ownership is obtained from the member owner outside Core roots. Parent and child identity/scope predicates remain exact; NULL buyer is not an omitted filter. Current claim proof grants read entitlement without rewriting historical GUEST identity. MP155 consumption is correctly left to Task67. |
| A7 financial isolation | Actual USER and GUEST CASH/ADMITTED sources are exercised against payment, legacy receipt repair, refund dispatch/results/recovery, points/resources, and settlement boundaries. The source/stock snapshots and zero forbidden owner effects are checked. Ordinary funded terminal recovery controls remain separately evidenced. |
| A8 transport and composition | Remote methods bind complete requests and validate replies; actual outer PREPARE→nested member controllers→canonical owners restores/rechecks the outer proof. Config-created adapters use the existing token and old constructors remain compatible. No generic auth weakening or direct business installation of bound context was added. READ owner enforcement has the I1 gap. |
| Scope and compatibility | M2b/V9, FULL_CUT execution, service start authority, Pay integration and formal deployment remain deferred. No new canStart authority is introduced. Legacy hashing paths/constructors remain separated from new typed counter domains. Three unchanged released mappers use their existing parameterized queries; unused planned declarations are not required. |
| Architecture and maintainability | Services declare their interfaces independently and adapters retain owner boundaries. The ObjectMapper exclusion is exact-type, with the same rule allowing JSON and rejecting the real persistence OperationMapper fixture. No baseline/POM manipulation or product relocation was found. Tests are substantial but exercise real state, protocol boundaries, concurrency and rollback rather than merely duplicating implementations. |

No additional actionable Critical, Important or Minor defect was established. Existing disclosed compiler and Mockito notices are not recast as changed-feature defects without a concrete changed risk.

## Evidence inspected and its limits

Read `task-66-report.md` and `task-66-code-inventory.json` as claims to verify. Parsed the actual selected XML files and actual cleanup JSON for all 16 runs in `task-66-evidence/final-native-evidence-index.json`; testcase names/counts agree with the index. Each cleanup artifact reports owned container, temporary directory and lock absent. I did not query Docker or rerun cleanup during this read-only review; the root’s independent resource checks remain recorded historical evidence.

Distinct run results verified:

- Current-owner RED 1 own failure, then GREEN 1 pass.
- First V542/A1 RED 1 own failure.
- Bridge RED 1 own failure; failed GREEN 1 failure; hydration diagnostic 1 failure; corrected bridge GREEN 1 pass. Actual bridge output identifies canonical injected owners, USER and CLAIMED_GUEST 200 responses, NULL mapping, and full-payload/purpose/unbound/echo/revocation checks.
- A1 GREEN 1 pass, with six mapped aggregate NULL and revision-1 markers.
- Cashier handoff RED 1 own failure, then GREEN 1 pass.
- Original remaining29: 17 pass / 6 failures / 6 errors, zero skips. This was not GREEN.
- Recovery diagnostic: 2 own failures, zero errors/skips. This was diagnostic evidence.
- Focused failed12 plus two new controls: 11 pass / 1 failure / 2 errors, zero skips. Actual output includes DUAL owner transaction/producer/outbox and both USER/GUEST CASH_ADMITTED financial guard markers.
- Final focused3: all pass, zero failures/errors/skips, including actual balance/line/head rollback+retry and distinct-connection finalizer-wins markers.
- Single A2 ablation: 1 own expected-throw assertion failure, zero errors/skips. Exact source and all five class hashes were restored through the recorded real 31-module compile (39.557 seconds), not class copying. The separately approved same restored focused method passed.

Read `final-source-class-provenance.json`, `final-source-static-checks.json`, ablation `restoration.json`, actual restoration compile output, actual protocol/config output (8 pass), finite runner output (12 pass), corrected architecture summary/log and all three actual corrected XMLs (18/13/13, each zero failures/errors/skips; Maven 26.602 seconds). Current provenance bytes match. Static output reports no changed product POM, known baseline or out-of-release source. Root documentation records five generator checks, paired metadata equality, SQL portability and controller cohesion passing; I reviewed those semantic documentation deltas but did not rerun these commands.

The native evidence uses disclosed reduced structural platform/inventory fixtures plus the real migrations and bounded pre-V542 legacy rows. It is not a complete historical Flyway replay, packaged deployment, external payment run, or end-to-end DUAL delivery proof. No new native/Maven/business tests were executed by this reviewer. I1 is established by the concrete owner call chain and missing check, not presented as a newly executed failing test.

## Named outside-diff lookups

Outside-code reads were limited to concrete risks:

1. **Could an existing inherited policy already authenticate READ?** Read `backend/shop-core/src/main/java/ai/neargo/shop/trade/policy/CheckoutAccessPolicy.java`, then the relevant `SecurityUtils`, `DelegatedIdentityContext` and `BizIdentityResolver` definitions under `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/`. They confirm that `requireMerchant` accepts the delegated principal plus live grants and does not require a token/action proof; genuine-token checks exist in the separate buyer/cashier paths. This supports I1.
2. **Does the existing DUAL producer actually retain RESERVE/RELEASE outbox production?** Read the `lock`, `release` and `mirror` sections of `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/port/DualWriteStockPort.java`. They call the platform owner and publish the existing mirror events without swallowing producer failure. Combined with the new local owner transaction and real recorded outbox test, this supports the enqueue claim without asserting delivery.

No repository-wide architecture crawl, Git operation, product edit, test rerun, commit, push or deployment was performed. The only reviewer-written artifact is this report. Resolve I1 and provide the focused regression evidence before re-reviewing the fix.
