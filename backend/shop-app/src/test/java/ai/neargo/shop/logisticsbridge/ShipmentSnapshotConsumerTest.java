package ai.neargo.shop.logisticsbridge;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.trade.order.service.ship.ShipmentSnapshotConverger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ShipmentSnapshotConsumerTest {
    @Test
    void asyncSnapshotPassesKuaidi100SourceToOrderConvergence() {
        ShipmentSnapshotConverger converger = mock(ShipmentSnapshotConverger.class);
        SysOutbox event = new SysOutbox();
        event.setPayload("{\"shipmentNo\":\"SRC1\",\"version\":0,\"waybillNo\":\"WB1\","
                + "\"status\":\"DELIVERED\",\"signedAt\":1000,\"signedNodeAt\":1000,"
                + "\"firstTraceAt\":900,\"source\":\"kuaidi100\"}");
        new ShipmentSnapshotConsumer(converger, new ObjectMapper()).consume(event);
        verify(converger).converge("SRC1", 0, "WB1", "DELIVERED", 1000L, 1000L, 900L,
                "kuaidi100");
    }
}
