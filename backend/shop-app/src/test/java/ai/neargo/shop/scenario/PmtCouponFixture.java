package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.entity.PmtCouponScope;
import ai.neargo.shop.promotion.entity.PmtUserCoupon;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponScopeMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.UserCouponMapper;

import java.util.List;
import java.util.function.Consumer;

/**
 * 直接落一张新模型的券模板并发到某人手上 —— 绕开建券校验，才摆得出「存量里已经有的那种券」。
 * 默认：商家券、现金 10 元、无门槛、全店、绝对有效期（昨天起 7 天）、下单抵扣、一次性。
 */
final class PmtCouponFixture {

    private static int seq = 7700;

    private PmtCouponFixture() {
    }

    /** @return 用户券号 */
    static String give(CouponMapper coupons, UserCouponMapper held, CouponScopeMapper scopes,
                       String userNo, String entityNo, Consumer<PmtCoupon> tweak, List<String> storeRefs) {
        int n = ++seq;
        long now = System.currentTimeMillis();
        PmtCoupon c = new PmtCoupon();
        c.setCouponNo("PC-FX" + n);
        c.setEntityNo(entityNo);
        c.setFunder(PmtCoupon.BY_MERCHANT);
        c.setTitle("夹具券" + n);
        c.setBenefitMode(PmtCoupon.CASH);
        c.setBenefitValue(1_000L);
        c.setMinAmountMinor(0L);
        c.setScopeType(PmtCoupon.SCOPE_ALL);
        c.setValidityMode(PmtCoupon.ABSOLUTE);
        c.setStartAt(now - 86_400_000L);
        c.setEndAt(now + 7 * 86_400_000L);
        c.setIssueMode(PmtCoupon.ISSUE_TARGETED);
        c.setRedeemMode(PmtCoupon.REDEEM_ORDER);
        c.setTimesTotal(1);
        c.setReceivedCount(1);
        c.setPerUserLimit(1);
        c.setStatus(PmtCoupon.ACTIVE);
        tweak.accept(c);
        DataScopeContext.executeWithoutScope(() -> coupons.insert(c));
        for (String ref : storeRefs) {
            PmtCouponScope s = new PmtCouponScope();
            s.setCouponNo(c.getCouponNo());
            s.setScopeType(PmtCoupon.SCOPE_STORE);
            s.setRefNo(ref);
            DataScopeContext.executeWithoutScope(() -> scopes.insert(s));
        }
        PmtUserCoupon uc = new PmtUserCoupon();
        uc.setUserCouponNo("PU-FX" + n);
        uc.setCouponNo(c.getCouponNo());
        uc.setUserNo(userNo);
        uc.setEntityNo(entityNo);
        uc.setStatus(PmtUserCoupon.UNUSED);
        uc.setTimesUsed(0);
        uc.setReceivedAt(now);
        // 与发放时同一口径：绝对有效期的券到期时刻就是模板的结束时刻
        uc.setExpireAt(c.getEndAt());
        DataScopeContext.executeWithoutScope(() -> held.insert(uc));
        return uc.getUserCouponNo();
    }

    static int next() {
        return ++seq;
    }
}
