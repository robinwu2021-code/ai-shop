package ai.neargo.shop.food.port;

import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/** Preserves the metadata-only adapter and the contract default for unsupported SKU identity lookup. */
public final class LazyGoodsMetadataQueryPortImpl implements GoodsCatalogQueryPort {
    private final ObjectProvider<GoodsCatalogQueryPort> provider;

    public LazyGoodsMetadataQueryPortImpl(ObjectProvider<GoodsCatalogQueryPort> provider) {
        this.provider = provider;
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
