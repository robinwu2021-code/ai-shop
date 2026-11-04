package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

import java.time.Instant;

/**
 * Produced only by a trusted adapter after verifying the original payment and final refund result.
 * FAILED with definitiveFinal requires an irrevocable provider fence against future execution of
 * the same refundNo; timeout, NOT_FOUND and an ordinary failed response are not such proof.
 */
public record VerifiedRefundFact(
        String tenantNo,
        String eventNo,
        String orderNo,
        String refundNo,
        StoreRef store,
        String paymentAttemptNo,
        String originalPaymentNo,
        String platformMerchantNo,
        Money originalPaid,
        Money refunded,
        String providerRefundNo,
        AsyncStatus status,
        boolean definitiveFinal,
        Instant observedAt,
        String verificationReference) {}
