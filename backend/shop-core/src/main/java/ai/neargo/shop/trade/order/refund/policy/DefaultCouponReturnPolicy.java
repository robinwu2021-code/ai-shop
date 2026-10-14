package ai.neargo.shop.trade.order.refund.policy;

import java.util.List;
import java.util.Set;

/** 支付单下全部子单都已退款或已取消才退券（部分退不退，与行业一致）。 */
public class DefaultCouponReturnPolicy implements CouponReturnPolicy {

    private static final Set<String> CLOSED = Set.of("REFUNDED", "CANCELLED");

    @Override
    public boolean shouldReturn(List<String> siblingSubOrderStatuses) {
        return !siblingSubOrderStatuses.isEmpty() && CLOSED.containsAll(siblingSubOrderStatuses);
    }
}
