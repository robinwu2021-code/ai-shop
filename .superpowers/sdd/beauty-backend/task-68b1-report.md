# Task68B1 pure protocol and economic compatibility prerequisite report

Status: **DONE_PENDING_ROOT_REVIEW**. Source and compile phase only; business counter discounts, final owner evaluation, quote consumers and admission/cash integration remain unactivated pending B2. All runtime evidence, genuine golden generation and byte compatibility execution remain phase B.

Sole worktree `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`, branch `codex/beauty-foundation`, coding HEAD `63f4866e62709ac8b3c1df3fe66a64e53504f9c8`. Task68A fence prerequisite `5aa1769f2` is unchanged. Read B1 brief/inventory/preflight, reviewed shared-contract overlay/report/review, original addendum §§2.1/3/3.4, CLAUDE and Java standards before implementation. The explicit phase-A user override supersedes RED/GREEN execution: tests/harness authored before the production implementation, but none executed.

## Exact delivered source scope and frozen pins

Exactly **3 modified + 6 created Java sources = 9**, plus this report. No other product/test/resource path was written. Root's existing dirty `progress.md` ledger is root-owned and was not edited by this agent. No staging, commit, reset, subagent, migration/POM/global mapper/config/known-* change occurred.

| Action | Actual path | Frozen SHA-256 |
|---|---|---|
| modify | `backend/shop-base/src/main/java/ai/neargo/shop/spi/marketing/CampaignPort.java` | `364f3e07adc0fa713e490fd905dd3a065d6508e519691964662aabe889a5f7fc` |
| modify | `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutEconomicSnapshot.java` | `84ffbf1dc58e4ea34edbfa4fa3194d414461ed46fc3700e6c130f76e81ec1237` |
| modify | `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutPricingScope.java` | `fea01078516e7c23434d54a609ffdd375b90705d06e08e8d0be61fe5cb99c6af` |
| create | `backend/shop-core/src/main/java/ai/neargo/shop/marketing/campaign/CounterCampaignRules.java` | `29a3b0360f77a852f33401ae1360ec683e05aaf981685d3e2863ef27288285a1` |
| create | `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CounterEconomicCodec.java` | `188aa673d3d3481026b4547f86d07799d1c7bcf13e4bad6815dcab126472efd5` |
| create | `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CounterQuoteSnapshot.java` | `b645014f34ae17e4d6ddaa80f5b758350e916ba56eee86c224dfed2968631772` |
| create | `backend/shop-core/src/test/java/ai/neargo/shop/trade/order/service/create/CounterEconomicCompatibilityTest.java` | `6fb41e1fd266f122805048f2353d232668126dc453b821bd40a4b55546a5a6ea` |
| create | `backend/shop-core/src/test/java/ai/neargo/shop/marketing/campaign/CounterCampaignRulesTest.java` | `f403ce14f7a754d2a01e12f98d3076e5b85ca1e500f320cc26e8809f82077f6d` |
| create | `backend/shop-base/src/main/java/ai/neargo/shop/spi/marketing/CounterCampaignContract.java` | `5280a79001b8338e7ca9827bdf7d9b3aba8295405077aaf61018f56b4e57c48a` |

All three modified HEAD source hashes independently match the released inventory baselines: CampaignPort `92d0cce6d20c04faa38220a8a7659d26a95fc3f386575aa3774bc9266f602476`, economic `f7efd1585e981c91393a09e2007319c9619d7a847facfb0dfcd856705eb48cae`, pricing scope `c913c40c672a3ad3c205171f7955fe9ece685378975ed7207180da88e6efb32b`.

## Actual implemented contracts

`CampaignPort` adds immutable `CounterChoice(entityNo,storeNo,mode,activityNo)`, `CounterRule(tenantNo,activityNo,entityNo,storeNo,ruleVersion,type,status,deleted,thresholdMinor,discountMinor,startAt,endAt,goodsNos,appliedMinor,funder,platformMinor)`, `CounterActivitySeal(schema,policy,party,groups,requestedChoices,resolvedChoices,selectedRules,digest)` and `CounterCandidates(candidates,selected,seal)`. All collection-bearing new records defensively copy and reject null collections/elements; seal groups deep-copy goods lines, requested choices are immutable sorted copies, candidates snapshot both selected allocation lists. Nullable actual rule values, raw owner scopes, nullable USER identityRevision and digest-construction null are preserved where their contracts allow them.

The two new complete overloads are:

```java
default CounterCandidates counterCandidates(CustomerParty party,
        List<MerchantAmount> groups, Map<String, String> choices);
default void requireCounterSelection(CustomerParty party,
        List<MerchantAmount> originalGroups, Map<String, String> originalChoices,
        CounterActivitySeal originalSeal, Discount originalSelected);
```

Both default fail closed with CHECKOUT_DEPENDENCY_UNAVAILABLE. The five existing counter methods and every ordinary method/signature/body remain unchanged. No adapter is activated by adding these defaults.

`CounterCampaignContract` is final, private-constructor, pure SPI with all seven complete static methods, using the injected Jackson mapper:

```java
public static List<MerchantAmount> canonicalGroups(List<MerchantAmount> groups);
public static Map<String, String> normalizeChoices(List<MerchantAmount> groups, Map<String, String> choices);
public static List<CounterChoice> resolveChoices(List<MerchantAmount> groups, Map<String, String> choices);
public static CounterActivitySeal seal(ObjectMapper json, CustomerParty party,
        List<MerchantAmount> groups, Map<String, String> choices, List<CounterRule> selectedRules);
public static String sealDigest(ObjectMapper json, CounterActivitySeal seal);
public static void requireSeal(ObjectMapper json, CounterActivitySeal seal);
public static void requireSelection(ObjectMapper json, CounterActivitySeal seal, CustomerParty originalParty,
        List<MerchantAmount> originalGroups, Map<String, String> originalChoices, Discount originalSelected);
```

CanonicalGroups requires exactly one real positive group, canonical entity/store references, nonempty goods lines, rewards disabled, nonnegative line amounts/positive quantities and exact amount/count sums. Duplicate goods aggregate with exact long/int arithmetic and natural case-sensitive order. Choices retain equal entity/store aliases and exact bytes in a sorted immutable map; omitted/AUTO, NONE and EXPLICIT resolve once against the actual group even with no candidates. Malformed/null/conflicting client input is BAD_REQUEST, well-formed foreign/case-variant key including foreign NONE is ACTIVITY_CHOICE_UNAVAILABLE. Seal APIs translate choice/construction failures to IllegalArgumentException; no empty-success fallback exists.

The only seal digest algorithm is in this SPI class: exact COUNTER_ACTIVITY_SEAL_V1 positional tuple, explicit null tuple positions, declared record field order, sorted map keys, UTF-8 SHA-256 and lowercase hex. Shape validates schema1/LEGACY_FULL_CUT_V1, typed party, canonical original groups/choices/resolution, zero-or-one rule, exact explicit activity identity, FULL_CUT/RUNNING/deleted0/version>=0/empty goods/merchant funding/platform0, positive capped discount strictly below gross, frozen null-threshold-as-zero and ordered inclusive timestamps. It never compares timestamps to a later clock. RequireSelection compares original prepared facts and validates canonical group applied/share coordinates, activity identity/amount/qty1/platform0/enrollment-null. Applied names are deliberately nonauthoritative in SPI; codec separately requires null names. Raw owner tenant/entity/store aliases are retained and digest-covered without case/accent/trim conversion or Java equality to canonical group coordinates. Only the scoped owner evaluation in B2 can establish initial SQL membership; no pure helper claims to establish it.

`CounterCampaignRules` contains only owner-local pure helpers:

```java
public static List<String> goodsNos(ObjectMapper json, String raw);
public static boolean active(MktCampaign row, long ownerNowMillis);
public static boolean supportedEligible(MktCampaign row, List<String> goodsNos,
        MerchantAmount group, long ownerNowMillis);
public static boolean scopedFullCutEffect(MktCampaign row, List<String> goodsNos,
        MerchantAmount group, long ownerNowMillis);
public static boolean mandatoryPriceOrGiftEffect(MktCampaign row, List<String> goodsNos,
        MerchantAmount group, long ownerNowMillis);
public static CounterRule selectedRule(MktCampaign row, List<String> goodsNos,
        MerchantAmount group, long ownerNowMillis);
public static AppliedActivity candidate(MktCampaign row, List<String> goodsNos,
        MerchantAmount group, long ownerNowMillis);
public static CounterActivitySeal seal(ObjectMapper json, CustomerParty party,
        List<MerchantAmount> groups, Map<String, String> choices, List<CounterRule> selectedRules);
```

Goods parsing accepts SQL null/Java blank, JSON empty array and one H2 string wrapper; arrays must contain valid references and are sorted/deduplicated. Malformed/wrong/null/trailing-token JSON fails dependency-unavailable, never broadens scope. Predicates take explicit owner time and already owner-scoped rows; whole-group FULL_CUT and null threshold retain legacy semantics, scoped FULL_CUT uses purchased-goods intersection plus whole-group subtotal, mandatory FLASH/BUY_GIFT uses actual goods with valid positive effect facts. Selected facts retain real version0/raw owner aliases; candidates use canonical group coordinates. Rule construction seal delegates to SPI and maps owner defects to dependency-unavailable. No duplicate resolver/digest algorithm, current owner query or private domain dependency exists.

`CheckoutEconomicSnapshot` appends exactly one trailing component with property-only `com.fasterxml.jackson.annotation.JsonInclude(NON_NULL)` and adds the exact original 18-argument constructor delegating seal=null. Existing component order, Evidence, line copying and paidShares() remain intact. There is no class-wide/global omission change. Existing `CounterOrderPlan` and `CheckoutDigests` are byte-identical to HEAD.

`CheckoutPricingScope` appends internal `CounterPolicy counterPolicy` and `CheckoutEconomicSnapshot originalCounterEconomic`. Its old 7/9/11-argument constructors retain their old lanes and supply null new context. Enum values CURRENT_FULL_CUT/FROZEN_M2A_ZERO live in codec. Noncounter context cannot carry a policy/original; original economics require a policy; frozen requires an original without a seal; current original requires a seal. Fresh current can have no original. B2 must derive this context from the locked original quote using strict codec classification; there is no request flag or activated legacy bypass in B1.

`CounterQuoteSnapshot(int schema,CheckoutEconomicSnapshot economic,List<PromotionOption> promotions)` copies display, requires schema0/1 and nonnull economics/list. Schema0 remains an explicit old bare in-memory representation.

`CounterEconomicCodec(ObjectMapper json)` has complete methods:

```java
public CounterQuoteSnapshot readQuoteSnapshot(String pricingJson);
public CheckoutEconomicSnapshot readQuote(String pricingJson);
public CounterOrderPlan readPlan(String counterPlanJson);
public String writeQuote(CounterQuoteSnapshot snapshot);
public String writePlan(CounterOrderPlan plan);
public String planHash(CounterOrderPlan plan);
public boolean legacyZero(CheckoutEconomicSnapshot economic, CreateOrderCommand command);
public void requireQuoteHash(CustomerParty party, List<CheckoutLineReq> originalLines,
        CheckoutEconomicSnapshot economic, String persistedQuoteHash);
```

The private immutable reader enables strict duplicate-object-key detection, duplicate-tree-key rejection and trailing-token rejection without configuring the application mapper. Raw recursive record validation requires exact property sets, required fields, noncoercing scalar/list/map types, nonnull collections/elements and absent-or-nonnull seal. New persisted quote is exact schema1 root schema/economic/promotions; a bare quote with any seal, explicit-null seal, mixed/unknown fields/schema/policy, missing display/seal or malformed options conflicts. Bare old quote must have strict old zero money, empty activity/coupon, positive due==goods, offline CNY count-service lane and original command evidence; it receives schema0 with original empty selected-only options and no synthetic NONE. Plan stays an exact original typed plan with bare economics, validated plan metadata and old-zero/current-seal classification. Quote wrappers never become plans. No stored JSON is upgraded or rewritten by reads.

New economic validation uses SPI requireSeal/requireSelection plus original evidence-derived canonical groups/choices/lines, exact line funding sums and checked arithmetic, merchant-only allocation, null activity names and no coupons/platform/freight. Frozen promotions must have unique valid IDs, ACTIVITY/eligible/null-reason, exactly one final untitled NONE and every selected real activity. Completeness/eligibility of all display candidates remains the authoritative one-evaluation B2 builder's responsibility, not a present-owner reconstruction. Display is excluded from economic and plan hashes. Writes preserve application serialization; planHash remains CheckoutDigests over the full typed original plan. RequireQuoteHash reconstructs the actual existing OrderServiceImpl COUNTER_MANUAL_QUOTE_V1 tuple with original typed party, original lines, tenant/entity/store/scope/catalog evidence and nameless money with quoteHash=null and the same optional seal. It compares both embedded and persisted quote hashes; it does not guess another tag or hash raw JSON.

## Staged integration boundary

These foundations are fully implemented but deliberately unconsumed by current runtime writers/readers. Existing OrderServiceImpl/CheckoutServiceImpl, CheckoutQuoteGuard, owner PortImpl/Router/promotion provider, goods provider, recovery/placement services and ManualServiceAdmissionServiceImpl were not edited. Existing ordinary/M1/M2a constructors compile unchanged and still use their existing flow. B2 owns new owner queries/fences/time, one-evaluation candidates/display, trusted policy derivation, quote/plan consumer routing and consumed cash/admission validation. B1 does not enable C02, discounted quotes, current final owner validation, old recovery bypass or cash→ADMITTED source. No extra path/type gap remains for this prerequisite implementation.

## Authored verification sources — all UNEXECUTED

### `CounterEconomicCompatibilityTest.java` — 7 authored cases, all UNEXECUTED

- `exactOldConstructorAndGenuineGoldenBytesRoundtrip` — UNEXECUTED.
- `schemaOneEnvelopeAndBarePlanKeepDisplayOutsideAuthority` — UNEXECUTED.
- `newZeroAndNoneCarrySealsAndOnlyTheirFrozenNoneOption` — UNEXECUTED.
- `oldScopeConstructorsKeepTheirLaneAndTrustedPoliciesRejectMixedContext` — UNEXECUTED.
- `strictRawShapeRejectsUnknownMissingNullDuplicateAndCoercedFields` — UNEXECUTED.
- `optionsAndSelectedEconomicsCannotContradictFrozenSeal` — UNEXECUTED.
- `frozenOwnerAliasesRoundtripAndLegacyCannotCarryPositiveMoney` — UNEXECUTED.

### `CounterCampaignRulesTest.java` — 10 authored cases, all UNEXECUTED

- `equalAliasesRemainFrozenAndResolveOnce` — UNEXECUTED.
- `canonicalGoodsAggregateExactlyAndSnapshotCollections` — UNEXECUTED.
- `rawOwnerAliasesBindToCanonicalAppliedAndSharesWithoutLiveLookup` — UNEXECUTED.
- `digestAndFrozenChoiceShapeRejectTampering` — UNEXECUTED.
- `digestUsesExactPositionalTupleIncludingNullableFields` — UNEXECUTED.
- `originalPartyGroupChoiceAndAllocationMustMatchExactly` — UNEXECUTED.
- `strictGoodsParsingCannotTurnMalformedScopeIntoWholeGroup` — UNEXECUTED.
- `ownerPredicatesUseFrozenObjectiveFactsOnly` — UNEXECUTED.
- `mandatoryPriceAndGiftPredicatesIgnoreDiscretionaryChoiceButRequireRelevantValidFacts` — UNEXECUTED.
- `sealAndCandidateCollectionsCannotBeMutatedAfterEvaluation` — UNEXECUTED.

The cases cover genuine old serializer byte/hash comparison; exact old ctor omission; old ordinary/M1/USER/GUEST and pending plan; schema1 quote-vs-plan separation; display title/list mutations outside economic/hash authority; valid new zero/AUTO/NONE; null/unknown/missing/mixed/duplicate/coerced JSON; invalid options; raw frozen aliases with canonical shares; wrong original party/group/choice/activity/amount/hash; exact nullable positional tuple; immutable nested collection snapshots; goods overflow/order/aggregation; strict goods JSON; objective owner cut/window/scope/mandatory price/gift facts. This is compiled test source, not evidence that these assertions passed.

The fixture loader throws if a genuine required phase-B fixture is absent. There is no Disabled/Assumption/missing-fixture skip, fabricated JSON resource, placeholder byte/hash or adjusted expected hash. Full real owner USER/GUEST discount/cash/source and native alias/fence scenarios remain B2/C source integration and unified-B execution.

## Genuine old baseline archive and unexecuted producer

Archive `.superpowers/sdd/beauty-backend/task-68-prechange-serialization`, source baseline `531d2a87b98f1d15e5a002a5100c4545ab6280b9`, document baseline `28ebe9f111ede8363194cd0e42d50005de465a39`. Actual archived files were re-read and SHA-256/byte lengths compared to the manifest; all eight are CLEAN. These are archived copies, not newly compiled target classes.

| Archived relative path | Actual bytes | Actual SHA-256 | Rehash |
|---|---|---|---|
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutEconomicSnapshot.java` | 1651 | `f7efd1585e981c91393a09e2007319c9619d7a847facfb0dfcd856705eb48cae` | CLEAN |
| `backend/shop-core/target/classes/ai/neargo/shop/trade/order/service/create/CheckoutEconomicSnapshot$Evidence.class` | 2686 | `9cf608ec9bbe34f232b2bc6f71e3dc342092e8a49482f0438661e95fc69fe859` | CLEAN |
| `backend/shop-core/target/classes/ai/neargo/shop/trade/order/service/create/CheckoutEconomicSnapshot.class` | 6645 | `bf12e58cd2285f53ee385995905cff8281c076b952b8fe281f1c8e7960f5a922` | CLEAN |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CounterOrderPlan.java` | 3461 | `804eaf59ad03ceb623b9b0a05441a43a22db2b17aea9a0a807be1dc56842eb33` | CLEAN |
| `backend/shop-core/target/classes/ai/neargo/shop/trade/order/service/create/CounterOrderPlan$CompensationEvidence.class` | 3412 | `09e73fd407fa7825e6fc154490deee67daef24b82216a9dbec3d532badd50d41` | CLEAN |
| `backend/shop-core/target/classes/ai/neargo/shop/trade/order/service/create/CounterOrderPlan$PreparedCommand.class` | 3534 | `865b2219ef63117da3e6f3e5559ef3925a3977654a3573da336c575ff4102065` | CLEAN |
| `backend/shop-core/target/classes/ai/neargo/shop/trade/order/service/create/CounterOrderPlan$SelectorProof.class` | 2711 | `e85a8697ce7c1907f719340ec73cce89440a96916ecfcc0c28a2d53bb13da2be` | CLEAN |
| `backend/shop-core/target/classes/ai/neargo/shop/trade/order/service/create/CounterOrderPlan.class` | 5455 | `4ca1e2c19ddb6dcb3a43440cd9e00b4db24c3cc245466acef709d2e16bdd8134` | CLEAN |

The compatibility test contains complete public static phase-B entry points:

```java
public static void generateBaseline(ObjectMapper applicationJson, ObjectMapper legacyJson,
        Path archive, Path output) throws Exception;
public static void verifyBaseline(ObjectMapper applicationJson, ObjectMapper legacyJson,
        Path fixtures) throws IOException;
```

Producer requires a fresh output directory, rehashes every archived input again, loads only the two original serializer classes and their nested classes child-first from archived target/classes (no current-class fallback for those names), constructs actual old18/evidence/plan instances reflectively from typed ordinary/M1/USER/GUEST/plan inputs, calculates the existing unchanged hash tuples using the old money object, serializes with the supplied actual application and legacy mappers and rejects differing bytes. It emits exactly ordinary-v1.json, m1-v1.json, m2a-user-v1.json, m2a-guest-v1.json, m2a-pending-plan-v1.json and hashes.json. The verifier compares both supplied mapper variants against genuine bytes/SHA/old plan hash and counter tuples. Neither entry point has been invoked. The six resource paths do not exist yet and remain DEFERRED_BASELINE_BYTES_PHASE_B. No old serializer was invoked now.

## Actual allowed commands and compile evidence

Read-only git status/show/diff, bounded rg/sed/cat source inspection, Python deterministic source authoring/static SHA/style/inventory checks and javap API/class inspection were used. No Java product/test class, serializer, runner, SQL, native/container, application/HTTP/network/deployment/migration or ablation was executed.

Four runs of exactly the authorized command occurred as authored source/static-review changes required recompilation:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f /private/tmp/task66-complete-reactor/pom.xml \
  -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -Pbeauty-embedded \
  -pl :shop-app -am test-compile -DskipTests
```

Each command output was redirected to the distinct bounded log below. Every run returned exit0 / BUILD SUCCESS; no ERROR, surefire phase or Tests run output occurred. No Maven install or stale installed owner provider was used. The reactor resolves actual current worktree sources, and only compile/testCompile phases ran.

| Log | Actual result | Elapsed | Finished | SHA-256 |
|---|---|---|---|---|
| `/private/tmp/task-68b1-test-compile-1.log` | exit0 / BUILD SUCCESS | 43.967 s | 2026-10-26T14:16:33+08:00 | `6b901c20553df54e73083666838501ec70293126fa76747b789197af8e315488` |
| `/private/tmp/task-68b1-test-compile-2.log` | exit0 / BUILD SUCCESS | 45.426 s | 2026-10-26T14:20:45+08:00 | `6f35c2fcddfc106ce65cbf45d63219c0fe8182ce9798e6f39513efabc2b4119b` |
| `/private/tmp/task-68b1-test-compile-final.log` | exit0 / BUILD SUCCESS | 48.115 s | 2026-10-26T14:23:31+08:00 | `081e12a6050f3c44badf8ad01577764e15d31b2bcc0e195ae9e82a3f5157e55d` |
| `/private/tmp/task-68b1-test-compile-freeze.log` | exit0 / BUILD SUCCESS | 5.273 s | 2026-10-26T14:24:00+08:00 | `d69864961fded8e58dec19300ef62b755b09ea8698f55f60e572632d40cff7c7` |

Runs1/2/3 compiled actual **shop-base313 main +21 test**, **shop-core1070 main +88 test**, **shop-app413 main +750 test** sources. Third run includes all final functional source and seventeen tests; fourth freeze run followed only the last line-width wrap, recompiled actual88 core test sources and reported base/core main/host main+test up to date. All24 selected reactor modules report SUCCESS in each run. Existing annotation-processing/deprecation/unchecked compiler notices remain in the logs; no runtime status is inferred.

## Static self-review and limits

- Reviewed exact released method/record inventory and source boundaries. There are seven pure shared statics, complete fail-closed defaults and no placeholder helper. The new SPI imports only JDK/Jackson/common errors/SPI, marketing Rules imports its own owner entity and SPI, codec imports only its own trade types and SPI across domains. No new private-domain edge or known-* exemption exists.
- Reviewed raw owner alias retention versus canonical applied/share binding, exact case-sensitive public choices, frozen threshold-null semantics, explicit nullable tuple positions and checked aggregation. No clock, member/session/stock/pay lookup or owner repository/SQL exists in SPI/codec/Rules. Rules consumes explicit already-scoped owner facts; live IO remains B2.
- Reviewed old18arg delegation/property-only null omission/component order/Evidence/paidShares and unchanged CounterOrderPlan/CheckoutDigests. Old byte/hash compatibility is **not runtime-proven** before genuine phase-B generation/comparison.
- Reviewed strict raw shapes, duplicates/trailing/coercion, schema0 absence versus schema1 nonnull seal, bare-plan separation, zero funding, names/display exclusion and reconstruction of the exact existing outer quote tuple. Stored defects map to CONFLICT. Codec does not authorize client-provided seals or prove present owner eligibility.
- Static scanner over all nine owned sources found no tabs, wildcard imports or lines >120. New code uses explicit imports and public why-Javadoc. Existing CampaignPort fully-qualified legacy references remain byte-unchanged; no new fully-qualified type/annotation reference was introduced. `git diff --check` returned exit0 with no output.
- Root owns all TDD/paired metadata/ER/ledger/review publication and subsequent B2/C. No whole-feature completion, passing test, full build matrix, native collation/fence correctness, real discounted cash/source chain or runtime serializer compatibility is claimed.

Source freeze: the nine Java SHA pins above are final for root independent review. No unresolved prerequisite source/type/harness path gap was encountered. Subsequent integration and execution must use their own reviewed scope and phase gates.


## Fix round1/5 — scoped R0 I1 correction

Status: **DONE_PENDING_SCOPED_REVIEW**, 2026-10-26. This append supersedes the initial compatibility-test source pin, seventeen-case count and original source-freeze statement only. Other eight B1 sources remain byte-identical to the root-provided `task-68b1-r0-base/manifest.json`. All tests/serializers/runtime checks remain UNEXECUTED; current total is eighteen authored tests (ten Rules, eight Compatibility).

Read fix1 brief and full `review-task-68b1-r0.md`, then confirmed I1 in the exact compatibility helper: explicit schema1 economics `{E1:A1}` were paired with `command()`'s empty choices and a hardcoded USER party. The codec correctly rejects that contradiction before the original plan/display assertions. No codec or other production source was changed.

Changed only `CounterEconomicCompatibilityTest.java`:

- `plan(CheckoutEconomicSnapshot)` now obtains schema1 party and original requested choices from the supplied seal and calls the existing command's `withChoices` method. A seal-absent old plan still uses the exact original USER/empty-choice command branch. The original explicit-choice display/hash test remains intact.
- Added authored `schemaOnePlansPreserveExplicitPartyAndRejectContradictoryCommand` for both USER and GUEST: asserts exact typed party and `{E1:A1}`, authored bare-plan write/read equality and existing plan hash equality, then changes only the original command to empty choices and requires CONFLICT. These assertions are UNEXECUTED. Static path review confirms matching party/choice reaches the intended plan hash checks, while contradictory choice reaches the existing plan-choice comparison.
- Compared the root R0 archived source sections to current source: `command()` and the complete `generateBaseline(...)` producer body are byte-identical. The old helper's null-seal branch retains the same USER, empty choices and stock fields, so genuine baseline construction remains unchanged in meaning. No golden bytes were generated, rewritten, hand-adjusted or skipped.

Actual permitted verification command, executed once for this fix:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f /private/tmp/task66-complete-reactor/pom.xml \
  -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -Pbeauty-embedded \
  -pl :shop-app -am test-compile -DskipTests
```

Log `/private/tmp/task-68b1-fix1-test-compile.log`: exit0 / BUILD SUCCESS, 6.193s, finished `2026-10-26T14:38:18+08:00`, 38125 bytes, SHA-256 `7ab14d0f219117153699c36331780dd89a4d8042596fcc70da55f7ede29994f5`. Actual core test compilation: **88 current source files**. Base main/test, core main and host main/test were up to date; no new main-source compilation occurred in this fix. All24 reactor modules report SUCCESS. No ERROR, surefire or Tests run output occurred. No Maven install or runtime/serializer/baseline/SQL/native/app/probe/network/deployment invocation occurred.

Bounded static checks: exactly one Java source differs from R0; all other eight rehash equal the root manifest. Changed source contains no lines >120, tabs, trailing whitespace or wildcard imports. `git diff --check` returned exit0 with no output. Only this report was appended; no extra path, codec relaxation, index/commit/reset or subagent was used.

### Final nine source pins after fix1

| Source path | Final SHA-256 | R0 relation |
|---|---|---|
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/marketing/CampaignPort.java` | `364f3e07adc0fa713e490fd905dd3a065d6508e519691964662aabe889a5f7fc` | R0 byte-identical |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutEconomicSnapshot.java` | `84ffbf1dc58e4ea34edbfa4fa3194d414461ed46fc3700e6c130f76e81ec1237` | R0 byte-identical |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutPricingScope.java` | `fea01078516e7c23434d54a609ffdd375b90705d06e08e8d0be61fe5cb99c6af` | R0 byte-identical |
| `backend/shop-core/src/main/java/ai/neargo/shop/marketing/campaign/CounterCampaignRules.java` | `29a3b0360f77a852f33401ae1360ec683e05aaf981685d3e2863ef27288285a1` | R0 byte-identical |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CounterEconomicCodec.java` | `188aa673d3d3481026b4547f86d07799d1c7bcf13e4bad6815dcab126472efd5` | R0 byte-identical |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CounterQuoteSnapshot.java` | `b645014f34ae17e4d6ddaa80f5b758350e916ba56eee86c224dfed2968631772` | R0 byte-identical |
| `backend/shop-core/src/test/java/ai/neargo/shop/trade/order/service/create/CounterEconomicCompatibilityTest.java` | `0e1d1ef0f09a51d2bc6be14c742c703f59e598c059088079c1270ae8530236ac` | I1-only edit |
| `backend/shop-core/src/test/java/ai/neargo/shop/marketing/campaign/CounterCampaignRulesTest.java` | `f403ce14f7a754d2a01e12f98d3076e5b85ca1e500f320cc26e8809f82077f6d` | R0 byte-identical |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/marketing/CounterCampaignContract.java` | `5280a79001b8338e7ca9827bdf7d9b3aba8295405077aaf61018f56b4e57c48a` | R0 byte-identical |

Self-review disposition: R0 I1's contradictory test setup has been corrected in source while retaining the explicit choice case, typed guest/user contexts and old empty-choice baseline. Runtime proof is deferred to unified phase B as required. Source freeze resumes at the final nine pins above for independent scoped R1 review; no whole-C02 or passing-test claim is made.
