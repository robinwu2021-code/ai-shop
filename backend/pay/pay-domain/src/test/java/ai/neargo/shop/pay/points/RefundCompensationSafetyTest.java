package ai.neargo.shop.pay.points;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ai.neargo.shop.pay.points.refund.impl.*;
import ai.neargo.shop.pay.points.refund.PointsRefundCompensationService.CompensationSnapshot;
import ai.neargo.shop.pay.points.refund.impl.PointsRefundPreparationServiceImpl.RefundSnapshot;
import ai.neargo.shop.pay.points.store.*;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.*;
import ai.neargo.shop.spi.points.PointsRefundPort.*;
import ai.neargo.shop.spi.settle.RefundSafety;
import ai.neargo.shop.spi.pay.RefundQueryResult.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

class RefundCompensationSafetyTest {
    PointsReadStore read=mock(PointsReadStore.class);
    PointsConfigurationStore config=mock(PointsConfigurationStore.class);
    PointsCashRefundReadStore cash=mock(PointsCashRefundReadStore.class);
    PointsPostingService posting=mock(PointsPostingService.class);
    @SuppressWarnings("unchecked") ObjectProvider<PointsFundsGateway> gateways=mock(ObjectProvider.class);
    PointsRefundCompensationServiceImpl service=new PointsRefundCompensationServiceImpl(read,config,cash,posting,gateways);
    RefundAbort abort=new RefundAbort("P","H","CASH");
    void fixture(String state, boolean collected, boolean compensation) {
        var payment=new PaymentSnapshot("DIRECT","TEST","M","S","1","SM","TRADE","OUT",100);
        var command=new RefundCommand("AS","SUB","U","T","CNY",1,100,100,List.of(),payment);
        var prep=new RefundPreparation("P","T",1,"H",0,0,0,0,10,90,0,collected?"COLLECT":null,"CASH_AUTHORIZED");
        var funds=new FundsCommand("X","IDEM",FundsPurpose.COLLECTION_REVERSAL,null,"CNY",10);
        var snapshot=new RefundSnapshot(command,prep,null,null,Map.of(),funds,null,null,
                new FundsResult("X",FundsStatus.SUCCEEDED,"RECEIPT",10,null),null,
                compensation?new CompensationSnapshot(abort,funds,null):null);
        var head=Map.<String,Object>of("state",state,"type_code","T","payload_json",new ObjectMapper().writeValueAsString(snapshot));
        when(read.position(anyString(),eq("P"))).thenReturn(head);
        when(config.lockRefundPreparation("P")).thenReturn(head);
        var safety=new RefundSafety(Status.MANUAL_REQUIRED,false,true,"REFUND_LATE_SUCCESS_FROZEN");
        var cashRow=new PointsCashRefundReadStore.CashRefund("CASH","REFUND","AS","SUB","U","CNY",90,"FAILED","TEST",null,null,null,null,safety);
        when(cash.lockRefund("CASH")).thenReturn(cashRow);
    }
    @Test void lateSuccessBlocksFirstCompensationSubmission() {
        fixture("COMPENSATION_REQUIRED",true,true);
        assertThatThrownBy(() -> service.claim("P")).hasMessage("POINTS_REFUND_CASH_OUTCOME_UNKNOWN");
        verifyNoInteractions(posting);
        var order=inOrder(cash,config);order.verify(cash).lockRefund("CASH");order.verify(config).lockTypeAndPool("T");
    }
    @Test void alreadyDispatchedCompensationStillQueriesItsOriginalCommand() {
        fixture("COMPENSATION_UNKNOWN",true,true);
        var claim=service.claim("P");
        assertThat(claim.submit()).isFalse();assertThat(claim.command().ticketNo()).isEqualTo("X");
        verifyNoInteractions(posting);
    }
    @Test void lateSuccessBlocksPreparingANewCompensation() {
        fixture("CASH_AUTHORIZED",true,false);
        assertThatThrownBy(() -> service.prepare(abort,1)).hasMessage("POINTS_REFUND_CASH_OUTCOME_UNKNOWN");
        verifyNoInteractions(posting);
    }
    @Test void lateSuccessBlocksAbortWithoutCollection() {
        fixture("CASH_AUTHORIZED",false,false);
        var service=new PointsRefundAbortServiceImpl(read,config,cash,posting);
        assertThatThrownBy(() -> service.abort(abort)).hasMessage("POINTS_REFUND_CASH_OUTCOME_UNKNOWN");
        verifyNoInteractions(posting);
    }
}
