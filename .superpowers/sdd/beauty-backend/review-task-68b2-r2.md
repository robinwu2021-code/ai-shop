# Task68B2 scoped re-review — R2, fix round 2/5

## Finding Verdicts

- **N1 / incomplete I2 — NOT ADDRESSED.** `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java:699` now correctly uses a well-formed case-variant key for `ACTIVITY_CHOICE_UNAVAILABLE`, and `:702` correctly names `BAD_REQUEST` for the raw trailing-space key. However, the latter still fails outside the assertion because `rejected(...)` constructs the request before invoking `error(...)`. The required trailing-space positive therefore still cannot continue to place/cash/source.
- **I1 and I3 — remain ADDRESSED.** Neither closed finding is reopened by this single-IT correction. Root verified the other thirteen sources and complete old producer unchanged.

## New Breakage / Remaining Failure in the Fix Diff

**Important — The newly explicit malformed-key assertion does not catch request-construction failure.**

`backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java:702` calls `rejected("malformed-trailing-key", Map.of(rawEntity, rule), BAD_REQUEST)`. That helper constructs `actual.request(...)` at `:763`, before its `error(() -> actual.prepare(request), expected)` at `:764`.

The constructor path is concrete: `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ManualServiceAdmissionPort.java:120` delegates request shape validation to `PrepareCheckoutReq`; `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/CheckoutPort.java:239` validates each choice key/value during construction, and `:506` rejects leading/trailing whitespace. The `BAD_REQUEST` is consequently thrown before the expected-error assertion starts. This is a failing assertion path, not a runtime uncertainty.

Wrap the request construction itself in `error(...)` for this malformed-input negative, for example an action that calls `actual.request("malformed-trailing-key", actual.user(), Map.of(rawEntity, rule))`. Retain the well-formed unavailable assertion and the raw trailing-space owner/canonical positive flow. No production, helper-wide or SQL change is required.

## Out-of-Scope Observations

None. The DTO/helper reads above were solely a bounded check of the new malformed-input assertion's exception boundary; unchanged production behavior was not reopened.

## Spec and Quality Verdict

**Spec compliance: ❌ N1 / I2 remains open.**

**Task quality: Needs fixes.** The error-code distinction is corrected, but the assertion must include the constructor that raises that error. No other breakage was identified in the three-line fix.

**Fix round: Findings remain open — N1 / incomplete I2.**

## Checks and Limits

- Read the fix2 brief, appended implementer report, final fourteen pins and complete R1→current single-IT diff once. The only focused dependency check followed `rejected` → `PrepareCounterReq` → `PrepareCheckoutReq.reference` to establish where the new malformed negative throws. No broader source inspection was performed.
- Root verified the other thirteen B2 sources and complete old producer byte-identical, the seventeen-method suite unchanged, and the final pins. Those checks were not rerun.
- Root-verified covering compile evidence: `/private/tmp/task68b2-fix2-compile.log`, SHA-256 `f1b2466884a787e137390c144b45dd0ed01c6526cadb7233df5179adfe9be2b0`, BUILD SUCCESS across 24 modules, 752 app test sources, 21.095 seconds. This reviewer ran no compile or test. All seventeen IT methods and runtime/serializer/native/old→current evidence remain UNEXECUTED under phase A.
- Root's C topology ruling pins the m2c stage to the real PLATFORM stock owner (`SHOP_INVENTORY_STOCK_AUTHORITY=PLATFORM`, `SHOP_INVENTORY_ENABLED=false`). Existing `prd_sku` stock observations are PLATFORM-only evidence; no INVENTORY/DUAL success is inferred. This is a recorded scope limit, not another fix request.
- Only this review report was written. No product/test source mutation, index/commit action, build, test, serializer, native/SQL/application/probe/network execution or agent dispatch occurred.
