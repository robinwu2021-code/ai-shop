package ai.neargo.shop.marketing.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.promotion.CouponCalculation;
import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.entity.MktUserCoupon;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers.CouponMapper;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers.UserCouponMapper;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.entitlement.LegacyCouponWriteFencePort;
import ai.neargo.shop.spi.marketing.CouponPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

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

    @Autowired
    @Qualifier("dataSource")
    private DataSource checkoutSource;

    private void requireCheckoutTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || checkoutSource == null
                || !TransactionSynchronizationManager.hasResource(checkoutSource)) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
    }

    private final LegacyCouponWriteFencePort sourceFence;
    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;

    public CouponPortImpl(CouponMapper couponMapper, UserCouponMapper userCouponMapper, LegacyCouponWriteFencePort sourceFence) {
        this.sourceFence = sourceFence;
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
    }

    @Override
    public Allocation quoteOrdinary(
            String userNo, String userCouponNo, List<MerchantAmount> groups) {
        if (userCouponNo == null) {
            return Allocation.none();
        }
        var instance = ownUserCoupon(userNo, userCouponNo);
        ordinary(templateOf(instance.getCouponNo()));
        return allocate(userNo, userCouponNo, groups);
    }

    @Override
    public void lockOrdinary(String userNo, String userCouponNo) {
        requireCheckoutTransaction();
        var original = ownUserCoupon(userNo, userCouponNo);
        DataScopeContext.executeWithoutScope(
                () -> {
                    var coupon =
                            couponMapper.selectOne(
                                    Wrappers.<MktCoupon>lambdaQuery()
                                            .eq(MktCoupon::getTenantNo, "MAIN")
                                            .eq(MktCoupon::getCouponNo, original.getCouponNo())
                                            .last("FOR UPDATE"));
                    var instance =
                            userCouponMapper.selectOne(
                                    Wrappers.<MktUserCoupon>lambdaQuery()
                                            .eq(MktUserCoupon::getTenantNo, "MAIN")
                                            .eq(MktUserCoupon::getUserNo, userNo)
                                            .eq(MktUserCoupon::getUserCouponNo, userCouponNo)
                                            .last("FOR UPDATE"));
                    if (instance == null
                            || !original.getCouponNo().equals(instance.getCouponNo())) {
                        throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
                    }
                    ordinary(coupon);
                    assertUsable(instance, coupon);
                });
    }

    private void ordinary(MktCoupon coupon) {
        if (coupon == null
                || !Set.of("FULL_CUT", "DISCOUNT").contains(coupon.getType())
                || !Set.of("PLATFORM", "MERCHANT").contains(coupon.getFunder())) {
            throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        }
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
                .filter(g -> coupon.getEntityNo() == null || coupon.getEntityNo().isBlank()
                        || coupon.getEntityNo().equals(g.merchantNo()))
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
        var amounts = CouponCalculation.allocate(total, applicable.stream().map(MerchantAmount::goodsAmount).toList());
        for (int i = 0; i < applicable.size(); i++) {
            var group = applicable.get(i);
            shares.add(new Share(group.merchantNo(), amounts.get(i), group.storeNo()));
        }

        boolean byMerchant = MktCoupon.BY_MERCHANT.equals(coupon.getFunder());
        return new Allocation(total, byMerchant,
                shares.stream().map(sh -> new MerchantDiscount(sh.merchantNo(), sh.amount(), sh.storeNo())).toList(),
                coupon.getTitle());
    }

    @Override
    @Transactional
    public void markUsed(String userNo, String userCouponNo, String orderNo,
                         Allocation allocation) {
        // 老模型没有「优惠发生记录」这张表，减了多少只能靠重算 —— 所以这里用不上 allocation。
        // 这正是新模型要多写一行 pmt_apply 的原因，P9 老表退场时这个空实现一起消失
        if (userCouponNo == null || userCouponNo.isBlank()) {
            return;
        }
        MktUserCoupon uc = ownUserCoupon(userNo, userCouponNo);
        sourceFence.requireWritable(uc.getTenantNo(), SourceSystem.MKT_LEGACY, uc.getCouponNo(), false);
        assertUsable(uc, templateOf(uc.getCouponNo()));
        /*
         * **带条件的 UPDATE 才是这里的锁**（《分布式集群与高可用方案》§5 P0）：只有仍是 UNUSED 才改得动。
         * 此前是 updateById —— 两笔订单同时用同一张券时都读到 UNUSED、都校验通过，后写的那笔
         * 被 @Version 乐观锁拒成 0 行却不报错，两单都享受了优惠（一券两用）。0 行即拒，下单事务整体回滚。
         */
        int affected = DataScopeContext.executeWithoutScope(() -> userCouponMapper.update(null,
                Wrappers.<MktUserCoupon>lambdaUpdate()
                        .eq(MktUserCoupon::getId, uc.getId())
                        .eq(MktUserCoupon::getStatus, MktUserCoupon.UNUSED)
                        .set(MktUserCoupon::getStatus, MktUserCoupon.USED)
                        .set(MktUserCoupon::getOrderNo, orderNo)
                        .set(MktUserCoupon::getUsedAt, System.currentTimeMillis())));
        if (affected != 1) {
            throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
        }
    }

    @Override
    @Transactional
    public void release(String orderNo) {
        // 取消订单退回券。不退的话用户会觉得券被平台吞了 —— 券功能第二大客诉
        List<MktUserCoupon> used = DataScopeContext.executeWithoutScope(() ->
                userCouponMapper.selectList(Wrappers.<MktUserCoupon>lambdaQuery()
                        .eq(MktUserCoupon::getOrderNo, orderNo)
                        .eq(MktUserCoupon::getStatus, MktUserCoupon.USED)));
        used = used.stream().sorted(Comparator.comparing(MktUserCoupon::getCouponNo)).toList();
        for (MktUserCoupon uc : used) {
            sourceFence.requireWritable(uc.getTenantNo(), SourceSystem.MKT_LEGACY, uc.getCouponNo(), true);
            /*
             * **订单号留在券上**（2026-09-21 写明）。此前这里写着 setOrderNo(null)，
             * 但 updateById 跳过 null 字段 —— 那一行从来没生效过，订单号一直都留着。
             * 现在把它当成有意的：它是「这张券上一次用在哪一单」，订单详情据此说
             * 「「满 30 减 5」已回到券包」（returnedTitleOf）。重复退回由上面的 USED 过滤挡住，
             * 再用到别的单时 markUsed 会覆盖它。
             */
            uc.setStatus(MktUserCoupon.UNUSED);
            DataScopeContext.executeWithoutScope(() -> userCouponMapper.update(uc,
                    Wrappers.<MktUserCoupon>lambdaUpdate().eq(MktUserCoupon::getId, uc.getId())
                            .eq(MktUserCoupon::getOrderNo, orderNo).eq(MktUserCoupon::getStatus, MktUserCoupon.USED)));
        }
    }

    @Override
    public List<String> heldUsable(String userNo) {
        long now = System.currentTimeMillis();
        List<MktUserCoupon> rows = DataScopeContext.executeWithoutScope(() ->
                userCouponMapper.selectList(Wrappers.<MktUserCoupon>lambdaQuery()
                        .eq(MktUserCoupon::getUserNo, userNo)
                        .eq(MktUserCoupon::getStatus, MktUserCoupon.UNUSED)));
        List<String> out = new java.util.ArrayList<>();
        for (MktUserCoupon uc : rows) {
            MktCoupon c = DataScopeContext.executeWithoutScope(() -> couponMapper.selectOne(
                    Wrappers.<MktCoupon>lambdaQuery().eq(MktCoupon::getCouponNo, uc.getCouponNo()).last("limit 1")));
            if (c != null && "ACTIVE".equals(c.getStatus())
                    && nz(c.getStartAt()) <= now && nz(c.getEndAt()) >= now) {
                out.add(uc.getUserCouponNo());
            }
        }
        return out;
    }

    @Override
    public String usedTitleOf(String orderNo) {
        return titleOnOrder(orderNo, MktUserCoupon.USED);
    }

    @Override
    public String returnedTitleOf(String orderNo) {
        return titleOnOrder(orderNo, MktUserCoupon.UNUSED);
    }

    /** 挂在这一单上、处于 {@code status} 的那张券的名字 */
    private String titleOnOrder(String orderNo, String status) {
        if (orderNo == null || orderNo.isBlank()) {
            return null;
        }
        MktUserCoupon uc = DataScopeContext.executeWithoutScope(() -> userCouponMapper.selectOne(
                Wrappers.<MktUserCoupon>lambdaQuery()
                        .eq(MktUserCoupon::getOrderNo, orderNo)
                        .eq(MktUserCoupon::getStatus, status)
                        .last("limit 1")));
        if (uc == null) {
            return null;
        }
        try {
            return templateOf(uc.getCouponNo()).getTitle();
        } catch (BizException e) {
            return null;   // 模板被删了：说不出名字就不说，别让订单详情整页报错
        }
    }

    /**
     * 券面额计算。**实现挪到了 {@link MktCoupon#discountFor} 上** ——
     * 最优券试算此前另有一份只认 faceMinor 的算法，折扣券在那边恒为 0。
     * 一个概念两处实现，分岔只是时间问题（而它已经分岔了）。
     */
    private long discountOf(MktCoupon coupon, long base) {
        return coupon.discountFor(base);
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

    private record Share(String merchantNo, long amount, String storeNo) {
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
