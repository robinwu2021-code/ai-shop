package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import java.time.LocalDate;

/** Actual durable queue transition; does not assert that SMS or another external notification was sent. */
public record DiningQueueChanged(String entityNo,String storeNo,String ticketNo,LocalDate businessDate,
        String status,int partySize,String checkNo,String actorNo,long version) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_QUEUE"; }
    @Override public String aggregateId() { return ticketNo; }
    @Override public String eventType() { return "DINING_QUEUE_CHANGED"; }
}
