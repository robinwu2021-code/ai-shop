package ai.neargo.shop.food.config;

import java.util.Set;

/**
 * 行业句柄负责的餐饮表（ADR-068 及修订一、二；TDD-餐饮域迁移新库 §2.4–§2.7）。启动校验与句柄守卫都以它为准。
 *
 * <p>P1 期间，合并（TDD §2.5）尚未完成的旧表也在这里；每完成一组合并，就把旧表换成合并后的新表。
 */
public final class FoodIndustryTables {
    private FoodIndustryTables() {}

    public static final Set<String> NAMES =
            Set.of(
                    // 设计 DDL 里的同名表
                    "fnb_table_area",
                    "fnb_table",
                    "fnb_station",
                    "fnb_kitchen_ticket",
                    "fnb_kds_subscription",
                    "fnb_kds_event",
                    "fnb_service_entitlement",
                    "fnb_service_entitlement_event",
                    "fnb_course",
                    "fnb_banquet_plan",
                    "fnb_banquet_attendance_confirmation",
                    "fnb_queue_ticket",
                    // 厨显：.61 已按独立表建（01-industry-kds.sql）
                    "fnb_kds_cursor",
                    "fnb_kds_voice_command",
                    "fnb_station_runtime",
                    // 修订一：出品整组
                    "fnb_kitchen_task",
                    // 修订二：占台、换台记录、订位、时段与容量
                    "fnb_table_occupancy",
                    "fnb_table_operation",
                    "fnb_reservation",
                    "fnb_reservation_table",
                    "fnb_service_slot",
                    "fnb_capacity_claim",
                    "fnb_service_calendar",
                    // 过渡：合并与改名完成前的旧表（§2.5）
                    "fnb_config_revision",
                    "fnb_banquet_plan_revision",
                    "fnb_store_policy",
                    "fnb_dining_entitlement",
                    "fnb_scheduled_dispatch",
                    "fnb_service_event",
                    "fnb_service_handover",
                    "fnb_service_handover_item",
                    "fnb_service_responsibility",
                    // P1-2 改名：寄存（原 fnb_bottle_custody、fnb_bottle_movement）
                    "fnb_custody",
                    "fnb_custody_movement");

    /** 已改名的旧表 → 行业库新表。主库里可能还留着旧表（私有迁移建的），校验器不再要求、也不当作未知表。 */
    public static final java.util.Map<String, String> RENAMED =
            java.util.Map.of(
                    "fnb_bottle_custody", "fnb_custody",
                    "fnb_bottle_movement", "fnb_custody_movement");
}
