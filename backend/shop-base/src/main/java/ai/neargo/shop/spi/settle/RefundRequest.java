package ai.neargo.shop.spi.settle;

/** Frozen cash coordinates let recovery reuse the same instruction instead of opening another refund. */
public record RefundRequest(String executionNo, String afterSaleNo, String subOrderNo, String originPaymentNo,
                            long amountMinor, String currency, String reason) {
    /** Trusted source marker for a full return of an unapplied order payment, with no invented after-sale. */
    public boolean paymentResolution() {
        return subOrderNo == null && afterSaleNo != null && afterSaleNo.startsWith("PAYRES:");
    }
}
