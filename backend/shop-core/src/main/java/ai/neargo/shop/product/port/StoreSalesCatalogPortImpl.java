package ai.neargo.shop.product.port;

import ai.neargo.shop.product.service.StoreSalesCatalogService;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Read-only shared catalog adapter; industry packages do not import product storage. */
@Component
public class StoreSalesCatalogPortImpl implements StoreSalesCatalogPort {
    private final StoreSalesCatalogService service;
    public StoreSalesCatalogPortImpl(StoreSalesCatalogService service) { this.service = service; }
    @Override
    public Snapshot read(String entityNo, String storeNo, Instant at) { return service.read(entityNo, storeNo, at); }
}
