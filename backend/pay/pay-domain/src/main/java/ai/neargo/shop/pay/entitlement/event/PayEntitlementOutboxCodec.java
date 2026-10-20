package ai.neargo.shop.pay.entitlement.event;

import ai.neargo.shop.pay.entitlement.entity.PayEntOutbox;
import ai.neargo.shop.spi.entitlement.PayEntitlementEvent;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventCodec;
import tools.jackson.databind.ObjectMapper;

/** Validate persisted immutable facts before delivery or assigning a merchant scope. */
public final class PayEntitlementOutboxCodec {
    private PayEntitlementOutboxCodec() { }
    public static PayEntitlementEvent decode(PayEntOutbox row,ObjectMapper json) {
        var event=PayEntitlementEventCodec.read(row.getEventType(),row.getPayload(),json);
        PayEntitlementEventCodec.validate(event);
        String canonical=json.writeValueAsString(event);
        if (!"MAIN".equals(row.getTenantNo()) || !event.eventNo().equals(row.getEventNo())
                || event.schemaVersion()!=row.getSchemaVersion() || !event.eventType().equals(row.getEventType())
                || !event.aggregateId().equals(row.getAggregateNo())
                || !json.readTree(canonical).equals(json.readTree(row.getPayload()))
                || !PayEntitlementEventCodec.hash(event,canonical).equals(row.getPayloadHash())) {
            throw new IllegalStateException("Pay entitlement event payload does not match its immutable header");
        }
        return event;
    }
}
