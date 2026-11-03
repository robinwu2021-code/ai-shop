package ai.neargo.commerce.persistence.trade;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MallRefundRecord(
        Long id,
        String tenantNo,
        java.time.LocalDateTime createdAt,
        java.time.LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long version,
        Integer deleted,
        String refundNo,
        String orderNo,
        String storeNo,
        String reason,
        String policySnapshot,
        Long requestedCashMinor,
        Long approvedCashMinor,
        Long restoreCreditMinor,
        String payRefundNo,
        String status,
        String idempotencyKey,
        String reviewedBy,
        Long reviewedAt) {}
