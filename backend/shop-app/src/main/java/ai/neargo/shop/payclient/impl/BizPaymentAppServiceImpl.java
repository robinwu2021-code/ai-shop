package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.acquiring.service.PayStatementService.DailyStatement;
import ai.neargo.shop.pay.biz.PayBizFacade.CollectView;
import ai.neargo.shop.pay.biz.PayBizFacade.QrView;
import ai.neargo.shop.pay.biz.PayBizFacade.RefundView;
import ai.neargo.shop.pay.biz.PayBizFacade;
import ai.neargo.shop.pay.openapp.service.AppGrantService.GrantView;
import ai.neargo.shop.payclient.BizPaymentAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 内嵌形态：直调支付域（与 {@code pay-svc} 内部端点同一个 {@link PayBizFacade}） */
@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded", matchIfMissing = true)
public class BizPaymentAppServiceImpl implements BizPaymentAppService {

    private final PayBizFacade biz;
    private final AuditLogPort auditLogPort;

    public BizPaymentAppServiceImpl(PayBizFacade biz, AuditLogPort auditLogPort) {
        this.biz = biz;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public List<GrantView> grants(String storeNo) {
        List<GrantView> result = biz.grants(storeNo, operator());
        auditLogPort.record("BIZ_PAY_VIEW", storeNo, "已授权的第三方");
        return result;
    }

    @Override
    public GrantView grant(String storeNo, String appNo, Set<String> actions) {
        GrantView result = biz.grant(storeNo, operator(), appNo, actions);
        auditLogPort.record("BIZ_PAY_APP_GRANT", storeNo + "/" + appNo, String.valueOf(actions), true);
        return result;
    }

    @Override
    public void revokeGrant(String storeNo, Long id) {
        biz.revokeGrant(storeNo, operator(), id);
        auditLogPort.record("BIZ_PAY_APP_GRANT_REVOKE", storeNo + "/" + id, "撤销授权", true);
    }

    @Override
    public CollectView collect(String storeNo, Long amountMinor, String subject, Integer expireMinutes) {
        CollectView result = biz.collect(storeNo, operator(), amountMinor, subject, expireMinutes);
        auditLogPort.record("BIZ_PAY_COLLECT", storeNo, "代客收款 " + amountMinor);
        return result;
    }

    @Override
    public List<QrView> qrCodes(String storeNo) {
        List<QrView> result = biz.qrCodes(storeNo, operator());
        auditLogPort.record("BIZ_PAY_VIEW", storeNo, "收款码");
        return result;
    }

    @Override
    public QrView createQrCode(String storeNo, String kind, String tableRef, Long singleLimitMinor, Long dailyLimitMinor) {
        QrView result = biz.createQrCode(storeNo, operator(), kind, tableRef, singleLimitMinor, dailyLimitMinor);
        auditLogPort.record("BIZ_PAY_QR_CREATE", storeNo, kind + (tableRef == null ? "" : " " + tableRef));
        return result;
    }

    @Override
    public void disableQrCode(String storeNo, String qrCode) {
        biz.disableQrCode(storeNo, operator(), qrCode);
        auditLogPort.record("BIZ_PAY_QR_DISABLE", storeNo + "/" + qrCode, "停用收款码");
    }

    @Override
    public RefundView requestRefund(String storeNo, String payOrderNo, Long amountMinor, String reason, String outRefundNo) {
        RefundView result = biz.requestRefund(storeNo, operator(), payOrderNo, amountMinor, reason, outRefundNo);
        auditLogPort.record("BIZ_PAY_REFUND", storeNo + "/" + payOrderNo, "原路退款 " + amountMinor, true);
        return result;
    }

    @Override
    public List<RefundView> refunds(String storeNo) {
        List<RefundView> result = biz.refunds(storeNo, operator());
        auditLogPort.record("BIZ_PAY_VIEW", storeNo, "最近退款");
        return result;
    }

    @Override
    public DailyStatement statement(String storeNo, String day) {
        DailyStatement result = biz.statement(storeNo, operator(), day);
        auditLogPort.record("BIZ_PAY_VIEW", storeNo, "收款日流水 " + (day == null ? "今天" : day));
        return result;
    }

    private static String operator() {
        return SecurityUtils.currentUserNo();
    }
}
