package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.portal.internal.InternalIndustryOrderEndpoint;
import ai.neargo.shop.spi.trade.IndustryOrderInternalApi;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class InternalDiningRoomOrderEndpointTest {
    @Test void dedicatedOriginalEndpointChecksExistingServiceTokenBeforeInvokingStaffCapability() {
        var orders=mock(IndustryOrderPort.class); var endpoint=new InternalIndustryOrderEndpoint(orders,"wire");
        var command=new IndustryOrderPort.Command(new IndustryOrderPort.Identity("MAIN","E","S","BUYER","STAFF"),
                new PricingSourceRef("FOOD","CART","CART",3,null,null),null,"QUOTE","a".repeat(64),
                IndustryOrderPort.Mode.POSTPAY,null);
        var request=new IndustryOrderInternalApi.Place(command,"ORIGINAL_BATCH");
        assertThat(endpoint.placeForDiningRoom(request,null).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(orders);
        when(orders.placeForDiningRoom(command,"ORIGINAL_BATCH")).thenReturn(new IndustryOrderPort.Result("ORDER","SUB",
                "QUOTE","WAIT_PAY","CNY",100L,3,3,List.of(),"POSTPAY","WAIT_FULFILL",true));
        assertThat(endpoint.placeForDiningRoom(request,"wire").getStatusCode().value()).isEqualTo(200);
        verify(orders).placeForDiningRoom(command,"ORIGINAL_BATCH");
    }
    @Test void staffCancellationTokenGateKeepsOriginalKeyAndFulfillment() {
        var orders = mock(IndustryOrderPort.class);
        var endpoint = new InternalIndustryOrderEndpoint(orders, "wire");
        var command = new IndustryOrderPort.Command(
                new IndustryOrderPort.Identity("MAIN", "E", "S", "BUYER", "STAFF"),
                new PricingSourceRef("FOOD", "CART", "CART", 3, null, null), null, "QUOTE", "a".repeat(64),
                IndustryOrderPort.Mode.POSTPAY, null);
        var request = new IndustryOrderInternalApi.StoreCancel(command, "BATCH", "DINE_IN");
        assertThat(endpoint.cancelForStore(request, null).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(orders);
        when(orders.cancelForStore(command, "BATCH", "DINE_IN")).thenReturn(new IndustryOrderPort.Result(
                "ORDER", "SUB", "QUOTE", "RELEASED", null, null, 3, 3, List.of(), "POSTPAY", null, false));
        assertThat(endpoint.cancelForStore(request, "wire").getStatusCode().value()).isEqualTo(200);
        verify(orders).cancelForStore(command, "BATCH", "DINE_IN");
    }
}
