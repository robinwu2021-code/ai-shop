package ai.neargo.shop.common.pricing;

import java.util.Set;

/** Closed compiled rule vocabulary. No raw JSON, class names, scripts, or reflection paths are executable. */
public record RuleDefinition(String ruleNo, int ruleVersion, EvaluatorType evaluatorType, Parameters parameters) {
    public enum BenefitKind {
        ACTIVITY, PERCENT_COUPON, CASH_COUPON, DEDUCTION_COUPON, STORED_VALUE, POINTS,
        MANUAL_PERCENT, MANUAL_AMOUNT, MANUAL_WAIVER, CASH_ROUNDING
    }

    public enum EvaluatorType {
        FORBID_COMBINATION, COUNT_LIMIT, REQUIRE_CAPABILITY, SCOPE_OVERLAP, ORDER_AND_REMAINDER,
        REQUIRE_APPROVAL, CONSERVATION, TAX_POLICY_VALIDITY, TAX_PRICE_MODE, TAX_CLASSIFICATION,
        TAX_ADJUSTMENT_CLASSIFICATION, TAX_ROUNDING, TAX_FINALIZATION, TAX_PRESENTATION, TAX_HISTORY
    }

    public enum LimitPath {
        TOTAL, PERCENT, CASH, DEDUCTION, AMOUNT_TOTAL
    }

    public enum FixedPolicy {
        B03, B06, B09, B10, B11, B12, T01, T02, T03, T04, T05, T06, T07, T08
    }

    public sealed interface Parameters permits ForbiddenCombination, CountLimits, FixedPolicyParameters {
    }

    public record ForbiddenCombination(
            Set<BenefitKind> leftKinds, Set<BenefitKind> rightKinds, boolean actuallyAppliedOnly)
            implements Parameters {
        public ForbiddenCombination {
            leftKinds = Set.copyOf(leftKinds);
            rightKinds = Set.copyOf(rightKinds);
        }
    }

    public record CountLimits(Set<BenefitKind> kinds, Set<LimitPath> limits) implements Parameters {
        public CountLimits {
            kinds = Set.copyOf(kinds);
            limits = Set.copyOf(limits);
        }
    }

    /** Remaining mandatory policies have code-owned semantics, rather than mutable generic parameters. */
    public record FixedPolicyParameters(FixedPolicy policy) implements Parameters {
    }
}
