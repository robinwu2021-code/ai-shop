package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.food.foodclient.RemoteFoodStockReplenishmentPort;
import ai.neargo.shop.spi.food.FoodLocalStockPort.Scope;
import ai.neargo.shop.spi.food.FoodStockReplenishmentInternalApi;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RemoteFoodStockReplenishmentPortTest {
    @Test void permanentMissingCommandIsExplicitAndTimeoutNeverBecomesMissing(){
        var api=mock(FoodStockReplenishmentInternalApi.class);var port=new RemoteFoodStockReplenishmentPort(api);var scope=new Scope("E","S");
        when(api.findCommand(any())).thenReturn(new FoodStockReplenishmentInternalApi.Lookup(null));
        assertThat(port.findCommand(scope,"REPLENISH_RECEIVE","KEY")).isNull();
        when(api.findCommand(any())).thenThrow(new ServiceCallException(CallOutcome.TIMEOUT,ServiceName.FOOD,0,"timeout"));
        assertThatThrownBy(()->port.findCommand(scope,"REPLENISH_RECEIVE","KEY")).isInstanceOf(ServiceCallException.class);
        verify(api,times(2)).findCommand(new FoodStockReplenishmentInternalApi.CommandQuery(scope,"REPLENISH_RECEIVE","KEY"));verifyNoMoreInteractions(api);
    }
    @Test void exactCumulativeQuantityAndActualSourceOriginalSurviveTransport(){
        var api=mock(FoodStockReplenishmentInternalApi.class);var port=new RemoteFoodStockReplenishmentPort(api);var scope=new Scope("E","S");
        // Missing response is a protocol error, while the captured payload still proves exact serialization inputs.
        assertThatThrownBy(()->port.receive(scope,"A","KEY","FRP",new BigDecimal("0.123456"))).isInstanceOf(IllegalStateException.class);
        verify(api).receive(new FoodStockReplenishmentInternalApi.Quantity(scope,"A","KEY","FRP",new BigDecimal("0.123456"),null));
        var fact=new StockReplenishmentSourcePort.Inbound("SOURCE","OWNER","IN",1,"ITEM","KG",new BigDecimal("0.125"),"FRP");
        assertThatThrownBy(()->port.ackReturn(scope,"A","ACK","FRP",fact)).isInstanceOf(IllegalStateException.class);
        verify(api).ackReturn(new FoodStockReplenishmentInternalApi.Ack(scope,"A","ACK","FRP",fact));
    }    @Test void sealedOriginalStateRemainsTerminalAcrossTheRemoteBoundary(){
        var api=mock(FoodStockReplenishmentInternalApi.class);var port=new RemoteFoodStockReplenishmentPort(api);var scope=new Scope("E","S");
        var sealed=new ai.neargo.shop.spi.food.FoodStockReplenishmentPort.CommandState("CANCELLED",null,null);
        when(api.commandState(any())).thenReturn(sealed);when(api.cancelCommand(any())).thenReturn(sealed);
        assertThat(port.commandState(scope,"REPLENISH_RECEIVE","OLD")).isEqualTo(sealed);
        assertThat(port.cancelCommand(scope,"ACT","REPLENISH_RECEIVE","OLD","stop")).isEqualTo(sealed);
        verify(api).cancelCommand(new FoodStockReplenishmentInternalApi.Cancel(scope,"ACT","REPLENISH_RECEIVE","OLD","stop"));
    }

}
