package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;

/** Immutable kitchen service evidence; does not assert inventory consumption or monetary settlement. */
public record DiningServiceEvent(String eventNo,String entityNo,String storeNo,String batchNo,String ticketNo,
        String taskNo,String foodLineNo,String componentNo,String stationNo,String taskRole,long routeVersion,
        String action,BigDecimal qty,String reason,String actorNo,Instant occurredAt) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_KITCHEN_TASK"; }
    @Override public String aggregateId() { return taskNo; }
    @Override public String eventType() { return "DINING_SERVICE_EVENT"; }
}
