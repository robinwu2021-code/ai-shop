package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.payclient.OrderPointsIntegrationAppService;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.*;
import ai.neargo.shop.trade.order.points.OrderPointsRecoveryService.Inspection;

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
}
