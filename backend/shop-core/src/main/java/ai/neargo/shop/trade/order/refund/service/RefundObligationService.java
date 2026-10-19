package ai.neargo.shop.trade.order.refund.service;

/** Durable consumer obligations; stock recovery is intentionally independent. */
public interface RefundObligationService {
    boolean hasScope(String orderNo);
    void prepare(String executionNo);
    void recover(String executionNo);
    boolean settled(String executionNo);
}
