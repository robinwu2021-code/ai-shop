package ai.neargo.shop.beauty.policy;

import ai.neargo.shop.common.RuntimeTriggerMetadata;
import static ai.neargo.shop.beauty.policy.BeautyExecutionSql.expression;
import static ai.neargo.shop.beauty.policy.BeautyExecutionSql.tokens;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.DataSourceUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import javax.sql.DataSource;

/** Per-use physical owner inspection keeps optional execution damage from becoming an unfenced legacy write. */
public final class ServiceExecutionSchemaVerifier {
    private static final String DESCRIPTOR = "META-INF/shop/beauty-service-execution-fence-schema.json";
    private static final String MIGRATION = "db/industry/beauty/V10__beauty_service_execution_fence.sql";
    private static final Set<String> TABLES = Set.of(
            "bty_service_execution_fence");
    private final DataSource source;
    private final String historyTable;
    private final String historySchema;

    /** Only existing primary datasource and Flyway owner settings are captured; construction performs no IO. */
    public ServiceExecutionSchemaVerifier(@Qualifier("dataSource") DataSource source) {
        this.source = Objects.requireNonNull(source, "source");
        this.historyTable = "bty_flyway_history";
        this.historySchema = null;
    }

    /** ABSENT is legal only when neither a V10 marker nor any new owner table exists. */
    public enum Installation {
        ABSENT, INSTALLED, CORRUPT
    }

    /** Reinspect on every affected use; a previously valid installation never masks later physical drift. */
    public Installation installation() {
        Connection connection = null;
        try {
            identifier(historyTable);
            if (historySchema != null) {
                identifier(historySchema);
            }
            connection = DataSourceUtils.getConnection(source);
            String product = connection.getMetaData().getDatabaseProductName();
            String schema = "MySQL".equals(product) || "MariaDB".equals(product)
                    ? connection.getCatalog() : connection.getSchema();
            if (schema == null || schema.isBlank()) {
                schema = connection.getSchema();
            }
            require(schema != null && !schema.isBlank());
            require(historySchema == null || historySchema.equals(schema));
            int present = 0;
            for (String table : TABLES) {
                if (tableExists(connection, schema, table)) {
                    present++;
                }
            }
            List<Map<String, Object>> markers = tableExists(connection, schema, historyTable)
                    ? query(connection, "SELECT version, script, type, checksum, success FROM "
                            + quoted(schema) + "." + quoted(historyTable) + " WHERE version=?", "10")
                    : List.of();
            if (present == 0 && markers.isEmpty()) {
                return Installation.ABSENT;
            }
            require(present == 1 && markers.size() == 1);
            JsonNode contract = descriptor();
            Map<String, Object> marker = markers.getFirst();
            require("V10__beauty_service_execution_fence.sql".equals(text(marker, "script"))
                    && "SQL".equals(text(marker, "type")) && success(marker.get("success"))
                    && marker.get("checksum") instanceof Number checksum
                    && checksum.intValue() == contract.path("flywayChecksum").asInt());
            inspect(connection, schema, contract);
            return Installation.INSTALLED;
        } catch (Exception failure) {
            return Installation.CORRUPT;
        } finally {
            if (connection != null) {
                DataSourceUtils.releaseConnection(connection, source);
            }
        }
    }

    /** New execution cannot start using absent or damaged authority, regardless of provider feature flags. */
    public void requireAvailable() {
        if (installation() != Installation.INSTALLED) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, "EXECUTION_SCHEMA_UNAVAILABLE");
        }
    }

    private JsonNode descriptor() throws Exception {
        var loader = getClass().getClassLoader();
        var descriptors = Collections.list(loader.getResources(DESCRIPTOR));
        var migrations = Collections.list(loader.getResources(MIGRATION));
        require(descriptors.size() == 1 && migrations.size() == 1);
        JsonNode contract;
        try (InputStream stream = descriptors.getFirst().openStream()) {
            contract = JsonMapper.builder().build().readTree(stream);
        }
        require(contract.path("schemaVersion").asInt() == 1 && contract.path("platformVersion").asInt() == 10
                && "beauty-core/primary".equals(contract.path("owner").asString())
                && contract.path("tables").size() == 1 && contract.path("triggers").size() == 2);
        try (InputStream stream = migrations.getFirst().openStream()) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
            require(hash.equals(contract.path("migrationSha256").asString()));
        }
        return contract;
    }

    private void inspect(Connection connection, String schema, JsonNode contract) throws SQLException {
        String tenantCollation = null;
        for (JsonNode identity : contract.path("ownerIdentity")) {
            List<Map<String, Object>> rows = columns(connection, schema, identity.path("table").asString());
            Map<String, Object> column = rows.stream()
                    .filter(row -> identity.path("column").asString().equals(text(row, "column_name")))
                    .findFirst().orElseThrow();
            require("utf8mb4".equals(text(column, "character_set_name"))
                    && identity.path("type").asString().equals(text(column, "column_type")));
            String collation = text(column, "collation_name");
            if ("TENANT".equals(identity.path("role").asString())) {
                require(!collation.isBlank());
                if (tenantCollation == null) {
                    tenantCollation = collation;
                } else {
                    require(tenantCollation.equals(collation));
                }
            } else {
                require("utf8mb4_bin".equals(collation));
            }
        }
        require(tenantCollation != null);
        Set<String> seen = new HashSet<>();
        for (JsonNode table : contract.path("tables")) {
            String name = table.path("name").asString();
            require(TABLES.contains(name) && seen.add(name));
            var physical = query(connection,
                    "SELECT ENGINE, TABLE_COLLATION FROM information_schema.TABLES"
                            + " WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",
                    schema, name);
            require(physical.size() == 1 && "InnoDB".equals(text(physical.getFirst(), "engine"))
                    && tenantCollation.equals(text(physical.getFirst(), "table_collation")));
            List<Map<String, Object>> actual = columns(connection, schema, name);
            require(actual.size() == table.path("columns").size());
            for (int i = 0; i < actual.size(); i++) {
                checkColumn(actual.get(i), table.path("columns").get(i), tenantCollation);
            }
            checkKeys(connection, schema, table);
            checkConstraints(connection, schema, table);
        }
        require(seen.equals(TABLES));
        Map<String, Map<String, Object>> triggers = new HashMap<>();
        for (var trigger : RuntimeTriggerMetadata.read(connection, schema,
                RuntimeTriggerMetadata.Scope.BEAUTY_EXECUTION)) {
            require(triggers.put(text(trigger, "trigger_name"), trigger) == null);
        }
        require(triggers.size() == contract.path("triggers").size());
        for (JsonNode expected : contract.path("triggers")) {
            var trigger = triggers.get(expected.path("name").asString());
            require(trigger != null
                    && text(trigger, "event_manipulation").equals(expected.path("event").asString())
                    && text(trigger, "event_object_table").equals(expected.path("table").asString())
                    && text(trigger, "action_timing").equals(expected.path("timing").asString())
                    && tokens(text(trigger, "action_statement")).equals(tokens(expected.path("body").asString())));
        }
    }

    private static void checkColumn(Map<String, Object> actual, JsonNode expected, String tenantCollation) {
        String name = expected.path("name").asString();
        String type = text(actual, "column_type").toLowerCase(Locale.ROOT)
                .replaceAll("\\b(bigint|int)\\([0-9]+\\)", "$1");
        String expectedCollation = nullable(expected.get("collation"));
        String expectedCharset = nullable(expected.get("charset"));
        if ("OWNER_TENANT".equals(expectedCollation)) {
            expectedCollation = tenantCollation;
            expectedCharset = "utf8mb4";
        }
        require(name.equals(text(actual, "column_name")) && type.equals(expected.path("type").asString())
                && (expected.path("nullable").asBoolean() ? "YES" : "NO").equals(text(actual, "is_nullable"))
                && Objects.equals(expectedCharset, actual.get("character_set_name"))
                && Objects.equals(expectedCollation, actual.get("collation_name"))
                && Objects.equals(defaultValue(nullable(expected.get("default"))),
                        defaultValue(actual.get("column_default")))
                && ("id".equals(name) ? "auto_increment" : "").equals(text(actual, "extra")));
    }

    private static void checkKeys(Connection connection, String schema, JsonNode table) throws SQLException {
        Map<String, List<String>> keys = new TreeMap<>();
        Map<String, Boolean> unique = new HashMap<>();
        for (var row : query(connection,
                "SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME, SUB_PART, INDEX_TYPE FROM information_schema.STATISTICS"
                        + " WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY INDEX_NAME,SEQ_IN_INDEX",
                schema, table.path("name").asString())) {
            String name = text(row, "index_name");
            require(row.get("sub_part") == null && "BTREE".equals(text(row, "index_type")));
            keys.computeIfAbsent(name, ignored -> new ArrayList<>()).add(text(row, "column_name"));
            unique.put(name, ((Number) row.get("non_unique")).intValue() == 0);
        }
        Map<String, List<String>> expected = new TreeMap<>();
        Map<String, Boolean> expectedUnique = new HashMap<>();
        expected.put("PRIMARY", strings(table.path("primaryKey")));
        expectedUnique.put("PRIMARY", true);
        for (String kind : List.of("unique", "indexes")) {
            table.path(kind).properties().forEach(entry -> {
                expected.put(entry.getKey(), strings(entry.getValue()));
                expectedUnique.put(entry.getKey(), "unique".equals(kind));
            });
        }
        require(keys.equals(expected) && unique.equals(expectedUnique));
    }

    /** Render only the frozen boolean-valued COALESCE forms with an explicit MySQL result type. */
    public static String mysqlCheck(String clause) {
        if (clause.startsWith("COALESCE(")) return "(" + clause + ")=1";
        String prefix = "JSON_VALID(binding_json) AND ";
        if (clause.startsWith(prefix + "COALESCE(")) {
            return prefix + "(" + clause.substring(prefix.length()) + ")=1";
        }
        return clause;
    }

    private static void checkConstraints(Connection connection, String schema, JsonNode table) throws SQLException {
        Map<String, String> actual = new TreeMap<>();
        for (var row : query(connection,
                "SELECT t.CONSTRAINT_NAME,c.CHECK_CLAUSE FROM information_schema.TABLE_CONSTRAINTS t"
                        + " JOIN information_schema.CHECK_CONSTRAINTS c ON c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA"
                        + " AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME"
                        + " WHERE t.TABLE_SCHEMA=? AND t.TABLE_NAME=? AND t.CONSTRAINT_TYPE='CHECK'",
                schema, table.path("name").asString())) {
            require(actual.put(text(row, "constraint_name"), expression(text(row, "check_clause"))) == null);
        }
        Map<String, String> expected = new TreeMap<>();
        var product = connection.getMetaData();
        boolean mysql9 = "MySQL".equals(product.getDatabaseProductName()) && product.getDatabaseMajorVersion() == 9;
        table.path("checks").properties().forEach(entry -> {
            String clause = entry.getValue().asString();
            String legacy = expression(clause);
            if (mysql9 && entry.getKey().startsWith("ck_bsef_json_")
                    && expression(clause + " = 1").equals(actual.get(entry.getKey()))) {
                actual.put(entry.getKey(), legacy);
            }
            expected.put(entry.getKey(), legacy);
        });
        require(actual.equals(expected));
        var enforcementColumn = query(connection,
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='information_schema'"
                        + " AND TABLE_NAME='TABLE_CONSTRAINTS' AND COLUMN_NAME='ENFORCED'");
        if (!enforcementColumn.isEmpty()) {
            var enforced = query(connection,
                    "SELECT ENFORCED FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=?"
                            + " AND TABLE_NAME=? AND CONSTRAINT_TYPE='CHECK'", schema, table.path("name").asString());
            require(enforced.size() == expected.size()
                    && enforced.stream().allMatch(row -> "YES".equals(text(row, "enforced"))));
        }
    }

    private static List<Map<String, Object>> columns(Connection connection, String schema, String table)
            throws SQLException {
        return query(connection,
                "SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,CHARACTER_SET_NAME,COLLATION_NAME,COLUMN_DEFAULT,EXTRA"
                        + " FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?"
                        + " ORDER BY ORDINAL_POSITION",
                schema, table);
    }

    private static boolean tableExists(Connection connection, String schema, String name) throws SQLException {
        return !query(connection,
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",
                schema, name).isEmpty();
    }

    private static List<Map<String, Object>> query(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            try (ResultSet results = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                var metadata = results.getMetaData();
                while (results.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        row.put(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT), results.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private static String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }

    private static String nullable(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static String defaultValue(Object value) {
        if (value == null || "NULL".equals(value.toString())) {
            return null;
        }
        String text = value.toString();
        return text.startsWith("'") && text.endsWith("'") ? text.substring(1, text.length() - 1) : text;
    }

    private static boolean success(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() == 1;
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(value.asString()));
        return result;
    }

    private static String identifier(String value) {
        if (value == null || value.isBlank() || value.length() > 64 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid configured owner schema identifier");
        }
        return value;
    }

    private static String quoted(String value) {
        return "`" + identifier(value).replace("`", "``") + "`";
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalStateException("EXECUTION_SCHEMA_UNAVAILABLE");
        }
    }
}
