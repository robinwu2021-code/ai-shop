package ai.neargo.shop.invbridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.shop.product.service.InvManagedService;
import ai.neargo.shop.product.service.StockSyncService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.product.InventoryProductPort;
import ai.neargo.shop.spi.product.InventoryProductPort.SkuFact;
import org.springframework.stereotype.Service;

/** Read-only composition. Does not create inventory mappings or infer health from the sync switch. */
@Service
@ConditionalOnInventory
public class InventoryOverviewQuery {
    private final InventoryProductPort products;
    private final InvManagedService managed;
    private final InventoryAclService acl;
    private final StockSyncService sales;
    private final MerchantQueryPort merchants;

    public InventoryOverviewQuery(InventoryProductPort products, InvManagedService managed, InventoryAclService acl,
                                  StockSyncService sales, MerchantQueryPort merchants) {
        this.products = products;
        this.managed = managed;
        this.acl = acl;
        this.sales = sales;
        this.merchants = merchants;
    }

    public record Overview(String storeNo, String skuNo, String unit, String inventoryState,
                           String accountingMode, Integer onHand, Integer reserved, Integer available,
                           Integer safetyStock, Integer supplyAvailable, int onlineSellable, int onlineLocked,
                           String salesStockMode, StockSyncService.Rule ruleSource, boolean syncEnabled,
                           String syncHealth, long asOf) { }

    /** Bounded, read-only batch. Preserve the same ownership checks as the single lookup. */
    public java.util.List<Overview> batch(String entityNo, String storeNo, java.util.List<String> skuNos) {
        if (storeNo == null || !merchants.storeNos(entityNo).contains(storeNo)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (skuNos == null || skuNos.size() > 500 || skuNos.stream().anyMatch(k -> k == null || k.isBlank())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (skuNos.isEmpty()) return java.util.List.of();
        var skuRows = products.skus(entityNo, skuNos);
        var goods = products.goodsOfBatch(entityNo, skuRows.stream().map(SkuFact::goodsNo).distinct().toList());
        java.util.Map<String, SkuFact> valid = new java.util.LinkedHashMap<>();
        skuRows.stream().filter(row -> goods.containsKey(row.goodsNo()))
                .forEach(row -> valid.putIfAbsent(row.skuNo(), row));
        if (valid.isEmpty()) return java.util.List.of();
        var tracked = managed.managedSkus(valid.keySet());
        var physical = acl.stockAtBatch(entityNo, storeNo, tracked);
        var sellables = sales.sellablesOf(storeNo, valid.keySet());
        var rules = sales.rules(storeNo);
        boolean enabled = sales.enabled(storeNo);
        return valid.values().stream().map(sku -> {
            var g = goods.get(sku.goodsNo());
            return render(storeNo, sku, tracked.contains(sku.skuNo()), physical.get(sku.skuNo()),
                    sellables.getOrDefault(sku.skuNo(), new StockSyncService.Sellable("NONE", 0, 0)),
                    StockSyncService.resolveRule(rules, storeNo, g.goodsNo(), g.categoryNo()), enabled);
        }).toList();
    }

    public Overview get(String entityNo, String storeNo, String skuNo) {
        if (storeNo == null || !merchants.storeNos(entityNo).contains(storeNo)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        SkuFact sku = products.skus(entityNo, java.util.List.of(skuNo)).stream().findFirst().orElse(null);
        var goods = sku == null ? null : products.goodsOf(entityNo, sku.goodsNo());
        if (goods == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        boolean tracked = products.isManaged(entityNo, goods.goodsNo());
        var stock = tracked ? acl.stockAt(entityNo, storeNo, skuNo) : null;
        var sellable = sales.sellableOf(storeNo, skuNo);
        var rule = sales.ruleOf(storeNo, goods.goodsNo(), goods.categoryNo());
        return render(storeNo, sku, tracked, stock, sellable, rule, sales.enabled(storeNo));
    }

    private Overview render(String storeNo, SkuFact sku, boolean tracked, InventoryAclService.StockAt stock,
                            StockSyncService.Sellable sellable, StockSyncService.Rule rule, boolean enabled) {
        return new Overview(storeNo, sku.skuNo(), sku.saleUnit(),
                !tracked ? "UNMANAGED" : stock == null ? "INITIALIZING" : "MAPPED",
                "PAYMENT_DEDUCT", stock == null ? null : stock.onHand(),
                stock == null ? null : stock.reserved(),
                stock == null ? null : Math.max(0, stock.onHand() - stock.reserved()),
                stock == null ? null : stock.safety(), stock == null ? null : stock.available(),
                sellable.sellable(), sellable.locked(), sellable.mode(), rule,
                enabled, "NOT_ASSESSED", System.currentTimeMillis());
    }
}
