package ai.neargo.shop.trade.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentResolutionOpsService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Profile("ops")
@RestController
public class OpsPaymentResolutionController {
    private final PaymentResolutionOpsService operations;
    private final AuditLogPort audit;

    public OpsPaymentResolutionController(PaymentResolutionOpsService operations, AuditLogPort audit) {
        this.operations = operations;
        this.audit = audit;
    }

    @GetMapping("/ops/order-payment-resolutions")
    @PreAuthorize("@perm.can('" + Perms.PAY_ORDER_VIEW + "')")
    public List<PaymentResolutionOpsService.Case> list(@RequestParam(required = false) String status) {
        return operations.list(status);
    }

    @PostMapping("/ops/order-payment-resolutions/{payNo}/{paymentNo}/refund")
    @PreAuthorize("@perm.can('" + Perms.PAY_ORDER_MANAGE + "')")
    public PaymentResolutionOpsService.Case refund(@PathVariable String payNo, @PathVariable String paymentNo) {
        audit.record("ORDER_PAYMENT_RESOLUTION_REFUND", payNo, "人工核对原支付并申请/查询原退款：" + paymentNo);
        return operations.refund(payNo, paymentNo);
    }
}
