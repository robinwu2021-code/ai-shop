package ai.neargo.shop.inventory.api.biz;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.inventory.dto.InventoryVOs;
import ai.neargo.shop.inventory.entity.InvLocation;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.LocationService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端 · 库位与仓（s09）。
 *
 * <p>读用 {@code biz:stock}，**建仓与改发货源用 {@code biz:store:admin}** ——
 * 建仓是门店级配置，与「改库存」是两拨人。
 */
@Profile("api")
@ConditionalOnInventory
@RestController
public class BizLocationController {

    private final LocationService locations;
    private final InventoryAclService acl;

    public BizLocationController(LocationService locations, InventoryAclService acl) {
        this.locations = locations;
        this.acl = acl;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/inventory/locations")
    public List<InvLocation> list() {
        return locations.list(owner());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/inventory/locations")
    public InventoryVOs.DocNoVO createWarehouse(@RequestBody WarehouseReq req) {
        return new InventoryVOs.DocNoVO(
                locations.createWarehouse(owner(), req.name(), SecurityUtils.currentUserNo()));
    }

    /** 设发货源。**链式指向在 Service 里被拦住** —— 环与「货从哪出」都从那里起。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PutMapping("/biz/inventory/locations/{id}/source")
    public void setSource(@PathVariable String id, @RequestBody SourceReq req) {
        locations.setSource(owner(), id, req.sourceLocationId(), SecurityUtils.currentUserNo());
    }

    public record WarehouseReq(String name) {
    }

    public record SourceReq(String sourceLocationId) {
    }

    private String owner() {
        return acl.ownerIdOf(BizContext.requireMerchantNo());
    }
}
