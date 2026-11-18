package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
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
import ai.neargo.shop.payclient.OpsPaymentAppService;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付运营（开发计划 M5a.2；pay-guard TDD §2.8）：ops-web 的「支付运营」模块只调这里 —— 运营会话 + 平台权限码（与其它运营端
 * 同一套账号体系），再经 {@link OpsPaymentAppService} 调支付。取代直连支付的 {@code /pay-ops/v1}（过渡一个版本后删，M5a.6）。
 * 权限码与原端点逐个对应。
 */
@Profile("ops")
@RestController
public class OpsPaymentController {

    private final OpsPaymentAppService app;

    public OpsPaymentController(OpsPaymentAppService app) {
        this.app = app;
    }

    public record ReviewReq(Boolean approve, String reason) {
    }

    public record IgnoreReq(String note) {
    }

    @GetMapping("/ops/payments/orders")
    @PreAuthorize("@perm.can('" + Perms.PAY_ORDER_VIEW + "')")
    public List<OrderRow> orders(@RequestParam(required = false) String status,
                                 @RequestParam(required = false) String payOrderNo,
                                 @RequestParam(required = false) String outOrderNo,
                                 @RequestParam(required = false) Long storeId,
                                 @RequestParam(required = false) String bizType,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return app.orders(new OrderFilter(status, payOrderNo, outOrderNo, storeId, bizType), page, size);
    }

    @GetMapping("/ops/payments/orders/{payOrderNo}")
    @PreAuthorize("@perm.can('" + Perms.PAY_ORDER_VIEW + "')")
    public OrderDetail order(@PathVariable String payOrderNo) {
        return app.order(payOrderNo);
    }

    @GetMapping("/ops/payments/refunds")
    @PreAuthorize("@perm.can('" + Perms.PAY_REFUND_VIEW + "')")
    public List<RefundRow> refunds(@RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "50") int limit) {
        return app.refunds(status, limit);
    }

    @PostMapping("/ops/payments/refunds/{payRefundNo}/review")
    @PreAuthorize("@perm.can('" + Perms.PAY_REFUND_REVIEW + "')")
    public RefundRow review(@PathVariable String payRefundNo, @RequestBody ReviewReq req) {
        return app.reviewRefund(payRefundNo, Boolean.TRUE.equals(req.approve()), req.reason());
    }

    @GetMapping("/ops/payments/exceptions")
    @PreAuthorize("@perm.can('" + Perms.PAY_ORDER_VIEW + "')")
    public List<ExceptionRow> exceptions(@RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "50") int limit) {
        return app.exceptions(status, limit);
    }

    @PostMapping("/ops/payments/exceptions/{id}/refund")
    @PreAuthorize("@perm.can('" + Perms.PAY_ORDER_MANAGE + "')")
    public void refundException(@PathVariable Long id) {
        app.refundException(id);
    }

    @PostMapping("/ops/payments/exceptions/{id}/ignore")
    @PreAuthorize("@perm.can('" + Perms.PAY_ORDER_MANAGE + "')")
    public void ignoreException(@PathVariable Long id, @RequestBody IgnoreReq req) {
        app.ignoreException(id, req.note());
    }

    @GetMapping("/ops/payments/notifies")
    @PreAuthorize("@perm.can('" + Perms.PAY_LOG_VIEW + "')")
    public List<NotifyRow> notifies(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "50") int limit) {
        return app.notifies(status, limit);
    }

    @PostMapping("/ops/payments/notifies/{id}/resend")
    @PreAuthorize("@perm.can('" + Perms.PAY_NOTIFY_RESEND + "')")
    public void resendNotify(@PathVariable Long id) {
        app.resendNotify(id);
    }

    @PutMapping("/ops/payments/qr-codes/{qrCode}/disabled")
    @PreAuthorize("@perm.can('" + Perms.PAY_QR_MANAGE + "')")
    public void disableQrCode(@PathVariable String qrCode) {
        app.disableQrCode(qrCode);
    }

    // ---- 接入方与授权、接入方密钥（M5b.1）：只看指纹，明文不出现 ----

    public record RevokeReq(String reason) {
    }

    @GetMapping("/ops/payments/apps")
    @PreAuthorize("@perm.can('" + Perms.PAY_APP_MANAGE + "')")
    public List<AppRow> apps() {
        return app.apps();
    }

    @GetMapping("/ops/payments/apps/{appNo}/grants")
    @PreAuthorize("@perm.can('" + Perms.PAY_APP_MANAGE + "')")
    public List<GrantRow> appGrants(@PathVariable String appNo) {
        return app.appGrants(appNo);
    }

    @GetMapping("/ops/payments/apps/{appNo}/credentials")
    @PreAuthorize("@perm.can('" + Perms.PAY_APP_MANAGE + "')")
    public List<CredentialRow> credentials(@PathVariable String appNo) {
        return app.credentials(appNo);
    }

    @PostMapping("/ops/payments/apps/{appNo}/credentials/{kind}/{version}/revoke")
    @PreAuthorize("@perm.can('" + Perms.PAY_APP_CREDENTIAL_REVOKE + "')")
    public void revokeCredential(@PathVariable String appNo, @PathVariable String kind, @PathVariable int version,
                                 @RequestBody RevokeReq req) {
        app.revokeCredential(appNo, kind, version, req.reason());
    }

    // ---- 请求记录（M5b.2）：含内部调用来源，按追踪号串两边 ----

    @GetMapping("/ops/payments/request-logs")
    @PreAuthorize("@perm.can('" + Perms.PAY_LOG_VIEW + "')")
    public List<RequestLogRow> requestLogs(@RequestParam(required = false) String entry,
                                           @RequestParam(required = false) String appNo,
                                           @RequestParam(required = false) String sourceService,
                                           @RequestParam(required = false) String traceId,
                                           @RequestParam(required = false) String outcome,
                                           @RequestParam(required = false) Long fromMs,
                                           @RequestParam(required = false) Long toMs,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return app.requestLogs(new RequestLogFilter(entry, appNo, sourceService, traceId, outcome, fromMs, toMs), page, size);
    }

    @GetMapping("/ops/payments/request-logs/{id}")
    @PreAuthorize("@perm.can('" + Perms.PAY_LOG_VIEW + "')")
    public RequestLogDetail requestLog(@PathVariable Long id) {
        return app.requestLog(id);
    }

    // ---- 静态收款码（M5b.4）：列表；停用见上 ----

    @GetMapping("/ops/payments/qr-codes")
    @PreAuthorize("@perm.can('" + Perms.PAY_QR_MANAGE + "')")
    public List<QrRow> qrCodes(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return app.qrCodes(storeId, status, page, size);
    }

    // ---- 平台证书与密钥（M5b.3）：启用两人（密钥模块强制） ----

    @GetMapping("/ops/payments/platform-keys")
    @PreAuthorize("@perm.can('" + Perms.SEC_KEY_VIEW + "')")
    public List<PlatformKeyRow> platformKeys() {
        return app.platformKeys();
    }

    @GetMapping("/ops/payments/platform-keys/{keyId}/versions")
    @PreAuthorize("@perm.can('" + Perms.SEC_KEY_VIEW + "')")
    public List<KeyVersionRow> platformKeyVersions(@PathVariable long keyId) {
        return app.platformKeyVersions(keyId);
    }

    @PostMapping("/ops/payments/platform-keys/{keyId}/versions/{version}/enable")
    @PreAuthorize("@perm.can('" + Perms.SEC_KEY_ENABLE + "')")
    public void enablePlatformKey(@PathVariable long keyId, @PathVariable int version) {
        app.enablePlatformKey(keyId, version);
    }

    @PostMapping("/ops/payments/platform-keys/{keyId}/versions/{version}/revoke")
    @PreAuthorize("@perm.can('" + Perms.SEC_KEY_WRITE + "')")
    public void revokePlatformKey(@PathVariable long keyId, @PathVariable int version, @RequestBody RevokeReq req) {
        app.revokePlatformKey(keyId, version, req.reason());
    }
}
