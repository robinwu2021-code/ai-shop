package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.acquiring.service.PayStatementService.DailyStatement;
import ai.neargo.shop.pay.biz.PayBizFacade;
import ai.neargo.shop.pay.biz.PayBizFacade.CollectView;
import ai.neargo.shop.pay.biz.PayBizFacade.QrView;
import ai.neargo.shop.pay.biz.PayBizFacade.RefundView;
import ai.neargo.shop.pay.openapp.service.AppGrantService.GrantView;
import ai.neargo.shop.payclient.BizPaymentAppService;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 内嵌形态：直调支付域（与 {@code pay-svc} 内部端点同一个 {@link PayBizFacade}） */
@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded", matchIfMissing = true)
public class BizPaymentAppServiceImpl implements BizPaymentAppService {

    private final PayBizFacade biz;

    public BizPaymentAppServiceImpl(PayBizFacade biz) {
        this.biz = biz;
    }

    @Override
    public List<GrantView> grants(String storeNo) {
        return biz.grants(storeNo, operator());
    }

    @Override
    public GrantView grant(String storeNo, String appNo, Set<String> actions) {
        return biz.grant(storeNo, operator(), appNo, actions);
    }

    @Override
    public void revokeGrant(String storeNo, Long id) {
        biz.revokeGrant(storeNo, operator(), id);
    }

    @Override
    public CollectView collect(String storeNo, Long amountMinor, String subject, Integer expireMinutes) {
        return biz.collect(storeNo, operator(), amountMinor, subject, expireMinutes);
    }

    @Override
    public List<QrView> qrCodes(String storeNo) {
        return biz.qrCodes(storeNo, operator());
    }

    @Override
    public QrView createQrCode(String storeNo, String kind, String tableRef, Long singleLimitMinor, Long dailyLimitMinor) {
        return biz.createQrCode(storeNo, operator(), kind, tableRef, singleLimitMinor, dailyLimitMinor);
    }

    @Override
    public void disableQrCode(String storeNo, String qrCode) {
        biz.disableQrCode(storeNo, operator(), qrCode);
    }

    @Override
    public RefundView requestRefund(String storeNo, String payOrderNo, Long amountMinor, String reason, String outRefundNo) {
        return biz.requestRefund(storeNo, operator(), payOrderNo, amountMinor, reason, outRefundNo);
    }

    @Override
    public List<RefundView> refunds(String storeNo) {
        return biz.refunds(storeNo, operator());
    }

    @Override
    public DailyStatement statement(String storeNo, String day) {
        return biz.statement(storeNo, operator(), day);
    }

    private static String operator() {
        return SecurityUtils.currentUserNo();
    }
}
