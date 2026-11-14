# Task68B2 static task review — R0

## Spec Compliance

- ❌ **Issues found.** The 14-path integration implements the principal pricing, frozen-display, old-policy and consumed-cash boundaries, but two required positive IT paths cannot reach their assertions under the required V543 schema, and the authored finite cases omit explicit alias and balanced-allocation requirements (I1–I3 below).
- ⚠️ **Cannot verify from static review:** the 17 IT methods, SQL collation/fence concurrency, serializers/goldens, genuine Task66 old-owner build/bootstrap/classpath, four-output timing and old→current execution. These are intentionally deferred phase-B evidence; their nonexecution is not a defect. C owns the exact old source/build/classpath/runner/materialization protocol. The review does not claim runtime compatibility, cash/source success, deployment or full-history migration validation.
- **Scope checked:** supplied BASE `c13e83533477c80218ec94ec549c823697ead8c5` → frozen working tree, all 14 source sections in `task-68b2-review-package.md`, the brief, implementer report, pins and the original/shared/A-fix1 binding addenda. B1/A remain reviewed prerequisites; no source edit, build, test, serializer, old producer, SQL, application, probe, network, index or Git mutation was performed.

## Strengths

- `backend/shop-app/src/main/java/ai/neargo/shop/portal/port/CampaignPortRouter.java:50` and `backend/shop-core/src/main/java/ai/neargo/shop/marketing/port/CampaignPortImpl.java:101`: the router acquires the owner fence before taking one wall time and carries the actual choices/time through both owners. Legacy scope uses the prescribed two SQL queries and primary-key store membership, while selected raw rule scope and canonical priced group coordinates remain distinct.
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java:2180` and `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java:1883`: the quote writer obtains economic authority and display in one pricing result; the counter-only reader dispatch uses persisted options. New counter economic names are removed, while the existing ordinary/M1 branches retain their own serialization/display paths. `ManualCounterDiscountIT.java:185` authors a useful negative assertion against live fence acquisition during read/replay.
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutQuoteGuard.java:138` and `:177`: original stored authority determines the schema0 policy; schema1 gets preliminary and final owner validation, with the latter after pricing/catalog work. The unchanged B1 codec already derives canonical groups from economic lines (`CounterEconomicCodec.java:220`, `:321`), and the guard compares original and actual economics. This supplies the required group/quantity/gross crosscheck without a second group implementation in the guard.
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/ManualServiceAdmissionServiceImpl.java:948`: the merchant-discount exception is restricted to validated counter evidence, exact consumed confirmation/party/namespace/hash and parent/child/item allocations. The new helper has no live marketing or clock eligibility dependency; noncounter/schema0 remain zero-only. `:1106` adds declaration consistency for sealed replay/source without changing the source hash tuple.
- `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountFixture.java:196`: the authored old producer boots its own fixture, calls real prepare/accept/place and delegates the actual original reserve before losing its reply. It records the original plan/confirmation/reservation rather than stripping a seal or fabricating zero economics. Its actual old-build provenance and timing remain C/phase-B obligations.

## Issues

### Critical

None identified in this bounded static review.

### Important

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

### Minor

None recorded.

## Assessment

**Task quality: Needs fixes.** The core implementation has coherent authority boundaries and no additional demonstrated production defect in this bounded review. The authored IT defects and explicit finite-case omissions need correction before the source-only task gate can be approved; this does not authorize running them before unified phase B.

## Focused checks and limits

- **Immutable fixture identity risk:** inspected only the applicable V543 owner UPDATE predicates and related trigger context. This confirms the two failures against the existing reviewed A contract; it does not reopen A or propose changing SQL.
- **Frozen group/hash consistency risk:** inspected B1 `CounterEconomicCodec.requireCurrentMoney`, `groups`, `requireQuoteHash`, command/line checks and their integration calls. B1 source was unchanged and was not rereviewed as a separate task.
- **Objective promotion semantics risk:** checked `UnifiedActivityDefinition`, `ActivityRuleValidator`, `PmtActivity.isActiveAt`, and the existing combo parameter validator/calculation to compare the new probe with actual owner shapes, schedule and CUT/PERCENT/POINTS rules. No member/quota eligibility lookup is added by the new probe.
- **Old bootstrap fixture/authentication risk:** checked `CheckoutFlowFixture.fixture`/`ClockConfig` and `ManualServiceAdmissionIT.currentCashier` against the authored nested bootstrap. The current helper has no required JUnit lifecycle action beyond the explicitly invoked setup; C must still compile/load the genuine Task66 helper and old owner graph, pin all relevant origins, and satisfy the original 300-second validity window for all four outputs.
- **Mid-function diff boundaries:** separately read only the necessary continuation/context for `OrderServiceImpl.checkoutEconomics` and `stageCounterConfirmedFor`, `CheckoutQuoteGuard.requireCounterMatches`, `ManualServiceAdmissionServiceImpl.facts`, and `ActivityPricingServiceImpl.comboHit`/`cutTriggerHits`; their review-package hunks cut those functions or omitted the unchanged semantics required to judge the changed call.
- **Evidence handling:** source pins, 14-path freeze, unchanged prerequisite/SQL checks and nine compile-log checks were supplied as root-verified evidence and were not rerun. The reported corrected run-2 accessor failure is historical; the final compile is reported successful. Compilation is not runtime evidence. All 17 scenario methods remain UNEXECUTED.
