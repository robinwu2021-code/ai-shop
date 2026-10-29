# Task64 scoped R2 re-review

## Finding verdict

**R1-N1 — Valid full points refunds are rejected after their normal terminal transition: ADDRESSED.**

`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/impl/PointsRefundResourceServiceImpl.java:276` now derives current physical shipping evidence solely from item shipped quantities. At `:290`, an original unshipped command requires no positive shipped quantity; the guard no longer requires the current child to remain WAIT_FULFILL. A legitimate transition to REFUNDED therefore does not invalidate the prepared command. Original command.unshipped=false retains its original routing; the fix does not infer that every REFUNDED order was unshipped. Manual-kind, source identity/tenant, hash, authority, lifecycle/full checks and transaction boundaries are unchanged by this minimal production delta. Original commands are not rewritten.

`backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualServiceFinancialIsolationIT.java:724` adds the specific missing regression. It obtains real legacy checkout/consent/place and allocated original stock; invokes genuine pointsResources.prepare at `:796`; applies the actual RefundClosurePolicy to persisted counters; invokes the unwrapped production settleRefundTerminal through the approved hook at `:844`; and asserts both child and after-sale are actually REFUNDED before resource recovery at `:874`. Recovery must produce actual stock release, original balance restoration, STOCK success/proof and stockRestored=1. Replay at `:901` causes no second stock dispatch and preserves exact command bytes. Six actual terminal-owner types/origins are asserted. Independent raw primary MySQL NOWAIT probes check child, STOCK obligation and points execution locks at actual stock IO; the existing cash probe is omitted only when its explicit execution argument is null (`:940`).

The supplied RED fails at this test's own recovery assertion with CHECKOUT_PAYMENT_CONFLICT after real terminal transitions succeeded. The supplied GREEN passes both this regression and the existing imported-manual STOCK rejection case. The implementation and evidence address the specific R1-N1 defect.

## New breakage in the fix diff

**None found. Counts: 0 Critical, 0 Important, 0 Minor newly introduced by R2.**

The production change is limited to the physical-shipping predicate. The test retains a real production terminal transition rather than a SQL terminal-state flip. The runner adds only named r2-red/r2-green selections, exact method counts and the intended assertion-failure requirement. No terminal business owner, migration, API, dependency or existing financial orchestration is changed.

## Checks and evidence bounds

- Read all four files in the immutable R2 delta once in bounded chunks against the supplied R1 candidate snapshot, not HEAD. Verified package 32,521 bytes and SHA256 `3c6356306433dc010c8371b5d2b92904278e4ec1512a3c59842fdebfbfb11f1c`.
- Read the R2 brief and its approved test-hook/seed boundaries, plus final appended report. Verified report 35,774 bytes and SHA256 `e2ad4813611389593f874e24e3d6626306b71c9a0fb275cb092d9d3ed21ac3fa`.
- Independently checked all 32 R2 artifact byte/hash pins: zero mismatches. Inventory is 8,409 bytes, SHA256 `e018e5a4d041010421457ddc24bb7dd6f90b06360e8d908a2375e186fa7f6f44`.
- Read actual RED XML in `task-64-evidence/r2-red-8295386e-80a0-49c6-af77-a27562ba3576`: 1 test, 1 own assertion failure, 0 errors/skips. Its stack identifies the intended real recovery conflict, not setup failure.
- Read actual GREEN XML in `task-64-evidence/r2-green-4a0691b6-c2ef-4187-95c0-76770621badb`: exactly the new terminal regression and existing imported manual-stock rejection; 2 tests, 0 failures/errors/skips. Read six canonical owner origins, runner status 0 and owned cleanup records. Root's recorded post-GREEN comparison covers 94 source/fixture and 211 compiled-class pins with zero mismatches; this review did not recreate native resources or recompile.
- Read preserved final compile output: BUILD SUCCESS, 24.918 seconds. Earlier staged compiles remain separately recorded. Historical original/R1 tests and the settlement-filter mutation retain their original source bounds; they were not rerun or relabeled final by this review.
- **CannotVerify beyond the accepted scope:** paid state, refund counters, COMMITTED points/cash proof and terminal COUPON evidence are explicit fixture seeds. The hook calls the real terminal method and actual state machines within ordered child→after-sale transaction boundaries, but does not run the full Pay/points financial orchestration. External money behavior, full historical migration/deployment topology and future display/billing integration are not established by this test. These are proof limits, not newly found defects in the R2 predicate.
- No outside-delta source inspection, git recrawl, build, test/native execution, subagent dispatch, or product/index/HEAD mutation was performed. Only this review report was written.

## Out-of-scope observations

None newly discovered. Original I1/I2/I3 remain independently ADDRESSED by R1; untouched paths were not reopened. Initial four Minors remain tracked at their existing deadlines and are not claimed fixed here. M1 backend manual queries do not constitute a UI/billing display claim; root's mandatory M2 display/bill distinction before cash-core exposure remains a separate follow-up. Existing warning/configuration and fixture limits remain explicitly recorded in the implementer report.

## Verdict

**Fix round: all findings addressed, no new Critical/Important breakage. R1-N1 ADDRESSED.**

**Updated spec compliance: complies within the reviewed M1 scope, with the previously recorded nonblocking follow-ups retained. Updated task quality: Approved with nits.** No open Critical or Important review findings remain. The initial four Minors remain root-tracked; this scoped verdict does not approve deployment, broader cash-core completion or M2–M7 work.
