package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.product.StockPort;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class FulfillmentStockIssueConsumerTest {
    @Test
    void handoffFirstEnsuresPaidAllocationThenIssuesSubOrderIdempotently() {
        StockPort stock = mock(StockPort.class);
        FulfillmentStockIssueConsumer consumer = new FulfillmentStockIssueConsumer(stock, new tools.jackson.databind.ObjectMapper());
        SysOutbox event = new SysOutbox();
        event.setEventType("SUB_ORDER_SHIPPED");
        event.setPayload("{\"subOrderNo\":\"SUB-1\",\"orderNo\":\"PAY-1\"}");

        consumer.consume(event);

        var order = inOrder(stock);
        order.verify(stock).allocatePaid("PAY-1");
        order.verify(stock).issue("SUB-1", "PAY-1");
        verifyNoMoreInteractions(stock);
    }
}
