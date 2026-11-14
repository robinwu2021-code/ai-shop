# C04b3 FIX1 scoped independent review

Reviewed `/private/tmp/beauty-c04b3-review-fix1.diff` (1,782 lines / 17 paths), the final preparation-observation specification ruling, FIX1 report and 17-path manifest. Scope was I1/I2/I3 and breakage introduced by their fixes; the original broad review was not repeated. The root-approved typed wrapper is the governing specification; I did not demand reconstruction of missing historical mirror evidence from the old aggregate receipt.

**SPEC: REQUEST CHANGES (one fixture-source correction). QUALITY: REQUEST CHANGES (same correction).** No remaining product Important found in this scoped pass. No Critical finding. No tests, Maven, SQL, DB, HTTP, application or deployment execution, and no product edits/subagents. Compile outcomes belong to root; this report makes no runtime-pass claim.

## I1 — ADDRESSED

`ServiceTaskStockPreparationCoordinator.java:211` now rechecks `access.requireSame` after the final external evidence callback and before applying its response. The existing denial propagation remains. The focused unit fixture changes authorization only in that final callback, after retain, and asserts no final observe and exact-original preservation. The new native fixture at `backend/shop-app/src/test/java/ai/neargo/shop/scenario/DualExecutionStockRetentionIT.java:234` removes the actual employee store-role record in that final callback, expects FORBIDDEN, checks no final Beauty receipt, and compares actual accepted PLATFORM/Inventory rows. These are appropriate source fixtures; they have not been executed in this review.

## I2 — ADDRESSED

`ExecutionStockRetentionPreparationObservation.validate` now binds the full frozen ordered roster to the exact lookup and original aggregate receipt: authority roster/cardinality/order, roster hash, exact component parent and common source confirmation, descriptor validity, wrapper kind/no/hash/status, exclusive typed payload, fixed inner schemas and hashes, inner outcome association, and actual proof association. RETAINED requires every expected component retained; missing roster is explicit and restricted to absent UNKNOWN/UNAVAILABLE observations.

The approved implementation gets that roster through real HOST `queryPreparationObservation` -> persisted `read(row)` / `RouteOriginalV2` validation, not current configuration or a reconstructed mirror. The wrapper carries no saved HUMAN authority. The two existing preparation stock methods/routes use the wrapper; the operation count and machine/HUMAN separation remain unchanged. Dedicated strict decoding uses the concrete wrapper type. Machine reply acceptance, standalone HUMAN result acceptance, Beauty pre-apply validation, and durable rereads all invoke the shared validator. Existing aggregate/proof/hash shapes are not extended by this fix diff.

The native A/B source fixture obtains actual Core references and accepted native receipts and routes an actual retained observation through the Beauty coordinator. It then substitutes self-valid foreign owner proofs, truncates/duplicates components, alters wrapper identity, changes inventory schema/outcome and swaps roster. It asserts rejection before companion version/receipt changes and preservation of actual pins; a persisted semantic tamper case exercises durable reread validation. This closes the originally identified self-validation-versus-parent-binding gap at source level.

## I3 — NOT ADDRESSED completely: physical fixture strategy corrected, one assertion contradicts the real owner

The new fixture at `backend/shop-app/src/test/java/ai/neargo/shop/scenario/DualExecutionStockRetentionIT.java:151` now inserts the semantically invalid real row with `route_json={}`, asserts insertion, uses the real `dueGroup`, checks the bad-row retry CAS, and has a later real accepted DUAL original with both native owner claims. This fixes the prior mock-source deficiency. The native Beauty retained consumer also replaces the prior DTO-only positive evidence.

**Important F1 — successful later-row observation incorrectly required to retain its previous receipt hash.**

Location: `backend/shop-app/src/test/java/ai/neargo/shop/scenario/DualExecutionStockRetentionIT.java:228`:

`assertThat(goodAfter.getObservationsHash()).isEqualTo(goodBefore.getObservationsHash());`

Trigger: the actual later good row is continued successfully, exactly as the fixture intends. The unchanged real coordinator executes `persist` -> `combine` -> `receipt`; `receipt` uses a new `Instant.now()` in the aggregate receipt (`ExecutionStockRetentionCoordinatorImpl.java:525`), and that observedAt participates in `receiptHash`. `persist` writes that new receipt hash into `observations_hash` (`:483-491`). Therefore a normal successful refresh changes this mutable observation hash even when every original and pin is unchanged. The fixture incorrectly rejects the expected successful behavior; receipt-hash equality is appropriate for the failed bad row, not for the independently successful later row. This conclusion follows from actual source and does not rely on a claimed test execution.

Minimal correction: replace the good-row old/new hash equality with validation of the newly persisted aggregate receipt and its stored hash plus exact reference/retention/request/route/roster association. Keep good-row immutable original comparisons, revision progression, RETAINED, actual pin-row equality, and the failed bad-row exact original/receipt preservation assertions. Do not change production receipt timestamps/hash algorithms to satisfy this test. The existing fixture itself is the regression source; no additional broad test framework is needed.

## Focused dependency checks

Only the existing HOST `persist/observe/combine/receipt` path and native Inventory proof validation were read beyond the supplied fix to confirm that validation and fixture expectations are actually supplied by those callees. Inventory proof validation already requires RETAINED state and validates its native descriptor/domain, so no additional missing-state finding was inferred. No unrelated project audit was performed.

After F1's fixture correction, the three original issues can be marked addressed at source-review level, subject to root's fresh compilation and the user's later unified runtime test gate.
