package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.payclient.OrderPointsIntegrationAppService;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.*;
import ai.neargo.shop.trade.order.points.OrderPointsRecoveryService.Inspection;
import ai.neargo.shop.trade.dto.OpsAfterSaleVO;
import ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.ManualPage;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Profile("ops")
@RestController
public class OpsOrderPointsIntegrationController {
    private final OrderPointsIntegrationAppService service;

    public OpsOrderPointsIntegrationController(OrderPointsIntegrationAppService service) {
        this.service = service;
    }

    @GetMapping("/ops/points/order-integration")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public IntegrationPolicy policy(@RequestParam String storeNo, @RequestParam String scene) {
        return service.policy(storeNo, scene);
    }

    @GetMapping("/ops/points/order-integration/obligations")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public ai.neargo.shop.trade.order.points.OrderPointsCoordinator.StoreObligations obligations(@RequestParam String storeNo) {
        return service.obligations(storeNo);
    }

    @PostMapping("/ops/points/order-integration")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public IntegrationPolicy change(@RequestBody IntegrationPolicyChange request) {
        return service.change(request, SecurityUtils.currentUserNo());
    }

    @GetMapping("/ops/points/order-integration/inspection")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public Inspection inspect(@RequestParam String subOrderNo) {
        return service.inspect(subOrderNo);
    }

    @GetMapping("/ops/points/order-integration/refunds/manual")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public ManualPage manualRefunds(@RequestParam String storeNo,
            @RequestParam(defaultValue = "") String after,
            @RequestParam(defaultValue = "20") int limit) {
        return service.manualRefunds(storeNo, after, limit);
    }

    @PostMapping("/ops/points/order-integration/refunds/{afterSaleNo}/close")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public OpsAfterSaleVO closeRefund(@PathVariable String afterSaleNo, @RequestBody CloseRefundReq request) {
        return service.closeCompensatedRefund(afterSaleNo, SecurityUtils.currentUserNo(), request.reason());
    }

    @PostMapping("/ops/points/order-integration/refunds/{afterSaleNo}/retry")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public OpsAfterSaleVO retryRefund(@PathVariable String afterSaleNo, @RequestBody RetryRefundReq request) {
        return service.retryCompensatedRefund(afterSaleNo, SecurityUtils.currentUserNo(), request.reason());
    }

    public record CloseRefundReq(String reason) {}
    public record RetryRefundReq(String reason) {}
}
