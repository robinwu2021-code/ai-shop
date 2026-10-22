package ai.neargo.shop.common.pricing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import ai.neargo.shop.common.pricing.BenefitPlanSelector.PlanCalculator;
import ai.neargo.shop.common.pricing.BenefitPlanSelector.PlanRequest;
import ai.neargo.shop.common.pricing.BenefitSelection.CouponOption;
import ai.neargo.shop.common.pricing.PricingFormula.Amount;
import ai.neargo.shop.common.pricing.PricingFormula.Percent;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.Benefit;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.PortionAmount;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;
import ai.neargo.shop.common.pricing.PricingFormula.Input;
import ai.neargo.shop.common.pricing.PricingFormula.Step;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.ApprovalEvidence;
import ai.neargo.shop.common.pricing.PricingResult.Status;

/** Shared calculation/recalculation boundary; never consumes coupons, money or stock. */
public final class PricingEngine {
    /** Calculates and independently reconciles owner-approved steps; confirmation calls the same method again. */
    public PricingResult calculate(Input input) {
        Objects.requireNonNull(input);
        try {
            if (!quotedActivities(input.steps()).equals(input.facts().appliedActivities())) {
                return failed(Status.UNAVAILABLE, "ACTIVITY_FACT_MISMATCH", List.of());
            }
            var definition = input.config().definition();
            var result = FormulaRegistry.resolve(definition.formulaId(), definition.formulaVersion()).compute(input);
            var violations = new PricingRuleEngine().validate(input.facts(), input.config(), result.candidate());
            if (!violations.isEmpty()) {
                Status status = violations.stream().anyMatch(v -> v.code().equals("CONFIG_NOT_COMPILED"))
                        ? Status.UNAVAILABLE : Status.REJECTED;
                return failed(status, "RULE_CONFLICT", violations);
            }
            if (result.candidate().benefits().stream().anyMatch(b -> BenefitSelection.isCoupon(b.kind())
                    && b.amount().minor() == 0)) {
                return failed(Status.REJECTED, "COUPON_HAS_NO_EFFECT", List.of());
            }
            if (!new PricingInvariantChecker().check(input.facts(), result).isEmpty()) {
                return failed(Status.UNAVAILABLE, "PRICING_INVARIANT_FAILED", List.of());
            }
            return result;
        } catch (PricingFormula.Failure failure) {
            return failed(failure.status, failure.code, List.of());
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return failed(Status.UNAVAILABLE, "INVALID_CALCULATION_INPUT", List.of());
        }
    }

    /** Bridges complete owner-backed recipes to CKO-05 without treating missing evidence as ineligibility. */
    public PlanCalculator calculator(RecipeSource source) {
        Objects.requireNonNull(source);
        return request -> {
            var response = source.recipes(request);
            if (response == null) {
                return new BenefitPlanSelector.Unavailable("RECIPE_RESPONSE_MISSING");
            }
            if (response.status() == RecipeStatus.UNAVAILABLE) {
                return new BenefitPlanSelector.Unavailable(response.code());
            }
            if (response.status() == RecipeStatus.INELIGIBLE) {
                return new BenefitPlanSelector.Rejected(response.code(), List.of());
            }
            var candidates = new ArrayList<PricingRuleCandidate>();
            var violations = new ArrayList<RuleViolation>();
            for (var recipe : response.recipes()) {
                if (!matchesIntent(request, recipe)) {
                    return new BenefitPlanSelector.Unavailable("RECIPE_INTENT_MISMATCH");
                }
                var result = calculate(new Input(request.facts(), request.config(),
                        recipe.steps(), recipe.approvals()));
                if (result.status() == Status.UNAVAILABLE) {
                    return new BenefitPlanSelector.Unavailable(result.code());
                }
                if (result.status() == Status.REJECTED) {
                    violations.addAll(result.violations());
                } else {
                    candidates.add(result.candidate());
                }
            }
            return candidates.isEmpty() ? new BenefitPlanSelector.Rejected("NO_LEGAL_ALLOCATION", violations)
                    : new BenefitPlanSelector.Evaluated(candidates);
        };
    }

    private static boolean matchesIntent(PlanRequest request, Recipe recipe) {
        var coupons = recipe.steps().stream().filter(s -> BenefitSelection.isCoupon(s.kind()))
                .map(s -> new CouponOption(s.asset(), s.kind())).toList();
        if (!coupons.equals(request.coupons())
                || !quotedActivities(recipe.steps()).equals(request.facts().appliedActivities())) {
            return false;
        }
        var fixed = new ArrayList<Benefit>();
        try {
            for (var step : recipe.steps()) {
                if (BenefitSelection.isCoupon(step.kind()) || step.kind() == BenefitKind.ACTIVITY) {
                    continue;
                }
                var benefit = quotedBenefit(step);
                if (step.operation() instanceof Amount operation
                        && operation.amountMinor() != benefit.amount().minor()) {
                    return false;
                }
                fixed.add(benefit);
            }
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return false;
        }
        return fixed.equals(request.fixedBenefits());
    }

    private static List<Benefit> quotedActivities(List<Step> steps) {
        return steps.stream().filter(step -> step.kind() == BenefitKind.ACTIVITY)
                .map(PricingEngine::quotedBenefit).toList();
    }

    private static Benefit quotedBenefit(Step step) {
        var amount = new MoneyMinor(step.quote().bases().get(0).amount().currency(), 0);
        var portions = new ArrayList<PortionAmount>();
        for (var allocation : step.quote().allocations()) {
            amount = amount.add(allocation.amount());
            portions.add(new PortionAmount(allocation.portion(), allocation.amount()));
        }
        Integer rate = step.kind() == BenefitKind.MANUAL_PERCENT && step.operation() instanceof Percent p
                ? p.payableRateBp() : null;
        return new Benefit(step.asset(), step.kind(), amount, portions, rate);
    }

    private static PricingResult failed(Status status, String code, List<RuleViolation> violations) {
        return new PricingResult(status, null, List.of(), List.of(), violations, code);
    }

    @FunctionalInterface
    public interface RecipeSource {
        RecipeResponse recipes(PlanRequest request);
    }

    public record Recipe(List<Step> steps, List<ApprovalEvidence> approvals) {
        public Recipe {
            steps = PricingRuleCandidate.bounded(steps, 128);
            approvals = PricingRuleCandidate.bounded(approvals, 64);
        }
    }

    public enum RecipeStatus { COMPLETE, INELIGIBLE, UNAVAILABLE }

    public record RecipeResponse(RecipeStatus status, List<Recipe> recipes, String code) {
        public RecipeResponse {
            Objects.requireNonNull(status);
            recipes = PricingRuleCandidate.bounded(recipes, 64);
            PricingPortion.requireIdentity(code, 64);
            if ((status == RecipeStatus.COMPLETE) != !recipes.isEmpty()) {
                throw new IllegalArgumentException("Recipe completeness and alternatives must agree");
            }
        }

        public static RecipeResponse complete(List<Recipe> recipes) {
            return new RecipeResponse(RecipeStatus.COMPLETE, recipes, "COMPLETE");
        }

        public static RecipeResponse unavailable(String code) {
            return new RecipeResponse(RecipeStatus.UNAVAILABLE, List.of(), code);
        }

        public static RecipeResponse ineligible(String code) {
            return new RecipeResponse(RecipeStatus.INELIGIBLE, List.of(), code);
        }
    }
}
