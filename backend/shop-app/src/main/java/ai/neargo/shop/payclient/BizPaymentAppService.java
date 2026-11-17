package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.client.PayBizInternalApi;
import ai.neargo.shop.pay.client.PayBizInternalApi.StoreMethods;
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

/**
 * 商家端 · 支付（开发计划 M5a.5）：代客收款、退款、收款日流水、静态收款码、授权第三方。
 * b-app 只调业务服务（{@code /biz/payment/**}），认商家、判权、定当前门店在这里之前完成；内嵌形态直调支付域，独立形态经
 * {@code /internal/pay/v1/biz/**}。操作人由实现取当前登录的商家账号。
 */
public interface BizPaymentAppService {

    List<GrantView> grants(String storeNo);

    GrantView grant(String storeNo, String appNo, Set<String> actions);

    void revokeGrant(String storeNo, Long id);

    /** @param bizNo 业务单号（可空，TDD-业务单收款汇总 AC8） */
    CollectView collect(String storeNo, Long amountMinor, String subject, Integer expireMinutes, String bizNo);

    /** 本店带某个业务单号的付款链接与储值扣款原始行（TDD-业务单收款汇总） */
    ai.neargo.shop.pay.biz.PayBizFacade.BizReceipts receipts(String storeNo, String bizNo);

    /** 门店付款方式（TDD-门店付款方式-App设置）：门店能用的方式及开关、不可开原因 */
    StoreMethods storeMethods(String storeNo);

    /** 保存开启集合（全开 = 跟随商户）；expectedVersion 取读到的 version */
    StoreMethods saveStoreMethods(String storeNo, String requestNo, Long expectedVersion,
                                                                             List<String> enabledProductCodes);

    PayBizInternalApi.MethodPreferences methodPreferences(String storeNo);

    PayBizInternalApi.MethodPreferences saveMethodPreferences(String storeNo, String requestNo, long expectedVersion,
            String eligibilityRevision, String mode, List<PayBizInternalApi.MethodChoice> choices);

    List<QrView> qrCodes(String storeNo);

    QrView createQrCode(String storeNo, String kind, String tableRef, Long singleLimitMinor, Long dailyLimitMinor);

    void disableQrCode(String storeNo, String qrCode);

    RefundView requestRefund(String storeNo, String payOrderNo, Long amountMinor, String reason, String outRefundNo);

    List<RefundView> refunds(String storeNo);

    DailyStatement statement(String storeNo, String day);

    // ---- 「收款」Tab：本店支付订单 ----

    PaymentPage paymentOrders(String storeNo, PaymentQuery query);

    Summary paymentSummary(String storeNo, String from, String to);

    Lookup paymentLookup(String storeNo, Long amountMinor, String within, String tradeNoSuffix);

    CollectStatus collectStatus(String storeNo);

    Detail paymentDetail(String storeNo, String payOrderNo);

    Detail paymentRefresh(String storeNo, String payOrderNo);

    // ---- 门店收银台：付款链接（TDD-门店收银台与付款链接 L3–L5） ----

    LinkPage paymentLinks(String storeNo, String status, String cursor, Integer size);

    CollectView reissueLink(String storeNo, String payOrderNo);

    LinkRow voidLink(String storeNo, String payOrderNo, String reason);
}
