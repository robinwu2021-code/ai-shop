package ai.neargo.commerce.persistence.reliability;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record CommerceOutboxRecord(
    Long id,
    String tenantNo,
    java.time.LocalDateTime createdAt,
    java.time.LocalDateTime updatedAt,
    String createdBy,
    String updatedBy,
    Long version,
    Integer deleted,
    String eventNo,
    String aggregateType,
    String aggregateNo,
    Long aggregateVersion,
    String eventType,
    String payload,
    String status,
    Integer retryCount,
    Long nextRetryAt,
    Integer schemaVersion,
    Long occurredAt,
    Long publishedAt) {}
