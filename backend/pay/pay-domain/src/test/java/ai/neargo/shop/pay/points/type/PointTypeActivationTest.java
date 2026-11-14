package ai.neargo.shop.pay.points.type;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.market.MarketService;
import ai.neargo.shop.pay.points.lifecycle.TypeRetirementService;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.type.impl.PointTypeServiceImpl;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PointTypeActivationTest {
    private final MarketService markets = mock(MarketService.class);
    private final PointsReadStore read = mock(PointsReadStore.class);
    private final PointsConfigurationStore config = mock(PointsConfigurationStore.class);
    private final PointTypeService service = new PointTypeServiceImpl(markets, config, read, mock(TypeRetirementService.class));
    PointTypeActivationTest() {
        when(markets.find("CN")).thenReturn(Optional.of(new MarketService.MarketRow("CN", "China", "CNY", 2, "Asia/Shanghai", 1, true, 1)));
    }
    @Test void a_foreign_currency_type_never_becomes_the_cny_order_default() {
        when(read.type("AED_POINTS")).thenReturn(Optional.of(new PointType("AED_POINTS", "AED points", "CN", "AED", 2, new RedeemRate(1, 1), 3000, 365, PointType.Status.DRAFT)));
        when(read.activeType("CN")).thenReturn(Optional.empty());
        service.activate("AED_POINTS", "OPS");
        verify(config).changeTypeStatus("AED_POINTS", PointType.Status.DRAFT, PointType.Status.ACTIVE, null, "OPS");
    }
    private PointType type(String code, PointType.Status status) {
        return new PointType(code, code, "CN", "CNY", 2, new RedeemRate(1, 1), 3000, 365, status);
    }
    @Test void additional_type_activates_without_replacing_the_order_default() {
        when(read.type("SECOND")).thenReturn(Optional.of(type("SECOND", PointType.Status.DRAFT)));
        when(read.activeType("CN")).thenReturn(Optional.of(type("FIRST", PointType.Status.ACTIVE)));
        service.activate("SECOND", "OPS");
        verify(config).changeTypeStatus("SECOND", PointType.Status.DRAFT, PointType.Status.ACTIVE, null, "OPS");
    }
    @Test void draining_an_independent_type_does_not_claim_the_default_slot() {
        when(read.type("SECOND")).thenReturn(Optional.of(type("SECOND", PointType.Status.ACTIVE)));
        when(read.activeType("CN")).thenReturn(Optional.of(type("FIRST", PointType.Status.ACTIVE)));
        service.drain("SECOND", "OPS");
        verify(config).changeTypeStatus("SECOND", PointType.Status.ACTIVE, PointType.Status.DRAINING, null, "OPS");
    }
    @Test void draining_default_remains_available_for_its_existing_obligations() {
        when(read.type("FIRST")).thenReturn(Optional.of(type("FIRST", PointType.Status.ACTIVE)));
        when(read.activeType("CN")).thenReturn(Optional.of(type("FIRST", PointType.Status.ACTIVE)));
        service.drain("FIRST", "OPS");
        verify(config).changeTypeStatus("FIRST", PointType.Status.ACTIVE, PointType.Status.DRAINING, "CN", "OPS");
    }
    @Test void the_first_type_still_becomes_the_market_default() {
        when(read.type("FIRST")).thenReturn(Optional.of(type("FIRST", PointType.Status.DRAFT)));
        when(read.activeType("CN")).thenReturn(Optional.empty());
        service.activate("FIRST", "OPS");
        verify(config).changeTypeStatus("FIRST", PointType.Status.DRAFT, PointType.Status.ACTIVE, "CN", "OPS");
    }
    @Test void a_rejected_default_claim_can_still_activate_as_an_independent_type() {
        when(read.type("SECOND")).thenReturn(Optional.of(type("SECOND", PointType.Status.DRAFT)));
        when(read.activeType("CN")).thenReturn(Optional.empty());
        doThrow(new IllegalArgumentException("POINTS_ACTIVE_EXISTS")).when(config)
                .changeTypeStatus("SECOND", PointType.Status.DRAFT, PointType.Status.ACTIVE, "CN", "OPS");
        service.activate("SECOND", "OPS");
        verify(config).changeTypeStatus("SECOND", PointType.Status.DRAFT, PointType.Status.ACTIVE, null, "OPS");
    }
}
