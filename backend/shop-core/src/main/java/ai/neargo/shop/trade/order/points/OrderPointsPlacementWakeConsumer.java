package ai.neargo.shop.trade.order.points;

import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.points.OrderPointsPlacementWakeEvent;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

/** Outbox dispatch holds a source transaction; suspend it around quote and pay calls. */
@Component
public class OrderPointsPlacementWakeConsumer implements OutboxConsumer {
    private final PointsOrderPlacementCoordinator placement;
    private final ObjectMapper json = new ObjectMapper();

    public OrderPointsPlacementWakeConsumer(PointsOrderPlacementCoordinator placement) {
        this.placement = placement;
    }

    @Override public String handlerId() { return "order-points-placement"; }
    @Override public boolean externalWorkflow() { return true; }

    @Override
    public boolean supports(String type) {
        return OrderPointsPlacementWakeEvent.TYPE.equals(type);
    }

    @Override
    @Transactional(value = "transactionManager", propagation = Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox transport) {
        var wake = json.readValue(transport.getPayload(), OrderPointsPlacementWakeEvent.class);
        var state = placement.resume(wake.orderNo());
        if (state != null
                && (state.phase() == PointsOrderPlacementCoordinator.Phase.UNKNOWN
                        || state.phase() == PointsOrderPlacementCoordinator.Phase.ADMITTED
                        || state.phase()
                                == PointsOrderPlacementCoordinator.Phase.CANCEL_REQUESTED)) {
            throw new RetryableOutboxException("POINTS_PLACEMENT_RETRY_REQUIRED");
        }
    }
}
