package ai.neargo.shop.beauty.dto;

/** Safe source workflow projection; observed allocation never grants execution authority. */
public record CurrencyBillVO(
        String currency,
        Long dueMinor,
        Long receivedMinor,
        Long refundedMinor,
        Long netReceivedMinor,
        Long outstandingMinor,
        boolean complete,
        Long manualDeclaredMinor,
        String financialVerification) {
    /** Retains the previous public constructor and funded projection defaults. */
    public CurrencyBillVO(
            String currency,
            Long dueMinor,
            Long receivedMinor,
            Long refundedMinor,
            Long netReceivedMinor,
            Long outstandingMinor,
            boolean complete) {
        this(
                currency,
                dueMinor,
                receivedMinor,
                refundedMinor,
                netReceivedMinor,
                outstandingMinor,
                complete,
                null,
                "VERIFIED_PAYMENT");
    }
}
