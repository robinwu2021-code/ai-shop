package ai.neargo.commerce.persistence.subscription;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MchSubscriptionAccountRecord(
        Long id,
        String tenantNo,
        java.time.LocalDateTime createdAt,
        java.time.LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long version,
        Integer deleted,
        String storeNo,
        String entityNo,
        String businessTypeCode,
        Long entitlementVersion,
        String pendingChangeOrderNo,
        String legacyContractRef,
        String migrationBatchNo) {}
