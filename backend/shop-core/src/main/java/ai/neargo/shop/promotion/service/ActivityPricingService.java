package ai.neargo.shop.promotion.service;

import ai.neargo.shop.spi.marketing.CampaignPort;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 新模型活动在下单时的算价与扣量（P5）。
 *
 * <p><b>不直接实现 {@link CampaignPort}</b>，理由与券那边一样：老实现还在，
 * 两个 Bean 同时实现同一个 Port 会让注入点看运气。谁来接由 app 层的路由决定。
 *
 * <p><b>但与券的路由方式不同</b>：券按「这张券在哪张表」分流，活动不行 ——
 * 活动是按商家查出来的，新旧两套表里可能各有一个满减，各减一次就是**减两次**。
 * 所以路由那边按<b>同类取最优</b>合并（既有口径），而不是相加。
 */
public interface ActivityPricingService {

    /**
     * 按商家算自动优惠。
     *
     * @param userNo 买家。<b>受众判断要用它</b>：会员专享的活动对非会员不生效。
     *               为空（游客下单不存在，但接口要能答）时按「不是会员」处理
     */
    CampaignPort.Discount autoDiscount(String userNo, List<CampaignPort.MerchantAmount> groups);

    /** 这个活动号是不是新模型的（路由把老模型的活动交给新模型记配额之前要先筛掉） */
    boolean ownsActivity(String activityNo);

    /** 每家店命中的全部活动（候选）。见实现上的注释 */
    List<ai.neargo.shop.spi.marketing.CampaignPort.AppliedActivity> candidates(
            String userNo, List<ai.neargo.shop.spi.marketing.CampaignPort.MerchantAmount> groups);

    /** 关单：把这一单占掉的配额与预算退回去（执行计划 B6）。幂等 */
    void release(String orderNo);

    /**
     * 这一单当时用上了哪些优惠（订单详情要显示依据）。
     * **读 `pmt_apply` 那几行，不按现在的规则重算** —— 规则可能早就改了。
     */
    java.util.List<CampaignPort.AppliedDiscount> appliedOf(String orderNo);

    /** 限时特价：这些商品此刻的活动价。只含命中的 */
    Map<String, Long> flashPrices(String userNo, Collection<String> goodsNos);

    /** 买赠：这些商品此刻的「买 N 送 M」。只含命中的 */
    Map<String, CampaignPort.GiftRule> giftRules(String userNo, Collection<String> goodsNos);

    /**
     * 下单之后扣限量。<b>带条件的 UPDATE</b>：
     * {@code quota_used + qty <= quota} 不成立时影响 0 行 —— 这一单没抢到最后一份。
     *
     * <p>扣不动<b>不抛异常</b>：订单已经按算价时的金额落库了，这时候翻脸会让用户
     * 付了钱却下不成单。记一条 WARN，让运营看到「超发了几份」——
     * 那是限量与并发之间必然存在的一点点重叠，而不是错误。
     */
    void commit(String userNo, String orderNo, CampaignPort.Discount discount);

    /**
     * 这一单在这家店用上的「自己组合」要额外送多少积分（原型 s11「送积分」）。
     * 读的是下单时落的优惠发生记录，已回退的不算。付款成功时随常规积分一起发（同一次发放、同一笔费用金）。
     */
    long bonusPoints(String orderNo, String merchantNo);
}
