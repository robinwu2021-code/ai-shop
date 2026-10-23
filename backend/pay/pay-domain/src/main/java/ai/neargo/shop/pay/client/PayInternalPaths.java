package ai.neargo.shop.pay.client;

/**
 * 支付域独立进程（pay-svc）的内部端点路径。
 *
 * <p><b>服务端 {@code InternalPayEndpoint} 的 mapping 与客户端 {@link PayInternalApi} 引用同一份常量</b>，
 * 路径漂了就编译不过。此前两边各写一份字面量，一边改了，另一边只会在运行时收到 404 ——
 * 而测试默认装配的是进程内实现，那个 404 在本地根本碰不到。
 */
public final class PayInternalPaths {
    public static final String SV_MIGRATION_PROBE="/internal/pay/stored-value/migrations/probe";
    public static final String SV_MIGRATION_PREPARE="/internal/pay/stored-value/migrations/prepare";
    public static final String SV_ISSUER_PREPARE_STORE="/internal/pay/stored-value/issuers/prepare-store";
    public static final String SV_ISSUER_FIND="/internal/pay/stored-value/issuers/find";
    public static final String SV_ISSUER_CONFIGURE="/internal/pay/stored-value/issuers/configure";
    public static final String SV_ORGANIZATION_IMPACT="/internal/pay/stored-value/organization-impact";
    public static final String LEGACY_TEMPLATE_ARCHIVE="/internal/pay/entitlement/legacy-template/archive";
    public static final String LEGACY_ISSUE_CREATE="/internal/pay/entitlement/legacy-issue/create";
    public static final String LEGACY_ISSUE_GET="/internal/pay/entitlement/legacy-issue/get";
    public static final String LEGACY_ISSUE_RESUME="/internal/pay/entitlement/legacy-issue/resume";
    public static final String LEGACY_ISSUE_LIST="/internal/pay/entitlement/legacy-issue/list";
    public static final String LEGACY_TEMPLATE_GET="/internal/pay/entitlement/legacy-template/get";
    public static final String LEGACY_TEMPLATE_STATUS="/internal/pay/entitlement/legacy-template/status";
    public static final String LEGACY_TEMPLATE_SAVE="/internal/pay/entitlement/legacy-template/save";
    public static final String HISTORY_RETURN_REVERSE="/internal/pay/entitlement/migration/history-return/reverse";
    public static final String HISTORY_RETURN_QUERY="/internal/pay/entitlement/migration/history-return/query";

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
    public static final String ENT_QUERY_HISTORY = "/internal/pay/entitlement/query/history";
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
    public static final String SV_QUERY = "/internal/pay/stored-value/query/recharge";
    public static final String SV_BALANCE = "/internal/pay/stored-value/query/balance";
    public static final String SV_CONSUMER_STORE = "/internal/pay/stored-value/consumer/store";
    public static final String SV_CONSUMER_RECORDS = "/internal/pay/stored-value/consumer/records";
    public static final String SV_NOTIFICATION_FACT = "/internal/pay/stored-value/consumer/notification-fact";
    public static final String SV_STORES = "/internal/pay/stored-value/query/stores";
    public static final String SV_CONSUMPTIONS = "/internal/pay/stored-value/query/consumptions";
    public static final String SV_USE_QUOTE = "/internal/pay/stored-value/use/quote";

    public static final String SALE_RESERVE = "/internal/pay/entitlement/sale/reserve";
    public static final String SALE_RELEASE = "/internal/pay/entitlement/sale/release";
    public static final String SALE_QUERY = "/internal/pay/entitlement/sale/query";
    public static final String EXCEPTIONS_RETRY_EVENT="/internal/pay/entitlement/exceptions/retry-event";
    public static final String EXCEPTIONS_DEAD_LETTERS="/internal/pay/entitlement/exceptions/dead-letters";
    public static final String EXCEPTIONS_LIST="/internal/pay/entitlement/exceptions/list";
    public static final String EXCEPTIONS_GET="/internal/pay/entitlement/exceptions/get";
    public static final String EXCEPTIONS_RETRY="/internal/pay/entitlement/exceptions/retry-grant";
    public static final String EXCEPTIONS_SOURCE="/internal/pay/entitlement/exceptions/query-source";
    public static final String RECON_CHECK="/internal/pay/entitlement/reconciliations/check";
    public static final String RECON_LIST="/internal/pay/entitlement/reconciliations/list";
    public static final String RECON_GET="/internal/pay/entitlement/reconciliations/get";
    public static final String RECON_DIFFS="/internal/pay/entitlement/reconciliations/differences";
    public static final String EXCEPTIONS_ACTIONS="/internal/pay/entitlement/exceptions/actions";
    public static final String LIFECYCLE_FREEZE="/internal/pay/entitlement/lifecycle/freeze";
    public static final String LIFECYCLE_STATE="/internal/pay/entitlement/lifecycle/state";
    public static final String SV_CAPABILITIES="/internal/pay/stored-value/capabilities";
    public static final String LIFECYCLE_EXPIRE="/internal/pay/entitlement/lifecycle/expire";
    public static final String MARKETING_USAGE="/internal/pay/entitlement/marketing/usage-effects";
    public static final String OWNER_RESOLVE="/internal/pay/entitlement/owners/resolve";
    public static final String ENT_CAPABILITIES = "/internal/pay/entitlement/capabilities";
    public static final String LIFECYCLE_REMINDERS = "/internal/pay/entitlement/lifecycle/expiring-soon";
    public static final String EXCEPTIONS_LIST_ACROSS = "/internal/pay/entitlement/exceptions/list-scoped";
    public static final String MIGRATION_IMPORT="/internal/pay/entitlement/migration/import";
    public static final String MIGRATION_IMPORT_GET="/internal/pay/entitlement/migration/import/get";
    public static final String MIGRATION_PLAN="/internal/pay/entitlement/migration/plan";
    public static final String MIGRATION_PLAN_PREPARED="/internal/pay/entitlement/migration/plan/prepared";
    public static final String MIGRATION_PLAN_GET="/internal/pay/entitlement/migration/plan/get";
    public static final String MIGRATION_PLAN_VERIFY="/internal/pay/entitlement/migration/plan/verify";
    public static final String MIGRATION_STAGE="/internal/pay/entitlement/migration/stage";
    public static final String MIGRATION_STAGE_GET="/internal/pay/entitlement/migration/stage/get";
    public static final String MIGRATION_STAGE_VERIFY="/internal/pay/entitlement/migration/stage/verify";
    public static final String MIGRATION_STAGE_MANIFEST="/internal/pay/entitlement/migration/stage-manifest";
    public static final String MIGRATION_STAGE_ASSESS="/internal/pay/entitlement/migration/stage/assess";
    public static final String MIGRATION_ALIAS_COMPARE="/internal/pay/entitlement/migration/alias/compare";
    public static final String MIGRATION_ALIAS_RECOGNIZE="/internal/pay/entitlement/migration/alias/recognize";
    public static final String MIGRATION_ALIAS_GET="/internal/pay/entitlement/migration/alias/get";
    public static final String LEGACY_COUPON_SNAPSHOT="/internal/pay/entitlement/legacy-coupons/snapshot";
    public static final String LEGACY_COUPON_ESTIMATE="/internal/pay/entitlement/legacy-coupons/estimate";
    public static final String LEGACY_COUPON_QUOTE="/internal/pay/entitlement/legacy-coupons/quote";
    public static final String LEGACY_COUPON_PREPARE="/internal/pay/entitlement/legacy-coupons/prepare";
    public static final String LEGACY_COUPON_COMMIT="/internal/pay/entitlement/legacy-coupons/commit";
    public static final String LEGACY_COUPON_RELEASE="/internal/pay/entitlement/legacy-coupons/release";
    public static final String LEGACY_COUPON_REVERSE="/internal/pay/entitlement/legacy-coupons/reverse";
    public static final String LEGACY_COUPON_QUERY="/internal/pay/entitlement/legacy-coupons/query";

    public static final String SV_PLAN_CREATE = "/internal/pay/stored-value/plans/create";
    public static final String SV_PLAN_GET = "/internal/pay/stored-value/plans/get";
    public static final String SV_PLAN_LIST = "/internal/pay/stored-value/plans/list";
    public static final String SV_PLAN_STATUS = "/internal/pay/stored-value/plans/status";
    public static final String SV_CUSTOMER_BIND="/internal/pay/stored-value/customers/bind";
    public static final String SV_PLAN_RECHARGE_CONFIRM_OFFLINE="/internal/pay/stored-value/plan-recharges/confirm-offline";
    public static final String SV_PLAN_RECHARGE_CREATE = "/internal/pay/stored-value/plan-recharges/create";
    public static final String SV_PLAN_RECHARGE_QUERY = "/internal/pay/stored-value/plan-recharges/query";
    public static final String SV_PLAN_RECHARGE_RECOVER = "/internal/pay/stored-value/plan-recharges/recover";
    public static final String SV_MERCHANT_DIRECTORY="/internal/pay/stored-value/merchant/directory";
    public static final String SV_MERCHANT_ACCOUNT="/internal/pay/stored-value/merchant/account";
    public static final String SV_MERCHANT_RECORDS="/internal/pay/stored-value/merchant/records";
    public static final String SV_MERCHANT_RECORD="/internal/pay/stored-value/merchant/record";
    public static final String SV_PLAN_RECHARGE_REPLAY="/internal/pay/stored-value/plan-recharges/replay";
    public static final String SV_PLAN_RECHARGE_CANCEL_CREATION="/internal/pay/stored-value/plan-recharges/cancel-creation";
}
