package ai.neargo.commerce.persistence.subscription;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MchSubscriptionCreditLotRecord(
        Long id,
        String tenantNo,
        java.time.LocalDateTime createdAt,
        java.time.LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long version,
        Integer deleted,
        String creditLotNo,
        String storeNo,
        String currency,
        String sourceType,
        String sourceRefNo,
        Long issuedMinor,
        Long remainingMinor,
        Long reservedMinor,
        String parentProvenance,
        Long expiresAt) {}
