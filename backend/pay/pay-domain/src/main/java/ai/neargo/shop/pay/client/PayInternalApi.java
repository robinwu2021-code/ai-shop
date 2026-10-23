package ai.neargo.shop.pay.client;

import ai.neargo.shop.spi.entitlement.EntitlementCapabilityPort;
import ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.ReminderPage;

import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerRef;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort.UsageEffects;
import ai.neargo.shop.spi.storedvalue.StoredValueCapabilityPort.Status;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.RunView;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeView;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.ExpireResult;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ActionView;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.DiffView;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ExceptionView;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.SaleView;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges;
import ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews;
import ai.neargo.shop.spi.storedvalue.StoredValuePort;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews;
import ai.neargo.shop.pay.entitlement.dto.CodeViews;

import ai.neargo.shop.pay.dto.FeeRuleVO;
import ai.neargo.shop.pay.dto.FinanceVOs.SettleInvoiceVO;
import ai.neargo.shop.pay.entitlement.dto.ManagedTemplateView;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.PublishedVersionView;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.PolicyView;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort.PublishedDefinition;
import ai.neargo.shop.pay.client.PayEntitlementRequests.ListTemplates;
import ai.neargo.shop.pay.client.PayEntitlementRequests.GetTemplate;
import ai.neargo.shop.pay.client.PayEntitlementRequests.CreateTemplate;
import ai.neargo.shop.pay.client.PayEntitlementRequests.DraftTemplate;
import ai.neargo.shop.pay.client.PayEntitlementRequests.PublishTemplate;
import ai.neargo.shop.pay.client.PayEntitlementRequests.StatusTemplate;
import ai.neargo.shop.pay.client.PayEntitlementRequests.ConfigureIssue;
import ai.neargo.shop.pay.client.PayEntitlementRequests.CatalogVersion;
import ai.neargo.shop.pay.client.PayEntitlementRequests.ValidateCatalogReference;
import ai.neargo.shop.spi.storedvalue.StoredValueRechargeFactsPort.RechargeFact;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceView;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreItem;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort.Quote;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageItem;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 支付域独立进程（pay-svc）对外的全部内部端点，调用方是主应用。
 *
 * <p>支付域自己<b>不知道</b>它跑在哪种形态里（{@code PayHasNoControllerTest} 盯着这一条）——
 * 这里只描述这几个端点长什么样；用不用它、何时用，是主应用装配时决定的。
 *
 * <p>放在 pay-domain 而不是 shop-app：服务端（pay-svc）与客户端（shop-app）都看得见它，
 * 两边的请求体 {@link IssueReq} / {@link RejectReq} 也就只有这一份 —— 此前是服务端一份 public record、
 * 客户端一份 private record，字段名靠人对齐。
 *
 * <h2>不要给写操作加重试</h2>
 * {@code issue} / {@code reject} 只能从 PENDING 出发，重复执行第二次会失败或产生重复记录；
 * 在有幂等键之前，超时之后「成没成」只能由人去查。{@code @Retryable} 只允许加在 GET 方法上。
 */
public interface PayInternalApi {
    @org.springframework.web.service.annotation.PostExchange(PayInternalPaths.SV_MIGRATION_SOURCES)
    ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.OperatorEvidence svMigrationSources(@org.springframework.web.bind.annotation.RequestBody PayStoredValueMigrationRequests.Operator request);

    @org.springframework.web.service.annotation.PostExchange(PayInternalPaths.SV_ISSUER_LOCATE)
    ai.neargo.shop.spi.storedvalue.StoredValueIssuers.ScopeRef svIssuerLocate(@org.springframework.web.bind.annotation.RequestBody PayStoredValueIssuerRequests.Locate request);

    @org.springframework.web.service.annotation.PostExchange(PayInternalPaths.SV_ISSUER_CHECK)
    ai.neargo.shop.spi.storedvalue.StoredValueIssuers.ConfigurationImpact svIssuerCheck(@org.springframework.web.bind.annotation.RequestBody PayStoredValueIssuerRequests.Check request);

    @org.springframework.web.service.annotation.PostExchange(PayInternalPaths.SV_ISSUER_OPTIONS)
    ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreOptions svIssuerOptions(@org.springframework.web.bind.annotation.RequestBody PayStoredValueIssuerRequests.Options request);

    @org.springframework.web.service.annotation.PostExchange(PayInternalPaths.SV_ISSUER_REFRESH)
    ai.neargo.shop.spi.storedvalue.StoredValueIssuers.AccessAck svIssuerRefresh(@org.springframework.web.bind.annotation.RequestBody PayStoredValueIssuerRequests.PrepareStore request);

    @org.springframework.web.service.annotation.PostExchange(PayInternalPaths.SV_MIGRATION_OPERATOR)
    ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.ExecutionCommand svMigrationOperator(@org.springframework.web.bind.annotation.RequestBody PayStoredValueMigrationRequests.Operator request);
    @org.springframework.web.service.annotation.PostExchange(PayInternalPaths.SV_MIGRATION_COMMAND)
    ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.ExecutionCommand svMigrationCommand(@org.springframework.web.bind.annotation.RequestBody PayStoredValueMigrationRequests.Operator request);

    @PostExchange(PayInternalPaths.LEGACY_TEMPLATE_ARCHIVE)
    ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.Snapshot legacyTemplateArchive(@RequestBody PayLegacyTemplateRequests.Archive request);
    @PostExchange(PayInternalPaths.LEGACY_ISSUE_CREATE)
    ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.View legacyIssue(@RequestBody PayLegacyIssueRequests.Issue request);
    @PostExchange(PayInternalPaths.LEGACY_ISSUE_GET)
    ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.View legacyIssueGet(@RequestBody PayLegacyIssueRequests.Get request);
    @PostExchange(PayInternalPaths.LEGACY_ISSUE_RESUME)
    ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.View legacyIssueResume(@RequestBody PayLegacyIssueRequests.Resume request);
    @PostExchange(PayInternalPaths.LEGACY_ISSUE_LIST)
    java.util.List<ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.View> legacyIssueList(@RequestBody PayLegacyIssueRequests.History request);
    @PostExchange(PayInternalPaths.LEGACY_TEMPLATE_GET)
    ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.Snapshot legacyTemplateGet(@RequestBody PayLegacyTemplateRequests.Get request);
    @PostExchange(PayInternalPaths.LEGACY_TEMPLATE_STATUS)
    ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.Snapshot legacyTemplateStatus(@RequestBody PayLegacyTemplateRequests.Status request);
    @PostExchange(PayInternalPaths.LEGACY_TEMPLATE_SAVE)
    ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.Snapshot legacyTemplateSave(@RequestBody PayLegacyTemplateRequests.Save request);
    @PostExchange(PayInternalPaths.HISTORY_RETURN_REVERSE)
    ai.neargo.shop.spi.entitlement.LegacyCouponHistoricalReturnPort.Receipt historicalReturnReverse(@RequestBody PayHistoricalReturnRequests.Query request);
    @PostExchange(PayInternalPaths.HISTORY_RETURN_QUERY)
    ai.neargo.shop.spi.entitlement.LegacyCouponHistoricalReturnPort.Receipt historicalReturnQuery(@RequestBody PayHistoricalReturnRequests.Query request);

    /** 全部费率版本，含历史 */
    @GetExchange(PayInternalPaths.FEE_RULES)
    List<FeeRuleVO> feeRules();

    /** 某一时刻生效的费率：{@code 经营模式|流量来源 → 费率（基点）} */
    @GetExchange(PayInternalPaths.FEE_RULES_EFFECTIVE)
    Map<String, Integer> effectiveRates(@RequestParam("at") long at);

    /** status / keyword 为 {@code null} 时不带这个参数（服务端按「不筛」处理） */
    @GetExchange(PayInternalPaths.SETTLE_INVOICES)
    PageData<SettleInvoiceVO> settleInvoices(@RequestParam(value = "status", required = false) String status,
                                             @RequestParam(value = "keyword", required = false) String keyword,
                                             @RequestParam("page") long page,
                                             @RequestParam("size") long size);

    @PostExchange(PayInternalPaths.SETTLE_INVOICE_ISSUE)
    SettleInvoiceVO issue(@PathVariable("invoiceNo") String invoiceNo, @RequestBody IssueReq req);

    @PostExchange(PayInternalPaths.SETTLE_INVOICE_REJECT)
    SettleInvoiceVO reject(@PathVariable("invoiceNo") String invoiceNo, @RequestBody RejectReq req);

    record IssueReq(String serialNo, String operatorNo) {
    }

    record RejectReq(String reason, String operatorNo) {
    }
    @PostExchange(PayInternalPaths.ENT_LIST)
    PageData<ManagedTemplateView> entTemplates(@RequestBody ListTemplates request);

    @PostExchange(PayInternalPaths.ENT_GET)
    ManagedTemplateView entTemplate(@RequestBody GetTemplate request);

    @PostExchange(PayInternalPaths.ENT_CREATE)
    ManagedTemplateView entCreate(@RequestBody CreateTemplate request);

    @PostExchange(PayInternalPaths.ENT_DRAFT)
    ManagedTemplateView entDraft(@RequestBody DraftTemplate request);

    @PostExchange(PayInternalPaths.ENT_PUBLISH)
    PublishedVersionView entPublish(@RequestBody PublishTemplate request);

    @PostExchange(PayInternalPaths.ENT_STATUS)
    ManagedTemplateView entStatus(@RequestBody StatusTemplate request);

    @PostExchange(PayInternalPaths.ENT_POLICY)
    PolicyView entIssuePolicy(@RequestBody ConfigureIssue request);

    @PostExchange(PayInternalPaths.ENT_VERSION)
    PublishedDefinition entVersion(@RequestBody CatalogVersion request);

    @PostExchange(PayInternalPaths.ENT_REFERENCE)
    PublishedDefinition entReference(@RequestBody ValidateCatalogReference request);


    @PostExchange(PayInternalPaths.ENT_GRANT_PREPARE)
    EntitlementGrantPort.GrantView entGrantPrepare(@RequestBody PayEntitlementRequests.GrantPrepare request);

    @PostExchange(PayInternalPaths.ENT_GRANT_COMMIT)
    EntitlementGrantPort.GrantView entGrantCommit(@RequestBody PayEntitlementRequests.GrantCommit request);

    @PostExchange(PayInternalPaths.ENT_GRANT_ABORT)
    EntitlementGrantPort.GrantView entGrantAbort(@RequestBody PayEntitlementRequests.GrantAbort request);

    @PostExchange(PayInternalPaths.ENT_GRANT_QUERY)
    EntitlementGrantPort.GrantView entGrantQuery(@RequestBody PayEntitlementRequests.GrantQuery request);

    @PostExchange(PayInternalPaths.ENT_USE_QUOTE)
    EntitlementUsePort.QuoteView entQuote(@RequestBody PayEntitlementRequests.UseQuote request);

    @PostExchange(PayInternalPaths.ENT_USE_PREVIEW)
    EntitlementUsePort.PreviewView entPreview(@RequestBody PayEntitlementRequests.UsePreview request);

    @PostExchange(PayInternalPaths.ENT_USE_PREPARE)
    EntitlementUsePort.HoldView entUsePrepare(@RequestBody PayEntitlementRequests.UsePrepare request);

    @PostExchange(PayInternalPaths.ENT_USE_COMMIT)
    EntitlementUsePort.UseReceipt entUseCommit(@RequestBody PayEntitlementRequests.UseCommit request);

    @PostExchange(PayInternalPaths.ENT_USE_RELEASE)
    EntitlementUsePort.HoldView entUseRelease(@RequestBody PayEntitlementRequests.UseRelease request);

    @PostExchange(PayInternalPaths.ENT_USE_REVERSE)
    EntitlementUsePort.UseReceipt entReverse(@RequestBody PayEntitlementRequests.UseReverse request);

    @PostExchange(PayInternalPaths.ENT_USE_REVERSE_QUERY)
    EntitlementUsePort.ReversalView entReverseQuery(@RequestBody PayEntitlementRequests.ReverseQuery request);

    @PostExchange(PayInternalPaths.ENT_USE_QUERY)
    EntitlementUsePort.HoldView entUseQuery(@RequestBody PayEntitlementRequests.UseQuery request);

    @PostExchange(PayInternalPaths.ENT_QUERY_MINE)
    CursorPage<EntitlementQueryPort.EntitlementItem> entMine(@RequestBody PayEntitlementRequests.Mine request);

    @PostExchange(PayInternalPaths.ENT_QUERY_DETAIL)
    EntitlementQueryPort.EntitlementDetail entDetail(@RequestBody PayEntitlementRequests.Detail request);

    @PostExchange(PayInternalPaths.ENT_QUERY_HISTORY)
    CursorPage<EntitlementQueryPort.UsageItem> entHistory(@RequestBody PayEntitlementRequests.UsageHistory request);

    @PostExchange(PayInternalPaths.ENT_QUERY_USAGE)
    EntitlementQueryPort.UsageDetail entUsage(@RequestBody PayEntitlementRequests.Usage request);

    @PostExchange(PayInternalPaths.ENT_QUERY_ALLOWANCES)
    EntitlementQueryPort.AllowanceView entAllowances(@RequestBody PayEntitlementRequests.Allowances request);

    @PostExchange(PayInternalPaths.ENT_QUERY_ACTIVATION)
    EntitlementQueryPort.ActivationQuote entActivation(@RequestBody PayEntitlementRequests.Activation request);

    @PostExchange(PayInternalPaths.ENT_USE_FACTS)
    EntitlementUseFactsPort.UseFact entUseFacts(@RequestBody PayEntitlementRequests.UseFacts request);

    @PostExchange(PayInternalPaths.ENT_PAYMENT_ATTEMPTS)
    PayEntitlementResponses.PaymentAttemptView entPaymentAttempts(
            @RequestBody PayEntitlementRequests.PaymentAttempts request);

    @PostExchange(PayInternalPaths.ENT_BATCH_CREATE)
    IssueBatchViews.BatchView entBatchCreate(@RequestBody PayEntitlementRequests.BatchCreate request);

    @PostExchange(PayInternalPaths.ENT_BATCH_GET)
    IssueBatchViews.BatchView entBatchGet(@RequestBody PayEntitlementRequests.BatchGet request);

    @PostExchange(PayInternalPaths.ENT_BATCH_RESUME)
    IssueBatchViews.BatchView entBatchResume(@RequestBody PayEntitlementRequests.BatchResume request);

    @PostExchange(PayInternalPaths.ENT_CODE_ISSUE)
    CodeViews.CodeView entCodeIssue(@RequestBody PayEntitlementRequests.CodeIssue request);

    @PostExchange(PayInternalPaths.ENT_CODE_RESOLVE)
    CodeViews.ResolvedCode entCodeResolve(@RequestBody PayEntitlementRequests.CodeResolve request);

    @PostExchange(PayInternalPaths.ENT_CODE_INVALIDATE)
    PayEntitlementResponses.CodeInvalidationView entCodeInvalidate(
            @RequestBody PayEntitlementRequests.CodeInvalidate request);

    @PostExchange(PayInternalPaths.ENT_MERCHANT_USAGE)
    PageData<EntitlementQueryPort.UsageItem> entMerchantUsage(
            @RequestBody PayEntitlementRequests.MerchantUsage request);
    @PostExchange(PayInternalPaths.SV_FACTS)
    RechargeFact svFacts(@RequestBody PayStoredValueRequests.Query request);

    @PostExchange(PayInternalPaths.SV_CREATE)
    RechargeView svCreate(@RequestBody PayStoredValueRequests.Create request);

    @PostExchange(PayInternalPaths.SV_SUBMIT)
    RechargeView svSubmit(@RequestBody PayStoredValueRequests.Submit request);

    @PostExchange(PayInternalPaths.SV_RECEIVE)
    RechargeView svReceive(@RequestBody PayStoredValueRequests.Receive request);

    @PostExchange(PayInternalPaths.SV_CANCEL)
    RechargeView svCancel(@RequestBody PayStoredValueRequests.Cancel request);

    @PostExchange(PayInternalPaths.SV_CREDIT)
    RechargeView svCredit(@RequestBody PayStoredValueRequests.Credit request);

    @PostExchange(PayInternalPaths.SV_QUERY)
    RechargeView svQuery(@RequestBody PayStoredValueRequests.QueryRecharge request);

    @PostExchange(PayInternalPaths.SV_BALANCE)
    BalanceView svBalance(@RequestBody PayStoredValueRequests.Balance request);

    @PostExchange(PayInternalPaths.SV_CONSUMER_STORE)
    StoredValuePort.StoreItem svConsumerStore(@RequestBody PayStoredValueConsumerRequests.Store request);
    @PostExchange(PayInternalPaths.SV_CONSUMER_RECORDS)
    CursorPage<StoredValueConsumerViews.Record> svConsumerRecords(@RequestBody PayStoredValueConsumerRequests.Records request);
    @PostExchange(PayInternalPaths.SV_NOTIFICATION_FACT)
    StoredValueConsumerViews.NotificationFact svNotificationFact(@RequestBody PayStoredValueConsumerRequests.Fact request);

    @PostExchange(PayInternalPaths.SV_STORES)
    CursorPage<StoreItem> svStores(@RequestBody PayStoredValueRequests.Stores request);

    @PostExchange(PayInternalPaths.SV_CONSUMPTIONS)
    CursorPage<UsageItem> svConsumptions(@RequestBody PayStoredValueRequests.Consumptions request);

    @PostExchange(PayInternalPaths.SV_USE_QUOTE)
    Quote svUseQuote(@RequestBody PayStoredValueRequests.UseQuote request);

    @PostExchange(PayInternalPaths.SALE_RESERVE)
    SaleView saleReserve(@RequestBody PaySaleRequests.Reserve request);
    @PostExchange(PayInternalPaths.SALE_RELEASE)
    SaleView saleRelease(@RequestBody PaySaleRequests.Release request);
    @PostExchange(PayInternalPaths.SALE_QUERY)
    SaleView saleQuery(@RequestBody PaySaleRequests.Query request);
    @PostExchange(PayInternalPaths.EXCEPTIONS_RETRY_EVENT)
    ExceptionView exceptionRetryEvent(@RequestBody PayExceptionRequests.RetryGrant request);
    @PostExchange(PayInternalPaths.EXCEPTIONS_DEAD_LETTERS)
    ai.neargo.shop.pay.entitlement.dto.ExceptionViews.DeadLetterPage exceptionDeadLetters(@RequestBody PayExceptionRequests.DeadLetters request);
    @PostExchange(PayInternalPaths.EXCEPTIONS_LIST)
    PageData<ExceptionView> exceptionsList(@RequestBody PayExceptionRequests.ListExceptions request);
    @PostExchange(PayInternalPaths.EXCEPTIONS_GET)
    ExceptionView exceptionGet(@RequestBody PayExceptionRequests.GetException request);
    @PostExchange(PayInternalPaths.EXCEPTIONS_RETRY)
    ExceptionView exceptionRetry(@RequestBody PayExceptionRequests.RetryGrant request);
    @PostExchange(PayInternalPaths.EXCEPTIONS_SOURCE)
    ExceptionView exceptionSource(@RequestBody PayExceptionRequests.RetryGrant request);
    @PostExchange(PayInternalPaths.RECON_CHECK)
    RunView reconciliationCheck(@RequestBody PayReconciliationRequests.Start request);
    @PostExchange(PayInternalPaths.RECON_LIST)
    PageData<RunView> reconciliationsList(@RequestBody PayReconciliationRequests.ListRuns request);
    @PostExchange(PayInternalPaths.RECON_GET)
    RunView reconciliationGet(@RequestBody PayReconciliationRequests.GetRun request);
    @PostExchange(PayInternalPaths.RECON_DIFFS)
    PageData<DiffView> reconciliationDiffs(@RequestBody PayReconciliationRequests.Differences request);
    @PostExchange(PayInternalPaths.EXCEPTIONS_ACTIONS)
    PageData<ActionView> exceptionActions(@RequestBody PayExceptionRequests.Actions request);
    @PostExchange(PayInternalPaths.LIFECYCLE_FREEZE)
    FreezeView entitlementFreeze(@RequestBody PayLifecycleRequests.Freeze request);
    @PostExchange(PayInternalPaths.LIFECYCLE_STATE)
    FreezeView entitlementLifecycleState(@RequestBody PayLifecycleRequests.State request);
    @PostExchange(PayInternalPaths.SV_CAPABILITIES)
    Status storedValueCapabilities(@RequestBody PayStoredValueRequests.Capabilities request);
    @PostExchange(PayInternalPaths.LIFECYCLE_EXPIRE)
    ExpireResult entitlementExpire(@RequestBody PayLifecycleRequests.Expire request);
    @PostExchange(PayInternalPaths.MARKETING_USAGE)
    UsageEffects marketingUsage(@RequestBody PayMarketingRequests.Usage request);
    @PostExchange(PayInternalPaths.OWNER_RESOLVE)
    OwnerRef entitlementOwnerResolve(@RequestBody PayOwnerRequests.Resolve request);
    @PostExchange(PayInternalPaths.ENT_CAPABILITIES)
    EntitlementCapabilityPort.Status entitlementCapabilities(@RequestBody PayCapabilityRequests.Status request);
    @PostExchange(PayInternalPaths.LIFECYCLE_REMINDERS)
    ReminderPage entitlementExpiringSoon(@RequestBody PayLifecycleRequests.Reminders request);
    @PostExchange(PayInternalPaths.EXCEPTIONS_LIST_ACROSS)
    PageData<ExceptionView> exceptionsListAcross(@RequestBody PayExceptionRequests.ListAcross request);
    @PostExchange(PayInternalPaths.MIGRATION_IMPORT)
    ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportView migrationImport(@RequestBody PayMigrationRequests.Import request);
    @PostExchange(PayInternalPaths.MIGRATION_IMPORT_GET)
    ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportView migrationImportGet(@RequestBody PayMigrationRequests.PlanGet request);
    @PostExchange(PayInternalPaths.MIGRATION_PLAN)
    ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanView migrationPlan(@RequestBody PayMigrationRequests.Plan request);
    @PostExchange(PayInternalPaths.MIGRATION_PLAN_PREPARED)
    ai.neargo.shop.spi.entitlement.LegacyCouponMigrationPlan migrationPlanPrepared(@RequestBody PayMigrationRequests.PlanGet request);
    @PostExchange(PayInternalPaths.MIGRATION_PLAN_GET)
    ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanView migrationPlanGet(@RequestBody PayMigrationRequests.PlanGet request);
    @PostExchange(PayInternalPaths.MIGRATION_PLAN_VERIFY)
    ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanView migrationPlanVerify(@RequestBody PayMigrationRequests.PlanGet request);
    @PostExchange(PayInternalPaths.MIGRATION_STAGE)
    ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageView migrationStage(@RequestBody PayMigrationRequests.Stage request);
    @PostExchange(PayInternalPaths.MIGRATION_STAGE_GET)
    ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageView migrationStageGet(@RequestBody PayMigrationRequests.Get request);
    @PostExchange(PayInternalPaths.MIGRATION_STAGE_VERIFY)
    ai.neargo.shop.pay.migration.dto.MigrationStageViews.Verification migrationStageVerify(@RequestBody PayMigrationRequests.Get request);
    @PostExchange(PayInternalPaths.MIGRATION_STAGE_MANIFEST)
    ai.neargo.shop.pay.migration.dto.MigrationStageViews.ManifestStageView migrationStageManifest(@RequestBody PayMigrationRequests.Stage request);
    @PostExchange(PayInternalPaths.MIGRATION_STAGE_ASSESS)
    ai.neargo.shop.pay.migration.service.LegacyCouponMigrationNormalizer.Report migrationStageAssess(
            @RequestBody PayMigrationRequests.Get request);
    @PostExchange(PayInternalPaths.MIGRATION_ALIAS_COMPARE)
    ai.neargo.shop.pay.migration.service.LegacyCouponAliasComparator.Comparison migrationAliasCompare(@RequestBody PayMigrationRequests.AliasPair request);
    @PostExchange(PayInternalPaths.MIGRATION_ALIAS_RECOGNIZE)
    ai.neargo.shop.pay.migration.dto.MigrationAliasViews.AliasView migrationAliasRecognize(@RequestBody PayMigrationRequests.AliasRecognition request);
    @PostExchange(PayInternalPaths.MIGRATION_ALIAS_GET)
    ai.neargo.shop.pay.migration.dto.MigrationAliasViews.AliasView migrationAliasGet(@RequestBody PayMigrationRequests.AliasGet request);
    @PostExchange(PayInternalPaths.LEGACY_COUPON_SNAPSHOT)
    ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Snapshot legacyCouponSnapshot(
            @RequestBody PayLegacyCouponRequests.SnapshotRequest request);
    @PostExchange(PayInternalPaths.LEGACY_COUPON_ESTIMATE)
    ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Estimate legacyCouponEstimate(
            @RequestBody PayLegacyCouponRequests.EstimateRequest request);
    @PostExchange(PayInternalPaths.LEGACY_COUPON_QUOTE)
    ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Quote legacyCouponQuote(
            @RequestBody PayLegacyCouponRequests.PrepareRequest request);
    @PostExchange(PayInternalPaths.LEGACY_COUPON_PREPARE)
    ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Operation legacyCouponPrepare(
            @RequestBody PayLegacyCouponRequests.PrepareRequest request);
    @PostExchange(PayInternalPaths.LEGACY_COUPON_COMMIT)
    ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Operation legacyCouponCommit(
            @RequestBody PayLegacyCouponRequests.CompleteRequest request);
    @PostExchange(PayInternalPaths.LEGACY_COUPON_RELEASE)
    ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Operation legacyCouponRelease(
            @RequestBody PayLegacyCouponRequests.CompleteRequest request);
    @PostExchange(PayInternalPaths.LEGACY_COUPON_REVERSE)
    ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Operation legacyCouponReverse(
            @RequestBody PayLegacyCouponRequests.CompleteRequest request);
    @PostExchange(PayInternalPaths.LEGACY_COUPON_QUERY)
    ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Operation legacyCouponQuery(
            @RequestBody PayLegacyCouponRequests.Query request);

    @PostExchange(PayInternalPaths.SV_MIGRATION_CAPABILITY)
    ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Capability svMigrationCapability(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Query query);

    @PostExchange(PayInternalPaths.SV_MIGRATION_PAUSE)
    ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.MaintenanceResult svMigrationPause(@RequestBody PayStoredValueMigrationRequests.Pause request);
    @PostExchange(PayInternalPaths.SV_MIGRATION_APPLY)
    ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.MaintenanceResult svMigrationApply(@RequestBody PayStoredValueMigrationRequests.Apply request);

    @PostExchange(PayInternalPaths.SV_MIGRATION_PROBE)
    ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.Probe svMigrationProbe(@RequestBody PayStoredValueMigrationRequests.Probe request);
    @PostExchange(PayInternalPaths.SV_MIGRATION_PREPARE)
    ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.Manifest svMigrationPrepare(@RequestBody PayStoredValueMigrationRequests.Prepare request);

    @PostExchange(PayInternalPaths.SV_ISSUER_PREPARE_STORE)
    ai.neargo.shop.spi.storedvalue.StoredValueIssuers.View svIssuerPrepareStore(@RequestBody PayStoredValueIssuerRequests.PrepareStore request);
    @PostExchange(PayInternalPaths.SV_ISSUER_FIND)
    ai.neargo.shop.spi.storedvalue.StoredValueIssuers.Lookup svIssuerFind(@RequestBody PayStoredValueIssuerRequests.Find request);
    @PostExchange(PayInternalPaths.SV_ISSUER_CONFIGURE)
    ai.neargo.shop.spi.storedvalue.StoredValueIssuers.View svIssuerConfigure(@RequestBody PayStoredValueIssuerRequests.Configure request);

    @PostExchange(PayInternalPaths.SV_PLAN_CREATE)
    StoredValuePlans.View svPlanCreate(@RequestBody PayStoredValuePlanRequests.Create request);
    @PostExchange(PayInternalPaths.SV_PLAN_GET)
    StoredValuePlans.View svPlanGet(@RequestBody PayStoredValuePlanRequests.Get request);
    @PostExchange(PayInternalPaths.SV_PLAN_LIST)
    CursorPage<StoredValuePlans.View> svPlanList(@RequestBody PayStoredValuePlanRequests.List request);
    @PostExchange(PayInternalPaths.SV_PLAN_STATUS)
    StoredValuePlans.View svPlanStatus(@RequestBody PayStoredValuePlanRequests.Status request);
    @PostExchange(PayInternalPaths.SV_CUSTOMER_BIND)
    Boolean svCustomerBind(@RequestBody PayStoredValuePlanRechargeRequests.Bind request);
    @PostExchange(PayInternalPaths.SV_PLAN_RECHARGE_CONFIRM_OFFLINE)
    StoredValuePort.RechargeView svPlanRechargeConfirmOffline(@RequestBody PayStoredValuePlanRechargeRequests.Create request);
    @PostExchange(PayInternalPaths.SV_PLAN_RECHARGE_CREATE)
    StoredValuePort.RechargeView svPlanRechargeCreate(@RequestBody PayStoredValuePlanRechargeRequests.Create request);
    @PostExchange(PayInternalPaths.SV_PLAN_RECHARGE_QUERY)
    StoredValuePort.RechargeView svPlanRechargeQuery(@RequestBody PayStoredValuePlanRechargeRequests.Query request);
    @PostExchange(PayInternalPaths.SV_PLAN_RECHARGE_RECOVER)
    Boolean svPlanRechargeRecover(@RequestBody PayStoredValuePlanRechargeRequests.Query request);
    @PostExchange(PayInternalPaths.SV_MERCHANT_DIRECTORY)
    CursorPage<StoredValueMerchantViews.Account> svMerchantDirectory(@RequestBody PayStoredValueMerchantRequests.Directory r);
    @PostExchange(PayInternalPaths.SV_MERCHANT_ACCOUNT)
    StoredValueMerchantViews.Account svMerchantAccount(@RequestBody PayStoredValueMerchantRequests.Account r);
    @PostExchange(PayInternalPaths.SV_MERCHANT_RECORDS)
    CursorPage<StoredValueMerchantViews.Record> svMerchantRecords(@RequestBody PayStoredValueMerchantRequests.Records r);
    @PostExchange(PayInternalPaths.SV_MERCHANT_RECORD)
    StoredValueMerchantViews.Record svMerchantRecord(@RequestBody PayStoredValueMerchantRequests.Record r);
    @PostExchange(PayInternalPaths.SV_PLAN_RECHARGE_REPLAY)
    StoredValuePlanRecharges.ReplayResult svPlanRechargeReplay(@RequestBody PayStoredValuePlanRechargeRequests.Replay r);
    @PostExchange(PayInternalPaths.SV_PLAN_RECHARGE_CANCEL_CREATION)
    StoredValuePort.RechargeView svPlanRechargeCancelCreation(@RequestBody PayStoredValuePlanRechargeRequests.Replay r);
    @PostExchange(PayInternalPaths.SV_ORGANIZATION_IMPACT)
    ai.neargo.shop.spi.storedvalue.StoredValueOrganizationImpactPort.Impact svOrganizationImpact(@RequestBody PayStoredValueMerchantRequests.OrganizationImpact request);
}
