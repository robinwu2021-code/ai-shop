# Task57 R1 scoped independent re-review

Scoped SPEC: **FAIL**
QUALITY: **NEEDS_FIXES**

I1 **ADDRESSED**; I2 **ADDRESSED**; I3 **NOT ADDRESSED** (cancel/expiry fixes work, but the newly introduced PAID exception does not enforce receipt-only recovery for every already accepted attempt).

Open counts: **0 Critical / 1 Important / 0 Minor**. The one Important is the residual I3 issue below, counted once; no separate additional finding is double-counted. I1 and I2 are closed. No unrelated baseline item blocks this review.

## Scope and evidence

Reviewed only the nine-file R1 package `/tmp/task57-r1-root-review-package.diff`, FIX_BASE TREE `20a4801e8f7189dc1453c4d665892c0a78209c70` to updated staged TREE `d4201a0b576d40985a5908e45a7a31ac18ff8bfa`, against the exact open I1–I3 findings and Task57 brief including its R1 authorization-point ruling. Read the appended R1 report and all changed product/test hunks. Used targeted original-package context for the canonical view, transaction boundary, original dispatch, immutable owner facts, receipt reader and late settlement needed to judge these fixes.

This is not a new 98-file review. The root supplied verified package/hash/index integrity and independently sampled the recorded RED/GREEN logs. I inspected the test implementation against the claims, without rerunning native/process/broad suites. Final recorded selection is native 31/0/0/0 (Core10/Auth16/Offline3/Source2), ordinary compatibility14/0/0/0. These are finite selections, not a whole-backend pass or a rerun of historical process-crash evidence. Earlier I2 spy/reflection/setup failures are not treated as business RED; the real forced-RR SQL3819 failures are the relevant RED evidence.

No git commands, repository/index/HEAD changes, helper agents or source fixes were performed. This report is the only output file written.

## I1 — ADDRESSED

**Product location:** `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java:1090` (through the new terminal-receipt predicate at1104).

The source reader retains its operation/request-hash and stored scope checks, then skips REJECTED only with no receipt, or with CLOSED, absent receiptNo, present canonical/outTradeNo and matching payment/order/buyer identity. It does not skip a real SUCCESS, nor remove the original permanent mapping. Consequently the original NOT_SENT/CLOSED A no longer short-circuits source enumeration before successful explicit-new-key B.

**Trigger proof:** `backend/shop-app/src/test/java/ai/neargo/shop/scenario/CheckoutPaymentAuthenticationFlowTest.java:304`, `definitivelyUnsentOriginalDoesNotHideNewKeySuccessOrContraryLateMoney`, exercises real ledger NOT_SENT/CLOSED A, explicit new B, real B SUCCESS, B canonical operation/outTradeNo/receipt, historical303, PAID order and both permanent request rows. It then records actual contrary late SUCCESS on A and asserts UNKNOWN/BLOCKED, preserved B order trade, DUPLICATE_PAYMENT and two real SUCCESS rows. This distinguishes terminal rejection from contradictory money. The root-confirmed original RED receivedMinor=null and current GREEN correspond to this change.

## I2 — ADDRESSED

**Product locations:** `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/PaymentOperationServiceImpl.java:208` (claimSend), `:330` (observeReceipt), `:342` (canonicalTransaction), `:403` (current ledger lock), `:560` (locked request returned by lockRequestAndRoot).

The pre-guard object is now only a candidate for determining which locks to attempt. It is not trusted as the current canonical relationship: after the global order guard and unique lexically sorted operation locks, the current locked request relationship is compared with that candidate. A changed pointer throws the private CanonicalChanged exception before ledger access. The owned TransactionTemplate rolls back that attempt and releases its locks; the catch lies outside transaction execution. At most one new transaction retries with the current relationship. No arbitrary SQL/deadlock failure is retried. Caller transactions are rejected before the attempt loop, so a joined outer transaction cannot retain locks or its RR snapshot across the retry.

The canonical action observes the ledger through FOR UPDATE after operation locking. claimSend reuses that current ledger object. observeReceipt returns find only after the owned transaction completes; the subsequent view does not reuse that transaction's pre-guard RR snapshot. The open change also uses the row returned by the actual request lock rather than required() from the old snapshot. No DDL, broad lock scan or alternate financial ledger was introduced by R1.

**Trigger proof:** `backend/shop-app/src/test/java/ai/neargo/shop/scenario/PaymentOperationNativeMysqlTest.java:99`, `stalePreparedAlias`, changes the actual owner's TransactionTemplate isolation to RR and restores it in finally. The SQL interceptor pauses the observer after the real request find, allows the real offline successful alias commit, then resumes observation/claim. The assertions require two observer guard acquisitions and precisely the initial request lock followed by sorted distinct request/root locks. Observation returns the real root receipt; claim returns empty; B remains ALIASED and A SUCCEEDED. The tests exercise query/initiate recovery afterward. `:300` verifies caller-TX refusal. The root-confirmed two SQL3819 business RED errors and focused GREEN provide relevant native evidence.

## I3 — NOT ADDRESSED: PAID proof can authorize a different unsent attempt

**Severity:** Important.

**Changed locations:** `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java:2679` (PAID branch, especially readConfirmed at2689 and successful return at2703); `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutPaymentServiceImpl.java:115–132` (PREPARED/OPENED check followed by unconditional initiate).

The new PAID branch proves that the order has an exact real SUCCESS receipt, but it does not identify or constrain the replay's payment operation to that successful payment. Its void success return authorizes the ordinary initiate path even when that operation already points to a different OPENED/PENDING attempt. The comment that PAID recovery will “never initiate another collection” is therefore not enforced.

**Concrete trigger:**

1. Online A receives the locally proved NOT_SENT result and is retained as REJECTED with a CLOSED ledger row.
2. While the order is WAIT_PAY, the buyer explicitly accepts new key B. B reaches OPENED with a new PENDING ledger row, then execution stops before claimSend. This is the same supported OPENED-before-first-send recovery window used by the new cancellation test.
3. A contrary real late SUCCESS arrives for A and is projected to the still-WAIT_PAY order, making it PAID with A's exact channel/trade/amount. Preserving this late SUCCESS is explicitly required and is already exercised by the I1 test. Settlement updates A, not B; B remains OPENED/PENDING with no send_started_at.
4. The buyer replays B after the PAID projection. prepare exposes B's OPENED phase. requireCheckoutPaymentSendAllowed reads and validates the genuine A receipt, then returns successfully.
5. CheckoutPaymentServiceImpl calls payments.initiate(B). PaymentOperationServiceImpl.openLocked immediately returns an already-OPENED B. claimSend observes B's own PENDING ledger row, sets B SENDING and returns its ticket. PaymentDispatchServiceImpl makes B's first external prepay call even though the order was already paid before this replay's authorization point.

This is not cancellation after a valid owner-read authorization, nor a request to discard uncertain or received funds. It is a fresh authorization for an already-paid order which can send a different unsent payment. The successful receipt exception must authorize binding/recovery of that actual successful payment only.

**Specific supporting context inspected:** unchanged `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/PaymentLedgerServiceImpl.java:497` settle preserves actual SUCCESS on a CLOSED original row and does not close a separate newer pending row; original-package `PaymentOperationServiceImpl.openLocked` (current line145 onward) returns non-PREPARED rows unchanged, and current claimSend at208 validates only the canonical operation's own ledger PENDING status; unchanged `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/service/impl/PaymentDispatchServiceImpl.java:75–106` proceeds from that OPENED view and ticket to gateway.prepay. These lookups were limited to proving this concrete risk introduced by the PAID authorization exception.

A PREPARED replay is not inherently immune either if another newer OPENED/PENDING attempt exists: currentPayment selects the newest SUCCESS/INIT/PENDING row by id, so a PAID order's successful older receipt does not by itself guarantee selection of the successful canonical root. The OPENED sequence above suffices to establish the defect without relying on this second variant.

**What is correctly fixed:** PREPARED/OPENED current eligibility checks reject actual cancellation, swept expiry and unswept deadline expiry; original frozen economics and route are retained; no caller transaction spans the owner read/pay call; started/UNKNOWN work keeps original-number recovery; PAID new keys are rejected before P0 acceptance; a PAID flag without real receipt fails closed. The owner-read point correctly defines subsequent cancellation as the existing late race. These positive fixes do not close the different-attempt PAID hole.

**Missing regression:** combine the real existing primitives: A NOT_SENT/CLOSED; explicit B accepted and OPENED before send; actual late SUCCESS A and normal PAID projection; replay B. Assert zero B prepay/claim, no replacement identity or removal of P0/P1/A's money, and that any successful PAID recovery is bound to A's exact successful canonical receipt. Also cover accepted PREPARED B with a separate newer pending attempt if the chosen correction permits PAID binding through open. The existing `CheckoutPaymentAuthenticationFlowTest.java:212` PAID-alias test has only the winning SUCCESS available when the waiting PREPARED request resumes; it does not cover an already-OPENED different payment or a competing newer pending row. No test was fabricated or rerun for this report; this finding follows directly from the inspected control flow and real late-settlement contract.

**Required correction:** distinguish receipt-only PAID recovery from first-send authorization. Constrain recovery, under the financial guard, to the proven successful payment/canonical root; an already bound unsent different attempt must not pass into a send path. Preserve all existing request identities and actual money, and do not turn this into a blanket close/replacement rule for uncertain attempts.

This is an implementation defect against the brief's explicit “previously accepted P0 may bind actual success canonical only” requirement, not a plan-mandated defect or an exception justified by the authorization-point ruling.

## Other new R1 breakage

No separate Critical, Important or Minor finding was established in the remaining changed hunks. The PAID issue above is the one scoped R1 blocker and is counted once under I3.

## Out-of-scope observations

Original full-review M1 runner-selector validation remains ledgered for Task60 and was not reopened as an Important R1 issue. Existing architecture, permissions/i18n/generated baseline debt and unrelated deployment/process verification were not rereviewed or claimed resolved. No out-of-scope observation changes the verdict.
