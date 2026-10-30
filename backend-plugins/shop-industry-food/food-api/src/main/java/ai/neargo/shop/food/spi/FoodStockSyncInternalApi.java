package ai.neargo.shop.food.spi;

import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** PLATFORM remains the sole durable delivery runtime; FOOD owns the business dispatcher. */
public interface FoodStockSyncInternalApi {
    String PATH = "/internal/food/stock-sync/dispatch";
    record Dispatch(String entityNo, String storeNo, OutboxEnvelope envelope) {}
    @PostExchange(PATH)
    DeliveryResult dispatch(@RequestBody Dispatch request);
}
