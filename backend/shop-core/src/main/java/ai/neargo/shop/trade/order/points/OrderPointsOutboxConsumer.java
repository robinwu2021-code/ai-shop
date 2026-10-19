package ai.neargo.shop.trade.order.points;

import ai.neargo.shop.event.*;
import ai.neargo.shop.spi.points.OrderPointsSourceEvent;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
public class OrderPointsOutboxConsumer implements OutboxConsumer {
    private final OrderPointsFactService journal;
    private final OrderPointsCoordinator coordinator;
    private final ObjectMapper json = new ObjectMapper();

    public OrderPointsOutboxConsumer(
            OrderPointsFactService journal, OrderPointsCoordinator coordinator) {
        this.journal = journal;
        this.coordinator = coordinator;
    }

    @Override public String handlerId() { return "order-points-source"; }
    @Override public boolean externalWorkflow() { return true; }

    @Override
    public boolean supports(String type) {
        return OrderPointsSourceEvent.TYPE.equals(type);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(
            value = "transactionManager",
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox transport) {
        var reference = json.readValue(transport.getPayload(), OrderPointsSourceEvent.class);
        deliver(reference.eventNo(), reference.subOrderNo());
    }

    public void deliver(String eventNo, String subOrderNo) {
        var event =
                journal.find(eventNo)
                        .orElseThrow(
                                () -> new IllegalArgumentException("POINTS_SOURCE_EVENT_UNKNOWN"));
        if (!event.subOrderNo().equals(subOrderNo))
            throw new IllegalArgumentException("POINTS_SOURCE_REFERENCE_CONFLICT");
        coordinator.onSourceEvent(event);
        journal.acknowledge(eventNo);
    }
}
