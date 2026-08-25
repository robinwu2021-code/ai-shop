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
import ai.neargo.shop.promotion.service.CouponAllocService;
import ai.neargo.shop.spi.marketing.CouponPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 新模型的算价与核销。
 *
 * <p><b>分摊规则与老实现逐字相同</b>（{@code CouponPortImpl} 的类注释）：
 * <pre>
 *   子单优惠 = round(券优惠 × 子单适用商品额 / 总适用商品额)
 *   尾数 → 给**适用商品额最大**的子单
 * </pre>
 * 尾数给最大单而不是第一单：按金额排序是稳定的，重算时结果一致；
 * 给「第一单」的话购物车排序一变，历史账就对不上了。
 *
 * <p><b>与老实现的唯一差别是多写一行 {@code pmt_apply}</b> ——
 * 券的每一次使用都要留一行，线上线下同一张表。老模型只在用户券上盖个
 * {@code order_no}，于是「这张券减了多少钱」只能靠重算，而重算依赖的规则会变。
 */
@Service
public class CouponAllocServiceImpl implements CouponAllocService {

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final ApplyMapper applyMapper;

    public CouponAllocServiceImpl(CouponMapper couponMapper, UserCouponMapper userCouponMapper,
                                  ApplyMapper applyMapper) {
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
        this.applyMapper = applyMapper;
    }

    @Override
    public boolean owns(String userNo, String userCouponNo) {
        if (userCouponNo == null || userCouponNo.isBlank()) {
            return false;
        }
        return find(userNo, userCouponNo) != null;
    }

    @Override
    public CouponPort.Allocation allocate(String userNo, String userCouponNo,
                                          List<CouponPort.MerchantAmount> groups) {
        if (userCouponNo == null || userCouponNo.isBlank() || groups.isEmpty()) {
            return CouponPort.Allocation.none();
        }
        PmtUserCoupon uc = require(userNo, userCouponNo);
        PmtCoupon coupon = template(uc.getCouponNo());
        assertUsable(uc, coupon);

        // 商家券只对本店商品计门槛、也只减本店的钱
        List<CouponPort.MerchantAmount> applicable = groups.stream()
                .filter(g -> coupon.getEntityNo() == null || coupon.getEntityNo().isBlank()
                        || coupon.getEntityNo().equals(g.merchantNo()))
                .toList();
        long base = applicable.stream().mapToLong(CouponPort.MerchantAmount::goodsAmount).sum();
        if (base < nz(coupon.getMinAmountMinor())) {
            throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
        }

        long total = coupon.discountFor(base);
        if (total <= 0) {
            return CouponPort.Allocation.none();
        }

        List<CouponPort.MerchantDiscount> shares = new ArrayList<>();
        long allocated = 0;
        for (CouponPort.MerchantAmount g : applicable) {
            long part = Math.round((double) total * g.goodsAmount() / base);
            shares.add(new CouponPort.MerchantDiscount(g.merchantNo(), part));
            allocated += part;
        }
        long remainder = total - allocated;
        if (remainder != 0) {
            String largest = applicable.stream()
                    .max(Comparator.comparingLong(CouponPort.MerchantAmount::goodsAmount))
                    .map(CouponPort.MerchantAmount::merchantNo).orElseThrow();
            for (int i = 0; i < shares.size(); i++) {
                if (shares.get(i).merchantNo().equals(largest)) {
                    shares.set(i, new CouponPort.MerchantDiscount(largest,
                            shares.get(i).amount() + remainder));
                    break;
                }
            }
        }
        return new CouponPort.Allocation(total,
                PmtCoupon.BY_MERCHANT.equals(coupon.getFunder()), shares);
    }

    @Override
    @Transactional
    public void markUsed(String userNo, String userCouponNo, String orderNo,
                         CouponPort.Allocation allocation) {
        if (userCouponNo == null || userCouponNo.isBlank()) {
            return;
        }
        PmtUserCoupon uc = require(userNo, userCouponNo);
        PmtCoupon coupon = template(uc.getCouponNo());
        assertUsable(uc, coupon);

        int used = nz(uc.getTimesUsed()) + 1;
        uc.setTimesUsed(used);
        uc.setOrderNo(orderNo);
        uc.setUsedAt(System.currentTimeMillis());
        // 次卡用满才算 USED —— 用了一次就置 USED 的话，剩下四杯豆浆凭空消失
        if (used >= coupon.timesTotalOrOne()) {
            uc.setStatus(PmtUserCoupon.USED);
        }
        DataScopeContext.executeWithoutScope(() -> userCouponMapper.updateById(uc));

        PmtApply row = new PmtApply();
        row.setApplyNo(BizKey.next(BizKey.PROMO_APPLY));
        row.setPromoType(PmtApply.COUPON);
        row.setPromoNo(userCouponNo);
        row.setUserNo(userNo);
        row.setEntityNo(coupon.getEntityNo());
        row.setOrderNo(orderNo);
        row.setRedeemMode(PmtCoupon.REDEEM_ORDER);
        /*
         * **金额由下单链路带过来，不在这里重算。**
         *
         * 重算依赖的规则会变：同一张券三个月后再算，可能因为门槛改过、封顶调过
         * 而与当时的账对不上。这一行记的是「当时减了多少」，是事实不是派生值。
         */
        row.setAmountMinor(allocation == null ? 0L : allocation.totalDiscount());
        row.setFunder(coupon.getFunder());
        row.setAppliedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> applyMapper.insert(row));
    }

    @Override
    @Transactional
    public void release(String orderNo) {
        // 取消订单退回券。不退的话用户会觉得券被平台吞了 —— 券功能第二大客诉
        List<PmtUserCoupon> used = DataScopeContext.executeWithoutScope(() ->
                userCouponMapper.selectList(Wrappers.<PmtUserCoupon>lambdaQuery()
                        .eq(PmtUserCoupon::getOrderNo, orderNo)));
        for (PmtUserCoupon uc : used) {
            uc.setTimesUsed(Math.max(0, nz(uc.getTimesUsed()) - 1));
            uc.setStatus(PmtUserCoupon.UNUSED);
            DataScopeContext.executeWithoutScope(() -> userCouponMapper.update(null,
                    Wrappers.<PmtUserCoupon>lambdaUpdate()
                            .eq(PmtUserCoupon::getId, uc.getId())
                            .set(PmtUserCoupon::getStatus, PmtUserCoupon.UNUSED)
                            .set(PmtUserCoupon::getTimesUsed, uc.getTimesUsed())
                            // 必须显式 set null：updateById 默认跳过 null 字段，
                            // 那样订单号会留在券上，第二次取消时把别人的单也退了
                            .set(PmtUserCoupon::getOrderNo, null)
                            .set(PmtUserCoupon::getUsedAt, null)));
        }
        // 发生记录不删行，标一个撤销时刻 —— 「发生过又撤销了」与「没发生过」不是一回事
        long now = System.currentTimeMillis();
        DataScopeContext.executeWithoutScope(() -> applyMapper.update(null,
                Wrappers.<PmtApply>lambdaUpdate()
                        .eq(PmtApply::getOrderNo, orderNo)
                        .isNull(PmtApply::getRevertedAt)
                        .set(PmtApply::getRevertedAt, now)));
    }

    private void assertUsable(PmtUserCoupon uc, PmtCoupon coupon) {
        long now = System.currentTimeMillis();
        boolean ok = uc.usableAt(now, coupon.timesTotalOrOne())
                && PmtCoupon.ACTIVE.equals(coupon.getStatus())
                // 到店核销的券**不参与下单算价**：一张券两条路一定会被用两次，
                // 而对账时谁也说不清是重复核销还是重复抵扣
                && !PmtCoupon.REDEEM_STORE_CODE.equals(coupon.getRedeemMode());
        if (!ok) {
            throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
        }
    }

    /** 属主校验：券号可猜，必须带 userNo 查 */
    private PmtUserCoupon find(String userNo, String userCouponNo) {
        return DataScopeContext.executeWithoutScope(() ->
                userCouponMapper.selectOne(Wrappers.<PmtUserCoupon>lambdaQuery()
                        .eq(PmtUserCoupon::getUserCouponNo, userCouponNo)
                        .eq(PmtUserCoupon::getUserNo, userNo).last("limit 1")));
    }

    private PmtUserCoupon require(String userNo, String userCouponNo) {
        PmtUserCoupon uc = find(userNo, userCouponNo);
        if (uc == null) {
            throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
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
