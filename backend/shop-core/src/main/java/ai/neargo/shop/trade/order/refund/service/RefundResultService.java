package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.spi.settle.RefundDispatchResult;

/** One local transaction applies a confirmed cash result to its original frozen plan. */
public interface RefundResultService {
    void apply(RefundDispatchResult result);
}
