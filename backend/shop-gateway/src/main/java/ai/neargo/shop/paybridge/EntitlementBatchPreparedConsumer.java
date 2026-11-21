package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.payclient.EntitlementBatchAppService;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementBatchPrepared;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Bounded work uses original pay cursor; retrying the event cannot select a fresh audience or reserve more capacity. */
@Component
public class EntitlementBatchPreparedConsumer implements OutboxConsumer {
    @Override public String handlerId() { return "entitlement-batch-prepared"; }
    // Original owner/action ledgers provide replay safety across PAY calls and independent core writes.
    @Override public boolean externalWorkflow() { return true; }

    private final EntitlementBatchAppService batches;
    private final ObjectMapper json;
    public EntitlementBatchPreparedConsumer(EntitlementBatchAppService batches, ObjectMapper json) {
        this.batches = batches; this.json = json;
    }
    @Override public boolean supports(String type) { return "ENTITLEMENT_BATCH_PREPARED".equals(type); }
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox event) {
        var fact = json.readValue(event.getPayload(), EntitlementBatchPrepared.class);
        var scope = new CatalogScope(fact.tenantNo(), fact.entityNo(), fact.actorNo(), false);
        var batch = batches.get(scope, fact.batchNo());
        // A frozen audience has at most 1000 entries; normal progress must not consume the outbox failure budget.
        for (int round = 0; batch.pendingCount() > 0 && round < 10; round++) {
            var next = batches.resume(scope, fact.batchNo(), 100);
            if (next.pendingCount() >= batch.pendingCount()) {
                throw new IllegalStateException("Original issuance batch cannot advance its cursor");
            }
            batch = next;
        }
        if (batch.pendingCount() != 0) throw new IllegalStateException("Original issuance batch has pending recipients");
    }
}
