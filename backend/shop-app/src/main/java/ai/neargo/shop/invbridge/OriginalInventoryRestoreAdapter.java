package ai.neargo.shop.invbridge;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import ai.neargo.shop.inventory.service.OriginalReservationRestoreService;
import ai.neargo.shop.inventory.service.OriginalReservationRestoreService.ItemQty;
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

    private ReservationMapper reservations;
    private TransactionTemplate reads;

    @Autowired
    public void setOriginalBoundary(ReservationMapper reservations,
            @Qualifier("invTransactionManager") PlatformTransactionManager manager) {
        this.reservations = reservations;
        this.reads = new TransactionTemplate(manager);
        this.reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A tracked Inventory origin without a frozen routing envelope must never fall through to generic restoration. */
    public boolean hasTrackedOriginal(String originalRef) {
        return Boolean.TRUE.equals(reads.execute(status -> reservations.lockOriginal(originalRef).stream()
                .anyMatch(row -> row.getSourceManifestHash() != null)));
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
