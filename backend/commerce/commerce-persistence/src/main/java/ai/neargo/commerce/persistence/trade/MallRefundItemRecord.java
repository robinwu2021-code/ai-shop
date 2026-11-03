package ai.neargo.commerce.persistence.trade;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MallRefundItemRecord(
    Long id,
    String tenantNo,
    java.time.LocalDateTime createdAt,
    java.time.LocalDateTime updatedAt,
    String createdBy,
    String updatedBy,
    Long version,
    Integer deleted,
    String refundItemNo,
    String refundNo,
    String orderComponentNo,
    String periodNo,
    Integer quantity,
    Long cashRefundMinor,
    Long creditRestoreMinor,
    String rightsAction,
    String status,
    String adjustmentSnapshot) {}
