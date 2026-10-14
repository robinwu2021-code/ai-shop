package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.trade.order.refund.model.LedgerResult;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;

/** Source reservations survive retries; confirmed refunded counters change only after trusted cash evidence. */
public interface RefundExecutionService {
    Preparation prepare(String afterSaleNo,RefundPlan plan);
    void bindCash(String afterSaleNo,String paymentNo,long netCashMinor);
    void confirmCash(String afterSaleNo);
    LedgerResult commit(String afterSaleNo);
    void release(String afterSaleNo);
    record Preparation(String afterSaleNo,String subOrderNo,String inputHash,String state,long businessMinor) { }
}
