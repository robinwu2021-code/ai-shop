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
import ai.neargo.shop.pay.client.PayOpsInternalApi.OperatorReq;
import ai.neargo.shop.pay.client.PayOpsInternalApi;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.AppRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.CredentialRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.GrantRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.KeyVersionRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.PlatformKeyRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.QrRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.RequestLogDetail;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.RequestLogFilter;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.RequestLogRow;
import ai.neargo.shop.payclient.OpsPaymentAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AuditLogPort auditLogPort;

    @Autowired
    public RemoteOpsPaymentAppService(InternalHttp http, AuditLogPort auditLogPort) {
        this(http.client(ServiceName.PAY, PayOpsInternalApi.class, READ_TIMEOUT), auditLogPort);
    }

    RemoteOpsPaymentAppService(PayOpsInternalApi pay, AuditLogPort auditLogPort) {
        this.pay = pay;
        this.auditLogPort = auditLogPort;
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
        RefundRow row = unwrap(() -> pay.reviewRefund(payRefundNo, new OperatorReq(operator(), approve, reason, null)));
        auditLogPort.record("PAY_REFUND_REVIEW", payRefundNo, (approve ? "通过" : "驳回") + " → " + row.status(), true);
        return row;
    }

    @Override
    public List<ExceptionRow> exceptions(String status, int limit) {
        return unwrap(() -> pay.exceptions(status, limit));
    }

    @Override
    public void refundException(Long id) {
        unwrap(() -> pay.refundException(id, new OperatorReq(operator(), null, null, null)));
        auditLogPort.record("PAY_EXCEPTION_REFUND", String.valueOf(id), "原路退回", true);
    }

    @Override
    public void ignoreException(Long id, String note) {
        unwrap(() -> pay.ignoreException(id, new OperatorReq(operator(), null, null, note)));
        auditLogPort.record("PAY_EXCEPTION_IGNORE", String.valueOf(id), note);
    }

    @Override
    public List<NotifyRow> notifies(String status, int limit) {
        return unwrap(() -> pay.notifies(status, limit));
    }

    @Override
    public void resendNotify(Long id) {
        unwrap(() -> pay.resendNotify(id, new OperatorReq(operator(), null, null, null)));
        auditLogPort.record("PAY_NOTIFY_RESEND", String.valueOf(id), "手动重推");
    }

    @Override
    public void disableQrCode(String qrCode) {
        unwrap(() -> pay.disableQrCode(qrCode, new OperatorReq(operator(), null, null, null)));
        auditLogPort.record("PAY_QR_DISABLE", qrCode, "风控停用", true);
    }

    @Override
    public List<AppRow> apps() {
        return unwrap(pay::apps);
    }

    @Override
    public List<GrantRow> appGrants(String appNo) {
        return unwrap(() -> pay.appGrants(appNo));
    }

    @Override
    public List<CredentialRow> credentials(String appNo) {
        return unwrap(() -> pay.credentials(appNo));
    }

    @Override
    public void revokeCredential(String appNo, String kind, int version, String reason) {
        unwrap(() -> pay.revokeCredential(appNo, kind, version, new OperatorReq(operator(), null, reason, null)));
        auditLogPort.record("PAY_APP_CREDENTIAL_REVOKE", appNo + "/" + kind + "/v" + version, reason, true);
    }

    @Override
    public List<RequestLogRow> requestLogs(RequestLogFilter f, int page, int size) {
        return unwrap(() -> pay.requestLogs(f.entry(), f.appNo(), f.sourceService(), f.traceId(), f.outcome(), f.fromMs(),
                f.toMs(), page, size));
    }

    @Override
    public RequestLogDetail requestLog(Long id) {
        return unwrap(() -> pay.requestLog(id));
    }

    @Override
    public List<QrRow> qrCodes(Long storeId, String status, int page, int size) {
        return unwrap(() -> pay.qrCodes(storeId, status, page, size));
    }

    @Override
    public List<PlatformKeyRow> platformKeys() {
        return unwrap(pay::platformKeys);
    }

    @Override
    public List<KeyVersionRow> platformKeyVersions(long keyId) {
        return unwrap(() -> pay.platformKeyVersions(keyId));
    }

    @Override
    public void enablePlatformKey(long keyId, int version) {
        unwrap(() -> pay.enablePlatformKey(keyId, version, new OperatorReq(operator(), null, null, null)));
        auditLogPort.record("SEC_KEY_ENABLE", keyId + "/v" + version, "启用", true);
    }

    @Override
    public void revokePlatformKey(long keyId, int version, String reason) {
        unwrap(() -> pay.revokePlatformKey(keyId, version, new OperatorReq(operator(), null, reason, null)));
        auditLogPort.record("SEC_KEY_REVOKE", keyId + "/v" + version, reason, true);
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
