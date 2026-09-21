package ai.neargo.shop.promotion.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.entity.PmtActivityAudience;
import ai.neargo.shop.promotion.entity.PmtActivityGoods;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityAudienceMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityGoodsMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ApplyMapper;
import ai.neargo.shop.promotion.service.ActivityPricingService;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.member.MemberQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ActivityPricingServiceImpl implements ActivityPricingService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ActivityPricingServiceImpl.class);

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");

    private final ActivityMapper activityMapper;
    private final ActivityAudienceMapper audienceMapper;
    private final ActivityGoodsMapper goodsMapper;
    private final MemberQueryPort memberPort;
    /** 活动效果按 promo_no 聚合，读的就是这张表 —— 与券的每一次使用同一张 */
    private final ApplyMapper applyMapper;

    /**
     * 平台活动的报名单与报名的货（P3）。setter 注入：切片测试里没有它们时，平台活动不参与算价，
     * 其余行为与加这一段之前逐字相同。
     */
    private ai.neargo.shop.promotion.mapper.PromotionMappers.EnrollmentMapper enrollmentMapper;
    private ai.neargo.shop.promotion.mapper.PromotionMappers.EnrollmentGoodsMapper enrollmentGoodsMapper;

    /** 自己组合的行（P3b）。setter 注入，缺了组合活动不参与算价 */
    private ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityRuleMapper ruleMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRuleMapper(ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEnrollmentMappers(ai.neargo.shop.promotion.mapper.PromotionMappers.EnrollmentMapper enrollmentMapper,
                                     ai.neargo.shop.promotion.mapper.PromotionMappers.EnrollmentGoodsMapper enrollmentGoodsMapper) {
        this.enrollmentMapper = enrollmentMapper;
        this.enrollmentGoodsMapper = enrollmentGoodsMapper;
    }

    /**
     * 券名只在 {@link #appliedOf} 里用到（订单详情回查那一条券叫什么）。
     * **setter 注入**：切片测试里没有它时，券那一条就不给名字，其余行为一字不差。
     */
    private ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper couponMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCouponMapper(ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper couponMapper) {
        this.couponMapper = couponMapper;
    }

    public ActivityPricingServiceImpl(ActivityMapper activityMapper,
                                      ActivityAudienceMapper audienceMapper,
                                      ActivityGoodsMapper goodsMapper,
                                      MemberQueryPort memberPort,
                                      ApplyMapper applyMapper) {
        this.applyMapper = applyMapper;
        this.activityMapper = activityMapper;
        this.audienceMapper = audienceMapper;
        this.goodsMapper = goodsMapper;
        this.memberPort = memberPort;
    }

    @Override
    public CampaignPort.Discount autoDiscount(String userNo,
                                              List<CampaignPort.MerchantAmount> groups) {
        if (groups == null || groups.isEmpty()) {
            return CampaignPort.Discount.none();
        }
        long now = System.currentTimeMillis();
        List<CampaignPort.MerchantDiscount> shares = new ArrayList<>();
        List<CampaignPort.AppliedActivity> applied = new ArrayList<>();
        long total = 0L;

        for (CampaignPort.MerchantAmount g : groups) {
            PmtActivity best = null;
            long bestOff = 0L;
            PlatformHit bestPlatform = null;
            for (PmtActivity a : live(g.merchantNo(), g.storeNo(), now)) {
                if (PmtActivity.BENEFIT_COMBO.equals(a.getBenefitType())) {
                    /*
                     * 自己组合（P3b）与满减类**同类取最优**：一单只减一个满减类活动，按买家省多少比。
                     * 只送积分、不减钱的组合（off = 0）在没有别的活动时照样生效 —— 否则它永远赢不了任何比较。
                     */
                    if (!audienceHits(a, g.merchantNo(), userNo)) {
                        continue;
                    }
                    ComboHit h = comboHit(a, g);
                    if (h != null && (h.off() > bestOff || (best == null && bestOff == 0 && h.points() > 0))) {
                        bestOff = h.off();
                        best = a;
                    }
                    continue;
                }
                if (!PmtActivity.BENEFIT_CUT.equals(a.getBenefitType())) {
                    continue;
                }
                if (!cutTriggerHits(a, g)) {
                    continue;
                }
                if (!audienceHits(a, g.merchantNo(), userNo)) {
                    continue;
                }
                // 减不能超过商品额：券那边同一条规矩，否则会减出负数
                long off = Math.min(nz(a.getBenefitAmountMinor()), g.goodsAmount());
                // **同类取最优**：商家多建一个活动不该让顾客少减
                if (off > bestOff) {
                    bestOff = off;
                    best = a;
                }
            }
            /*
             * 平台活动（P3）与商家活动**同类取最优**：一单只减一个满减类活动，按买家能省多少比 ——
             * 同样的钱，谁出资不影响买家该减多少。平台活动只按报名里那几件货的小计判门槛与封顶。
             */
            for (PlatformHit h : platformHits(g, now)) {
                if (h.off() > bestOff) {
                    bestOff = h.off();
                    best = null;
                    bestPlatform = h;
                }
            }
            if (bestPlatform != null && bestOff > 0) {
                shares.add(new CampaignPort.MerchantDiscount(g.merchantNo(), bestOff));
                applied.add(new CampaignPort.AppliedActivity(bestPlatform.activity().getActivityNo(),
                        g.merchantNo(), bestOff, 1, bestPlatform.platformMinor(),
                        bestPlatform.enrollmentNo(), bestPlatform.activity().getName()));
                total += bestOff;
            } else if (best != null && bestOff > 0) {
                shares.add(new CampaignPort.MerchantDiscount(g.merchantNo(), bestOff));
                applied.add(new CampaignPort.AppliedActivity(best.getActivityNo(),
                        g.merchantNo(), bestOff, 1, 0L, null, best.getName()));
                total += bestOff;
            } else if (best != null && PmtActivity.BENEFIT_COMBO.equals(best.getBenefitType())) {
                // 只送积分的组合：不减钱，但要记下「这一单用上了它」—— 付款时按它发积分、扣它的量
                applied.add(new CampaignPort.AppliedActivity(best.getActivityNo(), g.merchantNo(), 0L, 1));
            }
        }
        return shares.isEmpty() ? CampaignPort.Discount.none()
                : new CampaignPort.Discount(total, shares, applied);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void release(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return;
        }
        var rows = DataScopeContext.executeWithoutScope(() -> applyMapper.selectList(
                Wrappers.<ai.neargo.shop.promotion.entity.PmtApply>lambdaQuery()
                        .eq(ai.neargo.shop.promotion.entity.PmtApply::getOrderNo, orderNo)
                        .eq(ai.neargo.shop.promotion.entity.PmtApply::getPromoType,
                                CampaignPort.AppliedDiscount.ACTIVITY)));
        for (var r : rows) {
            long amount = r.getAmountMinor() == null ? 0L : r.getAmountMinor();
            /*
             * 配额按「份」退，一单占一份（与 commit 那边的 qty 同一口径）。
             * **用 SQL 直接减并且不许减成负数** —— 并发下两次关单撞在一起时，
             * 负数配额会让这个活动此后谁都抢不到，而它看起来完全正常。
             */
            DataScopeContext.executeWithoutScope(() -> activityMapper.update(null,
                    Wrappers.<PmtActivity>lambdaUpdate()
                            .eq(PmtActivity::getActivityNo, r.getPromoNo())
                            .apply("quota_used > 0")
                            .setSql("quota_used = quota_used - 1")
                            .setSql("budget_used_minor = GREATEST(budget_used_minor - "
                                    + amount + ", 0)")));
            /*
             * 作废这一行。**幂等就在这里**：逻辑删之后下次查不到，也就不会再退一次。
             * 不物理删：这张表是对账与活动效果的账本，删了就查不出「当时发生过什么」。
             */
            DataScopeContext.executeWithoutScope(() -> applyMapper.deleteById(r.getId()));
        }
    }

    @Override
    public java.util.List<CampaignPort.AppliedDiscount> appliedOf(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return java.util.List.of();
        }
        var rows = DataScopeContext.executeWithoutScope(() -> applyMapper.selectList(
                Wrappers.<ai.neargo.shop.promotion.entity.PmtApply>lambdaQuery()
                        .eq(ai.neargo.shop.promotion.entity.PmtApply::getOrderNo, orderNo)));
        java.util.List<CampaignPort.AppliedDiscount> out = new ArrayList<>();
        for (var r : rows) {
            long amount = r.getAmountMinor() == null ? 0L : r.getAmountMinor();
            if (amount <= 0) {
                // 只送积分的组合也会记一行（减 0）—— 那不是「优惠依据」，别列出来
                continue;
            }
            String name = nameOfPromo(r.getPromoType(), r.getPromoNo());
            if (name == null || name.isBlank()) {
                // 名字取不到就不给这一条：占位说法与真名字长得一样，读的人分不出
                continue;
            }
            out.add(new CampaignPort.AppliedDiscount(
                    CampaignPort.AppliedDiscount.COUPON.equals(r.getPromoType())
                            ? CampaignPort.AppliedDiscount.COUPON
                            : CampaignPort.AppliedDiscount.ACTIVITY,
                    name, amount));
        }
        return out;
    }

    /** 活动名 / 券名。查不到给空 —— 调用方据此跳过这一条 */
    private String nameOfPromo(String type, String no) {
        if (no == null || no.isBlank()) {
            return null;
        }
        if (CampaignPort.AppliedDiscount.COUPON.equals(type)) {
            if (couponMapper == null) {
                return null;
            }
            var c = DataScopeContext.executeWithoutScope(() -> couponMapper.selectOne(
                    Wrappers.<ai.neargo.shop.promotion.entity.PmtCoupon>lambdaQuery()
                            .eq(ai.neargo.shop.promotion.entity.PmtCoupon::getCouponNo, no)
                            .last("limit 1")));
            return c == null ? null : c.getTitle();
        }
        var a = DataScopeContext.executeWithoutScope(() -> activityMapper.selectOne(
                Wrappers.<PmtActivity>lambdaQuery()
                        .eq(PmtActivity::getActivityNo, no).last("limit 1")));
        return a == null ? null : a.getName();
    }

    /**
     * 自己组合在这一家上的命中。
     *
     * @param off    买家少付多少（减钱与打折按顺序叠加，不超过作用范围的小计）
     * @param points 付款后额外送的积分
     */
    private record ComboHit(long off, long points) {
    }

    /**
     * 条件全部满足才生效（原型 s11「条件 · 全部满足」）。有「指定商品」时，
     * 件数与金额都按那几件货的小计判、优惠也只作用在它们上；没有就按整家的货。
     * 老调用方不传逐件明细时，带「指定商品」的组合不生效（不退回按整单判）。
     */
    private ComboHit comboHit(PmtActivity a, CampaignPort.MerchantAmount g) {
        if (ruleMapper == null) {
            return null;
        }
        List<ai.neargo.shop.promotion.entity.PmtActivityRule> rules = DataScopeContext.executeWithoutScope(() ->
                ruleMapper.selectList(Wrappers.<ai.neargo.shop.promotion.entity.PmtActivityRule>lambdaQuery()
                        .eq(ai.neargo.shop.promotion.entity.PmtActivityRule::getActivityNo, a.getActivityNo())
                        .orderByAsc(ai.neargo.shop.promotion.entity.PmtActivityRule::getSeq)));
        var items = rules.stream().map(ActivityServiceImpl::readParams).toList();
        long amount = g.goodsAmount();
        int qty = g.goodsQty();
        var scope = items.stream().filter(r -> "CONDITION".equals(r.kind()) && "GOODS".equals(r.type()))
                .findFirst().orElse(null);
        if (scope != null) {
            if (g.lines() == null || g.lines().isEmpty()) {
                return null;
            }
            java.util.Set<String> goods = new java.util.HashSet<>(scope.goodsNos() == null ? List.of() : scope.goodsNos());
            amount = 0L;
            qty = 0;
            for (CampaignPort.GoodsLine l : g.lines()) {
                if (goods.contains(l.goodsNo())) {
                    amount += l.amount();
                    qty += l.qty();
                }
            }
            if (amount <= 0) {
                return null;
            }
        }
        for (var r : items) {
            if (!"CONDITION".equals(r.kind())) {
                continue;
            }
            if ("AMOUNT".equals(r.type()) && amount < nz(r.amountMinor())) {
                return null;
            }
            if ("QTY".equals(r.type()) && qty < (r.n() == null ? 0 : r.n())) {
                return null;
            }
        }
        long off = 0L;
        long points = 0L;
        for (var r : items) {
            if (!"BENEFIT".equals(r.kind())) {
                continue;
            }
            switch (r.type()) {
                case "CUT" -> off += Math.min(nz(r.amountMinor()), amount - off);
                case "PERCENT" -> {
                    long cut = (amount - off) * (10_000 - (r.bp() == null ? 10_000 : r.bp())) / 10_000;
                    off += Math.min(cut, nz(r.capMinor()));
                }
                case "POINTS" -> points += r.n() == null ? 0 : r.n();
                default -> { }
            }
        }
        return new ComboHit(Math.max(0, Math.min(off, amount)), points);
    }

    @Override
    public long bonusPoints(String orderNo, String merchantNo) {
        if (ruleMapper == null || orderNo == null) {
            return 0L;
        }
        List<String> promos = DataScopeContext.executeWithoutScope(() -> applyMapper.selectList(
                        Wrappers.<PmtApply>lambdaQuery()
                                .eq(PmtApply::getOrderNo, orderNo)
                                .eq(PmtApply::getEntityNo, merchantNo)
                                .eq(PmtApply::getPromoType, PmtApply.ACTIVITY)
                                .isNull(PmtApply::getRevertedAt)))
                .stream().map(PmtApply::getPromoNo).distinct().toList();
        long total = 0L;
        for (String activityNo : promos) {
            total += DataScopeContext.executeWithoutScope(() -> ruleMapper.selectList(
                            Wrappers.<ai.neargo.shop.promotion.entity.PmtActivityRule>lambdaQuery()
                                    .eq(ai.neargo.shop.promotion.entity.PmtActivityRule::getActivityNo, activityNo)
                                    .eq(ai.neargo.shop.promotion.entity.PmtActivityRule::getKind, "BENEFIT")
                                    .eq(ai.neargo.shop.promotion.entity.PmtActivityRule::getRuleType, "POINTS")))
                    .stream().map(ActivityServiceImpl::readParams)
                    .mapToLong(r -> r.n() == null ? 0 : r.n()).sum();
        }
        return total;
    }

    /**
     * 平台活动在这一家上的命中（P3 · 详细设计 §1.6）。
     *
     * @param off           买家这一单少付多少（按报名货的小计封顶）
     * @param platformMinor 其中平台出的部分 = off × 出资比例（向下取整，零头归商家）
     */
    private record PlatformHit(PmtActivity activity, String enrollmentNo, long off, long platformMinor) {
    }

    /**
     * 这家店<b>已通过、还有份数</b>的报名里，此刻在跑的平台活动能减多少。
     *
     * <p>生效范围是报名里的货：门槛按那几件货的小计判（{@code MerchantAmount.lines}）。
     * 老调用方不传逐件明细时这里返回空 —— 平台活动不生效，而不是退回按整单判
     * （那会让「买一件报名的货加一堆别的货」凑够满减）。
     *
     * <p>绕数据域读报名，边界靠 {@code entity_no = 这一单的商家}（买家会话下不绕就恒为空）。
     */
    private List<PlatformHit> platformHits(CampaignPort.MerchantAmount g, long now) {
        if (enrollmentMapper == null || g.lines() == null || g.lines().isEmpty()) {
            return List.of();
        }
        List<ai.neargo.shop.promotion.entity.PmtEnrollment> es = DataScopeContext.executeWithoutScope(() ->
                enrollmentMapper.selectList(Wrappers.<ai.neargo.shop.promotion.entity.PmtEnrollment>lambdaQuery()
                        .eq(ai.neargo.shop.promotion.entity.PmtEnrollment::getEntityNo, g.merchantNo())
                        .eq(ai.neargo.shop.promotion.entity.PmtEnrollment::getStatus,
                                ai.neargo.shop.promotion.entity.PmtEnrollment.APPROVED)
                        .apply("quota_used < quota")));
        List<PlatformHit> out = new ArrayList<>();
        for (var e : es) {
            PmtActivity a = DataScopeContext.executeWithoutScope(() ->
                    activityMapper.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                            .eq(PmtActivity::getActivityNo, e.getActivityNo())
                            .eq(PmtActivity::getOwner, PmtActivity.OWNER_PLATFORM)
                            .last("limit 1")));
            if (a == null || !PmtActivity.BENEFIT_CUT.equals(a.getBenefitType()) || !a.isActiveAt(now, MARKET_ZONE)) {
                continue;
            }
            java.util.Set<String> goods = DataScopeContext.executeWithoutScope(() ->
                            enrollmentGoodsMapper.selectList(Wrappers.<ai.neargo.shop.promotion.entity.PmtEnrollmentGoods>lambdaQuery()
                                    .eq(ai.neargo.shop.promotion.entity.PmtEnrollmentGoods::getEnrollmentNo, e.getEnrollmentNo())))
                    .stream().map(ai.neargo.shop.promotion.entity.PmtEnrollmentGoods::getGoodsNo)
                    .collect(java.util.stream.Collectors.toSet());
            long amount = 0L;
            int qty = 0;
            for (CampaignPort.GoodsLine l : g.lines()) {
                if (goods.contains(l.goodsNo())) {
                    amount += l.amount();
                    qty += l.qty();
                }
            }
            if (amount <= 0) {
                continue;
            }
            CampaignPort.MerchantAmount scoped = new CampaignPort.MerchantAmount(g.merchantNo(), amount, qty, g.storeNo());
            if (!cutTriggerHits(a, scoped)) {
                continue;
            }
            long off = Math.min(nz(a.getBenefitAmountMinor()), amount);
            int bp = a.getPlatformShareBp() == null ? 0 : a.getPlatformShareBp();
            out.add(new PlatformHit(a, e.getEnrollmentNo(), off, off * bp / 10_000));
        }
        return out;
    }

    /**
     * 减钱类活动的门槛判定。<b>三种触发，各判各的字段</b>：
     *
     * <ul>
     *   <li>{@code AMOUNT} —— 满额减，看金额</li>
     *   <li>{@code QTY} —— 满件减，看件数（不含赠品，见 {@code MerchantAmount.goodsQty}）</li>
     *   <li>{@code NONE} —— 无门槛立减，恒命中</li>
     * </ul>
     *
     * <p><b>其余触发一律跳过而不是放行</b>：{@code GOODS × CUT}「买这几件货减 X」
     * 这一侧拿到的是按商家汇总后的金额，没有逐件明细，减在哪一件上无从摊分。
     * 放行的话它会变成一个整单减 —— 商家选了三件货，结果全店都减，且不报错。
     * 存那一侧已在 {@code ActivityServiceImpl.assertSane} 里直接拒绝这个组合。
     */
    private boolean cutTriggerHits(PmtActivity a, CampaignPort.MerchantAmount g) {
        return switch (a.getTriggerType() == null ? PmtActivity.TRIGGER_NONE : a.getTriggerType()) {
            case PmtActivity.TRIGGER_AMOUNT -> g.goodsAmount() >= nz(a.getTriggerAmountMinor());
            case PmtActivity.TRIGGER_QTY -> nz(a.getTriggerQty()) > 0
                    && g.goodsQty() >= nz(a.getTriggerQty());
            case PmtActivity.TRIGGER_NONE -> true;
            default -> false;
        };
    }

    @Override
    public Map<String, Long> flashPrices(String userNo, Collection<String> goodsNos) {
        if (goodsNos == null || goodsNos.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> out = new HashMap<>();
        for (PmtActivity a : liveByGoods(goodsNos, PmtActivity.BENEFIT_PRICE)) {
            /*
             * ★ **团购的成团价不是限时特价**（2026-09-18）。
             *
             * 两者的优惠都是 `BENEFIT_PRICE`，只有触发分得开。不判触发的话，
             * 商家建一个团购活动会让**所有人**按成团价买到 ——
             * 不用凑人数、不用进团，团购这件事整个失去意义。
             *
             * <b>而它一个字都不报</b>：价照样算得出来，订单照样成，
             * 只有对账时才看得出每一单都少收了钱。
             * 2026-09-18 被 groupPriceMustBeatOriginPrice 撞出来 ——
             * 那条用例里商品原价与成团价一路相等，正是这条在悄悄改价。
             *
             * 成团价只在**团里**生效，由开团那条链（mkt_group_buy）自己算。
             */
            if (PmtActivity.TRIGGER_GROUP.equals(a.getTriggerType())) {
                continue;
            }
            if (!audienceHits(a, a.getEntityNo(), userNo)) {
                continue;
            }
            for (String goodsNo : goodsOf(a.getActivityNo())) {
                if (!goodsNos.contains(goodsNo)) {
                    continue;
                }
                long price = nz(a.getBenefitAmountMinor());
                // **取最低价**：与满减「取最优」同一侧 —— 都往对用户有利的方向走
                out.merge(goodsNo, price, Math::min);
            }
        }
        return out;
    }

    @Override
    public Map<String, CampaignPort.GiftRule> giftRules(String userNo, Collection<String> goodsNos) {
        if (goodsNos == null || goodsNos.isEmpty()) {
            return Map.of();
        }
        Map<String, CampaignPort.GiftRule> out = new HashMap<>();
        for (PmtActivity a : liveByGoods(goodsNos, PmtActivity.BENEFIT_GIFT)) {
            if (!audienceHits(a, a.getEntityNo(), userNo)) {
                continue;
            }
            int buyN = nz(a.getTriggerQty());
            int giftM = nz(a.getBenefitQty());
            if (buyN <= 0 || giftM <= 0) {
                continue;
            }
            for (String goodsNo : goodsOf(a.getActivityNo())) {
                if (!goodsNos.contains(goodsNo)) {
                    continue;
                }
                CampaignPort.GiftRule cur = out.get(goodsNo);
                // 送得最多的那个赢 —— 同样往对用户有利的一侧走
                if (cur == null || giftM > cur.giftM()) {
                    out.put(goodsNo, new CampaignPort.GiftRule(buyN, giftM));
                }
            }
        }
        return out;
    }

    @Override
    @Transactional
    public void commit(String userNo, String orderNo, CampaignPort.Discount discount) {
        if (discount == null || discount.applied().isEmpty()) {
            return;
        }
        for (CampaignPort.AppliedActivity it : discount.applied()) {
            if (it.enrollmentNo() != null) {
                commitPlatform(userNo, orderNo, it);
                continue;
            }
            int affected = DataScopeContext.executeWithoutScope(() ->
                    activityMapper.update(null, Wrappers.<PmtActivity>lambdaUpdate()
                            .eq(PmtActivity::getActivityNo, it.activityNo())
                            // 限量为空 = 不限；有限量时必须还装得下这一单
                            .and(w -> w.isNull(PmtActivity::getQuota)
                                    .or().apply("quota_used + {0} <= quota", it.qty()))
                            .setSql("quota_used = quota_used + " + it.qty())
                            .setSql("budget_used_minor = budget_used_minor + "
                                    + it.amountMinor())));
            if (affected == 0) {
                /*
                 * 没抢到最后一份。**不抛异常**：订单已经按算价时的金额落库了，
                 * 这时候翻脸会让用户付了钱却下不成单 —— 那比多发一份的损失大得多。
                 * 记 WARN，让运营看到「超发了几份」：那是限量与并发之间必然存在的
                 * 一点点重叠，不是错误。
                 */
                log.warn("[活动] 限量已满仍命中：活动 {} 订单 {} 多发 {} 份",
                        it.activityNo(), orderNo, it.qty());
                continue;
            }
            /*
             * 记一行优惠发生记录。**与券共用 pmt_apply** ——
             * 「这一单命中了什么」只有一处答案，活动效果与券对账才不会各算各的。
             * 金额是算价时的结果，不在这里重算（重算依赖的规则会变）。
             */
            PmtApply row = new PmtApply();
            row.setApplyNo(BizKey.next(BizKey.PROMO_APPLY));
            row.setPromoType(PmtApply.ACTIVITY);
            row.setPromoNo(it.activityNo());
            row.setUserNo(userNo);
            row.setEntityNo(it.merchantNo());
            row.setOrderNo(orderNo);
            row.setRedeemMode(ai.neargo.shop.promotion.entity.PmtCoupon.REDEEM_ORDER);
            row.setAmountMinor(it.amountMinor());
            // 店铺活动的出资方恒为商家 —— 平台不出这个钱
            row.setFunder(ai.neargo.shop.promotion.entity.PmtCoupon.BY_MERCHANT);
            row.setAppliedAt(System.currentTimeMillis());
            DataScopeContext.executeWithoutScope(() -> applyMapper.insert(row));

            // 扣完正好到量：把活动收尾，并说清为什么停 —— 商家问「怎么停了」要有答案
            PmtActivity a = DataScopeContext.executeWithoutScope(() ->
                    activityMapper.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                            .eq(PmtActivity::getActivityNo, it.activityNo()).last("limit 1")));
            if (a != null && a.getQuota() != null && nz(a.getQuotaUsed()) >= a.getQuota()) {
                DataScopeContext.executeWithoutScope(() ->
                        activityMapper.update(null, Wrappers.<PmtActivity>lambdaUpdate()
                                .eq(PmtActivity::getActivityNo, it.activityNo())
                                .eq(PmtActivity::getStatus, PmtActivity.RUNNING)
                                .set(PmtActivity::getStatus, PmtActivity.ENDED)
                                .set(PmtActivity::getEndedReason, PmtActivity.ENDED_QUOTA)));
                log.info("[活动] {} 到量自动结束", it.activityNo());
            }
        }
    }

    /**
     * 平台活动这一单用掉的份数与钱（P3）。
     *
     * <p>限量扣在<b>报名单</b>上（商家报了多少份就是多少份），带条件的 UPDATE；平台花掉的钱记到活动的
     * {@code budget_used_minor}。{@code pmt_apply} 按出资方拆两行（平台、商家各一行），
     * 与子单 {@code discount_platform} / {@code discount_merchant} 对得上 —— 结算按子单那两列走。
     * 没抢到最后一份时与商家活动同一个处理：不抛，记 WARN。
     */
    private void commitPlatform(String userNo, String orderNo, CampaignPort.AppliedActivity it) {
        int affected = DataScopeContext.executeWithoutScope(() -> enrollmentMapper.update(null,
                Wrappers.<ai.neargo.shop.promotion.entity.PmtEnrollment>lambdaUpdate()
                        .eq(ai.neargo.shop.promotion.entity.PmtEnrollment::getEnrollmentNo, it.enrollmentNo())
                        .apply("quota_used + {0} <= quota", it.qty())
                        .setSql("quota_used = quota_used + " + it.qty())));
        if (affected == 0) {
            log.warn("[平台活动] 报名份数已满仍命中：报名 {} 订单 {}", it.enrollmentNo(), orderNo);
            return;
        }
        DataScopeContext.executeWithoutScope(() -> activityMapper.update(null, Wrappers.<PmtActivity>lambdaUpdate()
                .eq(PmtActivity::getActivityNo, it.activityNo())
                .setSql("budget_used_minor = budget_used_minor + " + it.platformMinor())));
        long merchantPart = it.amountMinor() - it.platformMinor();
        if (it.platformMinor() > 0) {
            insertApply(userNo, orderNo, it, it.platformMinor(), ai.neargo.shop.promotion.entity.PmtCoupon.BY_PLATFORM);
        }
        if (merchantPart > 0) {
            insertApply(userNo, orderNo, it, merchantPart, ai.neargo.shop.promotion.entity.PmtCoupon.BY_MERCHANT);
        }
    }

    private void insertApply(String userNo, String orderNo, CampaignPort.AppliedActivity it, long amount, String funder) {
        PmtApply row = new PmtApply();
        row.setApplyNo(BizKey.next(BizKey.PROMO_APPLY));
        row.setPromoType(PmtApply.ACTIVITY);
        row.setPromoNo(it.activityNo());
        row.setUserNo(userNo);
        row.setEntityNo(it.merchantNo());
        row.setOrderNo(orderNo);
        row.setRedeemMode(ai.neargo.shop.promotion.entity.PmtCoupon.REDEEM_ORDER);
        row.setAmountMinor(amount);
        row.setFunder(funder);
        row.setAppliedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> applyMapper.insert(row));
    }

    /**
     * 这家店此刻在跑的活动。
     *
     * <p>绕开数据域：算价时的会话是**买家**（SELF），而 {@code pmt_activity}
     * 按 entity_no 登记 —— 不绕的话查出来恒为空，表现是「所有活动都不生效」，
     * 而接口成功、日志干净。
     */
    private List<PmtActivity> live(String entityNo, String storeNo, long now) {
        List<PmtActivity> all = DataScopeContext.executeWithoutScope(() ->
                activityMapper.selectList(Wrappers.<PmtActivity>lambdaQuery()
                        .eq(PmtActivity::getEntityNo, entityNo)
                        .eq(PmtActivity::getStatus, PmtActivity.RUNNING)));
        List<PmtActivity> out = new ArrayList<>();
        for (PmtActivity a : all) {
            /*
             * 门店级活动只对那家店生效；全主体活动对谁都生效。
             * **storeNo 为空时只有全主体活动生效** —— 不是「所有门店活动都生效」，
             * 那会让一家店的开业满减减到别家店的单上。
             */
            if (a.getStoreNo() != null && !a.getStoreNo().equals(storeNo)) {
                continue;
            }
            if (!a.isActiveAt(now, MARKET_ZONE) || !a.hasQuotaLeft()) {
                continue;
            }
            out.add(a);
        }
        return out;
    }

    /** 按商品反查在跑的活动 —— `idx_pmt_goods_ref` 就是为这条路建的 */
    private List<PmtActivity> liveByGoods(Collection<String> goodsNos, String benefitType) {
        long now = System.currentTimeMillis();
        List<PmtActivityGoods> refs = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectList(Wrappers.<PmtActivityGoods>lambdaQuery()
                        .eq(PmtActivityGoods::getScopeType, PmtActivityGoods.GOODS)
                        .in(PmtActivityGoods::getRefNo, goodsNos)));
        List<PmtActivity> out = new ArrayList<>();
        for (String activityNo : refs.stream().map(PmtActivityGoods::getActivityNo)
                .distinct().toList()) {
            PmtActivity a = DataScopeContext.executeWithoutScope(() ->
                    activityMapper.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                            .eq(PmtActivity::getActivityNo, activityNo).last("limit 1")));
            if (a == null || !benefitType.equals(a.getBenefitType())) {
                continue;
            }
            if (!a.isActiveAt(now, MARKET_ZONE) || !a.hasQuotaLeft()) {
                continue;
            }
            out.add(a);
        }
        return out;
    }

    private List<String> goodsOf(String activityNo) {
        return DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectList(Wrappers.<PmtActivityGoods>lambdaQuery()
                                .eq(PmtActivityGoods::getActivityNo, activityNo))
                        .stream().map(PmtActivityGoods::getRefNo).toList());
    }

    /**
     * 他在不在这个活动的受众里。
     *
     * <p><b>一行受众都没有 = 对所有人生效</b>。这条默认值让存量活动迁过来之后
     * 行为逐分不变 —— 「空 = 谁都不给」会让所有老活动在上线那一刻集体失效。
     *
     * <p>多行之间是<b>或</b>：受众是「这些人都可以」，不是「必须同时满足」。
     * 与人群里的标签取交集刚好相反 —— 那边是筛人（收窄），这边是圈人（放宽）。
     */
    private boolean audienceHits(PmtActivity a, String entityNo, String userNo) {
        List<PmtActivityAudience> rules = DataScopeContext.executeWithoutScope(() ->
                audienceMapper.selectList(Wrappers.<PmtActivityAudience>lambdaQuery()
                        .eq(PmtActivityAudience::getActivityNo, a.getActivityNo())));
        if (rules.isEmpty()) {
            return true;
        }
        MemberQueryPort.MemberSnapshot me = memberPort.judge(entityNo, userNo);
        for (PmtActivityAudience r : rules) {
            boolean hit = switch (r.getAudienceType()) {
                case PmtActivityAudience.NON_MEMBER -> !me.member();
                case PmtActivityAudience.LEVEL -> me.member()
                        && r.getAudienceValue().equals(me.level());
                case PmtActivityAudience.SOURCE -> me.member()
                        && r.getAudienceValue().equals(me.source());
                case PmtActivityAudience.TAG -> me.tagNos().contains(r.getAudienceValue());
                // 有快照按快照（发布那一刻的人群），没有的是存量行，按人群此刻的条件（AC-9）
                case PmtActivityAudience.SEGMENT -> r.getRuleSnapshot() != null
                        ? memberPort.matchesRule(entityNo, userNo, r.getRuleSnapshot())
                        : me.segmentNos().contains(r.getAudienceValue());
                default -> false;
            };
            if (hit) {
                return true;
            }
        }
        return false;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
