package ai.neargo.shop.printbridge;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueReceiptConfirmed;
import ai.neargo.shop.trade.order.printing.BusinessPrintFactService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BusinessPrintReceiptConsumerTest {
    final ObjectMapper json=new ObjectMapper();
    final BusinessPrintFactService facts=mock(BusinessPrintFactService.class);
    final BusinessPrintReceiptConsumer consumer=new BusinessPrintReceiptConsumer(facts,json);
    SysOutbox row() {
        var e=new StoredValueReceiptConfirmed("E1","MAIN","R1","A1","M1","S1",100,10,1110,"CNY",1700000000000L);
        var row=new SysOutbox(); row.setEventNo(e.eventNo()); row.setEventType(e.eventType());
        row.setAggregateType(e.aggregateType()); row.setAggregateId(e.aggregateId()); row.setPayload(json.writeValueAsString(e));
        return row;
    }
    @Test void acceptsOnlyPostingReceiptEventAndValidatesItsImmutableIdentity() {
        assertTrue(consumer.supports("STORED_VALUE_RECEIPT_CONFIRMED"));
        assertFalse(consumer.supports("STORED_VALUE_CREDITED"));
        assertFalse(consumer.supports("STORED_VALUE_RECHARGE_RECEIVED"));
        var row=row(); consumer.consume(row); verify(facts).recordRecharge(any());
        row.setAggregateId("OTHER"); assertThrows(IllegalArgumentException.class,()->consumer.consume(row));
        var bad=row(); bad.setEventNo("OTHER");
        assertThrows(IllegalArgumentException.class,()->consumer.consume(bad));
        verifyNoMoreInteractions(facts);
    }
    @Test void genericFactReadyUsesTheExistingFacade() {
        var facade=mock(ai.neargo.shop.print.api.PrintFacade.class);
        var ready=new PrintFactReadyConsumer(facade,json);
        var ref=new ai.neargo.shop.print.api.PrintFactRef("MAIN","M1","S1","RECHARGE","F1","1","RECHARGE_RECEIPT","HASH");
        var event=new ai.neargo.shop.print.api.BusinessPrintFactReady("R1",ref);
        var row=new SysOutbox(); row.setEventType(event.eventType()); row.setAggregateId(event.aggregateId());
        row.setAggregateType(event.aggregateType()); row.setPayload(json.writeValueAsString(event));
        ready.consume(row); verify(facade).onFact(ref);
        row.setAggregateType("ORDER"); assertThrows(IllegalArgumentException.class,()->ready.consume(row));
        verifyNoMoreInteractions(facade);
    }
}
