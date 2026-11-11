package ai.neargo.shop.food.config;

import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Strict invariants for the new local-stock tables; never alters existing database objects. */
final class FoodLocalStockSchemaContract {
    private FoodLocalStockSchemaContract() {}

    static final Map<String, List<String>> REQUIRED = Map.ofEntries(
            Map.entry("fnb_stock_replenishment", List.of("id", "tenant_no", "entity_no", "store_no", "created_at", "created_by", "updated_at", "updated_by", "version", "deleted", "replenishment_no", "source_system", "source_owner", "source_document", "source_line", "source_item", "source_unit", "source_quantity", "item_no", "base_unit", "factor", "expected_quantity", "received_quantity", "difference_quantity", "returned_quantity", "return_accepted_quantity", "status", "admission_generation", "admission_mode")),
            Map.entry("fnb_stock_replenishment_return", List.of("id", "tenant_no", "entity_no", "store_no", "created_at", "created_by", "updated_at", "updated_by", "version", "deleted", "replenishment_no", "source_system", "source_owner", "source_document", "source_line", "accepted_quantity")),
            Map.entry("fnb_return_stock_disposition", List.of("tenant_no", "entity_no", "store_no", "operation_no", "return_no", "return_item_no", "production_event_no", "dish_qty", "disposition", "operation_key", "request_hash", "request_snapshot", "native_snapshot", "status")),
            Map.entry("fnb_stock_item", List.of("tenant_no", "entity_no", "item_no", "item_kind", "name", "base_unit", "tracking_mode", "status")),
            Map.entry("fnb_stock_balance", List.of("tenant_no", "entity_no", "store_no", "balance_no", "item_no", "on_hand", "reserved_qty")),
            Map.entry("fnb_stock_reservation", List.of("tenant_no", "entity_no", "store_no", "reservation_no", "source_system", "source_no", "source_version", "operation_key", "request_hash", "plan_hash", "lifecycle_version", "status")),
            Map.entry("fnb_stock_reservation_item", List.of("tenant_no", "entity_no", "store_no", "reservation_no", "leaf_ref", "source_component_ref", "item_no", "base_unit", "original_qty", "issued_qty", "released_qty", "returned_qty", "phase", "definition_hash")),
            Map.entry("fnb_stock_command", List.of("tenant_no", "entity_no", "store_no", "command_no", "action", "operation_key", "request_hash", "status")),
            Map.entry("fnb_stock_movement", List.of("tenant_no", "entity_no", "store_no", "movement_no", "command_no", "line_ref", "item_no", "action", "quantity", "base_unit", "on_hand_before", "on_hand_after", "reserved_before", "reserved_after")),
            Map.entry("fnb_stock_replenishment_connection", List.of("id", "tenant_no", "entity_no", "store_no", "created_at", "created_by", "updated_at", "updated_by", "version", "deleted", "status", "generation", "posting_watermark")),
            Map.entry("fnb_stock_replenishment_manual", List.of("id", "tenant_no", "entity_no", "store_no", "created_at", "created_by", "updated_at", "updated_by", "version", "deleted", "replenishment_no", "movement_no", "quantity")));

    static final Map<String, List<String>> DECIMALS = Map.of(
            "fnb_stock_replenishment_manual", List.of("quantity"),
            "fnb_stock_replenishment", List.of("source_quantity", "factor", "expected_quantity", "received_quantity", "difference_quantity", "returned_quantity", "return_accepted_quantity"),
            "fnb_stock_replenishment_return", List.of("accepted_quantity"),
            "fnb_return_stock_disposition", List.of("dish_qty"),
            "fnb_stock_balance", List.of("on_hand", "reserved_qty"),
            "fnb_stock_reservation_item", List.of("original_qty", "issued_qty", "released_qty", "returned_qty"),
            "fnb_stock_movement", List.of("quantity", "on_hand_before", "on_hand_after", "reserved_before", "reserved_after"));

    static final Map<String, String> CHECKS = Map.of(
            "fnb_stock_replenishment_connection", "generation>=0 and posting_watermark>=0",
            "fnb_stock_replenishment_manual", "quantity>0",
            "fnb_stock_replenishment", "received_quantity>=0 and difference_quantity>=0 and received_quantity+difference_quantity<=expected_quantity and returned_quantity>=0 and returned_quantity<=received_quantity and return_accepted_quantity>=0 and return_accepted_quantity<=returned_quantity",
            "fnb_stock_replenishment_return", "source_line>0 and accepted_quantity>0",
            "fnb_return_stock_disposition", "dish_qty>0",
            "fnb_stock_balance", "on_hand>=0 and reserved_qty>=0 and reserved_qty<=on_hand",
            "fnb_stock_reservation", "source_version>=0 and lifecycle_version>=0",
            "fnb_stock_reservation_item", "original_qty>0 and issued_qty>=0 and released_qty>=0 and issued_qty+released_qty<=original_qty",
            "fnb_stock_movement", "quantity>0 and on_hand_before>=0 and on_hand_after>=0 and reserved_before>=0 and reserved_after>=0 and reserved_before<=on_hand_before and reserved_after<=on_hand_after");

    static boolean nonNull(int nullable) {
        return nullable == DatabaseMetaData.columnNoNulls;
    }

    static boolean exactDecimal(int type, int precision, int scale, int nullable) {
        return (type == Types.DECIMAL || type == Types.NUMERIC) && precision == 24 && scale == 6 && nonNull(nullable);
    }

    static boolean hasExpectedCheck(String table, List<String> clauses) {
        String expected = CHECKS.get(table);
        return expected != null && clauses != null && clauses.stream()
                .anyMatch(clause -> clause != null && normalized(expected).equals(normalized(clause)));
    }

    static boolean hasReturnedCheck(List<String> clauses) {
        return clauses != null && clauses.stream().anyMatch(clause -> clause != null
                && normalized("returned_qty>=0 and returned_qty<=issued_qty").equals(normalized(clause)));
    }

    private static String normalized(String value) {
        return value.toLowerCase(Locale.ROOT).replace("`", "").replace("\"", "")
                .replace("(", "").replace(")", "").replaceAll("\\s+", "");
    }
}
