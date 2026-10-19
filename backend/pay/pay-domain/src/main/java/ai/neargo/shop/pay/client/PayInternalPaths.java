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

    public static final String ENT_LIST = "/internal/pay/entitlement/management/list";
    public static final String ENT_GET = "/internal/pay/entitlement/management/get";
    public static final String ENT_CREATE = "/internal/pay/entitlement/management/create";
    public static final String ENT_DRAFT = "/internal/pay/entitlement/management/draft";
    public static final String ENT_PUBLISH = "/internal/pay/entitlement/management/publish";
    public static final String ENT_STATUS = "/internal/pay/entitlement/management/status";
    public static final String ENT_POLICY = "/internal/pay/entitlement/management/issue-policy";
    public static final String ENT_VERSION = "/internal/pay/entitlement/catalog/version";
    public static final String ENT_REFERENCE = "/internal/pay/entitlement/catalog/validate-reference";

    private PayInternalPaths() {
    }
}
