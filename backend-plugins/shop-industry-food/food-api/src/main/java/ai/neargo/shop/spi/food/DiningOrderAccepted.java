package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;

/** Published atomically only after original cash success and inventory allocation are proved. */
public record DiningOrderAccepted(String batchNo, String entityNo, String storeNo, String checkNo,
        String orderNo, String subOrderNo, LocalDate businessDate, String fulfillment, Instant acceptedAt)
        implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_BATCH"; }
    @Override public String aggregateId() { return batchNo; }
    @Override public String eventType() { return "DINING_ORDER_ACCEPTED"; }
}
