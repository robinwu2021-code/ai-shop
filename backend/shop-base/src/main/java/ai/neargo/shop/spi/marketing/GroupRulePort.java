package ai.neargo.shop.spi.marketing;

import java.util.Optional;

/**
 * group → promotion：<b>这件货此刻的团购规则</b>（几人成团、成团价）。
 *
 * <h2>为什么有这个 Port</h2>
 * 团购的价格与人数此前长在 {@code prd_goods} 上（{@code group_price_minor} /
 * {@code group_min_count}），于是<b>一件货一辈子只有一个团购价</b>。
 * 挪进活动之后它才可能在不同时间参加不同的团 ——
 * 见 {@code TDD-团购从商品挪进活动}。
 *
 * <h2>为什么不要 activityNo 参数</h2>
 * 因为「一件货同时只能在一个团购活动里」是<b>服务端硬校验</b>
 *（{@code ActivityServiceImpl.assertNoGroupOverlap}），所以从货就能唯一反查出活动。
 * 让调用方先挑活动是把一个已经确定的东西又问一遍，而且**多一处可以挑错的地方**：
 * 挑了 A 活动却开了 B 活动的货，两边都说得通，账上是一个错价。
 *
 * <h2>为什么不复用 {@link CampaignPort}</h2>
 * 那一条是下单算价（「这一单减多少」）。这一条是<b>开团前问规则</b>，
 * 发生在没有订单的时候。塞进同一个接口会让「团开不出来」与「下单没减钱」
 * 共用一条排查路径 —— 与那个 Port 自己不复用 {@code CouponPort} 是同一条理由。
 */
public interface GroupRulePort {

    /**
     * @return 这件货此刻在跑的团购规则；没有在跑的团购活动时为空。
     *         <b>「此刻」是排期判断</b>（活动可能存在但还没开始、或已经结束）——
     *         判据只有 {@code PmtActivity.isActiveAt} 一处，不在这里另写一份
     */
    Optional<GroupRule> activeRuleFor(String entityNo, String goodsNo);

    /**
     * @param minCount        几人成团。<b>下限 2</b> 由建活动那一步保证
     * @param groupPriceMinor 成团价（分）
     * @param groupHours      开团后多少小时内成团。活动没配时由实现给缺省（24）
     */
    record GroupRule(String activityNo, int minCount, long groupPriceMinor, int groupHours) {
    }
}
