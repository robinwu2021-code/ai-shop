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

    public static final String ENT_GRANT_PREPARE = "/internal/pay/entitlement/grant/prepare";
    public static final String ENT_GRANT_COMMIT = "/internal/pay/entitlement/grant/commit";
    public static final String ENT_GRANT_ABORT = "/internal/pay/entitlement/grant/abort";
    public static final String ENT_GRANT_QUERY = "/internal/pay/entitlement/grant/query";
    public static final String ENT_USE_QUOTE = "/internal/pay/entitlement/use/quote";
    public static final String ENT_USE_PREVIEW = "/internal/pay/entitlement/use/preview";
    public static final String ENT_USE_PREPARE = "/internal/pay/entitlement/use/prepare";
    public static final String ENT_USE_COMMIT = "/internal/pay/entitlement/use/commit";
    public static final String ENT_USE_RELEASE = "/internal/pay/entitlement/use/release";
    public static final String ENT_USE_REVERSE = "/internal/pay/entitlement/use/reverse";
    public static final String ENT_USE_REVERSE_QUERY = "/internal/pay/entitlement/use/reverse/query";
    public static final String ENT_USE_QUERY = "/internal/pay/entitlement/use/query";
    public static final String ENT_QUERY_MINE = "/internal/pay/entitlement/query/mine";
    public static final String ENT_QUERY_DETAIL = "/internal/pay/entitlement/query/detail";
    public static final String ENT_QUERY_USAGE = "/internal/pay/entitlement/query/usage";
    public static final String ENT_QUERY_ALLOWANCES = "/internal/pay/entitlement/query/allowances";
    public static final String ENT_QUERY_ACTIVATION = "/internal/pay/entitlement/query/activation";
    public static final String ENT_USE_FACTS = "/internal/pay/entitlement/use/facts";
    public static final String ENT_PAYMENT_ATTEMPTS = "/internal/pay/entitlement/payment/attempt-exists";
    public static final String ENT_BATCH_CREATE = "/internal/pay/entitlement/batch/create";
    public static final String ENT_BATCH_GET = "/internal/pay/entitlement/batch/get";
    public static final String ENT_BATCH_RESUME = "/internal/pay/entitlement/batch/resume";
    public static final String ENT_CODE_ISSUE = "/internal/pay/entitlement/code/issue";
    public static final String ENT_CODE_RESOLVE = "/internal/pay/entitlement/code/resolve";
    public static final String ENT_CODE_INVALIDATE = "/internal/pay/entitlement/code/invalidate";

    public static final String ENT_MERCHANT_USAGE = "/internal/pay/entitlement/query/merchant-usage";

    private PayInternalPaths() {
    }
    public static final String SV_FACTS = "/internal/pay/stored-value/facts";
    public static final String SV_CREATE = "/internal/pay/stored-value/create";
    public static final String SV_SUBMIT = "/internal/pay/stored-value/submit";
    public static final String SV_RECEIVE = "/internal/pay/stored-value/receive";
    public static final String SV_CANCEL = "/internal/pay/stored-value/cancel";
    public static final String SV_CREDIT = "/internal/pay/stored-value/credit";

}
