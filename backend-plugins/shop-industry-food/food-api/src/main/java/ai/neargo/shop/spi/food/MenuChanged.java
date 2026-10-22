package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.food.dto.MenuContextInput;
import java.util.List;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.MenuCompositionVO;

/** Atomic draft invalidation; never a publication or channel synchronization acknowledgment. */
public record MenuChanged(String entityNo, String storeNo, String menuNo, String action,
        long version, String operationNo, MenuVO after, MenuScheduleVO schedule, MenuCompositionVO composition,
        List<MenuContextInput> contexts) implements DomainEvent {
    public MenuChanged(String entity, String store, String menu, String action, long version,
            String operation, MenuVO after, MenuScheduleVO schedule, MenuCompositionVO composition) {
        this(entity, store, menu, action, version, operation, after, schedule, composition, null);
    }
    public MenuChanged(String entityNo, String storeNo, String menuNo, String action, long version,
            String operationNo, MenuVO after, MenuScheduleVO schedule) {
        this(entityNo, storeNo, menuNo, action, version, operationNo, after, schedule, null, null);
    }
    public MenuChanged(String entityNo, String storeNo, String menuNo, String action, long version,
            String operationNo, MenuVO after) {
        this(entityNo, storeNo, menuNo, action, version, operationNo, after, null, null, null);
    }
    @Override public String aggregateType() { return "FOOD_MENU"; }
    @Override public String aggregateId() { return menuNo; }
    @Override public String eventType() { return "FOOD_MENU_CHANGED"; }
}
