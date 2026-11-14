package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponScopeMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.UserCouponMapper;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.spi.marketing.CouponPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 绝对有效期的券在开始时刻之前不能用 —— 不论它是怎么到手的。
 *
 * <p>领券中心按窗口判「能不能领」，但定向发放不判开始时刻；而「能不能用」只看状态、次数、到期 ——
 * 「双十一当天可用」的券，十一月一号定向发出去，当天就能抵扣。
 */
@SpringBootTest
@ActiveProfiles("test")
class CouponNotStartedTest {

    private static final long DAY = 86_400_000L;

    @Autowired private CouponPort couponPort;
    @Autowired private CouponService couponService;
    @Autowired private CouponMapper coupons;
    @Autowired private UserCouponMapper held;
    @Autowired private CouponScopeMapper scopes;

    @Test
    @DisplayName("★★★ 明天才开始的券：今天下单不能抵扣，券包里也不显示「可用」")
    void absoluteCouponIsNotUsableBeforeStart() {
        int n = PmtCouponFixture.next();
        String user = "U-CNS-" + n;
        String entity = "M-CNS-" + n;
        long now = System.currentTimeMillis();
        String uc = PmtCouponFixture.give(coupons, held, scopes, user, entity, c -> {
            c.setStartAt(now + DAY);
            c.setEndAt(now + 7 * DAY);
        }, List.of());

        assertThatThrownBy(() -> couponPort.allocate(user, uc, List.of(new CouponPort.MerchantAmount(entity, 6_000))))
                .as("★ 还没开始的券抵扣了")
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.COUPON_NOT_APPLICABLE.name());
        assertThat(couponPort.heldUsable(user)).doesNotContain(uc);
        assertThat(couponService.held(user)).filteredOn(h -> h.userCouponNo().equals(uc))
                .singleElement().satisfies(h -> assertThat(h.usableNow()).isFalse());
    }

    @Test
    @DisplayName("★ 已经开始的照常能用（对照）")
    void startedCouponIsUsable() {
        int n = PmtCouponFixture.next();
        String user = "U-CNS-" + n;
        String entity = "M-CNS-" + n;
        String uc = PmtCouponFixture.give(coupons, held, scopes, user, entity, c -> { }, List.of());
        assertThat(couponPort.allocate(user, uc, List.of(new CouponPort.MerchantAmount(entity, 6_000)))
                .totalDiscount()).isEqualTo(1_000);
        assertThat(couponPort.heldUsable(user)).contains(uc);
    }
}
