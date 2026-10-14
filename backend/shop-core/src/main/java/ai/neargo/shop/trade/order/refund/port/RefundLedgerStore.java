package ai.neargo.shop.trade.order.refund.port;

/**
 * 退款账本的存储端口。<b>全部写入都是带上限的条件增量</b>（集群规则 R2）：并发两笔退款同时记账时，
 * 超出上限的那笔写 0 行、返回 false —— 调用方报冲突回滚，绝不读改写。
 */
public interface RefundLedgerStore {

    /** 行：已退件数 + qty、已退额 + minor，条件「已退件数 + qty ≤ 购买件数」 */
    boolean addItemRefund(long itemId, int qty, long minor);

    /** 子单：已退总额 + minor，条件「已退总额 + minor ≤ 实付」 */
    boolean addSubRefund(String subOrderNo, long minor);

    /** 子单当前的 已退总额 与 实付 */
    long[] subRefundedAndPay(String subOrderNo);

    void setProgress(String subOrderNo, String progress);
}
