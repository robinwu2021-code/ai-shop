package ai.neargo.shop.pay.points.store;

/** Trusted internal read of the existing payment ledger, never a client-supplied success flag. */
public interface PointsCashRefundReadStore {
    CashRefund refund(String paymentNo);
    record CashRefund(String paymentNo,String direction,String afterSaleNo,String subOrderNo,String userNo,
                      String currency,long amountMinor,String status,String channel,String providerNo,Long succeededAt,Long verifiedMinor,Long verifiedAt) { }
}
