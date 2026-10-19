package ai.neargo.shop.invbridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.service.InvManagedService;
import ai.neargo.shop.product.service.StockSyncService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

/** Read-only composition. Does not create inventory mappings or infer health from the sync switch. */
@Service
@ConditionalOnInventory
public class InventoryOverviewQuery {
    private final SkuMapper skus;
    private final InvManagedService managed;
    private final InventoryAclService acl;
    private final StockSyncService sales;
    private final MerchantQueryPort merchants;

    public InventoryOverviewQuery(SkuMapper skus, InvManagedService managed, InventoryAclService acl,
                                  StockSyncService sales, MerchantQueryPort merchants) {
        this.skus = skus;
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
        var skuRows = DataScopeContext.executeWithoutScope(() -> skus.selectList(Wrappers.<PrdSku>lambdaQuery()
                .eq(PrdSku::getEntityNo, entityNo).in(PrdSku::getSkuNo, skuNos)));
        var goods = managed.goodsOfBatch(entityNo, skuRows.stream().map(PrdSku::getGoodsNo).distinct().toList());
        java.util.Map<String, PrdSku> valid = new java.util.LinkedHashMap<>();
        skuRows.stream().filter(row -> goods.containsKey(row.getGoodsNo()))
                .forEach(row -> valid.putIfAbsent(row.getSkuNo(), row));
        if (valid.isEmpty()) return java.util.List.of();
        var tracked = managed.managedSkus(valid.keySet());
        var physical = acl.stockAtBatch(entityNo, storeNo, tracked);
        var sellables = sales.sellablesOf(storeNo, valid.keySet());
        var rules = sales.rules(storeNo);
        boolean enabled = sales.enabled(storeNo);
        return valid.values().stream().map(sku -> {
            var g = goods.get(sku.getGoodsNo());
            return render(storeNo, sku, tracked.contains(sku.getSkuNo()), physical.get(sku.getSkuNo()),
                    sellables.getOrDefault(sku.getSkuNo(), new StockSyncService.Sellable("NONE", 0, 0)),
                    StockSyncService.resolveRule(rules, storeNo, g.getGoodsNo(), g.getCategoryNo()), enabled);
        }).toList();
    }

    public Overview get(String entityNo, String storeNo, String skuNo) {
        if (storeNo == null || !merchants.storeNos(entityNo).contains(storeNo)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        PrdSku sku = DataScopeContext.executeWithoutScope(() -> skus.selectOne(Wrappers.<PrdSku>lambdaQuery()
                .eq(PrdSku::getEntityNo, entityNo).eq(PrdSku::getSkuNo, skuNo).last("LIMIT 1")));
        var goods = sku == null ? null : managed.goodsOf(entityNo, sku.getGoodsNo());
        if (goods == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        boolean tracked = managed.isManaged(goods);
        var stock = tracked ? acl.stockAt(entityNo, storeNo, skuNo) : null;
        var sellable = sales.sellableOf(storeNo, skuNo);
        var rule = sales.ruleOf(storeNo, goods.getGoodsNo(), goods.getCategoryNo());
        return render(storeNo, sku, tracked, stock, sellable, rule, sales.enabled(storeNo));
    }

    private Overview render(String storeNo, PrdSku sku, boolean tracked, InventoryAclService.StockAt stock,
                            StockSyncService.Sellable sellable, StockSyncService.Rule rule, boolean enabled) {
        return new Overview(storeNo, sku.getSkuNo(), sku.getSaleUnit(),
                !tracked ? "UNMANAGED" : stock == null ? "INITIALIZING" : "MAPPED",
                "PAYMENT_DEDUCT", stock == null ? null : stock.onHand(),
                stock == null ? null : stock.reserved(),
                stock == null ? null : Math.max(0, stock.onHand() - stock.reserved()),
                stock == null ? null : stock.safety(), stock == null ? null : stock.available(),
                sellable.sellable(), sellable.locked(), sellable.mode(), rule,
                enabled, "NOT_ASSESSED", System.currentTimeMillis());
    }
}
