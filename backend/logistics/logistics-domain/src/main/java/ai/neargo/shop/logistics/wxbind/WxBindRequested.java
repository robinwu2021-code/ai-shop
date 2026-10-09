package ai.neargo.shop.logistics.wxbind;

import ai.neargo.shop.event.DomainEvent;

/** 物流内部事件：这张运单该去微信换 token 了。只有物流自己消费（失败重试不波及别人）。 */
public record WxBindRequested(String shipmentNo) implements DomainEvent {

    public static final String TYPE = "LGS_WX_BIND_REQUESTED";

    @Override
    public String aggregateType() {
        return "WAYBILL";
    }

    @Override
    public String aggregateId() {
        return shipmentNo;
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
