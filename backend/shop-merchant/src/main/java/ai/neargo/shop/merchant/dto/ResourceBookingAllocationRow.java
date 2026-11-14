package ai.neargo.shop.merchant.dto;

import java.time.LocalDateTime;

/** Bounded active allocation evidence; a missing bucket stays visible for failure-closed parent validation. */
public record ResourceBookingAllocationRow(String unitNo, long version, String bucketNo, String resourceNo,
        LocalDateTime startAt, LocalDateTime endAt, int qty, String sourceLineRef, int deleted,
        String consumeOccupationUnitNo, LocalDateTime consumedAt, String consumeCommandNo) {
}
