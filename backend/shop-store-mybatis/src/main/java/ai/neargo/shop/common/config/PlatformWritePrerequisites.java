package ai.neargo.shop.common.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** Shared read-only platform write contract extracted from restaurant validation; never executes migrations. */
public final class PlatformWritePrerequisites {
    /** Returns actionable missing fields/unique identities without changing a shared database. */
    public List<String> missing(DataSource source) {
        Connection connection = DataSourceUtils.getConnection(source);
        try {
            Map<String, Set<String>> actual = columns(connection);
            List<String> missing = new ArrayList<>(missingCoreCapabilities(actual));
            missing.addAll(missingCoreUniqueKeys(connection, actual));
            Collections.sort(missing);
            return List.copyOf(missing);
        } finally {
            // Retain the caller's transaction connection and its read/write state.
            DataSourceUtils.releaseConnection(connection, source);
        }
    }

    /** New write entries must fail closed before committing any business effect. */
    public void require(DataSource source) {
        List<String> missing = missing(source);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Platform write prerequisites missing: " + missing);
        }
    }

    private static List<String> missingCoreCapabilities(Map<String, Set<String>> actual) {
        Map<String, String> contract = Map.of(
            "sys_idempotent", "id idem_key endpoint user_no result_json created_at expire_at request_hash",
            "sys_outbox", "id event_no aggregate_type aggregate_id event_type payload status retry_count "
                    + "next_retry_at last_error created_at sent_at source_system source_event_no "
                    + "schema_version payload_hash stream_key stream_sequence routing_mode occurred_at",
            "sys_outbox_stream", "stream_key next_sequence",
            "sys_outbox_delivery", "id event_no handler_id stream_key stream_sequence external_workflow status "
                    + "attempt_count round_attempt_count replay_count next_retry_at claim_token "
                    + "lease_until first_attempt_at last_attempt_at last_replayed_at completed_at "
                    + "last_error_code last_error created_at",
            "sys_outbox_operation", "id request_id event_no handler_id operation actor reason original_error_code "
                    + "original_error original_attempt_count original_round_attempt_count "
                    + "original_last_attempt_at original_next_retry_at before_status after_status "
                    + "created_at");
        List<String> missing = new ArrayList<>();
        contract.forEach((table, fields) -> {
            if (!actual.containsKey(table)) missing.add(table);
            else for (String field : fields.split(" "))
                if (!actual.get(table).contains(field)) missing.add(table + "." + field);
        });
        return missing;
    }

    private static List<String> missingCoreUniqueKeys(Connection connection, Map<String, Set<String>> actual) {
        Map<String, List<String>> required = Map.of(
                "sys_idempotent", List.of("idem_key,endpoint"),
                "sys_outbox", List.of("event_no", "source_system,source_event_no", "stream_key,stream_sequence"),
                "sys_outbox_stream", List.of("stream_key"),
                "sys_outbox_delivery", List.of("event_no,handler_id"),
                "sys_outbox_operation", List.of("request_id"));
        List<String> missing = new ArrayList<>();
        try {
            for (var contract : required.entrySet()) {
                if (!actual.containsKey(contract.getKey())) continue;
                Map<String, SortedMap<Integer, String>> keys = new HashMap<>();
                String physicalTable = connection.getMetaData().storesUpperCaseIdentifiers()
                        ? contract.getKey().toUpperCase(Locale.ROOT) : contract.getKey();
                try (var rows = connection.getMetaData().getIndexInfo(connection.getCatalog(),
                        connection.getSchema(), physicalTable, true, false)) {
                    while (rows.next()) {
                        String field = rows.getString("COLUMN_NAME"); String name = rows.getString("INDEX_NAME");
                        if (name != null && field != null) keys.computeIfAbsent(name, n -> new TreeMap<>())
                                .put(rows.getInt("ORDINAL_POSITION"), field.toLowerCase(Locale.ROOT));
                    }
                }
                Set<String> present = new HashSet<>();
                keys.values().forEach(key -> present.add(String.join(",", key.values())));
                for (String key : contract.getValue()) if (!present.contains(key))
                    missing.add(contract.getKey() + ".unique(" + key + ")");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot inspect primary unique keys (SQLState="
                    + ex.getSQLState() + ")");
        }
        return missing;
    }

    private static Map<String, Set<String>> columns(Connection connection) {
        try {
            var metadata = connection.getMetaData();
            Map<String, Set<String>> result = new TreeMap<>();
            try (var tables = metadata.getTables(connection.getCatalog(), connection.getSchema(),
                    "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String table = tables.getString("TABLE_NAME");
                    Set<String> fields = new TreeSet<>();
                    try (var columns = metadata.getColumns(connection.getCatalog(), connection.getSchema(),
                            table, "%")) {
                        while (columns.next()) {
                            // JDBC tableNamePattern treats underscores as wildcards; never merge lookalike tables.
                            if (table.equals(columns.getString("TABLE_NAME")))
                                fields.add(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                        }
                    }
                    result.put(table.toLowerCase(Locale.ROOT), fields);
                }
            }
            return result;
        } catch (SQLException ex) {
            // Driver messages may contain connection details. Do not expose them in startup diagnostics.
            throw new IllegalStateException("Cannot inspect platform write schema (SQLState=" + ex.getSQLState() + ")");
        }
    }

}
