package ai.neargo.shop.inventory.port;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import java.util.List;
import org.springframework.stereotype.Service;

/** Adapts definitions only; ACL owns entity mapping and the isolated inventory datasource. */
@Service
@ConditionalOnInventory
public class MaterialCatalogQueryPortImpl implements MaterialCatalogQueryPort {
    private final InventoryAclService inventory;

    public MaterialCatalogQueryPortImpl(InventoryAclService inventory) { this.inventory = inventory; }

    @Override
    public List<MaterialFact> getMaterials(String entityNo, List<String> itemIds) {
        MaterialCatalogQueryPort.validateLookup(entityNo, itemIds);
        return inventory.findMaterials(entityNo, itemIds).stream()
                .map(item -> new MaterialFact(item.itemId(), entityNo, item.name(), item.baseUom(), item.weighed(),
                        item.dataSource(), "BASE_ONLY", "INTEGER_ONLY", MaterialCatalogFacts.definitionHash(
                                item.itemId(), entityNo, item.name(), item.baseUom(), item.weighed(),
                                item.dataSource())))
                .toList();
    }
}
