package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import java.time.LocalDate;

/** Calendar configuration was saved; this fact does not imply order acceptance or downstream delivery. */
public record ServiceCalendarSaved(String entityNo, String storeNo, String calendarNo, LocalDate localDate,
        String mode, long version, String operationNo) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_SERVICE_CALENDAR"; }
    @Override public String aggregateId() { return calendarNo; }
    @Override public String eventType() { return "FOOD_SERVICE_CALENDAR_SAVED"; }
}
