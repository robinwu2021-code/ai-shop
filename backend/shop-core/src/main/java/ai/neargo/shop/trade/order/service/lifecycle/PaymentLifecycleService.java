package ai.neargo.shop.trade.order.service.lifecycle;

import ai.neargo.shop.spi.settle.SettlePort.PaymentReceipt;
import ai.neargo.shop.trade.entity.OrdOrder;

/** Resolve authoritative cash facts before projecting payment into an order. */
public interface PaymentLifecycleService {
    /**
     * Read the immutable financial receipt before acquiring any source transaction or order lock.
     */
    PaymentReceipt readConfirmed(String orderNo, String channel, String tradeNo);

    /** Validate an already read receipt against the currently locked order without external I/O. */
    ConfirmedPayment requireConfirmed(
            OrdOrder order, String channel, String tradeNo, PaymentReceipt receipt);

    /**
     * Retain only the existing unmanaged offline confirmation path; managed orders require a real
     * receipt.
     */
    ConfirmedPayment requireConfirmed(OrdOrder order, String channel, String tradeNo);

    record ConfirmedPayment(String channel, String tradeNo) { }
}
