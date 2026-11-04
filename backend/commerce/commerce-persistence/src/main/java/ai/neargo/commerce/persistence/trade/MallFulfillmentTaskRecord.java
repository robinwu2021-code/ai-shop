package ai.neargo.commerce.persistence.trade;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MallFulfillmentTaskRecord(
        Long id,
        String tenantNo,
        java.time.LocalDateTime createdAt,
        java.time.LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long version,
        Integer deleted,
        String taskNo,
        String orderComponentNo,
        Integer unitSeq,
        String taskType,
        String status,
        Integer attemptCount,
        Long nextRetryAt,
        String resultNo,
        String lastErrorCode,
        Long leaseUntil,
        String orderNo,
        String requestSnapshot,
        String requestHash,
        String outcomeSnapshot,
        String dispatchState) {}
