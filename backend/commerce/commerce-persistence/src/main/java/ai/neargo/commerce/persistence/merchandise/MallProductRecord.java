package ai.neargo.commerce.persistence.merchandise;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MallProductRecord(
    Long id,
    String tenantNo,
    java.time.LocalDateTime createdAt,
    java.time.LocalDateTime updatedAt,
    String createdBy,
    String updatedBy,
    Long version,
    Integer deleted,
    String productNo,
    String categoryCode,
    String productType,
    String name,
    String description,
    String media,
    String allowedBusinessTypes,
    String saleStatus,
    Integer sort) {}
