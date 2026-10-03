package ai.neargo.shop.scenario;

import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新旧券模型算出来的钱必须<b>一分不差</b>（P4）。
 *
 * <p>存量券的历史账是按老模型算出来的。换模型这件事本身不该改变任何一张已存在的券
 * 能减多少钱 —— 差一分，用户看到的价格就变了，而他不会认为是「我们换了模型」，
 * 他会认为是<b>算错了</b>。
 *
 * <p>这里同时守着 {@code V232__pmt_backfill_from_mkt.sql} 的翻译口径：
 * {@link #translate} 与那支迁移里的 CASE 一一对应，改一边就要改另一边。
 * 不用真库跑是故意的 —— 这条断言与数据库无关，它是两个函数的等价性。
 */
class CouponModelCompatTest {

    /** 与 V232 的 CASE 一一对应。**改迁移就要改这里**，否则这条守卫守的是另一套翻译 */
    private static PmtCoupon translate(MktCoupon c) {
        PmtCoupon p = new PmtCoupon();
        p.setBenefitMode(MktCoupon.DISCOUNT.equals(c.getType()) ? PmtCoupon.PERCENT : PmtCoupon.CASH);
        p.setBenefitValue(MktCoupon.DISCOUNT.equals(c.getType())
                ? (c.getDiscountRate() == null ? 0L : c.getDiscountRate().longValue())
                : (c.getFaceMinor() == null ? 0L : c.getFaceMinor()));
        p.setBenefitCapMinor(c.getMaxDiscountMinor());
        p.setMinAmountMinor(c.getThresholdMinor());
        return p;
    }

    private static MktCoupon cash(long face) {
        MktCoupon c = new MktCoupon();
        c.setType(MktCoupon.FULL_CUT);
        c.setFaceMinor(face);
        return c;
    }

    private static MktCoupon percent(int rate, Long cap) {
        MktCoupon c = new MktCoupon();
        c.setType(MktCoupon.DISCOUNT);
        c.setDiscountRate(rate);
        c.setMaxDiscountMinor(cap);
        return c;
    }

    /** 从 0 到 10 万分，挑上会出事的那些点：0、1 分、正好等于面额、封顶前后、大额 */
    private static final List<Long> BASES = List.of(
            0L, 1L, 99L, 100L, 1_000L, 1_999L, 2_000L, 2_001L, 5_000L,
            9_999L, 10_000L, 33_333L, 100_000L, 999_999L);

    @Test
    @DisplayName("★★★ 满减券：新旧两边逐分相同（含「券比商品还大」那种）")
    void cashCouponIdentical() {
        for (MktCoupon c : List.of(cash(500), cash(2_000), cash(10_000))) {
            PmtCoupon p = translate(c);
            for (long base : BASES) {
                assertThat(p.discountFor(base))
                        .as("满减 %d 分对 %d 分的商品", c.getFaceMinor(), base)
                        .isEqualTo(c.discountFor(base));
            }
        }
    }

    @Test
    @DisplayName("★★★ 折扣券：万分比与封顶两边一致 —— 这是老模型分岔过一次的地方")
    void percentCouponIdentical() {
        List<MktCoupon> cases = List.of(
                percent(8_500, 2_000L),   // 八五折封顶 20 元
                percent(9_000, 500L),     // 九折封顶 5 元：小额就顶到
                percent(5_000, 100_000L), // 五折，封顶高到基本不生效
                percent(9_900, 1L));      // 极端封顶：1 分
        for (MktCoupon c : cases) {
            PmtCoupon p = translate(c);
            for (long base : BASES) {
                assertThat(p.discountFor(base))
                        .as("折扣 %d/万 封顶 %s 对 %d 分的商品",
                                c.getDiscountRate(), c.getMaxDiscountMinor(), base)
                        .isEqualTo(c.discountFor(base));
            }
        }
    }

    @Test
    @DisplayName("★★ 存量券可能带着「封顶 0」（旧口径=不封顶），翻译过去也得照旧不封顶")
    void legacyZeroCapKeepsMeaning() {
        MktCoupon c = percent(8_000, 0L);
        PmtCoupon p = translate(c);
        for (long base : BASES) {
            assertThat(p.discountFor(base)).isEqualTo(c.discountFor(base));
        }
        // 顺带把口径本身钉住：0 = 不封顶，8 折就是实打实的两成
        assertThat(p.discountFor(10_000)).isEqualTo(2_000);
    }

    @Test
    @DisplayName("★★ 门槛也要照搬：老模型的 threshold_minor 就是新模型的 min_amount_minor")
    void thresholdTranslates() {
        MktCoupon c = cash(1_000);
        c.setThresholdMinor(5_000L);
        assertThat(translate(c).getMinAmountMinor()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("★ 兑换券与免运费券在算价里减 0 —— 它们给的不是钱，别在这一步减")
    void giftAndFreeShipDeductNothingHere() {
        PmtCoupon gift = new PmtCoupon();
        gift.setBenefitMode(PmtCoupon.GIFT);
        gift.setBenefitRef("G0001");
        PmtCoupon ship = new PmtCoupon();
        ship.setBenefitMode(PmtCoupon.FREE_SHIP);
        for (long base : BASES) {
            assertThat(gift.discountFor(base)).isZero();
            assertThat(ship.discountFor(base)).isZero();
        }
    }
}
