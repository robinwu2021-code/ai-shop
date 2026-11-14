package ai.neargo.shop.payclient;
import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.order.points.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class OrderPointsRefundRoutingTest {
 @Test void persistedVersionSelectsUnifiedRefundWithoutLegacyFallback() {
  var refunds=mock(OrderPointsRefundCoordinator.class);var bindings=mock(OrderPointsBindingService.class);
  var legacy=mock(PointsRefundPort.class);var ops=mock(PointsOperationsPort.class);
  var beans=new StaticListableBeanFactory();beans.addBean("ops",ops);
  var routing=new OrderPointsRefundRouting(refunds,bindings,beans.getBeanProvider(PointsOperationsPort.class));
  var command=new PointsRefundPort.RefundCommand("AS","S","U","P","CNY",1,100,100,List.of(new PointsRefundPort.RefundLine("I",100)),null);
  when(refunds.inspect("AS")).thenReturn(new OrderPointsRefundCoordinator.Binding("AS","S",OrderPointsRefundCoordinator.Phase.STAGED,1,command,null,null,null));
  var snapshot=new PointsOrderPlacementCoordinator.EarningSnapshot(3,1,List.of(),List.of(),"ORDER_EARN_V3");
  var admission=new OrderPointsBindingService.Admission("R","O","S","U","M","ST","P","CNY","ONLINE",0,false,true,new OrderPointsBindingService.Snapshot(100,1,1,1,1,new ObjectMapper().writeValueAsString(snapshot),"null",null,List.of()));
  when(bindings.find("S")).thenReturn(new OrderPointsBindingService.Binding("B",admission,OrderPointsBindingService.Phase.BOUND,1,null));
  routing.route("AS",legacy).prepare(command);
  var ctx=new PointsOperationsPort.Context("TRADE:REFUND:PREPARE:AS",new PointsOperationsPort.Operation(PointsOperationsPort.Source.TRADE,"AS","ORDER_REFUND_V3","P"),"CNY");
  verify(ops).prepareRefund(ctx,command);verifyNoInteractions(legacy);
  doThrow(new IllegalArgumentException("POINTS_CASH_REFUND_NOT_CONFIRMED")).when(ops).confirmRefund(any(),any());
  assertThatThrownBy(()->routing.route("AS",legacy).confirm(new PointsRefundPort.RefundConfirmation("PREP","HASH","CASH",null,null))).hasMessage("POINTS_CASH_REFUND_NOT_CONFIRMED");
  verifyNoInteractions(legacy);
  assertThat(routing.route("UNKNOWN",legacy)).isSameAs(legacy);
 }
}
