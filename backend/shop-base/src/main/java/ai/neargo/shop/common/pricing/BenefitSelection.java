package ai.neargo.shop.common.pricing;

import java.util.List;
import java.util.Objects;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.AssetRef;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.Benefit;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;

/** Backend-admitted coupon universe and fixed non-coupon intent; no asset discovery or authorization occurs here. */
public record BenefitSelection(Mode mode, List<CouponOption> coupons, List<Benefit> fixedBenefits) {
    public BenefitSelection {
        Objects.requireNonNull(mode);
        coupons = PricingRuleCandidate.bounded(coupons, 64);
        fixedBenefits = PricingRuleCandidate.bounded(fixedBenefits, 64);
    }

    public enum Mode { EXPLICIT, CANDIDATE_POOL, AUTO_BEST }

    public record CouponOption(AssetRef asset, BenefitKind kind) {
        public CouponOption {
            Objects.requireNonNull(asset);
            if (!isCoupon(kind)) {
                throw new IllegalArgumentException("A coupon option must identify a supported coupon kind");
            }
        }
    }

    static boolean isCoupon(BenefitKind kind) {
        return kind == BenefitKind.PERCENT_COUPON || kind == BenefitKind.CASH_COUPON
                || kind == BenefitKind.DEDUCTION_COUPON;
    }
}
