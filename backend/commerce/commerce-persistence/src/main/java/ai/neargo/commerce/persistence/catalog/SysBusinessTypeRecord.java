package ai.neargo.commerce.persistence.catalog;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record SysBusinessTypeRecord(
        Long id,
        String tenantNo,
        java.time.LocalDateTime createdAt,
        java.time.LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long version,
        Integer deleted,
        String businessTypeCode,
        String name,
        Integer enabled,
        Integer sort,
        Long planCatalogVersion,
        String remark) {}
