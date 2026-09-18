package ai.neargo.shop.spi.trade;

/**
 * marketing → trade：团到期未成、商家散团、运营中止时，<b>把参团的钱退回去</b>。
 *
 * <p>与 {@link PeriodOrderPort#refundAll} 同一条退款路径（售后的系统全额退款）——
 * 此前中止团只改状态，钱一分没退（设计 D5）。
 */
public interface GroupOrderPort {

    /**
     * 把这个团里已付款、尚未退款的子单逐张全额退掉。
     *
     * <p>逐张独立、幂等：已退或已有退款在路上的跳过，重复调用不会退两次；
     * 未支付的子单不处理，它们会被超时关单关掉。
     *
     * @return 这一次新发起退款的子单数
     */
    int refundAll(String groupNo, String reason);
}
