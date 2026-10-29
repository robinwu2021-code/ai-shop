package ai.neargo.shop.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import ai.neargo.shop.inventory.mapper.InventoryDemandPlanMapper.Operation;
import ai.neargo.shop.inventory.port.InventoryDemandPlanPortImpl;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Branch;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Leaf;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.WithdrawalStatus;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class InventoryDemandWithdrawalTest {
    static class Fixture {
        final Scope scope = new Scope("MAIN", "E", "RESERVATION");
        final Plan plan;
        final InventoryDemandPlanMapper mapper = mock(InventoryDemandPlanMapper.class);
        final StockPostingService posting = mock(StockPostingService.class);
        final MaterialCatalogQueryPort materials = mock(MaterialCatalogQueryPort.class);
        final AtomicReference<Head> head = new AtomicReference<>();
        final InventoryDemandPlanPortImpl service;

        Fixture() {
            var branch =
                    new Branch(
                            "COMPONENT",
                            "SUBORDER",
                            "S",
                            "FINISHED_SKU",
                            BigDecimal.ONE,
                            "EA",
                            List.of(new Leaf("LEAF", "SKU", BigDecimal.ONE, "EA", "a".repeat(64))));
            var draft = new Plan(scope, "PLAN", null, List.of(branch));
            plan = new Plan(scope, "PLAN", InventoryDemandPlanFacts.hash(draft), draft.branches());
            var acl = mock(InventoryAclService.class);
            when(acl.findOwnerId("E")).thenReturn("OWNER");
            when(mapper.lockOwner("OWNER")).thenReturn(List.of("OWNER"));
            when(mapper.heads("OWNER", scope.externalRef()))
                    .thenAnswer(ignored -> head.get() == null ? List.of() : List.of(head.get()));
            var operations = new HashMap<String, Operation>();
            when(mapper.operations(anyString(), anyString()))
                    .thenAnswer(
                            call -> {
                                var operation = operations.get(call.getArgument(1, String.class));
                                return operation == null ? List.of() : List.of(operation);
                            });
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
                                head.set(
                                        new Head(
                                                1L,
                                                call.getArgument(2),
                                                call.getArgument(3),
                                                call.getArgument(5),
                                                "HELD",
                                                call.getArgument(6)));
                                return 1;
                            });
            when(mapper.markWithdrawn("OWNER", "PLAN"))
                    .thenAnswer(
                            call -> {
                                var original = head.get();
                                head.set(
                                        new Head(
                                                original.id(),
                                                original.planNo(),
                                                original.planHash(),
                                                original.lifecycleVersion(),
                                                "WITHDRAWN",
                                                original.expiresAt()));
                                return 1;
                            });
            when(mapper.insertOperation(
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString()))
                    .thenAnswer(
                            call -> {
                                operations.put(
                                        call.getArgument(2),
                                        new Operation(
                                                call.getArgument(1),
                                                call.getArgument(3),
                                                0,
                                                call.getArgument(4),
                                                call.getArgument(6),
                                                call.getArgument(5)));
                                return 1;
                            });
            service =
                    new InventoryDemandPlanPortImpl(
                            mapper,
                            acl,
                            materials,
                            posting,
                            JsonMapper.builder().findAndAddModules().build());
        }
    }

    @Test
    void aCommittedWithdrawalBeforeReservePreventsALateOriginalReserveFromTakingStock() {
        var f = new Fixture();
        var receipt = f.service.withdraw(f.plan, "WITHDRAW", 3);
        assertThat(receipt.status()).isEqualTo(WithdrawalStatus.WITHDRAWN_ABSENT);
        assertThat(f.service.withdraw(f.plan, "WITHDRAW", 3)).isEqualTo(receipt);
        assertThat(f.service.reserve(f.plan, 900, 3).status()).isEqualTo("WITHDRAWN");
        verify(f.mapper, never())
                .reserveBalance(any(), anyString(), anyString(), anyString(), any());
        verifyNoInteractions(f.posting, f.materials);
    }

    @Test
    void inventoryAlreadyAuthorizedForProductionCannotBeCancelledAsAnUnacceptedPreparation() {
        var f = new Fixture();
        f.head.set(
                new Head(
                        1L,
                        "PLAN",
                        f.plan.planHash(),
                        3,
                        "ACCEPTED_UNPAID",
                        LocalDateTime.now().plusMinutes(15)));
        assertThatThrownBy(() -> f.service.withdraw(f.plan, "WITHDRAW", 3))
                .isInstanceOf(RuntimeException.class);
        verify(f.mapper, never()).markWithdrawn(anyString(), anyString());
        verify(f.mapper, never())
                .unreserveBalance(any(), anyString(), anyString(), anyString(), any());
        verifyNoInteractions(f.posting);
    }

    @Test
    void theSameWithdrawalKeyCannotChangeThePlanOrLifecycle() {
        var f = new Fixture();
        f.service.withdraw(f.plan, "WITHDRAW", 3);
        assertThatThrownBy(() -> f.service.withdraw(f.plan, "WITHDRAW", 2))
                .isInstanceOf(RuntimeException.class);
        assertThat(f.head.get().status()).isEqualTo("WITHDRAWN");
    }
}
