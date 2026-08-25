package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.entity.MktUserCoupon;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers;
import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.entity.PmtUserCoupon;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ApplyMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.UserCouponMapper;
import ai.neargo.shop.spi.marketing.CouponPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 新模型的券在下单链路上算得对、退得回（P4）。
 *
 * <p>入口是 {@link CouponPort} 本身 —— 也就是<b>下单真正调用的那个 Bean</b>，
 * 而不是新实现。这样这组用例连**路由有没有接对**一起守着：
 * 路由挂了的话，新券会被送去老实现，那边查不到就抛「券不可用」。
 */
@SpringBootTest
@ActiveProfiles("test")
class PmtCouponAllocFlowTest {

    @Autowired
    private CouponPort couponPort;

    @Autowired
    private CouponMapper couponMapper;

    @Autowired
    private UserCouponMapper userCouponMapper;

    @Autowired
    private ApplyMapper applyMapper;

    @Autowired
    private CouponMappers.CouponMapper mktCouponMapper;

    @Autowired
    private CouponMappers.UserCouponMapper mktUserCouponMapper;

    private static int seq = 900;

    /** 建一张商家券并发到某人手上，返回用户券号 */
    private String give(String userNo, String entityNo, long face, long threshold,
                        String redeemMode, int timesTotal) {
        int n = ++seq;
        PmtCoupon c = new PmtCoupon();
        c.setCouponNo("PC-T" + n);
        c.setEntityNo(entityNo);
        c.setFunder(PmtCoupon.BY_MERCHANT);
        c.setTitle("测试券" + n);
        c.setBenefitMode(PmtCoupon.CASH);
        c.setBenefitValue(face);
        c.setMinAmountMinor(threshold);
        c.setScopeType(PmtCoupon.SCOPE_ALL);
        c.setValidityMode(PmtCoupon.ABSOLUTE);
        c.setIssueMode(PmtCoupon.ISSUE_TARGETED);
        c.setRedeemMode(redeemMode);
        c.setTimesTotal(timesTotal);
        c.setReceivedCount(0);
        c.setPerUserLimit(1);
        c.setStatus(PmtCoupon.ACTIVE);
        couponMapper.insert(c);

        PmtUserCoupon uc = new PmtUserCoupon();
        uc.setUserCouponNo("PU-T" + n);
        uc.setCouponNo(c.getCouponNo());
        uc.setUserNo(userNo);
        uc.setEntityNo(entityNo);
        uc.setStatus(PmtUserCoupon.UNUSED);
        uc.setTimesUsed(0);
        uc.setReceivedAt(System.currentTimeMillis());
        uc.setExpireAt(System.currentTimeMillis() + 7 * 86400_000L);
        userCouponMapper.insert(uc);
        return uc.getUserCouponNo();
    }

    @Test
    @DisplayName("★★★ 新券走新实现：满减照减，且分摊按商品额比例")
    void newCouponAllocates() {
        String user = "U-PMT-" + (++seq);
        String uc = give(user, "M-A", 1_000, 5_000, PmtCoupon.REDEEM_ORDER, 1);

        // 只有 M-A 是本店，M-B 那部分既不计门槛也不减钱
        CouponPort.Allocation a = couponPort.allocate(user, uc, List.of(
                new CouponPort.MerchantAmount("M-A", 6_000),
                new CouponPort.MerchantAmount("M-B", 4_000)));

        assertThat(a.totalDiscount()).isEqualTo(1_000);
        assertThat(a.byMerchant()).as("商家券的钱商家出").isTrue();
        assertThat(a.discountOf("M-A")).isEqualTo(1_000);
        assertThat(a.discountOf("M-B")).as("别家的单一分都不该减").isZero();
    }

    @Test
    @DisplayName("★★ 不够门槛就拒 —— 门槛只算本店那部分，别被别家的金额凑满")
    void thresholdCountsOwnStoreOnly() {
        String user = "U-PMT-" + (++seq);
        String uc = give(user, "M-A", 1_000, 5_000, PmtCoupon.REDEEM_ORDER, 1);

        assertThatThrownBy(() -> couponPort.allocate(user, uc, List.of(
                new CouponPort.MerchantAmount("M-A", 3_000),
                new CouponPort.MerchantAmount("M-B", 9_000))))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.COUPON_NOT_APPLICABLE.name());
    }

    @Test
    @DisplayName("★★★ 到店核销的券不参与下单算价 —— 一张券两条路一定会被用两次")
    void storeCodeCouponNeverDeductsAtCheckout() {
        String user = "U-PMT-" + (++seq);
        String uc = give(user, "M-A", 1_000, 0, PmtCoupon.REDEEM_STORE_CODE, 1);

        assertThatThrownBy(() -> couponPort.allocate(user, uc,
                List.of(new CouponPort.MerchantAmount("M-A", 9_000))))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.COUPON_NOT_APPLICABLE.name());
    }

    @Test
    @DisplayName("★★★ 用掉之后留一行 pmt_apply；取消订单退回券，那一行标撤销而不是删掉")
    void useThenReleaseLeavesATrail() {
        String user = "U-PMT-" + (++seq);
        String uc = give(user, "M-A", 1_000, 0, PmtCoupon.REDEEM_ORDER, 1);
        String orderNo = "O-PMT-" + seq;

        CouponPort.Allocation alloc = couponPort.allocate(user, uc,
                List.of(new CouponPort.MerchantAmount("M-A", 9_000)));
        couponPort.markUsed(user, uc, orderNo, alloc);

        PmtUserCoupon after = userCouponMapper.selectOne(Wrappers.<PmtUserCoupon>lambdaQuery()
                .eq(PmtUserCoupon::getUserCouponNo, uc).last("limit 1"));
        assertThat(after.getStatus()).isEqualTo(PmtUserCoupon.USED);
        assertThat(after.getOrderNo()).isEqualTo(orderNo);

        List<PmtApply> trail = applyMapper.selectList(Wrappers.<PmtApply>lambdaQuery()
                .eq(PmtApply::getOrderNo, orderNo));
        assertThat(trail).as("券的每一次使用都要留一行").hasSize(1);
        assertThat(trail.get(0).getPromoNo()).isEqualTo(uc);
        assertThat(trail.get(0).getRevertedAt()).isNull();
        // 记的是**当时减了多少**：事后重算会因为门槛/封顶被改过而对不上账
        assertThat(trail.get(0).getAmountMinor())
                .as("pmt_apply 要记下这一单实际减掉的钱").isEqualTo(1_000);

        couponPort.release(orderNo);

        PmtUserCoupon back = userCouponMapper.selectOne(Wrappers.<PmtUserCoupon>lambdaQuery()
                .eq(PmtUserCoupon::getUserCouponNo, uc).last("limit 1"));
        assertThat(back.getStatus()).as("退回来还能再用").isEqualTo(PmtUserCoupon.UNUSED);
        assertThat(back.getOrderNo()).as("订单号必须清掉 —— 留着会在下次取消时退错单").isNull();

        List<PmtApply> afterRelease = applyMapper.selectList(Wrappers.<PmtApply>lambdaQuery()
                .eq(PmtApply::getOrderNo, orderNo));
        assertThat(afterRelease).as("不删行：发生过又撤销了，与没发生过不是一回事").hasSize(1);
        assertThat(afterRelease.get(0).getRevertedAt()).isNotNull();
    }

    @Test
    @DisplayName("★★★ 次卡用一次不算用完 —— 置 USED 会让剩下四杯豆浆凭空消失")
    void punchCardKeepsRemainingTimes() {
        String user = "U-PMT-" + (++seq);
        String uc = give(user, "M-A", 0, 0, PmtCoupon.REDEEM_ORDER, 5);

        couponPort.markUsed(user, uc, "O-CARD-1-" + seq, CouponPort.Allocation.none());
        PmtUserCoupon one = userCouponMapper.selectOne(Wrappers.<PmtUserCoupon>lambdaQuery()
                .eq(PmtUserCoupon::getUserCouponNo, uc).last("limit 1"));
        assertThat(one.getTimesUsed()).isEqualTo(1);
        assertThat(one.getStatus()).as("还剩四次，不能算用完").isEqualTo(PmtUserCoupon.UNUSED);
    }

    @Test
    @DisplayName("★★★ 存量券照旧走老实现 —— 两套表并存期间，用户手上两种券都得能用")
    void legacyCouponStillWorksThroughTheRouter() {
        int n = ++seq;
        String user = "U-LEGACY-" + n;
        long now = System.currentTimeMillis();

        MktCoupon c = new MktCoupon();
        c.setCouponNo("MC-T" + n);
        c.setTitle("存量券" + n);
        c.setType(MktCoupon.FULL_CUT);
        c.setFaceMinor(800L);
        c.setThresholdMinor(0L);
        c.setFunder(MktCoupon.BY_MERCHANT);
        c.setEntityNo("M-A");
        c.setStartAt(now - 86400_000L);
        c.setEndAt(now + 86400_000L);
        c.setStatus("ACTIVE");
        c.setReceivedCount(0);
        c.setPerUserLimit(1);
        mktCouponMapper.insert(c);

        MktUserCoupon uc = new MktUserCoupon();
        uc.setUserCouponNo("MUC-T" + n);
        uc.setCouponNo(c.getCouponNo());
        uc.setUserNo(user);
        uc.setStatus(MktUserCoupon.UNUSED);
        uc.setReceivedAt(now);
        mktUserCouponMapper.insert(uc);

        CouponPort.Allocation a = couponPort.allocate(user, uc.getUserCouponNo(),
                List.of(new CouponPort.MerchantAmount("M-A", 9_000)));
        assertThat(a.totalDiscount()).as("路由把它交给老实现，减的钱一分不变").isEqualTo(800);
    }

    @Test
    @DisplayName("★★ 两边都不存在的券号：答案是「不可用」，不是静默返回 0")
    void unknownCouponIsRejectedNotIgnored() {
        assertThatThrownBy(() -> couponPort.allocate("U-NOBODY", "MUC-404",
                List.of(new CouponPort.MerchantAmount("M-A", 9_000))))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.COUPON_NOT_APPLICABLE.name());
    }
}
