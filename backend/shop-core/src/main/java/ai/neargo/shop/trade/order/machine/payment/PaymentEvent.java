package ai.neargo.shop.trade.order.machine.payment;

/** 支付单事件（完整方案 §2 P1–P6）。 */
public enum PaymentEvent {
    PLACE, PAY_SUCCEEDED, OFFLINE_CONFIRMED, CANCEL, PAY_TIMEOUT, LATE_PAY_SUCCEEDED
}
