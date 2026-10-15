package ai.neargo.shop.logisticsbridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.trade.order.service.ship.ShipmentSnapshotConverger;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Frozen trusted event fields prevent delayed delivery from resolving a newer mutable logistics mapping. */
@Component
public class ShipmentSnapshotConsumer implements OutboxConsumer {
    private final ShipmentSnapshotConverger converger;
    private final ObjectMapper json;

    public ShipmentSnapshotConsumer(ShipmentSnapshotConverger converger, ObjectMapper json) {
        this.converger = converger;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) { return "LGS_SHIPMENT_SNAPSHOT".equals(eventType); }

    @Override
    public void consume(SysOutbox event) {
        var p = json.readTree(event.getPayload());
        Long at = p.path("signedAt").isNumber() ? p.path("signedAt").asLong() : null;
        Long signedNodeAt = p.path("signedNodeAt").isNumber() ? p.path("signedNodeAt").asLong() : null;
        Long firstTraceAt = p.path("firstTraceAt").isNumber() ? p.path("firstTraceAt").asLong() : null;
        converger.converge(p.path("shipmentNo").asString(""), p.path("version").asLong(-1),
                p.path("waybillNo").asString(""), p.path("status").asString(""), at, signedNodeAt, firstTraceAt);
    }
}
