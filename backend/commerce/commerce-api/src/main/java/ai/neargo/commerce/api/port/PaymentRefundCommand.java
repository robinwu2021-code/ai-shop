package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

public record PaymentRefundCommand(
        String tenantNo,
        String orderNo,
        String refundNo,
        Money amount,
        String reason,
        StoreRef store,
        String paymentAttemptNo,
        String originalPaymentNo,
        String platformMerchantNo,
        Money originalPaid) {
    public PaymentRefundCommand(
            String tenantNo, String orderNo, String refundNo, Money amount, String reason) {
        this(tenantNo, orderNo, refundNo, amount, reason, null, null, null, null, null);
    }
}
