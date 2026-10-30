package ai.neargo.shop.invbridge;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.OriginalReservationRestoreService;
import ai.neargo.inventory.domain.service.OriginalReservationRestoreService.ItemQty;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ReservationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
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
        String originalRef = originalOrderOf(purpose);
        if (hasTrackedOriginal(originalRef)) {
            restores.restoreSourceSkus(purpose, originalRef, source(items));
        } else {
            // Actual saved order and real original reservation still bound the old untracked mapping/cap.
            restores.restore(purpose, originalRef, translate(items));
        }
    }

    /** 售后单 → 原订单号（按原单回补的依据）；找不到唯一一张就拒 */
    public String originalOrderOf(String purpose) {
        if (afterSales == null) {
            throw new IllegalStateException("STOCK_RETURN_SOURCE_UNAVAILABLE");
        }
        var originals = DataScopeContext.executeWithoutScope(() -> afterSales.selectList(
                Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getAfterSaleNo, purpose)));
        if (originals.size() != 1 || originals.getFirst().getOrderNo() == null) {
            throw new IllegalStateException("STOCK_RETURN_SOURCE_UNAVAILABLE");
        }
        return originals.getFirst().getOrderNo();
    }

    /** A tracked Inventory origin without a frozen routing envelope must never fall through to generic restoration. */
    public boolean hasTrackedOriginal(String originalRef) {
        return Boolean.TRUE.equals(reads.execute(status -> reservations.lockOriginal(originalRef).stream()
                .anyMatch(row -> row.getSourceManifestHash() != null)));
    }

    public void restore(String purpose, String originalRef, List<SkuQty> items) {
        restores.restoreSourceSkus(purpose, originalRef, source(items));
    }

    public boolean completed(String purpose, String originalRef, List<SkuQty> items) {
        return restores.completedSourceSkus(purpose, originalRef, source(items));
    }

    private List<ItemQty> translate(List<SkuQty> items) {
        return items.stream().map(item -> new ItemQty(acl.itemIdOfSku(item.skuNo()), item.qty())).toList();
    }

    /** 商城的来源行 → 库存领域的同形记录（ADR-046：领域层不认识商城 SPI） */
    private static List<ai.neargo.inventory.domain.support.SourceStock.Qty> source(List<SkuQty> items) {
        return items == null ? null : items.stream().map(q -> new ai.neargo.inventory.domain.support.SourceStock.Qty(
                q.skuNo(), q.qty(), q.storeNo(), q.fulfillmentRef(), q.lifecycleVersion())).toList();
    }
}
