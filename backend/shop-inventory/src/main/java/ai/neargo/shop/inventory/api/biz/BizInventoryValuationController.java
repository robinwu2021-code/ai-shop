package ai.neargo.shop.inventory.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.InventoryValuationService;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ai.neargo.shop.inventory.route.InventoryConsoleRoute;
import static ai.neargo.inventory.client.InventoryConsole.params;

@RestController
@Profile("api")
@ConditionalOnInventory
public class BizInventoryValuationController {
    private final InventoryValuationService service;
    private final InventoryAclService acl;

    /** 切流（M8）：已翻到库存服务的商家，转给服务的 /console，返回结构相同 */
    private final InventoryConsoleRoute route;

    public BizInventoryValuationController(
            InventoryValuationService service, InventoryAclService acl, InventoryConsoleRoute route) {
        this.service = service;
        this.acl = acl;
        this.route = route;
    }

    @GetMapping("/biz/inventory/items/{item}/valuation")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    public InventoryValuationService.Policy get(@PathVariable String item) {
        if (route.remote()) return route.via(c -> c.get("/items/" + item + "/valuation", params(), InventoryValuationService.Policy.class));
        return service.get(acl.ownerIdOf(BizContext.requireMerchantNo()), item);
    }

    @PostMapping("/biz/inventory/items/{item}/valuation")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    public InventoryValuationService.Policy configure(
            @PathVariable String item,
            @RequestBody InventoryValuationService.Policy req,
            @RequestHeader("Idempotency-Key") String key) {
        if (route.remote()) return route.via(c -> c.post("/items/" + item + "/valuation", params("key", key), req,
                InventoryValuationService.Policy.class));
        return service.configure(
                acl.ownerIdOf(BizContext.requireMerchantNo()),
                item,
                SecurityUtils.currentUserNo(),
                req,
                key);
    }
}
