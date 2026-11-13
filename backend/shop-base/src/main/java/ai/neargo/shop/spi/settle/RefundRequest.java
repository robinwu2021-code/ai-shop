package ai.neargo.shop.spi.settle;

/** Frozen cash coordinates and the source applicant survive retries independently of the recovery worker. */
public record RefundRequest(String executionNo, String afterSaleNo, String subOrderNo, String originPaymentNo,
                            long amountMinor, String currency, String reason, String requestedBy) {
    /** Historical trusted callers retain their explicit after-sale source marker. */
    public RefundRequest(String executionNo, String afterSaleNo, String subOrderNo, String originPaymentNo,
                         long amountMinor, String currency, String reason) {
        this(executionNo, afterSaleNo, subOrderNo, originPaymentNo, amountMinor, currency, reason, null);
    }
    /** Trusted source marker for a full return of an unapplied order payment, with no invented after-sale. */
    public boolean paymentResolution() {
        return subOrderNo == null && afterSaleNo != null && afterSaleNo.startsWith("PAYRES:");
    }
}
