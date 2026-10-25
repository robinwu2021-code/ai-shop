package ai.neargo.shop.food.spi;

import java.util.List;
import ai.neargo.shop.event.DomainEvent;

/** Original business change notification; never evidence of refund, inventory return or kitchen completion. */
public record DiningOrderChanged(String entityNo, String storeNo, String checkNo, String batchNo,
        String changeNo, String action, long sourceVersion, List<String> foodLineNos) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_BATCH"; }
    @Override public String aggregateId() { return batchNo; }
    @Override public String eventType() { return "DINING_ORDER_CHANGED"; }
    public DiningOrderChanged { foodLineNos = List.copyOf(foodLineNos); }
}
