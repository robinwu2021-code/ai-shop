package ai.neargo.shop.inventory.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.inventory.mapper.InventoryDemandPlanMapper;
import ai.neargo.shop.inventory.mapper.InventoryDemandPlanMapper.Head;
import ai.neargo.shop.inventory.mapper.InventoryDemandPlanMapper.Leaf;
import ai.neargo.shop.inventory.port.InventoryDemandPlanPortImpl;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Branch;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Target;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort.MaterialFact;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Actual demand-owner phase policy with isolated persistence/stock doubles; not an inventory
 * database integration claim.
 */
class InventoryFinishedModifierPlanTest {
    MaterialFact material(String id, String name, String unit, int weighed) {
        return new MaterialFact(
                id,
                "E",
                name,
                unit,
                weighed,
                "OWN",
                "BASE_ONLY",
                "INTEGER_ONLY",
                MaterialCatalogFacts.definitionHash(id, "E", name, unit, weighed, "OWN"));
    }

    @Test
    void mixedReservationRetainsRealItemMappingAndCannotIssueTheWrongPhase() {
        var mapper = mock(InventoryDemandPlanMapper.class);
        var acl = mock(InventoryAclService.class);
        var materials = mock(MaterialCatalogQueryPort.class);
        var posting = mock(StockPostingService.class);
        var finished = material("FINISHED_ITEM", "Finished tea", "PIECE", 0);
        var raw = material("RAW_ITEM", "Boba", "KG", 1);
        var scope = new Scope("MAIN", "E", "RESERVATION");
        var planLeaf =
                new InventoryDemandPlanPort.Leaf(
                        "F",
                        "SKU",
                        BigDecimal.ONE,
                        "PIECE",
                        finished.definitionHash(),
                        "FINISHED_SKU");
        var rawLeaf =
                new InventoryDemandPlanPort.Leaf(
                        "R",
                        raw.itemId(),
                        new BigDecimal("0.125"),
                        "KG",
                        raw.definitionHash(),
                        "RECIPE_MATERIAL");
        var original =
                new Plan(
                        scope,
                        "P",
                        null,
                        List.of(
                                new Branch(
                                        "C",
                                        "SUB",
                                        "S",
                                        "FINISHED_WITH_MODIFIERS",
                                        BigDecimal.ONE,
                                        "PIECE",
                                        List.of(planLeaf, rawLeaf))));
        var plan =
                new Plan(scope, "P", InventoryDemandPlanFacts.hash(original), original.branches());
        when(acl.findOwnerId("E")).thenReturn("OWNER");
        when(mapper.lockOwner("OWNER")).thenReturn(List.of("OWNER"));
        when(acl.itemIdOfSku("SKU")).thenReturn(finished.itemId());
        when(acl.stockLocationOfExistingStore("E", "S")).thenReturn("LOC");
        when(materials.getMaterials("E", List.of(finished.itemId()))).thenReturn(List.of(finished));
        when(materials.getMaterials("E", List.of(raw.itemId()))).thenReturn(List.of(raw));
        var inserted = new AtomicBoolean();
        when(mapper.heads("OWNER", scope.externalRef()))
                .thenAnswer(
                        call ->
                                inserted.get()
                                        ? List.of(
                                                new Head(
                                                        1L,
                                                        "P",
                                                        plan.planHash(),
                                                        3,
                                                        "ACCEPTED_UNPAID",
                                                        LocalDateTime.now().plusHours(1)))
                                        : List.of());
        when(mapper.legacyReservations("OWNER", scope.externalRef())).thenReturn(List.of());
        when(mapper.insertPlan(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyInt(),
                        any()))
                .thenAnswer(
                        call -> {
                            inserted.set(true);
                            return 1;
                        });
        when(mapper.reserveBalance(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
        var finishRow =
                new Leaf(
                        1L,
                        "F",
                        "C",
                        "SUB",
                        "FINISHED_SKU",
                        finished.itemId(),
                        "LOC",
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "PIECE",
                        finished.definitionHash(),
                        null,
                        null,
                        null,
                        null);
        var rawRow =
                new Leaf(
                        2L,
                        "R",
                        "C",
                        "SUB",
                        "RECIPE_MATERIAL",
                        raw.itemId(),
                        "LOC",
                        new BigDecimal("0.125"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "KG",
                        raw.definitionHash(),
                        null,
                        null,
                        null,
                        null);
        when(mapper.orderedLeaves("OWNER", "P")).thenReturn(List.of(finishRow, rawRow));
        when(mapper.findLeaves("OWNER", "P", "F")).thenReturn(List.of(finishRow));
        when(mapper.findLeaves("OWNER", "P", "R")).thenReturn(List.of(rawRow));
        when(mapper.operations(anyString(), anyString())).thenReturn(List.of());
        var owner =
                new InventoryDemandPlanPortImpl(
                        mapper,
                        acl,
                        materials,
                        posting,
                        JsonMapper.builder().findAndAddModules().build());
        owner.reserve(plan, 600, 3);
        verify(mapper)
                .insertLeaf(
                        "OWNER",
                        "P",
                        "F",
                        "C",
                        "SUB",
                        "FINISHED_SKU",
                        finished.itemId(),
                        "LOC",
                        BigDecimal.ONE,
                        "PIECE",
                        finished.definitionHash());
        verify(mapper)
                .insertLeaf(
                        "OWNER",
                        "P",
                        "R",
                        "C",
                        "SUB",
                        "RECIPE_MATERIAL",
                        raw.itemId(),
                        "LOC",
                        new BigDecimal("0.125"),
                        "KG",
                        raw.definitionHash());
        assertThatThrownBy(
                        () ->
                                owner.issueTo(
                                        scope,
                                        "WRONG_HANDOVER",
                                        plan.planHash(),
                                        "HANDOVER",
                                        List.of(new Target("R", new BigDecimal("0.125")))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                owner.issueTo(
                                        scope,
                                        "WRONG_PRODUCTION",
                                        plan.planHash(),
                                        "PRODUCTION",
                                        List.of(new Target("F", BigDecimal.ONE))))
                .isInstanceOf(RuntimeException.class);
        verify(mapper, never())
                .unreserveBalance(any(), anyString(), anyString(), anyString(), any());
        verifyNoInteractions(posting);
    }
}
