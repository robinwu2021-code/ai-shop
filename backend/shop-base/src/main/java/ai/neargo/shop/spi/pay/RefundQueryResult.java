package ai.neargo.shop.spi.pay;

/** Refund queries retain terminal and abnormal outcomes; neither absence nor transport failure proves closure. */
public record RefundQueryResult(Status status, String rawStatus, String outRefundNo,
        long amountMinor, String providerNo, String currency, long observedAt,String originalOutTradeNo,String originalTradeNo,
        Long originalTotalMinor,Long actualRefundAt,String receiverNo) {
    public RefundQueryResult(Status status,String rawStatus,String outRefundNo,long amountMinor,String providerNo,String currency,long observedAt) {
        this(status,rawStatus,outRefundNo,amountMinor,providerNo,currency,observedAt,null,null,null,null,null);
    }
    public enum Status { SUCCEEDED, PROCESSING, TERMINAL_FAILED, MANUAL_REQUIRED, UNKNOWN }
    public static RefundQueryResult unknown(String no) {
        return new RefundQueryResult(Status.UNKNOWN, null, no, 0, null, null, System.currentTimeMillis());
    }
}
