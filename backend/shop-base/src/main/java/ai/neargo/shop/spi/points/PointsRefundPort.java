package ai.neargo.shop.spi.points;

import java.util.List;

/** Frozen business refunds prepare rights first and confirm them only from trusted pay evidence. */
public interface PointsRefundPort {
    RefundPreparation prepare(RefundCommand command);
    RefundOutcome confirm(RefundConfirmation confirmation);
    void abort(RefundAbort abort);

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
    record RefundConfirmation(String preparationNo,String inputHash,String cashRefundPaymentNo,
                              String collectionTicket,String recoveryTicket) { }
    record RefundAbort(String preparationNo,String inputHash,String cashRefundPaymentNo) { }
    record RefundOutcome(String preparationNo,String state,long returnedPoints,long reclaimedPoints,
                         long collectedMinor,long waivedPoints,long receivableMinor,String voucherNo,
                         boolean replayed) { }
}
