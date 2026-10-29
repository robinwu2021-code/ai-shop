package ai.neargo.shop.product.service;

import ai.neargo.shop.spi.product.StoreSalesCatalogPort.PublishReq;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.Snapshot;
import java.time.Instant;

/** Trusted scope is supplied by an authenticated ingress; public projections use the read-only port. */
public interface StoreSalesCatalogService {
    Snapshot read(String entityNo, String storeNo, Instant at);

    Snapshot lockForCheckout(String buyerNo, String entityNo, String storeNo, Instant at);

    Snapshot lockForCounterCheckout(String tenant, String entity, String store, Instant at);

    Snapshot publish(String entityNo, String storeNo, String actorNo, PublishReq request, String idempotencyKey);
}
