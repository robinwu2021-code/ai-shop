package ai.neargo.shop.printbridge;
import ai.neargo.shop.event.*;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueReceiptConfirmed;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventCodec;
import ai.neargo.shop.trade.order.printing.BusinessPrintFactService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
/** Runs after PAY's original outbox transaction committed; freezes no mutable account reads. */
@Component
public class BusinessPrintReceiptConsumer implements OutboxConsumer {
    private final BusinessPrintFactService facts;
    private final ObjectMapper json;
    public BusinessPrintReceiptConsumer(BusinessPrintFactService facts,ObjectMapper json) { this.facts=facts; this.json=json; }
    public String handlerId() { return "business-print-receipt"; }
    public boolean supports(String type) { return "STORED_VALUE_RECEIPT_CONFIRMED".equals(type); }
    @Transactional
    public void consume(SysOutbox row) {
        var event=json.readValue(row.getPayload(),StoredValueReceiptConfirmed.class);
        PayEntitlementEventCodec.validate(event);
        if(!event.eventNo().equals(row.getEventNo()) || !event.aggregateId().equals(row.getAggregateId())
                || !event.aggregateType().equals(row.getAggregateType()) || !event.eventType().equals(row.getEventType()))
            throw new IllegalArgumentException("Receipt owner event identity mismatch");
        facts.recordRecharge(event);
    }
}
