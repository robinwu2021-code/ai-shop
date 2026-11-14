# Task68B2 actual counter pricing and consumed-cash integration report

Status: **DONE_PENDING_ROOT_REVIEW**. Source/compile phase A only. All seventeen scenario methods, genuine old producer/bootstrap, serializers/goldens, native database/fence/stock/cash observations and phase-B comparisons remain **UNEXECUTED**. No whole-C02 business/runtime completion or deployment claim is made.

Sole worktree `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`, branch `codex/beauty-foundation`, coding BASE `c13e83533477c80218ec94ec549c823697ead8c5`; reviewed B1 code prerequisite `419e9d94fa5618d8783abcdb847201c05fa1d525`. Read B2 brief/inventory/preflight, complete original/shared addenda, A fix1 overlay, B1 actual API/report and Java/CLAUDE standards. Main user phase-A override governs verification: only authored test source and current-source offline test compilation, no per-slice test execution. Root owns TDD/JSON/generated documents/gates/ER/ledger/C scripts and independent review.

## Exact source freeze

Exactly **12 modified + 2 created Java paths = 14**, plus this report. All twelve HEAD baseline SHA-256 values were rehashed and exactly match the released inventory. No extra source or B1 API gap was found; no B1 source was edited. Root-owned preexisting/ongoing `progress.md` and TDD edits are excluded from this worker scope. No subagent, index/staging/commit/reset, original restaurant change, POM, migration, schema, known-* exemption or global mapper change occurred.

| Action | Exact source path | Bytes | Final SHA-256 |
|---|---|---:|---|
| modify | `backend/shop-app/src/main/java/ai/neargo/shop/portal/port/CampaignPortRouter.java` | 9191 | `fb2c388ccccc4325f6a9cd590c4bdc45e5c0d4dd87ced5ac3ab94e7259de851c` |
| modify | `backend/shop-core/src/main/java/ai/neargo/shop/marketing/port/CampaignPortImpl.java` | 22521 | `f4bbe3c72e93f583e25228d376cba0e88af0d1dbf6955f986f88675027d6f121` |
| modify | `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/ActivityPricingService.java` | 4644 | `949284a691f99d4ac6b843791df1e849c4b6b17e3727309004eb332998b0fa88` |
| modify | `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/impl/ActivityPricingServiceImpl.java` | 63281 | `a3c873922a53d7d1208f6db265cffd06816045346605b4edf9a5e8d0eca48461` |
| modify | `backend/shop-core/src/main/java/ai/neargo/shop/product/port/GoodsQueryPortImpl.java` | 26971 | `a3ab36af5f70f28b1ba45d68a842dbcdcc2ea16f099dac54e4983d4c9f6976b1` |
| modify | `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java` | 104223 | `5372d8a2f37a93bfe8767ca2a623bedb75eb562bfac3470d7ade0644377123ae` |
| modify | `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/CheckoutOrderWriter.java` | 1798 | `92ee35239d22e067d5efc2116b9e4ac950b4f783a0ddc31e06c0bfb43923123c` |
| modify | `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/ManualServiceAdmissionServiceImpl.java` | 81318 | `5dfc51e4c11278954b07c04c7c0261ab2468da3dfdf013cd5e5dea67dda236b1` |
| modify | `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java` | 298393 | `15f8c7b4b3187c4533a0b1b57442af86b24c90bca8b2957458f8ec690a585637` |
| modify | `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutQuoteGuard.java` | 21467 | `9d822096ac5c47fff482ca1801ccdde6417e6c2c4e5bfc6773cf34cfc7a4789a` |
| modify | `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/impl/CounterOrderPlacementServiceImpl.java` | 42372 | `40e7389da9508077813e09dc8fd1825dfbef7181cadb87fcd8c79fd640dfb7a1` |
| modify | `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/impl/OrderCreationRecoveryServiceImpl.java` | 22990 | `78d408d5c8456a10dd17cdf95f27550f346d29c4f7c167be4a7c216f257f7c0d` |
| create | `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java` | 44498 | `70bcc0c441b296f0d822b58bf88d3757cc64d8b65c7ff6e7c7ca463a69cbf268` |
| create | `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountFixture.java` | 20685 | `225b36ff58b741fde8d49eccdc14f4ba35172deb95a01ab37dcebb26c235d98e` |

These fourteen pins are frozen for independent root review; no further source edits are planned by this worker.

## Actual producer/provider methods

- `CampaignPortRouter.counterCandidates(CustomerParty,List<MerchantAmount>)` now projects the explicit overload with empty original choices. `counterCandidates(CustomerParty,List<MerchantAmount>,Map<String,String>)` acquires the actual legacy owner's A fence, captures one fresh server wall time after that wait, invokes the promotion objective probe with the same choices/time and the legacy evaluation with that same time. `requireCounterSelection(CustomerParty,List<MerchantAmount>,Map<String,String>,CounterActivitySeal,Discount)` validates original frozen evidence, reevaluates both owners and compares the selected seal; changed supported/unsupported eligibility maps to stale, genuine dependency failure stays unavailable. Ordinary router methods remain unchanged.
- `CampaignPortImpl.lockCounter(List<MerchantAmount>)`, `evaluateCounter(CustomerParty,List<MerchantAmount>,Map<String,String>,long)`, `requireCounterEvidence(...)`, `requireSameCounter(...)`, both counter candidate overloads and the complete final selection overload are implemented in the existing owner. `currentCounterReads()` clears the actual transaction-bound MyBatis session cache before uncached RC owner enumeration. Exactly one tenant/entity head query and one SQL store-matching query produce primary-key membership; null/Java-isBlank whole-entity rows stay eligible without Java-equality filtering of SQL aliases. Candidates use canonical group coordinates; selected rule facts preserve raw tenant/entity/store/version/window/threshold/discount. Stable ascending primary-key order breaks equal AUTO amounts; all eligible supported candidates, including non-best, are returned. Shared SPI alone resolves/normalizes choices and creates/verifies the seal. Positive selected free orders are rejected before sealing, without fallback. Inactive unrelated rules are skipped before goods parsing; relevant malformed goods fail unavailable.
- Legacy two-argument selection remains zero-only. `commitCounter` validates structural merchant-only selected allocations and the bound active transaction, then retains the legacy no-op. It does not reuse the insufficient selected-only validator for a positive discount and never calls pmt commit/apply.
- `ActivityPricingService` adds the exact time-bearing choices overload and group/time mandatory price-effects overload. `ActivityPricingServiceImpl` implements them plus `probeCounter`, `requireCounterEffect`, `counterComboEffect`, `counterPositive`, `publishedCounterEffect` and its own `currentCounterReads`. Actual merchant heads use two SQL scope queries/PK membership. Approved platform enrollment uses actual SQL entity membership and enrolled goods, with no audience/member/quota/capacity reads. Legacy schedules/triggers, strict combo goods/amount/quantity/benefits and published exact-version definition/schedule/recurrence/store scope are evaluated objectively. Existing owner-local `ActivityRuleValidator` rejects malformed published definitions as dependency-unavailable before scope can be mistaken for irrelevance. NONE/explicit opt out of discretionary unsupported offers; mandatory PRICE/GIFT/FLASH/BUY_GIFT remain blocked. The old Collection probe is retained conservatively without audience/quota filtering. Ordinary candidate/price/gift/combo algorithms remain unchanged.
- `GoodsQueryPortImpl.snapshotForCounter` uses the existing SKU/store snapshot with an empty promotional price overlay. Both actual `OrderServiceImpl.split` passes already call this provider for counter parties. Ordinary/M1 snapshot paths retain their original promotional price behavior; no alternate beauty price engine exists.

## Actual trade writer/reader and policy integration

`CheckoutOrderWriter.quoteManualFor` and `OrderServiceImpl.quoteManualFor` now return `CounterQuoteSnapshot`. The sole prepare caller is updated. `OrderServiceImpl.CreatePricing` adds transient `counterPromotions`; `counterDiscounts(CustomerParty,List<MerchantAmount>,Map<String,String>)` returns `CounterCandidates`. Existing Discounts/LineShares allocate the selected merchant cut. All eligible payable supported options plus exactly one untitled NONE come from that one evaluation, including non-best and NONE requests. Economic applied names are null for new sealed counters; display stays outside quote/plan/seal hash and final equality. Both hash-money and returned economic copies carry the same seal.

`CheckoutServiceImpl.prepareCounter` permits explicit activity choices, sets CURRENT_FULL_CUT, stores exactly one `codec.writeQuote(snapshot)` and its economic quote hash. Its counter acceptance path uses the strict codec. `view` dispatches only already-proved MERCHANT_RECORDED_IN_PERSON authority through `readQuoteSnapshot`; readCounterQuote/counterReplay therefore use stored display without live fence/owner queries. Ordinary/M1 view and confirmation readers retain their direct economic decoding and selected-only display; ordinary coupon/freight/positive records are not routed through strict counter classification.

`CheckoutQuoteGuard.counterContext` locks original operation/quote/consent, decodes the original quote, verifies its original party/lines/hash and derives FROZEN_M2A_ZERO only from a genuine bare zero quote plus original PreparedCommand. No client request flag selects old policy. Schema1 uses the full preliminary campaign guard before catalog waits. Both trusted policy and original economics stay in internal CheckoutPricingScope. Final `requireCounterMatches` decodes/checks the same original authority, repeats fresh full owner validation after actual pricing/catalog waits for schema1, and compares the complete original economic snapshot to actual base-derived economics. Old schema0 bypasses only new marketing policy while retaining actual current base/catalog/security/stock and original hash/equality checks.

`CounterOrderPlacementServiceImpl` routes every former direct CounterOrderPlan read (recovery selection, compensation, release and requirePlan) through the codec and uses its plan hash. `OrderCreationRecoveryServiceImpl.registerCounter`, prior-plan recovery and reservation recording likewise use codec write/read/hash. There are zero direct CounterOrderPlan.class reads remaining in these two classes. Existing created-order fast paths, stable order/suborder/intent/reservation/manifest identities, outside-root real stock IO, compensation and retry protocol remain. A staged uncreated plan still passes final fresh policy; a created order recovers original facts.

Shared ordinary `CheckoutQuoteGuard.context/requireMatches`, noncounter recovery serializers/hashes, ordinary OrderService consumer-source verification and M1 reads remain their existing paths. Common economics carries a null optional seal in ordinary/M1/old zero flows, preserving the existing omission contract. Byte serializer compatibility remains phase-B evidence, not a compile claim.

## Consumed admission exception

`ManualServiceAdmissionServiceImpl` injects existing ConfirmationMapper in its constructor and adds exactly the local economic/declaration checks in the approved owner file: `requireAdmissionEconomics(...)`, `requireDeclarationEconomics(...)` and `alive(BaseEntity)`.

The unconditional child merchant-zero clause moves into lane-specific validation after actual consumed quote/source/item loading. Noncounter/M1 and old schema0 remain strict zero; consumer envelope/seal/malformed JSON is explicitly mapped to CONFLICT without applying the counter codec to valid ordinary/M1 money. Only a validated schema1 counter quote permits its frozen merchant discount. It proves original consumed confirmation (nonlocking BaseMapper read), typed party/namespace/actor/account/order/quote/hash/revision/timestamps and exact prepared lines/choices/lane; validates the original quote hash through B1 codec; checks nonnull parent/child gross/discount/freight/platform/merchant amounts, exact one-to-one source/item/economic lines and checked aggregate paid shares. No present owner/clock/collation eligibility is consulted after consumption.

Current financial/after-sale/refund/gift/points/fulfillment/auth restrictions remain. Schema1 same-key declaration replay revalidates original facts/declaration; schema0/M1 replay retains its old behavior. Source compares declaration economics inside its existing BizException -> UNKNOWN/no usable lines boundary. The complete `sourceView` method, including MANUAL_SOURCE_V1 and MANUAL_COUNTER_SOURCE_V1 hash tuples, is byte-identical to HEAD. Existing stock progress/recovery and manual business-paid events remain; no ordinary financial-paid/Pay path is introduced.

## Authored finite IT methods — all UNEXECUTED

`ManualCounterDiscountIT` directly extends CheckoutFlowFixture and composes ManualCounterDiscountFixture. It does not inherit any historical @Test methods. Origins are actual current router/legacy/promotion/product/checkout/order/admission/original-stock providers. Successful paths use real owner rows/requests/stock; spies only invoke real implementations, assert forbidden IO, introduce bounded latches, or authorized negative faults. No successful promotion/stock/cash reply is fabricated.

- `merchantFullCutSealsSameSharesForUserAndGuest` — **UNEXECUTED**.
- `explicitNonBestNoneOmissionAndChoiceKeys` — **UNEXECUTED**.
- `counterOptionsRemainFrozenAcrossReadAndOriginalReplay` — **UNEXECUTED**.
- `unsupportedOffersNeverEnterCounterOptions` — **UNEXECUTED**.
- `accountCouponPointsAndPromotionChoicesAreRejected` — **UNEXECUTED**.
- `unsupportedAutomaticOfferIsNotSilentlyDropped` — **UNEXECUTED**.
- `nonemptyGoodsScopeAndMalformedRuleFailClosed` — **UNEXECUTED**.
- `changedMerchantDiscountRequiresNewAcceptance` — **UNEXECUTED**.
- `sameAmountRuleVersionThresholdAndWindowChangesAreStale` — **UNEXECUTED**.
- `discountCannotMakeManualOrderFree` — **UNEXECUTED**.
- `noneAndEmptyAutoAreVerifiedUnderOwnerFence` — **UNEXECUTED**.
- `platformEnrollmentAndPublishedVersionRaceIsFenced` — **UNEXECUTED**.
- `oldPendingCounterKeepsZeroEconomicsAfterPromotionRollout` — **UNEXECUTED**.
- `consumedCounterRejectsAllocationPartyAndAuthorityTampering` — **UNEXECUTED**.
- `discountExceptionRequiresSealedCounterAuthority` — **UNEXECUTED**.
- `counterStillRequiresOriginalInPersonAcceptance` — **UNEXECUTED**.
- `sqlOwnerAliasesKeepCanonicalMoneyAndRawRuleFacts` — **UNEXECUTED**.

Within these exact seventeen methods: USER/GUEST 10000-1000=9000 prepare/accept/place/cash/source; original stock, frozen read/replay options with a failing-live-fence negative, non-best/NONE/aliases/foreign keys; pmt/member/coupon/unknown points fields and no member audience IO; scoped/malformed/objective/future/disjoint/threshold controls; version/threshold/window/base-price stale cases; free-order rejection; post-final-fence writer blocking for empty AUTO, new unsupported offers, NONE/discretionary/gift, platform approval+goods and published version; pre-reader unsupported staleness; genuine old positive/base/catalog controls; merchant/platform/parent/line/party/consent/namespace/hash/allocation tampering, after-cash source UNKNOWN/replay rejection, M1 positive-discount exclusion, malformed seal/consumer-envelope rejection, real financial/after-sale conflicts, original acceptance and SQL owner alias linkage. SQL-protected mutation assertions count only for their actual constraint boundary; they do not claim a successful service-path negative was executed. All assertions are source only.

## Genuine old pending C protocol — authored, never built or invoked here

Entry point: `ai.neargo.shop.scenario.ManualCounterDiscountFixture$OldPendingProducer.main(String[])`. Arguments are exactly `USER|GUEST` and a **fresh output directory**. `-Dtask68.old.classes=<real old common owner classes directory>` is mandatory and checked against the actual old ManualServiceAdmissionPort implementation code origin. C must source-archive/build genuine old common owners from Task66 commit `2c0708f752d747d88a9eda7478326a964d1984fa` in a separate source-only temp checkout/output/classpath. Compile this pinned fixture source with the genuine Task66 CheckoutFlowFixture/ClockConfig and old dependencies; do not overwrite current targets or Maven-install old providers. The nested producer/bootstrap uses unchanged public SPI plus test-fixture setup/auth only, and has no B1 protocol dependency.

No manually authored input identities are required: the producer starts the old source-built application with genuine old ClockConfig, autowires `OldPendingProducer.Bootstrap extends CheckoutFlowFixture`, calls only its real setup helper (no @Test method), composes the actual qualification/member/guest fixture and records generated `input.json`. It creates an actual active entity/store/merchant/buyer/person/member or registered GUEST, real SKU/catalog and real request in that same native database. C supplies the existing actual CHECKOUT_NATIVE/SPRING_DATASOURCE/MANUAL_INVENTORY environment and reduced native schemas; old application defaults explicitly enable member-code, archive-only catalog publication and the existing test guest key. Old runtime must not load current common-owner providers.

The producer really prepares/accepts/stages/reserves through the old public counter SPI. A stock spy delegates the real reserve, requires outside-root execution, real RESERVED lines and exactly one reserve, saves that exact ReservationView, then loses the reply and rejects follow-up observation so placement remains PLACING before final order write. It writes untouched actual pricing JSON, full original intent row/plan/hash, confirmation row, typed quote/accept/place requests and actual ReservationView to `old-pending.json`. It verifies no final order and no schema1 quote envelope/seal. It never strips a seal, rewrites a quote/hash/confirmation/intent/reservation, manufactures zero facts or returns a fake positive owner reply.

For C's dedicated old-pending single-method stage, invoke the producer four times against the same persistent native database and distinct fresh output directories: USER and GUEST positives, another USER for BASE_CHANGE, another GUEST for CATALOG_CHANGE. Pass the four absolute `old-pending.json` paths as `TASK68_OLD_PENDING_USER`, `TASK68_OLD_PENDING_GUEST`, `TASK68_OLD_PENDING_BASE_CHANGE`, `TASK68_OLD_PENDING_CATALOG_CHANGE`. Missing evidence fails; there is no skip/assumption/fallback. Current IT reads each original scope/actor/party/operation from its evidence rather than using its unrelated random @BeforeEach fixture, verifies original persisted plan/hash, introduces real promotion rollout, and resumes that same original request. BASE_CHANGE changes only real current SKU price; CATALOG_CHANGE publishes a real current catalog revision. Positive cases preserve original order/suborder/hash and assert no second current reserve, then real zero cash/source. New operations receive current policy and are blocked by mandatory rollout effects.

Run only this targeted current method immediately after all four old producers, under original 300-second quote/consent validity. Its `resumeOld` uses real 100ms polling bounded at45seconds, refreshes the controlled checkout clock from real time and respects existing nextAttemptAt; no lease/intent timestamp is manually rewritten. Do not run the other sixteen tests first. C owns exact source/build/classpath/SHA/timing pins, actual schema setup, output capture and cleanup of the generated scopes/rows from input/evidence after comparison. The two old serializers' B1 goldens are a different protocol and do not prove genuine pending-owner history. Neither protocol has executed in phase A.

## Permitted compile evidence

Every compile used exactly:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f /private/tmp/task66-complete-reactor/pom.xml \
  -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -Pbeauty-embedded \
  -pl :shop-app -am test-compile -DskipTests
```

Each source change requiring compilation used a distinct log. Actual nine invocations: eight exit0 BUILD SUCCESS across all24 selected reactor modules, one compile failure at run2. Run2 attempted a parent.getPayMode() accessor that actual OrdOrder does not have; source inspection confirmed admissionKind/economic OFFLINE are the actual lane facts, that invalid accessor was removed, and all subsequent compiles passed. No test phase, surefire execution or Tests run output appears. Existing compiler annotation/deprecation/unchecked notices remain. Base and unaffected modules were up to date; no new shop-base source compilation is claimed. Counts below are actual javac source batches in log order (core main/core test/app main/app test when present), not runtime tests.

| Log | Result | Elapsed | Finished | Bytes | SHA-256 | Actual compiled source batches |
|---|---|---|---|---:|---|---|
| `/private/tmp/task-68b2-test-compile-1.log` | SUCCESS | 26.832 s | 2026-10-26T14:48:26+08:00 | 41832 | `d26a859b144802729553c0bf371fdf251612f76cbed637a3aca560b2edccca0d` | 1070, 88, 413, 750 |
| `/private/tmp/task-68b2-test-compile-2.log` | FAILURE | 7.922 s | 2026-10-26T14:49:27+08:00 | 20002 | `6bcc5bb83fc6f0a09ccc60744b0277aac5a4ffad42155715887d1b19a248d445` | 1070 |
| `/private/tmp/task-68b2-test-compile-3.log` | SUCCESS | 27.459 s | 2026-10-26T14:55:21+08:00 | 41832 | `8ab08f0cf2963733de96aa7d17335c45d0398f70f81941c05b029ae72d7b395a` | 1070, 88, 413, 752 |
| `/private/tmp/task-68b2-test-compile-4.log` | SUCCESS | 28.331 s | 2026-10-26T15:00:01+08:00 | 41832 | `d6bf96ca66b19a39537115b8a35055ba0bb3b1d52832c823bb54c72b0f55fdb6` | 1070, 88, 413, 752 |
| `/private/tmp/task-68b2-test-compile-5.log` | SUCCESS | 17.177 s | 2026-10-26T15:06:15+08:00 | 38380 | `1ebfd87c837a588b0e83b2417f98fc681aafc4976df0886e7043fe7b41da82d8` | 752 |
| `/private/tmp/task-68b2-test-compile-final.log` | SUCCESS | 28.458 s | 2026-10-26T15:08:27+08:00 | 41832 | `34482057bc0a2bb20ede8a93bf13744aa776d8b22752f031e666dcc79bd01793` | 1070, 88, 413, 752 |
| `/private/tmp/task-68b2-test-compile-freeze.log` | SUCCESS | 34.627 s | 2026-10-26T15:10:51+08:00 | 41832 | `506eb00d59e1237ffb321afe350be8fdd7f11d08114ee8c7261245c0481c3cd7` | 1070, 88, 413, 752 |
| `/private/tmp/task-68b2-test-compile-source-freeze.log` | SUCCESS | 32.766 s | 2026-10-26T15:12:40+08:00 | 41832 | `9798edf1434217a8470f9be15cf511f443f33821397efdf2118e1645d70dbeb4` | 1070, 88, 413, 752 |
| `/private/tmp/task-68b2-test-compile-reviewed-freeze.log` | SUCCESS | 31.430 s | 2026-10-26T15:14:39+08:00 | 41832 | `4c34cf811cfa693d0c171d71acda766822157afa9e2bd3e4067ecff1586801a2` | 1070, 88, 413, 752 |

Final reviewed-freeze log includes all final functional/style/test sources: core1070 main +88 test, app413 main +752 test; all24 reactor modules SUCCESS. No Maven install, stale installed owner provider, old-owner build, application/serializer/HTTP/network/probe/SQL/migration/container/native/JUnit/Node/Vitest/deployment/ablation/pre-push invocation occurred. Root still owns final all-beauty/all-food31-module and default-retail compile after C source integration.

## Static self-review and remaining evidence boundaries

- Exact12baseline hashes and fourteen current source pins checked. Added/changed lines across14 Java sources have no tabs, trailing whitespace, wildcard imports or >120-column lines. New test imports are explicit and sorted; no new fully-qualified cross-domain type/annotation references. Existing unrelated long lines/FQ references remain unchanged. `git diff --check` returned exit0, no output.
- New marketing/promotion/trade integration imports shared SPI across domains. Owner entities/mappers/pure helpers remain owner-local; Router is existing HOST composition. No private promotion->marketing or marketing->trade helper edge, duplicate resolver/digest, architecture baseline exemption, member/quota eligibility lookup or pmt commit/apply exists in the new lane.
- Ordinary/M1 branch isolation, trusted schema0 derivation, raw SQL alias membership versus canonical public keys, display/hash/equality separation, every counter plan reader/hash, preliminary/final fence ordering and consumed declaration source checks were read statically. sourceView byte equality and zero direct plan decodes were mechanically checked.
- All runtime compatibility, real counter cash/source, native SQL/collation/fence ordering, actual old->current source-build timing/classpath/bootstrap and root runner cleanup remain phase B evidence. The authored finite checks are not claims of passing tests or all-history migration validation. No protocol seal or synthetic evidence is fabricated to bypass these boundaries.
- Independent root review, C exact runner publication, root metadata/gate/ER/ledger and full compile matrix remain. M3 grants/M4 occupation/service start, Pay integration and business-module deployment are outside B2; canStart remains false. No unresolved extra source/B1 method gap is reported.


## Fix round 1/5 — R0 I1/I2/I3 disposition (2026-10-26)

Status: **DONE_PENDING_SCOPED_REVIEW**. This appendix supersedes the earlier R0 source pins and R0 status for the two edited test sources. Source/compile phase A only; all seventeen scenario methods remain **UNEXECUTED**. No runtime, Pay, deployment or full C02 completion claim is made.

- **I1 addressed in authored source:** `promotion(..., owner, entity)` inserts the PLATFORM activity with owner/entity `PLATFORM` from the outset. `campaign(..., tenant, entity, store)` inserts alias campaign identities from the outset. No immutable identity is updated; the V543 triggers remain unchanged. Existing original helper overloads retain their MERCHANT/MAIN/canonical shapes.
- **I2 addressed in authored source:** the existing alias method inserts real case, accent and trailing-space owner/store variants for USER and GUEST, checks each actual `mkt_campaign` tenant/entity/store column predicate against canonical parameters, and asserts raw selected seal facts versus canonical group/applied/share coordinates and 10000−1000=9000 quote money. Distinct foreign entity and store rules are excluded. Case-variant public key and activity reference spellings are rejected byte-exactly. A selected raw store mutation before placement stales the accepted quote. Completed alias orders retain frozen read/replay/source and ADMITTED cash after status/version change and deletion. Separate frozen group, applied activity and consent mutations reject cash without declaration. No Java normalization or skipped collation branch was added.
- **I3 addressed in authored source:** `twoLineRequest` inserts a second real SKU under the real goods owner, publishes both catalog entries and prepares two distinct item lines through the actual flow. `balancePaidShares` checks exactly two persisted items, moves +1 and −1 between their paid shares, and proves item sum, child due and parent due remain equal. Before first cash, the negative asserts CONFLICT, no admission declaration, no parent status change, no SKU stock/locked-stock change and no manual-receipt outbox effect. A separate genuine two-line order first receives ADMITTED cash, then the same balanced tamper yields same-key replay CONFLICT and source UNKNOWN with no usable lines. No successful stock/cash owner reply was replaced.

The original `OldPendingProducer` class and everything after its declaration is byte-identical to the R0 snapshot. All other twelve B2 Java files exactly match the R0 manifest. The seventeen-method finite suite count is unchanged. These checks are source-only; the case behavior and native SQL collation result await unified phase B.

### Final source pins for scoped R1 review

| Source | Bytes | SHA-256 | R0 comparison |
|---|---:|---|---|
| `backend/shop-app/src/main/java/ai/neargo/shop/portal/port/CampaignPortRouter.java` | 9191 | `fb2c388ccccc4325f6a9cd590c4bdc45e5c0d4dd87ced5ac3ab94e7259de851c` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/marketing/port/CampaignPortImpl.java` | 22521 | `f4bbe3c72e93f583e25228d376cba0e88af0d1dbf6955f986f88675027d6f121` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/ActivityPricingService.java` | 4644 | `949284a691f99d4ac6b843791df1e849c4b6b17e3727309004eb332998b0fa88` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/impl/ActivityPricingServiceImpl.java` | 63281 | `a3c873922a53d7d1208f6db265cffd06816045346605b4edf9a5e8d0eca48461` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/product/port/GoodsQueryPortImpl.java` | 26971 | `a3ab36af5f70f28b1ba45d68a842dbcdcc2ea16f099dac54e4983d4c9f6976b1` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java` | 104223 | `5372d8a2f37a93bfe8767ca2a623bedb75eb562bfac3470d7ade0644377123ae` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/CheckoutOrderWriter.java` | 1798 | `92ee35239d22e067d5efc2116b9e4ac950b4f783a0ddc31e06c0bfb43923123c` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/ManualServiceAdmissionServiceImpl.java` | 81318 | `5dfc51e4c11278954b07c04c7c0261ab2468da3dfdf013cd5e5dea67dda236b1` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java` | 298393 | `15f8c7b4b3187c4533a0b1b57442af86b24c90bca8b2957458f8ec690a585637` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutQuoteGuard.java` | 21467 | `9d822096ac5c47fff482ca1801ccdde6417e6c2c4e5bfc6773cf34cfc7a4789a` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/impl/CounterOrderPlacementServiceImpl.java` | 42372 | `40e7389da9508077813e09dc8fd1825dfbef7181cadb87fcd8c79fd640dfb7a1` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/impl/OrderCreationRecoveryServiceImpl.java` | 22990 | `78d408d5c8456a10dd17cdf95f27550f346d29c4f7c167be4a7c216f257f7c0d` | unchanged |
| `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java` | 53404 | `13f20f78016e3208519035bfa4caa1bab5905c41819a71870b9a193bb42c2f63` | edited fix1 |
| `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountFixture.java` | 22691 | `c99b03a79d3bd01eaa02b02baaa84206550e3421b826b6d1117802598767cd21` | edited fix1 |

### Permitted phase-A verification

Exact final command (Java 21; offline current-source reactor; no tests):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f /private/tmp/task66-complete-reactor/pom.xml \
  -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -Pbeauty-embedded \
  -pl :shop-app -am test-compile -DskipTests
```

Final log `/private/tmp/task68b2-fix1-final-compile.log`: exit 0, **BUILD SUCCESS**, all 24 selected reactor modules SUCCESS; 752 app test source files compiled; elapsed 18.442 s, finished 2026-10-26T15:44:58+08:00; 38380 bytes; SHA-256 `05ab5f3b61e351bb30ef1c9f3dc784fe560f0f8fc8ca3e8abcd66e54d88bf891`. An earlier source compile also succeeded; the final log is the freeze evidence. `git diff --check` returned exit 0 with no output. Both edited test sources have no tabs, trailing whitespace or lines over 120 columns; imports are explicit; no added test method. Twelve source pins and OldPendingProducer byte equality were checked against `task-68b2-r0-base`.

All JUnit methods, serializers/goldens, genuine old producer, Node, native SQL/database, owner/stock/cash runtime, applications, probes, network, deployment and ablation remain **UNEXECUTED** under the user's phase-A boundary. Root owns independent scoped review, C and unified phase-B gates. No source edit outside the two test paths, no SQL/trigger change, no index/staging/commit/reset, and no Maven install occurred in this fix round.


## Fix round 2/5 — R1 N1 disposition (2026-10-26)

Status: **DONE_PENDING_SCOPED_REVIEW**. This appendix supersedes the prior IT source pin. I1/I3 remain closed and unchanged. The sole edited source is `ManualCounterDiscountIT.java`; the other thirteen B2 Java sources, including the complete `OldPendingProducer` body, match the R1 snapshot byte for byte. All B1/A/SQL remain outside this edit.

N1 is corrected in the existing alias method. Every iteration now submits the well-formed case-variant canonical entity key and expects `ACTIVITY_CHOICE_UNAVAILABLE`; index 2 additionally submits its raw trailing-space key and expects `BAD_REQUEST` under the approved public reference rule. The actual trailing-space owner row, its SQL equality assertion, canonical accepted request, and subsequent place/cash/frozen source assertions remain unchanged. The finite suite still has exactly 17 `@Test` methods; no new test/helper/source path was added.

### Final 14 source pins for scoped R2 review

| Source | Bytes | SHA-256 | R1 comparison |
|---|---:|---|---|
| `backend/shop-app/src/main/java/ai/neargo/shop/portal/port/CampaignPortRouter.java` | 9191 | `fb2c388ccccc4325f6a9cd590c4bdc45e5c0d4dd87ced5ac3ab94e7259de851c` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/marketing/port/CampaignPortImpl.java` | 22521 | `f4bbe3c72e93f583e25228d376cba0e88af0d1dbf6955f986f88675027d6f121` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/ActivityPricingService.java` | 4644 | `949284a691f99d4ac6b843791df1e849c4b6b17e3727309004eb332998b0fa88` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/impl/ActivityPricingServiceImpl.java` | 63281 | `a3c873922a53d7d1208f6db265cffd06816045346605b4edf9a5e8d0eca48461` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/product/port/GoodsQueryPortImpl.java` | 26971 | `a3ab36af5f70f28b1ba45d68a842dbcdcc2ea16f099dac54e4983d4c9f6976b1` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java` | 104223 | `5372d8a2f37a93bfe8767ca2a623bedb75eb562bfac3470d7ade0644377123ae` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/CheckoutOrderWriter.java` | 1798 | `92ee35239d22e067d5efc2116b9e4ac950b4f783a0ddc31e06c0bfb43923123c` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/ManualServiceAdmissionServiceImpl.java` | 81318 | `5dfc51e4c11278954b07c04c7c0261ab2468da3dfdf013cd5e5dea67dda236b1` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java` | 298393 | `15f8c7b4b3187c4533a0b1b57442af86b24c90bca8b2957458f8ec690a585637` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutQuoteGuard.java` | 21467 | `9d822096ac5c47fff482ca1801ccdde6417e6c2c4e5bfc6773cf34cfc7a4789a` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/impl/CounterOrderPlacementServiceImpl.java` | 42372 | `40e7389da9508077813e09dc8fd1825dfbef7181cadb87fcd8c79fd640dfb7a1` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/impl/OrderCreationRecoveryServiceImpl.java` | 22990 | `78d408d5c8456a10dd17cdf95f27550f346d29c4f7c167be4a7c216f257f7c0d` | unchanged |
| `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java` | 53559 | `6c4db6162dc70fc4414068637548f3f0579a21488b96457e00aa13b5cb625fd2` | edited fix2 |
| `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountFixture.java` | 22691 | `c99b03a79d3bd01eaa02b02baaa84206550e3421b826b6d1117802598767cd21` | unchanged |

### Permitted phase-A verification

Exact command (Java 21, offline current-source 24-module reactor):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f /private/tmp/task66-complete-reactor/pom.xml \
  -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -Pbeauty-embedded \
  -pl :shop-app -am test-compile -DskipTests
```

`/private/tmp/task68b2-fix2-compile.log`: exit 0, BUILD SUCCESS, all 24 selected modules SUCCESS; 752 app test sources compiled; elapsed 21.095 s, finished 2026-10-26T15:55:18+08:00; 38380 bytes; SHA-256 `f1b2466884a787e137390c144b45dd0ed01c6526cadb7233df5179adfe9be2b0`. Snapshot comparison found exactly the three-line N1 assertion adjustment in IT. Thirteen other B2 source hashes match R1; the producer body is byte-identical. `git diff --check` returned exit 0. The IT retains 17 test methods, no tab/trailing-whitespace lines and no line over 120 columns.

All tests/JUnit, native SQL/collation, owner/stock/cash runtime, serializers/goldens, genuine old producer, Node, applications, probes, network, deployment and ablation remain **UNEXECUTED**. These are authored checks and compile evidence only. The platform-only `prd_sku` stock observation limit noted in R1 remains for C's real PLATFORM stock owner disclosure; no inventory/dual stock claim is made here. No Maven install, index/staging/commit/reset, or SQL/production edit occurred. Root owns independent R2 review, C and unified phase-B gates.


## Fix round 3/5 — R2 N1 exception-boundary disposition (2026-10-26)

Status: **DONE_PENDING_SCOPED_REVIEW**. This appendix supersedes the previous IT source pin. The only source edit is in `ManualCounterDiscountIT.java`; the other thirteen B2 sources match R2 exactly. I1/I3 and all other I2 cases remain unchanged.

The malformed trailing-space choice now calls `actual.request("malformed-trailing-key", actual.user(), Map.of(rawEntity, rule))` inside `error(..., BAD_REQUEST)`. The lambda encloses the request-construction operation that throws: `PrepareCounterReq` constructs `PrepareCheckoutReq`, whose constructor validates the choice key with the no-strip-change reference rule. The existing `rejected` helper remains untouched for well-formed foreign keys; the raw trailing owner row, canonical accepted request and place/cash/source assertions remain in the same loop. The R2→current source diff changes only this assertion boundary.

### Final 14 source pins for scoped R3 review

| Source | Bytes | SHA-256 | R2 comparison |
|---|---:|---|---|
| `backend/shop-app/src/main/java/ai/neargo/shop/portal/port/CampaignPortRouter.java` | 9191 | `fb2c388ccccc4325f6a9cd590c4bdc45e5c0d4dd87ced5ac3ab94e7259de851c` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/marketing/port/CampaignPortImpl.java` | 22521 | `f4bbe3c72e93f583e25228d376cba0e88af0d1dbf6955f986f88675027d6f121` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/ActivityPricingService.java` | 4644 | `949284a691f99d4ac6b843791df1e849c4b6b17e3727309004eb332998b0fa88` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/impl/ActivityPricingServiceImpl.java` | 63281 | `a3c873922a53d7d1208f6db265cffd06816045346605b4edf9a5e8d0eca48461` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/product/port/GoodsQueryPortImpl.java` | 26971 | `a3ab36af5f70f28b1ba45d68a842dbcdcc2ea16f099dac54e4983d4c9f6976b1` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java` | 104223 | `5372d8a2f37a93bfe8767ca2a623bedb75eb562bfac3470d7ade0644377123ae` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/CheckoutOrderWriter.java` | 1798 | `92ee35239d22e067d5efc2116b9e4ac950b4f783a0ddc31e06c0bfb43923123c` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/ManualServiceAdmissionServiceImpl.java` | 81318 | `5dfc51e4c11278954b07c04c7c0261ab2468da3dfdf013cd5e5dea67dda236b1` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java` | 298393 | `15f8c7b4b3187c4533a0b1b57442af86b24c90bca8b2957458f8ec690a585637` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutQuoteGuard.java` | 21467 | `9d822096ac5c47fff482ca1801ccdde6417e6c2c4e5bfc6773cf34cfc7a4789a` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/impl/CounterOrderPlacementServiceImpl.java` | 42372 | `40e7389da9508077813e09dc8fd1825dfbef7181cadb87fcd8c79fd640dfb7a1` | unchanged |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/impl/OrderCreationRecoveryServiceImpl.java` | 22990 | `78d408d5c8456a10dd17cdf95f27550f346d29c4f7c167be4a7c216f257f7c0d` | unchanged |
| `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java` | 53617 | `ae5fa71f0e9398429e118dd83b0bfccc9b83af1caead3a5478f4bf955903744a` | edited fix3 |
| `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountFixture.java` | 22691 | `c99b03a79d3bd01eaa02b02baaa84206550e3421b826b6d1117802598767cd21` | unchanged |

### Permitted phase-A verification

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f /private/tmp/task66-complete-reactor/pom.xml \
  -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -Pbeauty-embedded \
  -pl :shop-app -am test-compile -DskipTests
```

`/private/tmp/task68b2-fix3-compile.log`: exit 0, BUILD SUCCESS, 24 selected reactor modules SUCCESS; 752 app test sources compiled; elapsed 18.272 s, finished 2026-10-26T16:01:35+08:00; 38380 bytes; SHA-256 `236f5267b4ee319f87edde237394ffbed851c894045bdb2ad2b9d69ec283a391`. `git diff --check` returned exit 0. The IT retains 17 `@Test` methods, no tabs/trailing whitespace and no line over 120 columns. The unchanged Fixture SHA also pins the complete old producer, separately checked byte-identical to its R1 snapshot.

All JUnit methods, native SQL/collation, owner/stock/cash runtime, serializers/goldens, genuine old producer, Node, applications, probes, network, deployment and ablation remain **UNEXECUTED**. No Maven install, index/staging/commit/reset, production/SQL edit or helper-wide change occurred. Root owns independent R3 review, C and unified phase-B gates.
