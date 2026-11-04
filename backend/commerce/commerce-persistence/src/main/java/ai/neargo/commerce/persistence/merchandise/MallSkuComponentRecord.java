package ai.neargo.commerce.persistence.merchandise;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MallSkuComponentRecord(
        Long id,
        String tenantNo,
        java.time.LocalDateTime createdAt,
        java.time.LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long version,
        Integer deleted,
        String componentNo,
        String skuVersionNo,
        Integer componentSeq,
        String componentType,
        String planVersionNo,
        String entitlementCode,
        String hardwareSkuNo,
        Integer periodDays,
        Integer unitCount,
        Long allocationWeight,
        Integer isGift,
        String activationPolicy) {}
