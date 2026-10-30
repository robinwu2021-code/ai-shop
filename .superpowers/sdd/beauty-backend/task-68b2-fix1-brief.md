# Task68B2 fix round1/5 — R0 I1/I2/I3

Read full review-task-68b2-r0.md. Root independently source-verified I1 against exact V543 predicates, I2 against shared addendum§6 and one-case IT, I3 against the one-line fixture/+1 aggregate perturbation and original addendum§3.4/§6. All three are accepted concrete required source defects/omissions; no business design or production SQL change is authorized.

Exact permitted edit paths: backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java and ManualCounterDiscountFixture.java, plus append existing task-68b2-report.md. Other12 B2 Java sources and all B1/A/SQL remain byte-identical to task-68b2-r0-base/manifest.json. Stay within existing17 finite @Test methods; helpers/actual variants may grow within these2files. No new @Test case or source path without exact gap report. C remains root-owned/unreleased. Retain genuineoldproducer byte-identical unless these2fixture helper changes intrinsically require a symbol adjustment; report any such need before edit.

## Open findings verbatim

**I1 — Two positive IT setups violate the required immutable owner identity triggers.**

- `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java:335` inserts a MERCHANT activity through `actual.promotion(...)`, then changes `entity_no` and `owner` to PLATFORM. V543's existing `trg_ccf_pmt_activity_update` rejects either byte change (`backend/shop-app/src/main/resources/db/migration/V543__counter_campaign_owner_fence.sql:87`). Consequently the platform enrollment and published-version race method fails before creating its enrollment or reaching either race.
- `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java:610` changes `tenant_no` and `entity_no` on an already-inserted canonical campaign. `trg_ccf_mkt_campaign_update` rejects those changes (`V543__counter_campaign_owner_fence.sql:34`). Consequently the alias positive cannot prepare its quote.
- Insert the platform and raw-alias rows with their intended identities from the outset, using the existing fixture/IT inventory. Keep the reviewed triggers enabled and unchanged; catching their rejection would not establish these requested positive behaviors.

**I2 — The required alias integration variants are not authored.**

- `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java:608` contains only one USER/case-only campaign flow and one case-variant client-key rejection. Even after I1's setup correction, it does not cover GUEST, collation-equivalent accent/trailing-space owner values derived from the native fixture, distinct foreign owner/store exclusion, an activity-reference spelling mismatch, or frozen alias read/source after owner mutation/deletion. These are explicit requirements of `task-68b-shared-contract-addendum.md` §6, not an optional coverage expansion.
- Extend this finite method/composed fixture with actual SQL owner rows and the specified variants, asserting raw seal facts, canonical economic coordinates, consumed cash/source and the byte-exact public-key/reference exclusions. Derive the actual column equality before choosing accent/trailing-space positives; do not normalize in Java or weaken the fence.

**I3 — The mandatory balanced per-line allocation tamper negative is missing.**

- `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java:481` (`consumedCounterRejectsAllocationPartyAndAuthorityTampering`) uses the one-line request from `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountFixture.java:89`; its post-cash `paid_share_minor + 1` changes the aggregate. It never keeps total paid shares and parent/child due unchanged while moving value between two real item lines. Therefore this case cannot distinguish the required per-line proof from an aggregate-only implementation.
- Add a real two-line owner order through the existing fixture, move one unit of paid share from one item to the other while preserving total/due, and assert first-confirm rejection with no declaration/effect; author the corresponding post-declaration UNKNOWN/no-usable-lines and same-key replay rejection as required by original addendum §3.4/§6. No successful owner/stock reply should be substituted.


## Binding implementation/verification limits

- Platform and raw-alias identities must be correct at INSERT; do not disable/drop/weaken triggers or catch identity rejection as a positive. Platform owner/entity shape must match existing V543/real owner semantics.
- Alias variants use real USER/GUEST owner/catalog/stock/cash flows and actual effective column equality. No Java normalization as proof, no assumptions/skips/empty-pass. Retain byte-exact client keys/activity identities, distinct foreign scope exclusion, frozen read/source after real owner changes/delete. Preserve canonical group/share versus raw rule facts and same-money changes stale before consumption.
- Balanced tamper needs a real two-line order, with paid shares redistributed ±1 while aggregate/due stays unchanged. Include first-confirm no declaration/status/stockeffect; separate genuine post-declaration source UNKNOWN/no usable lines and same-key replay rejection. Do not replace successful owners/stock/cash. Mutate only the authorized negative boundary after a real valid original. Existing SQL-protected failures count only for the actual DB constraint, not as this per-line service proof. Do not rewrite stored quote/hash/confirmation to fake positivity.
- User phase A: author tests/fixtures and offline current-source test-compile -DskipTests ONLY. NO tests/JUnit/Node/serializers/oldproducer/baselines/native/SQL/app/probe/network/deploy/ablation. No Maven install/stale owners. Covering authored sources are exactly these2tests; same Java21/cache/24module reactor command as B2, capture actual output/log SHA/counts. Static source/style/diff checks allowed.
- No subagents/index/commit/reset. Finalreport append fixdispositions/actualhelpers/cases/pins/commands/UNEXECUTEDlimits; refresh all14sourcepins and freeze. Root originalsame-reviewer scopedR1 follows. Do not claim runtimeGREEN or fullC02complete.
