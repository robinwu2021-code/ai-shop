package ai.neargo.shop.trade.order.refund.policy;

import ai.neargo.shop.trade.order.refund.model.RefundableLine;

/** 一行退 n 件是多少钱。 */
public interface LineAmountPolicy {

    long amountFor(RefundableLine line, int qty);
}
