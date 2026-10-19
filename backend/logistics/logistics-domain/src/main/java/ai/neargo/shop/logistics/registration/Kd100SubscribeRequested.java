package ai.neargo.shop.logistics.registration;

import ai.neargo.shop.event.DomainEvent;

/** Independent outbox event for Kuaidi100 /poll; never suppressed by WeChat success. */
public record Kd100SubscribeRequested(String shipmentNo, String identityKey) implements DomainEvent {
    public static final String TYPE = "LGS_KD100_SUBSCRIBE_REQUESTED";
    @Override public String aggregateType() { return "WAYBILL"; }
    @Override public String aggregateId() { return shipmentNo; }
    @Override public String eventType() { return TYPE; }
}
