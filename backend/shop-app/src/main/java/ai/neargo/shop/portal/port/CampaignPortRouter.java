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

    @Override
    public Discount autoDiscount(List<MerchantAmount> groups) {
        Discount a = legacy.autoDiscount(groups);
        Discount b = promo.autoDiscount(SecurityUtils.currentUserNoOrNull(), groups);
        if (b.total() == 0) {
            return a;
        }
        if (a.total() == 0) {
            return b;
        }
        /*
         * 逐个商家取更优的那一边（不是把两边相加）。
         * 按商家而不是按总额比：两个商家各有一边更优时，相加会让顾客少减。
         */
        Map<String, Long> best = new HashMap<>();
        Map<String, AppliedActivity> from = new HashMap<>();
        for (MerchantDiscount d : a.shares()) {
            best.put(d.merchantNo(), d.amount());
        }
        for (MerchantDiscount d : b.shares()) {
            if (d.amount() > best.getOrDefault(d.merchantNo(), 0L)) {
                best.put(d.merchantNo(), d.amount());
                b.applied().stream()
                        .filter(x -> x.merchantNo().equals(d.merchantNo()))
                        .findFirst().ifPresent(x -> from.put(d.merchantNo(), x));
            } else {
                // 老模型赢了这一家：新模型的那个活动这一单没用上，不能扣它的量
                from.remove(d.merchantNo());
            }
        }
        List<MerchantDiscount> shares = new ArrayList<>();
        long total = 0L;
        for (var e : best.entrySet()) {
            shares.add(new MerchantDiscount(e.getKey(), e.getValue()));
            total += e.getValue();
        }
        return new Discount(total, shares, List.copyOf(from.values()));
    }

    @Override
    public void commit(String orderNo, Discount discount) {
        // 老实现是空的；新实现按 applied 扣限量。applied 里只有真正用上的那些
        legacy.commit(orderNo, discount);
        promo.commit(orderNo, discount);
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
