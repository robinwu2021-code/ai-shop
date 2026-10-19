package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.product.StockPort;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Replays physical inventory issue from durable shipment/completion facts; waybill corrections remain idempotent. */
@Component
public class FulfillmentStockIssueConsumer implements OutboxConsumer {
    private final StockPort stock;
    private final ObjectMapper json;

    public FulfillmentStockIssueConsumer(StockPort stock, ObjectMapper json) {
        this.stock = stock;
        this.json = json;
    }

    @Override public String handlerId() { return "fulfillment-stock-issue"; }

    @Override public boolean supports(String eventType) {
        return "SUB_ORDER_SHIPPED".equals(eventType) || "SUB_ORDER_COMPLETED".equals(eventType);
    }

    @Override public void consume(SysOutbox event) {
        var payload = json.readTree(event.getPayload());
        String subOrderNo = payload.path("subOrderNo").asString("");
        if (subOrderNo.isBlank()) throw new IllegalStateException("STOCK_FULFILLMENT_REFERENCE_MISSING");
        String orderNo = payload.path("orderNo").asString("");
        if (orderNo.isBlank()) throw new IllegalStateException("STOCK_ORDER_REFERENCE_MISSING");
        stock.allocatePaid(orderNo);
        stock.issue(subOrderNo, orderNo);
    }
}
