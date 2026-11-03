package ai.neargo.commerce.persistence.catalog;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record SysIndustryPlanRecord(
    Long id,
    String tenantNo,
    java.time.LocalDateTime createdAt,
    java.time.LocalDateTime updatedAt,
    String createdBy,
    String updatedBy,
    Long version,
    Integer deleted,
    String planNo,
    String businessTypeCode,
    String tierCode,
    Integer rank,
    String currentVersionNo,
    String saleStatus,
    Integer sort) {}
