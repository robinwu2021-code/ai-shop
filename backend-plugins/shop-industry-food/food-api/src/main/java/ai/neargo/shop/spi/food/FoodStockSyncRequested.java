package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.Snapshot;

/** Immutable transport intent for the optional projection, never a native stock posting. */
public record FoodStockSyncRequested(String syncNo,String entityNo,String storeNo,long generation,
        String action,String payloadHash,Snapshot snapshot) implements DomainEvent {
    @Override public String aggregateType(){return "FOOD_STOCK_SYNC";}
    @Override public String aggregateId(){return syncNo;}
    @Override public String eventType(){return "FOOD_STOCK_SYNC_REQUESTED";}
}
