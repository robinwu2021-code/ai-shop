package ai.neargo.shop.trade.order.service.lifecycle;

import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.spi.settle.SettlePort.PaymentReceipt;

/** Resolve authoritative cash facts before projecting payment into an order. */
public interface PaymentLifecycleService {
    ConfirmedPayment requireConfirmed(OrdOrder order, String channel, String tradeNo);

    /** Rechecks immutable receipt coordinates under the original order lock without a second owner RPC. */
    void verifyConfirmed(OrdOrder order, ConfirmedPayment proof);

    record ConfirmedPayment(String channel, String tradeNo, PaymentReceipt receipt) {
        public ConfirmedPayment(String channel, String tradeNo) { this(channel, tradeNo, null); }
    }
}
