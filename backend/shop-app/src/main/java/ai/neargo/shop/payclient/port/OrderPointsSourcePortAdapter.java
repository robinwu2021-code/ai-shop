package ai.neargo.shop.payclient.port;

import ai.neargo.shop.spi.points.*;

import org.springframework.stereotype.Component;

/** Pay recovery scans the original source journal through its SPI, never mutable order tables. */
@Component
public class OrderPointsSourcePortAdapter implements SourceFactsPort {
    private final OrderPointsSourcePort source;

    public OrderPointsSourcePortAdapter(OrderPointsSourcePort source) {
        this.source = source;
    }

    private org.springframework.beans.factory.ObjectProvider<ai.neargo.shop.trade.order.points.OrderPointsOutboxConsumer> delivery;
    @org.springframework.beans.factory.annotation.Autowired
    public void setDelivery(org.springframework.beans.factory.ObjectProvider<ai.neargo.shop.trade.order.points.OrderPointsOutboxConsumer> delivery) { this.delivery = delivery; }
    @Override public boolean deliver(Fact fact) {
        String event = fact.lifecycle()!=null?fact.lifecycle().eventNo():fact.earning()!=null?fact.earning().eventNo():fact.closure()!=null?fact.closure().eventNo():fact.zeroCash()!=null?fact.zeroCash().eventNo():fact.refund().eventNo();
        var original = source.find(event).orElseThrow(() -> new IllegalArgumentException("POINTS_SOURCE_EVENT_UNKNOWN"));
        if (!original.fact().equals(fact)) throw new IllegalArgumentException("POINTS_SOURCE_REPLAY_CONFLICT");
        var consumer = delivery == null ? null : delivery.getIfAvailable();
        if (consumer == null) throw new IllegalArgumentException("POINTS_SOURCE_DELIVERY_UNAVAILABLE");
        consumer.deliver(event, original.subOrderNo());
        return true;
    }

    @Override
    public Page scan(String type, long after, int limit) {
        return source.scan(type, after, limit);
    }
}
