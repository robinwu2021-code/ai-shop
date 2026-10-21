package ai.neargo.shop.product.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.ServiceCatalogFactsInternalApi;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Composes owner-approved directory and quote facts without copying merchant or product storage. */
@Component
public class ServiceCatalogFactsPortImpl implements ServiceCatalogFactsPort {
    private final StoreDirectoryPort stores;
    private final ServiceProductFactsPort services;
    private final GoodsQueryPort quotes;
    private final StoreSalesCatalogPort catalogs;
    public ServiceCatalogFactsPortImpl(StoreDirectoryPort stores, ServiceProductFactsPort services,
            GoodsQueryPort quotes, StoreSalesCatalogPort catalogs) {
        this.stores = stores;
        this.services = services;
        this.quotes = quotes;
        this.catalogs = catalogs;
    }
    @Override
    public Store store(String storeNo) {
        new ServiceCatalogFactsInternalApi.StoreLookup(storeNo);
        var card = stores.cards(List.of(storeNo)).get(storeNo);
        if (card == null || !storeNo.equals(card.storeNo())
                || !(StoreDirectoryPort.STORE_ACTIVE.equals(card.status())
                || StoreDirectoryPort.STORE_READONLY.equals(card.status()))) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return new Store(card.entityNo(), card.storeNo(), card.storeName(), card.logo(), card.status(),
                card.openHours(), card.address(), catalogs.read(card.entityNo(), card.storeNo(), Instant.now()));
    }
    @Override
    public Service service(String storeNo, String skuNo) {
        new ServiceCatalogFactsInternalApi.ServiceLookup(storeNo, skuNo);
        var store = store(storeNo);
        if (!StoreDirectoryPort.STORE_ACTIVE.equals(store.status())) { throw BizException.of(ErrorCode.NOT_FOUND); }
        if (store.catalog().configured() && store.catalog().definition().visibleEntry(skuNo) == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        var fact = services.get(store.entityNo(), store.storeNo(), skuNo);
        var quote = quotes.snapshot(List.of(skuNo), Map.of(store.entityNo(), store.storeNo())).get(skuNo);
        if (fact == null || quote == null || !quote.onSale() || !store.entityNo().equals(fact.entityNo())
                || !store.storeNo().equals(fact.storeNo()) || !skuNo.equals(fact.skuNo())
                || !fact.goodsNo().equals(quote.goodsNo()) || !fact.entityNo().equals(quote.merchantNo())
                || !fact.storeNo().equals(quote.storeNo()) || !fact.skuNo().equals(quote.skuNo())
                || fact.durationMin() < 1 || quote.price() < 0) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return new Service(store, fact.skuNo(), fact.goodsNo(), quote.title(), quote.cover(),
                quote.price(), fact.durationMin());
    }
}
