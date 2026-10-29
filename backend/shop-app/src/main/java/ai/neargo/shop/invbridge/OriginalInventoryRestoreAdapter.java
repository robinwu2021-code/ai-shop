package ai.neargo.shop.invbridge;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
    private AfterSaleMapper afterSales;

    @Autowired
    public void setOriginalBoundary(ReservationMapper reservations,
            @Qualifier("invTransactionManager") PlatformTransactionManager manager) {
        this.reservations = reservations;
        this.reads = new TransactionTemplate(manager);
        this.reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Autowired
    public void setLegacyOriginalLookup(AfterSaleMapper afterSales) {
        this.afterSales = afterSales;
    }

    /** Old RESTORE envelopes identify a saved AfterSale, never an order guessed from current SKU ownership. */
    public void restoreLegacy(String purpose, List<SkuQty> items) {
        if (afterSales == null) {
            throw new IllegalStateException("STOCK_RETURN_SOURCE_UNAVAILABLE");
        }
        var originals = DataScopeContext.executeWithoutScope(() -> afterSales.selectList(
                Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getAfterSaleNo, purpose)));
        if (originals.size() != 1 || originals.getFirst().getOrderNo() == null) {
            throw new IllegalStateException("STOCK_RETURN_SOURCE_UNAVAILABLE");
        }
        String originalRef = originals.getFirst().getOrderNo();
        if (hasTrackedOriginal(originalRef)) {
            restores.restoreSourceSkus(purpose, originalRef, items);
        } else {
            // Actual saved order and real original reservation still bound the old untracked mapping/cap.
            restores.restore(purpose, originalRef, translate(items));
        }
    }

    /** A tracked Inventory origin without a frozen routing envelope must never fall through to generic restoration. */
    public boolean hasTrackedOriginal(String originalRef) {
        return Boolean.TRUE.equals(reads.execute(status -> reservations.lockOriginal(originalRef).stream()
                .anyMatch(row -> row.getSourceManifestHash() != null)));
    }

    public void restore(String purpose, String originalRef, List<SkuQty> items) {
        restores.restoreSourceSkus(purpose, originalRef, items);
    }

    public boolean completed(String purpose, String originalRef, List<SkuQty> items) {
        return restores.completedSourceSkus(purpose, originalRef, items);
    }

    private List<ItemQty> translate(List<SkuQty> items) {
        return items.stream().map(item -> new ItemQty(acl.itemIdOfSku(item.skuNo()), item.qty())).toList();
    }
}
