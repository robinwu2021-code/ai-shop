package ai.neargo.shop.spi.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Branch;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Leaf;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

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

    private Plan withKind(String kind, List<Leaf> leaves) {
        var scope = new InventoryReservationPort.Scope("MAIN", "E", "O");
        var raw =
                new Plan(
                        scope,
                        "P",
                        null,
                        List.of(
                                new Branch(
                                        "C", "SUB", "S", kind, BigDecimal.ONE, "PIECE", leaves)));
        return new Plan(scope, "P", InventoryDemandPlanFacts.hash(raw), raw.branches());
    }

    @Test
    void explicitFinishedAndModifierLeavesHaveDifferentPostingPhases() {
        var finished =
                new Leaf("F", "SKU", BigDecimal.ONE, "PIECE", "a".repeat(64), "FINISHED_SKU");
        var material =
                new Leaf(
                        "R",
                        "MATERIAL",
                        new BigDecimal("0.125"),
                        "KG",
                        "b".repeat(64),
                        "RECIPE_MATERIAL");
        var plan = withKind("FINISHED_WITH_MODIFIERS", List.of(finished, material));
        InventoryDemandPlanFacts.validate(plan);
        assertEquals(
                "FINISHED_SKU",
                InventoryDemandPlanFacts.sourceKind(plan.branches().getFirst(), finished));
        assertEquals(
                "RECIPE_MATERIAL",
                InventoryDemandPlanFacts.sourceKind(plan.branches().getFirst(), material));
    }

    @Test
    void mixedBranchesCannotHideAnUntaggedLeafOrTwoFinishedUnits() {
        var finished =
                new Leaf("F", "SKU", BigDecimal.ONE, "PIECE", "a".repeat(64), "FINISHED_SKU");
        var raw = new Leaf("R", "MATERIAL", BigDecimal.ONE, "KG", "b".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        InventoryDemandPlanFacts.validate(
                                withKind("FINISHED_WITH_MODIFIERS", List.of(finished, raw))));
        var other = new Leaf("F2", "SKU2", BigDecimal.ONE, "PIECE", "c".repeat(64), "FINISHED_SKU");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        InventoryDemandPlanFacts.validate(
                                withKind("FINISHED_WITH_MODIFIERS", List.of(finished, other))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        InventoryDemandPlanFacts.validate(
                                withKind("RECIPE_MATERIAL", List.of(finished))));
    }

    @Test
    void originalPlanGoldenShapeAndJsonRemainUnchangedWhenNoLeafPhaseWasAdded() {
        var leaf = new Leaf("F", "SKU", BigDecimal.ONE, "PIECE", "a".repeat(64));
        var plan = withKind("FINISHED_SKU", List.of(leaf));
        var expected =
                PricingSnapshotHash.of(
                        List.of(
                                "INVENTORY_DEMAND_PLAN_V1",
                                plan.scope(),
                                "P",
                                List.of(
                                        List.of(
                                                "C",
                                                "SUB",
                                                "S",
                                                "FINISHED_SKU",
                                                "1",
                                                "PIECE",
                                                List.of(
                                                        List.of(
                                                                "F",
                                                                "SKU",
                                                                "1",
                                                                "PIECE",
                                                                "a".repeat(64))),
                                                ""))));
        assertEquals(expected, plan.planHash());
        var json = JsonMapper.builder().findAndAddModules().build();
        assertFalse(json.writeValueAsString(leaf).contains("sourceKind"));
    }

    @Test
    void bundleExtraMaterialsNeverManufactureAFinishedBundleOrRecipeReference() {
        var raw = new Leaf("R", "MATERIAL", new BigDecimal("0.125"), "KG", "b".repeat(64));
        var plan = withKind("BUNDLE_MODIFIERS", List.of(raw));
        InventoryDemandPlanFacts.validate(plan);
        assertEquals(
                "RECIPE_MATERIAL",
                InventoryDemandPlanFacts.sourceKind(plan.branches().getFirst(), raw));
        var branch = plan.branches().getFirst();
        var inventedRecipe =
                new Plan(
                        plan.scope(),
                        plan.planNo(),
                        null,
                        List.of(
                                new Branch(
                                        branch.sourceComponentRef(),
                                        branch.fulfillmentRef(),
                                        branch.storeNo(),
                                        branch.kind(),
                                        branch.quantity(),
                                        branch.unit(),
                                        branch.leaves(),
                                        "FAKE_RECIPE")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        InventoryDemandPlanFacts.validate(
                                new Plan(
                                        inventedRecipe.scope(),
                                        inventedRecipe.planNo(),
                                        InventoryDemandPlanFacts.hash(inventedRecipe),
                                        inventedRecipe.branches())));
    }
}
