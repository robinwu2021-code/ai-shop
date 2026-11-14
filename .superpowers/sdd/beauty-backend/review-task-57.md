# Task 57 independent review

**Spec: FAIL. Quality: NEEDS_FIXES.** Findings: **0 Critical, 3 Important, 1 Minor**.

Task 57 has substantial, credible implementation and real-owner verification. It nevertheless contains two deterministic recovery defects and an eligibility gap that permits a first online send after cancellation or expiry. These affect the requested funds/source contract and should be fixed before acceptance. The native runner also has a smaller selector-validation gap. None of these findings depends on completing Tasks 58–60.

## Review identity and coverage

Reviewed worktree: `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`.

The code view was the root-frozen `/tmp/task57-root-review-package.diff`: base `b34e31ba4f10bc75088ccd44d1eef4ef0c5a4ac1`, staged tree `20a4801e8f7189dc1453c4d665892c0a78209c70`, 98 owned files, 847925 bytes, SHA256 `9158fff2ad561c7c78d740b5a5ff9edebb48e68a7c68fda0c490cbb75ce1d405`. The root's manifest/index/DDL checks are accepted as supplied evidence; I did not repeat git operations. The worktree's bookkeeping changes excluded by the root freeze are not product changes reviewed here.

Read the brief including appended rulings, the accepted design, and the report's final implementation/AC/debt sections. Historical report sections 1–9 were treated as design history, not evidence of the final implementation. Read production, schema, tests and scripts in bounded diff passes. The package is 14398 lines: the initial design/report hunks duplicate the separately read artifacts; production/test hunks through line 12782 were covered; the generated permission/documentation section through line 14214 was reviewed by changed lines and hunk headers; the remaining locale/mock/runner changes were covered through the end. Focused continuations were needed for cut-off `OrderServiceImpl.markPaid` and `PaymentLifecycleServiceImpl` method bodies. Outside-diff lookups were limited to the named risk areas and are recorded below.

No repository files were changed. No tests, process races, native suites, application instances or git commands were run for this review. The only deliverable written is this report. Findings I1–I3 are code/interleaving findings, not claims of a newly executed reproducer. Suggested tests below address gaps in the supplied evidence.

All source locations below are relative to the reviewed worktree. Line numbers refer to the reviewed version, not the diff file.

## Findings

### I1 — Important: a definitively rejected attempt permanently poisons the paid source projection

**Location:** `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java:1090` (`sourceFunding`, starting at 1057); supporting code `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/PaymentOperationServiceImpl.java:386` and unchanged `PaymentLedgerServiceImpl.java:584–591`.

`sourceFunding` skips a `REJECTED` operation only when `observed.receipt() == null`. The payment owner constructs the receipt view whenever the operation has a payment row. The real ledger reader returns a non-null receipt DTO for a `CLOSED` payment too; it does not restrict this DTO to successful receipts.

**Trigger:** attempt A receives a locally proved `NOT_SENT` result. Its ledger row is legitimately closed and its operation becomes `REJECTED`. The current buyer explicitly uses new key B, which is allowed for this definitive outcome. B succeeds, producing the real SUCCESS receipt and paid order. Source enumeration encounters permanent request A first. Its `REJECTED` view contains a non-null `CLOSED` receipt, so the skip condition fails and the next non-SUCCESS check returns UNKNOWN immediately. B is never considered.

**Impact:** a supported successful payment sequence remains UNKNOWN/BLOCKED with unknown money in source facts indefinitely. It cannot repair itself through retry because A's permanent request mapping is intentionally retained. This violates the requirement that true managed online/offline collection correctly projects paid facts while preserving the UNKNOWN contract. It does not mean the cash was lost or that UNKNOWN should be filled with zero.

**Fix:** explicitly distinguish a verified, definitively rejected original attempt with a non-success terminal ledger state from an unresolved/contradictory attempt. Permit source aggregation to continue past that definitively rejected attempt while still preserving any contradictory real SUCCESS and late-payment handling. Alternatively, deliberately tighten the payment-view receipt contract and update every consumer consistently. Do not delete the permanent request or blindly skip all REJECTED views irrespective of real funds.

**Missing test:** real-owner/native sequence A → proved NOT_SENT/CLOSED → current buyer explicit B → SUCCESS → source. Assert the real canonical B operation, payment and receipt, correct historical amount, and paid order/source facts. Existing NOT_SENT/new-attempt and successful-alias/source tests do not exercise their combination.

**Classification:** implementation defect, not a plan-mandated defect.

### I2 — Important: a pre-guard canonical snapshot can turn an alias observation into a constraint failure

**Location:** `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/PaymentOperationServiceImpl.java:335–338` (`observeReceipt`); corresponding stale-read pattern at 210–212 (`claimSend`), canonical selection at 506–510, mutation at 343–360. Schema invariant: `backend/shop-app/src/main/resources/db/migration/V540__checkout_payment_operation.sql:97` (`ck_payment_operation_alias`).

`observeReceipt` reads `known` before acquiring the order guard, then chooses the row to lock using the canonical pointer in that pre-guard object. Acquiring the guard does not refresh that pointer. `canonicalLocked(known)` can therefore lock the now-aliased request row rather than its canonical operation. `observeLocked` subsequently treats that row as the root and changes its phase.

**Trigger / deterministic interleaving:**

1. Canonical offline operation A already has a real SUCCESS payment.
2. Request B has committed PREPARED but has not yet been bound to A.
3. Query T1 reads B as PREPARED with no canonical pointer, then pauses before the order guard.
4. Confirmation T2 takes the guard, binds B as ALIASED to A's successful payment, and commits.
5. T1 takes the guard and resolves canonical identity from its stale pre-guard `known`. It locks B. The current locked B is ALIASED, but `observeLocked` sees its shared SUCCESS payment and attempts to save B as SUCCEEDED.

V540 correctly rejects this write: a non-ALIASED phase cannot retain a canonical operation different from self. This is not evidence that the check should be relaxed.

**Impact:** a legitimate concurrent original-key query/replay can fail with a database constraint error instead of returning the original receipt. The funds remain recorded, but the public recovery path fails and the alias invariant is not maintained by the service. The analogous `claimSend` ordering should be corrected in the same audit, even where a particular online alias sequence returns earlier.

**Fix:** after acquiring the order guard, reload the request's current operation/canonical relationship, then lock and observe the canonical root using the required deterministic lock order. Any pre-guard read should be used only to identify the immutable order guard, not to decide the mutable canonical relation. Preserve the alias row and schema check.

**Missing test:** use barriers around the pre-guard read to force PREPARED-read → successful alias commit → observation resume. Assert receipt recovery succeeds, B remains ALIASED and A alone is canonical SUCCEEDED. Exercise both query and any applicable initiate/replay path. The current alias tests are useful but do not establish this ordering.

**Classification:** implementation defect, not a plan-mandated defect.

### I3 — Important: original-key recovery can perform the first external online send after the order is cancelled or expired

**Location:** `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutPaymentServiceImpl.java:90` and `113–114`; initial-only eligibility check at 145–147; replay validation at 193 and placed-operation lookup at 331. Downstream first send: `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/service/impl/PaymentDispatchServiceImpl.java:75–106`. The ordinary owner eligibility reader is `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java:2629` (`paymentInstructionForCheckout`).

Current order status/deadline validation occurs only when the permanent P0 request mapping is absent. For an existing mapping, the service validates identity/payload against the original checkout operation, then unconditionally prepares and initiates the payment using the stored instruction. The checkout operation remaining CREATED establishes that the order was placed; it does not establish that the actual `ord_order` is still payable or unexpired.

**Trigger:** P0 commits, then the pay owner is unavailable before it accepts/prepares the operation. This is an explicitly supported recovery window and is represented in `CheckoutPaymentAuthenticationFlowTest#originalKeySurvivesFailureBeforePayOwnerAndReadNeverDispatches`. Before retry, the buyer cancels the actual order or its payment deadline expires. A still-authenticated buyer replays the original key and payload. The existing-mapping branch never calls the ordinary owner's current payment-eligibility reader. It can now create/open the payment, commit SENDING and make its first external prepay call for that already-terminal/expired order. A similar concern applies to an OPENED operation whose first send has not started.

**Impact:** the system itself starts a fresh online collection after it can already know the order is no longer payable. A later successful callback must then become a late-payment/manual-resolution case. Correct late-payment retention is necessary, but it is not a reason to initiate previously unsent work after cancellation. This also makes new-key and original-key eligibility inconsistent: the initial path rejects the terminal order while the replay path can first-send it.

This finding is specifically about a proven unsent first dispatch after the state change. It does not propose discarding already accepted money, closing UNKNOWN work, generating another number, or denying query/reconciliation of an existing external attempt. A cancellation racing after a valid send authorization can still legitimately require the existing late-payment flow.

**Fix:** preserve the permanent mapping and original-result query forever, but recheck current ordinary-order online eligibility before authorizing the first send of an operation that has not started sending. Keep the immutable original amount/route/scope and the lock-free RPC boundary. Already-started or uncertain operations should continue original-number query/recovery without reopening or replacement. Define the precise authorization point so cancellation/send races have explicit semantics.

**Missing test:** P0 committed/pay owner unavailable → actual order cancellation (and separately deadline expiry) → original-key replay. Assert no first gateway send, the original mapping remains readable and no replacement identity is allocated. Also cover an existing OPENED/not-yet-SENDING operation and confirm that previously accepted/UNKNOWN money still reconciles normally after cancellation.

**Classification:** an implementation eligibility gap against the ordinary-order/closed-order requirements. The accepted design explicitly distinguishes late offline evidence capture from online payment eligibility; it does not mandate first-sending an online request after cancellation. If the product deliberately wants such first sends, that requires an explicit policy decision because it creates avoidable real late-payment cases.

### M1 — Minor: the native runner can report success without validating or executing a selected native test

**Location:** `scripts/verify-checkout-payment.mjs:76–92`, especially the class filtering at 78 and Maven option `-Dsurefire.failIfNoSpecifiedTests=false` at 88.

**Trigger:** `CHECKOUT_PAYMENT_NATIVE_TESTS` contains a misspelled/unrecognised class or unsupported selector. The runner filters its known class list to an empty list. Maven is expressly allowed to succeed when the specified tests are absent. The subsequent fresh XML/nonzero execution/zero skip checks iterate the empty class list and therefore cannot reject this result. Wildcard or differently qualified selectors can also escape the intended per-suite report validation.

**Impact:** the advertised fail-closed native evidence command can be green without native execution for a mistaken selection. This does not invalidate the supplied runs with recognised classes: their explicit nonzero/zero-skip summaries and the root's sampling remain evidence. It is a latent verification-tool defect.

**Fix:** validate selectors against the supported native classes/method-selector syntax before provisioning or invoking Maven; reject an empty/unrecognised selection. Require fresh nonzero/zero-skip XML for every accepted class. Add a narrow selector-validation check for an unknown class and an empty match; no financial suite rerun is needed just to test this parser behavior.

**Classification:** implementation/tooling defect, not a plan-mandated defect.

## Specification assessment

| Requirement area | Assessment | Evidence and limits |
| --- | --- | --- |
| Real common funds owner; no beauty balance/payment ledger | Compliant in reviewed changes | Operations attach to real `stl_payment`; owner-built `PaymentOpen` and receipt matching are used. No second monetary balance was introduced. |
| Permanent P0 actor/action/key identity and conflict handling before pay RPC | Substantially compliant | V540 request uniqueness (`V540__checkout_payment_operation.sql:29`), immutable stored instruction and explicit replay checks in `CheckoutPaymentServiceImpl`. I3 is the missing current first-send eligibility check, not a request-key lifetime failure. |
| Actual global order identity and shared order guard | Compliant structure, concurrency defect remains | Global guard and operation keys reflect legacy order identity, rather than invented tenant isolation. Pay ledger and operation locking use the shared guard. I2 shows canonical identity must be refreshed under that guard. |
| Independent committed SENDING before lock-free gateway I/O; UNKNOWN retention | Compliant in reviewed send path | `PaymentOperationServiceImpl.claimSend` and `PaymentDispatchServiceImpl:89–106` separate transaction completion from gateway prepay. Ambiguous false/timeout/not-found paths retain original-number uncertainty; proved NOT_SENT is separately handled. I3 concerns whether an unsent operation is eligible to reach this otherwise sound send boundary. |
| Immutable receipt read outside owner lock; local proof validation under lock | Compliant at the changed payment/owner boundary | `PaymentLifecycleServiceImpl:69–92` checks the copied receipt against owner facts and preserves late/duplicate resolution. The focused provider inspection below found no newly introduced remote receipt call inside the order lock. Full deployment configuration is not proven here. |
| HOST actual current account/current store/new cash permission | Compliant in reviewed entrypoints | Actual session, typed account namespace, current store and permission SQL are checked. The new permission is not inherited from order view/RECEIVE, and default role expansion was not introduced. See authority inspection below. |
| Formal offline evidence, original receipt replay, already-received money retained | Substantially compliant | Real OFFLINE settlement, exact original amount, dedicated immutable transfer evidence and account attribution replace a managed shortcut. Original successful receipt replay and closed-order late-money handling are present. I2 affects a concurrent successful alias observation. |
| True paid source facts; RR/UNKNOWN/historical amount preserved | **Not compliant for a supported retry sequence** | I1 prevents paid projection after NOT_SENT followed by successful explicit replacement. Existing UNKNOWN/null-money behavior is appropriately conservative, but the rejected-row classification is wrong. |
| Managed paid state without legacy usable verification code or fake service completion | Compliant in reviewed owner changes | `OrderServiceImpl:3063–3069` preserves the managed restrictions; service execution/CAS is not bypassed and BM05 remains closed. |
| Private evidence retained through read/delete/reconcile paths | Compliant code intent; environment limit | Dedicated purpose/key/account matching, immutable metadata and retention guards cover the changed entrypoints. Actual production COS bucket policy cannot be inferred from code. |
| Append-only DDL and project structure/error conventions | Substantially compliant | Root verified frozen migrations unchanged and the exact V540 artifact. Services/Impls and own mappers are used; no Repository abstraction or new parallel funds model was found. Permission/locales/mock/generated documentation changes fit the authorized scope. Existing disclosed repository guard failures are not silently called green. |
| Real-owner native/JVM evidence, no skipped-tests laundering | Strong evidence with a runner gap | Actual owner/JVM helper and external-channel replacement inspected. Final log directly confirms 23 executed native tests, zero skips. M1 leaves malformed selector runs able to bypass report checks. |

No unsupported requirement to finish beauty binding, the full consumer wiring, whole-deployment migration replay or BM05 was added to this review. I did not identify an out-of-scope product feature that independently warrants removal. The narrow media purpose/retention changes, permission discovery updates, legacy offline fixture corrections and process-fault helper are connected to the accepted task requirements.

## Concrete strengths

1. The design is implemented across the actual owner boundary rather than simulated in a beauty-specific ledger. V540's real unique constraints, order guard and alias check express useful invariants. In particular, the alias check catches I2 rather than allowing silent canonical corruption.
2. Payment open/binding and formal offline settlement use short pay-owner transactions. The dispatch layer commits SENDING before the external channel call and distinguishes proved NOT_SENT from uncertain channel outcomes. The process-death tests exercise these material boundaries with distinct JVMs.
3. Authorization is more than a persisted buyer/account string. The current C path checks the actual token/session before dispatch and before parameter disclosure; read-only internal mapping paths do not expose pay parameters. Cash receipt permission uses the actual current merchant operator and current store, rather than a merchant entity number standing in for an operator.
4. The offline path preserves real money after order closure and uses the existing late/duplicate resolution machinery. It does not grant a boolean managed-order escape into the old trust-based offline shortcut. The old shortcut remains closed for managed orders.
5. Bank-transfer evidence is tied to a dedicated private upload purpose and original operator/scope, with real object existence and immutable metadata checks. Retention covers deletion/reconciliation behavior as well as initial API validation; preview credential suppression avoids returning an old signed URL as proof of current authorization.
6. The owner projection preserves the service-execution boundary: paid managed orders do not acquire a usable legacy verification code or fake fulfillment. UNKNOWN keeps money unknown instead of inventing zero or promoting a historical pair into current serviceability.
7. The report distinguishes actual native execution, deliberate non-native skips, baseline/fixture/environment failures and ablation evidence. Its financial evidence is materially stronger than a mocked repository test or a suite whose relevant tests all skipped.

## Focused outside-diff checks and outcomes

### 1. Shared guard ordering against callback/refund callers

Read `backend/shop-core/src/main/java/ai/neargo/shop/trade/api/callback/ChannelPayCallbackController.java:155–171` and `.../PayCallbackController.java:91–117`: the existing callbacks settle the actual ledger and then project owner payment; no controller-wide transaction wrapping those operations was found. Their order identity comes from the settlement/ledger result rather than an untrusted callback order substitution.

Read `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/RefundCapacityLedger.java:25–40`, `53–85`, `110–137`: it locks the original PAY and refund rows without later acquiring the new order guard or payment-operation rows. This inspection did not reveal a reverse guard-after-ledger edge against the new guard → operation → ledger sequence. A narrow search of direct ledger open/close callers found the known legacy `SettlePortImpl` open path and the new operation-owned open path, rather than a newly introduced bypass. This is a checked caller graph, not a claim to have exhaustively proven all future adapter configurations.

### 2. Actual HOST token/account/store authority

Read unchanged `backend/shop-core/src/main/java/ai/neargo/shop/trade/policy/CheckoutAccessPolicy.java:45–73` and `80–110` for current CONSUMER/USR/MAIN session identity and current merchant actor/store resolution; `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/TokenStore.java:23–35` for the real stored-session contract; and `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizContextFilter.java:34–48`, `74–80` for `X-Store-No` handling. An unauthorised store header does not simply become authority for that foreign store.

Read `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/mapper/MerchantMappers.java:63–90`: SQL selects the actual active `mch_account_no`, active entity/store/account, uses typed account/user identity branches and exact scope, applies role/custom permission semantics, and permits the caller to reject ambiguous multirow matches. The code does not substitute an MCH subject/entity number for the actual receipt operator. I did not prove every possible production identity resolver configuration or namespace collision; the checked HOST SQL/session path supports the intended authorization.

### 3. Receipt reader, narrow projection bypass and locked provider I/O graph

The focused lookup of unchanged `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/PaymentLedgerServiceImpl.java:584–591` established that `paymentByOutTradeNo` returns a DTO for any existing ledger payment, including CLOSED. That concrete contract produces I1.

Continued the cut `OrderServiceImpl.markPaid` method at `2921–2942`, `3034–3119`, `3144–3147` and `PaymentLifecycleServiceImpl:69–92`: receipt/source proof is obtained before the order transaction, current locked owner facts are compared against the immutable receipt, managed behavior is preserved, and notification work is deferred/outboxed. The trusted projection bypass is narrow to dispatch of a real SUCCESS receipt; it does not remove public entrypoint authentication.

For the named provider-I/O doubt, inspected these current method heads/bodies:

- `backend/shop-core/src/main/java/ai/neargo/shop/product/port/StockPortImpl.java:161–196`.
- `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/port/DualWriteStockPort.java:167–176,244–263` and `InventoryStockPort.java:114–125` in the same directory.
- `backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/service/impl/ReservationServiceImpl.java:330–339`.
- `backend/shop-core/src/main/java/ai/neargo/shop/user/port/PersonPortImpl.java:35` and `backend/shop-core/src/main/java/ai/neargo/shop/user/service/impl/PersonServiceImpl.java:95–102`.
- `backend/shop-core/src/main/java/ai/neargo/shop/member/port/MemberEventPortImpl.java:25` and `backend/shop-core/src/main/java/ai/neargo/shop/member/service/impl/MemberServiceImpl.java:116–119,731–736`.
- `backend/shop-core/src/main/java/ai/neargo/shop/marketing/attribution/impl/AttributionServiceImpl.java:106–112`.
- `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/port/MerchantPortImpl.java:1741–1762`.
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/EntitlementOrderUseServiceImpl.java:202–217`.
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/entitlement/impl/CardSaleSnapshotServiceImpl.java:64–73`.
- `backend/shop-store-mybatis/src/main/java/ai/neargo/shop/event/OutboxEventBus.java:67–110`.

The inspected heads use local service/mapper/outbox providers, not a new remote SettlePort call under the lock. This was a bounded current-provider inspection; it does not prove every nested implementation or alternate deployment profile is free of I/O. In particular, the existing inventory v1/v2 ownership boundary remains a separate deployment concern rather than newly invented cross-owner atomicity.

### 4. Private media entrypoints and owner retention

Read unchanged `backend/shop-channel/src/main/java/ai/neargo/shop/channel/media/api/MediaReadController.java:55–92`: signed access is required, the filesystem path is constrained and the response is private. Read `backend/shop-app/src/main/java/ai/neargo/shop/config/UploadResourceConfig.java:81–107`: public resource routing admits the old two-segment/goods forms, not the dedicated five-segment receipt evidence key. Read `backend/shop-channel/src/main/java/ai/neargo/shop/channel/media/CosMediaStore.java:92–108,128–130`: public treatment is goods-specific, the new purpose does not request PublicRead, and existence checks go to the actual backing object store.

The narrow deletion-call inspection identified `MediaPurgeService:141` and the generic `MediaReconcileJob:126` deletion path; the new dedicated retention branch prevents receipt-owned evidence from falling through that generic cleanup. No unreviewed general-purpose upload route was found that could mint the new purpose as accepted proof. This does not prove a production COS bucket is configured privately.

### 5. Reachable legacy offline path and historical fixture

Read unchanged `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/MerchantOrderServiceImpl.java:324–370`: the old offline confirmation path has the managed-order rejection before and within the locked state check and permits its actual WAIT_OFFLINE_PAY legacy state. It does not gain a new managed bypass. Read `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/machine/payment/PaymentMachine.java:25–50`: online WAIT_PAY and offline WAIT_OFFLINE_PAY are distinct reachable branches, with terminal paid/cancel/close states.

The changed `OrderPaymentRecoveryWiringFlowTest` historical fixture explicitly creates the no-operation historical ledger attempt and historical close before current retry. It tests that migration-history condition rather than presenting a new UNKNOWN operation as legally closeable. Its duplicate/late actual funds assertions remain relevant. The native schema fixture is not a full chronological deployment migration replay; that limitation is disclosed below.

### 6. Native runner and child JVM provenance

Read the full native runner plus `backend/shop-app/src/test/java/ai/neargo/shop/scenario/PaymentOperationProcessRestartNativeTest.java`, `support/PaymentOperationProcessMain.java` and `support/PaymentGatewayFaultServer.java` (the latter two under the same scenario directory) from the package. The child JVM runs the real `ShopApplication` owner path and current-C HTTP/session entrypoint. The helper replaces the external payment gateway with a loopback channel adapter; it does not replace the trade/funds owner implementation. The independently surviving parent fault server records actual external send/query requests. Tests wait for a real committed SENDING marker and kill an owned child process, then recover using another JVM against the same DB/original number.

Read `backend/shop-app/src/test/java/ai/neargo/shop/scenario/CheckoutFlowFixture.java:43–51,70–125` for the real app/native JDBC selection and real merchant/user/goods fixture. The runner supplies the native configuration and explicitly enables the native classes. Outside that runner, their deliberate skip behavior must not be counted as financial execution. M1 concerns unsupported runner selection, not substitution of owner implementations in the inspected known-class runs.

Some provider file basenames above are unique within the corresponding reviewed owner module; their line locations are included to identify the exact bounded lookups. No broad repository crawl or general application audit was performed.

## Verification evidence and warnings

Directly inspected the tail of `/tmp/task57-alias-final-native-green.log`. It reports BUILD SUCCESS and explicit native execution verification: `PaymentOperationNativeMysqlTest` 13 tests/0 skips; `PaymentOperationProcessRestartNativeTest` 2/0; `CheckoutPaymentAuthenticationFlowTest` 8/0. Thus this final recorded run executed 23 tests with zero skips. I did not rerun it.

The final report additionally records the 38-test source/offline/managed/auth boundary run, focused cash/media runs, and a finite ablation with 4 baseline green tests, 3 genuine failures after disabling the behavior, then 4 restored green tests. The root independently sampled supplied evidence. These are meaningful positive evidence, but none demonstrates the I1–I3 sequences above. Process-recovery logs support the recorded BEFORE_SEND send-count zero and AFTER_ACCEPT send-count one behaviors; they do not establish cancellation-before-first-send eligibility.

The final report does not claim that all repository gates are green. Its ordinary aggregate run includes disclosed pre-existing architecture/permission/fixture failures and a standalone food test skip; the narrow cash permission guard repair has a separately reported green gate with that unrelated skip still visible. The later default run with 41 skipped native tests is isolation evidence only, not a funds proof. I have not turned any of these skips or warning-heavy baseline runs into acceptance evidence.

The structural native database fixture reconstructs the pre-539 shape and then applies the target migration boundary; it is not a complete chronological replay of every historical production migration. The report's Task60/full-deployment debt remains real. I did not require Task57 to fix unrelated historical architecture FQN/store-domain assertions, old endpoint permission omissions, old fixture columns/tables or booking labels unless they affected the new task path. None of those disclosed debts explains away I1–I3.

## CannotVerify / explicitly bounded conclusions

1. **Real external channel behavior and production deployment:** the native/process tests replace the external channel. They establish owner and dispatch behavior against the specified channel contract, not production bank/provider behavior, deployed credentials or availability.
2. **Production private storage policy:** code avoids PublicRead and public routing for the dedicated evidence purpose. Actual COS bucket/CDN policy and production retention configuration were not available to verify. A publicly configured bucket would defeat the intended privacy despite the application code.
3. **Whole historical migration/application startup:** no full production-history Flyway replay, mixed-version deployment or complete Task60 startup validation was performed. Frozen migrations and the exact V540 artifact were root-verified; that is a narrower statement.
4. **Every provider/profile implementation under the order lock:** the bounded current-provider graph inspection found no newly introduced remote receipt call or evident RPC at the sampled method heads. It is not a proof covering alternate profiles, future remote SettlePort implementations, every nested member/quota implementation or inventory v1 transaction isolation. The implementation should retain the explicit no-RPC-under-lock boundary as adapters evolve.
5. **Cross-task completion:** beauty binding, full consumer wiring, deployed permission rollout and BM05 service execution were not accepted by this review. Task57 must expose correct facts for those later consumers; I1 is therefore a present defect even though downstream binding is not part of this acceptance.
6. **Runtime reproduction of new findings:** I1–I3 were established by the actual reader contract and explicit code interleavings. No new suites or races were run, per the review restrictions. The requested focused regression cases are absent from the evidence reviewed; adding them is part of resolving these findings.

## Acceptance recommendation

Do not accept Task57 yet. Fix I1–I3 and close M1 while touching the runner. Validate the new focused sequences against the real owner/native setup, preserve the existing original-number/unknown/late-payment and managed-execution invariants, and submit the resulting immutable diff plus fresh targeted results for review. No redesign of the entire funds owner or completion of Tasks58–60 is required to address these findings.
