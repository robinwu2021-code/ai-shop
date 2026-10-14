package ai.neargo.shop.trade.order.machine.aftersale;

/**
 * 售后单（ord_after_sale）状态，12 个（完整方案 §5.1）。
 *
 * <p>{@link #REFUNDING} 只剩一义「钱在路上」；等寄回 / 已寄回拆成 {@link #WAIT_RETURN} / {@link #RETURNING}。
 */
public enum AfterSaleStatus {
    APPLIED, NEGOTIATING, WAIT_RETURN, RETURNING, WAIT_RECEIPT, REFUNDING,
    WAIT_OFFLINE_REFUND, OFFLINE_REFUNDED, REFUNDED, REJECTED, ARBITRATING, CLOSED
}
