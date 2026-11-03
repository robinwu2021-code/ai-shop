package ai.neargo.commerce.persistence.subscription;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MchSubscriptionReservationRecord(
        Long id,
        String tenantNo,
        java.time.LocalDateTime createdAt,
        java.time.LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long version,
        Integer deleted,
        String reservationNo,
        String storeNo,
        String orderNo,
        String quoteNo,
        Long subscriptionVersion,
        Long creditAccountVersion,
        Long valuationAt,
        Long payDeadline,
        String status,
        String sourcePeriodsSnapshot,
        Long reservedCreditMinor) {}
