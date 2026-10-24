package ai.neargo.shop.spi.food;

import java.util.Map;
import java.util.Set;

/** Explicit source routing; fact-only archival never stands in for a downstream notification receipt. */
public final class FoodOutboxRouting {
    private FoodOutboxRouting() { }
    public static final String KITCHEN_ACCEPTED_HANDLER = "food-kitchen-accepted-v1";
    public static final String PRODUCTION_HANDLER = "food-production-consumption-v1";
    public static final Map<String, Map<String, Boolean>> DELIVERY_TARGETS = Map.of(
            "DINING_ORDER_ACCEPTED", Map.of(KITCHEN_ACCEPTED_HANDLER, true),
            "DINING_SERVICE_EVENT", Map.of(PRODUCTION_HANDLER, true));
    public static final Set<String> ARCHIVE_ONLY = Set.of(
            "PLATFORM:FOOD_DISH_CHANGED", "PLATFORM:FOOD_TABLE_AREA_CHANGED",
            "PLATFORM:FOOD_STORE_POLICY_PUBLISHED", "PLATFORM:FOOD_OPTION_GROUP_CHANGED",
            "PLATFORM:FOOD_TABLE_CHANGED", "PLATFORM:FOOD_CONFIG_PUBLISHED",
            "PLATFORM:FOOD_SERVICE_CALENDAR_SAVED", "PLATFORM:FOOD_RECIPE_CHANGED",
            "PLATFORM:FOOD_CHECK_CHANGED", "PLATFORM:FOOD_MENU_CHANGED",
            "PLATFORM:FOOD_DINING_SESSION_CHANGED", "PLATFORM:FOOD_STATION_CHANGED",
            "PLATFORM:FOOD_BUNDLE_CHANGED", "PLATFORM:FOOD_CART_CHANGED",
            "PLATFORM:FOOD_MODIFIER_CHANGED", "PLATFORM:FOOD_SALES_RESTRICTION_CHANGED",
            "PLATFORM:FOOD_MENU_CHANNEL_BINDING_CHANGED", "PLATFORM:DINING_QUEUE_CHANGED");
}
