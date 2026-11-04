package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.Money;

import java.time.Instant;

/** Stable platform-only request. The adapter must idempotently create/fence paymentAttemptNo. */
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
        String payerReference) {
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
