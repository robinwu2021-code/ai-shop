package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.promotion.dto.ActivityBenefitDecided;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** At-least-once delivery completes the original persisted bundle; it never manufactures another claim. */
@Component
public class ActivityBenefitDecisionConsumer implements OutboxConsumer {
    @Override public String handlerId() { return "activity-benefit-decision"; }
    // Original owner/action ledgers provide replay safety across PAY calls and independent core writes.
    @Override public boolean externalWorkflow() { return true; }

    private final ActivityBenefitCoordinator coordinator;
    private final ObjectMapper json;
    public ActivityBenefitDecisionConsumer(ActivityBenefitCoordinator coordinator, ObjectMapper json) {
        this.coordinator = coordinator; this.json = json;
    }
    @Override public boolean supports(String type) { return "ACTIVITY_BENEFIT_DECIDED".equals(type); }
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox event) {
        var fact = json.readValue(event.getPayload(), ActivityBenefitDecided.class);
        var context = new EntitlementContext(fact.tenantNo(), fact.userNo(), fact.intentNo(), "0".repeat(64));
        var result = coordinator.recover(context, fact.intentNo());
        if (!result.status().equals("COMMITTED") && !result.status().equals("ABORTED")) {
            throw new IllegalStateException("Original activity benefit bundle remains unresolved");
        }
    }
}
