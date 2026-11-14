package ai.neargo.shop.trade.order.refund.policy;

import ai.neargo.shop.trade.order.refund.model.RefundableLine;
import ai.neargo.shop.trade.order.refund.service.BasicRefundPolicy;

/** 按件比例向下取整；退到本行最后一件时取本行剩余可退额（吃掉尾差，Σ 恰等于行实付）。 */
public class DefaultLineAmountPolicy implements LineAmountPolicy {

    @Override
    public long amountFor(RefundableLine line, int qty) {
        if (qty <= 0 || line.orderedQty() <= 0) {
            return 0L;
        }
        if (qty >= line.remainingQty()) {
            return line.remainingMinor();
        }
        return BasicRefundPolicy.prefix(line.paidShareMinor(),
                line.refundedQty() + qty, line.orderedQty())
                - BasicRefundPolicy.prefix(line.paidShareMinor(),
                        line.refundedQty(), line.orderedQty());
    }
}
