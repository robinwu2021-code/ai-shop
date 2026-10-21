package ai.neargo.shop.food.config;

import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import ai.neargo.shop.auth.ScopeDim;
import java.util.Map;

/** 只注册已落地 Mapper 的真实主体字段。 */
public final class FoodDataScopeRegistration implements DataScopeRegistrar {
    @Override
    public void register(DataScopeTableRegistry registry) {
        registry.register("fnb_station", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_sku_station", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_sales_restriction", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_menu_context", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_menu_channel_binding", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_menu_category", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_menu_item", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_menu_item_sku", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_menu_item_option_group", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_menu", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_menu_store", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_menu_schedule", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_menu_exception", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_bundle", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_bundle_group", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_bundle_choice", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_dish", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_dish_option_group", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_config_revision", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_modifier", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_modifier_component", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_option_group", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_option_group_item", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_service_calendar", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_action_log", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_store_policy", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_table_token", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_guest_session", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_check", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_table_occupancy", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_seat", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_table_operation", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_reservation", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_table", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("fnb_table_area", Map.of(ScopeDim.MERCHANT, "entity_no"));
    }
}
