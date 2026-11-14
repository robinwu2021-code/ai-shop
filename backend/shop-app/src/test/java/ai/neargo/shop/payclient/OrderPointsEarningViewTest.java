package ai.neargo.shop.payclient;
import ai.neargo.shop.pay.points.admin.*;
import ai.neargo.shop.pay.points.model.*;
import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.order.points.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class OrderPointsEarningViewTest {
    @Test void accepted_work_is_processing_until_real_grant_exists() {
        var bindings=mock(OrderPointsBindingService.class);var inspect=mock(PointsInspectionService.class);
        var types=mock(PointsAdministrationService.class);var ops=mock(PointsOperationsPort.class);
        var lines=List.of(new PointsEarnPort.EarnLine("I","G","C",1,10000,0,new PointsEarnPort.EarnRule(PointsEarnPort.RuleMode.RATIO,100)));
        var frozen=new PointsOrderPlacementCoordinator.EarningSnapshot(3,1,lines,List.of(),"ORDER_EARN_V3");
        var a=new OrderPointsBindingService.Admission("R","O","S","U","M","ST","P","CNY","ONLINE",0,true,true,
            new OrderPointsBindingService.Snapshot(10000,100,100,1,1,new ObjectMapper().writeValueAsString(frozen),"null",null,List.of(new PointsRedeemPort.Item("I",10000))));
        when(bindings.find("S")).thenReturn(new OrderPointsBindingService.Binding("B",a,OrderPointsBindingService.Phase.BOUND,1,null));
        when(types.types()).thenReturn(List.of(new PointType("P","平台积分","CN","CNY",2,new RedeemRate(1,1),10000,365,PointType.Status.ACTIVE)));
        when(ops.status(any(),any())).thenReturn(new PointsOperationsPort.Receipt("K",PointsOperationsPort.Status.ACCEPTED,"ACCEPTED","REF",null));
        var order=mock(OrderVO.class);when(order.orderNo()).thenReturn("S");when(order.payOrderNo()).thenReturn("O");when(order.merchantNo()).thenReturn("M");when(order.paidAt()).thenReturn(1L);
        var view=new OrderPointsEarningViewService(bindings,inspect,types,ops);
        var result=view.read(order).getFirst();assertThat(result.state()).isEqualTo("PROCESSING");assertThat(result.availablePoints()).isZero();assertThat(result.expectedPoints()).isEqualTo(100);
        when(ops.status(any(),any())).thenReturn(new PointsOperationsPort.Receipt("K",PointsOperationsPort.Status.RETRYABLE,"RETRY","REF","POINTS_POOL_INSUFFICIENT"));
        result=view.read(order).getFirst();assertThat(result.state()).isEqualTo("RETRYABLE");assertThat(result.reason()).isEqualTo("POINTS_POOL_INSUFFICIENT");
        when(inspect.earning("P","S")).thenReturn(new PointsInspectionService.EarningView("GRANTED",100,100,0,100,0,null));
        assertThat(view.read(order).getFirst().state()).isEqualTo("PENDING");
        when(inspect.earning("P","S")).thenReturn(new PointsInspectionService.EarningView("GRANTED",100,100,100,0,0,null));
        assertThat(view.read(order).getFirst().state()).isEqualTo("AVAILABLE");
        var refunds=mock(OrderPointsRefundCoordinator.class);
        var refundRows=mock(ai.neargo.shop.trade.order.mapper.OrderPointsRefundBindingMapper.class);
        view.setRefundProgress(refunds,refundRows);
        when(refunds.confirmedTotals("S")).thenReturn(new OrderPointsRefundCoordinator.ConfirmedTotals(25,0));
        when(refundRows.forSubOrder("S")).thenReturn(List.of(Map.of("phase","CASH_PENDING")));
        when(inspect.position(anyString(),any(),anyString())).thenReturn(new PointsInspectionService.PositionView("P",PositionMove.Kind.REDEEM,"R","PAYABLE",100,0,1));
        result=view.read(order).getFirst();
        assertThat(result.redemptionState()).isEqualTo("PARTIALLY_RETURNED");assertThat(result.returnedPoints()).isEqualTo(25);
        assertThat(result.refundState()).isEqualTo("PROCESSING");
        when(refunds.confirmedTotals("S")).thenReturn(new OrderPointsRefundCoordinator.ConfirmedTotals(100,0));
        when(refundRows.forSubOrder("S")).thenReturn(List.of(Map.of("phase","COMMITTED")));
        result=view.read(order).getFirst();assertThat(result.redemptionState()).isEqualTo("RETURNED");assertThat(result.refundState()).isEqualTo("COMPLETED");
        when(order.paidAt()).thenReturn(null);when(order.status()).thenReturn("CANCELLED");
        assertThat(view.read(order).getFirst().state()).isEqualTo("SKIPPED");
        assertThat(view.read(order).getFirst().expectedPoints()).isZero();
        when(order.merchantNo()).thenReturn("OTHER");assertThatThrownBy(()->view.read(order)).hasMessage("POINTS_ORDER_BINDING_MISMATCH");
    }
}
