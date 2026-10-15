package ai.neargo.shop.trade.order.refund.service;

/** Resource failures are recoverable after cash success and never cause another cash send. */
public interface RefundResourceService {
    void recover(String executionNo);
}
