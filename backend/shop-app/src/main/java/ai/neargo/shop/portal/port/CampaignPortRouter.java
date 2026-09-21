package ai.neargo.shop.portal.port;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.marketing.port.CampaignPortImpl;
import ai.neargo.shop.promotion.service.ActivityPricingService;
import ai.neargo.shop.spi.marketing.CampaignPort;
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

    /**
     * 「这一单当时减了什么」只有新模型记着（`pmt_apply`）——
     * 老模型那边没有这张账，所以不必合并，直接转发。
     */
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
        List<AppliedActivity> all = new ArrayList<>(legacy.candidates(groups));
        all.addAll(promo.candidates(SecurityUtils.currentUserNoOrNull(), groups));
        return all;
    }

    @Override
    public Discount autoDiscount(List<MerchantAmount> groups, Map<String, String> choices) {
        return CampaignPort.pick(candidates(groups), choices == null ? Map.of() : choices);
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

    @Override
    public long bonusPoints(String orderNo, String merchantNo) {
        return promo.bonusPoints(orderNo, merchantNo);
    }

    @Override
    public Map<String, Long> flashPrices(Collection<String> goodsNos) {
        Map<String, Long> out = new HashMap<>(legacy.flashPrices(goodsNos));
        // 取更低的那个价 —— 与满减取最优同一侧
        promo.flashPrices(SecurityUtils.currentUserNoOrNull(), goodsNos)
                .forEach((k, v) -> out.merge(k, v, Math::min));
        return out;
    }

    @Override
    public Map<String, GiftRule> giftRules(Collection<String> goodsNos) {
        Map<String, GiftRule> out = new HashMap<>(legacy.giftRules(goodsNos));
        promo.giftRules(SecurityUtils.currentUserNoOrNull(), goodsNos)
                .forEach((k, v) -> out.merge(k, v, (x, y) -> y.giftM() > x.giftM() ? y : x));
        return out;
    }
}
