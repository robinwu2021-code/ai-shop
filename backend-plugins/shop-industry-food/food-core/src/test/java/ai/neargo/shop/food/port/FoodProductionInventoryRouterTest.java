package ai.neargo.shop.food.port;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.food.FoodProductionStockPort;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FoodProductionInventoryRouterTest {
    private final InventoryProductionPort.Command command=new InventoryProductionPort.Command(
            "E","S","EVENT","PRODUCTION",Instant.parse("2026-10-29T00:00:00Z"),List.of());
    @Test void unavailableOrUnrecognizedOriginalOwnerNeverFallsBackToLegacyInventory() {
        var legacy=mock(InventoryProductionPort.class);
        assertThatThrownBy(()->new FoodProductionInventoryRouter(()->null,()->legacy).query(command))
                .isInstanceOf(BizException.class);
        var local=mock(FoodProductionStockPort.class);
        when(local.owner(command)).thenReturn("OTHER");
        assertThatThrownBy(()->new FoodProductionInventoryRouter(()->local,()->legacy).consume(command))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(legacy);
    }
    @Test void localUnknownQueryIsPreservedAndDoesNotInvokeNativeInventory() {
        var local=mock(FoodProductionStockPort.class);var legacy=mock(InventoryProductionPort.class);
        when(local.owner(command)).thenReturn("FOOD_LOCAL");
        var unknown=new InventoryProductionPort.Receipt("EVENT","hash","UNKNOWN",null,List.of(),null,null);
        when(local.query(command)).thenReturn(unknown);
        assertThat(new FoodProductionInventoryRouter(()->local,()->legacy).query(command)).isSameAs(unknown);
        verify(local).query(command);verify(local,never()).consume(command);verifyNoInteractions(legacy);
    }
    @Test void explicitLossReasonAndOperatorReachOnlyLocalOriginalOwner() {
        var local=mock(FoodProductionStockPort.class);var legacy=mock(InventoryProductionPort.class);
        when(local.owner(command)).thenReturn("FOOD_LOCAL");
        var router=new FoodProductionInventoryRouter(()->local,()->legacy);
        assertThatThrownBy(()->router.validateDisposition(command,null,null,"U")).isInstanceOf(BizException.class);
        router.validateDisposition(command,"LOSS","DAMAGED","U");
        var reverse=new InventoryProductionPort.Reverse(command,"R","damaged","LOSS","DAMAGED","U");
        router.reverse(reverse);verify(local).reverse(reverse);verifyNoInteractions(legacy);
    }
    @Test void oldLegacyReversalRetainsOriginalTransportAndRejectsLocalDispositions() {
        var local=mock(FoodProductionStockPort.class);var legacy=mock(InventoryProductionPort.class);
        when(local.owner(command)).thenReturn("LEGACY_INVENTORY");
        var router=new FoodProductionInventoryRouter(()->local,()->legacy);
        var reverse=new InventoryProductionPort.Reverse(command,"R","original");
        router.reverse(reverse);verify(legacy).reverse(reverse);
        assertThatThrownBy(()->router.reverse(new InventoryProductionPort.Reverse(command,"R2","reason","RESALE","UNUSED","U")))
                .isInstanceOf(BizException.class);
        verifyNoMoreInteractions(legacy);verify(local,never()).reverse(any());
    }
}
