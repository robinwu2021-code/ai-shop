package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.mall.reporting.*;
import ai.neargo.commerce.api.mall.reporting.PurchaseReporting.*;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService;
import ai.neargo.shop.pay.service.PlatformCashReporting;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformMallReportingTest {
  final PurchaseOrderQueryPort stores=mock(PurchaseOrderQueryPort.class);
  final EntityPlanPurchaseService entities=mock(EntityPlanPurchaseService.class);
  final PlatformCashReporting cash=mock(PlatformCashReporting.class);
  final ObjectProvider<PlatformCashReporting> provider=mock(ObjectProvider.class);
  final PlatformMallReporting service=new PlatformMallReporting(stores,entities,provider);
  final PlatformMallAccess.Context all=new PlatformMallAccess.Context(new Access("MAIN",true,Set.of()),"OPS1",Set.of(Model.ENTITY,Model.STORE));
  final Filter filter=new Filter(null,null,null,null,null,null,null,false);
  Row row(Model model,String no,long time,String entity){return new Row(model,no,entity,"S1","TWD","100","CREATED",null,time);}
  @Test void mergesStableTiesWithoutPerEntityQueriesAndPassesScopeBeforeLimit() {
    when(entities.searchOperatorOrders(any(),any())).thenReturn(List.of(row(Model.ENTITY,"E2",20,"M1"),row(Model.ENTITY,"E1",10,"M1")));
    when(stores.search(any())).thenReturn(List.of(row(Model.STORE,"S2",20,"M1"),row(Model.STORE,"S1",9,"M1")));
    var page=service.orders(all,filter,null,2);
    assertEquals(List.of("E2","S2"),page.items().stream().map(Row::orderNo).toList());
    assertNotNull(page.nextCursor());
    service.orders(all,filter,page.nextCursor(),2);
    verify(stores).search(argThat(q->q.after()!=null && q.after().model()==Model.STORE && q.after().createdAt()==20 && q.limit()==3 && q.access().equals(all.access())));
    verify(entities,times(2)).searchOperatorOrders(any(),any());
  }
  @Test void validatesFiltersAndDoesNotQueryUnpermittedModel() {
    var context=new PlatformMallAccess.Context(all.access(),"OPS1",Set.of(Model.STORE));
    when(stores.search(any())).thenReturn(List.of());
    assertTrue(service.orders(context,filter,null,20).items().isEmpty());
    verifyNoInteractions(entities);
    assertThrows(RuntimeException.class,()->service.orders(all,filter,"broken!",20));
    assertThrows(RuntimeException.class,()->service.orders(all,filter,null,101));
  }
  @Test void fullWindowTotalsKeepCurrenciesExactAndPagingDoesNotChangeTotals() {
    when(provider.getIfAvailable()).thenReturn(cash);
    when(entities.findOperatorOrderOwners(any(),eq("MAIN"),any())).thenReturn(List.of(row(Model.ENTITY,"E",0,"M1")));
    when(stores.findByOrderNos(eq("MAIN"),any())).thenReturn(List.of(row(Model.STORE,"S",0,"M1")));
    doAnswer(call->{ java.util.function.Consumer<List<PlatformCashReporting.Fact>> consumer=call.getArgument(1);
      consumer.accept(List.of(fact("P3","E","PAY","TWD",Long.MAX_VALUE,30,true),fact("P2","E","PAY","TWD",Long.MAX_VALUE,20,true)));
      consumer.accept(List.of(fact("R","E","REFUND","TWD",7,15,true),fact("P1","S","PAY","USD",10,10,true))); return null;
    }).when(cash).scan(any(),any());
    var first=service.cash(all,0,100,null,null,null,null,1);
    assertEquals("18446744073709551614",first.summary().getFirst().receivedMinor());
    assertEquals("18446744073709551607",first.summary().getFirst().netMinor());
    assertEquals(2,first.summary().size()); assertEquals("P3",first.items().getFirst().paymentNo());
    var second=service.cash(all,0,100,null,null,null,first.nextCursor(),1);
    assertEquals(first.summary(),second.summary());assertEquals("P2",second.items().getFirst().paymentNo());
  }
  @Test void hiddenMalformedFactsDoNotLeakButVisibleAndUnattributedFactsFailClosed() {
    when(provider.getIfAvailable()).thenReturn(cash);
    when(entities.findOperatorOrderOwners(any(),any(),any())).thenReturn(List.of(row(Model.ENTITY,"E",0,"HIDDEN")));
    when(stores.findByOrderNos(any(),any())).thenReturn(List.of());
    doAnswer(call->{java.util.function.Consumer<List<PlatformCashReporting.Fact>> c=call.getArgument(1);c.accept(List.of(fact("P","E","PAY","TWD",1,1,false)));return null;}).when(cash).scan(any(),any());
    var restricted=new PlatformMallAccess.Context(new Access("MAIN",false,Set.of("M1")),"OPS1",all.models());
    assertTrue(service.cash(restricted,0,100,null,null,null,null,20).summary().isEmpty());
    assertThrows(RuntimeException.class,()->service.cash(all,0,100,null,null,null,null,20));
    when(entities.findOperatorOrderOwners(any(),any(),any())).thenReturn(List.of());
    assertThrows(RuntimeException.class,()->service.cash(all,0,100,null,null,null,null,20));
  }
  @Test void unavailableAndAmbiguousSourceNeverProduceZeroIncome() {
    assertThrows(RuntimeException.class,()->service.cash(all,0,100,null,null,null,null,20));
    when(provider.getIfAvailable()).thenReturn(cash);
    when(entities.findOperatorOrderOwners(any(),any(),any())).thenReturn(List.of(row(Model.ENTITY,"X",0,"M1")));
    when(stores.findByOrderNos(any(),any())).thenReturn(List.of(row(Model.STORE,"X",0,"M1")));
    doAnswer(call->{java.util.function.Consumer<List<PlatformCashReporting.Fact>> c=call.getArgument(1);c.accept(List.of(fact("P","X","PAY","TWD",1,1,true)));return null;}).when(cash).scan(any(),any());
    assertThrows(RuntimeException.class,()->service.cash(all,0,100,null,null,null,null,20));
  }
  static PlatformCashReporting.Fact fact(String p,String o,String d,String c,long a,long t,boolean ok){return new PlatformCashReporting.Fact(p,o,null,d,c,a,t,"NORMAL",ok,null);}
}
