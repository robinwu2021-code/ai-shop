package ai.neargo.shop.food.port;

import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;

import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

/** Resolve both original material reads lazily without dropping the SKU/store contract. */
public final class LazyMaterialCatalogQueryPortImpl implements MaterialCatalogQueryPort {
    private final ObjectProvider<MaterialCatalogQueryPort> provider;

    public LazyMaterialCatalogQueryPortImpl(ObjectProvider<MaterialCatalogQueryPort> provider) {
        this.provider = provider;
    }

    @Override
    public List<MaterialFact> getMaterials(String entityNo, List<String> itemIds) {
        return provider.getObject().getMaterials(entityNo, itemIds);
    }

    @Override
    public List<SkuMaterialFact> getSkuMaterials(
            String entityNo, String storeNo, List<String> skuNos) {
        return provider.getObject().getSkuMaterials(entityNo, storeNo, skuNos);
    }
}
