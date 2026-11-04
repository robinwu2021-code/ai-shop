package ai.neargo.commerce.core.mall;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class ComponentAllocationTest {
    SkuComponent component(String no, ComponentKind kind, int units, long weight, boolean gift) {
        return new SkuComponent(
                no,
                kind,
                kind == ComponentKind.PLAN ? "pv" : null,
                kind == ComponentKind.END_PACKAGE ? "POS" : null,
                kind == ComponentKind.HARDWARE ? "hw" : null,
                kind == ComponentKind.HARDWARE ? null : 30,
                units,
                weight,
                gift,
                "ON_FULFILLMENT");
    }

    @Test
    void componentAndCycleRemaindersConserveMoneyAndExcludeHardwareEndAndGiftsFromCredit() {
        var result =
                ComponentAllocation.allocate(
                        101,
                        80,
                        2,
                        List.of(
                                component("b", ComponentKind.HARDWARE, 1, 1, false),
                                component("a", ComponentKind.PLAN, 3, 1, false),
                                component("c", ComponentKind.END_PACKAGE, 1, 1, false),
                                component("d", ComponentKind.PLAN, 1, 0, true)));
        assertEquals(101, result.stream().mapToLong(ComponentAllocation.Allocation::deal).sum());
        var plan = result.getFirst();
        assertEquals("a", plan.component().componentNo());
        assertEquals(34, plan.deal());
        assertEquals(34, plan.credit());
        assertEquals(6, plan.cycles().size());
        assertEquals(
                List.of(6L, 6L, 6L, 6L, 5L, 5L),
                plan.cycles().stream().map(ComponentAllocation.Cycle::deal).toList());
        for (var a : result) {
            assertEquals(a.deal(), a.cash() + a.credit());
            assertEquals(
                    a.deal(), a.cycles().stream().mapToLong(ComponentAllocation.Cycle::deal).sum());
            assertEquals(
                    a.cash(), a.cycles().stream().mapToLong(ComponentAllocation.Cycle::cash).sum());
            assertEquals(
                    a.credit(),
                    a.cycles().stream().mapToLong(ComponentAllocation.Cycle::credit).sum());
            if (a.component().kind() != ComponentKind.PLAN) assertEquals(0, a.credit());
            if (a.component().gift()) assertEquals(0, a.deal());
        }
    }

    @Test
    void usesExactArithmeticAtLongLimitAndRejectsOverflowAndDuplicateComponents() {
        var c = component("a", ComponentKind.PLAN, 1, Long.MAX_VALUE, false);
        assertEquals(
                Long.MAX_VALUE,
                ComponentAllocation.allocate(Long.MAX_VALUE, 0, 1, List.of(c)).getFirst().deal());
        assertThrows(
                ArithmeticException.class,
                () ->
                        ComponentAllocation.allocate(
                                1,
                                0,
                                Integer.MAX_VALUE,
                                List.of(component("a", ComponentKind.PLAN, 2, 1, false))));
        assertThrows(
                IllegalArgumentException.class,
                () -> ComponentAllocation.allocate(1, 0, 1, List.of(c, c)));
    }

    @Test
    void materializedCycleCountIsBoundedBeforeAllocatingMemory() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ComponentAllocation.allocate(
                                1,
                                0,
                                10001,
                                List.of(component("a", ComponentKind.PLAN, 1, 1, false))));
    }

    @Test
    void grossDealDiscountAndCyclesAreSeparatelyConserved() {
        var allocations =
                ComponentAllocation.allocate(
                        101,
                        80,
                        30,
                        2,
                        List.of(
                                component("a", ComponentKind.PLAN, 3, 1, false),
                                component("b", ComponentKind.HARDWARE, 1, 1, false),
                                component("c", ComponentKind.END_PACKAGE, 1, 1, false)));
        assertEquals(
                List.of(34L, 34L, 33L),
                allocations.stream().map(ComponentAllocation.Allocation::gross).toList());
        assertEquals(
                80, allocations.stream().mapToLong(ComponentAllocation.Allocation::deal).sum());
        for (var a : allocations) {
            assertTrue(a.gross() >= a.deal());
            assertEquals(
                    a.gross(),
                    a.cycles().stream().mapToLong(ComponentAllocation.Cycle::gross).sum());
            for (var c : a.cycles()) assertTrue(c.gross() >= c.deal());
        }
        var zero =
                ComponentAllocation.allocate(
                                5,
                                0,
                                100,
                                1,
                                List.of(component("a", ComponentKind.PLAN, 3, 1, false)))
                        .getFirst();
        assertEquals(5, zero.gross());
        assertEquals(0, zero.deal());
        assertEquals(0, zero.credit());
    }

    @Test
    void allGiftZeroPriceRetainsPositiveMarketingGrossWithNoValueBasis() {
        var a =
                ComponentAllocation.allocate(
                        11,
                        0,
                        100,
                        2,
                        List.of(
                                component("a", ComponentKind.PLAN, 3, 0, true),
                                component("b", ComponentKind.HARDWARE, 1, 0, true)));
        assertEquals(
                List.of(6L, 5L), a.stream().map(ComponentAllocation.Allocation::gross).toList());
        assertEquals(
                11,
                a.stream()
                        .flatMap(c -> c.cycles().stream())
                        .mapToLong(ComponentAllocation.Cycle::gross)
                        .sum());
        assertTrue(
                a.stream()
                        .flatMap(c -> c.cycles().stream())
                        .allMatch(
                                c ->
                                        c.deal() == 0
                                                && c.cash() == 0
                                                && c.credit() == 0
                                                && c.eligibleBasis() == 0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ComponentAllocation.allocate(
                                11,
                                1,
                                100,
                                1,
                                List.of(component("a", ComponentKind.PLAN, 1, 0, true))));
    }
    @Test
    void twdWholeDollarAllocationRetainsFractionalCreditAndConservesEveryCycle() {
        var result = ComponentAllocation.allocate(69900, 69900, 19950, 1,
                List.of(component("a", ComponentKind.PLAN, 3, 1, false)), 100);
        var plan = result.getFirst();
        assertEquals(19900, plan.credit());
        assertEquals(50000, plan.cash());
        assertEquals(List.of(23300L, 23300L, 23300L),
                plan.cycles().stream().map(ComponentAllocation.Cycle::deal).toList());
        assertEquals(List.of(6700L, 6600L, 6600L),
                plan.cycles().stream().map(ComponentAllocation.Cycle::credit).toList());
        assertEquals(List.of(16600L, 16700L, 16700L),
                plan.cycles().stream().map(ComponentAllocation.Cycle::cash).toList());
        assertEquals(50, 19950 - plan.credit());
    }

    @Test
    void quantumAllocationRejectsFractionalPricesAndKeepsMixedComponentsOnGrid() {
        var components = List.of(component("a", ComponentKind.PLAN, 3, 1, false),
                component("b", ComponentKind.HARDWARE, 1, 1, false),
                component("c", ComponentKind.END_PACKAGE, 2, 1, false),
                component("d", ComponentKind.PLAN, 2, 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> ComponentAllocation.allocate(69901, 69900, 0, 1, components, 100));
        assertThrows(IllegalArgumentException.class,
                () -> ComponentAllocation.allocate(69900, 69899, 0, 1, components, 100));
        var result = ComponentAllocation.allocate(70100, 69900, 19950, 1, components, 100);
        assertEquals(70100, result.stream().mapToLong(ComponentAllocation.Allocation::gross).sum());
        assertEquals(69900, result.stream().mapToLong(ComponentAllocation.Allocation::deal).sum());
        assertEquals(19900, result.stream().mapToLong(ComponentAllocation.Allocation::credit).sum());
        for (var a : result) {
            assertEquals(a.cash(), a.cycles().stream().mapToLong(ComponentAllocation.Cycle::cash).sum());
            for (var c : a.cycles()) {
                assertEquals(0, c.cash() % 100);
                assertEquals(0, c.deal() % 100);
                assertEquals(0, c.gross() % 100);
                assertTrue(c.cash() >= 0);
            }
            if (a.component().kind() != ComponentKind.PLAN || a.component().gift())
                assertEquals(0, a.credit());
        }
    }

}
