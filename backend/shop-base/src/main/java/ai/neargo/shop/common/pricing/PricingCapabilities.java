package ai.neargo.shop.common.pricing;

/** Trusted provider capability evidence, not balances or caller-supplied permission to consume assets. */
public record PricingCapabilities(
        int maxCouponsPerOrder,
        int maxPercentCouponsPerOrder,
        boolean percentAndAmount,
        boolean stacking,
        boolean storedValue) {
    public PricingCapabilities {
        if (maxCouponsPerOrder < 0 || maxPercentCouponsPerOrder < 0
                || maxPercentCouponsPerOrder > maxCouponsPerOrder) {
            throw new IllegalArgumentException("Provider coupon capacities must be coherent");
        }
    }
}
