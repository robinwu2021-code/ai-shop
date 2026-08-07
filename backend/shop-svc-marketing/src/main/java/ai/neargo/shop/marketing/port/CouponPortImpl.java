package ai.neargo.shop.marketing.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.entity.MktUserCoupon;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers.CouponMapper;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers.UserCouponMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * trade → marketing：下单时的券计算与核销（{@link CouponPort}）。
 *
 * <p><b>分摊规则（Q9 / db-design §3.4）在 {@link #allocate} 里，只有这一处实现</b>：
 * <pre>
 *   子单优惠 = round(券面额 × 子单适用商品额 / 总适用商品额)
 *   尾数（分摊后与面额之差）→ 给**适用商品额最大**的子单
 * </pre>
 * 尾数给最大单而不是第一单：按金额排序是稳定的，重算时结果一致；
 * 给「第一单」的话购物车排序一变，历史账就对不上了。
 */
@Component
public class CouponPortImpl implements CouponPort {

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;

    public CouponPortImpl(CouponMapper couponMapper, UserCouponMapper userCouponMapper) {
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
    }

    @Override
    public Allocation allocate(String userNo, String userCouponNo, List<MerchantAmount> groups) {
        if (userCouponNo == null || userCouponNo.isBlank() || groups.isEmpty()) {
            return Allocation.none();
        }
        MktUserCoupon uc = ownUserCoupon(userNo, userCouponNo);
        MktCoupon coupon = templateOf(uc.getCouponNo());
        assertUsable(uc, coupon);

        // 商家券只对本店商品计门槛、也只减本店的钱
        List<MerchantAmount> applicable = groups.stream()
                .filter(g -> coupon.getMerchantNo() == null || coupon.getMerchantNo().isBlank()
                        || coupon.getMerchantNo().equals(g.merchantNo()))
                .toList();
        long base = applicable.stream().mapToLong(MerchantAmount::goodsAmount).sum();
        if (base < nz(coupon.getThresholdMinor())) {
            throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
        }

        long total = discountOf(coupon, base);
        if (total <= 0) {
            return Allocation.none();
        }

        // 按比例分摊
        List<Share> shares = new ArrayList<>();
        long allocated = 0;
        for (MerchantAmount g : applicable) {
            long part = Math.round((double) total * g.goodsAmount() / base);
            shares.add(new Share(g.merchantNo(), part));
            allocated += part;
        }
        // 尾数给适用商品额最大的那一单（正负都可能：round 会上下浮动）
        long remainder = total - allocated;
        if (remainder != 0) {
            String largest = applicable.stream()
                    .max(Comparator.comparingLong(MerchantAmount::goodsAmount))
                    .map(MerchantAmount::merchantNo).orElseThrow();
            for (int i = 0; i < shares.size(); i++) {
                if (shares.get(i).merchantNo().equals(largest)) {
                    shares.set(i, new Share(largest, shares.get(i).amount() + remainder));
                    break;
                }
            }
        }

        boolean byMerchant = MktCoupon.BY_MERCHANT.equals(coupon.getFunder());
        return new Allocation(total, byMerchant,
                shares.stream().map(sh -> new MerchantDiscount(sh.merchantNo(), sh.amount())).toList());
    }

    @Override
    @Transactional
    public void markUsed(String userNo, String userCouponNo, String orderNo) {
        if (userCouponNo == null || userCouponNo.isBlank()) {
            return;
        }
        MktUserCoupon uc = ownUserCoupon(userNo, userCouponNo);
        assertUsable(uc, templateOf(uc.getCouponNo()));
        uc.setStatus(MktUserCoupon.USED);
        uc.setOrderNo(orderNo);
        uc.setUsedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> userCouponMapper.updateById(uc));
    }

    @Override
    @Transactional
    public void release(String orderNo) {
        // 取消订单退回券。不退的话用户会觉得券被平台吞了 —— 券功能第二大客诉
        List<MktUserCoupon> used = DataScopeContext.executeWithoutScope(() ->
                userCouponMapper.selectList(Wrappers.<MktUserCoupon>lambdaQuery()
                        .eq(MktUserCoupon::getOrderNo, orderNo)
                        .eq(MktUserCoupon::getStatus, MktUserCoupon.USED)));
        for (MktUserCoupon uc : used) {
            uc.setStatus(MktUserCoupon.UNUSED);
            uc.setOrderNo(null);
            uc.setUsedAt(null);
            DataScopeContext.executeWithoutScope(() -> userCouponMapper.updateById(uc));
        }
    }

    /** 券面额计算：满减直接给面额，折扣按比例并受封顶约束。 */
    private long discountOf(MktCoupon coupon, long base) {
        if (MktCoupon.DISCOUNT.equals(coupon.getType())) {
            long off = base * (100 - nz(coupon.getDiscountRate())) / 100;
            long cap = nz(coupon.getMaxDiscountMinor());
            return cap > 0 ? Math.min(off, cap) : off;
        }
        // 满减不能减成负数：券面额大于商品额时按商品额封顶
        return Math.min(nz(coupon.getFaceMinor()), base);
    }

    private void assertUsable(MktUserCoupon uc, MktCoupon coupon) {
        long now = System.currentTimeMillis();
        boolean ok = MktUserCoupon.UNUSED.equals(uc.getStatus())
                && nz(coupon.getStartAt()) <= now && nz(coupon.getEndAt()) >= now
                && "ACTIVE".equals(coupon.getStatus());
        if (!ok) {
            throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
        }
    }

    /** 属主校验：券号可猜，必须带 userNo 查。 */
    private MktUserCoupon ownUserCoupon(String userNo, String userCouponNo) {
        MktUserCoupon uc = DataScopeContext.executeWithoutScope(() ->
                userCouponMapper.selectOne(Wrappers.<MktUserCoupon>lambdaQuery()
                        .eq(MktUserCoupon::getUserCouponNo, userCouponNo)
                        .eq(MktUserCoupon::getUserNo, userNo)
                        .last("limit 1")));
        if (uc == null) {
            throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
        }
        return uc;
    }

    private MktCoupon templateOf(String couponNo) {
        MktCoupon c = DataScopeContext.executeWithoutScope(() ->
                couponMapper.selectOne(Wrappers.<MktCoupon>lambdaQuery()
                        .eq(MktCoupon::getCouponNo, couponNo).last("limit 1")));
        if (c == null) {
            throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
        }
        return c;
    }

    private record Share(String merchantNo, long amount) {
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
