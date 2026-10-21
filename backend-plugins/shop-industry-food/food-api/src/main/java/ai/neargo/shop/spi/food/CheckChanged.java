package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.food.dto.CheckVO;
import ai.neargo.shop.food.dto.SeatReq;
import java.util.List;

/** Reception/topology change only; no claim about order acceptance or payment settlement. */
public record CheckChanged(String entityNo,String storeNo,String checkNo,String action,long version,
        String operationNo,CheckVO before,CheckVO after,String reason,List<SeatReq> beforeSeats,List<SeatReq> afterSeats) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_CHECK"; }
    @Override public String aggregateId() { return checkNo; }
    @Override public String eventType() { return "FOOD_CHECK_CHANGED"; }
}
