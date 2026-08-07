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
            long base = c.getMerchantNo() == null || c.getMerchantNo().isBlank()
                    ? total : byMerchant.getOrDefault(c.getMerchantNo(), 0L);

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
                c.getFunder(), c.getMerchantNo(), nz(c.getStartAt()), nz(c.getEndAt()),
                Math.max(remain, 0), received);
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
}
