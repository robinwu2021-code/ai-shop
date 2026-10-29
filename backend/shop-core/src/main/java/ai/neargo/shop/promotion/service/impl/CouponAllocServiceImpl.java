package ai.neargo.shop.promotion.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.promotion.CouponCalculation;
import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.entity.PmtCouponScope;
import ai.neargo.shop.promotion.entity.PmtUserCoupon;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ApplyMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponScopeMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.UserCouponMapper;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.promotion.service.CouponAllocService;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.marketing.CouponPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import javax.sql.DataSource;

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

    private final LegacyCouponSourceFenceService sourceFence;
    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final ApplyMapper applyMapper;

    public CouponAllocServiceImpl(CouponMapper couponMapper, UserCouponMapper userCouponMapper,
                                  ApplyMapper applyMapper, LegacyCouponSourceFenceService sourceFence) {
        this.sourceFence = sourceFence;
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
        this.applyMapper = applyMapper;
    }

    @Autowired private CouponScopeMapper checkoutScopes;

    @Override
    public CouponPort.Allocation quoteOrdinary(
            String userNo, String userCouponNo, List<CouponPort.MerchantAmount> groups) {
        if (userCouponNo == null) {
            return CouponPort.Allocation.none();
        }
        var instance = require(userNo, userCouponNo);
        ordinary(template(instance.getCouponNo()));
        return allocate(userNo, userCouponNo, groups);
    }

    @Override
    public void lockOrdinary(String userNo, String userCouponNo) {
        requireCheckoutTransaction();
        var original = require(userNo, userCouponNo);
        DataScopeContext.executeWithoutScope(
                () -> {
                    var coupon =
                            couponMapper.selectOne(
                                    Wrappers.<PmtCoupon>lambdaQuery()
                                            .eq(PmtCoupon::getTenantNo, "MAIN")
                                            .eq(PmtCoupon::getCouponNo, original.getCouponNo())
                                            .last("FOR UPDATE"));
                    var instance =
                            userCouponMapper.selectOne(
                                    Wrappers.<PmtUserCoupon>lambdaQuery()
                                            .eq(PmtUserCoupon::getTenantNo, "MAIN")
                                            .eq(PmtUserCoupon::getUserNo, userNo)
                                            .eq(PmtUserCoupon::getUserCouponNo, userCouponNo)
                                            .last("FOR UPDATE"));
                    if (instance == null
                            || !original.getCouponNo().equals(instance.getCouponNo())) {
                        throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
                    }
                    ordinary(coupon);
                    assertUsable(instance, coupon);
                });
    }

    private void ordinary(PmtCoupon coupon) {
        if (coupon == null
                || !Set.of("CASH", "PERCENT").contains(coupon.getBenefitMode())
                || !"ORDER".equals(coupon.getRedeemMode())
                || !"ALL".equals(coupon.getScopeType())
                || coupon.getTimesTotal() != null && coupon.getTimesTotal() != 1
                || coupon.getMinQty() != null && coupon.getMinQty() != 0
                || coupon.getBenefitRef() != null && !coupon.getBenefitRef().isBlank()
                || !Set.of("PLATFORM", "MERCHANT").contains(coupon.getFunder())
                || DataScopeContext.executeWithoutScope(
                                () ->
                                        checkoutScopes.selectCount(
                                                Wrappers.<PmtCouponScope>lambdaQuery()
                                                        .eq(PmtCouponScope::getTenantNo, "MAIN")
                                                        .eq(
                                                                PmtCouponScope::getCouponNo,
                                                                coupon.getCouponNo())))
                        != 0) {
            throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        }
    }

    @Override
    public boolean hasActiveBinding(String tenantNo, String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> applyMapper.selectCount(
                Wrappers.<PmtApply>lambdaQuery().eq(PmtApply::getTenantNo, tenantNo)
                        .eq(PmtApply::getOrderNo, orderNo).eq(PmtApply::getPromoType, PmtApply.COUPON)
                        .isNull(PmtApply::getRevertedAt))) > 0;
    }

    @Override
    public List<String> heldUsable(String userNo) {
        long now = System.currentTimeMillis();
        List<PmtUserCoupon> rows = DataScopeContext.executeWithoutScope(() ->
                userCouponMapper.selectList(Wrappers.<PmtUserCoupon>lambdaQuery()
                        .eq(PmtUserCoupon::getUserNo, userNo)
                        .eq(PmtUserCoupon::getStatus, PmtUserCoupon.UNUSED)));
        List<String> out = new java.util.ArrayList<>();
        for (PmtUserCoupon uc : rows) {
            PmtCoupon c = DataScopeContext.executeWithoutScope(() -> couponMapper.selectOne(
                    Wrappers.<PmtCoupon>lambdaQuery().eq(PmtCoupon::getCouponNo, uc.getCouponNo()).last("limit 1")));
            if (c != null && PmtCoupon.ACTIVE.equals(c.getStatus())
                    && PmtCoupon.REDEEM_ORDER.equals(c.getRedeemMode())
                    && uc.usableAt(now, c.timesTotalOrOne()) && c.startedAt(now)) {
                out.add(uc.getUserCouponNo());
            }
        }
        return out;
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
        java.util.Set<String> stores = storesOf(coupon);
        List<CouponPort.MerchantAmount> applicable = groups.stream()
                .filter(g -> coupon.getEntityNo() == null || coupon.getEntityNo().isBlank()
                        || coupon.getEntityNo().equals(g.merchantNo()))
                // 指定门店的券只认那几家店出货的组：门槛只按它们计、钱也只减它们
                .filter(g -> stores == null || (g.storeNo() != null && stores.contains(g.storeNo())))
                .toList();
        if (stores != null && applicable.isEmpty()) {
            throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
        }
        long base = applicable.stream().mapToLong(CouponPort.MerchantAmount::goodsAmount).sum();
        if (base < nz(coupon.getMinAmountMinor())) {
            throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
        }

        long total = coupon.discountFor(base);
        if (total <= 0) {
            return CouponPort.Allocation.none();
        }

        List<CouponPort.MerchantDiscount> shares = new ArrayList<>();
        var amounts = CouponCalculation.allocate(total, applicable.stream()
                .map(CouponPort.MerchantAmount::goodsAmount).toList());
        for (int i = 0; i < applicable.size(); i++) {
            var group = applicable.get(i);
            shares.add(new CouponPort.MerchantDiscount(group.merchantNo(), amounts.get(i), group.storeNo()));
        }
        return new CouponPort.Allocation(total,
                PmtCoupon.BY_MERCHANT.equals(coupon.getFunder()), shares, coupon.getTitle());
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
        sourceFence.requireWritable(coupon.getTenantNo(), SourceSystem.PMT, coupon.getCouponNo(), false);
        assertUsable(uc, coupon);

        int before = nz(uc.getTimesUsed());
        int used = before + 1;
        /*
         * **带条件的 UPDATE 才是这里的锁**（与 CouponRedeemServiceImpl 同一手法）：已用次数必须还等于我读到的值、
         * 且仍是 UNUSED。此前是 updateById —— 并发两单都读到同一个次数，一单被乐观锁拒成 0 行却不报错，
         * 结果两单都用了券、次数只加一次。0 行即拒，下单事务整体回滚。
         */
        int affected = DataScopeContext.executeWithoutScope(() -> userCouponMapper.update(null,
                Wrappers.<PmtUserCoupon>lambdaUpdate()
                        .eq(PmtUserCoupon::getId, uc.getId())
                        .eq(PmtUserCoupon::getTimesUsed, before)
                        .eq(PmtUserCoupon::getStatus, PmtUserCoupon.UNUSED)
                        .set(PmtUserCoupon::getTimesUsed, used)
                        .set(PmtUserCoupon::getOrderNo, orderNo)
                        .set(PmtUserCoupon::getUsedAt, System.currentTimeMillis())
                        // 次卡用满才算 USED —— 用了一次就置 USED 的话，剩下四杯豆浆凭空消失
                        .set(used >= coupon.timesTotalOrOne(), PmtUserCoupon::getStatus, PmtUserCoupon.USED)));
        if (affected != 1) {
            throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
        }

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
        /*
         * **按这一单的使用记录（pmt_apply）找券，不按券上的 order_no 找。**
         *
         * 次卡每用一次，券上的 order_no 就被改成最近那一单：A 单用一次、B 单再用一次，
         * 取消 A 时按 order_no 一张都找不到 —— A 那一次永久消失，用户的次卡凭空少一次。
         * pmt_apply 每一次使用一行、带着订单号，是「这一单用了哪张券几次」唯一不会被覆盖的答案。
         */
        var originalApplications = DataScopeContext.executeWithoutScope(() -> applyMapper.selectList(
                Wrappers.<PmtApply>lambdaQuery().eq(PmtApply::getOrderNo, orderNo)
                        .eq(PmtApply::getPromoType, PmtApply.COUPON).isNull(PmtApply::getRevertedAt)));
        var roots = new TreeMap<String, PmtUserCoupon>();
        var applied = new TreeMap<String, PmtUserCoupon>();
        for (var application : originalApplications) {
            var instance = DataScopeContext.executeWithoutScope(() -> userCouponMapper.selectOne(
                    Wrappers.<PmtUserCoupon>lambdaQuery().eq(PmtUserCoupon::getTenantNo, application.getTenantNo())
                            .eq(PmtUserCoupon::getUserCouponNo, application.getPromoNo())));
            if (instance != null) {
                roots.put(instance.getCouponNo(), instance);
                applied.put(instance.getUserCouponNo(), instance);
            }
        }
        // 没有使用记录的存量（新模型之前盖了 order_no 的那批）：照旧按券上的订单号退，一单算一次
        List<PmtUserCoupon> legacy = DataScopeContext.executeWithoutScope(() ->
                userCouponMapper.selectList(Wrappers.<PmtUserCoupon>lambdaQuery()
                        .eq(PmtUserCoupon::getOrderNo, orderNo))).stream()
                .filter(uc -> !applied.containsKey(uc.getUserCouponNo())).toList();
        legacy.forEach(row -> roots.put(row.getCouponNo(), row));
        for (var root : roots.values()) {
            sourceFence.requireWritable(root.getTenantNo(), SourceSystem.PMT, root.getCouponNo(), true);
        }
        long now = System.currentTimeMillis();
        for (PmtUserCoupon uc : applied.values().stream()
                .sorted(Comparator.comparing(PmtUserCoupon::getCouponNo)).toList()) {
            /*
             * 先把这一单这张券的使用记录标撤销，**标中几行就退几次** —— 重复取消、并发取消都只有一次标得中，
             * 不会多退。发生记录不删行：「发生过又撤销了」与「没发生过」不是一回事。
             */
            int uses = DataScopeContext.executeWithoutScope(() -> applyMapper.update(null,
                    Wrappers.<PmtApply>lambdaUpdate()
                            .eq(PmtApply::getOrderNo, orderNo)
                            .eq(PmtApply::getPromoType, PmtApply.COUPON)
                            .eq(PmtApply::getPromoNo, uc.getUserCouponNo())
                            .isNull(PmtApply::getRevertedAt)
                            .set(PmtApply::getRevertedAt, now)));
            if (uses > 0) {
                restore(uc, orderNo, uses);
            }
        }
        for (PmtUserCoupon uc : legacy.stream().sorted(Comparator.comparing(PmtUserCoupon::getCouponNo)).toList()) {
            restore(uc, orderNo, 1);
        }
        // 兜底：这一单剩下的券使用记录（找不到那张券的）一并标撤销
        DataScopeContext.executeWithoutScope(() -> applyMapper.update(null,
                Wrappers.<PmtApply>lambdaUpdate()
                        .eq(PmtApply::getOrderNo, orderNo)
                        // Coupon reversal must not preempt activity capacity reversal on the same order.
                        .eq(PmtApply::getPromoType, PmtApply.COUPON)
                        .isNull(PmtApply::getRevertedAt)
                        .set(PmtApply::getRevertedAt, now)));
    }

    /**
     * 把 {@code uses} 次使用退回这张券：已用次数用 SQL 直接减（不许减成负数），并回到 UNUSED。
     * 券上的订单号<b>只在它正指着这一单时</b>清掉 —— 指着别的单说明那一单还在用着它。
     */
    private void restore(PmtUserCoupon uc, String orderNo, int uses) {
        DataScopeContext.executeWithoutScope(() -> userCouponMapper.update(null,
                Wrappers.<PmtUserCoupon>lambdaUpdate()
                        .eq(PmtUserCoupon::getId, uc.getId())
                        .setSql("times_used = GREATEST(times_used - " + uses + ", 0)")
                        .set(PmtUserCoupon::getStatus, PmtUserCoupon.UNUSED)));
        DataScopeContext.executeWithoutScope(() -> userCouponMapper.update(null,
                Wrappers.<PmtUserCoupon>lambdaUpdate()
                        .eq(PmtUserCoupon::getId, uc.getId())
                        .eq(PmtUserCoupon::getOrderNo, orderNo)
                        // 必须显式 set null：updateById 默认跳过 null 字段，
                        // 那样订单号会留在券上，第二次取消时把别人的单也退了
                        .set(PmtUserCoupon::getOrderNo, null)
                        .set(PmtUserCoupon::getUsedAt, null)));
    }

    /**
     * 新模型：退券时 {@code pmt_apply} 那一行标了 {@code reverted_at}（券上的订单号已清掉），
     * 从那一行找回用户券，**再看它此刻是不是 UNUSED** —— 两件事都成立才说「已回到券包」。
     */
    @Override
    public String usedTitleOf(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return null;
        }
        PmtUserCoupon uc = DataScopeContext.executeWithoutScope(() -> userCouponMapper.selectOne(
                Wrappers.<PmtUserCoupon>lambdaQuery().eq(PmtUserCoupon::getOrderNo, orderNo)
                        .eq(PmtUserCoupon::getStatus, PmtUserCoupon.USED).last("limit 1")));
        if (uc == null) {
            return null;
        }
        PmtCoupon c = DataScopeContext.executeWithoutScope(() -> couponMapper.selectOne(
                Wrappers.<PmtCoupon>lambdaQuery()
                        .eq(PmtCoupon::getCouponNo, uc.getCouponNo()).last("limit 1")));
        return c == null ? null : c.getTitle();
    }

    @Override
    public String returnedTitleOf(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return null;
        }
        PmtApply row = DataScopeContext.executeWithoutScope(() -> applyMapper.selectOne(
                Wrappers.<PmtApply>lambdaQuery()
                        .eq(PmtApply::getOrderNo, orderNo)
                        .eq(PmtApply::getPromoType, PmtApply.COUPON)
                        .isNotNull(PmtApply::getRevertedAt)
                        .last("limit 1")));
        if (row == null) {
            return null;
        }
        PmtUserCoupon uc = DataScopeContext.executeWithoutScope(() -> userCouponMapper.selectOne(
                Wrappers.<PmtUserCoupon>lambdaQuery()
                        .eq(PmtUserCoupon::getUserCouponNo, row.getPromoNo()).last("limit 1")));
        if (uc == null || !PmtUserCoupon.UNUSED.equals(uc.getStatus())) {
            return null;
        }
        PmtCoupon c = DataScopeContext.executeWithoutScope(() -> couponMapper.selectOne(
                Wrappers.<PmtCoupon>lambdaQuery()
                        .eq(PmtCoupon::getCouponNo, uc.getCouponNo()).last("limit 1")));
        return c == null ? null : c.getTitle();
    }

    /** 门店范围（{@code pmt_coupon_scope}）。setter 注入：直接 new 本类的切片测试没有它 */
    private ai.neargo.shop.promotion.mapper.PromotionMappers.CouponScopeMapper scopeMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setScopeMapper(ai.neargo.shop.promotion.mapper.PromotionMappers.CouponScopeMapper scopeMapper) {
        this.scopeMapper = scopeMapper;
    }

    /**
     * 「指定门店」券能用的门店号；不是门店券给 null（= 不按门店限）。
     *
     * <p>建券时下单抵扣只放行全店与指定门店，门店列表落在 {@code pmt_coupon_scope}；此前算价只按主体筛，
     * 写着「仅限河坊街店」的券在总店的单上照样减 —— 商家为一家店做的让利被全主体吃掉，到对账才发现。
     * <b>读不到范围就当一家店都不认</b>（空集合）：宁可这张券用不了，也不能退回成全店券。
     */
    private java.util.Set<String> storesOf(PmtCoupon coupon) {
        if (!PmtCoupon.SCOPE_STORE.equals(coupon.getScopeType())) {
            return null;
        }
        if (scopeMapper == null) {
            return java.util.Set.of();
        }
        return DataScopeContext.executeWithoutScope(() -> scopeMapper.selectList(
                        Wrappers.<ai.neargo.shop.promotion.entity.PmtCouponScope>lambdaQuery()
                                .eq(ai.neargo.shop.promotion.entity.PmtCouponScope::getCouponNo, coupon.getCouponNo())
                                .eq(ai.neargo.shop.promotion.entity.PmtCouponScope::getScopeType, PmtCoupon.SCOPE_STORE)))
                .stream().map(ai.neargo.shop.promotion.entity.PmtCouponScope::getRefNo)
                .collect(java.util.stream.Collectors.toSet());
    }

    private void assertUsable(PmtUserCoupon uc, PmtCoupon coupon) {
        long now = System.currentTimeMillis();
        boolean ok = uc.usableAt(now, coupon.timesTotalOrOne())
                // 绝对有效期没到开始时刻不能用（定向发放不判开始时刻，持有的那张只记了到期）
                && coupon.startedAt(now)
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
