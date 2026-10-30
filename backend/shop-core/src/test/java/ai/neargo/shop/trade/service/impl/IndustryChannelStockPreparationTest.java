package ai.neargo.shop.trade.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Branch;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Leaf;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Progress;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Receipt;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

class IndustryChannelStockPreparationTest {
    final Scope scope = new Scope("MAIN", "E", "REAL_PREPARATION");
    final String definition = "a".repeat(64);

    Plan plan() {
        var branch =
                new Branch(
                        "C",
                        "SUB",
                        "S",
                        "FINISHED_SKU",
                        new BigDecimal("1.25"),
                        "KG",
                        List.of(new Leaf("L", "SKU", new BigDecimal("1.25"), "KG", definition)));
        var raw = new Plan(scope, "P", null, List.of(branch));
        return new Plan(scope, "P", InventoryDemandPlanFacts.hash(raw), raw.branches());
    }

    Progress line(
            String component,
            String fulfillment,
            String quantity,
            String issued,
            String released,
            String hash) {
        return new Progress(
                "L",
                component,
                fulfillment,
                "FINISHED_SKU",
                "REAL_INVENTORY_ITEM",
                new BigDecimal(quantity),
                new BigDecimal(issued),
                new BigDecimal(released),
                "KG",
                hash,
                null,
                null,
                null,
                null,
                "UNKNOWN");
    }

    Receipt receipt(Plan plan, String status, List<Progress> lines) {
        return new Receipt(
                scope,
                plan.planNo(),
                plan.planHash(),
                status,
                3,
                Instant.parse("2026-10-29T01:00:00Z"),
                lines);
    }

    @Test
    void stockIdentityIsOriginalInventoryItemRatherThanSourceSku() {
        var plan = plan();
        var receipt =
                receipt(plan, "HELD", List.of(line("C", "SUB", "1.25", "0", "0", definition)));
        assertDoesNotThrow(() -> IndustryChannelStockPreparation.validateReceipt(plan, receipt));
    }

    @Test
    void anotherComponentDefinitionQuantityOrFulfillmentCannotBorrowTheSamePlanHash() {
        var plan = plan();
        for (var line :
                List.of(
                        line("OTHER", "SUB", "1.25", "0", "0", definition),
                        line("C", "OTHER", "1.25", "0", "0", definition),
                        line("C", "SUB", "1.24", "0", "0", definition),
                        line("C", "SUB", "1.25", "0", "0", "b".repeat(64)))) {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            IndustryChannelStockPreparation.validateReceipt(
                                    plan, receipt(plan, "HELD", List.of(line))));
        }
    }

    @Test
    void heldMustContainEveryUniqueLeafWithNoReleaseOrIssuance() {
        var plan = plan();
        var valid = line("C", "SUB", "1.25", "0", "0", definition);
        for (var lines :
                List.of(
                        List.<Progress>of(),
                        List.of(valid, valid),
                        List.of(line("C", "SUB", "1.25", "0.01", "0", definition)),
                        List.of(line("C", "SUB", "1.25", "0", "0.01", definition)))) {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            IndustryChannelStockPreparation.validateReceipt(
                                    plan, receipt(plan, "HELD", lines)));
        }
        assertThrows(
                RuntimeException.class,
                () ->
                        IndustryChannelStockPreparation.validateReceipt(
                                plan,
                                new Receipt(
                                        new Scope("MAIN", "OTHER", "REAL_PREPARATION"),
                                        plan.planNo(),
                                        plan.planHash(),
                                        "HELD",
                                        3,
                                        Instant.now(),
                                        List.of(valid))));
    }
}
