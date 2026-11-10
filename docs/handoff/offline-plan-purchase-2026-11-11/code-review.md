# Final offline plan review

Read-only review of `/tmp/offline-plan-final-review.diff` and sources in `/tmp/offline-plan-closure-host` and `/tmp/offline-plan-closure-app`, against `docs/superpowers/plans/2026-11-11-offline-plan-closure.md`. The last pass was deliberately limited to verifying the cancellation finding and OPS refusal visibility fixes. No tests rerun, no commits, no source edits and no subagents. Runtime test results remain the coordinator's separately recorded evidence.

## Verified fixes

The earlier three findings remain addressed in inspected code:

- Definitive ledger refusals terminate the saved action as `REJECTED` and reopen a correctable order; unknown failures retain `ACCEPTED` and the frozen command.
- The payment deadline no longer prevents later claim reporting or correction after a decline.
- Post-cash identity evaluation locks deleted/frozen identities and persists `REVIEW_REQUIRED` instead of granting entitlements or permanently throwing before disposition.

The final cancellation finding is now addressed:

- `EntityPlanPurchaseServiceImpl.java:601` accepts receipt claims in `AWAITING_PAYMENT`, `CANCELLATION_REQUESTED` and `CANCELLED`, preserving a truthful path when a real transfer is discovered during or after cancellation.
- `EntityPlanPurchaseServiceImpl.java:741,771–790` validates the trusted money disposition and maps canonical `LATE` to `REVIEW_REQUIRED`. Only `MATCHED` enters entitlement application, so a late receipt after a closed payment does not automatically activate or extend a plan.
- `EntityPlanPurchaseServiceImpl.java:852–856` preserves the current order's existing `open_entity` on a manual disposition. A previously cancelled order keeps its released slot and does not claim or overwrite a later pending purchase's slot.
- `recoverPayment()` acknowledges only the matching payment number with durable `APPLIED`/`REVIEW_REQUIRED`; the existing handoff adapter therefore acknowledges the late manual disposition without routing it into store mall fulfillment.
- App `entity_plan_purchase_api.dart:123–131` permits declaration in the same three statuses. `entity_plan_purchase_pages.dart:482–486` supplies the localized late-transfer explanation, including that reporting does not automatically activate a plan. English, Traditional Chinese and Simplified Chinese entries are present.
- The real-ledger test source now covers a cancelled purchase, a later pending purchase, a late real receipt becoming `REVIEW_REQUIRED`, unchanged entitlement expiry, preservation of the later purchase's slot and canonical handoff completion. This reviewer inspected those assertions but did not execute them.

The minor OPS visibility observation is also fixed: `ops-web/app/merchants/plan-purchases.tsx:61` renders saved action status and reason, including the backend's persisted refusal code for `REJECTED`.

## Strengths retained from whole-scope review

Canonical `stl_payment` remains the sole cash fact. Merchant declaration cannot grant entitlements. Offers and original money commands are frozen; entity and store entitlement scopes remain distinct; quota overrides and trial history are preserved. Authority uses canonical typed owner membership, request/evidence ownership includes subject kind, and the App fences selection/session changes and retains exact unknown-result commands. Store mall purchasing requires explicit offline readiness.

## Findings

**No unresolved P1/P2 findings in the reviewed implementation scope.** The cancellation finding and minor OPS observation from the previous version of this report are closed by the inspected changes. No new broad review or test execution was performed in the final scoped pass.

## Declined to judge / explicit remaining limit

- Product-internal refund/reallocation/release for recorded `UNMATCHED` and `REVIEW_REQUIRED` remains explicitly deferred in `docs/handoff/offline-plan-purchase-2026-11-11/README.md:49`. The absence is documented as a commercial-release blocker; it is not an automatic-unlock or entitlement policy to invent in this change. Late cancelled-order receipts now reach this honest recorded-funds review state.
- Actual bank/profile and sales-price configuration, online payments, deployment, environment 166 mutation and the independent stored-value migration remain outside the authorized implementation/review scope.
- Historical trial-row reclassification was not supplied as a policy and is outside this patch verification.
- Runtime/regression pass claims are left to the coordinator's execution evidence; this report does not independently claim tests passed.

## Assessment

**Spec compliance:** The reviewed normal purchase flow, trusted cash recording, scoped entitlement application and interrupted/late reporting paths satisfy the implemented local scope. All concrete review findings have been addressed.

**Code quality:** The revised state transitions distinguish a refused proof from an unknown outcome and a late real receipt from an entitlement grant. The cash/business boundary and original command recovery remain explicit.

**Ready to merge the scoped implementation: Yes, subject to the coordinator's required checks. Ready for full commercial release: No.** The documented unmatched/manual-review funds disposition and real configuration work still block a claim of complete commercial exception handling.
