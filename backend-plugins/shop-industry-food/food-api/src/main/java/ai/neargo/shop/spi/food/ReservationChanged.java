package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.food.dto.ReservationContracts.DetailVO;

/** Local appointment facts expose allocated tables without buyer/contact/deposit assertions. */
public record ReservationChanged(String entityNo,String storeNo,String reservationNo,String action,
        long version,String operationNo,DetailVO after,String reason) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_RESERVATION"; }
    @Override public String aggregateId() { return reservationNo; }
    @Override public String eventType() { return "FOOD_RESERVATION_CHANGED"; }
}
