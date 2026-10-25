# Task65 independent design review

## Spec Compliance

**❌ Issues found.** The design covers the requested counter authority, typed shared order, original-stock recovery, current member ownership, manual bill semantics and bounded discounts, but its M2b plan leaves two mandatory compatibility/type contracts incomplete (I1, I2). This is a design-only verdict, not an implementation verdict.

**Design quality: Needs fixes.** Critical: **0**. Important: **2**. Minor: **1**. The concurrency concern M1 was evaluated independently; the evidence does not establish an implemented or inevitable deadlock.

Reviewed baseline: `df39678f30f530388c3c7dbe4e5d0d6557d7755b`, unchanged HEAD. Reviewed package: `/tmp/task65-review-package-eb197b03-ab9f-44d6-b605-d25df625188b.diff`, supplied SHA256 `fb741232a0ca12a3d2287c13dbde791221e8bcdcdbcf7d95480353691cadc3c6`. Inputs were the brief including Root clarification, author report and complete proposed design. Root-owned brief/main/ledger reconciliation is requirements input, not unauthorized product modification.

References below use `D` = `.superpowers/sdd/beauty-backend/bm05-m2-counter-source-design.md`; other paths are relative to `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`.

## Strengths and mandatory-outcome checks

- **Genuine no-C counter acceptance and identity:** D:49–67 explicitly distinguishes canonical USER/GUEST identification from merchant-recorded acceptance, defines wrapper/request/confirmation methods, and preserves ordinary C authority. D:73–89 implements the root's prepare-time snapshot ruling, including claim-before-acceptance, immutable historical GUEST and no member purchase witness. Nullable counter USER revision is justified by the actual member return type rather than a fabricated version.
- **Shared order and nullable aggregates:** D:99–123 names all six aggregates, including V541 admission, nonnull actor namespaces, separate hash domains, existing confirmation purpose, and staged extension of the real ordinary writer. Existing funded/M1 hash tuples and constructors remain explicit compatibility requirements. No alternative order, guest money or member ledger is proposed.
- **Bound ingress and live permissions:** D:93–95 specifies separate counter actions, exact scoped payload reconstruction, token-first bound calls and live canonical account checks outside roots; the new C claim methods do not weaken merchant get/verify. Consumer request entity/store are correctly null in the binding, with requested claim scope in the digested body.
- **Stock and financial separation:** D:127–140 distinguishes STOCK_RESERVE_V1 from STOCK_CONFIRM_V1, freezes original IDs/authority/manifest, excludes counter intents before the old scanner's LIMIT, and keeps external stock IO outside Core roots. D:148–150 retains manual financial exclusion and the ordinary funded terminal-refund behavior. The proposed tests in D:232–234 cover original recovery and those two specific existing refund regressions; these are future checks, not passing evidence.
- **Manual source and bill ownership:** D:164–186 keeps manual facts and revisions out of financial READY/hash fields, preserves quantity lineage and canStart=false, and obtains actual manual declarations from the owner RR snapshot. Manual amounts remain separate from Pay totals and current MP155 access comes from member-owned claims. I1/I2 below concern getting existing manual sources through this otherwise appropriate separation.
- **Supported discounts:** D:190–196 limits the initial counter subset to actual legacy merchant FULL_CUT, uses ordinary allocation/economic seals, denies unsupported explicit and automatic effects, and preserves positive due. This is a bounded capability, with concrete C1–C3 assertions at D:239–241; there is no unsupported promise to make the promotion ledger guest-compatible.
- **Bounded delivery and verification:** D:204–212 lists create/modify/reused files, explicitly retains service/SPI separation, and explains the M2a zero-discount dependency. D:224–260 supplies named tests, meaningful behavior ablations, actual migration candidates, finite slice runners, MySQL/Java/reactor bounds and changed-path checks. D:264–268 preserves the M3–M7 boundary and the three separate minor deadlines. The audit/report pins and root verification support the source inventory; they do not prove future behavior.

## Issues

### Critical

None found within this bounded design review.

### Important

**I1 — An existing UNKNOWN financial observation permanently occupies the manual source's unique identity.**

- **Design evidence:** D:168 defaults every existing `bty_source_observation` to immutable `LEGACY_FUNDED` and explicitly says “No migration of historical observations to manual.” D:164/D:172 introduce a separate manual observer/completion path; D:180 promises MANUAL_EXISTING support for accepted M1 USER/C sources.
- **Concrete existing path:** `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSubmissionServiceImpl.java:944` validates the original quote without excluding manual admission, and its EXISTING branch returns at line 959. At line 817 it calls `observations.observe` before binding can reject the unavailable financial source. `ServiceSourceObservation.java:95` claims the UNKNOWN row before owner IO; lines 188–207 insert the canonical source/operation identity. `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java:861` deliberately returns financial UNKNOWN for an M1 manual operation. Thus an ordinary attempted EXISTING binding can already leave this row before M2.
- **Schema evidence:** `backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V8__beauty_source_observation.sql:31` and `:32` uniquely identify source and operation without admission kind. After the proposed default, the same true manual source cannot insert a second manual observation, and immutable kind plus the no-migration rule prevents using the old row. It remains unable to bind through the new manual path even when its real declaration is ADMITTED.
- **Required correction:** Decide a precise owner-verified migration/adoption rule for these legacy UNKNOWN placeholders, or a revised observation-key/domain design that safely represents both histories. Preserve any genuine funded evidence, line allocation identity and late-response fence. Also prevent an old financial observer/recovery call from recreating or overwriting the conflict after upgrade. Add a named real-DB test that first creates the legitimate pre-M2 UNKNOWN row for an M1 manual source, applies V9, then binds/displays that same original manually; include an in-flight old completion. Fresh manual fixtures alone do not exercise this compatibility requirement.

**I2 — The specified manual identity validator cannot consume the promised MANUAL_EXISTING/M1 input.**

- **Design evidence:** D:158 defines MANUAL_EXISTING with only original operation/suborder/source pair and line selections. D:160 gives the actual new identity boundary only as `ServiceSubmissionAccess.manualParty(BtyWorkOrderContext, CounterCheckoutQuote):CustomerParty`. D:58 makes `CounterCheckoutQuote` validate `MERCHANT_RECORDED_IN_PERSON`. Yet D:180 explicitly requires accepted M1 USER/C MANUAL_EXISTING to consume `ManualSourceFacts` from `.source`, not `readCounterQuote` on a consumer-authority operation.
- **Concrete type evidence:** `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ManualServiceAdmissionPort.java:173` defines `ManualSourceFacts` with the canonical party and source coordinates, not a CheckoutQuote. The existing beauty boundary in `.../policy/ServiceSubmissionAccess.java:54` is `buyer(context, customerRef)`; it deliberately retains the funded claimed-USER behavior. `.../service/impl/ServiceSubmissionServiceImpl.java:179` consumes that boundary before freezing the command, and `:887` revalidates it during recovery. The proposed sole manual signature supplies no equivalent facts-based path for these checkpoints.
- **Why it matters:** M1 source facts cannot become the specified counter wrapper without fabricating a quote/authority, while retaining `buyer(...)` would send this new manual lane through the branch the design says is funded-only. A coder must invent an additional authorization method/normalization and its historical-party rules, contrary to the exact producer/consumer handoff requirement. This affects the required existing manual integration, not just naming or style.
- **Required correction:** Specify a manual validator that accepts owner-proven manual source identity (or an explicitly defined normalized value with separate quote/facts producers), name its submit/read/recovery call points, and define USER/GUEST/context matching for both M1 CONSUMER_SESSION and new counter authority. Retain historical GUEST identity across claim and current-member read entitlement separately. Add named assertions for M1 USER/C MANUAL_EXISTING, existing counter USER/GUEST and recovery without raw guest credentials; do not construct a fake counter quote for an M1 source.

### Minor

**M1 — Make the committed-order retry/recovery lock contract explicit.**

- **Design evidence:** D:137 orders finalization as operation→quote→confirmation→intent→parent/child **(new rows)**; D:138 says recovery locks operation first and reads committed-parent existence. It does not say whether that read locks an already committed parent, or where a stale retry must exit before doing so. A5 at D:232 covers finalize/compensate but does not explicitly race an already-created place retry with receipt confirmation/progress.
- **Focused source evidence:** `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/ManualServiceAdmissionServiceImpl.java:234`–239 locks existing parent→children→operation for receipt confirmation; `:648`–654 does the same for progress/recovery. `ManualAdmissionMapper.java:44`–52 confirms those are FOR UPDATE reads. Conversely, the source RR observation at `ManualServiceAdmissionServiceImpl.java:458`–477 locks operation and then uses a nonlocking parent snapshot. The latter is not evidence of an existing inverted parent lock.
- **Assessment and improvement:** New-row finalization alone does not establish a cycle, and “reads committed parent” does not prove a locking read. Do not classify this as a proven deadlock. Add the exact committed/replayed fast path and forbid acquiring an existing parent lock while holding the counter operation/intent roots unless all intersecting owners are deliberately reconciled. Add one deterministic existing-order retry versus receipt/progress assertion. This clarification makes the intended safe interpretation durable for implementation.

## Scoped CannotVerify

- The package contains design artifacts only. No implementation, migration execution, compiled constructors/providers, race result, JSON compatibility result or successful no-Pay runtime flow exists in this review input. A1–C3 and the ablations are proposed acceptance obligations, not executed evidence. The controller should require their actual focused results when each slice is implemented.
- Source pin completeness and clean product diff were reported as independently checked by root. This reviewer did not rerun git or rehash 108 sources; targeted source checks above establish the specific compatibility claims, not an unrestricted whole-tree audit.
- No production historical database was inspected. I1 establishes a reachable source/schema compatibility case, not a claim that a particular deployed customer currently has that row.

## Checks and scope

Read the brief and author report, then the proposed design in the immutable package. The first package output was truncated by the tool; the missing bounded span was recovered. No other design reread was used. The already-read author report was not redundantly reread from its duplicate package section.

Targeted unchanged-source checks were limited to named risks: (1) counter/M1 intersecting locks; (2) manual source versus existing beauty identity input; (3) reservation retry after released stock, checking `StockPortImpl.java:98`–134, which rejects released originals; and (4) legacy financial UNKNOWN observation occupying the manual source identity. The latter two expanded only enough to follow the specific owner/caller/schema path. The existing `ServiceSubmissionAccess` and guest claim path preserve the historical context rather than rewriting it; no separate defect is asserted for that check.

No tests, builds, native commands, Docker, deploy, git mutation, product edits or subagents. Only this requested review report was written.

## Assessment

**Spec compliance: Needs fixes. Design quality: Needs fixes.** Resolve I1 and I2 in the design before releasing M2b coding. The remaining design has concrete owners, constrained authority and meaningful future verification; M1 is a nonblocking precision improvement, not a pregraded concurrency failure.
