package ai.neargo.shop.inventory.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.mapper.InventoryExactSupportMapper;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Adapts definitions only; ACL owns entity mapping and the isolated inventory datasource. */
@Service
@ConditionalOnInventory
public class MaterialCatalogQueryPortImpl implements MaterialCatalogQueryPort {
    private final InventoryAclService inventory;
    private InventoryExactSupportMapper exactMapper;

    @Autowired
    public void configureExactDatabase(InventoryExactSupportMapper mapper) {
        exactMapper = mapper;
    }

    public MaterialCatalogQueryPortImpl(InventoryAclService inventory) {
        this.inventory = inventory;
    }

    @Override
    public List<SkuMaterialFact> getSkuMaterials(
            String entityNo, String storeNo, List<String> skuNos) {
        MaterialCatalogQueryPort.validateLookup(entityNo, skuNos);
        if (!MaterialCatalogFacts.validKey(storeNo, 32)
                || inventory.findOwnerId(entityNo) == null
                || inventory.stockLocationOfExistingStore(entityNo, storeNo) == null)
            throw BizException.of(ErrorCode.FORBIDDEN);
        String owner = inventory.findOwnerId(entityNo);
        var found = new ArrayList<SkuMaterialFact>();
        for (var sku : skuNos) {
            var item = inventory.itemIdOfSku(sku);
            if (item == null || !sku.equals(inventory.skuOfItem(owner, item))) continue;
            var facts = getMaterials(entityNo, List.of(item));
            if (facts.size() == 1) {
                StockQuantity quantity = null;
                if (exactMapper != null) {
                    var balances =
                            exactMapper.stock(
                                    owner,
                                    item,
                                    inventory.stockLocationOfExistingStore(entityNo, storeNo));
                    if (balances.isEmpty())
                        quantity =
                                new StockQuantity(
                                        BigDecimal.ZERO,
                                        BigDecimal.ZERO,
                                        BigDecimal.ZERO,
                                        0L,
                                        "KNOWN");
                    else {
                        var row = balances.getFirst();
                        var onHand = row.onHand();
                        var reserved = row.reserved();
                        quantity =
                                new StockQuantity(
                                        onHand,
                                        reserved,
                                        onHand.subtract(reserved),
                                        row.version(),
                                        "KNOWN");
                    }
                }
                found.add(new SkuMaterialFact(sku, facts.getFirst(), quantity));
            }
        }
        return List.copyOf(found);
    }

    @Override
    public List<MaterialFact> getMaterials(String entityNo, List<String> itemIds) {
        MaterialCatalogQueryPort.validateLookup(entityNo, itemIds);
        return inventory.findMaterials(entityNo, itemIds).stream()
                .map(
                        item ->
                                new MaterialFact(
                                        item.itemId(),
                                        entityNo,
                                        item.name(),
                                        item.baseUom(),
                                        item.weighed(),
                                        item.dataSource(),
                                        "BASE_ONLY",
                                        "INTEGER_ONLY",
                                        MaterialCatalogFacts.definitionHash(
                                                item.itemId(),
                                                entityNo,
                                                item.name(),
                                                item.baseUom(),
                                                item.weighed(),
                                                item.dataSource())))
                .toList();
    }
}
