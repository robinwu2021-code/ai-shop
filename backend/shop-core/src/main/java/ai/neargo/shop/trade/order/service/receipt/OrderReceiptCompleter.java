package ai.neargo.shop.trade.order.service.receipt;

/** 扫描器只找候选；每单经独立代理事务重新验证后完成，避免扫描事务半成功。 */
public interface OrderReceiptCompleter {

    /** @return 当前事实仍到期且本次完成；否则没有副作用 */
    boolean completeAuto(String subOrderNo, long now, int shippedDays, int signedDays);
}
