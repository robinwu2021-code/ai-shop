package ai.neargo.shop.trade.order.service;

import ai.neargo.shop.trade.order.command.OrderActor;

/** Buyer receipt commands serialize packages with child completion and local after-sale notifications. */
public interface OrderReceiptService {
    /** Receive one owned package; historical multiple packages complete only after all are received. */
    void confirmReceipt(String subOrderNo, String shipmentNo, OrderActor actor);

    /** Preserve the existing whole-order button by receiving every current live package atomically. */
    void confirmCurrent(String subOrderNo, OrderActor actor);
}
