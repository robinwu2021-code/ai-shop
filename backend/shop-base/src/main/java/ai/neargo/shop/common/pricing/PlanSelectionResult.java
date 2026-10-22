package ai.neargo.shop.common.pricing;

import java.util.List;
import java.util.Objects;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.AssetRef;

/** Failure never carries a partial winner; incomplete or truncated search cannot make an optimality claim. */
public record PlanSelectionResult(Status status, CalculationPlan plan, List<Rejection> rejections,
        int evaluatedPlans, int evaluatedSubsets) {
    public PlanSelectionResult {
        Objects.requireNonNull(status);
        rejections = List.copyOf(rejections);
        if ((status == Status.SELECTED) != (plan != null) || evaluatedPlans < 0 || evaluatedSubsets < 0) {
            throw new IllegalArgumentException("Selection result must contain exactly a winner or a failure");
        }
    }

    public enum Status { SELECTED, NO_LEGAL_PLAN, INVALID_SELECTION, PLAN_SEARCH_LIMIT, EVALUATION_INCOMPLETE }

    public record Rejection(String code, List<AssetRef> coupons, List<RuleViolation> violations) {
        public Rejection {
            PricingPortion.requireIdentity(code, 64);
            coupons = List.copyOf(coupons);
            violations = List.copyOf(violations);
        }
    }
}
