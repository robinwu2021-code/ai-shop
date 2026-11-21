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
public class PrintControlChangedConsumer implements OutboxConsumer {
    private final PrintJobCommandService service;
    private final ObjectMapper json;

    public PrintControlChangedConsumer(PrintJobCommandService s, ObjectMapper j) {
        service = s;
        json = j;
    }

    public String handlerId() {
        return "print-control-changed";
    }

    public boolean supports(String type) {
        return "ORDER_PRINT_CONTROL_CHANGED".equals(type);
    }

    public void consume(SysOutbox row) {
        var payload = json.readValue(row.getPayload(), OrderPrintControlChanged.class);
        if (!row.getAggregateId().equals(payload.fact().orderNo()))
            throw new IllegalArgumentException("Print source event identity mismatch");
        service.applyControl(payload.fact());
    }
}
