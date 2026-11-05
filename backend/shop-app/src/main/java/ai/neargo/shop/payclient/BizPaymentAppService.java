package ai.neargo.shop.payclient;

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

    CollectView collect(String storeNo, Long amountMinor, String subject, Integer expireMinutes);

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

    Detail paymentDetail(String storeNo, String payOrderNo);

    Detail paymentRefresh(String storeNo, String payOrderNo);
}
