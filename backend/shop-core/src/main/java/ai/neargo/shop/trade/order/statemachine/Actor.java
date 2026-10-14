package ai.neargo.shop.trade.order.statemachine;

/**
 * 谁触发了这次迁移。状态机按它判「这个人能不能做这件事」。
 *
 * <p>{@link #CHANNEL} 与 {@link #SYSTEM} 分开：通道回调（支付成功、轨迹揽收）是外部事实到达，
 * 定时器是系统自己做决定 —— 「退款永不自动」的不变式只约束后者。
 */
public enum Actor {
    BUYER,
    MERCHANT,
    PLATFORM,
    /** 定时器与级联（本系统自己发起） */
    SYSTEM,
    /** 外部通道回调（支付 / 退款回执 / 物流轨迹） */
    CHANNEL
}
