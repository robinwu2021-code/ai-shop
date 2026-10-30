package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.foodclient.RemoteFoodLocalStockPort;
import ai.neargo.shop.spi.food.FoodLocalStockInternalApi;
import ai.neargo.shop.spi.food.FoodLocalStockPort.*;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteFoodLocalStockPortTest {
    private final Scope scope=new Scope("E","S");
    @Test void explicitAbsentReservationDiffersFromMissingOrFailedTransport() {
        var api=mock(FoodLocalStockInternalApi.class);var remote=new RemoteFoodLocalStockPort(api);
        when(api.findReservation(any())).thenReturn(new FoodLocalStockInternalApi.ReservationLookup(null));
        assertThat(remote.findReservation(scope,"CORE","SOURCE",7)).isNull();
        verify(api).findReservation(new FoodLocalStockInternalApi.SourceQuery(scope,"CORE","SOURCE",7));
        when(api.findReservation(any())).thenReturn(null);
        assertThatThrownBy(()->remote.findReservation(scope,"CORE","SOURCE",7)).isInstanceOf(IllegalStateException.class);
    }
    @Test void timeoutNeverRetriesOrTurnsIntoAnAbsentCommand() {
        var api=mock(FoodLocalStockInternalApi.class);var remote=new RemoteFoodLocalStockPort(api);
        when(api.findCommand(any())).thenThrow(new ServiceCallException(CallOutcome.TIMEOUT,ServiceName.FOOD,0,"timeout"));
        assertThatThrownBy(()->remote.findCommand(scope,"ISSUE","original-key","original-hash"))
                .isInstanceOf(ServiceCallException.class);
        verify(api).findCommand(new FoodLocalStockInternalApi.CommandQuery(scope,"ISSUE","original-key","original-hash"));
        verifyNoMoreInteractions(api);
    }
    @Test void exactReturnQuantityOriginalLedgerAndActualReasonSurviveRemoteBoundary() {
        var api=mock(FoodLocalStockInternalApi.class);var remote=new RemoteFoodLocalStockPort(api);
        var command=new Command("key","hash","CORE","source",8,11);
        var targets=List.of(new ReturnTarget("L","MOV",new BigDecimal("0.123456"),"RETURN","LOSS","DAMAGED","reason","U",true));
        var receipt=new Receipt("CMD","RESERVATION","FOOD_LOCAL","plan","COMPLETED",12,List.of(),List.of("LOSS_MOV"));
        when(api.returnIssued(any())).thenReturn(receipt);
        assertThat(remote.returnIssued(scope,command,"RESERVATION",targets)).isSameAs(receipt);
        verify(api).returnIssued(new FoodLocalStockInternalApi.ReturnCommand(scope,command,"RESERVATION",targets));
    }
    @Test void conflictAndForbiddenRemainBusinessErrorsWithoutRetry() {
        for(int status:List.of(403,409)) {
            var api=mock(FoodLocalStockInternalApi.class);var remote=new RemoteFoodLocalStockPort(api);
            when(api.getReservation(any())).thenThrow(new ServiceCallException(CallOutcome.REMOTE_ERROR,ServiceName.FOOD,status,"safe"));
            assertThatThrownBy(()->remote.getReservation(scope,"RESERVATION")).isInstanceOf(BizException.class);
            verify(api).getReservation(new FoodLocalStockInternalApi.ReservationQuery(scope,"RESERVATION"));
            verifyNoMoreInteractions(api);
        }
    }
}
