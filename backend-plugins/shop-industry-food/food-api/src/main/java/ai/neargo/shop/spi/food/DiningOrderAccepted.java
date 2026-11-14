package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;

/** Published atomically after original cash/allocation proof or lifecycle-3 unpaid acceptance proof; never a cash receipt. */
public record DiningOrderAccepted(String batchNo, String entityNo, String storeNo, String checkNo,
        String orderNo, String subOrderNo, LocalDate businessDate, String fulfillment, Instant acceptedAt)
        implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_BATCH"; }
    @Override public String aggregateId() { return batchNo; }
    @Override public String eventType() { return "DINING_ORDER_ACCEPTED"; }
}
