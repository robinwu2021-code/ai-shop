# Task68B shared counter contract and frozen owner membership overlay

Status: DONE_PENDING_REVIEW, 2026-10-26. Design only; not Task68B coding release. This narrow overlay supersedes only the shared-helper placement in task-68-design-addendum.md §2.1/§3.1/§3.2 and the raw owner-scope equality interpretation in §2.3/§3.4. Original documents remain frozen. Selection policy, display envelope, old recovery, admission economics, owner fence and hash tags remain approved. Task68A's separately reviewed fix1 effective-column equality ruling remains binding.

## 1. Actual source boundary

`backend/shop-app/src/test/java/ai/neargo/shop/arch/ArchitectureTest.java#svcModulesMustNotDependOnEachOther` rejects newly introduced private domain dependencies and stale exemptions. The current Router lives in `shop-app/.../portal/port/CampaignPortRouter.java` and already composes marketing and promotion owners. Promotion and trade cannot import `marketing.campaign.CounterCampaignRules`; marketing cannot import `trade.order.service.create.CheckoutDigests`. No known-dependency baseline change is permitted.

Existing `shop-base/.../spi/product/StockDispositionContract.java` demonstrates a pure shared canonical contract; `spi/entitlement/LegacyCouponHistoryFacts.java` already uses injected `tools.jackson.databind.ObjectMapper`, UTF-8 and SHA-256. `CheckoutDigests.hash(ObjectMapper,Object)` uses an immutable writer with `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS`, then lowercase hexadecimal SHA-256 over UTF-8. These are the existing dependencies and algorithm to use, without importing that trade class or changing it.

Task68A's current `CounterCampaignFence#ownerIdentityCollation` and `CounterCampaignFenceMapper#ownerIdentityBindings` prove exactly ten effective tenant/entity column bindings; they do not prove every store column has that collation. The approved R1 review reports I1 ADDRESSED/PASS/APPROVED, with runtime checks still unexecuted. `CampaignPortImpl#bestFullCut` currently SQL-filters entity but Java-compares store; that ordinary method remains untouched. New counter evaluation must implement its own scoped enumeration rather than reuse this best-only helper or copy its Java store equality into alias handling.

## 2. One pure SPI helper: exact API and failure ownership

Create exactly one additional product source:
`backend/shop-base/src/main/java/ai/neargo/shop/spi/marketing/CounterCampaignContract.java`.
It is `public final`, has a private constructor and the following public static methods. Types are the already-approved nested `CampaignPort` types and `ManualServiceAdmissionPort.CustomerParty`; there is no new carrier component.

```java
public static List<MerchantAmount> canonicalGroups(List<MerchantAmount> groups);
public static Map<String, String> normalizeChoices(
        List<MerchantAmount> groups, Map<String, String> choices);
public static List<CounterChoice> resolveChoices(
        List<MerchantAmount> groups, Map<String, String> choices);
public static CounterActivitySeal seal(ObjectMapper json, CustomerParty party,
        List<MerchantAmount> groups, Map<String, String> choices,
        List<CounterRule> selectedRules);
public static String sealDigest(ObjectMapper json, CounterActivitySeal seal);
public static void requireSeal(ObjectMapper json, CounterActivitySeal seal);
public static void requireSelection(ObjectMapper json, CounterActivitySeal seal,
        CustomerParty originalParty, List<MerchantAmount> originalGroups,
        Map<String, String> originalChoices, Discount originalSelected);
```

No Spring bean, clock, SecurityUtils, owner entity/mapper, SQL, permission, current eligibility, member query, price allocation or `CheckoutEconomicSnapshot` dependency belongs in this helper. It accepts the caller's mapper and never constructs/configures a global mapper. It may import only JDK, existing Jackson, common error types and SPI types.

`normalizeChoices`/`resolveChoices` preserve approved input errors: malformed/null keys or values and conflicting aliases are BAD_REQUEST; well-formed unknown/foreign keys, including foreign NONE, are ACTIVITY_CHOICE_UNAVAILABLE. Reference validation exactly matches CheckoutPort's nonnull/nonblank/max64/no-strip-change rule, with case-sensitive matching. Values are exact activity references or reserved NONE. Canonical group defects are internal contract failures (`IllegalArgumentException`), not guessed client scope. The seal methods, arithmetic overflow, digest/shape mismatch and selection mismatch also fail `IllegalArgumentException`; they never return null/empty success. When validating stored facts the codec translates failures to CONFLICT; owner construction/serialization defects translate to CHECKOUT_DEPENDENCY_UNAVAILABLE. At live final verification a valid original followed by changed owner facts maps to CHECKOUT_QUOTE_STALE; genuine owner/fence/parse dependency failure remains CHECKOUT_DEPENDENCY_UNAVAILABLE. Stored invalid choices are caught and mapped to CONFLICT by the codec, not leaked as a fresh request error.

`normalizeChoices` returns an immutable sorted TreeMap copy retaining the accepted exact keys, including equal entity/store aliases; it does not collapse or rewrite command bytes. `resolveChoices` returns one canonical group choice: omitted → AUTO/null activityNo; NONE → NONE/null activityNo; explicit → EXPLICIT/exact requested activityNo. Store precedence, equal aliases accepted, contradictory aliases BAD_REQUEST and ambiguous identity rejection remain as approved. It resolves against actual groups even with zero candidates.

`canonicalGroups` requires exactly one nonnull actual group in this lane, valid canonical entity/store references, positive goodsAmount/goodsQty, nonempty goods lines, pointsRewardsEnabled=false. Each line has a valid goods reference, amount >= 0 and qty > 0; aggregate duplicate goodsNo with exact long/int addition and sort by natural case-sensitive goodsNo order. Require sums equal the group's goodsAmount/goodsQty. Return deeply copied immutable data. Group/choice ordering is entity then store; selected-rule ordering is raw entity/store/activity, natural Java string order with null stores first. This defines the approved ordering despite the current one-group/at-most-one-selected-rule bound; it does not add multi-group support.

Move planned `normalizeChoices`, `resolveChoices` and `sealDigest` out of marketing `CounterCampaignRules` into this SPI helper; do not retain duplicate implementations. Marketing's helper keeps strict goods JSON parsing, owner-local predicates and evaluation only, and calls these SPI methods for protocol construction/checking. Trade codec and promotion call this same helper directly. There is no fallback copy of the resolver or digest in either domain.

## 3. Exact seal encoding and validation

`seal` canonicalizes groups/choices and selected-rule list, derives resolvedChoices, fixes schema=1 and policy=LEGACY_FULL_CUT_V1, validates the supported frozen shape, and returns the same approved CounterActivitySeal with its computed digest. `sealDigest` validates shape excluding digest and hashes this positional expansion; `requireSeal` additionally requires the stored canonical shape and the exact recomputed 64-character lowercase digest. A persisted noncanonical list is rejected rather than silently normalized on read.

The positional expansion of the approved `COUNTER_ACTIVITY_SEAL_V1` contract is:

```text
[TAG, schema, policy, partyTuple, groupTuples, requestedChoices,
 resolvedChoiceTuples, selectedRuleTuples]
partyTuple = [kind, userNo, guestNo, identityRevision]
groupTuple = [merchantNo, goodsAmount, goodsQty, storeNo, goodsLineTuples,
              pointsRewardsEnabled]
goodsLineTuple = [goodsNo, amount, qty]
resolvedChoiceTuple = [entityNo, storeNo, mode, activityNo]
selectedRuleTuple = [tenantNo, activityNo, entityNo, storeNo, ruleVersion,
 type, status, deleted, thresholdMinor, discountMinor, startAt, endAt,
 goodsNos, appliedMinor, funder, platformMinor]
```

TAG is exactly `COUNTER_ACTIVITY_SEAL_V1`. All tuple fields follow the declared record order. Use null-supporting lists such as Arrays.asList, not List.of for nullable positions; null USER revision, null AUTO/NONE activity, null whole-entity store and nullable threshold retain explicit JSON null positions. requestedChoices is the sorted map, never an iteration-order-dependent array. Serialize this tuple with `json.writer().with(ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(tuple)`, hash its UTF-8 bytes using MessageDigest SHA-256, and HexFormat lowercase output. No extra prefix/separator, evaluation time, display name, fence revision, cashier or current owner observation enters it. This specifies the positional expansion already required by §3.1; no preexisting deployed schema1 bytes are being migrated.

`requireSeal` rejects null/unknown schema or policy, invalid typed party (including guest revision), null collections/elements, duplicate/missing groups or choices, more than one selected rule, inconsistent resolution, invalid digest and arithmetic overflow. NONE and empty AUTO require no selected rule; EXPLICIT requires exactly one whose activityNo equals the requested reference byte-for-byte. A nonempty AUTO has exactly one selected rule. Rule activityNo is a strict public reference and cannot be NONE. Selected rule goodsNos is the canonical empty list; type FULL_CUT, status RUNNING, deleted=0, ruleVersion>=0, funder=MERCHANT, platformMinor=0, discountMinor>0, appliedMinor>0 and less than group gross are required. Threshold null means zero; eligibility uses the frozen threshold value and existing capped `min(discountMinor, goodsAmount)` calculation, not a newly invented positive-threshold requirement. Selected start/end are nonnull, ordered inclusive timestamps; consumed checks never compare them with the later clock. Raw owner scope handling is §4.

`requireSelection` first calls requireSeal, then exactly compares originalParty, canonicalGroups(originalGroups), normalized originalChoices and recomputed resolvedChoices with the seal. It validates the supplied originalSelected against the binding in §4: zero has Discount.none; positive has one share/applied with matching total/amount/rule and canonical group, qty1/platform0/enrollment null. Applied name is non-authoritative in this pure owner-boundary comparison; the new economic codec separately requires names null, and only quote promotions retain names. This helper does not prove fresh owner eligibility or rank candidates.

CounterEconomicCodec retains its strict raw JSON tree checks before typed conversion: exact envelope/plan/economic/seal/record property sets, required properties and types, no unknown or duplicate object keys, explicit-null/missing-seal rejection for new shapes, schema/policy validation. It calls requireSeal, then requireSelection with the economic activity and original evidence-derived groups/choices at the appropriate existing consumer. Typed records alone cannot detect fields Jackson would otherwise ignore. The codec continues owning envelope/display checks, old zero classification, quote hash, plan hash and original-line evidence. Neither this helper nor a valid SHA digest is authorization to accept client-supplied seals; only trusted owner-produced durable quote/plan facts enter these paths.

## 4. Frozen alias-to-group membership without another field

There are two distinct comparisons. Public keys/references and canonical order context remain case-sensitive. Stored owner tenant/entity/store membership is established by the applicable SQL column predicate at the fenced evaluation. Never lowercase, trim or accent-fold either side, and never re-filter a SQL-matching tenant/entity/store with Java equals. A's ten-binding proof protects tenant/entity fence contention; store membership uses the actual store column's SQL equality and is protected by the already-held entity fence, without claiming store was one of those ten bindings.

The new marketing evaluator uses `tenant_no = MAIN`, `entity_no = originalGroup.merchantNo` under the active A fence. For store membership, include the existing null/Java-isBlank whole-entity case and rows matching `store_no = originalGroup.storeNo` using the database column predicate. Exact implementation within the existing BaseMapper inventory: obtain the tenant/entity head set once and the same set restricted by SQL store equality once; retain a head only when its raw store is null/blank or its primary-key id is in the SQL store-matching set. Deduplicate by actual primary-key id, never by normalized scope strings. Both uncached current reads are in the same fenced RC transaction. This preserves all existing blank forms without guessing that SQL TRIM implements Java isBlank. Do not reuse ordinary bestFullCut or issue one query per row. The promotion counter probe follows the same SQL scope principle for its own heads/enrollment context; existing objective effects and parent/link rules remain unchanged. A collation lookup failure is never an empty candidate result.

A legitimate row such as raw tenant `main`, entity `qe-x`, or a store alias equivalent under its actual column collation may therefore bind to canonical group MAIN/QE-X/S-1. Keep CounterRule.tenantNo/entityNo/storeNo exactly as read, including case/accent/trailing spaces and null-versus-blank whole-entity store. These raw stored scope facts are nonblank tenant/entity strings, tenant max32 and entity/store max64; store may be null/blank. They are not passed through the client no-strip-change validator. The client cannot use `qe-x` as a choice alias for canonical `QE-X`; that still fails scope. Explicit activity references likewise must equal the stored candidate activityNo exactly, even if a broad SQL lookup would match another spelling.

Existing fields already carry an unambiguous relation in this one-group lane:

| Frozen field | Meaning |
|---|---|
| seal.groups[0] | Canonical original priced group, real entity/store/goods/amount/qty |
| seal.resolvedChoices[0] | Exact canonical entity/store plus resolved mode and explicit activity reference |
| economic.activity.applied[0] | Exact raw selected activityNo, but merchantNo/storeNo from the canonical group; frozen amount/qty/funding |
| economic.activity.shares[0] | Canonical group entity/store and the same merchant amount |
| seal.selectedRules[0] | Exact selected raw owner rule facts; its activityNo must equal applied.activityNo |

Only zero-or-one selected rule is permitted. Membership of that selected rule in the sole group is the owner-produced attestation; no ambiguous list-position matching across multiple groups exists. Candidates and selected AppliedActivity/MerchantDiscount are constructed with canonical group coordinates, never raw owner entity/store strings. Rule scope retains the raw facts separately. `seal` does not rediscover collation equality; owner evaluation has already established it. There is no missing membership field, new identifier/ledger, or permission to seal arbitrary unscoped rows.

At initial/final evaluation, the owner repeats those real SQL predicates under the reviewed fence and time policy. Changed raw selected facts/version/amount or canonical membership change the seal and stale the quote; empty/NONE/new unsupported rules keep their approved handling. At consumed admission, §3.4's “matching entity/exact store scope” means this frozen canonical membership relation, not Java equality between raw rule strings and canonical order strings. Codec/admission require one canonical group/choice, exact original prepared context and aggregation, applied/share binding, matching activity identity, digest, outer quote hash, consumed confirmation, typed party, actual order allocations and paid shares. Raw tenant/entity/store are retained and hash-checked, not reinterpreted against current marketing tables or current collation. Actual op/quote/confirmation/parent/child tenant MAIN checks remain unchanged; the alias exception applies only to raw CounterRule owner scope facts. Post-sale rule edit/delete/expiry does not revoke a completed sale's admission. M1/old M2a retain strict zero and original recovery/security checks.

## 5. Caller graph and exact inventory delta

```text
trade OrderServiceImpl -> SPI CampaignPort -> portal CampaignPortRouter
  router: existing A fence + one owner time; SPI canonical/choice methods
  -> promotion ActivityPricingServiceImpl: SPI choices + own objective SQL probe
  -> marketing CampaignPortImpl.evaluateCounter: own scoped owner enumeration
       -> marketing CounterCampaignRules: strict goods/owner predicates
       -> SPI CounterCampaignContract.seal: frozen protocol evidence
trade codec/guard -> SPI CounterCampaignContract.requireSeal/requireSelection
trade admission -> trade codec + original consumed quote/confirmation/order facts
```

Router remains the only cross-owner composition layer; it may use the existing marketing fence. Direct marketing SPI entry points retain their approved local fence behavior. No promotion-to-marketing private call is added for canonical choices, digest or locking. Final Router/owner verification validates original frozen evidence with requireSelection, evaluates current owners once at the approved final boundary, and compares authoritative selected economics/seal; transient promotions are excluded. Codec owns trade hashing through existing CheckoutDigests. Marketing calls SPI sealDigest, never CheckoutDigests. There are no new public DTO/HTTP signatures and no change to the already-approved CampaignPort overload signatures.

Root's draft inventory changes from 15 modified + 7 created product/test sources to **15 modified + 8 created = 23**: add only CounterCampaignContract.java to create. Existing planned CounterCampaignRules, CounterEconomicCodec, CampaignPort, Router, CampaignPortImpl, ActivityPricingServiceImpl and admission/guard implementation consume this overlay. No additional mapper, test class, schema, migration, dependency or architecture-baseline source is required. Root alone republishes inventory/paired metadata after independent design review; this task changes neither.

## 6. Compatibility and finite source checks

The helper is invoked only for new schema1 counter facts. Existing 18-argument CheckoutEconomicSnapshot constructor and property-only null omission remain exact; no new record component beyond the approved nullable seal. Old ordinary/M1/M2a JSON and CounterOrderPlan constructor/hash remain unchanged. New plan economics stay bare; quote promotions remain only in the existing envelope and outside seal/quote/plan hashes and final equality. Baseline serializer capture/comparison remains deferred to unified phase B, using the root-pinned actual prechange source/classes; no serializer invocation precedes coding.

Extend the already-inventory test sources, not a new framework:

- `CounterCampaignRulesTest`: shared resolver for canonical entity/store equal aliases, conflicting/foreign/case-variant keys including NONE; deterministic canonical goods aggregation/order and explicit null tuple fields; rule shape/digest rejection; raw-scope alias retained with canonical one-group applied/share mapping. Unit construction checks protocol only, not live collation proof.
- `CounterEconomicCompatibilityTest`: typed and raw JSON unknown/missing/null/duplicate-field negatives; wrong party/group/choice/activity/amount and multiple selected-rule negatives; exact old constructor/JSON/hash coverage; frozen raw aliases roundtrip; display-only mutations leave authoritative hashes unchanged. Baseline byte production and all execution remain phase B.
- `ManualCounterDiscountIT` with existing `ManualCounterDiscountFixture`: add `storedOwnerAliasPreservesCanonicalCounterMembership` using real USER/GUEST customer/owner/catalog/stock flows, real alias campaign rows under the native fixture's actual column collation, canonical choices and 10000−1000=9000 allocations. Assert owner→seal raw facts→canonical selected shares→consumed confirmation→cash confirm→ADMITTED source. Repeat source/read after owner mutation/delete; no current marketing lookup repairs or blocks the consumed result. Include real case/accent/trailing-space aliases where that fixture collation equates them, a distinct foreign entity/store exclusion, case-variant client key rejection, explicit raw activity reference mismatch rejection, and tampered frozen group/activity/consent negatives. Derive/check actual equality in the existing native fixture rather than pretending Java lowercasing is a database proof. Final selected-rule alias mutations before consumption remain stale; afterward unchanged frozen admission remains valid.

All tests are future source/compile work in phase A and execution in unified phase B. The existing architecture test is the boundary gate in that unified run; do not weaken its baseline. This preflight ran no product, serializer, build, test, native, SQL, migration, network or probe command.
