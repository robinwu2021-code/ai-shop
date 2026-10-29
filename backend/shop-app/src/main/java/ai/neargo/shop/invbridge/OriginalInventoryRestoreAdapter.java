package ai.neargo.shop.invbridge;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.OriginalReservationRestoreService;
import ai.neargo.inventory.domain.service.OriginalReservationRestoreService.ItemQty;
import ai.neargo.shop.spi.product.StockPort.SkuQty;
import java.util.List;
import org.springframework.stereotype.Component;

/** SKU identity translation only; warehouse and quantity authority come from the original posting. */
@Component
@ConditionalOnInventory
public class OriginalInventoryRestoreAdapter {
    private final InventoryAclService acl;
    private final OriginalReservationRestoreService restores;

    public OriginalInventoryRestoreAdapter(InventoryAclService acl, OriginalReservationRestoreService restores) {
        this.acl = acl;
        this.restores = restores;
    }

    public void restore(String purpose, String originalRef, List<SkuQty> items) {
        restores.restore(purpose, originalRef, translate(items));
    }

    public boolean completed(String purpose, String originalRef, List<SkuQty> items) {
        return restores.completed(purpose, originalRef, translate(items));
    }

    private List<ItemQty> translate(List<SkuQty> items) {
        return items.stream().map(item -> new ItemQty(acl.itemIdOfSku(item.skuNo()), item.qty())).toList();
    }
}
