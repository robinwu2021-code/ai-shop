# BM04 cross-task integration code review

**Module specification: NEEDS_FIXES. Module quality: NEEDS_FIXES.**

**Critical: 0 · Important: 1 · Minor: 3.** The module code gate should remain open for I1. This is the bounded cross-task integration review after the individual Task54–59/60A reviews, not a repeated review of every product line, final all-P4 branch approval, or deployment acceptance.

## Reviewed identity and scope

- Read-only checkout: `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`, branch `codex/beauty-foundation`.
- BASE: `e90e12aab3acf4e336d66b0c3620ae7d981cbd1a`.
- Final product commit: `817ec6c58478d62f48fa90dda509c01815bbc9b3`.
- Documentation HEAD: `9e01eb6b1ba07b05f7bb28669e6e49228b60eb51`; independently read HEAD and checked the product-to-documentation diff, which contains only documentation/review records.
- Immutable inventory: `/tmp/bm04-module-review-9e01eb6b1/inventory.json`, SHA256 `8ad8a2bfe45ee21243961dc666ced6ffbc2e086710e3779a5072b6ca684c66ac`.
- Root report SHA256: `ae7fb89bf60a27b400e4dc9a305a6b85411983372de404ff7b2d462516780e82`.
- Independently verified all 398 inventory entries: 397 present source files match their hashes, the removed AppointmentPreparationService.java has the expected null source hash/absent file, and all 398 contexted diff hashes match. All 40 accepted-review registry artifact hashes match. Hash verification establishes snapshot identity, not a claim that every source line was reviewed.

Requirements were read before implementation: the module brief/report, main TDD §0.2/§1/§2.22/§2.37 and current §32 records, then the relevant Task54 identity, Task55C/D opening/recovery, Task56 checkout, Task57 payment, Task58 source, and Task59 wire design contracts. The explicit code-first/all-P1–P4-before-60B instruction governs this review. Historical design-phase statuses were reconciled with later accepted refinements and review records; old failed reviews were not treated as still-open findings after their documented fixes.

Paths below are rooted at the checkout above unless an absolute path is supplied. Line numbers describe the reviewed snapshot.

## Strengths

1. **Current identity and immutable identity evidence are separate.** `backend/shop-core/src/main/java/ai/neargo/shop/member/service/impl/MemberGuestRegistrationServiceImpl.java:143` validates the complete current namespace and current CUSTOMER grant before registration/claim/replay. Its original claim match at `:123` includes actor realm/kind, key, request and user, and checks an observed committed revision under the guest lock. `member/config/MemberGuestCrypto.java:60` binds AES-GCM to scope/purpose; `:68` uses a purpose-separated HMAC for contact-bearing request fingerprints. Beauty only records verified canonical identity and immutable claim evidence, rather than copying contact or constructing a buyer.
2. **Opening and source submission have durable, scoped acceptance.** `WorkOrderCoordinator.java:235` checks the canonical 052/054 alias contract and preserves extra original 054 assertions; `:504` takes root/fence/current/operation locks before the single persistence path at `:581`. `ServiceSubmissionServiceImpl.java:156` authorizes before endpoint replay; `:396` writes the permanent SOURCE_SUBMITTED receipt atomically with the submission, and `:435` compares its full namespace/payload/work-order/submission identity after any unique-key race. Successful projections are not used to rewrite the accepted command.
3. **Source allocation has independent observation and quantity defenses.** `ServiceSourceObservation.java:239` prevents a superseded UNKNOWN/higher observation from being cleared by an older in-flight response; `:263` independently rejects revision regression. `ServiceSourceBinding.java:89` locks the canonical observation, then sorted source guards, work order, tasks and submission lease. The mapper at `BeautyMappers.java:963` also checks COUNT integrality, both quantity ceilings, source pair and current observation epoch. The transaction contains no owner RPC.
4. **Checkout, funds and execution ownership stay distinct.** `CheckoutPaymentServiceImpl.java:87` uses the actual current buyer, freezes original request mapping before external preparation, and rechecks the buyer before/after dispatch. `OfflineReceiptServiceImpl.java:108` uses current cashier/session/actual account authority and at `:172` repeats it before collection. Pay's `PaymentOperationServiceImpl.java:135`/`:209` serializes on the order guard and persists SENDING before dispatch. Source readers consume original owner facts; beauty does not acquire a second money ledger.
5. **Wire and readiness boundaries are explicit.** `BoundDelegatedCalls.java:41` runs token validation and route binding before delegation; `DelegatedIdentityContext.java:33` restores the previous context in finally. `InternalBindings.java:59` requires both complete binding equality and the request HMAC. `RemoteCheckoutPort.java:120` rejects consumer confirm/place/initiate capabilities. `WorkOrderQuery.java:134` and submission task projection `ServiceSubmissionServiceImpl.java:1016` retain canStart=false. `ManagedServiceCompletionPolicy.java:17` and the minimum `StatusCasMapper.java:55` completion CAS retain SERVICE_EXECUTION blocking.

Beauty helper paths in strengths 2/3/5 are under `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/` (mapper under its sibling `mapper/`). Checkout/payment paths are under their actual shared trade/pay owners, as detailed below.

## Issues

### Critical

None found in the audited integration chains. This does not assert absence of defects in unreviewed code or unexecuted topologies.

### Important

#### I1 — Recheck the current consumer immediately before final order placement

**Location:** [CheckoutServiceImpl.java:574](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java:574), with the sole current-session check at [line 483](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java:483).

`place` calls `access.requireCurrentBuyer()` at entry, then performs catalog/campaign/coupon preflight at lines 506–515 and a separate metadata claim transaction at 517–539 before opening the final order transaction at 574–630. It never calls the current-session/active-user policy again. If the valid consumer token is revoked, expires, or the account is disabled during that preflight interval, the existing principal/token string remains in SecurityContext and the final writer can still proceed.

This is a specific mismatch with [task-56-design.md:91](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/.superpowers/sdd/beauty-backend/task-56-design.md:91), which explicitly places the request's authorization linearization immediately before the final transaction, outside locks. The implementation report at line 65 also claims that placement. I did not infer a generic requirement to revalidate indefinitely throughout an already-authorized transaction.

**The downstream defenses do not close this interval:** `OrderServiceImpl.java:2020` checks principal equality, while `CheckoutQuoteGuard.java:181` checks the principal namespace and token *presence*, not current TokenStore membership or an active user. Quote/confirmation expiry checks at `CheckoutQuoteGuard.java:219` are different from session revocation. The authoritative session and active-user lookup is only in `CheckoutAccessPolicy.java:46–73`. The existing `CheckoutAuthenticationFlowTest.java:61` revokes a token before directly invoking the policy; it does not cover revocation between place preflight and final transaction.

**Impact:** an order and its inventory/creation side effects may be accepted after the contract's required current-C authorization point has ceased to be valid. This is an Important authorization-boundary defect, not a claim that B or an arbitrary unauthenticated caller can place orders.

**Bounded fix:** immediately before the final transaction, outside all local locks, invoke `requireCurrentBuyer()` again and require it to equal the originally resolved buyer. Keep current checks on entry/replay and do not put TokenStore/remote identity I/O inside the transaction. Add a focused actual-place test that pauses/revokes the real session (or disables the real buyer) during preflight, resumes, and asserts denial before ordinary order/create-intent/inventory effects. The permanent metadata key may already be committed according to the existing recovery contract; do not discard or change that original key to fix this issue. Root should release this as a precise product fix and obtain a focused re-review.

**Verification level:** high-confidence source/control-flow finding, not a newly executed runtime reproduction. I traced the actual writer, seal, policy, and existing authentication test to establish the missing check; no unchanged suite was rerun and no checkout code was changed.

### Minor

#### M1 — The other initial successful A3 response is not fully compared

**Location:** [ServiceSubmissionIT.java:924](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ServiceSubmissionIT.java:924).

Every initial success is checked for submission number/action, but the complete stable-field comparator at 1048 applies to the chosen original response and later replay. An incorrect quantity/status/quote in the other initial successful response could escape this test. The test independently checks the permanent receipt, original command, task/allocation/source effects, single prepare, real loser conflict and full later projection; the current implementation paths do not demonstrate a corresponding incorrect second response. Therefore this remains a **nonblocking Minor coverage gap**, not an Important duplicate-effect finding. A future bounded test-only improvement can compare stable fields for every initial success, allowing only the documented recovery-lease revision difference. Do not turn legitimate live-projection changes into an immutable-result requirement.

#### M2 — Permission-label duplicate protection is explicitly biz-only

**Location:** [biz-perm-label.test.ts:66](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/packages/shared/tests/biz-perm-label.test.ts:66).

The parser rejects duplicates only among matched `biz:` entries; it does not cover `pay:cash:receive` or `trade:checkout:prepare`. This is the pre-existing parser domain, not a runtime grant check or an A8 regression. No actual duplicate or missing runtime permission was established. **Nonblocking Minor/documented boundary:** retain this limitation in the guard description; universal duplicate protection would require a precisely scoped parser/test extension. It must not be advertised as all-permission duplicate coverage. A8b's separate MerchantApi/typecheck defect was subsequently fixed and is not reopened here.

#### M3 — Existing compatibility/compiler/loader warnings remain debt

**Location:** [A11 first-green.log:76](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/.superpowers/sdd/beauty-backend/task-60a-a11-evidence/first-green.log:76), with compiler notes at lines 59/65 and the broader warning categories recorded in `task-60a-a11-report.md:36`.

Each A11 native batch contains seven Flyway warnings for MySQL 9.7 newer than the verified MySQL 8.1 range; successful batches also contain the one deliberately asserted, operation-attributed recovery warning. The recovery warning is expected behavior, not an owner/bean failure. Implicit annotation processor discovery and existing BeautyCatalogIT deprecation remain. Historical duplicate-Mapper/mapperLocations/H2/test-config, Mockito CDS/DNS, and Node MODULE_TYPELESS locale-loader diagnostics are not resolved merely because absent from this finite A11 run.

Keep compatibility/compiler debt for the authorized dependency/topology maintenance gate and retain the raw warning ledger; do not suppress it or describe output as pristine. The inspected bean assertions and code graph show no newly established duplicate business owner; warning text alone is insufficient evidence of such a defect. These warnings do not invalidate the actual narrow assertion/mutation results.

## Actual cross-task chains inspected

1. **Identity → reception → canonical opening:** shared member register/claim/current-scope and namespace checks; strict member-reference/consumer-proof methods; AES-GCM/HMAC credential handling; beauty ReceptionIdentity and GuestRegistrationServiceImpl original intent/receipt; WorkOrderCoordinator authorization-before-replay, 052/054 canonical key, root/fence/current lock, frozen identity, scope/task/draft/operation commit; claimed GUEST→USER receipt bridge. Inspected the A7 actual replacement/revocation test source and archived native evidence. This was not a fresh end-to-end real-owner execution.
2. **Original operation → source observation → quantity binding/recovery:** ServiceSubmissionAccess and the permanent BAPI-056 receipt; explicit original PREPARE fallback conditions and changed-payload conflict; lease claim, owner readQuote/findOperation/source sequence, current grant/buyer recheck before binding; observation epoch/highest/verified split; lock ordering and actual SQL CAS; immutable command and current task snapshot; ReceptionRecovery original command reconciliation and its ARRIVAL_PENDING limit. Read the accepted Task58 R2 correction so identity NOT_FOUND is not mistaken for owner availability. No source callback was treated as an execution permit or automatic doneQty increment.
3. **Current C → ordinary checkout → payment/cash → bill:** CheckoutAccessPolicy actual session and live merchant data scope; prepare identity and immutable namespace; confirm quote locking; place metadata/final transaction and writer/seal; payment original mapping, pay order guard/canonical attempt/SENDING; offline current cashier/evidence/account checks; source operation/suborder/buyer/store/fulfillment mapping and RR snapshot; source-aware bill grouping, receipt identity, nullable money, attribution and per-currency checked arithmetic. I1 was found in this chain. This was not an exhaustive re-review of every legacy pricing/refund/provider implementation.
4. **Public → typed internal → delegated owner → validated remote result:** public submission ingress resource/store validation; private work-order/source route dispatch; BoundDelegatedCalls, InternalServiceResponses, InternalBindings, DelegatedIdentityContext finally; BeautyReceptionCalls status/error-digest validation; RemoteCheckoutPort merchant-only reverse calls and explicit C prohibition; exact detail-task validation and Task59 R1 evidence. The route families share these common boundaries, but I did not claim line-by-line audit of every controller/DTO.
5. **Assembly/readiness/completion guard:** standalone missing-core/channel conditions, conditional private-server reverse CheckoutPort, enabled local source beans, api profile restrictions and the current singleton/disabled/ops test assertions; architecture registration and job-lock move provenance; canStart=false in actual projections and shared policy/minimum completion CAS. Missing owner paths return unavailable/UNKNOWN, and the module does not implement BM05 occupation/start authority. No actual current deployed bean graph was observed.

Supporting governance checks: current Beauty V1–V8 hashes match BeautySchemaValidator pins; current V537–V540 hashes were computed (V539/V540 match the runner's exact frozen pins). The BASE-to-HEAD migration diff contains only V5–V8 and V537–V540 additions, not old frozen migrations, and no known-* ratchet changes. Inspected beauty's business-domain registration, actual SQL scanner roots/Java-payload/newline implementation, source-specific checkout quote lineage, and the member-code request/body adapter-generator registrations. These checks do not replace database migration execution or exhaustive parser verification.

## Actual evidence checks

No build, test suite, native case, container, installation, generator or deployment was executed during this review. Read-only commands, source/diff inspection, hashing and XML/log parsing were used.

- **Current module compile:** independently checked all 347 plugin input hashes; all match current source. Both archived compile logs match the JSON hashes and report BUILD SUCCESS/exit0. Five plugin modules are present in the reactor summary; the second command compiles default-retail shop-app with dependencies. These are incremental `test-compile -DskipTests` results, not clean rebuilds or test executions. The plugin input capture reports the precommit documentation base; matching input bytes establish its applicability to this snapshot.
- **A3:** parsed actual focused-green XML 5/0/0/0 and restored-green 2/0/0/0, including both directed winner parameters and the unchanged concurrency methods; inspected source around initial-success comparison, full replay, acceptance and effects. Do not add these counts to claim a fresh 29-case final class run.
- **A7:** checked the archived XML hashes and parsed native-three 3/0/0/0, permission ablation 1/1/0/0, restored 1/0/0/0. Methods explicitly name canonical claim replacement, revoked-USR grant and failed replacement preserving the original hold. These are different batches, not final rerun of all three.
- **A9:** checked all 106 archived evidence hashes and read snapshot-2 identity/overlay metadata and the actual checker output stating all 25 generators current. This is the immutable A9 snapshot proof; it is not a current live full-generator run after later main-document edits.
- **A10:** checked all 29 manifest evidence hashes and parsed first/final helper XML 3/0/0/0 and mutation XML 3/2/0/0. The methods exercise valid confirmation body and strict wrong-realm helper assertions, including rejection of malformed-body 200/10400 as an identity proof. They do not execute the deferred HTTP/SQL scenario.
- **A11:** checked all 38 archived evidence hashes; parsed exact named native case 1/0/0/0, warning ablation 1/1/0/0, restored 1/0/0/0. Read raw logs: successful batches each 8 WARNs (7 compatibility + 1 expected recovery), mutation 7 compatibility WARNs and no recovery warning. Actual final Node log records 26 pass/0 fail/0 skipped; actual SQL log records 162 hits/51 files/54 known and no new dependencies. These were read, not rerun.
- **Historical Task54–59/A1 and final fixes:** registry hashes, relevant source boundaries and accepted reviews/reports establish provenance. I did not independently replay all archived suites or recalculate a single all-green module total. Task59 R1 specifically closes public-path and detail-child consistency findings; Task58 R2 closes the overbroad NOT_FOUND catch. No stale initial PENDING/failed heading was used to override those accepted fixes.
- **Named uncovered-doubt check:** I1 prompted focused reads of actual CheckoutServiceImpl.place, shared writer, CheckoutQuoteGuard, CheckoutAccessPolicy, and CheckoutAuthenticationFlowTest plus the precise Task56 design/report wording. No production code was modified or dynamic behavior claimed from this static check.

## Declined to judge

- BM05 actual occupation owner, race-safe start permit, execution/participation/steps/completion/close: explicitly not implemented by BM04; the inspected gate remains closed. Its future design must solve those contracts rather than weaken the present guard.
- BM06 full calendar/workbench/frontend and BM07/BM08/P2/P3/P4 functionality: outside this released module integration scope and explicitly not delivered.
- Formal60B current embedded/standalone child-JVM authentication, SQL/economic side effects, production token ablation, restarts and live topology: expressly deferred by the user's all-code-first instruction, therefore UNEXECUTED rather than failed or passed here.
- Real banking/third-party finance, printing and devices: not supplied by isolated fixture evidence and outside this gate.
- Final all-P4 whole-branch merge/deployment readiness and exhaustive retail/food regressions: distinct later review; no permission to merge/deploy is implied by this report.
- Full line-by-line re-review of all 398 product entries and every historical task suite: deliberately outside the bounded integration audit; exact audited chains and source/evidence limits are stated above.
- Universal permission-label duplicate parsing and all legal Java/dynamic SQL parser forms: existing bounded static tools; M2 and scanner limitations are retained rather than silently promoted to universal guarantees.

## CannotVerify

- A dynamic reproduction of I1 on a real checkout fixture was not run; the missing policy call and the explicitly required authorization point are verified from current source. The bounded product fix needs its named regression before acceptance.
- Current production token/session/permission behavior, current real databases/Flyway history, actual singleton owner beans and process-restart side effects in either formal topology remain unverified until60B.
- Current clean-from-empty compilation and every upstream cached artifact/classpath identity are not established by incremental module/default-retail compile logs. The report correctly discloses this limit; the checked 347 plugin source inputs are narrower than a full reproducible build manifest.
- Full current generated-output cleanliness after future root reconciliation is not established by A9's frozen snapshot. Root should reconcile only the affected status/index outputs and preserve the original snapshot scope.
- Historical task successes do not prove that every named case was rerun on product817ec6/doc9e01. A7/A10/A11 evidence uses different fixture boundaries and cannot be combined into a production end-to-end pass.
- Absence of a finding on other inspected paths is not blanket authentication/security approval; this was the released five-risk code audit, not penetration testing or an exhaustive threat review.

## Assessment

**Ready to close this module code gate: No, with one bounded fix.** The identity/original-operation/source-quantity/wire/readiness integration is substantively coherent, but the final placement authorization point does not satisfy its approved current-C contract. Resolve I1, obtain targeted behavioral evidence and a focused re-review; M1–M3 can remain explicit nonblocking debt. The user-directed60B and final all-P4 gates stay separate and unexecuted.

Final recheck: all 398 product inventory entries still match. HEAD remains9e01eb6b1; git status now shows a root-owned progress.md documentation edit made during review, with no product hash drift. Review actions did not mutate the checkout, index, HEAD, branch, configuration or database. Only `/tmp/review-bm04-module-code.md` was written. No subagents were dispatched.
