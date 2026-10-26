package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponScopeMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.UserCouponMapper;
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
 * 「指定门店」的下单抵扣券只在那几家店出货的单上能用。
 *
 * <p>建券允许 SCOPE_STORE（下单抵扣只放行全店与指定门店），门店列表也存进了 {@code pmt_coupon_scope}，
 * 而算价只按主体筛 —— 写着「仅限河坊街店」的券在总店的单上照样减。
 */
@SpringBootTest
@ActiveProfiles("test")
class CouponStoreScopeTest {

    @Autowired private CouponPort couponPort;
    @Autowired private CouponMapper coupons;
    @Autowired private UserCouponMapper held;
    @Autowired private CouponScopeMapper scopes;

    @Test
    @DisplayName("★★★ 门店券：只减指定门店出货的那一组；别家店出货的单当场拒")
    void storeScopedCouponOnlyAppliesAtItsStores() {
        int n = PmtCouponFixture.next();
        String user = "U-CSS-" + n;
        String entity = "M-CSS-" + n;
        String uc = PmtCouponFixture.give(coupons, held, scopes, user, entity,
                c -> c.setScopeType(PmtCoupon.SCOPE_STORE), List.of("ST-HFJ-" + n));

        assertThatThrownBy(() -> couponPort.allocate(user, uc,
                List.of(new CouponPort.MerchantAmount(entity, 6_000, "ST-HQ-" + n))))
                .as("★ 仅限河坊街店的券，在总店出货的单上减了钱")
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.COUPON_NOT_APPLICABLE.name());

        CouponPort.Allocation a = couponPort.allocate(user, uc, List.of(
                new CouponPort.MerchantAmount(entity, 6_000, "ST-HFJ-" + n),
                new CouponPort.MerchantAmount(entity, 4_000, "ST-HQ-" + n)));
        assertThat(a.totalDiscount()).isEqualTo(1_000);
        assertThat(a.shares()).as("只分摊到指定门店那一组")
                .allSatisfy(s -> assertThat(s.storeNo()).isEqualTo("ST-HFJ-" + n));
    }

    @Test
    @DisplayName("★ 全店券不受影响：哪家店出货都能用")
    void entityWideCouponStillAppliesEverywhere() {
        int n = PmtCouponFixture.next();
        String user = "U-CSS-" + n;
        String entity = "M-CSS-" + n;
        String uc = PmtCouponFixture.give(coupons, held, scopes, user, entity, c -> { }, List.of());
        assertThat(couponPort.allocate(user, uc,
                List.of(new CouponPort.MerchantAmount(entity, 6_000, "ST-ANY-" + n))).totalDiscount())
                .isEqualTo(1_000);
    }
}
