package ai.neargo.shop.product.port;

import ai.neargo.shop.product.service.StoreSalesCatalogService;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Read-only shared catalog adapter; industry packages do not import product storage. */
@Component
public class StoreSalesCatalogPortImpl implements StoreSalesCatalogPort {
    private final StoreSalesCatalogService service;
    public StoreSalesCatalogPortImpl(StoreSalesCatalogService service) { this.service = service; }
    @Override
    public Snapshot lockForCheckout(String buyerNo, String entityNo, String storeNo, Instant at) {
        return service.lockForCheckout(buyerNo, entityNo, storeNo, at);
    }

    @Override
    public Snapshot lockForCounterCheckout(String tenant, String entity, String store, Instant at) {
        return service.lockForCounterCheckout(tenant, entity, store, at);
    }

    @Override
    public Snapshot read(String entityNo, String storeNo, Instant at) { return service.read(entityNo, storeNo, at); }
}
