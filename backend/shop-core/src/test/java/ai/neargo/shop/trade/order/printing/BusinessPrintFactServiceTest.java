package ai.neargo.shop.trade.order.printing;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueReceiptConfirmed;
import ai.neargo.shop.trade.order.mapper.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessPrintFactServiceTest {
    final BusinessPrintFactMapper rows = mock(BusinessPrintFactMapper.class);
    final OutboxEventBus events = mock(OutboxEventBus.class);
    final ObjectMapper json = new ObjectMapper();
    final PaymentMapper orders=mock(PaymentMapper.class);
    final OrderItemMapper items=mock(OrderItemMapper.class);
    final BusinessPrintFactService service = new ai.neargo.shop.trade.order.printing.service.impl.BusinessPrintFactServiceImpl(rows, orders, items, json, events);
    StoredValueReceiptConfirmed credited(String store) {
        return new StoredValueReceiptConfirmed("EV1", "MAIN", "R".repeat(64), "A1", "M".repeat(64), store, 100, 20, 5120, "CNY", 1700000000000L);
    }
    @Test void freezesPostingBalanceAndBonusWithoutReadingAccount() {
        when(rows.selectOne(any())).thenReturn(null);
        service.recordRecharge(credited("S".repeat(64)));
        var captured = org.mockito.ArgumentCaptor.forClass(BusinessPrintFact.class);
        verify(rows).insert(captured.capture());
        var fact = captured.getValue();
        var payload = json.readTree(fact.getSnapshot());
        assertEquals("5120", payload.at("/fields/balanceAfter/minor").asText());
        assertEquals("20", payload.at("/fields/giftAmount/minor").asText());
        assertEquals("READY", fact.getPrintability());
        assertEquals("S".repeat(64), fact.getStoreNo());
        verify(events).publish(any(BusinessPrintFactReady.class));
    }
    @Test void rejectsMissingStoreAndUnboundTenant() {
        assertThrows(IllegalArgumentException.class, () -> service.recordRecharge(credited(null)));
        var e = credited("S1");
        assertThrows(IllegalArgumentException.class, () -> service.recordRecharge(new StoredValueReceiptConfirmed(e.eventNo(), "OTHER", e.rechargeNo(), e.accountNo(), e.entityNo(), e.storeNo(),100,20,5120,"CNY",e.occurredAt())));
        verifyNoInteractions(rows, events);
    }
    @Test void replayDoesNotReplaceFrozenSnapshotOrRepublish() {
        when(rows.selectOne(any())).thenReturn(null);
        var ref=service.recordRecharge(credited("S1"));
        var captured=org.mockito.ArgumentCaptor.forClass(BusinessPrintFact.class); verify(rows).insert(captured.capture());
        var old=captured.getValue(); var frozen=old.getSnapshot();
        when(rows.selectOne(any())).thenReturn(old);
        assertEquals(ref, service.recordRecharge(credited("S1")));
        assertEquals(frozen,old.getSnapshot());
        verify(rows,times(1)).insert(any(BusinessPrintFact.class));
        verify(events,times(1)).publish(any(BusinessPrintFactReady.class));
    }
    @Test void rejectsChangedReplayRatherThanRewritingHistory() {
        var old = new BusinessPrintFact(); old.setOwnerEventHash("other");
        when(rows.selectOne(any())).thenReturn(old);
        assertThrows(IllegalStateException.class, () -> service.recordRecharge(credited("S1")));
        verify(rows, never()).insert(any(BusinessPrintFact.class));
    }
    @Test void fullReferenceAndScopeAreRequiredToReadFrozenFacts() {
        when(rows.selectOne(any())).thenReturn(null);
        assertThrows(PrintFailure.class, () -> service.snapshot(new PrintFactRef("OTHER","M1","S1","RECHARGE","F1","1","RECHARGE_RECEIPT","hash")));
        assertThrows(PrintFailure.class, () -> service.resolveReference(new PrintSourceReference("REFUND","R1","F1","1","RECHARGE_RECEIPT"),new PrintScope("MAIN","M1","S1")));
    }
    @Test void refundAliasLookupKeepsScopeTypeFactSceneAndRevisionPredicates() {
        var f=new BusinessPrintFact(); f.setTenantNo("MAIN"); f.setEntityNo("M1"); f.setStoreNo("S1");
        f.setSourceType("REFUND"); f.setSourceNo("AS1"); f.setOrderNo("O1"); f.setSubOrderNo("SUB1");
        f.setPrintFactNo("F1"); f.setBizRevision("1"); f.setScene("REFUND_RECEIPT"); f.setSnapshotHash("frozenHash");
        when(rows.selectOne(any())).thenReturn(f);
        var scope=new PrintScope("MAIN","M1","S1");
        assertEquals("F1",service.resolveReference(new PrintSourceReference("REFUND","O1","F1","1","REFUND_RECEIPT"),scope).factNo());
        var captured=org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(rows).selectOne(captured.capture());
        String sql=captured.getValue().getSqlSegment();
        for(var column:List.of("tenant_no","entity_no","store_no","source_type","print_fact_no","biz_revision","scene","source_no","order_no","sub_order_no")) assertTrue(sql.contains(column),sql);
        assertTrue(sql.contains(" OR "),sql);
        assertEquals("F1",service.resolveReference(new PrintSourceReference("REFUND","SUB1","F1","1","REFUND_RECEIPT"),scope).factNo());
    }
    @Test void rechargeDoesNotAcceptOrderAliases() {
        when(rows.selectOne(any())).thenReturn(null);
        assertThrows(PrintFailure.class,()->service.resolveReference(new PrintSourceReference("RECHARGE","O1","F1","1","RECHARGE_RECEIPT"),new PrintScope("MAIN","M1","S1")));
        var captured=org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(rows).selectOne(captured.capture());
        String sql=captured.getValue().getSqlSegment();
        assertTrue(sql.contains("source_no")); assertFalse(sql.contains("order_no"));
    }
    @Test void refundsRequireTerminalOwnerProofAndUsePartialPlanInsteadOfWholeOrder() {
        var sale=new ai.neargo.shop.trade.entity.OrdAfterSale();
        sale.setAfterSaleNo("AS1"); sale.setSubOrderNo("SUB1"); sale.setEntityNo("M1"); sale.setReason("DAMAGED");
        var sub=new ai.neargo.shop.trade.entity.OrdSubOrder();
        sub.setTenantNo("MAIN"); sub.setEntityNo("M1"); sub.setStoreNo("S1"); sub.setSubOrderNo("SUB1"); sub.setOrderNo("O1");
        var ex=new ai.neargo.shop.trade.order.entity.OrdRefundExecution();
        ex.setClosureVersion(2); ex.setAfterSaleNo("AS1"); ex.setSubOrderNo("SUB1"); ex.setExecutionNo("EX1"); ex.setCurrency("JPY");
        ex.setOriginPaymentNo("PAY1"); ex.setPaymentNo("REFPAY1"); ex.setPayChannel("WECHAT"); ex.setAmountMinor(300L); ex.setSucceededAt(1700000000000L);
        ex.setPlanJson(json.writeValueAsString(new ai.neargo.shop.trade.order.refund.model.RefundPlan("SUB1",
                List.of(new ai.neargo.shop.trade.order.refund.model.RefundLine(11L,1,300)),0)));
        ex.setStatus("UNKNOWN"); sale.setStatus("REFUNDING");
        assertThrows(IllegalArgumentException.class, ()->service.recordRefund(sale,sub,ex));
        verifyNoInteractions(rows,events);
        sale.setStatus("REFUNDED"); ex.setStatus("SUCCEEDED");
        ex.setClosureVersion(1);
        assertThrows(IllegalArgumentException.class,()->service.recordRefund(sale,sub,ex));
        verifyNoInteractions(rows,events);
        ex.setClosureVersion(2);
        var order=new ai.neargo.shop.trade.entity.OrdOrder(); order.setCurrency("JPY");
        when(orders.selectOne(any())).thenReturn(order);
        var item=new ai.neargo.shop.trade.entity.OrdItem(); item.setTitle("被退的一件商品");
        when(items.selectOne(any())).thenReturn(item);
        service.recordRefund(sale,sub,ex);
        var capture=org.mockito.ArgumentCaptor.forClass(BusinessPrintFact.class); verify(rows).insert(capture.capture());
        var f=capture.getValue(); var payload=json.readTree(f.getSnapshot());
        assertEquals("READY",f.getPrintability());
        assertEquals(1,payload.path("lines").size());
        assertEquals("300",payload.at("/fields/refundedAmount/minor").asText());
        assertEquals(0,payload.at("/fields/refundedAmount/scale").asInt());
        // A different cash amount must be blocked, never silently distributed over goods lines.
        ex.setAmountMinor(250L);
        service.recordRefund(sale,sub,ex);
        verify(rows,times(2)).insert(capture.capture());
        assertEquals("BLOCKED",capture.getValue().getPrintability());
    }
    @Test void scanUsesRequestedSourceAndAllThreeScopeKeys() {
        when(rows.selectList(any())).thenReturn(List.of());
        service.scan(new PrintFactCursor(new PrintScope("TENANT","ENTITY","STORE"),"REFUND",
                java.time.Instant.EPOCH,java.time.Instant.now(),null,null),20);
        var captured=org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(rows).selectList(captured.capture());
        String sql=captured.getValue().getSqlSegment();
        assertTrue(sql.contains("tenant_no")); assertTrue(sql.contains("entity_no")); assertTrue(sql.contains("store_no")); assertTrue(sql.contains("source_type"));
        assertTrue(captured.getValue().getParamNameValuePairs().values().containsAll(List.of("TENANT","ENTITY","STORE","REFUND")));
    }
}
