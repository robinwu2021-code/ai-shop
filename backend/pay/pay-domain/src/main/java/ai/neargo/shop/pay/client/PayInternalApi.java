package ai.neargo.shop.pay.client;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.CursorPage;
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

    @PostExchange(PayInternalPaths.ENT_QUERY_USAGE)
    EntitlementQueryPort.UsageDetail entUsage(@RequestBody PayEntitlementRequests.Usage request);

    @PostExchange(PayInternalPaths.ENT_QUERY_ALLOWANCES)
    EntitlementQueryPort.AllowanceView entAllowances(@RequestBody PayEntitlementRequests.Allowances request);

    @PostExchange(PayInternalPaths.ENT_QUERY_ACTIVATION)
    EntitlementQueryPort.ActivationQuote entActivation(@RequestBody PayEntitlementRequests.Activation request);

    @PostExchange(PayInternalPaths.ENT_USE_FACTS)
    EntitlementUseFactsPort.UseFact entUseFacts(@RequestBody PayEntitlementRequests.UseFacts request);

    @PostExchange(PayInternalPaths.ENT_PAYMENT_ATTEMPTS)
    PayEntitlementResponses.PaymentAttemptView entPaymentAttempts(@RequestBody PayEntitlementRequests.PaymentAttempts request);

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
    PayEntitlementResponses.CodeInvalidationView entCodeInvalidate(@RequestBody PayEntitlementRequests.CodeInvalidate request);

}
