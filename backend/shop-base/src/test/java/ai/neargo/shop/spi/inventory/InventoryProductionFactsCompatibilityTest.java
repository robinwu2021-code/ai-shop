package ai.neargo.shop.spi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.Command;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.Line;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Adding an exclusive plan must not alter the hash of an already persisted legacy command. */
class InventoryProductionFactsCompatibilityTest {
    record LegacyCommand(
            String entityNo,
            String storeNo,
            String eventNo,
            String purpose,
            Instant occurredAt,
            List<Line> lines) {}

    @Test
    void absentPlanRetainsTheOriginalSixFieldHash() {
        var at = Instant.parse("2026-01-01T00:00:00Z");
        var lines = List.of(new Line("I", BigDecimal.ONE, "EA", "a".repeat(64)));
        var command = new Command("E", "S", "EV", "PRODUCTION", at, lines);
        assertThat(InventoryProductionFacts.hash(command))
                .isEqualTo(
                        PricingSnapshotHash.of(
                                List.of(
                                        "FOOD_PRODUCTION_V1",
                                        new LegacyCommand(
                                                "E", "S", "EV", "PRODUCTION", at, lines))));
    }
}
