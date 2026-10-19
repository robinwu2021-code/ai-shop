package ai.neargo.shop.trade.order.machine.payment;

/** 支付单（ord_order）状态。名字与库里的字符串逐字一致。 */
public enum PaymentStatus {
    POINTS_PENDING, WAIT_PAY, WAIT_OFFLINE_PAY, PAID, CANCELLED, CLOSED
}
