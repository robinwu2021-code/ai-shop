package ai.neargo.shop.spi.marketing;

/** Exact legacy ownership fact; display titles and template visibility cannot prove absence of a coupon hold. */
public interface LegacyCouponOrderFactsPort {
    boolean hasActiveBinding(String tenantNo, String orderNo);
}
