package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.DomainEvent;

/** Upload the already committed payment queue row now; the scheduled job remains compensation. */
public record WxShippingUploadRequested(String orderNo) implements DomainEvent {
    public static final String TYPE = "WX_SHIPPING_UPLOAD_REQUESTED";
    @Override public String aggregateType() { return "ORDER"; }
    @Override public String aggregateId() { return orderNo; }
    @Override public String eventType() { return TYPE; }
}
