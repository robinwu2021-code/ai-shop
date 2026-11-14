# Task68 consumed-order admission prerequisite — independent design review

Date: 2026-10-26. Review mode: bounded static design/source review.

## Verdict

**Spec: PASS. Architecture: APPROVED. Whole-C02 admission prerequisite: ADDRESSED.**

Critical: 0. Important: 0. Minor actionable findings: 0. No new blocking breakage found in this correction. Original I1/I2 and R2 display approvals remain preserved; they were not re-audited.

The design is ready for root to publish the paired metadata, exact inventory/baseline and C02 coding brief. This closes the separate admission prerequisite identified in the R2 handoff; it does not claim the proposed product changes have been implemented or verified at runtime. No additional business ruling or user preference is required. Author/product/test-source compilation belongs to phase A; execution of all tests, probes and migrations remains unified phase B under the current policy.

## Frozen inputs and scope

Workspace: `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`.

Read the admission-prerequisite brief first, the regenerated scoped diff/pins, the final report section, added §3.4 and its changed inventory/scenarios, then direct actual model/writer/confirm/replay/source call sites. No unrelated C03–C18, P3, marketing-fence or selected-candidate re-audit was performed.

The final files match the regenerated pins:

| Input | SHA-256 |
|---|---|
| task-68-design-addendum.md | `8a74b6e40a0cf792a0326eb42a720071fd8b59513a5c8a00589238ef08aff763` |
| task-68-design-report.md | `1701a659a8dab5467eb141f9ebd0ccba66d26ed54cecc86b7dc436fae86d03d4` |

The initial addendum pin was superseded by root after the documented correction to `conflict() → ErrorCode.CONFLICT`; the final pin and diff were reread. Actual `ManualServiceAdmissionServiceImpl.java:1300` confirms that error mapping.

All source paths below are relative to the workspace; `C` means `backend/shop-core/src/main/java/ai/neargo/shop`. Document references mean `.superpowers/sdd/beauty-backend/task-68-design-addendum.md` unless otherwise stated.

## Closure and feasibility evidence

1. **The actual blocking zero check is replaced narrowly.** Document lines165–179 identify the real `C/trade/service/impl/ManualServiceAdmissionServiceImpl.java:844` unconditional merchant-zero gate and preserve its common exclusions at804–847. Only proven schema1 `MERCHANT_RECORDED_IN_PERSON` consumed counter economics receive an exception. M1/CONSUMER_SESSION and schema0 remain zero-only; malformed/mixed/unknown authority cannot gain the exception. Positive due and per-line paid-share checks at876–901 already work and are explicitly retained. The design correctly avoids inventing a gross-equals-due defect.

2. **The proposed money checks match the real order writer.** Document lines184–186 require goods, freight, total discount, merchant/platform split, due and individual original item shares to agree with frozen economics, plus exact aggregate checks. The actual parent fields are written at `C/trade/service/impl/OrderServiceImpl.java:2309`, child allocation fields at2551–2557, and the existing counter writer verifies due/merchant/platform against economics at2579–2582. Therefore goods10000, merchant1000, platform0, due9000 has an actual representable stored allocation. A mismatched merchant split cannot be excused by unchanged parent due. No new payment or allocation algorithm is needed at admission.

3. **Consumed consent is available through existing fields and an existing mapper.** Document lines181–182 specify actual row identity, party, scope, operation/quote linkage, original prepared command and consumed authorization. `C/trade/entity/OrdCheckoutConfirmation.java:18` through60 already carries the typed party, authority, namespace, actor/account, accepted/confirmed/consumed timestamps, quote revision/hash, amount, purpose and order. `C/trade/service/impl/CheckoutServiceImpl.java:448` through483 writes these counter acceptance fields; `C/trade/order/service/create/CheckoutQuoteGuard.java:319` through332 consumes confirmation/quote and records order/suborder on the operation. `C/trade/mapper/CheckoutMappers.java:95` already extends BaseMapper, so the specified tenant/deleted-filtered nonlocking select needs only the stated constructor dependency. It does not introduce the existing confirmation FOR UPDATE lock into the admission lock order. Historical USER nullable revision/GUEST identity and a different later authorized cashier are supported.

4. **Hash reconstruction uses frozen, available data.** Document line183 adds `CounterEconomicCodec.requireQuoteHash(...)` with explicit party, original `CheckoutLineReq` list, economic evidence and persisted quote hash. The actual `COUNTER_MANUAL_QUOTE_V1` tuple is at `C/trade/service/impl/OrderServiceImpl.java:2047` through2064. Evidence fields are in `C/trade/order/service/create/CheckoutEconomicSnapshot.java:32`; original lines and command are carried by `CounterOrderPlan.PreparedCommand` at57 and persisted operation command/scope fields exist in `OrdCheckoutOperation.java:50` through55. Name-free new counter money and seal handling remain the previously approved design. The new helper can reconstruct that tuple without a current marketing query, display fields or a new hash version. Frozen rule validation inherits §2.3's threshold semantics (document line81: null threshold is zero); it does not require a positive threshold or a current live rule.

5. **The local helpers fit the actual confirm/replay/source flow.** Document lines169–174 name concrete private helper signatures. First confirm holds parent→children→operation→declaration at `C/trade/service/impl/ManualServiceAdmissionServiceImpl.java:260` through278, then calls facts at288 before original-stock validation301, declaration insertion342 and business transitions344–379. The new checks can execute there before effects. Existing same-key/payload replay returns at279–286; the design explicitly adds schema1 facts/declaration validation before that return while preserving old replay behavior. `Facts` already contains operation/parent/child/quote/line/item fields at1322–1328, and declaration quoteHash/declaredMinor/currency already exist in `C/trade/entity/OrdManualServiceAdmission.java:45` through54. No new public response, declaration ledger or database field is required.

6. **Source admission and stock ownership remain distinct.** Document lines188–192 use the same frozen checks before exposing ADMITTED lines, preserve real stock progression/recovery and prohibit present campaign/member eligibility checks, Pay, ordinary markPaid and financial-paid events. The actual source observation invokes facts at531, checks current parent/declaration revision and business status at533–539, emits usable lines only at541–552, and converts caught BizException to UNKNOWN/empty/incomplete at562–570. Existing stock observe/confirm remains at591–625. Both exact source-hash tuples at1022–1047 remain unchanged. The design therefore permits an already consumed sale after subsequent rule edit/pause/expiry/deletion, while still requiring real ADMITTED stock and current row evidence.

7. **Inventory and finite cases cover the correction.** Document lines266/269/285 include the existing service, ConfirmationMapper constructor dependency, both helpers and local quote-hash verifier. Lines300 and313–314 require genuine USER/GUEST prepare→accept→place→cash9000→original stock→ADMITTED source, post-sale rule mutations, allocation/line/party/context/consent tampering, schema1 replay rejection, old zero controls and sealed NONE. The report's final section at `.superpowers/sdd/beauty-backend/task-68-design-report.md:35` agrees with those changes and the phase-A author/compile, phase-B execution policy. Tests have not been run for this review.

## Preserved source-response interpretation

This is an implementation/test interpretation of unchanged early guards, not a new defect or an architecture blocker. Document lines188/313's UNKNOWN projection applies when the contradictory frozen economics are caught inside the source observation. Actual source calls `progress(row,true)` first at `ManualServiceAdmissionServiceImpl.java:480`; progress invokes `requireDeclarationParty` at611. The final nested view also invokes that guard at1086. Operation/declaration identity corruption can therefore already throw CONFLICT before/after the observation catch instead of returning a source DTO. Preserve those guards: for such cases assert the existing rejection and no usable source; for allocation/quote/consent contradictions reaching the observation catch assert UNKNOWN, incomplete evidence and empty usable lines. Do not weaken authorization or wrap all early failures into successful-looking projections merely to force one return shape.

## Review limits and release

Only this review artifact was written. No product or design input was changed; no tests, build, compilation, serializer/runtime probe, native process, Docker, network, migration, index, commit or subagent action was performed.

Within the requested whole-C02 admission-prerequisite scope, the former zero-discount dead end is now covered by an explicit implementable contract. Root may complete metadata/inventory publication and release coding under the established compile-first policy. Runtime correctness, byte compatibility and actual concurrent/stock behavior remain claims for unified phase B, not claims of this design review.
