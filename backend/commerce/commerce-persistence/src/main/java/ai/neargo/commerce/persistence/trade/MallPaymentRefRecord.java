package ai.neargo.commerce.persistence.trade;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MallPaymentRefRecord(
        Long id,
        String tenantNo,
        java.time.LocalDateTime createdAt,
        java.time.LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long version,
        Integer deleted,
        String paymentAttemptNo,
        String orderNo,
        String payOrderNo,
        String receiverAccountNo,
        String paymentBusinessType,
        String currency,
        Long amountMinor,
        String status,
        Long providerPaidAt) {}
