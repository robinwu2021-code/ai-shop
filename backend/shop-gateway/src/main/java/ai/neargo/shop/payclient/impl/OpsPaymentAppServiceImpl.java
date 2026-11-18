package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.ExceptionRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.NotifyRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderDetail;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderFilter;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.RefundRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.AppRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.CredentialRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.GrantRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.KeyVersionRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.PlatformKeyRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.QrRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.RequestLogDetail;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.RequestLogFilter;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.RequestLogRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade;
import ai.neargo.shop.pay.ops.PayOpsFacade;
import ai.neargo.shop.payclient.OpsPaymentAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 内嵌形态：直调支付域（与 {@code pay-svc} 内部端点同一个 {@link PayOpsFacade} / {@link PayAccessOpsFacade}） */
@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded", matchIfMissing = true)
public class OpsPaymentAppServiceImpl implements OpsPaymentAppService {

    private final PayOpsFacade ops;
    private final PayAccessOpsFacade access;
    private final AuditLogPort auditLogPort;

    public OpsPaymentAppServiceImpl(PayOpsFacade ops, PayAccessOpsFacade access, AuditLogPort auditLogPort) {
        this.ops = ops;
        this.access = access;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public List<OrderRow> orders(OrderFilter filter, int page, int size) {
        return ops.orders(filter, page, size);
    }

    @Override
    public OrderDetail order(String payOrderNo) {
        return ops.order(payOrderNo);
    }

    @Override
    public List<RefundRow> refunds(String status, int limit) {
        return ops.refunds(status, limit);
    }

    @Override
    public RefundRow reviewRefund(String payRefundNo, boolean approve, String reason) {
        RefundRow row = ops.reviewRefund(payRefundNo, approve, reason, SecurityUtils.currentUserNo());
        auditLogPort.record("PAY_REFUND_REVIEW", payRefundNo, (approve ? "通过" : "驳回") + " → " + row.status(), true);
        return row;
    }

    @Override
    public List<ExceptionRow> exceptions(String status, int limit) {
        return ops.exceptions(status, limit);
    }

    @Override
    public void refundException(Long id) {
        ops.refundException(id, SecurityUtils.currentUserNo());
        auditLogPort.record("PAY_EXCEPTION_REFUND", String.valueOf(id), "原路退回", true);
    }

    @Override
    public void ignoreException(Long id, String note) {
        ops.ignoreException(id, SecurityUtils.currentUserNo(), note);
        auditLogPort.record("PAY_EXCEPTION_IGNORE", String.valueOf(id), note);
    }

    @Override
    public List<NotifyRow> notifies(String status, int limit) {
        return ops.notifies(status, limit);
    }

    @Override
    public void resendNotify(Long id) {
        ops.resendNotify(id, SecurityUtils.currentUserNo());
        auditLogPort.record("PAY_NOTIFY_RESEND", String.valueOf(id), "手动重推");
    }

    @Override
    public void disableQrCode(String qrCode) {
        ops.disableQrCode(qrCode, SecurityUtils.currentUserNo());
        auditLogPort.record("PAY_QR_DISABLE", qrCode, "风控停用", true);
    }

    @Override
    public List<AppRow> apps() {
        return access.apps();
    }

    @Override
    public List<GrantRow> appGrants(String appNo) {
        return access.grants(appNo);
    }

    @Override
    public List<CredentialRow> credentials(String appNo) {
        return access.credentials(appNo);
    }

    @Override
    public void revokeCredential(String appNo, String kind, int version, String reason) {
        access.revokeCredential(appNo, kind, version, reason, SecurityUtils.currentUserNo());
        auditLogPort.record("PAY_APP_CREDENTIAL_REVOKE", appNo + "/" + kind + "/v" + version, reason, true);
    }

    @Override
    public List<RequestLogRow> requestLogs(RequestLogFilter filter, int page, int size) {
        return access.requestLogs(filter, page, size);
    }

    @Override
    public RequestLogDetail requestLog(Long id) {
        return access.requestLog(id);
    }

    @Override
    public List<QrRow> qrCodes(Long storeId, String status, int page, int size) {
        return access.qrCodes(storeId, status, page, size);
    }

    @Override
    public List<PlatformKeyRow> platformKeys() {
        return access.platformKeys();
    }

    @Override
    public List<KeyVersionRow> platformKeyVersions(long keyId) {
        return access.platformKeyVersions(keyId);
    }

    @Override
    public void enablePlatformKey(long keyId, int version) {
        access.enablePlatformKey(keyId, version, SecurityUtils.currentUserNo());
        auditLogPort.record("SEC_KEY_ENABLE", keyId + "/v" + version, "启用", true);
    }

    @Override
    public void revokePlatformKey(long keyId, int version, String reason) {
        access.revokePlatformKey(keyId, version, reason, SecurityUtils.currentUserNo());
        auditLogPort.record("SEC_KEY_REVOKE", keyId + "/v" + version, reason, true);
    }
}
