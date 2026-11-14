package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Branch;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Leaf;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.RetailChannelOrderSourceInternalApi;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

class RemoteRetailChannelOrderSourcePortTest {
    final RetailChannelOrderSourceInternalApi api = mock(RetailChannelOrderSourceInternalApi.class);
    final RemoteRetailChannelOrderSourcePort remote =
            new RemoteRetailChannelOrderSourcePort(() -> api, Clock.systemUTC());
    final Reference reference =
            new Reference(
                    new IndustryChannelOrderPort.Scope("MAIN", "E", "S"),
                    "RETAIL",
                    "SOURCE",
                    1,
                    "a".repeat(64),
                    "IMPORT");

    Plan plan(String store, String fulfillment) {
        var raw =
                new Plan(
                        new Scope("MAIN", "E", "RESERVATION"),
                        "RESERVATION",
                        null,
                        List.of(
                                new Branch(
                                        "COMPONENT",
                                        fulfillment,
                                        store,
                                        "FINISHED_SKU",
                                        BigDecimal.ONE,
                                        "PIECE",
                                        List.of(
                                                new Leaf(
                                                        "LEAF",
                                                        "SKU",
                                                        BigDecimal.ONE,
                                                        "PIECE",
                                                        "b".repeat(64))))));
        return new Plan(
                raw.scope(), raw.planNo(), InventoryDemandPlanFacts.hash(raw), raw.branches());
    }

    @Test
    void actualPreparationGenerationMustRetainOriginalStoreAndFulfillmentScope() {
        when(api.demand(any())).thenReturn(plan("S", "SUBORDER"));
        assertThat(remote.demandPlan(reference, "RESERVATION", "SUBORDER").planNo())
                .isEqualTo("RESERVATION");
        when(api.demand(any())).thenReturn(plan("OTHER", "SUBORDER"));
        assertThatThrownBy(() -> remote.demandPlan(reference, "RESERVATION", "SUBORDER"))
                .isInstanceOf(IllegalStateException.class);
        when(api.demand(any())).thenReturn(plan("S", "OTHER_ORDER"));
        assertThatThrownBy(() -> remote.demandPlan(reference, "RESERVATION", "SUBORDER"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void foodReferenceCannotRouteToRetailOriginalOwner() {
        var food =
                new Reference(
                        reference.scope(),
                        "FOOD",
                        reference.sourceNo(),
                        reference.sourceVersion(),
                        reference.sourceHash(),
                        reference.importRef());
        assertThatThrownBy(() -> remote.capture(food)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(api);
    }
}
