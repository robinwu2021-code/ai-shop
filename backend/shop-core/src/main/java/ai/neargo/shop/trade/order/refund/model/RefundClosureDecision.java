package ai.neargo.shop.trade.order.refund.model;

import ai.neargo.shop.trade.order.machine.order.OrderStatus;

/** A null target means the existing order status must remain unchanged. */
public record RefundClosureDecision(RefundProgress cashProgress, boolean goodsClosed,
        boolean allGoodsTerminated, boolean approvedObligationsSettled, OrderStatus targetOrderStatus) { }
