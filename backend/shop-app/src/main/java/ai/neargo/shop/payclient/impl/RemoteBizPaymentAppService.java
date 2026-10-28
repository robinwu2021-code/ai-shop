package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.service.PayStatementService.DailyStatement;
import ai.neargo.shop.pay.biz.PayBizFacade.CollectView;
import ai.neargo.shop.pay.biz.PayBizFacade.QrView;
import ai.neargo.shop.pay.biz.PayBizFacade.RefundView;
import ai.neargo.shop.pay.client.PayBizInternalApi;
import ai.neargo.shop.pay.client.PayBizInternalApi.CollectReq;
import ai.neargo.shop.pay.client.PayBizInternalApi.GrantReq;
import ai.neargo.shop.pay.client.PayBizInternalApi.QrReq;
import ai.neargo.shop.pay.client.PayBizInternalApi.RefundReq;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.pay.openapp.service.AppGrantService.GrantView;
import ai.neargo.shop.payclient.BizPaymentAppService;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
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

    public RemoteBizPaymentAppService(InternalHttp http) {
        this(http.client(ServiceName.PAY, PayBizInternalApi.class, READ_TIMEOUT));
    }

    RemoteBizPaymentAppService(PayBizInternalApi pay) {
        this.pay = pay;
    }

    @Override
    public List<GrantView> grants(String storeNo) {
        return unwrap(() -> pay.grants(storeNo, operator()));
    }

    @Override
    public GrantView grant(String storeNo, String appNo, Set<String> actions) {
        return unwrap(() -> pay.grant(storeNo, new GrantReq(operator(), appNo, actions)));
    }

    @Override
    public void revokeGrant(String storeNo, Long id) {
        unwrap(() -> pay.revokeGrant(storeNo, id, operator()));
    }

    @Override
    public CollectView collect(String storeNo, Long amountMinor, String subject, Integer expireMinutes) {
        return unwrap(() -> pay.collect(storeNo, new CollectReq(operator(), amountMinor, subject, expireMinutes)));
    }

    @Override
    public List<QrView> qrCodes(String storeNo) {
        return unwrap(() -> pay.qrCodes(storeNo, operator()));
    }

    @Override
    public QrView createQrCode(String storeNo, String kind, String tableRef, Long singleLimitMinor, Long dailyLimitMinor) {
        return unwrap(() -> pay.createQrCode(storeNo, new QrReq(operator(), kind, tableRef, singleLimitMinor, dailyLimitMinor)));
    }

    @Override
    public void disableQrCode(String storeNo, String qrCode) {
        unwrap(() -> pay.disableQrCode(storeNo, qrCode, operator()));
    }

    @Override
    public RefundView requestRefund(String storeNo, String payOrderNo, Long amountMinor, String reason, String outRefundNo) {
        return unwrap(() -> pay.requestRefund(storeNo, new RefundReq(operator(), payOrderNo, amountMinor, reason, outRefundNo)));
    }

    @Override
    public List<RefundView> refunds(String storeNo) {
        return unwrap(() -> pay.refunds(storeNo, operator()));
    }

    @Override
    public DailyStatement statement(String storeNo, String day) {
        return unwrap(() -> pay.statement(storeNo, operator(), day));
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
