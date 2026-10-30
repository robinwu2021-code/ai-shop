package ai.neargo.shop.food.service;

import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;

public interface FoodStockSyncDispatchService {
    DeliveryResult dispatch(String entityNo, String storeNo, OutboxEnvelope envelope);
}
