package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.trade.order.command.OrderActor;

/** Freeze the original execution in the approval transaction, before any financial dispatch. */
public interface RefundPreparationService {
    String prepare(String afterSaleNo, OrderActor actor);
}
