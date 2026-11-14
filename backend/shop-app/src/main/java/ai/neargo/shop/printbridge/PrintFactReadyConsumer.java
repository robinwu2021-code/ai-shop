package ai.neargo.shop.printbridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import ai.neargo.shop.event.*;
import ai.neargo.shop.print.api.*;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@ConditionalOnProperty(
        name = "shop.print.enabled",
        havingValue = "true")
@Component
public class PrintFactReadyConsumer implements OutboxConsumer {
    private final PrintFacade service;
    private final ObjectMapper json;

    public PrintFactReadyConsumer(PrintFacade s, ObjectMapper j) {
        service = s;
        json = j;
    }

    public String handlerId() {
        return "print-fact-ready";
    }

    public boolean supports(String type) {
        return "ORDER_PRINT_FACT_READY".equals(type) || "BUSINESS_PRINT_FACT_READY".equals(type);
    }

    public void consume(SysOutbox row) {
        if ("BUSINESS_PRINT_FACT_READY".equals(row.getEventType())) {
            var business = json.readValue(row.getPayload(), BusinessPrintFactReady.class);
            if (!business.aggregateId().equals(row.getAggregateId())
                    || !business.aggregateType().equals(row.getAggregateType()))
                throw new IllegalArgumentException("Print business source event identity mismatch");
            service.onFact(business.factRef());
            return;
        }
        var payload = json.readValue(row.getPayload(), OrderPrintFactReady.class);
        if (!row.getAggregateId().equals(payload.orderNo()))
            throw new IllegalArgumentException("Print source event identity mismatch");
        service.onFact(payload.factRef());
    }
}
