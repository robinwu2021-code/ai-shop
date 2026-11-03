package ai.neargo.commerce.persistence.subscription;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MchSubscriptionConversionItemRecord(
    Long id,
    String tenantNo,
    java.time.LocalDateTime createdAt,
    java.time.LocalDateTime updatedAt,
    String createdBy,
    String updatedBy,
    Long version,
    Integer deleted,
    String conversionNo,
    String sourcePeriodNo,
    Long basisMinor,
    Long originalDurationMs,
    Long remainingDurationMs,
    Long convertedMinor) {}
