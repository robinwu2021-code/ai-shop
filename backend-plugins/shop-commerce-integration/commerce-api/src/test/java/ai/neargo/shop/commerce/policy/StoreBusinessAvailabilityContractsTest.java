package ai.neargo.shop.commerce.policy;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelAvailabilityGateway.Publication;
import ai.neargo.shop.spi.commerce.ChannelAvailabilityGateway.Scope;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Data;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Kind;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Query;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.State;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

class StoreBusinessAvailabilityContractsTest {
    @Test
    void legacyStockJsonAndHashesDoNotAcquireStoreFields() {
        var json = JsonMapper.builder().findAndAddModules().build();
        var query = new Query("E", "S", Kind.STOCK, "SKU");
        var data = new Data(query, State.QUANTITY, "1.25", "KG", "SOURCE_REVISION");
        assertThat(json.valueToTree(query).has("owner")).isFalse();
        assertThat(json.valueToTree(query).has("storeMode")).isFalse();
        assertThat(json.valueToTree(data).has("nextChangeAt")).isFalse();
        assertThat(PricingSnapshotHash.of(query))
                .isEqualTo(
                        PricingSnapshotHash.of(
                                Map.of(
                                        "entityNo",
                                        "E",
                                        "storeNo",
                                        "S",
                                        "kind",
                                        "STOCK",
                                        "resourceRef",
                                        "SKU")));
        assertThat(PricingSnapshotHash.of(data))
                .isEqualTo(
                        PricingSnapshotHash.of(
                                Map.of(
                                        "query",
                                        query,
                                        "state",
                                        "QUANTITY",
                                        "quantity",
                                        "1.25",
                                        "unit",
                                        "KG",
                                        "sourceRevision",
                                        "SOURCE_REVISION")));
        var publication =
                new Publication(
                        "OP",
                        1,
                        new Scope(
                                "E",
                                "S",
                                "C",
                                "P",
                                "ACCOUNT",
                                "STORE",
                                "REV",
                                "AUTH",
                                "CREDENTIAL"),
                        new Identity("REMOTE_SKU", List.of()),
                        "MAP",
                        "MAP_REV",
                        data,
                        "ORIGINAL_STOCK");
        assertThat(json.valueToTree(publication).has("storeIdentityProof")).isFalse();
    }

    @Test
    void daylightOverlapUsesBothOffsetsAndTheActualTransitionRatherThanSampling() {
        var now = Instant.parse("2026-11-01T05:45:00Z");
        var zone = ZoneId.of("America/New_York");
        var next =
                StoreBusinessBoundaries.nextChange(
                        now,
                        List.of(zone),
                        List.of(LocalTime.of(1, 0), LocalTime.of(1, 30)),
                        List.of(),
                        at -> {
                            var local = at.atZone(zone).toLocalTime();
                            return !local.isBefore(LocalTime.of(1, 0))
                                            && local.isBefore(LocalTime.of(1, 30))
                                    ? State.OPEN
                                    : State.CLOSED;
                        });
        assertThat(next).isEqualTo(Instant.parse("2026-11-01T06:00:00Z"));
    }
}
