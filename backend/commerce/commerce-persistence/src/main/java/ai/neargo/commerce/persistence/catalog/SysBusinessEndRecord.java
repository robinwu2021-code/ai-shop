package ai.neargo.commerce.persistence.catalog;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record SysBusinessEndRecord(
    Long id,
    String tenantNo,
    java.time.LocalDateTime createdAt,
    java.time.LocalDateTime updatedAt,
    String createdBy,
    String updatedBy,
    Long version,
    Integer deleted,
    String endCode,
    String entitlementCode,
    String name,
    String scope,
    String allowedBusinessTypes,
    String releaseStatus,
    Integer saleEnabled,
    Integer bootstrapRequired,
    Integer enabled,
    String routePolicyRef) {}
