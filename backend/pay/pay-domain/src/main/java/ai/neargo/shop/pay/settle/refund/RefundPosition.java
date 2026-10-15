package ai.neargo.shop.pay.settle.refund;

/** 结算已应用的位置；与买家是否实际到账分属不同事实，货款与运费不能共用前值。 */
public record RefundPosition(long goodsMinor, long freightMinor) { }
