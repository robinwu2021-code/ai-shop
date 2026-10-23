package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ai.neargo.shop.common.pricing.PricingEngineTestData.input;
import static ai.neargo.shop.common.pricing.PricingEngineTestData.money;
import static ai.neargo.shop.common.pricing.PricingEngineTestData.step;

import java.util.ArrayList;
import java.util.Collections;
import tools.jackson.databind.node.ObjectNode;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.OwnerEvidence;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.Benefit;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.PortionAmount;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.ApprovalEvidence;
import ai.neargo.shop.common.pricing.PricingFormula.StepKey;
import java.util.List;
import org.junit.jupiter.api.Test;
import ai.neargo.shop.common.pricing.BenefitPlanSelector.Context;
import ai.neargo.shop.common.pricing.BenefitPlanSelector.SearchLimits;
import ai.neargo.shop.common.pricing.BenefitSelection.CouponOption;
import ai.neargo.shop.common.pricing.BenefitSelection.Mode;
import ai.neargo.shop.common.pricing.PricingEngine.Recipe;
import ai.neargo.shop.common.pricing.PricingEngine.RecipeResponse;
import ai.neargo.shop.common.pricing.PricingEngineTestData.Spec;
import ai.neargo.shop.common.pricing.PricingFormula.Algorithm;
import ai.neargo.shop.common.pricing.PricingFormula.Amount;
import ai.neargo.shop.common.pricing.PricingFormula.FundedAllocation;
import ai.neargo.shop.common.pricing.PricingFormula.FundingShare;
import ai.neargo.shop.common.pricing.PricingFormula.Input;
import ai.neargo.shop.common.pricing.PricingFormula.OwnerAllocated;
import ai.neargo.shop.common.pricing.PricingFormula.Percent;
import ai.neargo.shop.common.pricing.PricingFormula.Quote;
import ai.neargo.shop.common.pricing.PricingFormula.Step;
import ai.neargo.shop.common.pricing.PricingRuleFacts.AccessContext;
import ai.neargo.shop.common.pricing.PricingRuleFacts.PricingRole;
import ai.neargo.shop.common.pricing.PricingRuleFacts.ScopeStatus;
import ai.neargo.shop.common.pricing.PricingRuleFacts.UnitPolicy;
import ai.neargo.shop.common.pricing.PricingResult.Status;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;

class PricingEngineTest {
    private final PricingEngine engine = new PricingEngine();

    @Test
    void deductThenDiscount() throws Exception {
        var request = input(new long[] {20000}, "{}",
                step("C", BenefitKind.CASH_COUPON, new Amount(2000, 0), 0, 2000),
                step("P", BenefitKind.PERCENT_COUPON, new Percent(9000, null, 0), 0, 1800));
        var result = engine.calculate(request);
        assertThat(result.status()).isEqualTo(Status.CALCULATED);
        assertThat(result.remainingPayable()).isEqualTo(money(16200));
        assertThat(result.priceReductionTotal()).isEqualTo(money(3800));
        assertThat(result.steps()).extracting(s -> s.basisTotal().minor()).containsExactly(20000L, 18000L);
    }

    @Test
    void disjointAmountsAndControlledStackHaveExactResults() throws Exception {
        var disjoint = engine.calculate(input(new long[] {10000, 5000}, "{}",
                step("P1", BenefitKind.PERCENT_COUPON, new Percent(8000, null, 0), 0, 2000),
                step("P2", BenefitKind.PERCENT_COUPON, new Percent(9000, null, 0), 1, 500)));
        assertThat(disjoint.status()).isEqualTo(Status.CALCULATED);
        assertThat(disjoint.remainingPayable()).isEqualTo(money(12500));
        var stacked = engine.calculate(input(new long[] {10000}, """
                {"discountCouponPolicy":{"overlapMode":"STACK","stackingEnabled":true,"maxUsedPerItem":2}}
                """, step("P1", BenefitKind.PERCENT_COUPON, new Percent(8000, null, 0), 0, 2000),
                step("P2", BenefitKind.PERCENT_COUPON, new Percent(9000, null, 0), 0, 800)));
        assertThat(stacked.status()).isEqualTo(Status.CALCULATED);
        assertThat(stacked.remainingPayable()).isEqualTo(money(7200));
    }

    @Test
    void roundingIsPerCouponNotOneCombinedRate() throws Exception {
        var result = engine.calculate(input(new long[] {101}, """
                {"discountCouponPolicy":{"overlapMode":"STACK","stackingEnabled":true,"maxUsedPerItem":2}}
                """, step("P1", BenefitKind.PERCENT_COUPON, new Percent(8500, null, 0), 0, 15),
                step("P2", BenefitKind.PERCENT_COUPON, new Percent(8500, null, 0), 0, 12)));
        assertThat(result.status()).isEqualTo(Status.CALCULATED);
        assertThat(result.remainingPayable()).isEqualTo(money(74));
    }

    @Test
    void proportionalRemainderIsVisibleAndFundersConserveEachLine() throws Exception {
        var spec = new Spec("C", BenefitKind.CASH_COUPON, new Amount(1, 0), new int[] {0, 1},
                new long[] {0, 1}, Algorithm.SHARED_COUPON_V1, "PLATFORM");
        var request = input(new long[] {1, 1}, "{}", spec);
        var result = engine.calculate(request);
        assertThat(result.status()).isEqualTo(Status.CALCULATED);
        assertThat(result.steps().get(0).allocations()).extracting(a -> a.correction().minor())
                .containsExactly(-1L, 0L);
        assertThat(result.steps().get(0).allocations()).extracting(a -> a.roundedShare().minor())
                .containsExactly(1L, 1L);
        assertThat(result.remainingPayable()).isEqualTo(money(1));
        assertThat(new PricingInvariantChecker().check(request.facts(), result)).isEmpty();
    }

    @Test
    void capsAndThresholdUseTheCurrentApprovedScopeBasis() throws Exception {
        var capped = engine.calculate(input(new long[] {101}, "{}",
                step("P", BenefitKind.PERCENT_COUPON, new Percent(8500, 10L, 0), 0, 10)));
        assertThat(capped.status()).isEqualTo(Status.CALCULATED);
        assertThat(capped.remainingPayable()).isEqualTo(money(91));
        var threshold = engine.calculate(input(new long[] {20000}, "{}",
                step("C", BenefitKind.CASH_COUPON, new Amount(10000, 0), 0, 10000),
                step("P", BenefitKind.PERCENT_COUPON, new Percent(9000, null, 15000), 0, 1000)));
        assertThat(threshold.status()).isEqualTo(Status.REJECTED);
        assertThat(threshold.code()).isEqualTo("THRESHOLD_NOT_MET");
        assertThat(threshold.candidate()).isNull();
    }

    @Test
    void ownerAmountAllocationAndBasisMismatchBlockCalculation() throws Exception {
        var wrongAmount = engine.calculate(input(new long[] {10000}, "{}",
                step("P", BenefitKind.PERCENT_COUPON, new Percent(9000, null, 0), 0, 999)));
        assertThat(wrongAmount.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(wrongAmount.code()).isEqualTo("OWNER_ALLOCATION_MISMATCH");
        var original = input(new long[] {10000}, "{}",
                step("P", BenefitKind.PERCENT_COUPON, new Percent(9000, null, 0), 0, 1000));
        var old = original.steps().get(0);
        var q = old.quote();
        var stale = new Quote(q.owner(), q.quoteRef(), "0".repeat(64), q.algorithm(), q.bases(), q.allocations());
        var result = engine.calculate(new Input(original.facts(), original.config(),
                List.of(new Step(old.kind(), old.operation(), stale)), List.of()));
        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.code()).isEqualTo("OWNER_BASIS_CHANGED");
    }

    @Test
    void replayingQuoteUnderAnotherActorOrExecutionOrderFails() throws Exception {
        var original = input(new long[] {20000}, "{}",
                step("C", BenefitKind.CASH_COUPON, new Amount(2000, 0), 0, 2000),
                step("P", BenefitKind.PERCENT_COUPON, new Percent(9000, null, 0), 0, 1800));
        var f = original.facts();
        var access = f.context();
        var changed = new PricingRuleFacts(f.prices(), new AccessContext("OTHER", access.storeNo(), access.scopeNo(),
                access.calculationBasis(), access.evaluatedAt()), f.unitPolicies(), f.appliedActivities(), List.of());
        assertThat(engine.calculate(new Input(changed, original.config(), original.steps(), List.of())).code())
                .isEqualTo("OWNER_BASIS_CHANGED");
        var reversed = new ArrayList<>(original.steps());
        Collections.reverse(reversed);
        assertThat(engine.calculate(new Input(f, original.config(), reversed, List.of())).status())
                .isNotEqualTo(Status.CALCULATED);
    }

    @Test
    void storedValueAndPointsAreSeparateFromCommercialPriceReduction() throws Exception {
        var result = engine.calculate(input(new long[] {10000}, "{\"storedValueEnabled\":true}",
                step("S", BenefitKind.STORED_VALUE, new Amount(6000, 0), 0, 6000),
                step("I", BenefitKind.POINTS, new Amount(1000, 0), 0, 1000)));
        assertThat(result.status()).isEqualTo(Status.CALCULATED);
        assertThat(result.priceReductionTotal()).isEqualTo(money(0));
        assertThat(result.assetOffsetTotal()).isEqualTo(money(7000));
        assertThat(result.remainingPayable()).isEqualTo(money(3000));
    }

    @Test
    void percentCouponAndStoredValueStillConflictAfterArithmetic() throws Exception {
        var result = engine.calculate(input(new long[] {10000}, "{\"storedValueEnabled\":true}",
                step("P", BenefitKind.PERCENT_COUPON, new Percent(9000, null, 0), 0, 1000),
                step("S", BenefitKind.STORED_VALUE, new Amount(1000, 0), 0, 1000)));
        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.violations()).extracting(RuleViolation::ruleNo).contains("B02");
        assertThat(result.candidate()).isNull();
        assertThat(result.lines()).isEmpty();
    }

    @Test
    void legacyPreservesFrozenAllocationWithoutInventingRounding() throws Exception {
        var original = input(new long[] {10, 20}, "{}",
                new Spec("C", BenefitKind.CASH_COUPON, new OwnerAllocated(), new int[] {0, 1},
                        new long[] {3, 2}, Algorithm.OWNER_FROZEN_V1, "PLATFORM"));
        // Trusted platform template transition, not a store formula override.
        var tree = PricingConfigTestData.JSON.readTree(original.config().canonicalJson()).deepCopy();
        ((ObjectNode) tree).put("formulaId", "LEGACY_OWNER_COMPAT");
        var template = new PricingConfigCompiler().compile(tree.toString(), "{}", SystemConstraints.standard(),
                PricingConfigTestData.capabilities()).config();
        var spec = original.steps().get(0);
        var owner = spec.quote().owner();
        var changedOwner = new OwnerEvidence(owner.asset(), owner.combinableKinds(),
                "LEGACY_OWNER_COMPAT", 1, owner.calculationBasis(), owner.eligibleRemainder());
        var order = List.of(new StepKey(spec.asset(), spec.kind()));
        var q = spec.quote();
        var hash = PricingFormula.basisHash(original.facts(), template, order, 0,
                spec.operation(), q.algorithm(), q.bases());
        var step = new Step(spec.kind(), spec.operation(), new Quote(changedOwner, q.quoteRef(), hash,
                q.algorithm(), q.bases(), q.allocations()));
        var result = engine.calculate(new Input(original.facts(), template, List.of(step), List.of()));
        assertThat(result.status()).isEqualTo(Status.CALCULATED);
        assertThat(result.remainingPayable()).isEqualTo(money(25));
        assertThat(result.steps().get(0).allocations()).extracting(a -> a.allocation().amount().minor())
                .containsExactly(3L, 2L);
        assertThat(result.steps().get(0).allocations()).allSatisfy(a -> {
            assertThat(a.roundedShare()).isNull();
            assertThat(a.correction()).isNull();
        });
    }

    @Test
    void frozenOwnerAlgorithmCannotSneakIntoNewComputedFormula() throws Exception {
        var result = engine.calculate(input(new long[] {10000}, "{}",
                new Spec("C", BenefitKind.CASH_COUPON, new OwnerAllocated(), new int[] {0},
                        new long[] {1000}, Algorithm.OWNER_FROZEN_V1, "PLATFORM")));
        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.code()).isEqualTo("OWNER_ALGORITHM_UNSUPPORTED");
    }

    @Test
    void fundingFactsMustSumToTheOwnersDeclaredDiscount() throws Exception {
        var source = input(new long[] {10000}, "{}",
                step("C", BenefitKind.CASH_COUPON, new Amount(1000, 0), 0, 1000));
        var a = source.steps().get(0).quote().allocations().get(0);
        assertThatThrownBy(() -> new FundedAllocation(a.portion(), money(1000),
                List.of(new FundingShare("PLATFORM", money(999))))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void independentInvariantCheckFindsLineTamperingEvenWhenGlobalTotalStillMatches() throws Exception {
        var request = input(new long[] {10000, 5000}, "{}",
                step("C", BenefitKind.CASH_COUPON, new Amount(1000, 0), 0, 1000));
        var original = engine.calculate(request);
        assertThat(original.status()).isEqualTo(Status.CALCULATED);
        var lines = List.of(
                new PricingResult.LineResult(request.facts().prices().units().get(0),
                        money(500), money(0), money(9500)),
                new PricingResult.LineResult(request.facts().prices().units().get(1),
                        money(500), money(0), money(4500)));
        var tampered = new PricingResult(Status.CALCULATED, original.candidate(), lines,
                original.steps(), List.of(), null);
        assertThat(new PricingInvariantChecker().check(request.facts(), tampered)).contains("LINE_STEP_TOTAL_MISMATCH");
    }

    @Test
    void sourceExcludedAndUnknownPortionsCannotBeAuthorizedByFormula() throws Exception {
        var original = input(new long[] {10000}, "{}",
                step("C", BenefitKind.CASH_COUPON, new Amount(1000, 0), 0, 1000));
        var f = original.facts();
        for (var p : List.of(new UnitPolicy(f.unitPolicies().get(0).portion(), false, ScopeStatus.OPEN,
                null, PricingRole.NONE), new UnitPolicy(f.unitPolicies().get(0).portion(), true,
                ScopeStatus.UNKNOWN, null, PricingRole.NONE))) {
            var changed = new PricingRuleFacts(f.prices(), f.context(), List.of(p), List.of(), List.of());
            var prior = original.steps().get(0);
            var q = prior.quote();
            var order = List.of(new StepKey(prior.asset(), prior.kind()));
            var hash = PricingFormula.basisHash(changed, original.config(), order, 0,
                    prior.operation(), q.algorithm(), q.bases());
            var fresh = new Step(prior.kind(), prior.operation(), new Quote(q.owner(), q.quoteRef(), hash,
                    q.algorithm(), q.bases(), q.allocations()));
            var rejected = engine.calculate(new Input(changed, original.config(), List.of(fresh), List.of()));
            assertThat(rejected.status()).isEqualTo(Status.REJECTED);
            assertThat(rejected.violations()).extracting(RuleViolation::ruleNo).contains("B12");
        }
    }

    @Test
    void largeJsonSafeAmountsDoNotOverflowIntermediateMultiplication() throws Exception {
        var result = engine.calculate(input(new long[] {9_000_000_000_000_001L}, "{}",
                step("P", BenefitKind.PERCENT_COUPON, new Percent(5000, null, 0), 0, 4_500_000_000_000_000L)));
        assertThat(result.status()).isEqualTo(Status.CALCULATED);
        assertThat(result.remainingPayable()).isEqualTo(money(4_500_000_000_000_001L));
    }

    @Test
    void sameInputProducesImmutableIdenticalEvidence() throws Exception {
        var request = input(new long[] {10000}, "{}",
                step("P", BenefitKind.PERCENT_COUPON, new Percent(9000, null, 0), 0, 1000));
        var first = engine.calculate(request);
        assertThat(first.status()).isEqualTo(Status.CALCULATED);
        assertThat(first).isEqualTo(engine.calculate(request));
        assertThatThrownBy(() -> first.steps().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.steps().get(0).allocations().get(0).allocation().funders().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void formulaEngineCanSupplyTheBoundedPlanSelectorAndBeReusedAtConfirmation() throws Exception {
        var quoted = input(new long[] {10000}, "{}",
                step("P", BenefitKind.PERCENT_COUPON, new Percent(9000, null, 0), 0, 1000));
        var calculator = engine.calculator(request -> RecipeResponse.complete(List.of(
                new Recipe(request.coupons().isEmpty() ? List.of() : quoted.steps(), List.of()))));
        var selected = new BenefitPlanSelector().select(new Context(quoted.facts(), quoted.config(), calculator,
                SearchLimits.standard()), new BenefitSelection(Mode.AUTO_BEST,
                List.of(new CouponOption(quoted.steps().get(0).asset(), BenefitKind.PERCENT_COUPON)), List.of()));
        assertThat(selected.status()).isEqualTo(PlanSelectionResult.Status.SELECTED);
        assertThat(selected.plan().candidate().remainingPayable()).isEqualTo(money(9000));
        assertThat(engine.calculate(quoted).candidate()).isEqualTo(selected.plan().candidate());
    }

    @Test
    void unavailableOwnerRecipePreventsAnyBestPriceClaim() throws Exception {
        var source = input(new long[] {10000}, "{}");
        var calculator = engine.calculator(request -> RecipeResponse.unavailable("OWNER_QUOTE_UNAVAILABLE"));
        var selected = new BenefitPlanSelector().select(new Context(source.facts(), source.config(), calculator,
                SearchLimits.standard()), new BenefitSelection(Mode.AUTO_BEST, List.of(), List.of()));
        assertThat(selected.status()).isEqualTo(PlanSelectionResult.Status.EVALUATION_INCOMPLETE);
        assertThat(selected.plan()).isNull();
    }
    @Test
    void rejectedWrongRecipeCannotHideAMissingRequestedCoupon() throws Exception {
        var wrong = input(new long[] {10000}, "{}",
                step("X", BenefitKind.STORED_VALUE, new Amount(1000, 0), 0, 1000));
        var calculator = engine.calculator(request -> RecipeResponse.complete(List.of(new Recipe(
                request.coupons().isEmpty() ? List.of() : wrong.steps(), List.of()))));
        var selected = new BenefitPlanSelector().select(new Context(wrong.facts(), wrong.config(), calculator,
                SearchLimits.standard()), new BenefitSelection(Mode.AUTO_BEST,
                List.of(new CouponOption(PricingEngineTestData.asset("P"), BenefitKind.PERCENT_COUPON)), List.of()));
        assertThat(selected.status()).isEqualTo(PlanSelectionResult.Status.EVALUATION_INCOMPLETE);
        assertThat(selected.plan()).isNull();
    }

    @Test
    void coordinatedRoundingTraceTamperingCannotPreserveAFalseExplanation() throws Exception {
        var request = input(new long[] {1, 1}, "{}", new Spec("C", BenefitKind.CASH_COUPON,
                new Amount(1, 0), new int[] {0, 1}, new long[] {0, 1}, Algorithm.SHARED_COUPON_V1, "PLATFORM"));
        var original = engine.calculate(request);
        assertThat(original.status()).isEqualTo(Status.CALCULATED);
        var evidence = original.steps().get(0);
        var traces = new ArrayList<>(evidence.allocations());
        var first = traces.get(0);
        traces.set(0, new PricingResult.AllocationTrace(first.portion(), first.basis(), money(0), money(0),
                first.allocation()));
        var alteredStep = new PricingResult.StepResult(evidence.step(), evidence.basisTotal(),
                evidence.appliedTotal(), traces);
        var altered = new PricingResult(Status.CALCULATED, original.candidate(), original.lines(),
                List.of(alteredStep), List.of(), null);
        assertThat(new PricingInvariantChecker().check(request.facts(), altered))
                .contains("ROUNDING_EVIDENCE_MISMATCH");
    }

    @Test
    void zeroEffectCouponIsRejectedBeforeFinalConfirmation() throws Exception {
        var result = engine.calculate(input(new long[] {10000}, "{}",
                step("P", BenefitKind.PERCENT_COUPON, new Percent(10000, null, 0), 0, 0)));
        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.code()).isEqualTo("COUPON_HAS_NO_EFFECT");
    }

    @Test
    void frozenLegacyCompatibilityDoesNotPretendToSupportNewManualRateRules() throws Exception {
        var request = PricingEngineTestData.inputWithFormula("LEGACY_OWNER_COMPAT", new long[] {10000}, "{}",
                new Spec("M", BenefitKind.MANUAL_PERCENT, new OwnerAllocated(), new int[] {0},
                        new long[] {1000}, Algorithm.OWNER_FROZEN_V1, "ENTITY"));
        var result = engine.calculate(request);
        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.code()).isEqualTo("FORMULA_KIND_UNSUPPORTED");
    }

    @Test
    void actualActivityIsAppliedOnceAndRequiresItsOriginalFrozenQuote() throws Exception {
        var request = input(new long[] {10000}, "{}", new Spec("A", BenefitKind.ACTIVITY,
                new OwnerAllocated(), new int[] {0}, new long[] {1000}, Algorithm.OWNER_FROZEN_V1, "ENTITY"));
        var result = engine.calculate(request);
        assertThat(result.status()).isEqualTo(Status.CALCULATED);
        assertThat(result.remainingPayable()).isEqualTo(money(9000));
        assertThat(result.priceReductionTotal()).isEqualTo(money(1000));
        assertThat(result.candidate().benefits()).isEmpty();
        assertThat(engine.calculate(new Input(request.facts(), request.config(), List.of(), List.of())).code())
                .isEqualTo("ACTIVITY_FACT_MISMATCH");
    }

    @Test
    void manualDiscountAndLastWaiverRequireMatchingComputedAmountApprovals() throws Exception {
        var request = input(new long[] {10000}, "{}",
                step("M", BenefitKind.MANUAL_PERCENT, new Percent(8000, null, 0), 0, 2000));
        var actor = request.facts().context();
        var approval = new ApprovalEvidence(request.steps().get(0).asset(),
                BenefitKind.MANUAL_PERCENT, actor.actorNo(), actor.storeNo(), actor.scopeNo(), money(2000),
                "PRICE_CORRECTION", actor.evaluatedAt().plusSeconds(60), "APPROVAL");
        assertThat(engine.calculate(request).status()).isEqualTo(Status.REJECTED);
        var approved = engine.calculate(new Input(request.facts(), request.config(),
                request.steps(), List.of(approval)));
        assertThat(approved.status()).isEqualTo(Status.CALCULATED);
        assertThat(approved.remainingPayable()).isEqualTo(money(8000));
        var funding = input(new long[] {10000}, "{\"storedValueEnabled\":true}",
                step("S", BenefitKind.STORED_VALUE, new Amount(4000, 0), 0, 4000),
                step("I", BenefitKind.POINTS, new Amount(1000, 0), 0, 1000),
                step("W", BenefitKind.MANUAL_WAIVER, new Amount(500, 0), 0, 500));
        var waiveApproval = new ApprovalEvidence(funding.steps().get(2).asset(),
                BenefitKind.MANUAL_WAIVER, actor.actorNo(), actor.storeNo(), actor.scopeNo(), money(500),
                "PRICE_CORRECTION", actor.evaluatedAt().plusSeconds(60), "WAIVER");
        var finalResult = engine.calculate(new Input(funding.facts(), funding.config(),
                funding.steps(), List.of(waiveApproval)));
        assertThat(finalResult.status()).isEqualTo(Status.CALCULATED);
        assertThat(finalResult.remainingPayable()).isEqualTo(money(4500));
    }

    @Test
    void stagesCannotBeSwappedEvenWithFreshOwnerQuotes() throws Exception {
        var result = engine.calculate(input(new long[] {20000}, "{}",
                step("P", BenefitKind.PERCENT_COUPON, new Percent(9000, null, 0), 0, 2000),
                step("C", BenefitKind.CASH_COUPON, new Amount(1000, 0), 0, 1000)));
        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.code()).isEqualTo("FORMULA_STAGE_ORDER");
    }

    @Test
    void storedFormulaSupportsCashThenStoredButNeverPercentCoupons() throws Exception {
        var request = PricingEngineTestData.inputWithFormula("DEDUCT_THEN_STORED_VALUE_V1", new long[] {10000},
                "{\"storedValueEnabled\":true}", step("C", BenefitKind.CASH_COUPON, new Amount(1000, 0), 0, 1000),
                step("S", BenefitKind.STORED_VALUE, new Amount(4000, 0), 0, 4000));
        var result = engine.calculate(request);
        assertThat(result.status()).isEqualTo(Status.CALCULATED);
        assertThat(result.remainingPayable()).isEqualTo(money(5000));
        var invalid = PricingEngineTestData.inputWithFormula("DEDUCT_THEN_STORED_VALUE_V1", new long[] {10000}, "{}",
                step("P", BenefitKind.PERCENT_COUPON, new Percent(9000, null, 0), 0, 1000));
        assertThat(engine.calculate(invalid).code()).isEqualTo("FORMULA_KIND_UNSUPPORTED");
    }

    @Test
    void zeroSourceAndUnsupportedFormulaVersionHaveNoInventedPrices() throws Exception {
        var result = engine.calculate(input(new long[] {0}, "{}"));
        assertThat(result.status()).isEqualTo(Status.CALCULATED);
        assertThat(result.remainingPayable()).isEqualTo(money(0));
        assertThatThrownBy(() -> FormulaRegistry.resolve("DEDUCT_THEN_DISCOUNT_V1", 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormulaRegistry.resolve("USER_SCRIPT", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activityManifestMustMatchBeforeLaterThresholdCanRejectARecipe() throws Exception {
        var actual = input(new long[] {10000}, "{\"storedValueEnabled\":true}",
                new Spec("A", BenefitKind.ACTIVITY, new OwnerAllocated(), new int[] {0},
                        new long[] {1000}, Algorithm.OWNER_FROZEN_V1, "ENTITY"));
        var wrong = input(new long[] {10000}, "{\"storedValueEnabled\":true}",
                new Spec("A", BenefitKind.ACTIVITY, new OwnerAllocated(), new int[] {0},
                        new long[] {2000}, Algorithm.OWNER_FROZEN_V1, "ENTITY"),
                step("S", BenefitKind.STORED_VALUE, new Amount(100, 8500), 0, 100));
        var keys = wrong.steps().stream().map(s -> new StepKey(s.asset(), s.kind())).toList();
        var rebound = new ArrayList<Step>();
        for (int i = 0; i < wrong.steps().size(); i++) {
            var step = wrong.steps().get(i);
            var q = step.quote();
            var hash = PricingFormula.basisHash(actual.facts(), actual.config(), keys, i,
                    step.operation(), q.algorithm(), q.bases());
            rebound.add(new Step(step.kind(), step.operation(), new Quote(q.owner(), q.quoteRef(), hash,
                    q.algorithm(), q.bases(), q.allocations())));
        }
        var malformed = new Input(actual.facts(), actual.config(), rebound, List.of());
        assertThat(engine.calculate(malformed).status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(engine.calculate(malformed).code()).isEqualTo("ACTIVITY_FACT_MISMATCH");
        var funded = new Benefit(PricingEngineTestData.asset("S"), BenefitKind.STORED_VALUE,
                money(100), List.of(new PortionAmount(
                        PricingPortion.from(actual.facts().prices().units().get(0)), money(100))), null);
        var selected = new BenefitPlanSelector().select(new Context(actual.facts(), actual.config(),
                engine.calculator(request -> RecipeResponse.complete(List.of(new Recipe(rebound, List.of())))),
                SearchLimits.standard()), new BenefitSelection(Mode.AUTO_BEST, List.of(), List.of(funded)));
        assertThat(selected.status()).isEqualTo(PlanSelectionResult.Status.EVALUATION_INCOMPLETE);
    }

    @Test
    void mixedFunderSharesArePreservedWithoutRedistribution() throws Exception {
        var request = input(new long[] {10000}, "{}",
                step("C", BenefitKind.CASH_COUPON, new Amount(1000, 0), 0, 1000));
        var original = request.steps().get(0);
        var quote = original.quote();
        var approved = new FundedAllocation(quote.allocations().get(0).portion(), money(1000),
                List.of(new FundingShare("PLATFORM", money(400)), new FundingShare("ENTITY", money(600))));
        var amended = new Step(original.kind(), original.operation(), new Quote(quote.owner(), quote.quoteRef(),
                quote.basisHash(), quote.algorithm(), quote.bases(), List.of(approved)));
        var result = engine.calculate(new Input(request.facts(), request.config(), List.of(amended), List.of()));
        assertThat(result.status()).isEqualTo(Status.CALCULATED);
        assertThat(result.steps().get(0).allocations().get(0).allocation().funders())
                .containsExactly(new FundingShare("PLATFORM", money(400)), new FundingShare("ENTITY", money(600)));
    }

}
