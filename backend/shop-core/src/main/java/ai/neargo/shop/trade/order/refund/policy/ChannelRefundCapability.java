package ai.neargo.shop.trade.order.refund.policy;

/** 支付渠道的退款能力。 */
public interface ChannelRefundCapability {

    /** 能否原路部分退（多次） */
    boolean supportsPartial(String payChannel);
}
