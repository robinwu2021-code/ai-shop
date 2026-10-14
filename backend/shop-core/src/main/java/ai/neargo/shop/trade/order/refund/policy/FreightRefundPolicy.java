package ai.neargo.shop.trade.order.refund.policy;

/** 运费退不退、退多少。 */
public interface FreightRefundPolicy {

    /**
     * @param freightRemainingMinor 还没退过的运费
     * @param shipped               货是否已发出（已揽收及以后）
     * @param coversAllRemaining    这次是否退完全部剩余件数
     */
    long freightFor(long freightRemainingMinor, boolean shipped, boolean coversAllRemaining, String reasonCode);
}
