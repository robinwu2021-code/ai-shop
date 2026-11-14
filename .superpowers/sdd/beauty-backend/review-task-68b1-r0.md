# Task68B1 static task review R0

## Spec Compliance

- ❌ **Issues found:** the required schema1 quote/bare-plan compatibility test constructs contradictory original choices and cannot reach its intended plan/hash checks. See I1. The nine-source implementation otherwise follows the B1 brief and approved shared-contract overlay in the reviewed diff.
- ✅ The diff contains all 3 modifications and 6 additions in the brief, including the seven public pure SPI methods, owner-local rules, strict codec, quote envelope and two test sources. No runtime consumer integration or private-domain import was introduced by this diff.
- ⚠️ Exact old serializer bytes/hashes, executed assertions and live owner/cash behavior are not established by a static diff. The six golden resources and all runtime/serializer/native/SQL/application checks are intentionally deferred to phase B; their absence is not a coding defect. B2 owns consumer integration.

## Strengths

- `backend/shop-base/src/main/java/ai/neargo/shop/spi/marketing/CounterCampaignContract.java:38`, `:127`, `:163`, `:196`: canonical goods aggregation uses checked arithmetic; the single digest implementation preserves positional nulls and sorted maps; selection checks bind raw owner rules to canonical group allocations without current owner IO or Java scope-alias reinterpretation.
- `backend/shop-base/src/main/java/ai/neargo/shop/spi/marketing/CampaignPort.java:53`, `:79`: new seal and evaluation carriers snapshot nested group/allocation collections. The two complete overloads fail closed while existing counter signatures remain intact in the diff.
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutEconomicSnapshot.java:30`, `:32`: the seal is one trailing property-level NON_NULL component, and the original 18-argument constructor delegates with null. Existing component order and unchanged economic methods are preserved by the diff.
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CounterEconomicCodec.java:51`, `:134`, `:350`: raw record shapes are checked before typed conversion; quote envelopes remain distinct from bare plan economics; the counter hash reconstruction matches the existing writer tuple. `CounterQuoteSnapshot.java:10` copies the display list, and `CheckoutPricingScope.java:83` rejects mixed policy context.
- `backend/shop-core/src/main/java/ai/neargo/shop/marketing/campaign/CounterCampaignRules.java:25`, `:127`: malformed goods scope fails closed and protocol construction delegates to SPI. `backend/shop-core/src/test/java/ai/neargo/shop/marketing/campaign/CounterCampaignRulesTest.java:108`, `:124` independently assert the nullable positional tuple and original-context/allocation mismatch behavior.
- `backend/shop-core/src/test/java/ai/neargo/shop/trade/order/service/create/CounterEconomicCompatibilityTest.java:296`, `:309`, `:388`, `:410`: the fixture loader fails when genuine fixtures are missing; the deferred producer rehashes archived inputs and loads original serializer classes child-first; verification compares both supplied mapper variants. No placeholder fixture or missing-fixture success path is present.

## Issues

### Critical (Must Fix)

None found in the reviewed scope.

### Important (Should Fix)

**I1 — Build the schema1 plan from the same explicit choices as its sealed economics.**

`backend/shop-core/src/test/java/ai/neargo/shop/trade/order/service/create/CounterEconomicCompatibilityTest.java:73`, `:78`, `:287`, `:292`.

`schemaOneEnvelopeAndBarePlanKeepDisplayOutsideAuthority` creates economics with requested choices `{E1:A1}`. Its first `codec.planHash(plan(economic))` call then uses the shared `plan` helper, which calls `command()` and hardcodes an empty activityChoices map. `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CounterEconomicCodec.java:211` correctly requires that map to equal the seal's requested choices and throws CONFLICT. Therefore this authored test deterministically stops before the intended bare-plan serialization/display hash assertions; fixing golden files or running it later cannot resolve the contradiction.

Make the new-plan test helper preserve the economics' original choice map (and typed party where applicable), while retaining the empty-choice helper for genuine old baseline construction. Keep the explicit-choice case so it verifies a real supported schema1 plan. This finding is established from source control flow; no test or serializer was run.

### Minor (Nice to Have)

None reported.

## Focused checks outside the diff

- **Risk: guessed outer quote or baseline hash tuple.** Inspected only the matching hash/evidence construction blocks in `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java:1988`, `:2032`, `:2047`, `:2065`. The codec and deferred producer use the actual existing tags, nested scope/catalog tuple, typed party and nameless money convention.
- **Risk: reflective old-plan constructor incompatibility.** Inspected `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CounterOrderPlan.java:15` and its compact constructor at `:29`. The existing 13-component record matches the producer's argument list; quote display is not a plan component.
- **Risk: typed-party validation accidentally rejects nullable USER revision or accepts missing GUEST revision.** Inspected `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ManualServiceAdmissionPort.java:437`. The reused constructor has the approved USER/GUEST behavior.
- **Risk: recursive strict plan checking rejects types or nullable maps actually emitted by the old counter command producer.** Inspected the command components in `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/OrderService.java:288` and the bounded command factory in `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java:1957`. Its record/scalar/list/map types are supported, and the factory supplies nonnull maps.

## Assessment and limits

**Task quality: Needs fixes.** I1 must be corrected so the authored compatibility case can exercise its promised schema1 plan/display behavior. No additional product-code defect was established in this static pass.

Reviewed BASE `63f4866e62709ac8b3c1df3fe66a64e53504f9c8` against the frozen working-tree package and its nine source pins, with the brief, implementer report and approved design overlays. The initial combined tool output was truncated; only omitted package/document segments were recovered. No changed source file was separately reread, no git command was run and no broad source crawl was performed.

No builds, tests, serializers, baseline generation, probes, SQL, native checks, network calls, applications or deployments were executed. The controller supplied four pinned, independently verified compile-only runs; this review does not turn those into runtime evidence or independently revalidate the archived class bytes. Only this review report was written; product/test sources, index, HEAD and branch state remain untouched.
