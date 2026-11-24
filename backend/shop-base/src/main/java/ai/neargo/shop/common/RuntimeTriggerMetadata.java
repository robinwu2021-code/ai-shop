package ai.neargo.shop.common;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the exact trigger definitions without giving the application TRIGGER privileges. */
public final class RuntimeTriggerMetadata {
    public enum Scope {
        SERVICE_EXECUTION("EVENT_OBJECT_TABLE IN ('ord_service_execution','ord_service_execution_unit','ord_service_execution_action')"),
        STOCK_RETENTION("EVENT_OBJECT_TABLE='prd_execution_retention'"),
        BEAUTY_BASE("LEFT(EVENT_OBJECT_TABLE,4)='bty_' AND EVENT_OBJECT_TABLE NOT IN "
                + "('bty_service_execution_fence','bty_service_execution_stock_intent')"),
        BEAUTY_EXECUTION("EVENT_OBJECT_TABLE='bty_service_execution_fence'"),
        BEAUTY_STOCK("EVENT_OBJECT_TABLE='bty_service_execution_stock_intent'"),
        RESOURCE_OCCUPATION("EVENT_OBJECT_TABLE IN ('mch_resource_occupation','mch_resource_occupation_unit',"
                + "'mch_resource_occupation_segment','mch_resource_occupation_bucket','mch_booking_owner',"
                + "'mch_resource_command','mch_booking_hold_unit','mch_resource_bucket')",
                "shop_resource_trigger_metadata");

        private final String predicate;
        private final String procedure;

        Scope(String predicate) {
            this(predicate, "shop_runtime_trigger_metadata");
        }

        Scope(String predicate, String procedure) {
            this.predicate = predicate;
            this.procedure = procedure;
        }
    }

    private RuntimeTriggerMetadata() {}

    public static List<Map<String, Object>> read(Connection connection, String schema, Scope scope)
            throws SQLException {
        if (schema == null || !schema.matches("[A-Za-z0-9_]+")) {
            throw new SQLException("Invalid runtime trigger schema");
        }
        try (var direct = connection.prepareStatement(
                "SELECT TRIGGER_NAME,EVENT_MANIPULATION,EVENT_OBJECT_TABLE,ACTION_TIMING,ACTION_STATEMENT"
                        + " FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? AND " + scope.predicate)) {
            direct.setString(1, schema);
            try (var result = direct.executeQuery()) {
                var rows = rows(result);
                if (!rows.isEmpty()) {
                    return rows;
                }
            }
        }
        // The routine returns live metadata for the same fixed scope. Missing or denied remains a failure.
        try (var proxy = connection.prepareStatement(
                "CALL `" + schema + "`.`" + scope.procedure + "`(?)")) {
            proxy.setString(1, scope.name());
            if (!proxy.execute()) {
                throw new SQLException("Runtime trigger metadata procedure returned no result");
            }
            try (var result = proxy.getResultSet()) {
                return rows(result);
            }
        }
    }

    private static List<Map<String, Object>> rows(ResultSet result) throws SQLException {
        var rows = new ArrayList<Map<String, Object>>();
        while (result.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("trigger_name", result.getString("TRIGGER_NAME"));
            row.put("event_manipulation", result.getString("EVENT_MANIPULATION"));
            row.put("event_object_table", result.getString("EVENT_OBJECT_TABLE"));
            row.put("action_timing", result.getString("ACTION_TIMING"));
            row.put("action_statement", result.getString("ACTION_STATEMENT"));
            rows.add(row);
        }
        return rows;
    }
}
