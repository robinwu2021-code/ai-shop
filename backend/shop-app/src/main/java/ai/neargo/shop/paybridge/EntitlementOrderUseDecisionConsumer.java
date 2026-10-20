package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOrderEvents.UseDecided;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Outbox retry only replays the original source operation; it never replaces an unknown hold. */
@Component
public class EntitlementOrderUseDecisionConsumer implements OutboxConsumer {
    private final EntitlementOrderCoordinator coordinator;
    private final ObjectMapper json;
    public EntitlementOrderUseDecisionConsumer(EntitlementOrderCoordinator coordinator, ObjectMapper json) {
        this.coordinator = coordinator; this.json = json;
    }
    @Override public boolean supports(String type) {
        return "ENTITLEMENT_ORDER_USE_DECIDED".equals(type) || "ENTITLEMENT_ORDER_USE_PREPARED".equals(type);
    }
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox event) {
        var fact = json.readValue(event.getPayload(), UseDecided.class);
        var ctx = new EntitlementContext(fact.tenantNo(), "OUTBOX", fact.operationNo(), "0".repeat(64));
        var result = coordinator.recover(ctx, fact.entityNo(), fact.operationNo());
        boolean prepared = "ENTITLEMENT_ORDER_USE_PREPARED".equals(event.getEventType());
        if (prepared && result.status() == State.HELD) return;
        if (result.status() != State.COMMITTED && result.status() != State.RELEASED) {
            throw new IllegalStateException("Original entitlement use is not terminal");
        }
    }
}
