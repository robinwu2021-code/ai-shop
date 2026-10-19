package ai.neargo.shop.scenario;

import ai.neargo.shop.config.OutboxEventRoutes;
import ai.neargo.shop.spi.trade.OrderEvents;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class OutboxRoutingTest {
    @Test
    void arrivedNotificationSplitsByRootOrderAndPreservesRecipientAndPickup() {
        var routes = new OutboxEventRoutes(new ObjectMapper(),
                sub -> Map.of("S1","O1","S2","O2","S3","O1").get(sub));
        var result = routes.expand("PLATFORM",new OrderEvents.SubOrdersArrived("U1","P1",List.of("S1","S2","S3")));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).options().streamKey()).isEqualTo("ORDER:O1");
        assertThat(result.get(1).options().streamKey()).isEqualTo("ORDER:O2");
        assertThat(result.get(0).event()).isEqualTo(new OrderEvents.SubOrdersArrived("U1","P1",List.of("S1","S3")));
        assertThat(result.get(1).event()).isEqualTo(new OrderEvents.SubOrdersArrived("U1","P1",List.of("S2")));
        assertThatThrownBy(() -> routes.expand("PLATFORM",new OrderEvents.SubOrdersArrived("U1","P1",List.of("MISSING"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
