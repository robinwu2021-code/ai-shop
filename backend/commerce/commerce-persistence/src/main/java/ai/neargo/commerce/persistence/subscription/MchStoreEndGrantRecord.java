package ai.neargo.commerce.persistence.subscription;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MchStoreEndGrantRecord(
    Long id,
    String tenantNo,
    java.time.LocalDateTime createdAt,
    java.time.LocalDateTime updatedAt,
    String createdBy,
    String updatedBy,
    Long version,
    Integer deleted,
    String grantNo,
    String storeNo,
    String periodNo,
    String endCode,
    String entitlementCode,
    Long scheduleRevision,
    Integer segmentSeq,
    String scheduleEventNo,
    Long startAt,
    Long endAt,
    String state,
    Long revokedAt,
    String reason) {}
