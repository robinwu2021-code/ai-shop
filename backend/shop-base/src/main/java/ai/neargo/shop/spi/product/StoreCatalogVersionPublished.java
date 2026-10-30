package ai.neargo.shop.spi.product;

import ai.neargo.shop.event.DomainEvent;

/** Immutable catalog version evidence; does not request delivery or grant financial authority. */
public record StoreCatalogVersionPublished(String aggregateId, String entityNo, String storeNo,
                                          String catalogVersionNo, long revision, String snapshotHash)
        implements DomainEvent {
    @Override
    public String aggregateType() { return "PRODUCT_STORE_CATALOG"; }
    @Override
    public String eventType() { return "PRODUCT_STORE_CATALOG_VERSION_PUBLISHED"; }
}
