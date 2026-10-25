package ai.neargo.shop.trade.order.printing;
import ai.neargo.shop.trade.entity.*;
import ai.neargo.shop.trade.order.mapper.*;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentLifecycleService.ConfirmedPayment;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
class OrderPrintFactRecorderTest {
 PaymentMapper orders=mock(PaymentMapper.class);StoreOrderMapper subs=mock(StoreOrderMapper.class);OrderItemMapper items=mock(OrderItemMapper.class);OrdOrder order;OrdSubOrder sub;OrdItem item;
 @BeforeEach void data(){order=new OrdOrder();order.setOrderNo("O1");order.setTenantNo("T1");order.setCurrency("CNY");order.setPayAmount(8800L);order.setCreatedAt(LocalDateTime.of(2026,10,23,1,0));order.setPaidAt(Instant.parse("2026-10-23T01:01:00Z").toEpochMilli());sub=new OrdSubOrder();sub.setSubOrderNo("SO1");sub.setOrderNo("O1");sub.setEntityNo("E1");sub.setStoreNo("S1");sub.setEntityName("Test store");sub.setGoodsAmount(10000L);sub.setDiscountAmount(1200L);sub.setFreightAmount(0L);sub.setPayAmount(8800L);sub.setFulfillment("PICKUP");sub.setVersion(3L);item=new OrdItem();item.setId(1L);item.setTitle("Frozen product");item.setQty(2);item.setAmount(10000L);when(orders.selectOne(any())).thenReturn(order);when(subs.selectOne(any())).thenReturn(sub);when(items.selectList(any())).thenReturn(List.of(item));}
 OrderPrintFact capture(ConfirmedPayment p){return new OrderPrintFactRecorder(orders,subs,items,JsonMapper.builder().build()).capturePaidSubOrder("O1","SO1",p);}
 @Test void capturesSubOrderAmountsAndImmutableLineData(){var f=capture(new ConfirmedPayment("WECHAT","PAY1"));assertEquals("READY",f.getPrintability());assertTrue(f.getSnapshot().contains("8800"));String frozen=f.getSnapshot();item.setTitle("Changed after payment");sub.setPayAmount(1L);assertEquals(frozen,f.getSnapshot());assertEquals(ai.neargo.shop.print.api.PrintHashes.sha256(frozen),f.getSnapshotHash());}
 @Test void unsupportedCompositionIsDurablyBlockedInsteadOfFabricated(){sub.setPayAmount(8799L);var f=capture(new ConfirmedPayment("WECHAT","PAY1"));assertEquals("BLOCKED",f.getPrintability());assertTrue(f.getDiagnostics().contains("sourceCompositionUnsupported"));}
 @Test void missingPaymentProofIsBlocked(){assertEquals("BLOCKED",capture(new ConfirmedPayment("WECHAT",null)).getPrintability());}
 @Test void noCashDoesNotInventPaymentTransaction(){order.setPayAmount(0L);sub.setDiscountAmount(10000L);sub.setPayAmount(0L);var f=capture(new ConfirmedPayment("NO_CASH",null));assertEquals("READY",f.getPrintability());assertTrue(f.getSnapshot().contains("免支付"));assertFalse(f.getSnapshot().contains("\"tradeNo\""));}
}
