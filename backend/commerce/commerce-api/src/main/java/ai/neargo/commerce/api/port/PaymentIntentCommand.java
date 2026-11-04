package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.Money;

import java.time.Instant;

/**
 * Stable platform-only request. The adapter must idempotently create/fence paymentAttemptNo.
 * preparedAt is trusted coordinator business time, frozen before dispatch. A zero intent
 * establishes local settlement at this time; null from legacy constructors provides no
 * zero-settlement evidence.
 */
public record PaymentIntentCommand(
        String tenantNo,
        String orderNo,
        String platformMerchantNo,
        Money amount,
        Instant expiresAt,
        String paymentAttemptNo,
        String buyerNo,
        String channel,
        String payMethod,
        String payerReference,
        Instant preparedAt) {
    public PaymentIntentCommand(
            String tenantNo,
            String orderNo,
            String platformMerchantNo,
            Money amount,
            Instant expiresAt,
            String paymentAttemptNo,
            String buyerNo,
            String channel,
            String payMethod,
            String payerReference) {
        this(
                tenantNo,
                orderNo,
                platformMerchantNo,
                amount,
                expiresAt,
                paymentAttemptNo,
                buyerNo,
                channel,
                payMethod,
                payerReference,
                null);
    }

    public PaymentIntentCommand(
            String tenantNo,
            String orderNo,
            String platformMerchantNo,
            Money amount,
            Instant expiresAt) {
        this(
                tenantNo,
                orderNo,
                platformMerchantNo,
                amount,
                expiresAt,
                orderNo,
                null,
                null,
                null,
                null);
    }
}
