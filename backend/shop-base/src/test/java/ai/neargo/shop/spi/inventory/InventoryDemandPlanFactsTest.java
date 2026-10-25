package ai.neargo.shop.spi.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Branch;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Leaf;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

class InventoryDemandPlanFactsTest {
    @Test
    void decimalIdentityDoesNotDependOnTrailingZeros() {
        var scope = new InventoryReservationPort.Scope("MAIN", "E", "O");
        var a =
                new Plan(
                        scope,
                        "P",
                        null,
                        List.of(
                                new Branch(
                                        "C",
                                        "S",
                                        "STORE",
                                        "RECIPE_MATERIAL",
                                        BigDecimal.ONE,
                                        "EA",
                                        List.of(
                                                new Leaf(
                                                        "L",
                                                        "I",
                                                        new BigDecimal("0.125000"),
                                                        "KG",
                                                        "a".repeat(64))))));
        var b =
                new Plan(
                        scope,
                        "P",
                        null,
                        List.of(
                                new Branch(
                                        "C",
                                        "S",
                                        "STORE",
                                        "RECIPE_MATERIAL",
                                        new BigDecimal("1.0"),
                                        "EA",
                                        List.of(
                                                new Leaf(
                                                        "L",
                                                        "I",
                                                        new BigDecimal("0.125"),
                                                        "KG",
                                                        "a".repeat(64))))));
        assertEquals(InventoryDemandPlanFacts.hash(a), InventoryDemandPlanFacts.hash(b));
    }

    @Test
    void excessivePrecisionIsRejectedWithoutRounding() {
        assertThrows(
                IllegalArgumentException.class,
                () -> InventoryDemandPlanFacts.quantity(new BigDecimal("0.0000001"), false));
    }
}
