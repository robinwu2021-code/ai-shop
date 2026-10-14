package ai.neargo.shop.trade.order.refund.policy;

import java.util.List;

/** 券什么时候退回。 */
public interface CouponReturnPolicy {

    boolean shouldReturn(List<String> siblingSubOrderStatuses);
}
