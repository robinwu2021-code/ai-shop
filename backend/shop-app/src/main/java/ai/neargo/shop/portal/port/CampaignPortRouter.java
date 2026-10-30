package ai.neargo.shop.portal.port;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.port.CampaignPortImpl;
import ai.neargo.shop.promotion.service.ActivityPricingService;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 活动算价走老模型还是新模型 —— <b>两个都走，然后同类取最优</b>（P5）。
 *
 * <p><b>为什么不像券那样按数据分流</b>：券是「这一张券在哪张表」，唯一确定；
 * 活动是<b>按商家查出来的</b>，新旧两套表里可能各有一个满减 ——
 * 各减一次就是**减两次**，而那是最难发现的一类账目错误（金额看着"合理"）。
 *
 * <p>取最优不是新规则：同类活动之间本来就是取最优（见 {@link CampaignPort} 的类注释），
 * 老模型内部也是这么合并的。把新模型的活动当成"又几个候选"，语义完全一致。
 *
 * <p>P9 老表退场时，这个类连同 {@code marketing.port.CampaignPortImpl} 一起删掉。
 */
@Primary
@Component
public class CampaignPortRouter implements CampaignPort {

    private final CampaignPortImpl legacy;
    private final ActivityPricingService promo;

    public CampaignPortRouter(CampaignPortImpl legacy, ActivityPricingService promo) {
        this.legacy = legacy;
        this.promo = promo;
    }

    /** 「这一单当时减了什么」只有新模型记着（`pmt_apply`）—— 老模型那边没有这张账，所以不必合并，直接转发。 */
    @Override
    public List<AppliedActivity> counterCandidates(CustomerParty party, List<MerchantAmount> groups) {
        return counterCandidates(party, groups, Map.of()).candidates();
    }

    @Override
    public CounterCandidates counterCandidates(CustomerParty party, List<MerchantAmount> groups,
            Map<String, String> choices) {
        legacy.lockCounter(groups);
        long now = System.currentTimeMillis();
        promo.rejectUnsupportedCounterChoices(groups, choices, now);
        return legacy.evaluateCounter(party, groups, choices, now);
    }

    @Override
    public void requireCounterSelection(CustomerParty party, List<MerchantAmount> groups,
            Map<String, String> choices, CounterActivitySeal seal, Discount selected) {
        legacy.requireCounterEvidence(party, groups, choices, seal, selected);
        try {
            legacy.requireSameCounter(seal, counterCandidates(party, groups, choices));
        } catch (BizException failure) {
            if (failure.errorCode() == ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION
                    || failure.errorCode() == ErrorCode.ACTIVITY_CHOICE_UNAVAILABLE) {
                throw BizException.of(ErrorCode.CHECKOUT_QUOTE_STALE);
            }
            throw failure;
        }
    }

    @Override
    public void requireCounterSelection(CustomerParty party, Discount selected) {
        legacy.requireCounterSelection(party, selected);
    }

    @Override
    public Map<String, Long> counterFlashPrices(CustomerParty party, Collection<String> goodsNos) {
        promo.rejectUnsupportedCounterPriceEffects(goodsNos);
        return legacy.counterFlashPrices(party, goodsNos);
    }

    @Override
    public Map<String, GiftRule> counterGiftRules(
            CustomerParty party, Collection<String> goodsNos) {
        promo.rejectUnsupportedCounterPriceEffects(goodsNos);
        return legacy.counterGiftRules(party, goodsNos);
    }

    @Override
    public void commitCounter(CustomerParty party, String orderNo, Discount discount) {
        legacy.commitCounter(party, orderNo, discount);
    }

    @Override
    public List<AppliedDiscount> appliedOf(String orderNo) {
        return promo.appliedOf(orderNo);
    }

    /** 配额只有新模型有，直接转发（老模型没有这张账） */
    @Override
    public void release(String orderNo) {
        promo.release(orderNo);
    }

    @Override
    public Discount autoDiscount(List<MerchantAmount> groups) {
        return autoDiscount(groups, Map.of());
    }

    /**
     * 两套活动的候选合在一起：<b>老模型在前</b>。{@link CampaignPort#pick} 同额取先出现的，
     * 于是没人选的时候仍是「新模型要严格更优才替换老模型」—— 与改造前逐商家比较的结果一致。
     * 按商家而不是按总额比：两个商家各有一边更优时，按总额比会让顾客少减。
     */
    @Override
    public List<AppliedActivity> candidates(List<MerchantAmount> groups) {
        return candidatesOf(SecurityUtils.currentUserNoOrNull(), groups);
    }

    @Override
    public List<AppliedActivity> candidatesFor(String buyerNo, List<MerchantAmount> groups) {
        // Preserve promised effects long enough for the owner to reject unsupported rewards.
        // The ordinary retail API above retains its original capability filtering.
        var complete =
                groups.stream()
                        .map(
                                g ->
                                        new MerchantAmount(
                                                g.merchantNo(),
                                                g.goodsAmount(),
                                                g.goodsQty(),
                                                g.storeNo(),
                                                g.lines(),
                                                true))
                        .toList();
        return candidatesOf(buyerNo, complete);
    }

    private List<AppliedActivity> candidatesOf(String buyerNo, List<MerchantAmount> groups) {
        List<AppliedActivity> all = new ArrayList<>(legacy.candidatesFor(buyerNo, groups));
        all.addAll(promo.candidates(buyerNo, groups));
        return all;
    }

    /** 商品页的活动标签：两套合在一起（批 3） */
    @Override
    public List<ActivityTag> activityTags(String merchantNo) {
        List<ActivityTag> all = new ArrayList<>(legacy.activityTags(merchantNo));
        all.addAll(promo.activityTags(merchantNo));
        return all;
    }

    @Override
    public Discount autoDiscount(List<MerchantAmount> groups, Map<String, String> choices) {
        return CampaignPort.pick(candidates(groups), choices == null ? Map.of() : choices);
    }

    @Override
    public void requireOrdinarySelection(String buyerNo, Discount selected) {
        var old = new ArrayList<AppliedActivity>();
        var current = new ArrayList<AppliedActivity>();
        for (var row : selected.applied()) {
            if (promo.ownsActivity(row.activityNo())) {
                current.add(row);
            } else {
                old.add(row);
            }
        }
        legacy.requireOrdinarySelection(
                buyerNo, new Discount(selected.total(), selected.shares(), old));
        promo.requireOrdinarySelection(
                buyerNo, new Discount(selected.total(), selected.shares(), current));
    }

    @Override
    public void commit(String userNo, String orderNo, Discount discount) {
        legacy.commit(userNo, orderNo, discount);
        /*
         * 只把新模型的活动交给新模型记配额。applied 里现在也有老模型赢下的那家
         * （为了明细有名字）—— 交过去它会找不到这个活动，记一条「限量已满仍命中」的假告警。
         */
        List<AppliedActivity> mine = discount.applied().stream()
                .filter(a -> promo.ownsActivity(a.activityNo())).toList();
        promo.commit(userNo, orderNo, new Discount(discount.total(), discount.shares(), mine));
    }

    /**
     * 特价 / 买赠的份数只有新模型有。<b>只扣真的胜出了的那个</b>：老模型的特价更低（或送得更多）时，
     * 这件货成交用的是老模型的活动，新模型那个活动的份数不该被扣。一单一个活动扣一份，按活动号排序加锁。
     */
    @Override
    public void commitItemBenefits(String userNo, String orderNo, List<ItemBenefitUse> uses) {
        if (uses == null || uses.isEmpty()) {
            return;
        }
        List<String> flashGoods = uses.stream().filter(u -> !u.gift()).map(ItemBenefitUse::goodsNo).distinct().toList();
        List<String> giftGoods = uses.stream().filter(ItemBenefitUse::gift).map(ItemBenefitUse::goodsNo).distinct().toList();
        Map<String, Long> legacyPrice = legacy.flashPrices(flashGoods);
        Map<String, Long> promoPrice = promo.flashPrices(userNo, flashGoods);
        Map<String, String> flashActs = promo.flashActivityOf(userNo, flashGoods);
        Map<String, GiftRule> legacyGift = legacy.giftRules(giftGoods);
        Map<String, GiftRule> promoGift = promo.giftRules(userNo, giftGoods);
        Map<String, String> giftActs = promo.giftActivityOf(userNo, giftGoods);
        java.util.TreeMap<String, String> merchantOf = new java.util.TreeMap<>();
        for (ItemBenefitUse u : uses) {
            String act;
            if (u.gift()) {
                GiftRule old = legacyGift.get(u.goodsNo());
                GiftRule mine = promoGift.get(u.goodsNo());
                act = mine != null && (old == null || mine.giftM() > old.giftM()) ? giftActs.get(u.goodsNo()) : null;
            } else {
                Long old = legacyPrice.get(u.goodsNo());
                Long mine = promoPrice.get(u.goodsNo());
                act = mine != null && (old == null || mine <= old) ? flashActs.get(u.goodsNo()) : null;
            }
            if (act != null) {
                merchantOf.putIfAbsent(act, u.merchantNo());
            }
        }
        merchantOf.forEach((act, merchant) -> promo.commitItemUsage(userNo, orderNo, act, merchant, 1));
    }

    @Override
    public long bonusPoints(String orderNo, String merchantNo) {
        return promo.bonusPoints(orderNo, merchantNo);
    }

    @Override
    public Map<String, Long> flashPrices(Collection<String> goodsNos) {
        Map<String, Long> out = new HashMap<>(legacy.flashPrices(goodsNos));
        promo.flashPrices(SecurityUtils.currentUserNoOrNull(), goodsNos)
                .forEach((k, v) -> out.merge(k, v, Math::min));
        return out;
    }

    @Override
    public Map<String, Long> flashPricesFor(String buyerNo, Collection<String> goodsNos) {
        return promo.ordinaryFlashPrices(buyerNo, goodsNos, legacy.flashPrices(goodsNos));
    }

    @Override
    public Map<String, GiftRule> giftRules(Collection<String> goodsNos) {
        return giftRulesFor(SecurityUtils.currentUserNoOrNull(), goodsNos);
    }

    @Override
    public Map<String, GiftRule> giftRulesFor(String buyerNo, Collection<String> goodsNos) {
        Map<String, GiftRule> out = new HashMap<>(legacy.giftRules(goodsNos));
        promo.giftRules(buyerNo, goodsNos)
                .forEach((k, v) -> out.merge(k, v, (x, y) -> y.giftM() > x.giftM() ? y : x));
        return out;
    }
}
