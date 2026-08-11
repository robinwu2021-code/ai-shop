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
import ai.neargo.shop.marketing.coupon.entity.MktCouponIssue;
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
    private final ai.neargo.shop.marketing.coupon.mapper.CouponMappers.CouponIssueMapper issueMapper;
    private final ai.neargo.shop.spi.user.UserQueryPort userPort;

    public CouponServiceImpl(CouponMapper couponMapper, UserCouponMapper userCouponMapper,
                             GoodsQueryPort goodsPort,
                             ai.neargo.shop.marketing.coupon.mapper.CouponMappers.CouponIssueMapper issueMapper,
                             ai.neargo.shop.spi.user.UserQueryPort userPort) {
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
        this.goodsPort = goodsPort;
        this.issueMapper = issueMapper;
        this.userPort = userPort;
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
    public List<ai.neargo.shop.marketing.coupon.dto.OpsCouponVO> opsCoupons(String status,
                                                                            boolean showArchived) {
        // executeWithoutScope：平台视角要跨商家。不解除数据域的话，
        // 运营看到的永远是空列表 —— 而且不报错
        return DataScopeContext.executeWithoutScope(() ->
                couponMapper.selectList(Wrappers.<MktCoupon>lambdaQuery()
                        .eq(status != null && !status.isBlank(), MktCoupon::getStatus, status)
                        // 已归档的默认不出现 —— 否则「归档」这个动作在页面上看不出效果
                        .isNull(!showArchived, MktCoupon::getArchivedAt)
                        .orderByDesc(MktCoupon::getId))).stream()
                .map(this::toOpsVO).toList();
    }

    @Override
    @Transactional
    public ai.neargo.shop.marketing.coupon.dto.OpsCouponVO setBudget(String couponNo,
                                                                     long budgetMinor,
                                                                     String operatorNo) {
        if (budgetMinor < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MktCoupon c = DataScopeContext.executeWithoutScope(() ->
                couponMapper.selectOne(Wrappers.<MktCoupon>lambdaQuery()
                        .eq(MktCoupon::getCouponNo, couponNo).last("limit 1")));
        if (c == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        /*
         * **不能改到低于已发放金额**：那等于人为造出一个「已经超支」的状态，
         * 而超支之后没有任何补救动作可做 —— 券已经在用户手里了，收不回来。
         *
         * 0 是显式的「不限」，不受这条约束（把闸门整个撤掉是合法操作）。
         */
        long issuedAmount = nzi(c.getReceivedCount()) * nz(c.getFaceMinor());
        if (budgetMinor > 0 && budgetMinor < issuedAmount) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        c.setBudgetMinor(budgetMinor);
        DataScopeContext.executeWithoutScope(() -> couponMapper.updateById(c));
        return toOpsVO(c);
    }

    @Override
    @Transactional
    public ai.neargo.shop.marketing.coupon.dto.CouponIssueVO issue(String couponNo, String target,
                                                                    String targetDesc, String userKey,
                                                                    int count, String operatorNo) {
        if (count <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MktCoupon c = DataScopeContext.executeWithoutScope(() ->
                couponMapper.selectOne(Wrappers.<MktCoupon>lambdaQuery()
                        .eq(MktCoupon::getCouponNo, couponNo).last("limit 1")));
        if (c == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        /*
         * **只有 SINGLE_USER 能真发**。不是后端偷懒 —— 另外三种的收件人
         * 在入口处就不存在：ops-web 的「定向说明」是自由文本（「锦绣花园」），
         * 它给不出社区号也给不出 userNo。
         *
         * 按名字模糊匹配去猜收券人，猜错就是把钱发给了别人。
         * 与其那样，不如明说这条路还没通。
         */
        if (!MktCouponIssue.SINGLE_USER.equals(target)) {
            throw BizException.of(ErrorCode.NOT_IMPLEMENTED);
        }
        if (userKey == null || userKey.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var user = userPort.find(userKey);
        if (user.isEmpty()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }

        /*
         * **限领规则不能被主动发放绕开**：客服连发五张「限领一张」的券，
         * 与用户自己领五张是同一件事，只是路径不同。
         */
        long mine = myCoupons(userKey).stream()
                .filter(uc -> uc.getCouponNo().equals(couponNo)).count();
        if (mine + count > Math.max(nzi(c.getPerUserLimit()), 1)) {
            throw BizException.of(ErrorCode.COUPON_SOLD_OUT);
        }

        /*
         * **预算是硬闸门，整批拒绝，不部分发放** —— 页面上那句
         * 「超出剩余预算会被拒绝，不会部分发放」说的就是这件事，它必须是真的。
         * 部分发放更坏：运营以为发了 100 张，实际发了 37 张，而没有任何提示。
         */
        long face = nz(c.getFaceMinor());
        long amount = face * count;
        long already = nzi(c.getReceivedCount()) * face;
        long budget = nz(c.getBudgetMinor());
        if (budget > 0 && already + amount > budget) {
            throw BizException.of(ErrorCode.CONFLICT);
        }

        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            // 走与用户自领同一条原子扣减：张数上限与预算都在那条 UPDATE 里判
            int affected = DataScopeContext.executeWithoutScope(() -> couponMapper.tryReceive(couponNo));
            if (affected == 0) {
                // 扣不动 = 张数发完或预算到顶。已插的那几张随事务一起回滚
                throw BizException.of(ErrorCode.COUPON_SOLD_OUT);
            }
            MktUserCoupon uc = new MktUserCoupon();
            uc.setUserCouponNo(BizKey.next(BizKey.COUPON));
            uc.setCouponNo(couponNo);
            uc.setUserNo(userKey);
            uc.setStatus(MktUserCoupon.UNUSED);
            uc.setReceivedAt(now);
            DataScopeContext.executeWithoutScope(() -> userCouponMapper.insert(uc));
        }

        MktCouponIssue rec = new MktCouponIssue();
        rec.setIssueNo(BizKey.next(BizKey.COUPON) + "-I");
        rec.setCouponNo(couponNo);
        rec.setCouponName(c.getTitle());
        rec.setTarget(target);
        rec.setTargetDesc(targetDesc);
        rec.setUserNo(userKey);
        rec.setIssuedCount(count);
        rec.setAmountMinor(amount);
        rec.setOperatorNo(operatorNo);
        DataScopeContext.executeWithoutScope(() -> issueMapper.insert(rec));
        return toIssueVO(rec);
    }

    @Override
    public java.util.List<ai.neargo.shop.marketing.coupon.dto.CouponIssueVO> issues(String couponNo) {
        return DataScopeContext.executeWithoutScope(() ->
                issueMapper.selectList(Wrappers.<MktCouponIssue>lambdaQuery()
                        .eq(couponNo != null && !couponNo.isBlank(),
                                MktCouponIssue::getCouponNo, couponNo)
                        .orderByDesc(MktCouponIssue::getId))).stream()
                .map(this::toIssueVO).toList();
    }

    private ai.neargo.shop.marketing.coupon.dto.CouponIssueVO toIssueVO(MktCouponIssue r) {
        return new ai.neargo.shop.marketing.coupon.dto.CouponIssueVO(
                r.getIssueNo(), r.getCouponNo(), r.getCouponName(), r.getTarget(),
                r.getTargetDesc(), nzi(r.getIssuedCount()), nz(r.getAmountMinor()),
                r.getOperatorNo(),
                r.getCreatedAt() == null ? 0
                        : r.getCreatedAt().atZone(java.time.ZoneId.systemDefault())
                                .toInstant().toEpochMilli());
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
                                .toInstant().toEpochMilli(),
                c.getArchivedAt() == null ? null
                        : c.getArchivedAt().atZone(java.time.ZoneId.systemDefault())
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
