package ai.neargo.shop.inventory.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.InventoryValuationService;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("api")
@ConditionalOnInventory
public class BizInventoryValuationController {
    private final InventoryValuationService service;
    private final InventoryAclService acl;

    public BizInventoryValuationController(
            InventoryValuationService service, InventoryAclService acl) {
        this.service = service;
        this.acl = acl;
    }

    @GetMapping("/biz/inventory/items/{item}/valuation")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    public InventoryValuationService.Policy get(@PathVariable String item) {
        return service.get(acl.ownerIdOf(BizContext.requireMerchantNo()), item);
    }

    @PostMapping("/biz/inventory/items/{item}/valuation")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    public InventoryValuationService.Policy configure(
            @PathVariable String item,
            @RequestBody InventoryValuationService.Policy req,
            @RequestHeader("Idempotency-Key") String key) {
        return service.configure(
                acl.ownerIdOf(BizContext.requireMerchantNo()),
                item,
                SecurityUtils.currentUserNo(),
                req,
                key);
    }
}
