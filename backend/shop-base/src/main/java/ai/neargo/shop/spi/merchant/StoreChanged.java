package ai.neargo.shop.spi.merchant;

import ai.neargo.shop.event.DomainEvent;

/**
 * 门店（或其商户）展示信息发生变化（门店信息同步设计 §5）。只带门店号与修订号；
 * 消费方按当前值组装快照，事件晚到也不会回退（近端按修订单调应用）。
 */
public record StoreChanged(String aggregateId, String entityNo, long revision) implements DomainEvent {
    public static final String TYPE = "MERCHANT_STORE_CHANGED";

    @Override
    public String aggregateType() { return "MERCHANT_STORE"; }

    @Override
    public String eventType() { return TYPE; }
}
