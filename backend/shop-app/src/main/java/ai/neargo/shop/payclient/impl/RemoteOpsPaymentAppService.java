package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.ExceptionRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.NotifyRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderDetail;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderFilter;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.RefundRow;
import ai.neargo.shop.pay.client.PayOpsInternalApi;
import ai.neargo.shop.pay.client.PayOpsInternalApi.OperatorReq;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.payclient.OpsPaymentAppService;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.svc.client.ServiceCalls;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 独立形态：经 {@code /internal/pay/v1/ops/**} 调支付（开发计划 M5a.3）。操作人取当前登录的运营员工号，显式传过去 ——
 * 支付侧只记、不认人（ADR-045 G9）；业务拒绝原样还原成 {@link BizException}。
 */
@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteOpsPaymentAppService implements OpsPaymentAppService {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final PayOpsInternalApi pay;

    public RemoteOpsPaymentAppService(InternalHttp http) {
        this(http.client(ServiceName.PAY, PayOpsInternalApi.class, READ_TIMEOUT));
    }

    RemoteOpsPaymentAppService(PayOpsInternalApi pay) {
        this.pay = pay;
    }

    @Override
    public List<OrderRow> orders(OrderFilter f, int page, int size) {
        return unwrap(() -> pay.orders(f.status(), f.payOrderNo(), f.outOrderNo(), f.storeId(), f.bizType(), page, size));
    }

    @Override
    public OrderDetail order(String payOrderNo) {
        return unwrap(() -> pay.order(payOrderNo));
    }

    @Override
    public List<RefundRow> refunds(String status, int limit) {
        return unwrap(() -> pay.refunds(status, limit));
    }

    @Override
    public RefundRow reviewRefund(String payRefundNo, boolean approve, String reason) {
        return unwrap(() -> pay.reviewRefund(payRefundNo, new OperatorReq(operator(), approve, reason, null)));
    }

    @Override
    public List<ExceptionRow> exceptions(String status, int limit) {
        return unwrap(() -> pay.exceptions(status, limit));
    }

    @Override
    public void refundException(Long id) {
        unwrap(() -> pay.refundException(id, new OperatorReq(operator(), null, null, null)));
    }

    @Override
    public void ignoreException(Long id, String note) {
        unwrap(() -> pay.ignoreException(id, new OperatorReq(operator(), null, null, note)));
    }

    @Override
    public List<NotifyRow> notifies(String status, int limit) {
        return unwrap(() -> pay.notifies(status, limit));
    }

    @Override
    public void resendNotify(Long id) {
        unwrap(() -> pay.resendNotify(id, new OperatorReq(operator(), null, null, null)));
    }

    @Override
    public void disableQrCode(String qrCode) {
        unwrap(() -> pay.disableQrCode(qrCode, new OperatorReq(operator(), null, null, null)));
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
