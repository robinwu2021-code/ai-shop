package ai.neargo.shop.logistics.event;

import ai.neargo.shop.event.DomainEvent;

/**
 * 物流内部事件：一张运单登记了（或需要重新取快照 / 补订阅）。<b>只有物流自己消费</b>。
 *
 * <p>为什么要多这一跳：发货事件 {@code SUB_ORDER_SHIPPED} 还有通知模块在消费，而派发器把一条事件
 * 依次交给所有消费者、任何一个抛异常整条重投 —— 前面成功的消费者会再跑一遍（「已发货」再推一次）。
 * 所以发货事件那一端只登记骨架、几乎不可能失败；取快照、调渠道这些会失败的事放到这条只属于物流的事件上，
 * 重试只重试物流自己。
 */
public record WaybillRegistered(String shipmentNo) implements DomainEvent {

    public static final String TYPE = "LGS_WAYBILL_REGISTERED";

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
