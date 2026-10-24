package ai.neargo.shop.invbridge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/** A completion outbox event is physical evidence, never evidence that the customer paid. */
class FulfillmentStockIssueConsumerTest {
    private final StockPort stock=mock(StockPort.class);
    private final StoreOrderMapper children=mock(StoreOrderMapper.class);
    private final PaymentMapper parents=mock(PaymentMapper.class);
    private final IndustryOrderSourceMapper sources=mock(IndustryOrderSourceMapper.class);
    private final OrderItemMapper items=mock(OrderItemMapper.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<InventoryReservationPort> providers=mock(ObjectProvider.class);
    private final InventoryReservationPort inventory=mock(InventoryReservationPort.class);
    private final FulfillmentStockIssueConsumer consumer=new FulfillmentStockIssueConsumer(stock,new ObjectMapper(),
            children,parents,sources,items,providers);
    private final InventoryReservationPort.Scope scope=new InventoryReservationPort.Scope("MAIN","E","PAY-1");
    private final SysOutbox event=event();
    private final OrdOrder parent=new OrdOrder();
    private OrdSubOrder child;
    private void setup(int lifecycle,boolean paid) {
        var sub=new OrdSubOrder(); sub.setSubOrderNo("SUB-1"); sub.setOrderNo("PAY-1");
        sub.setTenantNo("MAIN"); sub.setEntityNo("E"); sub.setStoreNo("S"); sub.setStatus("COMPLETED");
        child=sub;
        sub.setInventoryLifecycleVersion(lifecycle); when(children.selectOne(any())).thenReturn(sub);
        parent.setTenantNo("MAIN"); parent.setOrderNo("PAY-1");
        parent.setStatus(paid?"PAID":"WAIT_PAY"); parent.setPaidAt(paid?1L:null);
        when(parents.selectOne(any())).thenReturn(parent);
        if(lifecycle==3) {
            var source=new OrdIndustryOrderSource(); source.setOrderMode("POSTPAY"); source.setStatus("ACCEPTED");
            when(sources.selectOne(any())).thenReturn(source);
            var item=new OrdItem(); item.setSkuNo("SKU"); item.setQty(2); when(items.selectList(any())).thenReturn(List.of(item));
            when(providers.orderedStream()).thenAnswer(call -> Stream.of(inventory));
        }
    }
    private InventoryReservationPort.Snapshot snapshot(InventoryReservationPort.State state,boolean paid,int quantity) {
        return new InventoryReservationPort.Snapshot(scope,"RES",state,3,paid,
                List.of(new InventoryReservationPort.ReservedLine("SKU",quantity,"SUB-1")));
    }
    @Test void handoffFirstVerifiesOriginalPaymentThenAllocatesAndIssues() {
        setup(2,true); consumer.consume(event);
        var ordered=inOrder(stock); ordered.verify(stock).allocatePaid("PAY-1");
        ordered.verify(stock).issue("SUB-1","PAY-1"); verifyNoMoreInteractions(stock);
    }
    @Test void legacyPaidWithoutTimestampPreservesAllocationAndIssueContract() {
        setup(2,true); parent.setPaidAt(null);
        consumer.consume(event);
        verify(stock).allocatePaid("PAY-1"); verify(stock).issue("SUB-1","PAY-1");
    }
    @Test void legacyRefundedChildReplaysAgainstOriginalInventoryOwnerWithoutAnotherDeduction() {
        setup(2,true); child.setStatus(OrdSubOrder.REFUNDED); parent.setPaidAt(null);
        var sku=mock(ai.neargo.shop.product.mapper.ProductMappers.SkuMapper.class);
        var locks=mock(ai.neargo.shop.product.mapper.ProductMappers.StockLockMapper.class);
        var stores=mock(ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper.class);
        // The original owner reads only ALLOCATED locks: an already issued/released original has none.
        when(locks.selectList(any())).thenReturn(List.of());
        var original=new ai.neargo.shop.product.port.StockPortImpl(sku,locks,stores,
                mock(ai.neargo.shop.product.service.StoreStockReader.class));
        var bridge=new FulfillmentStockIssueConsumer(original,new ObjectMapper(),children,parents,sources,items,providers);
        bridge.consume(event); bridge.consume(event);
        verify(locks,org.mockito.Mockito.times(2)).selectList(any());
        verifyNoInteractions(sku,stores,inventory); verify(locks,never()).updateById(any(ai.neargo.shop.product.entity.PrdStockLock.class));
    }
    @Test void lifecycleThreeRefundedChildStillRequiresReconciliation() {
        setup(3,false); child.setStatus(OrdSubOrder.REFUNDED);
        assertThatThrownBy(() -> consumer.consume(event)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(stock,inventory);
    }
    @Test void unpaidLifecycleThreeNeverAcknowledgesPaymentAndIssuesFromExactOriginalFacts() {
        setup(3,false);
        when(inventory.query(scope)).thenReturn(snapshot(InventoryReservationPort.State.ACCEPTED_UNPAID,false,2));
        when(inventory.issue(scope,"SUB-1")).thenReturn(snapshot(InventoryReservationPort.State.ISSUED_UNPAID,false,2));
        consumer.consume(event);
        verify(inventory).issue(scope,"SUB-1"); verify(inventory,never()).allocatePaid(any()); verifyNoInteractions(stock);
    }
    @Test void replayAfterDirectOwnerIssueDoesNotIssueOrAllocateAgain() {
        setup(3,false);
        when(inventory.query(scope)).thenReturn(snapshot(InventoryReservationPort.State.ISSUED_UNPAID,false,2));
        consumer.consume(event); consumer.consume(event);
        verify(inventory,never()).issue(any(),any()); verify(inventory,never()).allocatePaid(any()); verifyNoInteractions(stock);
    }
    @Test void forgedCompletionOrPaidFlagCannotReplaceOriginalMoneyAndQuantityProof() {
        setup(3,false);
        when(inventory.query(scope)).thenReturn(snapshot(InventoryReservationPort.State.ACCEPTED_UNPAID,false,1));
        assertThatThrownBy(() -> consumer.consume(event)).isInstanceOf(IllegalStateException.class);
        when(inventory.query(scope)).thenReturn(snapshot(InventoryReservationPort.State.ALLOCATED,true,2));
        assertThatThrownBy(() -> consumer.consume(event)).isInstanceOf(IllegalStateException.class);
        verify(inventory,never()).issue(any(),any()); verify(inventory,never()).allocatePaid(any()); verifyNoInteractions(stock);
    }
    @Test void unpaidLegacyCompletionCannotCreatePaidAllocation() {
        setup(2,false);
        assertThatThrownBy(() -> consumer.consume(event)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(stock,inventory);
    }
    private static SysOutbox event() {
        var event=new SysOutbox(); event.setEventType("SUB_ORDER_COMPLETED");
        event.setPayload("{\"subOrderNo\":\"SUB-1\",\"orderNo\":\"PAY-1\"}"); return event;
    }
}
