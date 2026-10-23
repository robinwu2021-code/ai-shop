package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.entity.MktUserCoupon;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers;
import ai.neargo.shop.marketing.port.CouponPortImpl;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.entity.PmtUserCoupon;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.UserCouponMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ApplyMapper;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.promotion.service.impl.CouponAllocServiceImpl;
import ai.neargo.shop.spi.entitlement.LegacyCouponWriteFencePort;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.spi.marketing.CouponPort.MerchantAmount;

/** Real existing allocators with only row reads substituted; expectations are historical literal outcomes. */
class RetailPricingCompatibilityTest {
    @Test
    void legacyAndPromotionPercentKeepMinorFloorAndCap() {
        var pmt = modernCoupon();
        pmt.setBenefitMode(PmtCoupon.PERCENT);
        pmt.setBenefitValue(8500L);
        var old = legacyCoupon();
        old.setType(MktCoupon.DISCOUNT);
        old.setDiscountRate(8500);
        assertThat(modern(pmt).allocate("U", "UC", List.of(new MerchantAmount("M", 101, "S")))
                .totalDiscount()).isEqualTo(15);
        assertThat(legacy(old).allocate("U", "UC", List.of(new MerchantAmount("M", 101, "S")))
                .totalDiscount()).isEqualTo(15);
        pmt.setBenefitCapMinor(10L);
        old.setMaxDiscountMinor(10L);
        assertThat(modern(pmt).allocate("U", "UC", List.of(new MerchantAmount("M", 101, "S")))
                .totalDiscount()).isEqualTo(10);
        assertThat(legacy(old).allocate("U", "UC", List.of(new MerchantAmount("M", 101, "S")))
                .totalDiscount()).isEqualTo(10);
    }

    @Test
    void originalAllocationRemainderAndPlatformFundingStayUnchanged() {
        var pmt = modernCoupon();
        pmt.setBenefitValue(5L);
        var old = legacyCoupon();
        old.setFaceMinor(5L);
        var groups = List.of(new MerchantAmount("M1", 10, "S1"), new MerchantAmount("M2", 20, "S2"));
        for (var allocation : List.of(modern(pmt).allocate("U", "UC", groups),
                legacy(old).allocate("U", "UC", groups))) {
            assertThat(allocation.totalDiscount()).isEqualTo(5);
            assertThat(allocation.byMerchant()).isFalse();
            assertThat(allocation.shares()).extracting(CouponPort.MerchantDiscount::amount).containsExactly(2L, 3L);
            assertThat(allocation.discountOf("M1", "S1")).isEqualTo(2);
            assertThat(allocation.discountOf("M2", "S2")).isEqualTo(3);
        }
    }

    @Test
    void merchantFundingOnlyDiscountsOwnedEntityAndPreservesStoreGroups() {
        var pmt = modernCoupon();
        pmt.setEntityNo("M1");
        pmt.setFunder(PmtCoupon.BY_MERCHANT);
        pmt.setBenefitValue(5L);
        var old = legacyCoupon();
        old.setEntityNo("M1");
        old.setFunder(MktCoupon.BY_MERCHANT);
        old.setFaceMinor(5L);
        var groups = List.of(new MerchantAmount("M1", 10, "S1"), new MerchantAmount("M1", 20, "S2"),
                new MerchantAmount("M2", 10000, "S3"));
        for (var allocation : List.of(modern(pmt).allocate("U", "UC", groups),
                legacy(old).allocate("U", "UC", groups))) {
            assertThat(allocation.byMerchant()).isTrue();
            assertThat(allocation.discountOf("M1")).isEqualTo(5);
            assertThat(allocation.discountOf("M1", "S1")).isEqualTo(2);
            assertThat(allocation.discountOf("M1", "S2")).isEqualTo(3);
            assertThat(allocation.discountOf("M2", "S3")).isZero();
            assertThat(allocation.shares()).hasSize(2);
        }
    }

    @Test
    void cashFaceCapsAtApplicableBaseAndEqualBaseTieUsesOriginalOrder() {
        var pmt = modernCoupon();
        pmt.setBenefitValue(1000L);
        var old = legacyCoupon();
        old.setFaceMinor(1000L);
        assertThat(modern(pmt).allocate("U", "UC", List.of(new MerchantAmount("M", 100, "S")))
                .totalDiscount()).isEqualTo(100);
        assertThat(legacy(old).allocate("U", "UC", List.of(new MerchantAmount("M", 100, "S")))
                .totalDiscount()).isEqualTo(100);
        pmt.setBenefitValue(1L);
        old.setFaceMinor(1L);
        var groups = List.of(new MerchantAmount("M", 1, "S1"), new MerchantAmount("M", 1, "S2"));
        assertThat(modern(pmt).allocate("U", "UC", groups).shares())
                .extracting(CouponPort.MerchantDiscount::amount).containsExactly(0L, 1L);
        assertThat(legacy(old).allocate("U", "UC", groups).shares())
                .extracting(CouponPort.MerchantDiscount::amount).containsExactly(0L, 1L);
    }

    private static CouponAllocServiceImpl modern(PmtCoupon coupon) {
        var coupons = mock(CouponMapper.class);
        var users = mock(UserCouponMapper.class);
        var user = new PmtUserCoupon();
        user.setUserCouponNo("UC");
        user.setCouponNo("C");
        user.setUserNo("U");
        user.setStatus(PmtUserCoupon.UNUSED);
        when(coupons.selectOne(any())).thenReturn(coupon);
        when(users.selectOne(any())).thenReturn(user);
        return new CouponAllocServiceImpl(coupons, users, mock(ApplyMapper.class),
                mock(LegacyCouponSourceFenceService.class));
    }

    private static CouponPortImpl legacy(MktCoupon coupon) {
        var coupons = mock(CouponMappers.CouponMapper.class);
        var users = mock(CouponMappers.UserCouponMapper.class);
        var user = new MktUserCoupon();
        user.setUserCouponNo("UC");
        user.setCouponNo("C");
        user.setUserNo("U");
        user.setStatus(MktUserCoupon.UNUSED);
        when(coupons.selectOne(any())).thenReturn(coupon);
        when(users.selectOne(any())).thenReturn(user);
        return new CouponPortImpl(coupons, users, mock(LegacyCouponWriteFencePort.class));
    }

    private static PmtCoupon modernCoupon() {
        var coupon = new PmtCoupon();
        coupon.setCouponNo("C");
        coupon.setStatus(PmtCoupon.ACTIVE);
        coupon.setBenefitMode(PmtCoupon.CASH);
        coupon.setFunder(PmtCoupon.BY_PLATFORM);
        coupon.setBenefitValue(100L);
        return coupon;
    }

    private static MktCoupon legacyCoupon() {
        var coupon = new MktCoupon();
        coupon.setCouponNo("C");
        coupon.setStatus(MktCoupon.ACTIVE);
        coupon.setStartAt(0L);
        coupon.setEndAt(Long.MAX_VALUE);
        coupon.setType("CASH");
        coupon.setFunder(MktCoupon.BY_PLATFORM);
        coupon.setFaceMinor(100L);
        return coupon;
    }
}
