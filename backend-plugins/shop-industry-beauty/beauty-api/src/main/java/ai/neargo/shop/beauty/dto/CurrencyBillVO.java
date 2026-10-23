package ai.neargo.shop.beauty.dto;

/** Safe source workflow projection; observed allocation never grants execution authority. */
public record CurrencyBillVO(
        String currency,
        Long dueMinor,
        Long receivedMinor,
        Long refundedMinor,
        Long netReceivedMinor,
        Long outstandingMinor,
        boolean complete) {}
