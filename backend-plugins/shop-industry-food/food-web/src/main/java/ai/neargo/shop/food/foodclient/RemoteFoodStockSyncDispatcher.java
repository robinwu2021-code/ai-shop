package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.OutboxRouteContribution;
import ai.neargo.shop.food.spi.FoodStockSyncInternalApi;
import ai.neargo.shop.spi.food.FoodOutboxRouting;
import ai.neargo.shop.spi.food.FoodStockSyncRequested;
import ai.neargo.svc.client.ServiceCallException;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** Transport only: no business SQL and no second scheduler in the gateway. */
public final class RemoteFoodStockSyncDispatcher implements OutboxHandler, OutboxRouteContribution {
    private final FoodStockSyncInternalApi api;
    private final ObjectMapper json;
    public RemoteFoodStockSyncDispatcher(FoodStockSyncInternalApi api, ObjectMapper json) {
        this.api = api; this.json = json;
    }
    @Override public String handlerId() { return FoodOutboxRouting.STOCK_SYNC_HANDLER; }
    @Override public boolean externalWorkflow() { return true; }
    @Override public boolean supports(String type) { return "FOOD_STOCK_SYNC_REQUESTED".equals(type); }
    @Override public Set<String> consumedArchiveRoutes() { return Set.of("PLATFORM:FOOD_STOCK_SYNC_REQUESTED"); }
    @Override public DeliveryResult handle(OutboxEnvelope envelope) {
        var original = json.readValue(envelope.payload(), FoodStockSyncRequested.class);
        try {
            return api.dispatch(new FoodStockSyncInternalApi.Dispatch(original.entityNo(), original.storeNo(), envelope));
        } catch (ServiceCallException unavailable) {
            // Original intent is durable and receipt-queryable; transport uncertainty is safe to retry.
            return DeliveryResult.retry("FOOD_DISPATCH_UNAVAILABLE", "Retry the original FOOD stock intent");
        }
    }
}
