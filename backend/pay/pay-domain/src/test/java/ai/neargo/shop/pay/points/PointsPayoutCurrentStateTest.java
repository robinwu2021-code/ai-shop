package ai.neargo.shop.pay.points;

import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutTicket;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.payout.PayoutSnapshot;
import ai.neargo.shop.pay.points.payout.impl.PointsPayoutServiceImpl;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/** Models a transaction snapshot predating a concurrently committed successful payout. */
class PointsPayoutCurrentStateTest {
    @Test void committed_success_is_replayed_even_if_the_initial_snapshot_is_dispatching() {
        var read=mock(PointsReadStore.class);
        var config=mock(PointsConfigurationStore.class);
        var posting=mock(PointsPostingService.class);
        var result=new FundsResult("TICKET",FundsStatus.SUCCEEDED,"RECEIPT",30,null);
        var ticket=new PayoutTicket("TICKET",30,0,30,"IDEM",FundsStatus.UNKNOWN);
        var json=new ObjectMapper();
        when(read.position(PositionMove.Kind.PAYOUT.table(),"TICKET"))
                .thenReturn(Map.of("type_code","TYPE","state","DISPATCHING",
                        "payload_json",json.writeValueAsString(new PayoutSnapshot(null,ticket,null))));
        when(config.lockPayoutTicket("TICKET")).thenReturn(Map.of("type_code","TYPE","state","SUCCEEDED",
                "payload_json",json.writeValueAsString(new PayoutSnapshot(null,ticket,null,result))));
        var service=new PointsPayoutServiceImpl(read,config,posting);
        assertThatCode(() -> service.acceptResult(result)).doesNotThrowAnyException();
        assertThat(service.acceptResult(result).replayed()).isTrue();
        assertThat(service.claimDispatch("TICKET").completed().replayed()).isTrue();
        verifyNoInteractions(posting);
    }
}
