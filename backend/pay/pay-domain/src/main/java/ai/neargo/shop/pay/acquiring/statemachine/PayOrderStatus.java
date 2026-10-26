package ai.neargo.shop.pay.acquiring.statemachine;

/** 支付单状态（{@code pay_order.status}） */
public enum PayOrderStatus {
    CREATED, AWAITING_PAYMENT, AUTHORIZED, AWAITING_CONFIRM, SUCCEEDED, PARTIALLY_REFUNDED, REFUNDED, CLOSED, FAILED;

    public boolean terminal() {
        return this == SUCCEEDED || this == PARTIALLY_REFUNDED || this == REFUNDED || this == CLOSED || this == FAILED;
    }
}
