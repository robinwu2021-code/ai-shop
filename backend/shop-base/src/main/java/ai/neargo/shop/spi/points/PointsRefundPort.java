package ai.neargo.shop.spi.points;

import java.util.List;
import java.time.Instant;

/** Frozen business refunds prepare rights first and confirm them only from trusted pay evidence. */
public interface PointsRefundPort {
    RefundPreparation prepare(RefundCommand command);
    CashDecision authorizeCash(String preparationNo,String inputHash,long revision);
    RefundOutcome confirm(RefundConfirmation confirmation);
    void requestCompensation(RefundAbort abort,long revision);
    void abort(RefundAbort abort);
    /** Internal recovery proof; only an aborted preparation with a successful collection reversal is settled. */
    RefundRecoveryStatus recoveryStatus(String preparationNo, String inputHash);

    record RefundRecoveryStatus(String preparationNo, String afterSaleNo, String subOrderNo,
                                String state, long netCashMinor, String cashRefundPaymentNo,
                                boolean compensationSucceeded) { }

    record RefundLine(String itemId,long cumulativeGoodsMinor) { }
    record PaymentSnapshot(String fundsPath,String channel,String merchantNo,String storeNo,String accountVersion,
                           String subMerchantId,String tradeNo,String outTradeNo,long orderTotalMinor) { }
    record RefundCommand(String afterSaleNo,String subOrderNo,String userNo,String typeCode,String currency,
                         long sourceVersion,long businessCashMinor,long cumulativeGoodsMinor,
                         List<RefundLine> lines,PaymentSnapshot payment) {
        public RefundCommand { lines=List.copyOf(lines); }
    }
    record RefundPreparation(String preparationNo,String typeCode,long revision,String inputHash,
                             long returnedPoints,long reclaimedPending,long reclaimedAvailable,
                             long expectedClawbackPoints,long cashOffsetMinor,long netCashMinor,
                             long waivedPoints,String collectionTicket,String state) { }
    record CashDecision(String preparationNo,String inputHash,long revision,String afterSaleNo,
                        String subOrderNo,String userNo,String currency,String channel,
                        long netCashMinor,long cashOffsetMinor,String collectionTicket,
                        PaymentSnapshot payment) { }
    record RefundConfirmation(String preparationNo,String inputHash,String cashRefundPaymentNo,
                              String collectionTicket,String recoveryTicket,String zeroCashCompletionEventNo) {
        public RefundConfirmation(String preparationNo,String inputHash,String cashRefundPaymentNo,
                                  String collectionTicket,String recoveryTicket) {
            this(preparationNo,inputHash,cashRefundPaymentNo,collectionTicket,recoveryTicket,null);
        }
    }
    enum ZeroCashReason { NO_CASH_DUE, COLLECTED_OFFSET }
    /** Trusted source cash-leg completion, not the terminal state of the entire after-sale.
     * sourceVersion refers to the original RefundCommand snapshot version. */
    record ZeroCashCompletionFact(String eventNo,String preparationNo,String inputHash,long revision,
                                 String typeCode,String afterSaleNo,String subOrderNo,String userNo,String storeNo,
                                 String currency,long sourceVersion,long businessCashMinor,long cashOffsetMinor,
                                 long netCashMinor,ZeroCashReason reason,Instant occurredAt) { }
    record RefundAbort(String preparationNo,String inputHash,String cashRefundPaymentNo) { }
    record RefundOutcome(String preparationNo,String state,long returnedPoints,long reclaimedPoints,
                         long collectedMinor,long waivedPoints,long receivableMinor,String voucherNo,
                         boolean replayed) { }
}
