package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.food.commerce.port.FoodChannelFulfillmentSourcePortImpl;
import ai.neargo.shop.food.dto.DiningChannelFulfillmentFacts;
import ai.neargo.shop.food.service.DiningChannelFulfillmentService;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.Stage;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

class FoodChannelFulfillmentSourcePortTest {
    @Test
    void adapterPreservesOriginalOpaqueIdentityAndExactSelectedPhysicalQuantity() {
        var now = Instant.now();
        DiningChannelFulfillmentService owner =
                query ->
                        new DiningChannelFulfillmentFacts.Facts(
                                query,
                                List.of(
                                        new DiningChannelFulfillmentFacts.Line(
                                                "SOURCE_LINE",
                                                "COMPONENT",
                                                "OPAQUE_ITEM",
                                                new BigDecimal("1.250000"),
                                                "KG")),
                                now.minusSeconds(2),
                                "FOOD:CUSTODY:REAL",
                                now,
                                now.plusSeconds(30));
        var adapter = new FoodChannelFulfillmentSourcePortImpl(owner);
        var query =
                new ChannelFulfillmentSourcePort.Query(
                        "E", "S", "BATCH", "CORE", "HANDOVER", "CUSTODY_V1:hash");
        var facts = adapter.capture(query);
        assertThat(facts.data().query()).isEqualTo(query);
        assertThat(facts.data().stage()).isEqualTo(Stage.HANDED_OVER);
        assertThat(facts.data().lines().getFirst().coreItemRef()).isEqualTo("OPAQUE_ITEM");
        assertThat(facts.data().lines().getFirst().quantity()).isEqualTo("1.25");
        assertThat(facts.data().sourceReceiptRef()).isEqualTo("FOOD:CUSTODY:REAL");
        assertThat(facts.data().packageRef()).isNull();
        assertThat(facts.data().trackingNo()).isNull();
    }
}
