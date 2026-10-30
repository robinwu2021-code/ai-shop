package ai.neargo.shop.food.channel.port;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionInternalApi;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionInternalApi.*;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.*;
import org.junit.jupiter.api.Test;

class CoreFoodStockProjectionPortTest {
    @Test void explicitAbsenceAllowsFirstDeliveryButEmptyTransportIsNeverAbsence() {
        var api=mock(InventoryFoodStockProjectionInternalApi.class);
        var client=new CoreFoodStockProjectionPortImpl(()->api);var scope=new Scope("E","S");
        when(api.queryReceipt(any())).thenReturn(new ReceiptLookup(null));
        assertThat(client.queryReceipt(scope,"EVENT","HASH")).isNull();
        verify(api).queryReceipt(new Query(scope,"EVENT","HASH"));
        when(api.queryReceipt(any())).thenReturn(null);
        assertThatThrownBy(()->client.queryReceipt(scope,"EVENT","HASH")).isInstanceOf(IllegalStateException.class);
        verify(api,never()).apply(any());
    }
}
