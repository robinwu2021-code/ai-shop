# Task57 scoped R2 re-review

Scoped SPEC: **PASS**. QUALITY: **APPROVED**.

I3: **ADDRESSED**. New R2 findings: **0 Critical / 0 Important / 0 Minor**.

## Scope and snapshot

Read-only review of the remaining I3 and regressions introduced by its R2 correction, not a new Task57 review. I1/I2 retain their independent ADDRESSED dispositions. Read the brief including the R1 authorization ruling, `/tmp/task57-r2-findings.md`, and only the appended R2 report section (line431 onward).

Reviewed `/tmp/task57-r2-root-review-package.diff` once as the code view: 26478 bytes, SHA256 `04556c15e17effc91ae796dde5c736a16e3a1607554efc60adb9b1d08da55389`, independently recomputed. FIX_BASETREE `d4201a0b576d40985a5908e45a7a31ac18ff8bfa`; UPDATED tree `d25c4e58cb7b888bbb900b320c82d4a834183d47`; unchanged HEAD `b34e31ba4f10bc75088ccd44d1eef4ef0c5a4ac1`. The root's independently verified 98-file snapshot and exact three-file delta are the review boundary; this reviewer did not repeat that inventory or use git. The delta is 13 product lines in claimSend, two focused regression methods plus their helper, and the appended report. No repository/index/HEAD changes or tests were run by this reviewer.

All source paths below are relative to `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`.

## I3 trigger and correction

`backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/PaymentOperationServiceImpl.java:222–234` adds the refusal at the correct financial boundary. After observing the canonical operation's own current PAY row, only an OPENED, unsent, ONLINE root with its own PENDING payment reaches the new same-order/direction PAY/status SUCCESS locking read. The query uses `LIMIT 1 FOR UPDATE`, executes in the existing unscoped pay transaction, and throws the existing CONFLICT before phase SENDING, send_started_at, save, or DispatchTicket construction. It cannot turn A's real success into permission to send B.

For the exact residual sequence A NOT_SENT/CLOSED → explicit B OPENED/PENDING and unsent → contrary real A SUCCESS and ordinary PAID projection → B replay, B's own row remains PENDING and the new current read finds A. The transaction refuses B's first claim. No ticket escapes to prepay. For PREPARED B with newer pending C, open may retain B as an alias of C; the canonical C claim finds A and refuses in the same way. This matches the expressly approved refusal alternative: no fabricated B SUCCESS or rebinding to A, no closure/replacement/purge, and conservative source UNKNOWN remains allowed.

The new tests at `backend/shop-app/src/test/java/ai/neargo/shop/scenario/CheckoutPaymentAuthenticationFlowTest.java:68` and `:73` construct those two actual owner sequences. Their shared helper settles real A money and obtains the ordinary PAID projection before replay, rather than manufacturing only a PAID flag. At `:136` the public replay must throw exact ErrorCode.CONFLICT; `:142` independently denies direct financial claim; `:144` asserts no gateway prepay. Subsequent assertions preserve the original B identity, alias/root relationship, OPENED view without receipt/parameters, original database phase (OPENED or ALIASED), no other send timestamp, original P0/P1 counts, exactly the original two ledger attempts, B/C PENDING, and A SUCCESS/303/original receipt/canonical/trade projection. The first RED failed at the missing exception before these retention assertions; those later assertions are supported by GREEN, not claimed as separate RED failures.

## Narrow unchanged context inspected for R2 risks

- Guard/settlement race and stale snapshot risk: `PaymentOperationServiceImpl.java:63–75`, `:356–403`, `:416–445`, and `:613–632` establish the pay transaction, global order guard, distinct sorted request/root operation locks, current own-PAY read, and unscoped execution. `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/mapper/PaymentOrderGuardMapper.java:14–22` uses the single order_no guard. `PaymentLedgerServiceImpl.java:56–68`, `:465–503`, and `:532–559` acquire that same guard for PAY open/close/settle/closeUnsent status mutation. Settlement cannot commit A SUCCESS across B's guarded success-check/first-claim transaction. Existing `recordPayer` at `:409–425` only patches payer metadata and does not alter this success invariant.
- Own SUCCESS / UNKNOWN recovery regression: `PaymentOperationServiceImpl.java:416–445` observes exact own success before claim's OPENED/PENDING preconditions. Already-started/non-OPENED operations return without entering the new check. Existing focused positives at `CheckoutPaymentAuthenticationFlowTest.java:321–414` demonstrate started UNKNOWN original-number recovery and sole-winning-SUCCESS alias recovery without another prepay. R2 does not change either path or the R1 authorization rule.
- Lock-order/refund interference and index risk: `PaymentLedgerServiceImpl.java:207–222` locks original PAY before refund rows; the added query only takes PAY locks after the already-held PAY lock, with no later operation/refund lock, RPC, or second connection. Global guard serialization prevents two same-order first claims from creating opposite PAY lock order. `backend/shop-app/src/main/resources/db/migration/V1__baseline.sql:1007` supplies `(order_no,direction)` index. The query is a current locking read, not a snapshot count; no schema change is required.
- Prepay escaping a refusal: `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/service/impl/PaymentDispatchServiceImpl.java:75–115` obtains claimSend before gateway.prepay and performs gateway I/O outside the financial transaction. The new thrown CONFLICT cannot reach prepay. Canonical selection context at `PaymentOperationServiceImpl.java:551–591` was examined only to verify the pending-C variant and existing sorted open locks.
- Test-fixture realism: `CheckoutPaymentAuthenticationFlowTest.java:416–441` isolates prepare/initiate availability to retain accepted identities and then restores the real owner. The new helper opens the actual pending original and uses real ledger settlement and normal projection. Only the third-party TEST gateway is stubbed for the initial definitive NOT_SENT.

These focused unchanged lookups establish the correction's safety; they do not reopen unchanged whole-task implementation. No new Critical/Important/Minor breakage was found in the R2 delta.

## Evidence reviewed; no reruns

Exact authentic RED command:

```sh
CHECKOUT_PAYMENT_TESTS='CheckoutPaymentAuthenticationFlowTest#lateSuccessfulOriginalBlocksOpenedOtherAttemptBeforeFirstSend+lateSuccessfulOriginalBlocksPreparedAliasOfNewerPendingAttempt' node scripts/verify-checkout-payment.mjs > /tmp/task57-r2-first-red.log 2>&1
```

`/tmp/task57-r2-first-red.log:631–655`: 2 tests, 2 assertion failures, 0 errors, 0 skips; both fail with “Expecting code to raise a throwable” at the then-current helper line110. This is behavioral RED, not setup failure, and does not independently establish masked later assertions.

Exact final native command:

```sh
CHECKOUT_PAYMENT_TESTS='CheckoutPaymentAuthenticationFlowTest#lateSuccessfulOriginalBlocksOpenedOtherAttemptBeforeFirstSend+lateSuccessfulOriginalBlocksPreparedAliasOfNewerPendingAttempt+paidOrderRejectsNewKeyBeforeMappingWhilePreviouslyAcceptedAliasRecoversActualReceipt+acceptedUnknownStillRecoversOriginalActualMoneyAfterCancellation+revokedDuringAcceptedDispatchCannotReceiveParametersButOriginalFinancialFactSurvives+definitivelyUnsentOriginalDoesNotHideNewKeySuccessOrContraryLateMoney,PaymentOperationNativeMysqlTest#aliasWinningTheFirstSendReturnsItsOwnIdentityAndCanonicalOriginalNumber+twoPreparedRequestsConcurrentlyShareOneCanonicalAndOneSendClaim+sendingAndUnknownNotFoundCannotBeClosedByLegacyCloseOrReconciliation+provenNotSentKeepsOriginalRejectedMappingAndPermitsOnlyNewOperationToAllocate+successfulReceiptPreventsBothOldAndNewOpenFromCreatingAnotherChargeableAttempt+preparedReadBeforeAliasCommitCannotClaimOrMutateSuccessfulAlias,OfflineReceiptFlowTest#sourceExposesOneCanonicalOriginalReceiptAndCallsFinancialOwnerOnlyOutsideSnapshot+firstAlreadyReceivedCashAfterActualCancellationAndPolicyChangePreservesReceiptAndLateResolution' node scripts/verify-checkout-payment.mjs > /tmp/task57-r2-scoped-green.log 2>&1
```

`/tmp/task57-r2-scoped-green.log`: 14 run / 0 failures / 0 errors / 0 skips, BUILD SUCCESS. Core6 / Offline2 / Auth6, each explicitly verified by the native runner. Independently parsed the corresponding existing fresh XML totals and exact selected testcase names; they agree. This covers both new residual sequences, sole SUCCESS alias, actual UNKNOWN recovery, caller revocation, I1 source/contrary-money interaction, canonical send arbitration, current-read alias race, open-after-success prevention, and relevant offline late-money/source behavior.

Exact ordinary compatibility command:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test '-Dtest=PaymentLedgerCloseRaceFlowTest,PaymentInitiationCashRaceFlowTest' -Dsurefire.failIfNoSpecifiedTests=false > /tmp/task57-r2-ordinary-compat.log 2>&1
```

`/tmp/task57-r2-ordinary-compat.log`: 4 run / 0 failures / 0 errors / 0 skips, BUILD SUCCESS. Existing XML independently agrees: 2 close-race plus 2 initiation/cash-race cases. No repeated native/process/broad suite or additional narrow test was necessary after code and supplied evidence inspection.

## Out-of-scope observations and limits

Full-review M1 selector validation and the recorded baseline/Task60 debt remain unchanged, separately nonblocking here. R2 approval makes no claim of full-backend green, deployment readiness, or revalidation of historical initial/R1 suites.

The deliberately retained competing pending B/C may remain blocked with CONFLICT and yield conservative source UNKNOWN. Actual late success racing after an already committed first claim remains within existing late/duplicate-resolution behavior. Both are explicit approved boundaries, not new findings. No additional out-of-scope issue was discovered during the named narrow context checks.

Final disposition: I3 ADDRESSED; Scoped SPEC PASS; QUALITY APPROVED; new R2 findings 0 Critical / 0 Important / 0 Minor.
