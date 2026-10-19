package ai.neargo.shop.spi.pay;

/** Refund queries retain terminal and abnormal outcomes; neither absence nor transport failure proves closure. */
public record RefundQueryResult(Status status, String rawStatus, String outRefundNo,
        long amountMinor, String providerNo, String currency, long observedAt) {
    public enum Status { SUCCEEDED, PROCESSING, TERMINAL_FAILED, MANUAL_REQUIRED, UNKNOWN }
    public static RefundQueryResult unknown(String no) {
        return new RefundQueryResult(Status.UNKNOWN, null, no, 0, null, null, System.currentTimeMillis());
    }
}
