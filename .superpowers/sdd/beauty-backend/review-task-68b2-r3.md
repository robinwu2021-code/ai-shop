# Task68B2 scoped re-review — R3, fix round 3/5

## Finding Verdicts

- **N1 — ADDRESSED.** `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java:702` now invokes `actual.request(...)` inside the action passed to `error(..., BAD_REQUEST)`. The request constructor's reference validation therefore executes within the expected-error assertion. The captured `rawEntity` and `rule` are effectively final iteration-local values; the mutable loop index is not captured.
- **I2 — ADDRESSED / fully closed for the authored-source gate.** The valid case-variant key still expects `ACTIVITY_CHOICE_UNAVAILABLE` at `ManualCounterDiscountIT.java:699`; the malformed trailing-space key now has a correctly bounded `BAD_REQUEST` assertion at `:702`. The raw trailing-space owner row, actual column-equality proof, canonical accepted request and subsequent place/cash/frozen-source assertions remain unchanged. The failure identified in R1/R2 no longer interrupts that authored positive path. The other alias variants were already reviewed in R1 and remain intact.
- **I1 and I3 — remain ADDRESSED.** This single assertion correction does not alter their previously accepted fixes.

## New Breakage in the Fix Diff

None identified. The fix changes only the expected-error assertion's execution boundary; it introduces no helper-wide, production or SQL behavior change.

## Out-of-Scope Observations

None. Unchanged production, B1/A, SQL, other test flows and the old-pending producer were not reopened.

## Spec and Quality Verdict

**Spec compliance: ✅ Approved for the phase-A static/source task gate.** All R0 findings and subsequent N1 are closed.

**Task quality: Approved.** The direct assertion correction catches the actual throwing operation and preserves the separately meaningful valid-foreign and malformed-reference checks.

**Fix round: All findings addressed, no new Critical/Important breakage.**

## Checks and Limits

- Read the fix3 brief, final report appendix, R3 fourteen-source pins and complete single-assertion R2→current diff once. The constructor/helper exception boundary had already been established by the bounded R2 check; no additional source inspection was needed.
- Root verified the other thirteen B2 sources and complete old producer unchanged, all seventeen finite methods retained, and the final pins. The current IT pin is `ae5fa71f0e9398429e118dd83b0bfccc9b83af1caead3a5478f4bf955903744a`. Those checks were not rerun.
- Root-verified covering compile: `/private/tmp/task68b2-fix3-compile.log`, SHA-256 `236f5267b4ee319f87edde237394ffbed851c894045bdb2ad2b9d69ec283a391`, BUILD SUCCESS across 24 selected modules, 752 app test sources, 18.272 seconds. The reviewer executed no build or test.
- All seventeen scenario methods, native SQL/collation/fence behavior, actual owner/stock/cash observations, serializers/goldens and genuine old→current producer/recovery execution remain UNEXECUTED under the user's phase-A boundary. Approval is not a runtime or deployment claim; C materialization and unified phase-B validation remain required.
- Root's m2c topology ruling uses the real PLATFORM stock owner. The `prd_sku` stock assertions remain PLATFORM-only evidence, with no INVENTORY/DUAL success claim.
- Only this review report was written. No product/test source mutation, index/commit action, build, test, serializer, native/SQL/application/probe/network execution or agent dispatch occurred.
