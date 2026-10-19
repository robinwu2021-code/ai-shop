package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.inventory.service.InventoryOrderTraceService;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Merchant-safe read projection for the inventory chain attached to one authorized sub-order. */
@Profile("api")
@RestController
public class BizOrderInventoryTraceController {
    private final MerchantOrderService orders;
    private final InventoryOrderTraceService inventory;

    public BizOrderInventoryTraceController(MerchantOrderService orders, InventoryOrderTraceService inventory) {
        this.orders = orders;
        this.inventory = inventory;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    @GetMapping("/biz/order/{subOrderNo}/inventory-trace")
    public InventoryOrderTraceService.InventoryOrderTrace trace(@PathVariable String subOrderNo) {
        var context = BizContext.current();
        if (context.courierOnlyOrderView()) throw new org.springframework.security.access.AccessDeniedException("ORDER_VIEW_REQUIRED");
        OrderVO order = orders.detail(context.requireMerchantNo(), context.currentStoreNo(), subOrderNo);
        return inventory.trace(order.payOrderNo());
    }
}
