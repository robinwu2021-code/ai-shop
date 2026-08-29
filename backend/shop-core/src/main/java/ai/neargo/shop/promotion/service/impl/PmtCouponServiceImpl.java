package ai.neargo.shop.promotion.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponIssueVO;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponVO;
import ai.neargo.shop.promotion.dto.CouponVOs.MyCouponVO;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.entity.PmtCouponIssue;
import ai.neargo.shop.promotion.entity.PmtCouponScope;
import ai.neargo.shop.promotion.entity.PmtUserCoupon;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponIssueMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponScopeMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.UserCouponMapper;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.spi.member.MemberQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 新模型券的实现。
 *
 * <p><b>类名带 Pmt 前缀是必要的，不是啰嗦</b>：老包里也有一个
 * {@code CouponServiceImpl}（{@code marketing.coupon.impl}）。两个同名类的
 * 默认 Bean 名都是 {@code couponServiceImpl}，组件扫描直接
 * {@code ConflictingBeanDefinitionException} —— <b>整个上下文起不来</b>，
 * 而报错里只提到类名，看不出是「新老两套并存」造成的。
 *
 * <p>显式给 Bean 名也能绕过去，但读栈的人还是会一直分不清哪个是哪个。
 * P9 老包删掉之后可以把前缀一并去掉。
 */
@Service
public class PmtCouponServiceImpl implements CouponService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PmtCouponServiceImpl.class);

    /** 一天 */
    private static final long DAY = 86_400_000L;

    private final CouponMapper couponMapper;
    private final CouponScopeMapper scopeMapper;
    private final UserCouponMapper userCouponMapper;
    private final CouponIssueMapper issueMapper;
    private final MemberQueryPort memberPort;

    public PmtCouponServiceImpl(CouponMapper couponMapper, CouponScopeMapper scopeMapper,
                             UserCouponMapper userCouponMapper, CouponIssueMapper issueMapper,
                             MemberQueryPort memberPort) {
        this.couponMapper = couponMapper;
        this.scopeMapper = scopeMapper;
        this.userCouponMapper = userCouponMapper;
        this.issueMapper = issueMapper;
        this.memberPort = memberPort;
    }

    // ---------------------------------------------------------------- 读

    @Override
    public List<CouponVO> list(String entityNo, boolean includeEnded) {
        return couponMapper.selectList(Wrappers.<PmtCoupon>lambdaQuery()
                        .eq(PmtCoupon::getEntityNo, entityNo)
                        .ne(!includeEnded, PmtCoupon::getStatus, PmtCoupon.ENDED)
                        .isNull(PmtCoupon::getArchivedAt)
                        .orderByDesc(PmtCoupon::getId))
                .stream().map(this::vo).toList();
    }

    @Override
    public CouponVO detail(String entityNo, String couponNo) {
        return vo(require(entityNo, couponNo));
    }

    @Override
    public List<MyCouponVO> myCoupons(String userNo) {
        long now = System.currentTimeMillis();
        /*
         * **绕开数据域**：这一刻的会话是买家（SELF），而 `pmt_user_coupon` 同时按
         * SELF 与 MERCHANT 登记 —— 两条锚点都能命中，本来不必绕。
         * 但券模板 `pmt_coupon` 只按 entity_no 登记，读它会被判 1=0。
         * 券包读不出模板就没有标题、没有面额，整页空白而不报错。
         */
        List<PmtUserCoupon> rows = DataScopeContext.executeWithoutScope(() ->
                userCouponMapper.selectList(Wrappers.<PmtUserCoupon>lambdaQuery()
                        .eq(PmtUserCoupon::getUserNo, userNo)
                        .ne(PmtUserCoupon::getStatus, PmtUserCoupon.REVOKED)
                        .orderByDesc(PmtUserCoupon::getId)));
        List<MyCouponVO> out = new ArrayList<>();
        for (PmtUserCoupon uc : rows) {
            PmtCoupon c = DataScopeContext.executeWithoutScope(() ->
                    couponMapper.selectOne(Wrappers.<PmtCoupon>lambdaQuery()
                            .eq(PmtCoupon::getCouponNo, uc.getCouponNo()).last("limit 1")));
            if (c == null) {
                continue;
            }
            int total = c.timesTotalOrOne();
            int used = nz(uc.getTimesUsed());
            boolean usable = PmtUserCoupon.UNUSED.equals(uc.getStatus())
                    && used < total
                    && (nz(uc.getExpireAt()) == 0 || nz(uc.getExpireAt()) >= now)
                    && PmtCoupon.ACTIVE.equals(c.getStatus());
            out.add(new MyCouponVO(uc.getUserCouponNo(), c.getCouponNo(), c.getTitle(),
                    benefitText(c), c.getEntityNo(), c.getRedeemMode(),
                    // 码只在到店券上给：下单抵扣的券显示一个码，顾客会拿着它去店里问
                    PmtCoupon.REDEEM_STORE_CODE.equals(c.getRedeemMode())
                            ? uc.getRedeemCode() : null,
                    c.getMinAmountMinor(), total, used, Math.max(0, total - used),
                    nz(uc.getExpireAt()), uc.getStatus(), usable));
        }
        return out;
    }

    /** 券面上那句人话。**折扣券要把「几折」说出来** —— 一个金额字段表达不了它 */
    private String benefitText(PmtCoupon c) {
        return switch (c.getBenefitMode()) {
            case PmtCoupon.GIFT -> "凭券兑换";
            case PmtCoupon.PERCENT -> (nz(c.getBenefitValue()) / 1000.0) + " 折";
            case PmtCoupon.FREE_SHIP -> "免运费";
            default -> "减 " + (nz(c.getBenefitValue()) / 100.0) + " 元";
        };
    }

    // ---------------------------------------------------------------- 建券

    @Override
    @Transactional
    public CouponVO save(String entityNo, CouponSaveCmd cmd, String operatorNo) {
        PmtCoupon c = cmd.couponNo() == null || cmd.couponNo().isBlank()
                ? null : require(entityNo, cmd.couponNo());
        boolean create = c == null;
        if (create) {
            c = new PmtCoupon();
            c.setCouponNo(BizKey.next(BizKey.PROMO_COUPON));
            c.setEntityNo(entityNo);
            c.setFunder(PmtCoupon.BY_MERCHANT);
            c.setReceivedCount(0);
            c.setStatus(PmtCoupon.ACTIVE);
        }

        apply(c, cmd);
        assertSane(c, create ? 0 : nz(c.getReceivedCount()));

        if (create) {
            couponMapper.insert(c);
        } else {
            couponMapper.updateById(c);
        }
        saveScopes(c.getCouponNo(), cmd);
        log.info("[券] {} 券 {} by {}", create ? "建" : "改", c.getCouponNo(), operatorNo);
        return vo(c);
    }

    private void apply(PmtCoupon c, CouponSaveCmd cmd) {
        c.setTitle(trim(cmd.title()));
        c.setBenefitMode(blank(cmd.benefitMode()) ? PmtCoupon.CASH : cmd.benefitMode());
        c.setBenefitValue(cmd.benefitValue() == null ? 0L : cmd.benefitValue());
        c.setBenefitCapMinor(cmd.benefitCapMinor());
        c.setBenefitRef(cmd.benefitRef());
        c.setMinAmountMinor(cmd.minAmountMinor());
        c.setMinQty(cmd.minQty());
        c.setScopeType(blank(cmd.scopeType()) ? PmtCoupon.SCOPE_ALL : cmd.scopeType());
        c.setScopeDesc(cmd.scopeDesc());
        c.setValidityMode(blank(cmd.validityMode()) ? PmtCoupon.ABSOLUTE : cmd.validityMode());
        c.setStartAt(cmd.startAt());
        c.setEndAt(cmd.endAt());
        c.setValidDays(cmd.validDays());
        c.setIssueMode(blank(cmd.issueMode()) ? PmtCoupon.ISSUE_TARGETED : cmd.issueMode());
        c.setRedeemMode(blank(cmd.redeemMode()) ? PmtCoupon.REDEEM_ORDER : cmd.redeemMode());
        c.setTimesTotal(cmd.timesTotal() == null || cmd.timesTotal() < 1 ? 1 : cmd.timesTotal());
        c.setTotalCount(cmd.totalCount());
        c.setPerUserLimit(cmd.perUserLimit() == null || cmd.perUserLimit() < 1
                ? 1 : cmd.perUserLimit());
        c.setBudgetMinor(cmd.budgetMinor());
    }

    /**
     * 建券时的全部硬校验。<b>每一条都堵的是「运行时才发现就晚了」的事</b>。
     *
     * @param received 已领张数。改券时发行量不能改到低于它 ——
     *                 那等于「已经超发了」这个状态被人为造出来，而没有任何补救动作
     */
    private void assertSane(PmtCoupon c, int received) {
        if (blank(c.getTitle())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        switch (c.getBenefitMode()) {
            case PmtCoupon.PERCENT -> {
                long rate = nz(c.getBenefitValue());
                /*
                 * 万分比：8500 = 八五折（顾客付 85%）。
                 *
                 * **下界卡在 1000（一折）而不是 0**：商家想写「88 折」时很容易填成 88，
                 * 而 88 在这个口径里是「顾客付 0.88%」—— 等于白送。两个数看着都像对的，
                 * 差 100 倍。真心想打一折以下的券几乎不存在，误填却很常见，
                 * 所以这里宁可错拒：被拒的商家会来问一句，白送出去的货追不回来。
                 */
                if (rate < 1_000 || rate >= 10_000) {
                    throw BizException.of(ErrorCode.COUPON_RATE_INVALID);
                }
                // 不封顶的折扣券，敞口随订单金额无限放大 —— 只能在核销那一刻去追预算
                if (nz(c.getBenefitCapMinor()) <= 0) {
                    throw BizException.of(ErrorCode.COUPON_DISCOUNT_CAP_REQUIRED);
                }
            }
            case PmtCoupon.CASH -> {
                if (nz(c.getBenefitValue()) <= 0) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
            case PmtCoupon.GIFT -> {
                if (blank(c.getBenefitRef())) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
            default -> { /* FREE_SHIP 没有额外参数 */ }
        }

        if (PmtCoupon.RELATIVE.equals(c.getValidityMode())) {
            if (c.getValidDays() == null || c.getValidDays() <= 0) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        } else if (nz(c.getStartAt()) <= 0 || nz(c.getEndAt()) <= nz(c.getStartAt())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        /*
         * **下单抵扣的券，范围只能是全店或指定门店。**
         *
         * 类目券、商品券要在算价时判「这一单里有没有这类商品」，而算价拿到的
         * 只有「哪个商家、多少钱」，没有商品明细（CouponPort.allocate 的签名）。
         * 放行的话，写着「仅限粮油」的券买猫粮照样能用 —— 这正是老模型
         * scope_desc 只是文案带来的那个问题，换个地方重演一遍。
         *
         * 到店核销的券不走算价，所以不受这条限制。
         */
        boolean itemScoped = PmtCoupon.SCOPE_CATEGORY.equals(c.getScopeType())
                || PmtCoupon.SCOPE_GOODS.equals(c.getScopeType());
        if (itemScoped && PmtCoupon.REDEEM_ORDER.equals(c.getRedeemMode())) {
            throw BizException.of(ErrorCode.COUPON_SCOPE_UNSUPPORTED);
        }

        Integer total = c.getTotalCount();
        if (total != null && total < received) {
            throw BizException.of(ErrorCode.COUPON_TOTAL_BELOW_ISSUED);
        }
        // 发行量留空只有定向发放允许：领券中心的券不限量，等于把敞口交给运气
        if (total == null && !PmtCoupon.ISSUE_TARGETED.equals(c.getIssueMode())) {
            throw BizException.of(ErrorCode.COUPON_TOTAL_COUNT_REQUIRED);
        }

        long budget = nz(c.getBudgetMinor());
        long exposure = (long) (total == null ? 0 : total) * maxPerCoupon(c);
        if (budget > 0 && total != null && budget < exposure) {
            /*
             * 文案是「预算不能低于最大敞口（{0} 分）」—— 必须把敞口传进去。
             * 不传的话运营看到的是字面的 `{0}`，而这条错误的全部价值就在那个数：
             * 它告诉运营该把预算提到多少。没有它，这句话等于「不行，我不告诉你为什么」。
             */
            throw BizException.of(ErrorCode.COUPON_BUDGET_BELOW_EXPOSURE, exposure);
        }
    }

    /** 单张最大优惠：现金看面额、折扣看封顶、兑换与免运费在这一步算 0 */
    private long maxPerCoupon(PmtCoupon c) {
        return switch (c.getBenefitMode()) {
            case PmtCoupon.CASH -> nz(c.getBenefitValue());
            case PmtCoupon.PERCENT -> nz(c.getBenefitCapMinor());
            default -> 0L;
        } * c.timesTotalOrOne();
    }

    /** 范围整批换掉：改券时不做增量 —— 增量在「删掉一个类目」上一定会漏 */
    private void saveScopes(String couponNo, CouponSaveCmd cmd) {
        scopeMapper.delete(Wrappers.<PmtCouponScope>lambdaQuery()
                .eq(PmtCouponScope::getCouponNo, couponNo));
        if (cmd.scopeRefs() == null || cmd.scopeRefs().isEmpty()
                || PmtCoupon.SCOPE_ALL.equals(cmd.scopeType())) {
            return;
        }
        for (String ref : cmd.scopeRefs().stream().filter(x -> !blank(x)).distinct().toList()) {
            PmtCouponScope row = new PmtCouponScope();
            row.setCouponNo(couponNo);
            row.setScopeType(cmd.scopeType());
            row.setRefNo(ref);
            scopeMapper.insert(row);
        }
    }

    @Override
    @Transactional
    public CouponVO setStatus(String entityNo, String couponNo, String status) {
        if (!PmtCoupon.ACTIVE.equals(status) && !PmtCoupon.PAUSED.equals(status)
                && !PmtCoupon.ENDED.equals(status)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PmtCoupon c = require(entityNo, couponNo);
        // 已结束不可复活：券的有效期与预算都是按那个终点算过的
        if (PmtCoupon.ENDED.equals(c.getStatus())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        c.setStatus(status);
        couponMapper.updateById(c);
        return vo(c);
    }

    // ---------------------------------------------------------------- 发放

    @Override
    @Transactional
    public CouponIssueVO issue(String entityNo, String couponNo, String segmentNo,
                               String operatorNo) {
        PmtCoupon c = require(entityNo, couponNo);
        if (!PmtCoupon.ACTIVE.equals(c.getStatus())) {
            throw BizException.of(ErrorCode.COUPON_NOT_ACTIVE);
        }
        MemberQueryPort.SegmentAudience audience = memberPort.resolveSegment(entityNo, segmentNo);

        long now = System.currentTimeMillis();
        long per = maxPerCoupon(c);
        // 不可触达的那部分先算出来：他们从一开始就进不了受众，不是「发失败」
        int unreachable = audience.matched() - audience.reachable().size();

        List<String> targets = new ArrayList<>();
        int alreadyHas = 0;
        for (MemberQueryPort.Audience a : audience.reachable()) {
            Long held = userCouponMapper.selectCount(Wrappers.<PmtUserCoupon>lambdaQuery()
                    .eq(PmtUserCoupon::getCouponNo, couponNo)
                    .eq(PmtUserCoupon::getUserNo, a.userNo()));
            if (held != null && held >= nz(c.getPerUserLimit())) {
                alreadyHas++;
                continue;
            }
            targets.add(a.userNo());
        }

        // 库存：发行量留空 = 不限
        int soldOut = 0;
        if (c.getTotalCount() != null) {
            int left = Math.max(0, c.getTotalCount() - nz(c.getReceivedCount()));
            if (targets.size() > left) {
                soldOut = targets.size() - left;
                targets = targets.subList(0, left);
            }
        }

        /*
         * **预算是硬闸门，且不部分发放。**
         * 部分发放会留下一个谁也说不清的中间态：发到第几个人？没发的那些怎么办？
         * 界面上那句「超出剩余预算会被拒绝，不会部分发放」必须是真的。
         */
        long amount = (long) targets.size() * per;
        long budget = nz(c.getBudgetMinor());
        if (budget > 0) {
            long spent = (long) nz(c.getReceivedCount()) * per;
            if (spent + amount > budget) {
                throw BizException.of(ErrorCode.COUPON_BUDGET_EXCEEDED);
            }
        }

        for (String userNo : targets) {
            PmtUserCoupon uc = new PmtUserCoupon();
            uc.setUserCouponNo(BizKey.next(BizKey.PROMO_USER_COUPON));
            uc.setCouponNo(couponNo);
            uc.setUserNo(userNo);
            uc.setEntityNo(entityNo);
            uc.setStatus(PmtUserCoupon.UNUSED);
            uc.setTimesUsed(0);
            uc.setReceivedAt(now);
            /*
             * **领取时就把到期时刻算好落库**。现算的话，商家把模板的天数从 7 改成 3，
             * 会把已经发出去的券一起改掉 —— 用户手上昨天还能用的券今天过期了，
             * 而没有任何记录说明发生过什么。
             */
            uc.setExpireAt(PmtCoupon.RELATIVE.equals(c.getValidityMode())
                    ? now + (long) c.getValidDays() * DAY
                    : nz(c.getEndAt()));
            if (PmtCoupon.REDEEM_STORE_CODE.equals(c.getRedeemMode())) {
                uc.setRedeemCode(redeemCode());
            }
            userCouponMapper.insert(uc);
        }

        c.setReceivedCount(nz(c.getReceivedCount()) + targets.size());
        couponMapper.updateById(c);

        int skipped = unreachable + alreadyHas + soldOut;
        PmtCouponIssue batch = new PmtCouponIssue();
        batch.setIssueNo(BizKey.next(BizKey.PROMO_ISSUE));
        batch.setCouponNo(couponNo);
        batch.setEntityNo(entityNo);
        batch.setIssueMode(PmtCoupon.ISSUE_TARGETED);
        batch.setSegmentNo(segmentNo);
        batch.setPlannedCount(audience.matched());
        batch.setIssuedCount(targets.size());
        batch.setSkippedCount(skipped);
        batch.setSkipDetail(skipDetail(unreachable, alreadyHas, soldOut));
        batch.setAmountMinor(amount);
        batch.setOperatorNo(operatorNo);
        batch.setIssuedAt(now);
        issueMapper.insert(batch);

        log.info("[券] 定向发放 {} 人群 {} 计划 {} 发出 {} 跳过 {}({})",
                couponNo, segmentNo, audience.matched(), targets.size(), skipped,
                batch.getSkipDetail());
        return issueVo(batch);
    }

    @Override
    public List<CouponIssueVO> issues(String entityNo, String couponNo) {
        return issueMapper.selectList(Wrappers.<PmtCouponIssue>lambdaQuery()
                        .eq(PmtCouponIssue::getEntityNo, entityNo)
                        .eq(couponNo != null && !couponNo.isBlank(),
                                PmtCouponIssue::getCouponNo, couponNo)
                        .orderByDesc(PmtCouponIssue::getId))
                .stream().map(this::issueVo).toList();
    }

    /**
     * 到店核销码。
     *
     * <p><b>去掉 0/O/1/I/L</b>：店员是照着顾客手机屏幕手输的，这几个字符在小屏上
     * 分不清，输错一个就是「没找到这张券」，而顾客会坚持说码是对的。
     *
     * <p>8 位、32 个字符 ≈ 1 万亿种组合，配合「必须带 entityNo 查」，
     * 猜码这条路不成立。撞库不是威胁模型 —— 真正的威胁是**输错**。
     */
    private static String redeemCode() {
        final String alphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** 三类跳过存成 `原因:数量` —— 界面要把话说全，不能只报一个总数 */
    private static String skipDetail(int unreachable, int alreadyHas, int soldOut) {
        List<String> parts = new ArrayList<>();
        if (unreachable > 0) {
            parts.add("UNREACHABLE:" + unreachable);
        }
        if (alreadyHas > 0) {
            parts.add("ALREADY_HAS:" + alreadyHas);
        }
        if (soldOut > 0) {
            parts.add("SOLD_OUT:" + soldOut);
        }
        return String.join(",", parts);
    }

    private CouponIssueVO issueVo(PmtCouponIssue b) {
        List<CouponIssueVO.SkipReason> reasons = new ArrayList<>();
        if (b.getSkipDetail() != null && !b.getSkipDetail().isBlank()) {
            for (String part : b.getSkipDetail().split(",")) {
                String[] kv = part.split(":");
                if (kv.length == 2) {
                    reasons.add(new CouponIssueVO.SkipReason(kv[0], Integer.parseInt(kv[1])));
                }
            }
        }
        return new CouponIssueVO(b.getIssueNo(), b.getCouponNo(), b.getSegmentNo(),
                nz(b.getPlannedCount()), nz(b.getIssuedCount()), nz(b.getSkippedCount()),
                reasons, nz(b.getAmountMinor()), b.getOperatorNo(), nz(b.getIssuedAt()));
    }

    private CouponVO vo(PmtCoupon c) {
        List<String> refs = scopeMapper.selectList(Wrappers.<PmtCouponScope>lambdaQuery()
                        .eq(PmtCouponScope::getCouponNo, c.getCouponNo()))
                .stream().map(PmtCouponScope::getRefNo).toList();
        Long exposure = c.getTotalCount() == null ? null : c.getTotalCount() * maxPerCoupon(c);
        return new CouponVO(c.getCouponNo(), c.getTitle(), c.getBenefitMode(), c.getBenefitValue(),
                c.getBenefitCapMinor(), c.getBenefitRef(), c.getMinAmountMinor(), c.getMinQty(),
                c.getScopeType(), refs, c.getScopeDesc(), c.getValidityMode(),
                c.getStartAt(), c.getEndAt(), c.getValidDays(), c.getIssueMode(),
                c.getRedeemMode(), c.timesTotalOrOne(), c.getTotalCount(),
                nz(c.getReceivedCount()), nz(c.getPerUserLimit()), c.getBudgetMinor(),
                exposure, c.getStatus());
    }

    private PmtCoupon require(String entityNo, String couponNo) {
        PmtCoupon c = couponMapper.selectOne(Wrappers.<PmtCoupon>lambdaQuery()
                .eq(PmtCoupon::getEntityNo, entityNo)
                .eq(PmtCoupon::getCouponNo, couponNo).last("limit 1"));
        if (c == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return c;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
