package ai.neargo.shop.inventory.policy;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Component;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/** Per-use physical owner inspection keeps optional execution damage from becoming an unfenced legacy write. */
@Component("inventoryExecutionStockRetentionSchemaVerifier")
@ConditionalOnInventory
public final class ExecutionStockRetentionSchemaVerifier {
    private static final String DESCRIPTOR = "META-INF/shop/inventory-execution-retention-schema.json";
    private static final String MIGRATION = "db/inventory/V15__execution_stock_retention.sql";
    private static final Set<String> TABLES = Set.of(
            "inv_execution_retention_claim", "inv_execution_retention");
    private final DataSource source;
    private final String historyTable;
    private final String historySchema;

    /** Existing Inventory datasource/history ownership is fixed; construction performs no IO. */
    @Autowired
    public ExecutionStockRetentionSchemaVerifier(@Qualifier("invDataSource") DataSource source) {
        this(source, "inv_flyway_history", null);
    }

    /** Explicit native fixture boundary also allows testing a configured wrong physical catalog. */
    public ExecutionStockRetentionSchemaVerifier(DataSource source, String historyTable, String historySchema) {
        this.source = Objects.requireNonNull(source, "source");
        this.historyTable = historyTable;
        this.historySchema = historySchema == null || historySchema.isBlank() ? null : historySchema;
    }

    /** ABSENT is legal only when neither a V15 marker nor any new owner table exists. */
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
                            + quoted(schema) + "." + quoted(historyTable) + " WHERE version=?", "15")
                    : List.of();
            if (present == 0 && markers.isEmpty()) {
                return Installation.ABSENT;
            }
            require(present == 2 && markers.size() == 1);
            JsonNode contract = descriptor();
            Map<String, Object> marker = markers.getFirst();
            require("V15__execution_stock_retention.sql".equals(text(marker, "script"))
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

    /** Local lock handles are reusable only inside this exact active Inventory transaction. */
    boolean ownsCurrentTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.hasResource(source);
    }

    /** New execution cannot start using absent or damaged authority, regardless of provider feature flags. */
    public void requireAvailable() {
        if (installation() != Installation.INSTALLED) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE,
                "EXECUTION_STOCK_RETENTION_SCHEMA_UNAVAILABLE");
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
        require(contract.path("schemaVersion").asInt() == 1 && contract.path("inventoryVersion").asInt() == 15
                && "shop-inventory/invDataSource".equals(contract.path("owner").asString())
                && contract.path("tables").size() == 2 && contract.path("triggers").size() == 4);
        try (InputStream stream = migrations.getFirst().openStream()) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
            require(hash.equals(contract.path("migrationSha256").asString()));
        }
        return contract;
    }

    private void inspect(Connection connection, String schema, JsonNode contract) throws SQLException {
        String ownerCollation = null;
        for (JsonNode identity : contract.path("ownerIdentity")) {
            List<Map<String, Object>> rows = columns(connection, schema, identity.path("table").asString());
            Map<String, Object> column = rows.stream()
                    .filter(row -> identity.path("column").asString().equals(text(row, "column_name")))
                    .findFirst().orElseThrow();
            require("utf8mb4".equals(text(column, "character_set_name"))
                    && identity.path("type").asString().equals(text(column, "column_type")));
            String collation = text(column, "collation_name");
            if ("OWNER".equals(identity.path("role").asString())) {
                require(!collation.isBlank());
                if (ownerCollation == null) {
                    ownerCollation = collation;
                } else {
                    require(ownerCollation.equals(collation));
                }
            } else {
                require("utf8mb4_bin".equals(collation));
            }
        }
        require(ownerCollation != null && ownerCollation.equals(contract.path("ownerCollation").asString()));
        Set<String> seen = new HashSet<>();
        for (JsonNode table : contract.path("tables")) {
            String name = table.path("name").asString();
            require(TABLES.contains(name) && seen.add(name));
            var physical = query(connection,
                    "SELECT ENGINE, TABLE_COLLATION FROM information_schema.TABLES"
                            + " WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",
                    schema, name);
            require(physical.size() == 1 && "InnoDB".equals(text(physical.getFirst(), "engine"))
                    && ownerCollation.equals(text(physical.getFirst(), "table_collation")));
            List<Map<String, Object>> actual = columns(connection, schema, name);
            require(actual.size() == table.path("columns").size());
            for (int i = 0; i < actual.size(); i++) {
                checkColumn(actual.get(i), table.path("columns").get(i), ownerCollation);
            }
            checkKeys(connection, schema, table);
            checkConstraints(connection, schema, table);
        }
        require(seen.equals(TABLES));
        Map<String, Map<String, Object>> triggers = new HashMap<>();
        for (var trigger : query(connection,
                "SELECT TRIGGER_NAME,EVENT_MANIPULATION,EVENT_OBJECT_TABLE,ACTION_TIMING,ACTION_STATEMENT"
                        + " FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=?"
                        + " AND EVENT_OBJECT_TABLE IN (?,?)", schema,
                "inv_execution_retention_claim", "inv_execution_retention")) {
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

    private static void checkColumn(Map<String, Object> actual, JsonNode expected, String ownerCollation) {
        String name = expected.path("name").asString();
        String type = text(actual, "column_type").toLowerCase(Locale.ROOT)
                .replaceAll("\\b(bigint|int)\\([0-9]+\\)", "$1");
        String expectedCollation = nullable(expected.get("collation"));
        String expectedCharset = nullable(expected.get("charset"));
        if ("OWNER_ID".equals(expectedCollation)) {
            expectedCollation = ownerCollation;
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
        table.path("checks").properties().forEach(entry ->
                expected.put(entry.getKey(), expression(entry.getValue().asString())));
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
            throw new IllegalStateException("EXECUTION_STOCK_RETENTION_SCHEMA_UNAVAILABLE");
        }
    }

    // Preserve literal bytes and boolean grouping. Only quoting, whitespace and charset introducers normalize.
    private static List<String> tokens(String sql) {
        Pattern token = Pattern.compile("\\s+|'(?:''|\\\\.|[^'\\\\])*'|`[^`]+`|<=>|<>|!=|<=|>=|"
                + "[a-zA-Z_][a-zA-Z0-9_]*|[0-9]+|[(),=<>;+.-]");
        Matcher matcher = token.matcher(sql);
        List<String> result = new ArrayList<>();
        int offset = 0;
        while (offset < sql.length()) {
            matcher.region(offset, sql.length());
            require(matcher.lookingAt());
            String value = matcher.group();
            offset = matcher.end();
            if (value.isBlank() || value.matches("_(utf8mb4|utf8|ascii|latin1|binary)")) {
                continue;
            }
            if (value.startsWith("`")) {
                value = value.substring(1, value.length() - 1);
            }
            result.add(value.startsWith("'") ? value : value.toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static String expression(String sql) {
        Expression parser = new Expression(tokens(sql));
        String value = parser.or();
        require(parser.at == parser.input.size());
        return value;
    }

    /** Finite expression grammar retains AND/OR/BETWEEN precedence while accepting server-added parentheses. */
    private static final class Expression {
        private final List<String> input;
        private int at;

        private Expression(List<String> input) {
            this.input = input;
        }

        private String or() {
            List<String> values = new ArrayList<>();
            values.add(and());
            while (take("or")) {
                values.add(and());
            }
            return values.size() == 1 ? values.getFirst() : "or(" + String.join(",", values) + ")";
        }

        private String and() {
            List<String> values = new ArrayList<>();
            values.add(comparison());
            while (take("and")) {
                values.add(comparison());
            }
            return values.size() == 1 ? values.getFirst() : "and(" + String.join(",", values) + ")";
        }

        private String comparison() {
            if (take("not")) {
                return "not(" + comparison() + ")";
            }
            String left = atom();
            if (take("is")) {
                boolean negated = take("not");
                expect("null");
                return (negated ? "notnull(" : "isnull(") + left + ")";
            }
            if (take("between")) {
                String lower = atom();
                expect("and");
                return "between(" + left + "," + lower + "," + atom() + ")";
            }
            boolean not = take("not");
            if (take("in")) {
                expect("(");
                List<String> args = arguments();
                return (not ? "notin(" : "in(") + left + "," + String.join(",", args) + ")";
            }
            require(!not);
            if (at < input.size() && Set.of("=", "<>", "!=", "<", ">", "<=", ">=", "regexp")
                    .contains(input.get(at))) {
                String operation = input.get(at++);
                return ("!=".equals(operation) ? "<>" : operation) + "(" + left + "," + atom() + ")";
            }
            return left;
        }

        private String atom() {
            if (take("(")) {
                String value = or();
                expect(")");
                return value;
            }
            require(at < input.size());
            String value = input.get(at++);
            require(!List.of(")", ",", "and", "or").contains(value));
            if (take("(")) {
                var args = arguments();
                // Only the no-flags two-argument native spelling is equivalent to the REGEXP operator.
                String function = "regexp_like".equals(value) && args.size() == 2 ? "regexp" : value;
                return function + "(" + String.join(",", args) + ")";
            }
            return value;
        }

        private List<String> arguments() {
            List<String> args = new ArrayList<>();
            if (!take(")")) {
                args.add(or());
                while (take(",")) {
                    args.add(or());
                }
                expect(")");
            }
            return args;
        }

        private boolean take(String value) {
            if (at < input.size() && input.get(at).equals(value)) {
                at++;
                return true;
            }
            return false;
        }

        private void expect(String value) {
            require(take(value));
        }
    }
}
