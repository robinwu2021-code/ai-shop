package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.payclient.StoredValuePlanRechargeAppService;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.promotion.dto.StoredValueRechargePrepared;
import ai.neargo.shop.promotion.service.RechargeIntentService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueCredited;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueRechargeCancelled;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueRechargeReceived;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Original-operation recovery. Deadlines and absence never permit release of funds or marketing budget. */
@Component
public class StoredValueRechargeEventConsumer implements OutboxConsumer {
    private final StoredValueRechargeCoordinator coordinator;
    private final RechargeIntentService intents;
    private final ObjectMapper json;
    private final StoredValuePlanRechargeAppService direct;
    public StoredValueRechargeEventConsumer(StoredValueRechargeCoordinator coordinator, RechargeIntentService intents,
                                            ObjectMapper json, StoredValuePlanRechargeAppService direct) {
        this.coordinator = coordinator; this.intents = intents; this.json = json; this.direct=direct;
    }
    @Override public boolean supports(String type) {
        return Set.of("STORED_VALUE_RECHARGE_PREPARED", "STORED_VALUE_RECHARGE_RECEIVED",
                "STORED_VALUE_RECHARGE_CANCELLED", "STORED_VALUE_CREDITED").contains(type);
    }
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox event) {
        String tenant, entity, no, expected;
        switch (event.getEventType()) {
            case "STORED_VALUE_RECHARGE_PREPARED" -> {
                var e = json.readValue(event.getPayload(), StoredValueRechargePrepared.class);
                var p = intents.byRechargeNo(e.tenantNo(), e.rechargeNo());
                if (!p.intentNo().equals(e.intentNo()) || !p.permit().entityNo().equals(e.entityNo())) {
                    throw new IllegalArgumentException("Recharge preparation binding mismatch");
                }
                tenant = e.tenantNo(); entity = e.entityNo(); no = e.rechargeNo(); expected = null;
            }
            case "STORED_VALUE_RECHARGE_RECEIVED" -> {
                var e = json.readValue(event.getPayload(), StoredValueRechargeReceived.class);
                tenant = e.tenantNo(); entity = e.entityNo(); no = e.rechargeNo(); expected = "CREDITED";
            }
            case "STORED_VALUE_RECHARGE_CANCELLED" -> {
                var e = json.readValue(event.getPayload(), StoredValueRechargeCancelled.class);
                tenant = e.tenantNo(); entity = e.entityNo(); no = e.rechargeNo(); expected = "CANCELLED";
            }
            case "STORED_VALUE_CREDITED" -> {
                var e = json.readValue(event.getPayload(), StoredValueCredited.class);
                tenant = "MAIN"; entity = e.entityNo(); no = e.rechargeNo(); expected = "CREDITED";
            }
            default -> throw new IllegalArgumentException("Unsupported recharge recovery event");
        }
        var ctx = new EntitlementContext(tenant, "OUTBOX", no, "0".repeat(64));
        if (direct.recover(ctx, entity, no)) {
            var result=direct.query(ctx,entity,no);
            if (expected!=null && !expected.equals(result.status().name())) {
                throw new IllegalStateException("Direct recharge recovery is not acknowledged");
            }
            return;
        }
        var result = coordinator.recover(ctx, entity, no);
        if ("PROCESSING".equals(result.status()) || expected != null && !expected.equals(result.status())) {
            throw new IllegalStateException("Original recharge recovery is not acknowledged");
        }
    }
}
