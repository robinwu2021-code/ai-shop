package ai.neargo.shop.trade.order.refund.service;

/** Resolves the original durable refund decision maker, never the worker performing recovery. */
public interface RefundRequesterService {
    /** Null means historical evidence cannot establish a person; callers retain an explicit unknown source. */
    String requestedBy(String afterSaleNo);
}
