# Task68B2 scoped re-review — R1, fix round 1/5

## Finding Verdicts

- **I1 — Positive fixtures changed immutable owner identity: ADDRESSED.** `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java:334` now inserts a PLATFORM head through the owner/entity overload. `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountFixture.java:169` supplies those values in the INSERT. Alias rows likewise receive their raw tenant/entity/store at INSERT through `ManualCounterDiscountFixture.java:155`; the former immutable-identity UPDATE is removed. No trigger change or exception-as-positive substitute appears in the fix.
- **I2 — Required alias integration variants: NOT ADDRESSED in full.** `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java:658` now authors case/accent/trailing-space rows, actual SQL column-equality assertions, USER/GUEST flows, foreign owner/store exclusion, byte-exact reference negatives, selected-scope staleness, frozen post-sale read/replay/source and group/activity/consent tampering. However, the new trailing-space iteration fails at `:699` because it expects the wrong error for a malformed client key (N1 below). That prevents the required trailing-space positive from reaching place/cash/source. The coverage omission is substantially corrected, but that case is not yet valid authored evidence.
- **I3 — Balanced per-line allocation tamper: ADDRESSED.** `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountFixture.java:99` creates a second real SKU and publishes both catalog entries. `ManualCounterDiscountIT.java:567` requires exactly two actual items, changes their paid shares by +1/−1, and verifies the unchanged item sum and parent/child due. `:533` asserts first-confirm CONFLICT, no declaration, unchanged parent status and product stock columns, and no receipt outbox event. The separate genuine admitted order at `:555` then checks same-key CONFLICT and UNKNOWN/no usable source lines after the balanced mutation. These are real flow assertions, not positive owner substitutes.

## New Breakage in the Fix Diff

**N1 — Important: the trailing-space alias iteration expects ACTIVITY_CHOICE_UNAVAILABLE for a malformed key.**

`backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java:664` assigns `rawEntity = entity + " "` for index 2, then `:699` submits that raw string as the client choice key and expects `ACTIVITY_CHOICE_UNAVAILABLE`. The approved public-reference contract rejects leading/trailing whitespace as `BAD_REQUEST`; raw SQL owner aliases do not relax that client rule. Thus the third iteration stops before its intended successful placement, cash admission and frozen source checks.

Use a well-formed foreign/case-variant key for the unavailable assertion in every iteration, or expect `BAD_REQUEST` specifically for the trailing-space key and retain the unavailable assertion separately. Preserve the raw trailing-space owner row and canonical accepted request so that its actual SQL membership and completed sale remain exercised. No production or SQL change is needed.

## Out-of-Scope Observations

None. Unchanged production, B1, A, SQL and the genuine old-pending producer were not reopened.

## Spec and Quality Verdict

**Spec compliance: ❌ One open defect remains: N1 / incomplete I2.**

**Task quality: Needs fixes.** I1 and I3 are corrected in the scoped source diff. The alias additions use the appropriate SQL predicates and frozen evidence, but their trailing-space client-key assertion must match the approved error contract before this fix round can pass.

**Fix round: Findings remain open — I2 through N1.**

## Checks and Limits

- Read the fix brief, final report appendix, R1 pins, scoped re-review template and the complete two-test-file R0→current package once. Line references were calculated from that package. No changed source file needed a separate reread.
- The new alias equality assertion (`ManualCounterDiscountIT.java:676`) compares canonical parameters directly against the actual `mkt_campaign` tenant/entity/store columns. It does not use Java normalization, an assumption or a skipped branch as equality proof.
- The new two-SKU INSERT uses the same owner/product/base-stock column shape as the established `CheckoutFlowFixture` setup already checked in R0, and the request names two distinct SKU/client-line identities.
- One bounded external check evaluated the new stock observation's scope: `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/port/OriginalStockDispositionPort.java` routes by frozen stock authority. The added `prd_sku.stock/locked_stock` comparison is direct evidence for those columns; it does not by itself prove unchanged inventory-owner balances in an INVENTORY run. C/phase B must preserve the actual authority disclosure; the balanced allocation and rejection assertions are present independently of that observation limit.
- Root supplied verification that the other twelve B2 sources and complete OldPendingProducer are byte-identical, all seventeen finite test methods remain, and final pins match. Those checks were not rerun.
- Reported covering compile: `/private/tmp/task68b2-fix1-final-compile.log`, SHA-256 `05ab5f3b61e351bb30ef1c9f3dc784fe560f0f8fc8ca3e8abcd66e54d88bf891`, BUILD SUCCESS across 24 modules, 752 app test sources, 18.442 seconds. Root verified that evidence; this reviewer did not execute a build or test. All seventeen methods, native SQL/collation/concurrency, owner/stock/cash behavior, serializers/goldens and old→current execution remain UNEXECUTED under the phase-A boundary.
- Only this review report was written. No source/index/commit mutation, build, test, JUnit, Node, serializer, old producer, native database, SQL, application, probe, network or agent dispatch occurred.
