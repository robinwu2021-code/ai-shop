package ai.neargo.shop.trade.order.service.refund;

/**
 * 退款资金执行出口（订单服务 2.0 · W6）。<b>实现委托现有引擎</b>（回退分账 → 原路退款 →
 * 整单退完退券/退积分 → 退货回补库存，线上验证过的 {@code AfterSaleServiceImpl.doRefund} 一族）。
 * 新内核不重写这坨资金逻辑，只经本 port 调用；接线阶段提供委托实现。
 */
public interface RefundExecutorPort {

    /** 对某订单的某行退 refundQty 件、金额 amountMinor。负责资金回退与行已退数量落库。 */
    void refund(String subOrderNo, String itemId, int refundQty, long amountMinor);
}
