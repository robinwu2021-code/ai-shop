package ai.neargo.shop.scenario;

import ai.neargo.shop.promotion.entity.PmtUserCoupon;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponScopeMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.UserCouponMapper;
import ai.neargo.shop.spi.marketing.CouponPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 次卡（一张券能用多次）在 A 单用一次、B 单再用一次，取消 A 单要退回 A 单那一次。
 *
 * <p>此前退券按 {@code pmt_user_coupon.order_no} 找券，而每用一次那一列就被改成最近那一单 ——
 * 取消 A 单找不到任何一张券，那一次使用永久消失；用户手上的次卡凭空少一次。
 * 每一次使用都在 {@code pmt_apply} 记着订单号，退券该按它找。
 */
@SpringBootTest
@ActiveProfiles("test")
class CouponMultiUseReleaseTest {

    @Autowired private CouponPort couponPort;
    @Autowired private CouponMapper coupons;
    @Autowired private UserCouponMapper held;
    @Autowired private CouponScopeMapper scopes;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("★★★ 次卡 A 单用一次、B 单用一次，取消 A：退回一次，B 那一次不动；再取消 B 退回到 0")
    void releasingAnEarlierOrderRestoresItsUse() {
        int n = PmtCouponFixture.next();
        String user = "U-MUR-" + n;
        String entity = "M-MUR-" + n;
        String uc = PmtCouponFixture.give(coupons, held, scopes, user, entity, c -> c.setTimesTotal(3), List.of());
        var groups = List.of(new CouponPort.MerchantAmount(entity, 6_000));

        couponPort.markUsed(user, uc, "O-MUR-A" + n, couponPort.allocate(user, uc, groups));
        couponPort.markUsed(user, uc, "O-MUR-B" + n, couponPort.allocate(user, uc, groups));
        assertThat(timesUsed(uc)).isEqualTo(2);

        couponPort.release("O-MUR-A" + n);
        assertThat(timesUsed(uc)).as("★ 取消 A 单，A 那一次没退回来").isEqualTo(1);
        assertThat(status(uc)).isEqualTo(PmtUserCoupon.UNUSED);
        assertThat(reverted("O-MUR-A" + n)).isTrue();
        assertThat(reverted("O-MUR-B" + n)).as("B 单那一次还在用着").isFalse();

        couponPort.release("O-MUR-A" + n);
        assertThat(timesUsed(uc)).as("重复取消不多退").isEqualTo(1);

        couponPort.release("O-MUR-B" + n);
        assertThat(timesUsed(uc)).isZero();
    }

    private int timesUsed(String uc) {
        return jdbc.queryForObject("select times_used from pmt_user_coupon where user_coupon_no=?", Integer.class, uc);
    }

    private String status(String uc) {
        return jdbc.queryForObject("select status from pmt_user_coupon where user_coupon_no=?", String.class, uc);
    }

    private boolean reverted(String orderNo) {
        return jdbc.queryForObject("select count(*) from pmt_apply where order_no=? and promo_type='COUPON' "
                + "and reverted_at is not null", Integer.class, orderNo) > 0;
    }
}
