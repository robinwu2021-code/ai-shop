# Task68 preparation — M2c shared ordinary FULL_CUT for cash counter

Status: DRAFT_NEEDS_DESIGN_FIXES_NOT_RELEASED. Task67 implementing. No Task68 product edit/build/native/source baseline authorized. Root finalizes BASE/exactinventory only after independent Task67 acceptance; re-read current OrderServiceImpl because Task67 owns its safe receipt projections.

## Accepted scope

Task65 bm05-m2-counter-source-design.md §4/C1–C3: reuse legacy mkt_campaign FULL_CUT only, merchant-funded, zero platform share, no coupon/points/gift/storedvalue/member-only quota or pmt commit. USER and GUEST both genuine typed parties; no null buyer ambient fallback or guest→NON_MEMBER inference. Existing ordinary/M1 consumer pricing unchanged. Preserve frozen accepted M2a intent/recovery economics, no reprice into newly introduced offer. No Pay or service execution/start.

## Concrete source checks before future release

1. Task66 already added five fail-closed CampaignPort counter methods and ActivityPricingService.rejectUnsupportedCounterChoices/groups+choices and rejectUnsupportedCounterPriceEffects. Do not duplicate signatures or pretend absent. Legacy counterCandidates currently throws if ordinary candidates exist; counter selection only zero; counterFlash/gift reject existing effects; commitCounter validates zero and no-ops.
2. Router.counterCandidates(party,groups) currently calls promo.rejectUnsupportedCounterChoices(groups,Map.of()). Actual two-arg CampaignPort has no choices input, so explicit NONE cannot be distinguished from omission at the unsupported-candidate owner. Current OrderServiceImpl.counterDiscounts(party,amounts) also lacks original activityChoices. Before coding rule a minimal explicit choices overload/contract through existing owner chain. Prefer explicit parameters, never ThreadLocal/session/cashier audience. Legacy adapters must default fail closed on unsupported nonempty choices. Exact new signature/consumers and paired API/Java metadata need root addendum before release.
3. Explicit supported FULL_CUT must match exact scoped active owner rule/time/amount. Explicit NONE is deliberate group opt-out. Omitted choice selects best supported only after owner rejects otherwise applicable unsupported rules; absent/nonexistent and explicitly unsupported remain distinct existing errors. Active FLASH/BUY_GIFT SKU effects always reject this subset (NONE cannot covertly erase product-price effects).
4. Current OrderServiceImpl.checkoutEconomics rejects any manual discount; specialize only authorized counter subset (merchantMinor==discountMinor, platform0, no coupon/gift/points, due>0), retain old C/M1 constraints. Existing discountsOf/LineShares/economic snapshot productVersionHash/final locked owner-rule seals remain authoritative.
5. Legacy CampaignPortImpl uses System.currentTimeMillis for active rules. Preserve owner semantics; design deterministic expiry test via actual owner time/rule update, never assume unrelated checkoutMutableClock controls marketing. Recheck actual commit/transaction locks and active candidate SQL; pmt owner probes read own rules only.
6. Reconcile Task67 OrderVO/manual projection changes as baseline, do not overwrite. Recheck no new migration needed; no applied V539–V542/inventoryV14/beautyV1–V9 edits. Final exact module/path list comes from current code after67.

## Future finite evidence — author/compile now, execute only unified phase B

Real USER/GUEST positive FULL_CUT quote→in-person acceptance→original order/item economic shares→cash source/declaration (same due), ordinary funded/M1 unchanged control only if touched. Explicit/omitted/NONE behavior and unsupported candidate/FLASH/gift/account offer/foreign scope. Price/rule/time mutation between accepted quote and creation invalidates stale economics; pending M2a original remains same order/stock/economics under new policy. No silent base-price fallback, promo.commit(null), pmt_apply guest row or new promotion ledger.

After design release, author the finite m2c/C1–C3 and focused A2 cases/runner, and compile current default/beauty/food affected product and test sources. Do NOT execute those tests, native MySQL/HTTP, protocol/architecture suites or ablation during coding. Execute finite real MySQL9.7.2 owner cases and meaningful final-seal ablation only in unified phase B under actual source pins/owned cleanup; no broad unrelated replay. Deployment follows phase C. Main TDD§0.4 overrides all older per-slice runtime gates.

## Read-only owner facts captured during Task67 R0 (not a release)

- CheckoutServiceImpl.prepareCounter currently rejects every nonempty activityChoices at the public entry; specialize that counter guard. ManualServicePurchasePolicy applies to M1 consumer admission and is not automatically in M2c edit scope.
- OrderServiceImpl.counterDiscounts currently takes only party/amounts; explicit choices must reach actual router and legacy owner. Its checkoutEconomics scope.manual zero-discount gate requires a counter-only branch, preserving M1.
- CampaignPortImpl is marketing/port/CampaignPortImpl.java. Its candidatesFor delegates to default candidates/autoDiscount (best only), so explicit eligible non-best FULL_CUT needs actual eligible enumeration. Existing requireOrdinarySelection locks rule/type only; this is insufficient as the counter final rule/value/time seal.
- CampaignServiceImpl.save accepts goodsNos on FULL_CUT; existing bestFullCut ignores that list. Do not invent an item-qualified scope from the bare merchant subtotal or silently fix retail pricing. Future release must explicitly rule whether the first counter subset accepts only empty goodsNos, with otherwise-applicable unsupported rules failing closed.
- MktCampaign inherits actual @Version Long version and @TableLogic deleted from shop-store-mybatis BaseEntity. Final counter seal must cover real version plus rule values and scope, not a fabricated activity version.
- ManualCounterPurchaseIT contains reusable actual counter/member/customer/original-stock fixtures, but extending it inherits its @Test methods. Finite M2c test selection must avoid replaying all M2a tests incidentally.
- Runner only has m2a/m2b today; new m2c needs its own Task68 gate, not accidental fallback to the accepted Task66 native gate. No native authorization is granted here.

## 2026-10-26 independent review release gate

See review-next-design-2026-10-26.md. Overall sequencing Spec PASS, immediate Task68 Architecture NEEDS_FIXES/NOT_READY_FOR_CODING. Important I1 requires exact choices/key/error/NONE/omission/candidate/goodsNos contract across both ingress guards/router/owners. Important I2 requires concrete versioned counter owner seal carrier, final owner input/linearization/contrary writers and unchanged old quote/CounterOrderPlan bytes/hash/original zero-policy recovery. Existing preflight notes alone are not coding authorization. Root must publish exact addendum/inventory/paired metadata and obtain scoped static design rereview. Minor old test-timing text synchronized here; no new tests/native authorized. C03+ and C11 owner contracts carried to their own publication gates.
