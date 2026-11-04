package ai.neargo.commerce.persistence.reliability;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MchSubscriptionMigrationMapRecord(
        Long id,
        String tenantNo,
        java.time.LocalDateTime createdAt,
        java.time.LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long version,
        Integer deleted,
        String migrationBatchNo,
        Long legacyEntityPlanId,
        String legacyContractRef,
        String storeNo,
        String newPeriodNo,
        Long allocatedBasisMinor,
        String evidenceRef,
        String legacySnapshot,
        String validationStatus,
        Long cutoverAt) {}
