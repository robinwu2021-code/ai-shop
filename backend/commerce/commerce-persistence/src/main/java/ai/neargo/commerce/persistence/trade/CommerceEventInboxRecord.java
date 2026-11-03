package ai.neargo.commerce.persistence.trade;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record CommerceEventInboxRecord(
    Long id,
    String tenantNo,
    java.time.LocalDateTime createdAt,
    java.time.LocalDateTime updatedAt,
    String createdBy,
    String updatedBy,
    Long version,
    Integer deleted,
    String sourceSystem,
    String eventId,
    String eventType,
    String aggregateNo,
    String payloadHash,
    String status,
    Long receivedAt,
    Long processedAt) {}
