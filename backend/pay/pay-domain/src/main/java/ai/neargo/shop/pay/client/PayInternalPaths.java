package ai.neargo.shop.pay.client;

/**
 * 支付域独立进程（pay-svc）的内部端点路径。
 *
 * <p><b>服务端 {@code InternalPayEndpoint} 的 mapping 与客户端 {@link PayInternalApi} 引用同一份常量</b>，
 * 路径漂了就编译不过。此前两边各写一份字面量，一边改了，另一边只会在运行时收到 404 ——
 * 而测试默认装配的是进程内实现，那个 404 在本地根本碰不到。
 */
public final class PayInternalPaths {

    public static final String FEE_RULES = "/internal/pay/fee-rules";
    public static final String FEE_RULES_EFFECTIVE = "/internal/pay/fee-rules/effective";
    public static final String SETTLE_INVOICES = "/internal/pay/settle-invoices";
    /** 路径变量名 {@code invoiceNo} 两边都按这个名字取 */
    public static final String SETTLE_INVOICE_ISSUE = "/internal/pay/settle-invoices/{invoiceNo}/issue";
    public static final String SETTLE_INVOICE_REJECT = "/internal/pay/settle-invoices/{invoiceNo}/reject";

    private PayInternalPaths() {
    }
}
