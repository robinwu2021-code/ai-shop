package ai.neargo.shop.marketing.coupon.impl;

import ai.neargo.shop.marketing.coupon.CouponService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.coupon.dto.CouponVO;
import ai.neargo.shop.marketing.coupon.dto.UserCouponVO;
import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.entity.MktUserCoupon;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers.CouponMapper;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers.UserCouponMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CouponServiceImpl implements CouponService {

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final GoodsQueryPort goodsPort;

    public CouponServiceImpl(CouponMapper couponMapper, UserCouponMapper userCouponMapper,
                             GoodsQueryPort goodsPort) {
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
        this.goodsPort = goodsPort;
    }

    @Override
    public List<CouponVO> center() {
        long now = System.currentTimeMillis();
        List<MktCoupon> coupons = DataScopeContext.executeWithoutScope(() ->
                couponMapper.selectList(Wrappers.<MktCoupon>lambdaQuery()
                        .eq(MktCoupon::getStatus, "ACTIVE")
                        .le(MktCoupon::getStartAt, now)
                        .ge(MktCoupon::getEndAt, now)));

        String userNo = SecurityUtils.currentUserNoOrNull();
        List<String> received = userNo == null ? List.of() : myCoupons(userNo).stream()
                .map(MktUserCoupon::getCouponNo).toList();

        return coupons.stream().map(c -> toVO(c, received.contains(c.getCouponNo()))).toList();
    }

    @Override
    @Transactional
    public UserCouponVO receive(String couponNo) {
        String userNo = SecurityUtils.currentUserNo();
        MktCoupon coupon = template(couponNo);

        long now = System.currentTimeMillis();
        if (!"ACTIVE".equals(coupon.getStatus()) || nz(coupon.getStartAt()) > now
                || nz(coupon.getEndAt()) < now) {
            throw BizException.of(ErrorCode.COUPON_SOLD_OUT);
        }

        long mine = myCoupons(userNo).stream()
                .filter(uc -> uc.getCouponNo().equals(couponNo)).count();
        if (mine >= Math.max(nzi(coupon.getPerUserLimit()), 1)) {
            throw BizException.of(ErrorCode.COUPON_SOLD_OUT);
        }

        // 原子扣库存：先查后改在并发下必然超发
        int affected = DataScopeContext.executeWithoutScope(() -> couponMapper.tryReceive(couponNo));
        if (affected == 0) {
            throw BizException.of(ErrorCode.COUPON_SOLD_OUT);
        }

        MktUserCoupon uc = new MktUserCoupon();
        uc.setUserCouponNo(BizKey.next(BizKey.COUPON));
        uc.setCouponNo(couponNo);
        uc.setUserNo(userNo);
        uc.setStatus(MktUserCoupon.UNUSED);
        uc.setReceivedAt(now);
        DataScopeContext.executeWithoutScope(() -> userCouponMapper.insert(uc));

        return new UserCouponVO(uc.getUserCouponNo(), toVO(coupon, true),
                uc.getStatus(), true, now, null);
    }

    @Override
    public List<UserCouponVO> mine() {
        String userNo = SecurityUtils.currentUserNo();
        List<MktUserCoupon> rows = myCoupons(userNo);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, MktCoupon> templates = templatesOf(rows);
        return rows.stream()
                .map(uc -> toVO(uc, templates.get(uc.getCouponNo()), true))
                .toList();
    }

    @Override
    public BestResult best(List<Item> items) {
        String userNo = SecurityUtils.currentUserNo();
        List<MktUserCoupon> rows = myCoupons(userNo).stream()
                .filter(uc -> MktUserCoupon.UNUSED.equals(uc.getStatus())).toList();
        if (rows.isEmpty() || items == null || items.isEmpty()) {
            return new BestResult(null, 0L, List.of(), List.of());
        }

        Map<String, GoodsQueryPort.SkuSnapshot> snaps =
                goodsPort.snapshot(items.stream().map(Item::skuNo).toList());
        // 按商家分组的商品额：商家券只对本店金额计门槛
        Map<String, Long> byMerchant = new HashMap<>();
        long total = 0;
        for (Item i : items) {
            var s = snaps.get(i.skuNo());
            if (s == null) {
                continue;
            }
            long amount = s.price() * i.qty();
            byMerchant.merge(s.merchantNo(), amount, Long::sum);
            total += amount;
        }

        Map<String, MktCoupon> templates = templatesOf(rows);
        List<UserCouponVO> usable = new ArrayList<>();
        List<BestResult.Unusable> unusable = new ArrayList<>();
        String bestNo = null;
        long bestDiscount = 0;

        for (MktUserCoupon uc : rows) {
            MktCoupon c = templates.get(uc.getCouponNo());
            if (c == null) {
                continue;
            }
            long base = c.getEntityNo() == null || c.getEntityNo().isBlank()
                    ? total : byMerchant.getOrDefault(c.getEntityNo(), 0L);

            String reason = reasonOfUnusable(c, base);
            if (reason != null) {
                unusable.add(new BestResult.Unusable(uc.getUserCouponNo(), reason));
                continue;
            }
            usable.add(toVO(uc, c, true));

            long discount = Math.min(nz(c.getFaceMinor()), base);
            if (discount > bestDiscount) {
                bestDiscount = discount;
                bestNo = uc.getUserCouponNo();
            }
        }
        return new BestResult(bestNo, bestDiscount, usable, unusable);
    }

    /**
     * 不可用原因。**给用户看的文案**，不是错误码 ——
     * 「满 500 可用，还差 200」比「COUPON_NOT_APPLICABLE」有用得多。
     */
    private String reasonOfUnusable(MktCoupon c, long base) {
        long now = System.currentTimeMillis();
        if (!"ACTIVE".equals(c.getStatus()) || nz(c.getEndAt()) < now) {
            return "已过期";
        }
        if (nz(c.getStartAt()) > now) {
            return "未到使用时间";
        }
        if (base < nz(c.getThresholdMinor())) {
            return "未达使用门槛，还差 " + (nz(c.getThresholdMinor()) - base) + " 分";
        }
        return null;
    }

    private List<MktUserCoupon> myCoupons(String userNo) {
        return DataScopeContext.executeWithoutScope(() ->
                userCouponMapper.selectList(Wrappers.<MktUserCoupon>lambdaQuery()
                        .eq(MktUserCoupon::getUserNo, userNo)
                        .orderByDesc(MktUserCoupon::getId)));
    }

    private Map<String, MktCoupon> templatesOf(List<MktUserCoupon> rows) {
        List<String> nos = rows.stream().map(MktUserCoupon::getCouponNo).distinct().toList();
        return DataScopeContext.executeWithoutScope(() ->
                        couponMapper.selectList(Wrappers.<MktCoupon>lambdaQuery()
                                .in(MktCoupon::getCouponNo, nos))).stream()
                .collect(java.util.stream.Collectors.toMap(MktCoupon::getCouponNo, c -> c, (a, b) -> a));
    }

    private MktCoupon template(String couponNo) {
        MktCoupon c = DataScopeContext.executeWithoutScope(() ->
                couponMapper.selectOne(Wrappers.<MktCoupon>lambdaQuery()
                        .eq(MktCoupon::getCouponNo, couponNo).last("limit 1")));
        if (c == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return c;
    }

    private CouponVO toVO(MktCoupon c, boolean received) {
        int remain = nzi(c.getTotalCount()) == 0 ? Integer.MAX_VALUE
                : nzi(c.getTotalCount()) - nzi(c.getReceivedCount());
        return new CouponVO(c.getCouponNo(), c.getTitle(), c.getType(), nz(c.getFaceMinor()),
                nzi(c.getDiscountRate()), nz(c.getThresholdMinor()), nz(c.getMaxDiscountMinor()),
                c.getFunder(), c.getEntityNo(), nz(c.getStartAt()), nz(c.getEndAt()),
                Math.max(remain, 0), received,
                c.getStatus() == null ? MktCoupon.ACTIVE : c.getStatus());
    }

    private UserCouponVO toVO(MktUserCoupon uc, MktCoupon c, boolean usableNow) {
        return new UserCouponVO(uc.getUserCouponNo(), c == null ? null : toVO(c, true),
                uc.getStatus(), usableNow && MktUserCoupon.UNUSED.equals(uc.getStatus()),
                nz(uc.getReceivedAt()), uc.getUsedAt());
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nzi(Integer v) {
        return v == null ? 0 : v;
    }
    // ---------------------------------------------------------------- 平台侧（P-7.1）

    @Override
    public List<ai.neargo.shop.marketing.coupon.dto.OpsCouponVO> opsCoupons(String status) {
        // executeWithoutScope：平台视角要跨商家。不解除数据域的话，
        // 运营看到的永远是空列表 —— 而且不报错
        return DataScopeContext.executeWithoutScope(() ->
                couponMapper.selectList(Wrappers.<MktCoupon>lambdaQuery()
                        .eq(status != null && !status.isBlank(), MktCoupon::getStatus, status)
                        .orderByDesc(MktCoupon::getId))).stream()
                .map(this::toOpsVO).toList();
    }

    private ai.neargo.shop.marketing.coupon.dto.OpsCouponVO toOpsVO(MktCoupon c) {
        int issued = nzi(c.getReceivedCount());
        long face = nz(c.getFaceMinor());
        /*
         * 已发放金额 = 已领张数 × 面额。折扣券的面额是 0（它用 discount_rate），
         * 于是这里返回 0 —— **宁可显示 0，也不要按订单均价估一个看着像真的数**。
         * 运营会拿这个数去和预算比。
         */
        long issuedAmount = issued * face;
        return new ai.neargo.shop.marketing.coupon.dto.OpsCouponVO(
                c.getCouponNo(), c.getTitle(), c.getType(), c.getStatus(),
                // DISCOUNT 券的 value 是折扣万分比，其余是面额 —— 与 ops-web 的 Coupon.value 同口径
                "DISCOUNT".equals(c.getType()) ? nzi(c.getDiscountRate()) : face,
                nz(c.getThresholdMinor()), c.getFunder(), c.getEntityNo(),
                nz(c.getStartAt()), nz(c.getEndAt()),
                nz(c.getBudgetMinor()), issuedAmount, issued,
                DataScopeContext.executeWithoutScope(() -> couponMapper.redeemedCount(c.getCouponNo())),
                c.getCreatedAt() == null ? 0
                        : c.getCreatedAt().atZone(java.time.ZoneId.systemDefault())
                                .toInstant().toEpochMilli());
    }

    @Override
    @Transactional
    public CouponVO setCouponStatus(String couponNo, String status, String reason, String operatorNo) {
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (!MktCoupon.ACTIVE.equals(status) && !MktCoupon.PAUSED.equals(status)
                && !MktCoupon.ENDED.equals(status)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MktCoupon c = DataScopeContext.executeWithoutScope(() ->
                couponMapper.selectOne(Wrappers.<MktCoupon>lambdaQuery()
                        .eq(MktCoupon::getCouponNo, couponNo).last("limit 1")));
        if (c == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (MktCoupon.ENDED.equals(c.getStatus())) {
            // 已结束不可恢复：把它改回 ACTIVE 等于让一批过期券重新可领，
            // 而发行量与预算的账早就按「结束」结过了
            throw BizException.of(ErrorCode.CONFLICT);
        }
        c.setStatus(status);
        DataScopeContext.executeWithoutScope(() -> couponMapper.updateById(c));
        return toVO(c, false);
    }

}
