package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;

/** Approvals freeze a plan; only confirmed cash results project actual refunded quantities. */
public interface BasicRefundExecutionService {
    String prepare(String afterSaleNo, OrderActor actor);
    void dispatch(String executionNo);
    void applyResult(RefundDispatchResult result);
    void recover(String executionNo);
    OrdRefundExecution find(String afterSaleNo);
    int recoverDue(long now, int limit);
}
