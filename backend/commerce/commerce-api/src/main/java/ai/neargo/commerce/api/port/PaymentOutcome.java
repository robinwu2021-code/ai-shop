package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

import java.time.Instant;
import java.util.*;

/**
 * SUCCEEDED proves paid, never prepay issuance. Closure requires a durable provider fence
 * reference.
 */
public record PaymentOutcome(
        String orderNo,
        Optional<String> paymentNo,
        String platformMerchantNo,
        Money amount,
        AsyncStatus status,
        Optional<Instant> paidAt,
        Optional<String> errorCode,
        Map<String, String> checkoutParameters,
        boolean definitivelyClosed,
        Optional<String> closureReference,
        Long paidAtPrecisionMs) {
    public PaymentOutcome {
        checkoutParameters = Map.copyOf(checkoutParameters);
        paidAtPrecisionMs = PaymentTimePrecision.normalize(paidAtPrecisionMs);
        if (paidAt != null && paidAt.isPresent())
            PaymentTimePrecision.latestMillis(paidAt.get(), paidAtPrecisionMs);
    }

    public PaymentOutcome(
            String orderNo,
            Optional<String> paymentNo,
            String platformMerchantNo,
            Money amount,
            AsyncStatus status,
            Optional<Instant> paidAt,
            Optional<String> errorCode,
            Map<String, String> checkoutParameters,
            boolean definitivelyClosed,
            Optional<String> closureReference) {
        this(
                orderNo,
                paymentNo,
                platformMerchantNo,
                amount,
                status,
                paidAt,
                errorCode,
                checkoutParameters,
                definitivelyClosed,
                closureReference,
                1L);
    }

    public PaymentOutcome(
            String orderNo,
            Optional<String> paymentNo,
            String platformMerchantNo,
            Money amount,
            AsyncStatus status,
            Optional<Instant> paidAt,
            Optional<String> errorCode) {
        this(
                orderNo,
                paymentNo,
                platformMerchantNo,
                amount,
                status,
                paidAt,
                errorCode,
                Map.of(),
                false,
                Optional.empty());
    }
}
