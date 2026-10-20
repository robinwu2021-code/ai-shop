package ai.neargo.shop.payclient;

import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.order.points.*;
import ai.neargo.shop.trade.order.points.impl.OrderPointsCoordinatorImpl;
import ai.neargo.shop.trade.order.mapper.OrderPointsBindingMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class OrderPointsUnifiedDeliveryTest {
    final PointsFactPort legacy=mock(PointsFactPort.class);
    final PointsOperationsPort unified=mock(PointsOperationsPort.class);
    final OrderPointsSourcePort source=mock(OrderPointsSourcePort.class);
    final OrderPointsBindingService bindings=mock(OrderPointsBindingService.class);
    final ObjectMapper json=new ObjectMapper();
    final PointsRefundPort.PaymentSnapshot payment=new PointsRefundPort.PaymentSnapshot("SERVICE_PROVIDER","WECHAT","M","ST","ACC","SUB","TX","OUT",100);
    final Instant at=Instant.parse("2026-10-20T00:00:00Z");
    OrderPointsCoordinatorImpl coordinator;
    PointsOrderEvent earning;
    PointsFactPort.LifecycleFact paid;
    @BeforeEach void setup() {
        var factory=new StaticListableBeanFactory();factory.addBean("legacy",legacy);factory.addBean("unified",unified);factory.addBean("source",source);
        coordinator=new OrderPointsCoordinatorImpl(mock(PointsIntegrationPolicyService.class),bindings,mock(OrderPointsBindingMapper.class),
                factory.getBeanProvider(PointsRedeemPort.class),factory.getBeanProvider(PointsFactPort.class),factory.getBeanProvider(PointsObligationPort.class),factory.getBeanProvider(OrderPointsSourcePort.class));
        coordinator.setUnifiedOperations(factory.getBeanProvider(PointsOperationsPort.class));
        var lines=List.of(new PointsEarnPort.EarnLine("I","G","C",1,100,0,new PointsEarnPort.EarnRule(PointsEarnPort.RuleMode.RATIO,1000)));
        paid=PointsLifecycleFacts.sign(new PointsFactPort.LifecycleFact("ORDER:PAID:S",null,"S","U","P",1,PointsFactPort.FactKind.PAID,100,0,List.of(new PointsFactPort.LineSnapshot("I",1,100,0)),at,payment),json::writeValueAsString);
        var grant=new PointsEarnPort.GrantCommand("ORDER:EARN:S","S","U","P","ST","WECHAT","ONLINE",1,lines,"M","MP",payment);
        earning=new PointsOrderEvent("O","S","M","ST","U","P","CNY",1,at,new SourceFactsPort.Fact(2,null,grant,null,null,null));
        when(source.find("ORDER:PAID:S")).thenReturn(Optional.of(new PointsOrderEvent("O","S","M","ST","U","P","CNY",1,at,new SourceFactsPort.Fact(1,paid,null,null,null,null))));
        binding(3,lines);
    }
    void binding(int version,List<PointsEarnPort.EarnLine> lines) {
        var snapshot=new PointsOrderPlacementCoordinator.EarningSnapshot(version,1,lines,List.of(),version==3?"ORDER_EARN_V3":null);
        var a=new OrderPointsBindingService.Admission("R","O","S","U","M","ST","P","CNY","ONLINE",0,true,false,
                new OrderPointsBindingService.Snapshot(100,0,0,1,1,json.writeValueAsString(snapshot),"null",payment,List.of(new PointsRedeemPort.Item("I",100))));
        when(bindings.find("S")).thenReturn(new OrderPointsBindingService.Binding("B",a,OrderPointsBindingService.Phase.BOUND,1,null));
    }
    @Test void new_snapshot_uses_stable_operation_and_original_paid_proof_on_every_retry() {
        coordinator.onSourceEvent(earning);coordinator.onSourceEvent(earning);
        var context=new PointsOperationsPort.Context("TRADE:EARN:B",new PointsOperationsPort.Operation(PointsOperationsPort.Source.TRADE,"S","ORDER_EARN_V3","P"),"CNY");
        verify(unified,times(2)).submitEarning(context,new PointsOperationsPort.Earning(earning.fact().earning(),paid));
        verifyNoInteractions(legacy);
    }
    @Test void historical_snapshot_remains_on_legacy_route() {
        binding(2,earning.fact().earning().lines());coordinator.onSourceEvent(earning);
        verify(legacy).acceptEarning(earning.fact().earning());verifyNoInteractions(unified);
    }
    @Test void missing_payment_proof_does_not_fall_back_to_legacy() {
        when(source.find(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(()->coordinator.onSourceEvent(earning)).hasMessage("POINTS_PAID_PROOF_REQUIRED");
        verifyNoInteractions(legacy,unified);
    }
    @Test void future_snapshot_version_is_rejected() {
        binding(4,earning.fact().earning().lines());
        assertThatThrownBy(()->coordinator.onSourceEvent(earning)).hasMessage("POINTS_EARNING_SCHEMA_UNSUPPORTED");
        verifyNoInteractions(legacy,unified);
    }
}
