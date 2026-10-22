package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ai.neargo.shop.common.pricing.BenefitPlanSelector.Context;
import ai.neargo.shop.common.pricing.BenefitPlanSelector.Evaluated;
import ai.neargo.shop.common.pricing.BenefitPlanSelector.PlanCalculator;
import ai.neargo.shop.common.pricing.BenefitPlanSelector.PlanRequest;
import ai.neargo.shop.common.pricing.BenefitPlanSelector.Rejected;
import ai.neargo.shop.common.pricing.BenefitPlanSelector.SearchLimits;
import ai.neargo.shop.common.pricing.BenefitPlanSelector.Unavailable;
import ai.neargo.shop.common.pricing.BenefitSelection.CouponOption;
import ai.neargo.shop.common.pricing.BenefitSelection.Mode;
import ai.neargo.shop.common.pricing.PlanSelectionResult.Status;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.AssetRef;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.Benefit;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.OwnerEvidence;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.PortionAmount;
import ai.neargo.shop.common.pricing.PricingRuleFacts.AccessContext;
import ai.neargo.shop.common.pricing.PricingRuleFacts.CouponUse;
import ai.neargo.shop.common.pricing.PricingRuleFacts.PricingRole;
import ai.neargo.shop.common.pricing.PricingRuleFacts.ScopeStatus;
import ai.neargo.shop.common.pricing.PricingRuleFacts.UnitPolicy;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;

class BenefitPlanSelectorTest {
    private final BenefitPlanSelector selector = new BenefitPlanSelector();

    @Test
    void explicitCannotDropCoupon() throws Exception {
        var result = select(Mode.EXPLICIT, List.of(percent("P1"), percent("P2")),
                request -> quote(request, List.of(b("P1", BenefitKind.PERCENT_COUPON, 0, 1000),
                        b("P2", BenefitKind.PERCENT_COUPON, 0, 2000))));
        assertThat(result.status()).isEqualTo(Status.NO_LEGAL_PLAN);
        assertThat(result.plan()).isNull();
        assertThat(result.rejections()).flatExtracting(PlanSelectionResult.Rejection::violations)
                .extracting(RuleViolation::code).contains("PERCENT_SCOPE_OVERLAP");
    }

    @Test
    void silentlyDroppedExplicitCouponIsAnIncompleteEvaluation() throws Exception {
        var result = select(Mode.EXPLICIT, List.of(percent("P1"), percent("P2")),
                request -> quote(request, List.of(b("P1", BenefitKind.PERCENT_COUPON, 0, 1000))));
        assertThat(result.status()).isEqualTo(Status.EVALUATION_INCOMPLETE);
        assertThat(result.plan()).isNull();
    }

    @Test
    void countsAssetsAndChecksQuantityOverlap() throws Exception {
        var f = facts(5);
        List<PortionAmount> split = f.prices().units().stream()
                .map(u -> new PortionAmount(PricingPortion.from(u), money(100))).toList();
        var benefit = new Benefit(asset("P1"), BenefitKind.PERCENT_COUPON, money(500), split, null);
        var result = selector.select(context(f, config("{}"), request -> quote(request, List.of(benefit))),
                selection(Mode.EXPLICIT, List.of(percent("P1"))));
        assertThat(result.status()).isEqualTo(Status.SELECTED);
        assertThat(result.plan().usedCoupons()).containsExactly(asset("P1"));
        assertThat(result.plan().candidate().benefits().get(0).allocations()).hasSize(5);
        assertThat(result.plan().candidate().remainingPayable()).isEqualTo(money(49500));
    }

    @Test
    void disjointCouponsCanUseDifferentPricedPortionsWithinRootLimits() throws Exception {
        var result = selector.select(context(facts(2), config("{}"), request -> quote(request,
                List.of(b("P1", BenefitKind.PERCENT_COUPON, 0, 1000),
                        b("P2", BenefitKind.PERCENT_COUPON, 1, 2000)))),
                selection(Mode.EXPLICIT, List.of(percent("P1"), percent("P2"))));
        assertThat(result.status()).isEqualTo(Status.SELECTED);
        assertThat(result.plan().candidate().remainingPayable()).isEqualTo(money(17000));
    }

    @Test
    void bestOneSearchesOptionalSubsetsInsteadOfDroppingExplicitIntent() throws Exception {
        var c = config("{\"discountCouponPolicy\":{\"overlapMode\":\"BEST_ONE\"}}");
        var result = selector.select(context(facts(1), c, additive(Map.of("P1", 1000L, "P2", 2000L))),
                selection(Mode.CANDIDATE_POOL, List.of(percent("P1"), percent("P2"))));
        assertThat(result.status()).isEqualTo(Status.SELECTED);
        assertThat(result.plan().usedCoupons()).containsExactly(asset("P2"));
        assertThat(result.plan().unusedCoupons()).containsExactly(asset("P1"));
        assertThat(result.plan().stage()).isEqualTo(CalculationPlan.Stage.BUSINESS_PRE_TAX);
    }

    @Test
    void revaluesEachFullCombinationInsteadOfAddingIndependentCouponSavings() throws Exception {
        var p = percent("P");
        var c = cash("C");
        var calls = new ArrayList<List<AssetRef>>();
        var result = selector.select(context(facts(1), config("{}"), request -> {
            calls.add(request.coupons().stream().map(CouponOption::asset).toList());
            var benefits = new ArrayList<Benefit>();
            boolean both = request.coupons().size() == 2;
            for (var coupon : request.coupons()) {
                benefits.add(b(coupon.asset().assetNo(), coupon.kind(), 0, both ? 100 : 2000));
            }
            return quote(request, benefits);
        }), selection(Mode.AUTO_BEST, List.of(c, p)));
        assertThat(result.status()).isEqualTo(Status.SELECTED);
        assertThat(result.plan().candidate().remainingPayable()).isEqualTo(money(8000));
        assertThat(result.plan().usedCoupons()).hasSize(1);
        assertThat(calls).contains(List.of(asset("C"), asset("P")));
    }

    @Test
    void considersEveryReturnedAllocationAlternativeBeforeRejectingASubset() throws Exception {
        var result = selector.select(context(facts(2), config("{}"), request -> {
            var invalid = candidate(request, List.of(b("P1", BenefitKind.PERCENT_COUPON, 0, 1000),
                    b("P2", BenefitKind.PERCENT_COUPON, 0, 2000)));
            var valid = candidate(request, List.of(b("P1", BenefitKind.PERCENT_COUPON, 0, 1000),
                    b("P2", BenefitKind.PERCENT_COUPON, 1, 2000)));
            return new Evaluated(List.of(invalid, valid));
        }), selection(Mode.EXPLICIT, List.of(percent("P1"), percent("P2"))));
        assertThat(result.status()).isEqualTo(Status.SELECTED);
        assertThat(result.plan().candidate().remainingPayable()).isEqualTo(money(17000));
    }

    @Test
    void stableEqualPriceChoiceUsesAssetIdentityAndDoesNotSpendZeroValueCoupons() throws Exception {
        var calculator = additive(Map.of("A", 1000L, "Z", 1000L));
        for (var pool : List.of(List.of(percent("Z"), percent("A")), List.of(percent("A"), percent("Z")))) {
            var result = select(Mode.AUTO_BEST, pool, calculator);
            assertThat(result.status()).isEqualTo(Status.SELECTED);
            assertThat(result.plan().usedCoupons()).containsExactly(asset("A"));
        }
        var zero = select(Mode.AUTO_BEST, List.of(percent("P")), additive(Map.of("P", 0L)));
        assertThat(zero.status()).isEqualTo(Status.SELECTED);
        assertThat(zero.plan().usedCoupons()).isEmpty();
    }

    @Test
    void stackUsesFrozenOwnerOrder() throws Exception {
        var c = config("""
                {"discountCouponPolicy":{"overlapMode":"STACK","stackingEnabled":true,"maxUsedPerItem":2}}
                """);
        var result = selector.select(context(facts(1), c, request -> {
            assertThat(request.coupons()).extracting(option -> option.asset().assetNo()).containsExactly("Z", "A");
            return quote(request, List.of(b("Z", BenefitKind.PERCENT_COUPON, 0, 2000),
                    b("A", BenefitKind.PERCENT_COUPON, 0, 800)));
        }), selection(Mode.EXPLICIT, List.of(percent("Z"), percent("A"))));
        assertThat(result.status()).isEqualTo(Status.SELECTED);
        assertThat(result.plan().candidate().remainingPayable()).isEqualTo(money(7200));
        assertThat(result.plan().candidate().benefits()).extracting(v -> v.asset().assetNo()).containsExactly("Z", "A");
    }

    @Test
    void rootHistoryCanMakeAnOtherwiseLegalSubsetInvalid() throws Exception {
        var f = facts(1);
        f = new PricingRuleFacts(f.prices(), f.context(), f.unitPolicies(), List.of(), List.of(
                new CouponUse(asset("OLD1"), BenefitKind.PERCENT_COUPON),
                new CouponUse(asset("OLD2"), BenefitKind.PERCENT_COUPON)));
        var result = selector.select(context(f, config("{}"), additive(Map.of("P", 1000L))),
                selection(Mode.AUTO_BEST, List.of(percent("P"))));
        assertThat(result.status()).isEqualTo(Status.SELECTED);
        assertThat(result.plan().usedCoupons()).isEmpty();
    }

    @Test
    void incompleteQuoteBlocksBestClaimEvenAfterFindingALegalWinner() throws Exception {
        var result = select(Mode.AUTO_BEST, List.of(percent("P")), request -> request.coupons().isEmpty()
                ? quote(request, List.of()) : new Unavailable("OWNER_UNAVAILABLE"));
        assertThat(result.status()).isEqualTo(Status.EVALUATION_INCOMPLETE);
        assertThat(result.plan()).isNull();
    }

    @Test
    void provenIneligibleSubsetCanBeSkippedWithoutPretendingAnUnavailableQuoteIsIneligible() throws Exception {
        var result = select(Mode.AUTO_BEST, List.of(percent("P")), request -> request.coupons().isEmpty()
                ? quote(request, List.of()) : new Rejected("THRESHOLD_NOT_MET", List.of()));
        assertThat(result.status()).isEqualTo(Status.SELECTED);
        assertThat(result.plan().usedCoupons()).isEmpty();
    }

    @Test
    void excessivePoolFailsBeforeAnyQuoteAndNeverReturnsAPartialWinner() throws Exception {
        var called = new AtomicInteger();
        var ctx = new Context(facts(1), config("{}"), request -> {
            called.incrementAndGet();
            return quote(request, List.of());
        }, new SearchLimits(1, 100));
        var result = selector.select(ctx, selection(Mode.AUTO_BEST, List.of(percent("P1"), percent("P2"))));
        assertThat(result.status()).isEqualTo(Status.PLAN_SEARCH_LIMIT);
        assertThat(result.plan()).isNull();
        assertThat(called.get()).isZero();
    }

    @Test
    void allocationBudgetExhaustionDiscardsAnyEarlierWinner() throws Exception {
        var ctx = new Context(facts(1), config("{}"), request -> {
            var benefit = request.coupons().isEmpty() ? List.<Benefit>of()
                    : List.of(b("P", BenefitKind.PERCENT_COUPON, 0, 1000));
            var proposal = candidate(request, benefit);
            return new Evaluated(List.of(proposal, proposal));
        }, new SearchLimits(2, 3));
        var result = selector.select(ctx, selection(Mode.AUTO_BEST, List.of(percent("P"))));
        assertThat(result.status()).isEqualTo(Status.PLAN_SEARCH_LIMIT);
        assertThat(result.plan()).isNull();
        assertThat(result.evaluatedPlans()).isLessThanOrEqualTo(3);
    }

    @Test
    void fixedFundingIntentCannotBeDroppedOrIncreasedToWin() throws Exception {
        var fixed = b("S", BenefitKind.STORED_VALUE, 0, 1000);
        var chosen = new BenefitSelection(Mode.AUTO_BEST, List.of(), List.of(fixed));
        var c = config("{\"storedValueEnabled\":true}");
        var dropped = selector.select(context(facts(1), c, request -> quote(request, List.of())), chosen);
        assertThat(dropped.status()).isEqualTo(Status.EVALUATION_INCOMPLETE);
        var changed = selector.select(context(facts(1), c,
                request -> quote(request, List.of(b("S", BenefitKind.STORED_VALUE, 0, 2000)))), chosen);
        assertThat(changed.status()).isEqualTo(Status.EVALUATION_INCOMPLETE);
        var unchanged = selector.select(context(facts(1), c,
                request -> quote(request, request.fixedBenefits())), chosen);
        assertThat(unchanged.status()).isEqualTo(Status.SELECTED);
    }

    @Test
    void duplicateSelectionAndInjectedAssetCannotBeAccepted() throws Exception {
        var invalid = select(Mode.EXPLICIT, List.of(percent("P"), percent("P")),
                request -> { throw new AssertionError("Must fail before quoting"); });
        assertThat(invalid.status()).isEqualTo(Status.INVALID_SELECTION);
        var injected = select(Mode.EXPLICIT, List.of(percent("P")), request -> quote(request,
                List.of(b("P", BenefitKind.PERCENT_COUPON, 0, 100), b("X", BenefitKind.CASH_COUPON, 0, 100))));
        assertThat(injected.status()).isEqualTo(Status.EVALUATION_INCOMPLETE);
    }

    @Test
    void unselectedAssetsAndInputListsRemainUntouched() throws Exception {
        var pool = new ArrayList<>(List.of(percent("P1"), percent("P2")));
        var result = select(Mode.CANDIDATE_POOL, pool, additive(Map.of("P1", 1000L, "P2", 2000L)));
        assertThat(result.status()).isEqualTo(Status.SELECTED);
        assertThat(pool).extracting(CouponOption::asset).containsExactly(asset("P1"), asset("P2"));
        assertThat(result.plan().unusedCoupons()).containsExactly(asset("P1"));
        assertThatThrownBy(() -> result.plan().usedCoupons().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.plan().candidate().benefits().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void parentLeafDoublePricingIsRejectedEvenWhenNoCouponIsChosen() throws Exception {
        var f = facts(2);
        f = new PricingRuleFacts(f.prices(), f.context(), List.of(
                new UnitPolicy(PricingPortion.from(f.prices().units().get(0)), true, ScopeStatus.OPEN,
                        "BUNDLE", PricingRole.PARENT),
                new UnitPolicy(PricingPortion.from(f.prices().units().get(1)), true, ScopeStatus.OPEN,
                        "BUNDLE", PricingRole.LEAF)), List.of(), List.of());
        var result = selector.select(context(f, config("{}"), request -> quote(request, List.of())),
                selection(Mode.AUTO_BEST, List.of()));
        assertThat(result.status()).isEqualTo(Status.NO_LEGAL_PLAN);
        assertThat(result.rejections()).flatExtracting(PlanSelectionResult.Rejection::violations)
                .extracting(RuleViolation::code).contains("BUNDLE_PARENT_LEAF_DOUBLE_COUNT");
    }

    @Test
    void calculatorFailureIsNotSilentlyConvertedIntoAnOptimalPlan() throws Exception {
        assertThatThrownBy(() -> select(Mode.AUTO_BEST, List.of(), request -> {
            throw new IllegalStateException("broken evaluation");
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotReorderOrChangeKindsInAFrozenExplicitExecution() throws Exception {
        var reversed = select(Mode.EXPLICIT, List.of(percent("P1"), percent("P2")), request -> quote(request,
                List.of(b("P2", BenefitKind.PERCENT_COUPON, 0, 100), b("P1", BenefitKind.PERCENT_COUPON, 0, 100))));
        assertThat(reversed.status()).isEqualTo(Status.EVALUATION_INCOMPLETE);
        var changed = select(Mode.EXPLICIT, List.of(percent("P")), request -> quote(request,
                List.of(b("P", BenefitKind.CASH_COUPON, 0, 100))));
        assertThat(changed.status()).isEqualTo(Status.EVALUATION_INCOMPLETE);
    }

    @Test
    void explicitZeroEffectCouponIsNotConsumedAsASuccessfulUse() throws Exception {
        var result = select(Mode.EXPLICIT, List.of(percent("P")), additive(Map.of("P", 0L)));
        assertThat(result.status()).isEqualTo(Status.NO_LEGAL_PLAN);
        assertThat(result.rejections()).extracting(PlanSelectionResult.Rejection::code)
                .contains("COUPON_HAS_NO_EFFECT");
    }

    @Test
    void nullEvaluationCannotBecomeAnImplicitRejection() throws Exception {
        var result = select(Mode.AUTO_BEST, List.of(), request -> null);
        assertThat(result.status()).isEqualTo(Status.EVALUATION_INCOMPLETE);
        assertThat(result.plan()).isNull();
    }

    @Test
    void preflightBudgetCannotHideUnvisitedCombinations() throws Exception {
        var ctx = new Context(facts(1), config("{}"), request -> {
            throw new AssertionError("Insufficient request budget must fail before evaluation");
        }, new SearchLimits(8, 4));
        var result = selector.select(ctx, selection(Mode.AUTO_BEST,
                List.of(percent("P1"), percent("P2"), percent("P3"))));
        assertThat(result.status()).isEqualTo(Status.PLAN_SEARCH_LIMIT);
        assertThat(result.evaluatedSubsets()).isZero();
    }

    @Test
    void equalPriceAllocationWinnerDoesNotDependOnAlternativeIterationOrder() throws Exception {
        for (boolean reversed : List.of(false, true)) {
            var ctx = context(facts(2), config("{}"), request -> {
                var first = candidate(request, List.of(b("P", BenefitKind.PERCENT_COUPON, 0, 1000)));
                var second = candidate(request, List.of(b("P", BenefitKind.PERCENT_COUPON, 1, 1000)));
                return new Evaluated(reversed ? List.of(second, first) : List.of(first, second));
            });
            var result = selector.select(ctx, selection(Mode.EXPLICIT, List.of(percent("P"))));
            assertThat(result.status()).isEqualTo(Status.SELECTED);
            assertThat(result.plan().candidate().benefits().get(0).portions().get(0).sourceLineNo()).isEqualTo("L0");
        }
    }

    @Test
    void optionalSearchEvaluatesEverySubsetAndReportsItsWork() throws Exception {
        var result = select(Mode.AUTO_BEST, List.of(percent("P1"), percent("P2")),
                additive(Map.of("P1", 1000L, "P2", 2000L)));
        assertThat(result.status()).isEqualTo(Status.SELECTED);
        assertThat(result.evaluatedSubsets()).isEqualTo(4);
        assertThat(result.evaluatedPlans()).isEqualTo(4);
    }

    @Test
    void quoteMustNotChangeFixedManualIntentToCreateAnAlternative() throws Exception {
        var fixed = b("M", BenefitKind.MANUAL_AMOUNT, 0, 1000);
        var result = selector.select(context(facts(1), config("{}"), request -> quote(request, List.of())),
                new BenefitSelection(Mode.AUTO_BEST, List.of(), List.of(fixed)));
        assertThat(result.status()).isEqualTo(Status.EVALUATION_INCOMPLETE);
        assertThat(result.plan()).isNull();
    }

    @Test
    void couponAndFixedBenefitCannotShareTheSameAssetIdentity() throws Exception {
        var result = selector.select(context(facts(1), config("{}"), request -> {
            throw new AssertionError("Duplicate intent must not reach evaluation");
        }), new BenefitSelection(Mode.EXPLICIT, List.of(percent("P")),
                List.of(b("P", BenefitKind.STORED_VALUE, 0, 100))));
        assertThat(result.status()).isEqualTo(Status.INVALID_SELECTION);
    }

    @Test
    void totalIntentSizeCannotExceedTheRuleCandidateCapacity() throws Exception {
        var fixed = new ArrayList<Benefit>();
        for (int i = 0; i < 64; i++) {
            fixed.add(b("F" + i, BenefitKind.POINTS, 0, 1));
        }
        var result = selector.select(context(facts(1), config("{}"),
                request -> new Unavailable("MUST_NOT_EVALUATE")),
                new BenefitSelection(Mode.EXPLICIT, List.of(percent("P")), fixed));
        assertThat(result.status()).isEqualTo(Status.INVALID_SELECTION);
        assertThat(result.evaluatedSubsets()).isZero();
    }

    @Test
    void explicitIntentAboveOptionalPoolLimitStillUsesOneEvaluationRequest() throws Exception {
        var coupons = new ArrayList<CouponOption>();
        for (int i = 0; i < 64; i++) {
            coupons.add(percent("P" + i));
        }
        var result = select(Mode.EXPLICIT, coupons, request -> new Rejected("OWNER_LIMIT_EXCEEDED", List.of()));
        assertThat(result.status()).isEqualTo(Status.NO_LEGAL_PLAN);
        assertThat(result.evaluatedSubsets()).isEqualTo(1);
    }

    @Test
    void oversizedOptionalSubsetDoesNotInvalidateTheFeasibleFixedIntent() throws Exception {
        var fixed = new ArrayList<Benefit>();
        for (int i = 0; i < 64; i++) {
            fixed.add(b("I" + i, BenefitKind.POINTS, 0, 1));
        }
        var result = selector.select(context(facts(1), config("{}"),
                request -> quote(request, request.fixedBenefits())),
                new BenefitSelection(Mode.AUTO_BEST, List.of(percent("P")), fixed));
        assertThat(result.status()).isEqualTo(Status.SELECTED);
        assertThat(result.plan().usedCoupons()).isEmpty();
        assertThat(result.plan().candidate().benefits()).hasSize(64);
        assertThat(result.evaluatedSubsets()).isEqualTo(1);
    }

    private PlanSelectionResult select(Mode mode, List<CouponOption> coupons, PlanCalculator calculator)
            throws Exception {
        return selector.select(context(facts(1), config("{}"), calculator), selection(mode, coupons));
    }

    private static BenefitSelection selection(Mode mode, List<CouponOption> coupons) {
        return new BenefitSelection(mode, coupons, List.of());
    }

    private static Context context(PricingRuleFacts facts, EffectivePricingConfig config, PlanCalculator calculator) {
        return new Context(facts, config, calculator, SearchLimits.standard());
    }

    private static PlanCalculator additive(Map<String, Long> amounts) {
        return request -> quote(request, request.coupons().stream()
                .map(option -> b(option.asset().assetNo(), option.kind(), 0, amounts.get(option.asset().assetNo())))
                .toList());
    }

    private static Evaluated quote(PlanRequest request, List<Benefit> benefits) {
        return new Evaluated(List.of(candidate(request, benefits)));
    }

    private static PricingRuleCandidate candidate(PlanRequest request, List<Benefit> benefits) {
        long total = request.facts().prices().baseTotal().minor();
        for (var benefit : request.facts().appliedActivities()) {
            total -= benefit.amount().minor();
        }
        for (var benefit : benefits) {
            total -= benefit.amount().minor();
        }
        var owners = benefits.stream().map(benefit -> new OwnerEvidence(benefit.asset(),
                Set.of(BenefitKind.values()), request.config().definition().formulaId(), 1,
                request.facts().context().calculationBasis(), money(100000))).toList();
        return new PricingRuleCandidate(benefits, owners, List.of(), money(total));
    }

    private static Benefit b(String no, BenefitKind kind, int line, long amount) {
        var portion = new PricingPortion("L" + line, null, BigDecimal.ZERO, BigDecimal.ONE);
        return new Benefit(asset(no), kind, money(amount), List.of(new PortionAmount(portion, money(amount))), null);
    }

    private static AssetRef asset(String no) {
        return new AssetRef("OWNER", no);
    }

    private static CouponOption percent(String no) {
        return new CouponOption(asset(no), BenefitKind.PERCENT_COUPON);
    }

    private static CouponOption cash(String no) {
        return new CouponOption(asset(no), BenefitKind.CASH_COUPON);
    }

    private static MoneyMinor money(long amount) {
        return new MoneyMinor("CNY", amount);
    }

    private static PricingRuleFacts facts(int lines) {
        var units = new ArrayList<PricingUnit>();
        for (int i = 0; i < lines; i++) {
            units.add(new PricingUnit("L" + i, null, "G", "SKU", "R1", "TAX" + i, BigDecimal.ZERO,
                    BigDecimal.ONE, money(10000), money(10000)));
        }
        return new PricingRuleFacts(new PricingFacts("CNY", "a".repeat(64), 1, units),
                new AccessContext("STAFF", "STORE", "ROOT", "PAYABLE_GROSS", Instant.parse("2026-10-22T08:00:00Z")),
                units.stream().map(u -> new UnitPolicy(PricingPortion.from(u), true, ScopeStatus.OPEN,
                        null, PricingRole.NONE)).toList(), List.of(), List.of());
    }

    private static EffectivePricingConfig config(String patch) throws Exception {
        var defaults = PricingConfigTestData.JSON.readTree("""
                {"couponLimits":{"total":4,"percent":2,"amountTotal":2,"cash":2,"deduction":2},
                 "discountCouponPolicy":{"maxUsedPerOrder":2}}
                """);
        var compiled = new PricingConfigCompiler().compile(PricingConfigTestData.template(), defaults.toString(),
                SystemConstraints.standard(), PricingConfigTestData.capabilities());
        var result = new PricingConfigCompiler().compile(compiled.config().canonicalJson(), patch,
                SystemConstraints.standard(), PricingConfigTestData.capabilities());
        assertThat(result.valid()).isTrue();
        return result.config();
    }
}
