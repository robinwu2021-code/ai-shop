package ai.neargo.shop.spi.beauty;

import ai.neargo.shop.event.DomainEvent;

/** Version-reference event only; no care records, financial authority or customer identity is disclosed. */
public record BeautyPolicyVersionPublished(String aggregateId, String entityNo, String storeNo,
                                          String policyVersionNo, long revision, String snapshotHash)
        implements DomainEvent {
    @Override
    public String aggregateType() { return "BEAUTY_STORE_POLICY"; }

    @Override
    public String eventType() { return "BEAUTY_STORE_POLICY_VERSION_PUBLISHED"; }
}
