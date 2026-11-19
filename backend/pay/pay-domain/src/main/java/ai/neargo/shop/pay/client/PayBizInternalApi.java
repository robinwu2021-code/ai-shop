package ai.neargo.shop.pay.client;

import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.CollectStatus;
import ai.neargo.shop.pay.acquiring.service.MerchantCollectLinkQueryService.LinkPage;
import ai.neargo.shop.pay.acquiring.service.MerchantCollectLinkQueryService.LinkRow;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.Detail;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.Lookup;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.PaymentPage;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.Summary;
import ai.neargo.shop.pay.acquiring.service.PayStatementService.DailyStatement;
import ai.neargo.shop.pay.biz.PayBizFacade.PaymentQuery;
import ai.neargo.shop.pay.biz.PayBizFacade.CollectView;
import ai.neargo.shop.pay.biz.PayBizFacade.QrView;
import ai.neargo.shop.pay.biz.PayBizFacade.RefundView;
import ai.neargo.shop.pay.openapp.service.AppGrantService.GrantView;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

/**
 * 独立形态下业务服务调商家端支付动作（开发计划 M5a.5）：{@code /internal/pay/v1/biz/stores/{storeNo}/**}，经内部入口守卫识别与留痕。
 * 门店是业务服务按商家授权校验过的当前门店；每个调用都带操作人（商家账号号 —— 支付只记、不认人，ADR-045 G9）。
 * 业务拒绝走 {@link PortResult}。
 */
public interface PayBizInternalApi {

    String PREFIX = PaymentInternalPaths.PREFIX + "/biz/stores/{storeNo}";

    record GrantReq(String operatorNo, String appNo, Set<String> actions) {
    }

    /**
     * @param bizNo 业务单号（可空，TDD-业务单收款汇总 AC8）。为空时不序列化：请求体与加字段前一字不差，
     *              独立部署、尚未升级的收款服务照常收（166 的 near-pay 是另一个仓库，见 D16）
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record CollectReq(String operatorNo, Long amountMinor, String subject, Integer expireMinutes, String bizNo) {
    }

    /** 门店付款方式（TDD-门店付款方式-App设置；与 near-pay 同形）。excludedBy：null 开启 / STORE 门店关（可开）/ MERCHANT、CATALOG、STORE_STATE、ENTRY 不可开 */
    /** @param checkout H5 收银台此刻是否显示（near-pay 与收银台同一计算）；null = 无法判断 */
    record StoreMethodItem(String productCode, String channelCode, String method, boolean test, boolean enabled, boolean editable,
                           String excludedBy, Boolean checkout) {
    }

    record StoreMethods(String storeNo, String mode, long version, List<StoreMethodItem> items) {
    }

    record StoreMethodsReq(String operatorNo, String requestNo, Long expectedVersion, List<String> enabledProductCodes) {
    }

    record MethodChoice(String paymentMethodCode, boolean test) {}
    record MethodPreference(MethodChoice choice, boolean configuredEnabled, boolean effectiveEnabled,
                            boolean editable, String blockedReason, List<String> productCodes, List<String> channelCodes) {}
    record MethodPreferences(String storeNo, String mode, long version, String eligibilityRevision,
                             List<MethodPreference> items) {}
    /**
     * 主体在支付侧的渠道账户（TDD-门店代码与商户渠道展示 C1–C3；与 near-pay 同形）。channelMerchantId 为全号；
     * pendingReason：CHANNEL_NOT_LIVE（渠道待开通 / 停用）、ACCOUNT_UNVERIFIED（渠道已开通、账户未核验），可用时为 null。
     */
    record EntityChannelAccount(String payMerchantNo, String channelCode, String channelName, boolean sandbox,
                                String channelStatus, String channelMerchantId, String accountStatus,
                                String verificationStatus, String pendingReason, List<String> boundStoreNos) {}
    /** merchantNo 为 null：主体在支付侧没有已核验的支付商户（据此回退到本地进件表）。 */
    record EntityChannelAccounts(String entityNo, String merchantNo, List<EntityChannelAccount> accounts) {}
    record MethodPreferencesReq(String operatorNo, String requestNo, long expectedVersion,
                                String eligibilityRevision, String mode, List<MethodChoice> enabledMethods) {}

    record QrReq(String operatorNo, String kind, String tableRef, Long singleLimitMinor, Long dailyLimitMinor) {
    }

    record RefundReq(String operatorNo, String payOrderNo, Long amountMinor, String reason, String outRefundNo) {
    }

    /** 列表条件走请求体：多值条件原样带过去，不靠查询串的重复参数 */
    record PaymentSearchReq(String operatorNo, PaymentQuery query) {
    }

    @GetExchange(PREFIX + "/app-grants")
    PortResult<List<GrantView>> grants(@PathVariable("storeNo") String storeNo, @RequestParam("operatorNo") String operatorNo);

    @PostExchange(PREFIX + "/app-grants")
    PortResult<GrantView> grant(@PathVariable("storeNo") String storeNo, @RequestBody GrantReq req);

    @DeleteExchange(PREFIX + "/app-grants/{id}")
    PortResult<Void> revokeGrant(@PathVariable("storeNo") String storeNo, @PathVariable("id") Long id,
                                 @RequestParam("operatorNo") String operatorNo);

    @PostExchange(PREFIX + "/collections")
    PortResult<CollectView> collect(@PathVariable("storeNo") String storeNo, @RequestBody CollectReq req);

    @GetExchange(PREFIX + "/collection-methods")
    PortResult<StoreMethods> storeMethods(@PathVariable("storeNo") String storeNo, @RequestParam("operatorNo") String operatorNo);

    @PutExchange(PREFIX + "/collection-methods")
    PortResult<StoreMethods> saveStoreMethods(@PathVariable("storeNo") String storeNo, @RequestBody StoreMethodsReq req);

    @GetExchange(PREFIX + "/method-preferences")
    PortResult<MethodPreferences> methodPreferences(@PathVariable("storeNo") String storeNo, @RequestParam("operatorNo") String operatorNo);

    @PutExchange(PREFIX + "/method-preferences")
    PortResult<MethodPreferences> saveMethodPreferences(@PathVariable("storeNo") String storeNo, @RequestBody MethodPreferencesReq req);

    /** 主体的渠道账户（按来源关联找支付商户），只读 */
    @GetExchange(PaymentInternalPaths.PREFIX + "/biz/entities/{entityNo}/channel-accounts")
    PortResult<EntityChannelAccounts> entityChannelAccounts(@PathVariable("entityNo") String entityNo,
                                                            @RequestParam("sourceScope") String sourceScope);

    @GetExchange(PREFIX + "/qr-codes")
    PortResult<List<QrView>> qrCodes(@PathVariable("storeNo") String storeNo, @RequestParam("operatorNo") String operatorNo);

    @PostExchange(PREFIX + "/qr-codes")
    PortResult<QrView> createQrCode(@PathVariable("storeNo") String storeNo, @RequestBody QrReq req);

    @PutExchange(PREFIX + "/qr-codes/{qrCode}/disabled")
    PortResult<Void> disableQrCode(@PathVariable("storeNo") String storeNo, @PathVariable("qrCode") String qrCode,
                                   @RequestParam("operatorNo") String operatorNo);

    @PostExchange(PREFIX + "/refunds")
    PortResult<RefundView> requestRefund(@PathVariable("storeNo") String storeNo, @RequestBody RefundReq req);

    @GetExchange(PREFIX + "/refunds")
    PortResult<List<RefundView>> refunds(@PathVariable("storeNo") String storeNo, @RequestParam("operatorNo") String operatorNo);

    @GetExchange(PREFIX + "/statement")
    PortResult<DailyStatement> statement(@PathVariable("storeNo") String storeNo, @RequestParam("operatorNo") String operatorNo,
                                         @RequestParam(value = "day", required = false) String day);

    @PostExchange(PREFIX + "/payment-orders/search")
    PortResult<PaymentPage> paymentOrders(@PathVariable("storeNo") String storeNo, @RequestBody PaymentSearchReq req);

    @GetExchange(PREFIX + "/payment-orders/summary")
    PortResult<Summary> paymentSummary(@PathVariable("storeNo") String storeNo, @RequestParam("operatorNo") String operatorNo,
                                       @RequestParam(value = "from", required = false) String from,
                                       @RequestParam(value = "to", required = false) String to);

    @GetExchange(PREFIX + "/payment-orders/lookup")
    PortResult<Lookup> paymentLookup(@PathVariable("storeNo") String storeNo, @RequestParam("operatorNo") String operatorNo,
                                     @RequestParam(value = "amountMinor", required = false) Long amountMinor,
                                     @RequestParam(value = "within", required = false) String within,
                                     @RequestParam(value = "tradeNoSuffix", required = false) String tradeNoSuffix);

    @GetExchange(PREFIX + "/payment-orders/{payOrderNo}")
    PortResult<Detail> paymentDetail(@PathVariable("storeNo") String storeNo, @PathVariable("payOrderNo") String payOrderNo,
                                     @RequestParam("operatorNo") String operatorNo);

    @PostExchange(PREFIX + "/payment-orders/{payOrderNo}/query")
    PortResult<Detail> paymentRefresh(@PathVariable("storeNo") String storeNo, @PathVariable("payOrderNo") String payOrderNo,
                                      @RequestParam("operatorNo") String operatorNo);

    @GetExchange(PREFIX + "/collect-status")
    PortResult<CollectStatus> collectStatus(@PathVariable("storeNo") String storeNo, @RequestParam("operatorNo") String operatorNo);

    // ---- 门店收银台：付款链接（TDD-门店收银台与付款链接 L3–L5） ----

    record VoidLinkReq(String operatorNo, String reason) {
    }

    @GetExchange(PREFIX + "/payment-links")
    PortResult<LinkPage> paymentLinks(@PathVariable("storeNo") String storeNo, @RequestParam("operatorNo") String operatorNo,
                                      @RequestParam(value = "status", required = false) String status,
                                      @RequestParam(value = "cursor", required = false) String cursor,
                                      @RequestParam(value = "size", required = false) Integer size);

    @GetExchange(PREFIX + "/order-receipts")
    PortResult<ai.neargo.shop.pay.biz.PayBizFacade.BizReceipts> receipts(@PathVariable("storeNo") String storeNo,
                                                                        @RequestParam("operatorNo") String operatorNo,
                                                                        @RequestParam("bizNo") String bizNo);

    @PostExchange(PREFIX + "/payment-links/{payOrderNo}/reissue")
    PortResult<CollectView> reissueLink(@PathVariable("storeNo") String storeNo, @PathVariable("payOrderNo") String payOrderNo,
                                        @RequestParam("operatorNo") String operatorNo);

    @PostExchange(PREFIX + "/payment-links/{payOrderNo}/void")
    PortResult<LinkRow> voidLink(@PathVariable("storeNo") String storeNo, @PathVariable("payOrderNo") String payOrderNo,
                                 @RequestBody VoidLinkReq req);
}
