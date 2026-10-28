package ai.neargo.shop.spi.settle;

/** Frozen cash coordinates and the source applicant survive retries independently of the recovery worker. */
public record RefundRequest(String executionNo, String afterSaleNo, String subOrderNo, String originPaymentNo,
                            long amountMinor, String currency, String reason, String requestedBy) {
    /** Historical trusted callers have no person evidence; they retain the explicit after-sale source marker. */
    public RefundRequest(String executionNo, String afterSaleNo, String subOrderNo, String originPaymentNo,
                         long amountMinor, String currency, String reason) {
        this(executionNo, afterSaleNo, subOrderNo, originPaymentNo, amountMinor, currency, reason, null);
    }
}
