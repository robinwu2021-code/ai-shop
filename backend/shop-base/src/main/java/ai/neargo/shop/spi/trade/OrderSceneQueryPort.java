package ai.neargo.shop.spi.trade;

/**
 * settle → trade：读订单上的<b>下单端快照</b>。
 *
 * <p><b>这个 Port 存在的全部理由是让一类错误写不出来。</b>
 * 积分发放的端判定必须读快照，而发放发生在支付/完成那一刻 ——
 * 那时用户可能已经换了端，<b>更常见的是根本没有用户在场</b>
 * （超时自动确认收货是系统动作，定时任务里没有请求头）。
 *
 * <p>把端当参数传进积分域的话，总有一天会有人在某个入口传当前请求的端进去，
 * 而症状是「同一笔订单发不发积分取决于谁在哪个端点的确认，甚至取决于
 * 是不是定时任务跑的」—— 不可复现、无法对账，也几乎不可能在评审里看出来。
 * 让积分域<b>自己去订单上取</b>，这个错就没有地方可写。
 *
 * <p>代价是一次按子单号的查询，只发生在真的要发分的时候。
 */
public interface OrderSceneQueryPort {

    /**
     * 子单所属订单的 {@code ord_order.pay_scene}。
     *
     * <p>查不到订单、或那一列为空（V1 之后到本批之前的存量单一律为空）时返回
     * {@code null} —— 调用方按「认不出端」处理，也就是<b>不拦</b>。
     */
    String paySceneOfSubOrder(String subOrderNo);

    /**
     * 子单所属订单的 {@code ord_order.pay_channel}（{@code PayModes.OFFLINE} / WECHAT / …）。
     *
     * <p>与 {@link #paySceneOfSubOrder} 同一个理由放在这里：<b>读的必须是订单上的那一份</b>。
     * 发分发生在支付成功那一刻，而「这单是怎么付的」只有订单说了算 ——
     * 从调用方传进来的话，总有一天会有人传当前请求里的值。
     *
     * <p>查不到订单时返回 {@code null}，调用方按「认不出」处理。
     */
    String payChannelOfSubOrder(String subOrderNo);
}
