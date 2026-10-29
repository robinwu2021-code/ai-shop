package ai.neargo.shop.retail.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.retail.commerce.port.RetailChannelFulfillmentSourcePortImpl;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.Stage;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourcePort;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class RetailChannelFulfillmentSourceTest {
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final ChannelFulfillmentSourcePort.Query q =
            new ChannelFulfillmentSourcePort.Query("E", "S", "SOURCE", "ORDER", "FULFILLMENT", "1");

    RetailChannelFulfillmentSourcePortImpl source(String stage, boolean forged, boolean expired) {
        IndustryChannelFulfillmentSourcePort owner =
                query -> {
                    var data =
                            new IndustryChannelFulfillmentSourcePort.Data(
                                    query,
                                    stage,
                                    List.of(
                                            new IndustryChannelFulfillmentSourcePort.Line(
                                                    "SOURCE_LINE", "COMP", "OPAQUE", "0.75", "KG")),
                                    now.minusSeconds(1),
                                    "ORIGINAL_ACTION",
                                    "a".repeat(64),
                                    "b".repeat(64),
                                    "c".repeat(64),
                                    "d".repeat(64));
                    return new IndustryChannelFulfillmentSourcePort.Facts(
                            data,
                            forged ? "e".repeat(64) : PricingSnapshotHash.of(data),
                            now,
                            expired ? now : now.plusSeconds(25),
                            "ORIGINAL_OWNER_READ");
                };
        return new RetailChannelFulfillmentSourcePortImpl(
                () -> owner, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void onlyRealOriginalPickupOrHandoverDeltaIsAdaptedWithoutCarrierOrFundsFacts() {
        var pickup = source("PICKED_UP", false, false).capture(q);
        var handover = source("HANDED_OVER", false, false).capture(q);
        assertThat(source("HANDED_OVER", false, false).owner()).isEqualTo(Owner.RETAIL);
        assertThat(pickup.data().stage()).isEqualTo(Stage.PICKED_UP);
        assertThat(handover.data().lines().getFirst().quantity()).isEqualTo("0.75");
        assertThat(handover.data().sourceReceiptRef()).isEqualTo("ORIGINAL_ACTION");
        assertThat(handover.data().packageRef()).isNull();
        assertThat(handover.data().trackingNo()).isNull();
    }

    @Test
    void shipmentDeliveredForgedAndExpiredSourcesCannotBeSubstituted() {
        for (var stage : List.of("SHIPPED", "DELIVERED", "READY"))
            assertThatThrownBy(() -> source(stage, false, false).capture(q))
                    .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> source("HANDED_OVER", true, false).capture(q))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> source("HANDED_OVER", false, true).capture(q))
                .isInstanceOf(RuntimeException.class);
    }
}
