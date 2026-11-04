package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

import java.time.Instant;
import java.util.*;

/** Persisted independent lifecycle axes and frozen economics; no client-side status inference. */
public record MallOrderDetailView(
        Summary summary,
        QuoteView quote,
        ShippingAddress shippingAddress,
        List<PaymentAttempt> paymentAttempts,
        List<Shipment> shipments,
        List<RefundView> refunds) {
    public record Summary(
            OrderView order, String paymentStatus, String fulfillmentStatus, String refundStatus) {}

    public record PaymentAttempt(
            String attemptNo,
            String channel,
            String payMethod,
            Money amount,
            AsyncStatus status,
            String paymentNo,
            Instant paidAt,
            String errorCode,
            Long paidAtPrecisionMs) {
        public PaymentAttempt(
                String attemptNo,
                String channel,
                String payMethod,
                Money amount,
                AsyncStatus status,
                String paymentNo,
                Instant paidAt,
                String errorCode) {
            this(
                    attemptNo,
                    channel,
                    payMethod,
                    amount,
                    status,
                    paymentNo,
                    paidAt,
                    errorCode,
                    paidAt == null ? null : 1L);
        }
    }

    public record Shipment(
            String operationNo,
            String orderComponentNo,
            String carrierCode,
            String trackingNo,
            long quantity,
            AsyncStatus status,
            String errorCode) {}
}
