package ai.neargo.shop.spi.marketing;

/**
 * trade → marketing：<b>参团 / 开团接到下单上</b>（TDD-营销域-详细设计 §1.4 · 开发计划 P1b）。
 *
 * <p>此前参团只插一行成员，不产生订单与付款 —— 成团价从未被收过、到期也无钱可退。
 * 现在参团 = 带团号下单，按团价收钱，<b>付款成功才算成员</b>：
 *
 * <pre>
 * 预览 / 下单 ─▶ {@link #quote}（团还能不能参、按什么价）
 * 落库前     ─▶ {@link #bind}（开团此刻才建团，参团再判一次）─▶ 子单写 group_no
 * 付款成功   ─▶ {@link #onPaid}（落成员、够人数 FORMED；团已散则告诉调用方去退款）
 * </pre>
 *
 * <p>三步分开而不是合成一个：预览会被反复调（改地址、改数量），
 * 在那儿建团的话，一个只是看看价格的人能开出一串空团。
 */
public interface GroupJoinPort {

    /**
     * 这张单按哪个团、什么价。<b>只读</b>，预览与下单共用。
     *
     * @param groupNo   参团：团号。与 {@code openGroup} 二选一
     * @param openGroup 开团：按这件货在跑的拼团活动报价，团在 {@link #bind} 时才建
     * @param goodsNo   这张单里的货。参团时必须就是团的那件货
     * @throws ai.neargo.shop.common.BizException {@code GROUP_CLOSED}：团已成 / 已散 / 已过期；
     *         {@code CONFLICT}：已经是这个团的成员；{@code ORDER_STATE_ILLEGAL}：这件货没有在跑的拼团活动
     */
    GroupQuote quote(String userNo, String groupNo, boolean openGroup, String goodsNo);

    /**
     * 下单落库之前调，与订单同一个事务：开团在此刻建团（发起人 = 下单人），参团再判一次状态。
     *
     * @param pickupNo 这张子单配到的自提点。开团时作为团的成团范围
     * @return 子单要写的团号
     */
    String bind(String userNo, GroupQuote quote, String pickupNo);

    /**
     * 付款成功：落成员行（按子单号幂等），够人数置 FORMED。
     *
     * <p>在支付事务里调，<b>不抛业务异常</b>：付款回调抛异常会让渠道重试，
     * 而重试会撞上「订单已支付」的幂等分支，这一步就永远不会再跑。
     */
    PaidOutcome onPaid(String groupNo, String subOrderNo, String userNo);

    /**
     * @param groupNo         参团时是团号；开团报价时为空（团还没建）
     * @param skuNo           团限定的规格；为空表示不限（按商品开的团）
     * @param groupPriceMinor 成团价（分），这张单按它收
     * @param pickupNo        团的成团范围（自提点）；为空表示不限，按下单配到的点
     */
    record GroupQuote(String groupNo, String activityNo, String goodsNo, String skuNo,
                      String merchantNo, long groupPriceMinor, String pickupNo) {
    }

    enum PaidOutcome {
        /** 落了成员 */
        JOINED,
        /** 这笔付款已经落过（回调重放），或这个人已经是成员（同一团买了第二单）—— 人数不变 */
        ALREADY,
        /** 团已散 / 已过期：<b>调用方要把这张子单退掉</b>，钱付进来了却没有团可参 */
        CLOSED
    }
}
