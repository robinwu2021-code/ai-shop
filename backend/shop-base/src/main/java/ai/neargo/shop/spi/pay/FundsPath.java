package ai.neargo.shop.spi.pay;

/**
 * 资金路径：钱从消费者出发后**先落到谁的账上**。
 *
 * <ul>
 *   <li>{@link #DIRECT} —— 直达商户：渠道直接结给商户自己的号（台湾蓝新、LINE Pay；阿联酋 PayBy 直收）；</li>
 *   <li>{@link #AGGREGATED} —— 平台代收：先进平台的号，再由平台结算出款（阿联酋 PayBy 代收；CN 收付通）。</li>
 * </ul>
 *
 * <p>它决定结算方式（直达只收手续费应收、代收要出款）与积分出资方式，所以支付单在建单时把它**冻结进路由快照**，
 * 之后商户改了进件结果也不影响在途单（ADR-041 §2-5）。
 *
 * <p>2026-10-25 从积分的 {@code PointsFundsGateway.FundsPath} 提到这里：支付单（I3）与积分要用同一份定义，
 * 两份同义枚举迟早有一边多出第三个值（TDD-支付服务-I0地基 AC8）。
 */
public enum FundsPath {
    DIRECT,
    AGGREGATED
}
