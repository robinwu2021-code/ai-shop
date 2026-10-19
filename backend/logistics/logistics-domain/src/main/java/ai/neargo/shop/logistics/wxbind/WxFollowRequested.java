package ai.neargo.shop.logistics.wxbind;

import ai.neargo.shop.event.DomainEvent;

/** Independent outbox event: the buyer's WeChat logistics-message subscription. */
public record WxFollowRequested(String shipmentNo, String identityKey) implements DomainEvent {
    public static final String TYPE = "LGS_WX_FOLLOW_REQUESTED";

    @Override public String aggregateType() { return "WAYBILL"; }
    @Override public String aggregateId() { return shipmentNo; }
    @Override public String eventType() { return TYPE; }
}
