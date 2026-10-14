package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.trade.order.refund.model.LedgerResult;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import java.util.List;

/** Source reservations survive retries; confirmed refunded counters change only after trusted cash evidence. */
public interface RefundExecutionService {
    /** Current durable source plan; null only when this after-sale has not been prepared. */
    Execution inspect(String afterSaleNo);
    /** Bounded durable work queue, including fresh approvals and retryable in-flight work. */
    List<String> pendingNos(int limit);
    Preparation prepare(String afterSaleNo,RefundPlan plan);
    /** Freeze the decision before a pay-domain cash intent can exist. Points plans require their own proof. */
    CashIntent beginCash(String afterSaleNo);
    void bindCash(String afterSaleNo,String paymentNo,long netCashMinor);
    void confirmCash(String afterSaleNo);
    LedgerResult commit(String afterSaleNo);
    void release(String afterSaleNo);
    record Execution(Preparation preparation,RefundPlan plan,String cashPaymentNo) { }
    record CashIntent(String afterSaleNo,String subOrderNo,long netCashMinor) { }
    record Preparation(String afterSaleNo,String subOrderNo,String inputHash,String state,long businessMinor) { }
}
