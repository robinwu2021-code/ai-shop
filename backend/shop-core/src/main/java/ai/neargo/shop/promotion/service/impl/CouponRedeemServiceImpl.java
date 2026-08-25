package ai.neargo.shop.promotion.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.entity.PmtUserCoupon;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ApplyMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.UserCouponMapper;
import ai.neargo.shop.promotion.service.CouponRedeemService;
import ai.neargo.shop.spi.user.PersonPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 到店核销。
 *
 * <p><b>扣次数用带条件的 UPDATE，不是「读出来 +1 再写回去」</b>：
 * 收银台前店员连点两下、或者两个店员同时扫同一张码，读改写会让一张 5 次的次卡
 * 被扣成 4 次却核销了两杯 —— 而这种错在对账时看起来只是「数字对不上」。
 *
 * <p>另有一层 <b>3 秒幂等窗口</b>：连点的那一下应当返回<b>上一次的结果</b>，
 * 而不是报「已核销」。报错会让店员以为没成功，于是再点一次 —— 真正的重复核销
 * 往往就是这么来的。
 */
@Service
public class CouponRedeemServiceImpl implements CouponRedeemService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CouponRedeemServiceImpl.class);

    /** 连点窗口。3 秒是收银台前「手抖再按一下」的量级，不是重试策略 */
    private static final long DEDUP_WINDOW_MS = 3_000L;

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final ApplyMapper applyMapper;
    private final PersonPort personPort;

    public CouponRedeemServiceImpl(CouponMapper couponMapper, UserCouponMapper userCouponMapper,
                                   ApplyMapper applyMapper, PersonPort personPort) {
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
        this.applyMapper = applyMapper;
        this.personPort = personPort;
    }

    @Override
    public RedeemView peek(String entityNo, String code) {
        PmtUserCoupon uc = byCode(entityNo, code);
        PmtCoupon c = template(uc.getCouponNo());
        int total = c.timesTotalOrOne();
        int used = nz(uc.getTimesUsed());
        String reason = whyNot(uc, c, System.currentTimeMillis());
        return new RedeemView(uc.getUserCouponNo(), c.getCouponNo(), c.getTitle(),
                benefitText(c), null, nz(uc.getExpireAt()), total, used,
                Math.max(0, total - used), reason == null, reason);
    }

    @Override
    @Transactional
    public RedeemResult redeem(String entityNo, String code, String storeNo, String operatorNo) {
        PmtUserCoupon uc = byCode(entityNo, code);
        PmtCoupon c = template(uc.getCouponNo());
        long now = System.currentTimeMillis();

        /*
         * 连点窗口：3 秒内同一张券已经核销过一次，就把上一次的结果原样返回。
         * **不报「已核销」** —— 报错会让店员以为刚才那下没成功，于是再按一次。
         */
        List<PmtApply> recent = DataScopeContext.executeWithoutScope(() ->
                applyMapper.selectList(Wrappers.<PmtApply>lambdaQuery()
                        .eq(PmtApply::getPromoNo, uc.getUserCouponNo())
                        .eq(PmtApply::getRedeemMode, PmtCoupon.REDEEM_STORE_CODE)
                        .ge(PmtApply::getAppliedAt, now - DEDUP_WINDOW_MS)));
        if (!recent.isEmpty()) {
            int used = nz(uc.getTimesUsed());
            int total = c.timesTotalOrOne();
            log.info("[券] 3 秒内重复核销 {}，返回上一次的结果", uc.getUserCouponNo());
            return new RedeemResult(uc.getUserCouponNo(), used, Math.max(0, total - used),
                    used >= total, true);
        }

        String reason = whyNot(uc, c, now);
        if (reason != null) {
            throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
        }

        int total = c.timesTotalOrOne();
        int before = nz(uc.getTimesUsed());
        /*
         * **带条件的 UPDATE 才是这里的锁**：times_used 必须还等于我读到的值。
         * 两个店员同时扫同一张码时，只有一条能改到行 —— 另一条影响 0 行，当场拒绝。
         */
        int affected = DataScopeContext.executeWithoutScope(() ->
                userCouponMapper.update(null, Wrappers.<PmtUserCoupon>lambdaUpdate()
                        .eq(PmtUserCoupon::getId, uc.getId())
                        .eq(PmtUserCoupon::getTimesUsed, before)
                        .set(PmtUserCoupon::getTimesUsed, before + 1)
                        .set(PmtUserCoupon::getUsedAt, now)
                        // 用满才转 USED：用了一次就置 USED，剩下四杯豆浆凭空消失
                        .set(before + 1 >= total, PmtUserCoupon::getStatus, PmtUserCoupon.USED)));
        if (affected == 0) {
            throw BizException.of(ErrorCode.COUPON_REDEEM_CONFLICT);
        }

        PmtApply row = new PmtApply();
        row.setApplyNo(BizKey.next(BizKey.PROMO_APPLY));
        row.setPromoType(PmtApply.COUPON);
        row.setPromoNo(uc.getUserCouponNo());
        row.setUserNo(uc.getUserNo());
        row.setEntityNo(entityNo);
        row.setStoreNo(storeNo);
        row.setRedeemMode(PmtCoupon.REDEEM_STORE_CODE);
        row.setOperatorNo(operatorNo);
        // 兑换类券减的不是钱，金额记 0；现金/折扣券到店核销时同样不改订单金额
        row.setAmountMinor(0L);
        row.setFunder(c.getFunder());
        row.setAppliedAt(now);
        // reverted_at 恒为空：**线下核销不可撤销**，东西已经给出去了
        DataScopeContext.executeWithoutScope(() -> applyMapper.insert(row));

        log.info("[券] 到店核销 {} 门店 {} 店员 {} 第 {}/{} 次",
                uc.getUserCouponNo(), storeNo, operatorNo, before + 1, total);
        return new RedeemResult(uc.getUserCouponNo(), before + 1,
                Math.max(0, total - before - 1), before + 1 >= total, false);
    }

    /** 不能核销的原因；能核销返回 null。**分开写是为了让店员看到人话** */
    private String whyNot(PmtUserCoupon uc, PmtCoupon c, long now) {
        if (!PmtCoupon.REDEEM_STORE_CODE.equals(c.getRedeemMode())) {
            return "NOT_STORE_CODE";
        }
        if (PmtUserCoupon.REVOKED.equals(uc.getStatus())) {
            return "REVOKED";
        }
        if (nz(uc.getExpireAt()) > 0 && nz(uc.getExpireAt()) < now) {
            return "EXPIRED";
        }
        if (nz(uc.getTimesUsed()) >= c.timesTotalOrOne()) {
            return "USED_UP";
        }
        if (!PmtCoupon.ACTIVE.equals(c.getStatus())) {
            return "COUPON_INACTIVE";
        }
        return null;
    }

    private String benefitText(PmtCoupon c) {
        return switch (c.getBenefitMode()) {
            case PmtCoupon.GIFT -> "兑换";
            case PmtCoupon.PERCENT -> (nz(c.getBenefitValue()) / 1000.0) + " 折";
            case PmtCoupon.FREE_SHIP -> "免运费";
            default -> "减 " + (nz(c.getBenefitValue()) / 100.0) + " 元";
        };
    }

    /**
     * 按核销码找券。<b>必须带 entityNo</b> —— 码是短的、可猜的，
     * 不限主体的话，A 店店员能核销 B 店的券。
     */
    private PmtUserCoupon byCode(String entityNo, String code) {
        if (code == null || code.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PmtUserCoupon uc = DataScopeContext.executeWithoutScope(() ->
                userCouponMapper.selectOne(Wrappers.<PmtUserCoupon>lambdaQuery()
                        .eq(PmtUserCoupon::getRedeemCode, code.trim().toUpperCase())
                        .eq(PmtUserCoupon::getEntityNo, entityNo).last("limit 1")));
        if (uc == null) {
            throw BizException.of(ErrorCode.COUPON_CODE_NOT_FOUND);
        }
        return uc;
    }

    private PmtCoupon template(String couponNo) {
        PmtCoupon c = DataScopeContext.executeWithoutScope(() ->
                couponMapper.selectOne(Wrappers.<PmtCoupon>lambdaQuery()
                        .eq(PmtCoupon::getCouponNo, couponNo).last("limit 1")));
        if (c == null) {
            throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
        }
        return c;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
