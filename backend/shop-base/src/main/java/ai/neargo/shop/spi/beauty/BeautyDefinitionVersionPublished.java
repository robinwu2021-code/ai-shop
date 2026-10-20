package ai.neargo.shop.spi.beauty;

import ai.neargo.shop.event.DomainEvent;

/** Version-reference event only; no care records, financial authority or customer identity is disclosed. */
public record BeautyDefinitionVersionPublished(String aggregateId, String entityNo, String storeNo,
                                          String definitionVersionNo, long revision, String snapshotHash)
        implements DomainEvent {
    @Override
    public String aggregateType() { return "BEAUTY_SERVICE_DEFINITION"; }

    @Override
    public String eventType() { return "BEAUTY_SERVICE_DEFINITION_VERSION_PUBLISHED"; }
}
