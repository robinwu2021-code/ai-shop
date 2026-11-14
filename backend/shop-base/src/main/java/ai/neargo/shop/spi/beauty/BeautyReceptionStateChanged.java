package ai.neargo.shop.spi.beauty;

import ai.neargo.shop.event.DomainEvent;

/** Public event contains only business state; private identity and credential evidence never leaves its owner. */
public record BeautyReceptionStateChanged(String businessNo, String entityNo, String storeNo,
        long revision, String state) implements DomainEvent {
    @Override public String aggregateId() { return businessNo; }
    @Override public String aggregateType() { return "BEAUTY_RECEPTION"; }
    @Override public String eventType() { return "BEAUTY_RECEPTION_STATE_CHANGED"; }
}
