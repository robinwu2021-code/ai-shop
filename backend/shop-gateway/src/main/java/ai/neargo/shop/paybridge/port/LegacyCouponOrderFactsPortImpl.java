package ai.neargo.shop.paybridge.port;

import ai.neargo.shop.marketing.coupon.CouponService;
import ai.neargo.shop.promotion.service.CouponAllocService;
import ai.neargo.shop.spi.marketing.LegacyCouponOrderFactsPort;
import org.springframework.stereotype.Component;

/** Only the composition root knows both legacy owners; neither domain reads the other's tables. */
@Component
public class LegacyCouponOrderFactsPortImpl implements LegacyCouponOrderFactsPort {
    private final CouponService legacy;
    private final CouponAllocService promotion;
    public LegacyCouponOrderFactsPortImpl(CouponService legacy, CouponAllocService promotion) {
        this.legacy = legacy;
        this.promotion = promotion;
    }
    @Override
    public boolean hasActiveBinding(String tenantNo, String orderNo) {
        return legacy.hasActiveBinding(tenantNo, orderNo) || promotion.hasActiveBinding(tenantNo, orderNo);
    }
}
