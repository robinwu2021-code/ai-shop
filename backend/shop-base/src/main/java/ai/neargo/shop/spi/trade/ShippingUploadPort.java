package ai.neargo.shop.spi.trade;

/**
 * 「这笔单该向微信上报发货了」—— 由**订单状态迁移**调用。
 *
 * <h2>为什么要这个 Port，而不是让状态机直接调上报服务</h2>
 * 上报服务在 {@code shop-app} 的 paybridge 里（它要同时够着订单与支付单），
 * 而状态迁移在 {@code shop-core} 的交易域里。依赖方向是 app → core，
 * 反过来调不通。与 {@link ai.neargo.shop.spi.settle.SettlePort} 同一手法。
 *
 * <h2>入参是我们的履约方式，不是微信的 logistics_type</h2>
 * 交易域<b>不该认识微信的四个类型码</b>。把 {@code 1/2/3/4} 传进 core，
 * 等于让「六种履约方式 → 微信四类」这个映射长出第二处调用点 ——
 * 而 {@link ai.neargo.shop.common.WxLogisticsTypes} 的类注释里写得很清楚：
 * 映射散成两处的表现不是报错，是<b>报上去了但语义是错的</b>，微信不会拒。
 *
 * <h2>这个调用不许失败</h2>
 * 它挂在用户/商家的动作上（发货、到货、支付成功）。上报本身是跨网络的副作用，
 * 一次抖动不该让商家那次「发货」点不动 —— 所以实现只落库、不发请求，
 * 真正的上报交给补报任务。实现里任何异常都要自己吞掉并记 ERROR，
 * <b>不能把异常抛回状态迁移的事务里</b>。
 */
public interface ShippingUploadPort {

    /**
     * 记一笔待上报。<b>幂等</b>：同一订单重复调不产生第二行。
     *
     * @param orderNo     主订单号。微信那笔单是按支付单号定位的，而支付单挂在主订单上
     * @param subOrderNo  触发这次上报的子单号，<b>只用于日志</b> ——
     *                    一笔多商家的单里哪一张子单先动的，排查时要看得见
     * @param fulfillment {@link ai.neargo.shop.common.Fulfillments} 的六种之一。
     *                    认不出来的实现必须**不上报并告警**，绝不兜默认值
     */
    void enqueue(String orderNo, String subOrderNo, String fulfillment);
}
