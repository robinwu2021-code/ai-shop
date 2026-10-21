package ai.neargo.shop.product.service;

import ai.neargo.shop.spi.product.StoreSalesCatalogPort.Snapshot;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.PublishReq;
import java.time.Instant;

/** Trusted scope is supplied by an authenticated ingress; public projections use the read-only port. */
public interface StoreSalesCatalogService {
    Snapshot read(String entityNo, String storeNo, Instant at);
    Snapshot publish(String entityNo, String storeNo, String actorNo, PublishReq request, String idempotencyKey);
}
