package ai.neargo.shop.trade.order.service.lifecycle;

import ai.neargo.shop.trade.entity.OrdOrder;

/** Resolve authoritative cash facts before projecting payment into an order. */
public interface PaymentLifecycleService {
    ConfirmedPayment requireConfirmed(OrdOrder order, String channel, String tradeNo);

    record ConfirmedPayment(String channel, String tradeNo) { }
}
