package ai.neargo.shop.trade.order.machine.order;

/** 订单（ord_sub_order）状态。名字与库里的字符串逐字一致；对外投影见 OrderStatusProjector。 */
public enum OrderStatus {
    WAIT_PAY, WAIT_FULFILL, FULFILLING, COMPLETED, CANCELLED, REFUNDED
}
