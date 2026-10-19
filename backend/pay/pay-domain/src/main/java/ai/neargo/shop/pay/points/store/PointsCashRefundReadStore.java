package ai.neargo.shop.pay.points.store;

/** Trusted internal read of the existing payment ledger, never a client-supplied success flag. */
public interface PointsCashRefundReadStore {
    boolean hasRefundForAfterSale(String afterSaleNo);
    CashRefund refund(String paymentNo);
    CashRefund lockRefund(String paymentNo);
    record CashRefund(String paymentNo,String direction,String afterSaleNo,String subOrderNo,String userNo,
                      String currency,long amountMinor,String status,String channel,String providerNo,Long succeededAt,Long verifiedMinor,Long verifiedAt, ai.neargo.shop.spi.settle.RefundSafety safety) {
        public CashRefund(String paymentNo,String direction,String afterSaleNo,String subOrderNo,String userNo,
                String currency,long amountMinor,String status,String channel,String providerNo,Long succeededAt,Long verifiedMinor,Long verifiedAt) {
            this(paymentNo,direction,afterSaleNo,subOrderNo,userNo,currency,amountMinor,status,channel,providerNo,succeededAt,verifiedMinor,verifiedAt,ai.neargo.shop.spi.settle.RefundSafety.unknown());
        }
    }
}
