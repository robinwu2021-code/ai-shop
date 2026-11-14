package ai.neargo.shop.trade.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.sql.NativeCheckMetadata;
import ai.neargo.shop.common.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
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
@Component
public final class ServiceExecutionSchemaVerifier {
    private static final String DESCRIPTOR = "META-INF/shop/manual-service-execution-schema.json";
    private static final String MIGRATION = "db/migration/V604__manual_service_execution_authority.sql";
    private static final String START_DESCRIPTOR = "META-INF/shop/service-execution-start-schema.json";
    private static final String START_MIGRATION = "db/migration/V649__service_execution_start_ack.sql";
    private static final String COMPLETION_DESCRIPTOR = "META-INF/shop/service-execution-completion-schema.json";
    private static final String COMPLETION_MIGRATION = "db/migration/V651__service_execution_completion.sql";
    private static final Set<String> TABLES = Set.of(
            "ord_service_execution_unit", "ord_service_execution", "ord_service_execution_action");
    private final DataSource source;
    private final String historyTable;
    private final String historySchema;

    /** Only existing primary datasource and Flyway owner settings are captured; construction performs no IO. */
    public ServiceExecutionSchemaVerifier(@Qualifier("dataSource") DataSource source,
            @Value("${spring.flyway.table:flyway_schema_history}") String historyTable,
            @Value("${spring.flyway.default-schema:}") String historySchema) {
        this.source = Objects.requireNonNull(source, "source");
        this.historyTable = historyTable;
        this.historySchema = historySchema == null || historySchema.isBlank() ? null : historySchema;
    }

    /** ABSENT is legal only when neither a V604 marker nor any new owner table exists. */
    public enum Installation {
        ABSENT, INSTALLED, CORRUPT
    }

    /** Reinspect on every affected use; a previously valid installation never masks later physical drift. */
    public Installation installation() {
        return installation(false);
    }

    private Installation installation(boolean startRequired) {
        return installation(startRequired, false);
    }

    private Installation installation(boolean startRequired, boolean completionRequired) {
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
                    ? history(connection, schema, historyTable, "604")
                    : List.of();
            List<Map<String, Object>> startMarkers = tableExists(connection, schema, historyTable)
                    ? history(connection, schema, historyTable, "649")
                    : List.of();
            List<Map<String, Object>> completionMarkers = tableExists(connection, schema, historyTable)
                    ? history(connection, schema, historyTable, "651") : List.of();
            if (present == 0 && markers.isEmpty() && startMarkers.isEmpty() && completionMarkers.isEmpty()) {
                return Installation.ABSENT;
            }
            require(present == 3 && markers.size() == 1);
            JsonNode contract = descriptor();
            Map<String, Object> marker = markers.getFirst();
            require("V604__manual_service_execution_authority.sql".equals(text(marker, "script"))
                    && "SQL".equals(text(marker, "type")) && success(marker.get("success"))
                    && marker.get("checksum") instanceof Number checksum
                    && checksum.intValue() == contract.path("flywayChecksum").asInt());
            if (!startMarkers.isEmpty()) {
                require(startMarkers.size() == 1);
                contract = startDescriptor(contract);
                var startMarker = startMarkers.getFirst();
                require("V649__service_execution_start_ack.sql".equals(text(startMarker, "script"))
                        && "SQL".equals(text(startMarker, "type")) && success(startMarker.get("success"))
                        && startMarker.get("checksum") instanceof Number checksum
                        && checksum.intValue() == contract.path("flywayChecksum").asInt());
            } else require(!startRequired);
            if (!completionMarkers.isEmpty()) {
                require(startMarkers.size() == 1 && completionMarkers.size() == 1);
                contract = completionDescriptor(contract);
                var completionMarker = completionMarkers.getFirst();
                require("V651__service_execution_completion.sql".equals(text(completionMarker, "script"))
                        && "SQL".equals(text(completionMarker, "type")) && success(completionMarker.get("success"))
                        && completionMarker.get("checksum") instanceof Number checksum
                        && checksum.intValue() == contract.path("flywayChecksum").asInt());
            } else require(!completionRequired);
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

    /** Base-only installations remain valid for old consumers, but cannot acknowledge actual effects. */
    public void requireStartAvailable() {
        if (installation(true) != Installation.INSTALLED) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, "EXECUTION_SCHEMA_UNAVAILABLE");
        }
    }

    /** A base/ACK installation cannot write completed quantity without the forward completion constraints. */
    public void requireCompletionAvailable() {
        if (installation(true, true) != Installation.INSTALLED) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, "EXECUTION_SCHEMA_UNAVAILABLE");
        }
    }

    private JsonNode completionDescriptor(JsonNode base) throws Exception {
        var loader = getClass().getClassLoader();
        var descriptors = Collections.list(loader.getResources(COMPLETION_DESCRIPTOR));
        var migrations = Collections.list(loader.getResources(COMPLETION_MIGRATION));
        require(descriptors.size() == 1 && migrations.size() == 1);
        JsonNode contract;
        try (InputStream stream = descriptors.getFirst().openStream()) {
            contract = JsonMapper.builder().build().readTree(stream);
        }
        require(contract.path("schemaVersion").asInt() == 1 && contract.path("platformVersion").asInt() == 651
                && "shop-core/primary".equals(contract.path("owner").asString())
                && contract.path("baseMigrationSha256").asString().equals(base.path("migrationSha256").asString())
                && contract.path("tables").size() == 3 && contract.path("postTriggers").size() == 9);
        try (InputStream stream = migrations.getFirst().openStream()) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
            require(hash.equals(contract.path("migrationSha256").asString()));
        }
        var successor = ((ObjectNode) contract).deepCopy();
        successor.set("triggers", contract.path("postTriggers"));
        return successor;
    }

    private JsonNode startDescriptor(JsonNode base) throws Exception {
        var loader = getClass().getClassLoader();
        var descriptors = Collections.list(loader.getResources(START_DESCRIPTOR));
        var migrations = Collections.list(loader.getResources(START_MIGRATION));
        require(descriptors.size() == 1 && migrations.size() == 1);
        JsonNode contract;
        try (InputStream stream = descriptors.getFirst().openStream()) {
            contract = JsonMapper.builder().build().readTree(stream);
        }
        require(contract.path("schemaVersion").asInt() == 1 && contract.path("platformVersion").asInt() == 649
                && "shop-core/primary".equals(contract.path("owner").asString())
                && contract.path("baseMigrationSha256").asString().equals(base.path("migrationSha256").asString())
                && contract.path("tables").size() == 3 && contract.path("postTriggers").size() == 7);
        try (InputStream stream = migrations.getFirst().openStream()) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
            require(hash.equals(contract.path("migrationSha256").asString()));
        }
        var successor = ((ObjectNode) contract).deepCopy();
        successor.set("triggers", contract.path("postTriggers"));
        return successor;
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
        require(contract.path("schemaVersion").asInt() == 1 && contract.path("platformVersion").asInt() == 604
                && "shop-core/primary".equals(contract.path("owner").asString())
                && contract.path("tables").size() == 3 && contract.path("triggers").size() == 6);
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
        for (var trigger : query(connection,
                "SELECT TRIGGER_NAME,EVENT_MANIPULATION,EVENT_OBJECT_TABLE,ACTION_TIMING,ACTION_STATEMENT"
                        + " FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=?"
                        + " AND EVENT_OBJECT_TABLE IN (?,?,?)", schema,
                "ord_service_execution_unit", "ord_service_execution", "ord_service_execution_action")) {
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

    private static void checkConstraints(Connection connection, String schema, JsonNode table) throws SQLException {
        Map<String, String> actual = new TreeMap<>();
        for (var row : query(connection,
                "SELECT t.CONSTRAINT_NAME,c.CHECK_CLAUSE FROM information_schema.TABLE_CONSTRAINTS t"
                        + " JOIN information_schema.CHECK_CONSTRAINTS c ON c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA"
                        + " AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME"
                        + " WHERE t.TABLE_SCHEMA=? AND t.TABLE_NAME=? AND t.CONSTRAINT_TYPE='CHECK'",
                schema, table.path("name").asString())) {
            require(actual.put(text(row, "constraint_name"), expression(NativeCheckMetadata.decode(text(row, "check_clause")))) == null);
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

    private static List<Map<String, Object>> history(Connection connection, String schema,
            String table, String version) throws SQLException {
        String columns = historyQuote(connection, "version") + "," + historyQuote(connection, "script")
                + "," + historyQuote(connection, "type") + "," + historyQuote(connection, "checksum")
                + "," + historyQuote(connection, "success");
        return query(connection, "SELECT " + columns + " FROM " + historyQuote(connection, schema)
                + "." + historyQuote(connection, table) + " WHERE " + historyQuote(connection, "version") + "=?", version);
    }

    private static String historyQuote(Connection connection, String value) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString().strip();
        require(Set.of("`", "\"").contains(quote));
        return quote + identifier(value).replace(quote, quote + quote) + quote;
    }

    private static boolean tableExists(Connection connection, String schema, String name) throws SQLException {
        return !query(connection,
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND LOWER(TABLE_NAME)=LOWER(?)",
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
            return NativeCheckMetadata.combine("or", values);
        }

        private String and() {
            List<String> values = new ArrayList<>();
            values.add(comparison());
            while (take("and")) {
                values.add(comparison());
            }
            return NativeCheckMetadata.combine("and", values);
        }

        private String comparison() {
            if (take("not")) {
                return "not(" + comparison() + ")";
            }
            String left = sum();
            if (take("is")) {
                boolean negated = take("not");
                expect("null");
                return (negated ? "notnull(" : "isnull(") + left + ")";
            }
            if (take("between")) {
                String lower = sum();
                expect("and");
                return "between(" + left + "," + lower + "," + sum() + ")";
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
                return ("!=".equals(operation) ? "<>" : operation) + "(" + left + "," + sum() + ")";
            }
            return left;
        }

        private String sum() {
            String value = atom();
            while (at < input.size() && Set.of("+", "-").contains(input.get(at))) {
                String operation = input.get(at++);
                value = operation + "(" + value + "," + atom() + ")";
            }
            return value;
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
                if ("octet_length".equals(value)) value = "length";
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
