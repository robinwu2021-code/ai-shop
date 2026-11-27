package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.service.CouponAllocService;
import ai.neargo.shop.promotion.service.CouponService;
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired private CouponService merchantCoupons;
    @Autowired private CouponAllocService checkout;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CouponMapper coupons;
    @Autowired private UserCouponMapper held;
    @Autowired private CouponScopeMapper scopes;

    @Test
    void editPreservesIssuedWalletPriceAndOriginalCheckoutScope() {
        int n = PmtCouponFixture.next();
        String entity = "M-VER-" + n;
        String store = "S-VER-" + n;
        String buyer = "U-VER-" + n;
        StoreOwnerFixture.store(jdbc, entity, store);
        var root = merchantCoupons.save(entity, center(null, "首版", 100L, "ALL", List.of()), "OP");
        var first = merchantCoupons.receive(buyer, root.couponNo());

        merchantCoupons.save(entity, center(root.couponNo(), "门店版", 250L, "STORE", List.of(store)), "OP");
        var second = merchantCoupons.receive(buyer, root.couponNo());
        var outside = List.of(new CouponPort.MerchantAmount(entity, 8000, "OTHER"));
        assertThat(checkout.quoteOrdinary(buyer, first.userCouponNo(), outside).totalDiscount()).isEqualTo(100);
        assertThat(merchantCoupons.held(buyer)).filteredOn(v -> v.userCouponNo().equals(first.userCouponNo()))
                .singleElement().satisfies(v -> assertThat(v.coupon().benefitValue()).isEqualTo(100));
        assertThat(merchantCoupons.myCoupons(buyer)).filteredOn(v -> v.userCouponNo().equals(first.userCouponNo()))
                .singleElement().satisfies(v -> assertThat(v.title()).isEqualTo("首版"));

        merchantCoupons.save(entity, center(root.couponNo(), "第三版", 500L, "ALL", List.of()), "OP");
        assertThat(checkout.allocate(buyer, second.userCouponNo(),
                List.of(new CouponPort.MerchantAmount(entity, 8000, store))).totalDiscount()).isEqualTo(250);
        assertThatThrownBy(() -> checkout.allocate(buyer, second.userCouponNo(), outside))
                .isInstanceOf(BizException.class).hasMessage(ErrorCode.COUPON_NOT_APPLICABLE.name());
    }

    private static CouponSaveCmd center(String no, String title, long amount, String scope, List<String> refs) {
        return new CouponSaveCmd(no, title, PmtCoupon.CASH, amount, null, null, 0L, null,
                scope, refs, null, PmtCoupon.RELATIVE, null, null, 7,
                PmtCoupon.ISSUE_CENTER, PmtCoupon.REDEEM_ORDER, 1, 10, 2, null);
    }

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
