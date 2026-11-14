package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.service.InventoryOrderTraceService;
import ai.neargo.shop.invbridge.InventoryTraceSyncProjection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only operations view joining inventory facts with their platform outbox delivery state. */
@Profile("ops")
@RestController
@ConditionalOnInventory
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public class OpsInventoryOrderTraceController {
    private final InventoryTraceSyncProjection trace;

    public OpsInventoryOrderTraceController(InventoryTraceSyncProjection trace) {
        this.trace = trace;
    }

    @PreAuthorize("@perm.can('" + Perms.INVENTORY_STOCK_READ + "')")
    @GetMapping("/ops/inventory/order-trace")
    public InventoryOrderTraceService.InventoryOrderTrace orderTrace(@RequestParam String orderNo) {
        return trace.trace(orderNo);
    }
}
