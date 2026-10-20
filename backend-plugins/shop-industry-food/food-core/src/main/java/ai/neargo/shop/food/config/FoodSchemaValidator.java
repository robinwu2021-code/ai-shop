package ai.neargo.shop.food.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** 餐饮私有历史校验；只在显式 MIGRATE 模式执行餐饮迁移，从不接管平台 Flyway。 */
public final class FoodSchemaValidator {
    public static final String HISTORY = "fnb_flyway_history";
    private static final String RESOURCE = "db/industry/food/V1__food_business_tables.sql";
    private static final String SHA256 = "215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9";

    public FoodSchemaReady initialize(DataSource source, FoodProperties.MigrationMode mode) {
        if (mode == FoodProperties.MigrationMode.OFF) return FoodSchemaReady.off();
        if (mode == FoodProperties.MigrationMode.VALIDATE) source = readOnly(source);
        Map<String, Set<String>> expected = expectedColumns();
        Map<String, Set<String>> before = columns(source);
        require(before.containsKey("prd_goods"), "missing primary fact: prd_goods");
        require(before.containsKey("ord_order"), "missing primary fact: ord_order");
        boolean history = before.containsKey(HISTORY);
        boolean existing = before.keySet().stream().anyMatch(n -> n.startsWith("fnb_") && !n.equals(HISTORY));
        require(history || !existing, "untracked food tables: refusing implicit baseline");
        require(history || mode == FoodProperties.MigrationMode.MIGRATE, "missing fnb_flyway_history; publish food migrations first");
        Set<String> unknown = new TreeSet<>(before.keySet());
        unknown.removeIf(n -> !n.startsWith("fnb_") || n.equals(HISTORY) || expected.containsKey(n));
        require(unknown.isEmpty(), "unknown food tables: " + unknown);
        // Local object, deliberately NOT a Spring Flyway bean. No platform locations/baseline/repair.
        Flyway flyway = Flyway.configure().dataSource(source).locations("classpath:db/industry/food")
                .table(HISTORY).baselineOnMigrate(mode == FoodProperties.MigrationMode.MIGRATE)
                .baselineVersion("0").createSchemas(false).cleanDisabled(true)
                .validateOnMigrate(true).placeholderReplacement(false).ignoreMigrationPatterns(new String[0])
                .failOnMissingLocations(true).validateMigrationNaming(true).load();
        if (mode == FoodProperties.MigrationMode.MIGRATE) flyway.migrate();
        flyway.validate();
        for (var migration : flyway.info().all()) {
            String state = migration.getState().name();
            require(state.equals("SUCCESS") || state.equals("BASELINE"), "food migration state: " + state);
        }
        var current = flyway.info().current();
        require(current != null && "1".equals(current.getVersion().getVersion()), "food migration version must be 1");
        var actual = columns(source);
        List<String> missing = new ArrayList<>();
        expected.forEach((table, fields) -> fields.forEach(field -> {
            if (!actual.getOrDefault(table, Set.of()).contains(field)) missing.add(table + "." + field);
        }));
        require(missing.isEmpty(), "missing food columns: " + missing);
        missing.addAll(missingCoreCapabilities(actual));
        missing.addAll(missingCoreUniqueKeys(source, actual));
        Collections.sort(missing);
        return new FoodSchemaReady(true, "1", missing);
    }

    private static List<String> missingCoreCapabilities(Map<String, Set<String>> actual) {
        Map<String, String> contract = Map.of(
            "sys_idempotent", "id idem_key endpoint user_no result_json created_at expire_at request_hash",
            "sys_outbox", "id event_no aggregate_type aggregate_id event_type payload status retry_count next_retry_at last_error created_at sent_at source_system source_event_no schema_version payload_hash stream_key stream_sequence routing_mode occurred_at",
            "sys_outbox_stream", "stream_key next_sequence",
            "sys_outbox_delivery", "id event_no handler_id stream_key stream_sequence external_workflow status attempt_count round_attempt_count replay_count next_retry_at claim_token lease_until first_attempt_at last_attempt_at last_replayed_at completed_at last_error_code last_error created_at",
            "sys_outbox_operation", "id request_id event_no handler_id operation actor reason original_error_code original_error original_attempt_count original_round_attempt_count original_last_attempt_at original_next_retry_at before_status after_status created_at");
        List<String> missing = new ArrayList<>();
        contract.forEach((table, fields) -> {
            if (!actual.containsKey(table)) missing.add(table);
            else for (String field : fields.split(" "))
                if (!actual.get(table).contains(field)) missing.add(table + "." + field);
        });
        return missing;
    }

    private static List<String> missingCoreUniqueKeys(DataSource source, Map<String, Set<String>> actual) {
        Map<String, List<String>> required = Map.of(
                "sys_idempotent", List.of("idem_key,endpoint"),
                "sys_outbox", List.of("event_no", "source_system,source_event_no", "stream_key,stream_sequence"),
                "sys_outbox_stream", List.of("stream_key"),
                "sys_outbox_delivery", List.of("event_no,handler_id"),
                "sys_outbox_operation", List.of("request_id"));
        List<String> missing = new ArrayList<>();
        try (Connection connection = source.getConnection()) {
            connection.setReadOnly(true);
            for (var contract : required.entrySet()) {
                if (!actual.containsKey(contract.getKey())) continue;
                Map<String, SortedMap<Integer, String>> keys = new HashMap<>();
                try (var rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), connection.getSchema(), contract.getKey(), true, false)) {
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
        } catch (SQLException ex) { throw new IllegalStateException("Cannot inspect primary unique keys (SQLState=" + ex.getSQLState() + ")"); }
        return missing;
    }

    private static DataSource readOnly(DataSource source) {
        return new DelegatingDataSource(source) {
            @Override public Connection getConnection() throws SQLException {
                return markReadOnly(super.getConnection());
            }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                return markReadOnly(super.getConnection(user, password));
            }
            private Connection markReadOnly(Connection connection) throws SQLException {
                try { connection.setReadOnly(true); return connection; }
                catch (SQLException ex) { connection.close(); throw ex; }
            }
        };
    }

    private static Map<String, Set<String>> columns(DataSource source) {
        try (Connection connection = source.getConnection()) {
            connection.setReadOnly(true);
            var metadata = connection.getMetaData();
            Map<String, Set<String>> result = new TreeMap<>();
            try (var tables = metadata.getTables(connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String table = tables.getString("TABLE_NAME");
                    Set<String> fields = new TreeSet<>();
                    try (var columns = metadata.getColumns(connection.getCatalog(), connection.getSchema(), table, "%")) {
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
            throw new IllegalStateException("Cannot inspect primary food schema (SQLState=" + ex.getSQLState() + ")");
        }
    }

    private static Map<String, Set<String>> expectedColumns() {
        try {
            var resources = Collections.list(FoodSchemaValidator.class.getClassLoader().getResources(RESOURCE));
            require(resources.size() == 1, "food V1 resource must have exactly one owner");
            byte[] bytes;
            try (var stream = resources.getFirst().openStream()) { bytes = stream.readAllBytes(); }
            require(SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))), "food V1 immutable checksum mismatch");
            var tables = Pattern.compile("CREATE TABLE `([^`]+)`\\s*\\((.*?)\\) ENGINE", Pattern.DOTALL)
                    .matcher(new String(bytes, StandardCharsets.UTF_8));
            Map<String, Set<String>> expected = new TreeMap<>();
            while (tables.find()) {
                Set<String> fields = new TreeSet<>();
                var columns = Pattern.compile("(?m)^\\s*`([^`]+)`\\s+").matcher(tables.group(2));
                while (columns.find()) fields.add(columns.group(1));
                expected.put(tables.group(1), fields);
            }
            require(expected.size() == 73 && expected.values().stream().noneMatch(Set::isEmpty), "food V1 manifest incomplete");
            return expected;
        } catch (IllegalStateException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalStateException("Cannot read immutable food V1 manifest"); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
