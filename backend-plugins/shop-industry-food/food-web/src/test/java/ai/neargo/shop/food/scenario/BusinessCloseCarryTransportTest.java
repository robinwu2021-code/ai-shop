package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.dto.BusinessCloseContracts.ApproveCarryReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.ExceptionVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.ProposeCarryReq;
import ai.neargo.shop.food.foodclient.RemoteBusinessCloseService;
import ai.neargo.shop.food.spi.BusinessCloseInternalApi;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Standalone close transport preserves authenticated actors, versions and original command keys. */
class BusinessCloseCarryTransportTest {
    @Test void forwardsTwoDistinctActorsAndOriginalVersionsWithoutInventingApproval() {
        var api=mock(BusinessCloseInternalApi.class);
        var service=new RemoteBusinessCloseService(api);
        var proposed=new ProposeCarryReq(3L,"OWNER","NEXT","Recover original sync intent");
        var approved=new ApproveCarryReq(4L);
        var pending=new ExceptionVO("X","C","SYNC","B","ASSIGNED","OWNER","NEXT","Reason",
                "MAKER",null,null,null,4L);
        var carried=new ExceptionVO("X","C","SYNC","B","CARRIED","OWNER","NEXT","Reason",
                "MAKER","CHECKER",LocalDateTime.of(2026,10,24,0,0),"LOCAL_NONPAY:APP",5L);
        when(api.proposeCarry(any())).thenReturn(pending);
        when(api.approveCarry(any())).thenReturn(carried);
        when(api.exceptions(any())).thenReturn(List.of(carried));
        assertThat(service.proposeCarry("E","S","MAKER","C","X",proposed,"P")).isEqualTo(pending);
        assertThat(service.approveCarry("E","S","CHECKER","C","X",approved,"A")).isEqualTo(carried);
        assertThat(service.exceptions("E","S","READER","C")).containsExactly(carried);
        verify(api).proposeCarry(new BusinessCloseInternalApi.ProposeCarryCommand(
                "E","S","MAKER","C","X",proposed,"P"));
        verify(api).approveCarry(new BusinessCloseInternalApi.ApproveCarryCommand(
                "E","S","CHECKER","C","X",approved,"A"));
        verify(api).exceptions(new BusinessCloseInternalApi.GetQuery("E","S","READER","C"));
    }
}
