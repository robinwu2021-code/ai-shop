package ai.neargo.shop.food.port;

import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.CatalogImageContracts;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/** Lazily resolves every original catalog operation, including SKU identity. */
public final class LazyGoodsCatalogQueryPortImpl implements GoodsCatalogQueryPort {
    private final ObjectProvider<GoodsCatalogQueryPort> provider;

    public LazyGoodsCatalogQueryPortImpl(ObjectProvider<GoodsCatalogQueryPort> provider) {
        this.provider = provider;
    }

    @Override
    public CatalogImageContracts.Facts resolveCatalogImages(CatalogImageContracts.Query query) {
        return provider.getObject().resolveCatalogImages(query);
    }

    @Override
    public SkuIdentityFact getSku(String entity, String store, String sku) {
        return provider.getObject().getSku(entity, store, sku);
    }

    @Override
    public GoodsFact getGoods(String entity, String store, String number) {
        return provider.getObject().getGoods(entity, store, number);
    }

    @Override
    public List<GoodsSummary> getGoodsBatch(String entity, String store, List<String> numbers) {
        return provider.getObject().getGoodsBatch(entity, store, numbers);
    }
}
