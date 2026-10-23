package ai.neargo.shop.common.pricing;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.AssetRef;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.Benefit;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;

/** Trusted business envelope around source prices; actual activities are separate from store settings. */
public record PricingRuleFacts(PricingFacts prices, AccessContext context, List<UnitPolicy> unitPolicies,
        List<Benefit> appliedActivities, List<CouponUse> previousCoupons) {
    public PricingRuleFacts {
        Objects.requireNonNull(prices);
        Objects.requireNonNull(context);
        unitPolicies = PricingRuleCandidate.bounded(unitPolicies, 512);
        appliedActivities = PricingRuleCandidate.bounded(appliedActivities, 64);
        previousCoupons = PricingRuleCandidate.bounded(previousCoupons, 64);
        var expected = new HashSet<>(prices.units().stream().map(PricingPortion::from).toList());
        var actual = new HashSet<>(unitPolicies.stream().map(UnitPolicy::portion).toList());
        if (unitPolicies.size() != prices.units().size() || !expected.equals(actual)
                || appliedActivities.stream().anyMatch(b -> b.kind() != BenefitKind.ACTIVITY)) {
            throw new IllegalArgumentException(
                    "Every source portion needs exactly one policy; activities only in facts");
        }
    }

    public enum ScopeStatus { OPEN, PAID, UNKNOWN }
    public enum PricingRole { NONE, PARENT, LEAF }

    public record AccessContext(String actorNo, String storeNo, String scopeNo,
            String calculationBasis, Instant evaluatedAt) {
        public AccessContext {
            PricingPortion.requireIdentity(actorNo, 32);
            PricingPortion.requireIdentity(storeNo, 32);
            PricingPortion.requireIdentity(scopeNo, 64);
            PricingPortion.requireIdentity(calculationBasis, 32);
            Objects.requireNonNull(evaluatedAt);
        }
    }

    public record UnitPolicy(PricingPortion portion, boolean promotionEligible, ScopeStatus status,
            String bundleGroup, PricingRole role) {
        public UnitPolicy {
            Objects.requireNonNull(portion);
            Objects.requireNonNull(status);
            Objects.requireNonNull(role);
            if ((bundleGroup == null) != (role == PricingRole.NONE)) {
                throw new IllegalArgumentException("Bundle pricing group and parent/leaf role must be paired");
            }
            if (bundleGroup != null) {
                PricingPortion.requireIdentity(bundleGroup, 64);
            }
        }
    }

    public record CouponUse(AssetRef asset, BenefitKind kind) {
        public CouponUse {
            Objects.requireNonNull(asset);
            if (kind != BenefitKind.PERCENT_COUPON && kind != BenefitKind.CASH_COUPON
                    && kind != BenefitKind.DEDUCTION_COUPON) {
                throw new IllegalArgumentException("Historical root use must identify a coupon");
            }
        }
    }
}
