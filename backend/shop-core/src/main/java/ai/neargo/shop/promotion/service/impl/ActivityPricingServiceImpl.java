package ai.neargo.shop.promotion.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityAudience;
import ai.neargo.shop.promotion.entity.PmtActivityGoods;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityAudienceMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityGoodsMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
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

    public ActivityPricingServiceImpl(ActivityMapper activityMapper,
                                      ActivityAudienceMapper audienceMapper,
                                      ActivityGoodsMapper goodsMapper,
                                      MemberQueryPort memberPort) {
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
            for (PmtActivity a : live(g.merchantNo(), g.storeNo(), now)) {
                if (!PmtActivity.BENEFIT_CUT.equals(a.getBenefitType())) {
                    continue;
                }
                if (!PmtActivity.TRIGGER_AMOUNT.equals(a.getTriggerType())
                        || g.goodsAmount() < nz(a.getTriggerAmountMinor())) {
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
            if (best != null && bestOff > 0) {
                shares.add(new CampaignPort.MerchantDiscount(g.merchantNo(), bestOff));
                applied.add(new CampaignPort.AppliedActivity(best.getActivityNo(),
                        g.merchantNo(), bestOff, 1));
                total += bestOff;
            }
        }
        return shares.isEmpty() ? CampaignPort.Discount.none()
                : new CampaignPort.Discount(total, shares, applied);
    }

    @Override
    public Map<String, Long> flashPrices(String userNo, Collection<String> goodsNos) {
        if (goodsNos == null || goodsNos.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> out = new HashMap<>();
        for (PmtActivity a : liveByGoods(goodsNos, PmtActivity.BENEFIT_PRICE)) {
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
    public void commit(String orderNo, CampaignPort.Discount discount) {
        if (discount == null || discount.applied().isEmpty()) {
            return;
        }
        for (CampaignPort.AppliedActivity it : discount.applied()) {
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
                case PmtActivityAudience.SEGMENT ->
                        me.segmentNos().contains(r.getAudienceValue());
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
