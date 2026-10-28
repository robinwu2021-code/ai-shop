package ai.neargo.shop.pay.service;

import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.spi.settle.SettlePort.PaymentReceipt;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;

/** Refund-only financial authority keeps the original receipt and instruction stable through recovery. */
public interface RefundExecutionService {
    PaymentReceipt origin(String orderNo, String tradeNo, String channel);
    boolean reverseUnsplit(SplitReversal request);
    boolean reverseUnsplit(SplitReversal request, boolean zeroLiability);
    RefundDispatchResult request(RefundRequest request);

    /** Dispatch a committed legacy cash intent through the common review gate and channel sender.
     * The original refund number and durable one-time marker remain owned by that intent. */
    void dispatchPrepared(String refundPaymentNo);
    RefundDispatchResult query(String executionNo);
    RefundDispatchResult query(RefundRequest request);

    /** Confirm only the immutable refund receipt; a legacy financial row need not have a trade execution. */
    RefundDispatchResult confirm(String outRefundNo, String payChannel, long amountMinor,
                                 String currency, String providerNo);
}
