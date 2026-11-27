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
        List<RefundView> refunds,
        List<Line> lines,
        List<HardwareItem> hardwareItems,
        List<DeliveredSource> deliveredSources) {
    public record DeliveredSource(
            String orderComponentNo,
            String periodNo,
            String planVersionNo,
            List<String> entitlementCodes,
            String lifecycle) {}

    public MallOrderDetailView(
            Summary summary,
            QuoteView quote,
            ShippingAddress shippingAddress,
            List<PaymentAttempt> paymentAttempts,
            List<Shipment> shipments,
            List<RefundView> refunds,
            List<Line> lines,
            List<HardwareItem> hardwareItems) {
        this(
                summary,
                quote,
                shippingAddress,
                paymentAttempts,
                shipments,
                refunds,
                lines,
                hardwareItems,
                List.of());
    }

    public record HardwareItem(String orderComponentNo, String hardwareSkuNo, int quantity) {}

    public MallOrderDetailView(Summary summary, QuoteView quote, ShippingAddress shippingAddress,
            List<PaymentAttempt> paymentAttempts, List<Shipment> shipments, List<RefundView> refunds,
            List<Line> lines) {
        this(summary, quote, shippingAddress, paymentAttempts, shipments, refunds, lines, List.of());
    }

    public MallOrderDetailView(
            Summary summary,
            QuoteView quote,
            ShippingAddress shippingAddress,
            List<PaymentAttempt> paymentAttempts,
            List<Shipment> shipments,
            List<RefundView> refunds) {
        this(summary, quote, shippingAddress, paymentAttempts, shipments, refunds, List.of());
    }

    /**
     * {@code title}/{@code lineCount}: display name from the first frozen order line and the number
     * of lines. {@code latestClaimStatus}: the newest offline payment declaration visible to the
     * caller (SUBMITTED/REVIEWING/CONFIRMED/MISMATCHED/DECLINED), or null.
     */
    public record Summary(
            OrderView order,
            String paymentStatus,
            String fulfillmentStatus,
            String refundStatus,
            String title,
            int lineCount,
            String latestClaimStatus) {
        public Summary(
                OrderView order, String paymentStatus, String fulfillmentStatus, String refundStatus) {
            this(order, paymentStatus, fulfillmentStatus, refundStatus, null, 0, null);
        }
    }

    /** Frozen at order time from the quote's SKU snapshot; never the live catalog. */
    public record Line(
            String productNo, String skuNo, String skuVersionNo, String title, int quantity) {}

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
            String errorCode,
            String requestOperationNo) {
        public Shipment(String operationNo, String orderComponentNo, String carrierCode,
                String trackingNo, long quantity, AsyncStatus status, String errorCode) {
            this(operationNo, orderComponentNo, carrierCode, trackingNo, quantity, status, errorCode, null);
        }
    }
}
