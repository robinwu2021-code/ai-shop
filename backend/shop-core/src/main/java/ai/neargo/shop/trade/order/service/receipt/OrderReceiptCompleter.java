package ai.neargo.shop.trade.order.service.receipt;

/** 扫描器只找候选；每单经独立代理事务重新验证后完成，避免扫描事务半成功。 */
public interface OrderReceiptCompleter {

    /** Count newly completed children; false may still commit due package receipts on historical multiple packages. */
    boolean completeAuto(String subOrderNo, long now, int shippedDays, int signedDays);

    /** Complete a current package on a trusted carrier signature in the caller's transaction. */
    boolean completeSigned(String subOrderNo, String shipmentNo, long signedAt, String source);
}
