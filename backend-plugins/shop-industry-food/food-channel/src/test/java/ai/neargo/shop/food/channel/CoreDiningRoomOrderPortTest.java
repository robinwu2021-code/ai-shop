package ai.neargo.shop.food.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.channel.port.CoreIndustryOrderPortImpl;
import ai.neargo.shop.spi.trade.IndustryOrderInternalApi;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreDiningRoomOrderPortTest {
    @Test void diningRoomPlacementUsesExplicitOriginalCapabilityAndSamePersistedBatchKey() {
        var api=mock(IndustryOrderInternalApi.class);
        var command=new IndustryOrderPort.Command(new IndustryOrderPort.Identity("MAIN","E","S","BUYER","STAFF"),
                new PricingSourceRef("FOOD","CART","CART",3,null,null),null,"QUOTE","a".repeat(64),
                IndustryOrderPort.Mode.POSTPAY,null);
        var receipt=new IndustryOrderPort.Result("ORDER","SUB","QUOTE","WAIT_PAY","CNY",100L,3,3,List.of(),
                "POSTPAY","WAIT_FULFILL",true);
        when(api.placeForDiningRoom(any())).thenReturn(receipt);
        assertThat(new CoreIndustryOrderPortImpl(() -> api).placeForDiningRoom(command,"ORIGINAL_BATCH")).isEqualTo(receipt);
        verify(api).placeForDiningRoom(new IndustryOrderInternalApi.Place(command,"ORIGINAL_BATCH"));
        verify(api,never()).placeForStore(any()); verify(api,never()).place(any());
    }
    @Test void cancellationKeepsOriginalFulfillmentAndDoesNotCallConsumerCancellation() {
        var api = mock(IndustryOrderInternalApi.class);
        var command = new IndustryOrderPort.Command(
                new IndustryOrderPort.Identity("MAIN", "E", "S", "BUYER", "STAFF"),
                new PricingSourceRef("FOOD", "CART", "CART", 3, null, null), null, "QUOTE", "a".repeat(64),
                IndustryOrderPort.Mode.POSTPAY, null);
        var receipt = new IndustryOrderPort.Result("ORDER", "SUB", "QUOTE", "RELEASED", null, null,
                3, 3, List.of(), "POSTPAY", null, false);
        when(api.cancelForStore(any())).thenReturn(receipt);
        assertThat(new CoreIndustryOrderPortImpl(() -> api).cancelForStore(command, "BATCH", "DINE_IN"))
                .isEqualTo(receipt);
        verify(api).cancelForStore(new IndustryOrderInternalApi.StoreCancel(command, "BATCH", "DINE_IN"));
        verify(api, never()).cancel(any());
    }
}
