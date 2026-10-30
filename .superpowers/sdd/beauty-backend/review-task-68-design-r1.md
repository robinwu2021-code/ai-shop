# Task68 design addendum scoped re-review R1

Date: 2026-10-26. Review scope: original `review-next-design-2026-10-26.md` I1/I2, proposed `task-68-design-addendum.md` / `task-68-design-report.md`, and new blocking consequences of that engineering overlay. The immutable accepted Task65 §4/C1–C3 remains the business specification. This is not a new C03–C18 audit or Task67/C01 code review.

## Verdict

- **Spec: FAIL** — one retained Task65 quote-candidate requirement lacks an implementation-ready mapping in the addendum.
- **Architecture: NEEDS_FIXES** — **NOT_READY_FOR_C02_CODING_RELEASE**, limited to R1-I1 below.
- Original I1: **ADDRESSED** for its specifically identified choices propagation, eligible enumeration, goods scope and unsupported-effect contracts. Original I2: **ADDRESSED** at the design level for seal carrier, complete final verification, absence/concurrency boundary and old quote/plan recovery.
- New findings: **0 Critical / 1 Important / 0 Minor**. No additional blocker found in the bounded source inspection of the new fence/codec design. Runtime correctness is not claimed.

Only this report was written. No product, tests, configs, schema, indexes, frozen design, bookkeeping or Git mutations; no compilation, test/probe, native/Docker, network or deployment. Read-only HEAD verification returned `28ebe9f111ede8363194cd0e42d50005de465a39`. C01 acceptance supplied by root is kept separate and not re-adjudicated.

## Original finding disposition

| Original | Disposition | Evidence in the proposed addendum |
|---|---|---|
| I1: incomplete choices and supported scope | **ADDRESSED** | §2.1 lines 43–57 defines complete overloads through both ingress/writer guards, Router and actual owners; retains the five existing signatures. §2.2 lines 61–77 resolves keys, contradictory aliases, NONE, explicit/omitted selection, nonexistent/unsupported errors and free-order rejection. §2.3 lines 81–95 enumerates eligible non-best FULL_CUT, chooses empty goodsNos only, uses strict parsing and objective legacy/published/platform predicates, separates mandatory product effects, and preserves ordinary/M1 paths. The remaining quote presentation gap is a new R1 finding below. |
| I2: missing durable seal/final check/old-intent compatibility | **ADDRESSED** | §3 lines 101–138 defines one trailing property-level NON_NULL seal, exact old 18-argument constructor, strict absent/null/new-shape codec, all actual quote/plan readers, canonical digest and trusted FROZEN_M2A_ZERO reconstruction. §4 lines 144–193 supplies owner fence table/trigger coverage, parent identity rules, RC/noncached reads, final complete inputs, fresh owner time and explicit decision linearization. Byte/DB/concurrency scenarios are authored now and executed in phase B, not treated as current proof. |

The accepted spec is not altered to excuse absent behavior. The original fixes are credited separately from the one remaining publication blocker.

## R1-I1 · Important — eligible quote options are computed but not carried to the existing immutable quote response

**Primary location:** `.superpowers/sdd/beauty-backend/task-68-design-addendum.md:118`.

**Requirement:** `.superpowers/sdd/beauty-backend/bm05-m2-counter-source-design.md:208` requires the counter quote to list supported candidates, alongside explicit eligible selection. The addendum correctly computes every eligible candidate in `CounterCandidates.candidates` at lines 40 and 51, but line 118 limits the actual display discussion to the selected AppliedActivity and leaves all candidates at the owner SPI. Neither the persistence contract nor the quote-view contract maps that list into the response.

**Actual source evidence:**

- `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/CheckoutPort.java:303` already has `CheckoutQuote.promotions: List<PromotionOption>`; the record at line 331 already carries promotionNo/kind/title/eligible/reasonCode. No new public field or candidates endpoint is required to expose eligible ordinary options.
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java:1883` builds promotions only from `money.activity().applied()`; line 1938 passes that selected-only list into CheckoutQuote. For NONE, this list is empty even if several supported FULL_CUT candidates exist. For AUTO it contains only the winner, so a valid lesser choice remains undiscoverable from this quote.
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java:306` obtains only the writer economic result, line 310 persists that economic JSON, and line 312 immediately returns `counterView`. `readCounterQuote` at line 336 and original-key replay use the same persisted view. `OrdCheckoutQuote.java:21` has pricingJson, with no separate candidate display payload currently defined.
- Addendum lines 37–39 persist selectedRules in the proposed seal; lines 110–116 define its selected-rule-only content and storage. The transient `CounterCandidates.candidates` list has no frozen representation or replay path. The existing view cannot recover it from selected rules.

**Consequence:** implementation following the addendum can satisfy all pricing/locking checks while failing the accepted counter quote candidate behavior. Calling current owner candidates again from readCounterQuote would also silently change an existing immutable quote after rules/names change, and would introduce transaction/fence requirements into the existing read path; that is not an acceptable unspecified substitute.

**Minimal resolution before coding release:** amend only the proposed overlay to specify:

1. Map all eligible supported candidates from the same owner evaluation to the **existing** `CheckoutQuote.promotions`, including NONE and explicit non-best cases; keep selected money in discounts/activity separately. Preserve the established error behavior when AUTO cannot produce a supported quote.
2. Define the exact counter-only frozen display carrier within the existing quote persistence workflow, plus its initial-write, read and original-key replay mapping. Decide explicitly how it interacts with codec/serialization/hash/equality and old ordinary/M1/M2a bytes. This is a mapping/compatibility addition, not a request for a new public endpoint, promotion ledger or broad rework.
3. Keep display evidence distinct from final selected-rule authority: do not accidentally make an irrelevant competing candidate/name change invalidate explicit/NONE economics merely because a display snapshot participates in Java record equality. Preserve frozen display on reread, and preserve the final eligibility/seal rules already chosen for the selected activity.
4. Add finite source assertions to existing planned C1/I1 and compatibility cases: two supported candidates are both exposed; NONE still exposes supported options; selected discount remains correct; read/replay returns the same frozen options after owner mutation; no unsupported candidate leaks; old quote bytes/behavior remain preserved. Author/compile now; execute only in phase B.

Once that exact carrier/dataflow is documented and the corresponding inventory/codec impact is reconciled, a bounded re-review of R1-I1 is sufficient. No need to reopen the resolved fence design or redo the full architecture review.

## Bounded checks supporting I2 closure

These are design adequacy conclusions based on source, not executable evidence:

- Current `CheckoutEconomicSnapshot.java:10` has the stated 18 components; `CounterOrderPlan.java:24` embeds it. `CounterOrderPlacementServiceImpl.java:393`, `:504`, `:547`, `:672` re-read plans and validate typed plan hashes; `OrderCreationRecoveryServiceImpl.java:84`, `:170`, `:221` are actual additional hash/read owners. Addendum §3.2 names these consumers and explicitly rejects treating default-null roundtrip as proof.
- Current `GoodsQueryPortImpl.java:240` counter path reaches a promotion probe at line 300, and actual `OrderServiceImpl.java:4133` / `:4178` are its two split passes. The proposed counter base/store path plus complete group-aware effect checks addresses both passes; old-policy eligibility is derived only from persisted counter shape/authority, not a public flag. Ordinary/M1 pricing remains a separate branch.
- `CheckoutServiceImpl.java:197` and `CounterOrderPlacementServiceImpl.java:101` set READ_COMMITTED for counter writes. The addendum requires matching platform transaction binding and noncached current reads after shared-fence acquisition. The selected-row `FOR UPDATE` omission is deliberate: a legacy writer may hold its head row while waiting for the fence; adding that reader lock would create a head↔fence cycle.
- Trigger coverage includes all inputs the proposed objective probes actually use: legacy head, pmt head/version/goods/rules, enrollment and enrollment goods. Audience and quota are explicitly not used to prove irrelevance, and published store scope comes from the frozen version snapshot. Current `ActivityVersionServiceImpl.java:144` locks an existing head before version insertion at line 161; `PlatformActivityServiceImpl.java:349` writes enrollment before goods insertion at line 368. The stated child→existing-parent ownership resolution is consistent with these inspected owner flows. Bulk writer deadlock/timeout is treated as transaction failure and original-key retry, never empty-candidate success.
- Addendum §4.3 captures fresh marketing owner time after pricing waits, distinguishes final activity decision time from later commit time, and keeps fences until transaction end. Selected same-amount rule/version changes, empty AUTO and mandatory effects under NONE are explicitly covered. No unknown or missing schema becomes successful zero pricing.
- Proposed V543 is not an executed file. The uncreated M3/M4 reservation shift, packaged schema descriptor, literal-preserving trigger validation, precise entity registry and lineage registrations are documented. This shared marketing coordination table does not create a beauty or payment ledger.
- The scenario plan explicitly defers baseline serializer invocation, golden-byte comparison, real migration/trigger/lock tests and ablations to unified phase B. Pinning existing source/classes by filesystem copy is not represented as runtime validation. No extra pre-coding runtime gate is requested by this review.

## Release boundary

Keep Task68 as design pending until R1-I1 is resolved. Then root can publish paired API/Java/development metadata, exact inventory and current BASE, retaining the compile-first/phase-B-test policy. This review creates no permission to edit an executed migration, integrate Pay or claim runtime compatibility before unified evidence exists.
