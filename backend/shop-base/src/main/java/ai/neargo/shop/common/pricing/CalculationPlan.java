package ai.neargo.shop.common.pricing;

import java.util.List;
import java.util.Objects;
import ai.neargo.shop.common.pricing.BenefitSelection.Mode;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.AssetRef;

/** Frozen business-stage evidence. It is neither a final taxed quote nor authorization to consume any asset. */
public record CalculationPlan(Mode mode, PricingRuleFacts facts, EffectivePricingConfig config,
        PricingRuleCandidate candidate, List<AssetRef> usedCoupons, List<AssetRef> unusedCoupons) {
    public CalculationPlan {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(facts);
        Objects.requireNonNull(config);
        Objects.requireNonNull(candidate);
        usedCoupons = List.copyOf(usedCoupons);
        unusedCoupons = List.copyOf(unusedCoupons);
    }

    public enum Stage { BUSINESS_PRE_TAX }

    /** Prevents callers from treating pre-tax search output as a final checkout price. */
    public Stage stage() {
        return Stage.BUSINESS_PRE_TAX;
    }
}
