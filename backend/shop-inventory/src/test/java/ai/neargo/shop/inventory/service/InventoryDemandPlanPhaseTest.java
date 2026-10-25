package ai.neargo.shop.inventory.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.inventory.mapper.InventoryDemandPlanMapper;
import ai.neargo.shop.inventory.mapper.InventoryDemandPlanMapper.Head;
import ai.neargo.shop.inventory.mapper.InventoryDemandPlanMapper.Leaf;
import ai.neargo.shop.inventory.port.InventoryDemandPlanPortImpl;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Target;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase exclusion and exact cumulative caps prevent handover from consuming recipe material twice.
 */
class InventoryDemandPlanPhaseTest {
    @Test
    void recipeLeafCannotBeIssuedAtHandover() {
        var f = new Fixture();
        assertThatThrownBy(
                        () ->
                                f.service.issueTo(
                                        f.scope,
                                        "OP",
                                        f.hash,
                                        "HANDOVER",
                                        List.of(new Target("L", new BigDecimal("0.125")))))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(f.posting);
        verify(f.mapper, never())
                .unreserveBalance(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aOneThousandthOverrunDoesNotUnreserveOrPost() {
        var f = new Fixture();
        assertThatThrownBy(
                        () ->
                                f.service.issueTo(
                                        f.scope,
                                        "OP",
                                        f.hash,
                                        "PRODUCTION",
                                        List.of(new Target("L", new BigDecimal("0.126")))))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(f.posting);
        verify(f.mapper, never())
                .unreserveBalance(any(), anyString(), anyString(), anyString(), any());
    }

    static class Fixture {
        final Scope scope = new Scope("MAIN", "E", "O");
        final String hash = "a".repeat(64);
        final InventoryDemandPlanMapper mapper = mock(InventoryDemandPlanMapper.class);
        final StockPostingService posting = mock(StockPostingService.class);
        final InventoryDemandPlanPortImpl service;

        Fixture() {
            var acl = mock(InventoryAclService.class);
            when(acl.findOwnerId("E")).thenReturn("OWNER");
            when(mapper.lockOwner("OWNER")).thenReturn(List.of("OWNER"));
            when(mapper.heads("OWNER", "O"))
                    .thenReturn(
                            List.of(
                                    new Head(
                                            1L,
                                            "PLAN",
                                            hash,
                                            3,
                                            "ACCEPTED_UNPAID",
                                            LocalDateTime.now().plusHours(1))));
            when(mapper.operations("OWNER", "OP")).thenReturn(List.of());
            var leaf =
                    new Leaf(
                            1L,
                            "L",
                            "COMPONENT",
                            "FULFILLMENT",
                            "RECIPE_MATERIAL",
                            "I",
                            "LOCATION",
                            new BigDecimal("0.125"),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            "KG",
                            hash,
                            null,
                            null,
                            null,
                            null);
            when(mapper.findLeaves("OWNER", "PLAN", "L")).thenReturn(List.of(leaf));
            service =
                    new InventoryDemandPlanPortImpl(
                            mapper,
                            acl,
                            mock(MaterialCatalogQueryPort.class),
                            posting,
                            new JsonMapper());
        }
    }
}
