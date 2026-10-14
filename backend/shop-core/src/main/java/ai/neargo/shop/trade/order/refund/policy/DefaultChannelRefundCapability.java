package ai.neargo.shop.trade.order.refund.policy;

import java.util.Set;

/**
 * 线下当面付：钱在商家手里，平台不能原路退（走线下退款登记）；0 元单：没有现金可退。
 * 其余线上渠道（微信、支付宝）可多次部分退 —— 次数与期限上限由通道自身拒绝时兜底。
 */
public class DefaultChannelRefundCapability implements ChannelRefundCapability {

    private static final Set<String> NO_PARTIAL = Set.of("OFFLINE", "FREE");

    @Override
    public boolean supportsPartial(String payChannel) {
        return payChannel != null && !NO_PARTIAL.contains(payChannel);
    }
}
