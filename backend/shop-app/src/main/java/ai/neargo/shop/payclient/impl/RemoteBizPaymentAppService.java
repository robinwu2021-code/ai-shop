package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
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
import ai.neargo.shop.pay.client.PayBizInternalApi.CollectReq;
import ai.neargo.shop.pay.client.PayBizInternalApi.GrantReq;
import ai.neargo.shop.pay.client.PayBizInternalApi.PaymentSearchReq;
import ai.neargo.shop.pay.client.PayBizInternalApi.QrReq;
import ai.neargo.shop.pay.client.PayBizInternalApi.RefundReq;
import ai.neargo.shop.pay.client.PayBizInternalApi.VoidLinkReq;
import ai.neargo.shop.pay.client.PayBizInternalApi;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.pay.openapp.service.AppGrantService.GrantView;
import ai.neargo.shop.payclient.BizPaymentAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 独立形态：经 {@code /internal/pay/v1/biz/**} 调支付（开发计划 M5a.5）。门店由调用方给（已按商家授权校验），操作人取当前登录的
 * 商家账号，都显式传过去 —— 支付侧只记、不认人（ADR-045 G9）；业务拒绝原样还原成 {@link BizException}。
 */
@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteBizPaymentAppService implements BizPaymentAppService {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final PayBizInternalApi pay;
    private final AuditLogPort auditLogPort;

    @Autowired
    public RemoteBizPaymentAppService(InternalHttp http, AuditLogPort auditLogPort) {
        this(http.client(ServiceName.PAY, PayBizInternalApi.class, READ_TIMEOUT), auditLogPort);
    }

    RemoteBizPaymentAppService(PayBizInternalApi pay, AuditLogPort auditLogPort) {
        this.pay = pay;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public List<GrantView> grants(String storeNo) {
        List<GrantView> result = unwrap(() -> pay.grants(storeNo, operator()));
        auditLogPort.record("BIZ_PAY_VIEW", storeNo, "已授权的第三方");
        return result;
    }

    @Override
    public GrantView grant(String storeNo, String appNo, Set<String> actions) {
        GrantView result = unwrap(() -> pay.grant(storeNo, new GrantReq(operator(), appNo, actions)));
        auditLogPort.record("BIZ_PAY_APP_GRANT", storeNo + "/" + appNo, String.valueOf(actions), true);
        return result;
    }

    @Override
    public void revokeGrant(String storeNo, Long id) {
        unwrap(() -> pay.revokeGrant(storeNo, id, operator()));
        auditLogPort.record("BIZ_PAY_APP_GRANT_REVOKE", storeNo + "/" + id, "撤销授权", true);
    }

    @Override
    public CollectView collect(String storeNo, Long amountMinor, String subject, Integer expireMinutes, String bizNo) {
        CollectView result = unwrap(() -> pay.collect(storeNo,
                new CollectReq(operator(), amountMinor, subject, expireMinutes, bizNo)));
        auditLogPort.record("BIZ_PAY_COLLECT", storeNo, "代客收款 " + amountMinor);
        return result;
    }

    @Override
    public ai.neargo.shop.pay.biz.PayBizFacade.BizReceipts receipts(String storeNo, String bizNo) {
        return unwrap(() -> pay.receipts(storeNo, operator(), bizNo));
    }

    @Override
    public List<QrView> qrCodes(String storeNo) {
        List<QrView> result = unwrap(() -> pay.qrCodes(storeNo, operator()));
        auditLogPort.record("BIZ_PAY_VIEW", storeNo, "收款码");
        return result;
    }

    @Override
    public QrView createQrCode(String storeNo, String kind, String tableRef, Long singleLimitMinor, Long dailyLimitMinor) {
        QrView result = unwrap(() -> pay.createQrCode(storeNo, new QrReq(operator(), kind, tableRef, singleLimitMinor, dailyLimitMinor)));
        auditLogPort.record("BIZ_PAY_QR_CREATE", storeNo, kind + (tableRef == null ? "" : " " + tableRef));
        return result;
    }

    @Override
    public void disableQrCode(String storeNo, String qrCode) {
        unwrap(() -> pay.disableQrCode(storeNo, qrCode, operator()));
        auditLogPort.record("BIZ_PAY_QR_DISABLE", storeNo + "/" + qrCode, "停用收款码");
    }

    @Override
    public RefundView requestRefund(String storeNo, String payOrderNo, Long amountMinor, String reason, String outRefundNo) {
        RefundView result = unwrap(() -> pay.requestRefund(storeNo, new RefundReq(operator(), payOrderNo, amountMinor, reason, outRefundNo)));
        auditLogPort.record("BIZ_PAY_REFUND", storeNo + "/" + payOrderNo, "原路退款 " + amountMinor, true);
        return result;
    }

    @Override
    public List<RefundView> refunds(String storeNo) {
        List<RefundView> result = unwrap(() -> pay.refunds(storeNo, operator()));
        auditLogPort.record("BIZ_PAY_VIEW", storeNo, "最近退款");
        return result;
    }

    @Override
    public DailyStatement statement(String storeNo, String day) {
        DailyStatement result = unwrap(() -> pay.statement(storeNo, operator(), day));
        auditLogPort.record("BIZ_PAY_VIEW", storeNo, "收款日流水 " + (day == null ? "今天" : day));
        return result;
    }

    @Override
    public PaymentPage paymentOrders(String storeNo, PaymentQuery query) {
        return unwrap(() -> pay.paymentOrders(storeNo, new PaymentSearchReq(operator(), query)));
    }

    @Override
    public Summary paymentSummary(String storeNo, String from, String to) {
        Summary result = unwrap(() -> pay.paymentSummary(storeNo, operator(), from, to));
        auditLogPort.record("BIZ_PAY_VIEW", storeNo, "收款汇总 " + from + "~" + to);
        return result;
    }

    @Override
    public Lookup paymentLookup(String storeNo, Long amountMinor, String within, String tradeNoSuffix) {
        return unwrap(() -> pay.paymentLookup(storeNo, operator(), amountMinor, within, tradeNoSuffix));
    }

    @Override
    public CollectStatus collectStatus(String storeNo) {
        return unwrap(() -> pay.collectStatus(storeNo, operator()));
    }

    @Override
    public Detail paymentDetail(String storeNo, String payOrderNo) {
        Detail result = unwrap(() -> pay.paymentDetail(storeNo, payOrderNo, operator()));
        auditLogPort.record("BIZ_PAY_VIEW", storeNo + "/" + payOrderNo, "收款详情");
        return result;
    }

    @Override
    public Detail paymentRefresh(String storeNo, String payOrderNo) {
        Detail result = unwrap(() -> pay.paymentRefresh(storeNo, payOrderNo, operator()));
        auditLogPort.record("BIZ_PAY_QUERY", storeNo + "/" + payOrderNo, "收款查单");
        return result;
    }

    @Override
    public LinkPage paymentLinks(String storeNo, String status, String cursor, Integer size) {
        return unwrap(() -> pay.paymentLinks(storeNo, operator(), status, cursor, size));
    }

    @Override
    public CollectView reissueLink(String storeNo, String payOrderNo) {
        CollectView result = unwrap(() -> pay.reissueLink(storeNo, payOrderNo, operator()));
        auditLogPort.record("BIZ_PAY_COLLECT", storeNo + "/" + payOrderNo, "再次分享付款链接");
        return result;
    }

    @Override
    public LinkRow voidLink(String storeNo, String payOrderNo, String reason) {
        LinkRow result = unwrap(() -> pay.voidLink(storeNo, payOrderNo, new VoidLinkReq(operator(), reason)));
        auditLogPort.record("BIZ_PAY_VOID", storeNo + "/" + payOrderNo, "作废付款链接 " + result.status()
                + (reason == null ? "" : " " + reason));
        return result;
    }

    private static String operator() {
        return SecurityUtils.currentUserNo();
    }

    private static <T> T unwrap(Supplier<PortResult<T>> call) {
        PortResult<T> r = ServiceCalls.call(ServiceName.PAY, call);
        if (r.errorCode() != null) {
            throw BizException.of(ErrorCode.valueOf(r.errorCode()), r.args() == null ? new Object[0] : r.args().toArray());
        }
        return r.value();
    }
}
