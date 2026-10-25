package ai.neargo.shop.paybridge;
import ai.neargo.shop.event.*;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.LegacyCouponIssueCompleted;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
@Component
public class LegacyCouponIssueCompletedConsumer implements OutboxConsumer {
    @Override public String handlerId() { return "legacy-coupon-issue-completed"; }
    // Original owner/action ledgers provide replay safety across PAY calls and independent core writes.
    @Override public boolean externalWorkflow() { return true; }

    private final LegacyCouponIssueCoordinator coordinator;
    private final ObjectMapper json;
    public LegacyCouponIssueCompletedConsumer(LegacyCouponIssueCoordinator coordinator,ObjectMapper json) {this.coordinator=coordinator;this.json=json;}
    public boolean supports(String type) {return "LEGACY_COUPON_ISSUE_COMPLETED".equals(type);}
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox event) {
        var fact=json.readValue(event.getPayload(),LegacyCouponIssueCompleted.class);
        coordinator.recover(new CatalogScope(fact.tenantNo(),fact.entityNo(),fact.actorNo(),false),fact.issueNo());
    }
}
