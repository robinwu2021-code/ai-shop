package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.ExceptionRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.NotifyRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderDetail;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderFilter;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.RefundRow;
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
}
