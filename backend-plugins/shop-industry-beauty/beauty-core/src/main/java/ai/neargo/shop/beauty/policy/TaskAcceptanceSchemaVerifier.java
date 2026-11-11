package ai.neargo.shop.beauty.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Per-use exact history and physical guards distinguish missing capability from partial or changed schema. */
public final class TaskAcceptanceSchemaVerifier {
    private static final String SCRIPT = "V14__beauty_task_acceptance.sql";
    private static final String DESCRIPTOR = "META-INF/shop/beauty-task-acceptance-schema.json";
    private final DataSource source;
    public TaskAcceptanceSchemaVerifier(DataSource source) {
        this.source = Objects.requireNonNull(source);
    }
    public enum Installation { ABSENT, INSTALLED, CORRUPT }

    /** No cached marker or feature flag may conceal physical drift after a previously valid request. */
    public Installation installation() {
        Connection connection = null;
        try {
            connection = DataSourceUtils.getConnection(source);
            String schema = connection.getCatalog();
            if (schema == null || !schema.matches("[a-zA-Z0-9_]+")) {
                return Installation.CORRUPT;
            }
            var triggers = query(connection,
                    "SELECT TRIGGER_NAME,EVENT_MANIPULATION,EVENT_OBJECT_TABLE,ACTION_TIMING,ACTION_STATEMENT"
                    + " FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? AND TRIGGER_NAME IN"
                    + " ('trg_bty_task_accept_immutable','trg_bty_task_accept_no_delete')", schema);
            var checks = query(connection,
                    "SELECT cc.CHECK_CLAUSE,tc.ENFORCED FROM information_schema.TABLE_CONSTRAINTS tc"
                    + " JOIN information_schema.CHECK_CONSTRAINTS cc ON tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA"
                    + " AND tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME WHERE tc.TABLE_SCHEMA=? AND "
                            + "tc.TABLE_NAME='bty_service_submission'"
                    + " AND tc.CONSTRAINT_NAME='ck_bty_task_accept' AND tc.CONSTRAINT_TYPE='CHECK'", schema);
            boolean history = !query(connection, "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=?"
                    + " AND TABLE_NAME='bty_flyway_history'", schema).isEmpty();
            var markers = history ? query(connection, "SELECT script,type,checksum,success FROM `" + schema
                    + "`.bty_flyway_history WHERE version='14'") : List.<Map<String, Object>>of();
            var table = query(connection, "SELECT ENGINE,TABLE_COLLATION FROM information_schema.TABLES"
                    + " WHERE TABLE_SCHEMA=? AND TABLE_NAME='bty_task_assignment_slot'", schema);
            if (triggers.isEmpty() && checks.isEmpty() && markers.isEmpty() && table.isEmpty()) {
                return Installation.ABSENT;
            }
            require(table.size() == 1 && "InnoDB".equals(text(table.getFirst(), "engine"))
                    && "utf8mb4_bin".equals(text(table.getFirst(), "table_collation")));
            require(triggers.size() == 2 && checks.size() == 1 && markers.size() == 1);
            JsonNode descriptor = descriptor();
            var marker = markers.getFirst();
            require(SCRIPT.equals(text(marker, "script")) && "SQL".equals(text(marker, "type"))
                    && (Boolean.TRUE.equals(marker.get("success"))
                            || marker.get("success") instanceof Number successNumber && successNumber.intValue() == 1)
                    && marker.get("checksum") instanceof Number checksumNumber
                            && checksumNumber.intValue() == descriptor.path("flywayChecksum").asInt());
            require("YES".equals(text(checks.getFirst(), "enforced"))
                    && ServiceExecutionSchemaVerifier.expression(descriptor.path("check").asString())
                            .equals(ServiceExecutionSchemaVerifier.expression(text(checks.getFirst(),
                                    "check_clause"))));
            for (var expected : descriptor.path("triggers")) {
                var actual = triggers.stream().filter(t -> expected.path("name").asString().equals(text(t,
                        "trigger_name")))
                        .findFirst().orElseThrow();
                require(expected.path("table").asString().equals(text(actual, "event_object_table"))
                        && expected.path("event").asString().equals(text(actual, "event_manipulation"))
                        && "BEFORE".equals(text(actual, "action_timing"))
                        && ServiceExecutionSchemaVerifier.tokens(expected.path("body").asString())
                                .equals(ServiceExecutionSchemaVerifier.tokens(text(actual, "action_statement"))));
            }
            validateHistory(connection, schema, descriptor);
            validateSlots(connection, schema, descriptor.path("slotTable"));
            return Installation.INSTALLED;
        } catch (Exception damaged) {
            return Installation.CORRUPT;
        } finally {
            if (connection != null) {
                DataSourceUtils.releaseConnection(connection, source);
            }
        }
    }
    public void requireAvailable() {
        var state = installation();
        if (state != Installation.INSTALLED) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE,
                    state == Installation.ABSENT ? "TASK_ACCEPT_SCHEMA_UNAVAILABLE" : "TASK_ACCEPT_SCHEMA_CORRUPT");
        }
    }
    private void validateHistory(Connection connection, String schema, JsonNode descriptor) throws Exception {
        var rows = query(connection, "SELECT version,script,type,checksum,success FROM `" + schema
                + "`.bty_flyway_history WHERE type<>'BASELINE'");
        var successor=TaskStartSchemaExtension.inspect(source);
        require(successor.installation()!=TaskStartSchemaExtension.Installation.CORRUPT);
        require((successor.installation() == TaskStartSchemaExtension.Installation.INSTALLED || rows.size() == 14)
                && descriptor.path("history").size() == 14);
        for (int version = 1; version <= 14; version++) {
            String number = Integer.toString(version);
            var expected = descriptor.path("history").get(version - 1);
            var matches = rows.stream().filter(row -> number.equals(text(row, "version"))).toList();
            require(matches.size() == 1);
            var row = matches.getFirst();
            require("SQL".equals(text(row, "type")) && expected.path("script").asString().equals(text(row, "script"))
                    && row.get("checksum") instanceof Number checksum
                    && checksum.intValue() == expected.path("checksum").asInt()
                    && (Boolean.TRUE.equals(row.get("success"))
                            || row.get("success") instanceof Number success && success.intValue() == 1));
            var files = Collections.list(getClass().getClassLoader().getResources(
                    "db/industry/beauty/" + expected.path("script").asString()));
            require(files.size() == 1);
            try (var input = files.getFirst().openStream()) {
                var crc = new CRC32();
                crc.update(new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "")
                        .replace("\r", "").replace("\n", "").getBytes(StandardCharsets.UTF_8));
                require((int) crc.getValue() == expected.path("checksum").asInt());
            }
        }
    }

    private static void validateSlots(Connection connection, String schema, JsonNode table) throws SQLException {
        var columns = query(connection, "SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLLATION_NAME,COLUMN_DEFAULT,EXTRA"
                + " FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME='bty_task_assignment_slot'"
                + " ORDER BY ORDINAL_POSITION", schema);
        require(columns.size() == table.path("columns").size());
        for (int index = 0; index < columns.size(); index++) {
            var actual = columns.get(index);
            var expected = table.path("columns").get(index);
            String definition = expected.path("definition").asString();
            String type = definition.split(" ")[0].toLowerCase(Locale.ROOT);
            String collation = type.startsWith("varchar") ? "utf8mb4_bin"
                    : type.startsWith("char") ? "ascii_bin" : "";
            String defaultValue = definition.contains("DEFAULT 'MAIN'") ? "MAIN"
                    : definition.contains("DEFAULT 0") ? "0" : "";
            require(expected.path("name").asString().equals(text(actual, "column_name"))
                    && type.equals(text(actual, "column_type").toLowerCase(Locale.ROOT))
                    && (definition.contains("NOT NULL") ? "NO" : "YES").equals(text(actual, "is_nullable"))
                    && collation.equals(text(actual, "collation_name"))
                    && defaultValue.equals(text(actual, "column_default"))
                    && definition.contains("AUTO_INCREMENT") == text(actual, "extra").contains("auto_increment"));
        }
        var indexes = query(connection, "SELECT INDEX_NAME,NON_UNIQUE,COLUMN_NAME,SEQ_IN_INDEX,INDEX_TYPE,IS_VISIBLE,"
                + "SUB_PART,COLLATION FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=?"
                + " AND TABLE_NAME='bty_task_assignment_slot' ORDER BY INDEX_NAME,SEQ_IN_INDEX", schema);
        Map<String, List<String>> actualIndexes = new LinkedHashMap<>();
        for (var row : indexes) {
            require(row.get("non_unique") instanceof Number unique && unique.intValue() == 0
                    && "BTREE".equals(text(row, "index_type")) && "YES".equals(text(row, "is_visible"))
                    && row.get("sub_part") == null && "A".equals(text(row, "collation")));
            actualIndexes.computeIfAbsent(text(row, "index_name"), ignored -> new ArrayList<>())
                    .add(text(row, "column_name"));
        }
        Map<String, List<String>> expectedIndexes = new LinkedHashMap<>();
        table.path("indexes").properties().forEach(entry -> {
            List<String> names = new ArrayList<>();
            entry.getValue().forEach(value -> names.add(value.asString()));
            expectedIndexes.put(entry.getKey(), names);
        });
        require(actualIndexes.equals(expectedIndexes));
        var checks = query(connection, "SELECT tc.CONSTRAINT_NAME,cc.CHECK_CLAUSE,tc.ENFORCED"
                + " FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc"
                + " ON tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME"
                + " WHERE tc.TABLE_SCHEMA=? AND tc.TABLE_NAME='bty_task_assignment_slot'"
                + " AND tc.CONSTRAINT_TYPE='CHECK'", schema);
        require(checks.size() == table.path("checks").size());
        table.path("checks").properties().forEach(entry -> {
            var matches = checks.stream().filter(row -> entry.getKey().equals(text(row, "constraint_name"))).toList();
            require(matches.size() == 1 && "YES".equals(text(matches.getFirst(), "enforced"))
                    && ServiceExecutionSchemaVerifier.expression(entry.getValue().asString()).equals(
                            ServiceExecutionSchemaVerifier.expression(text(matches.getFirst(), "check_clause"))));
        });
    }

    private JsonNode descriptor() throws Exception {
        var loader = getClass().getClassLoader();
        var resources = Collections.list(loader.getResources(DESCRIPTOR));
        var migrations = Collections.list(loader.getResources("db/industry/beauty/" + SCRIPT));
        require(resources.size() == 1 && migrations.size() == 1);
        JsonNode descriptor;
        try (InputStream input = resources.getFirst().openStream()) {
            descriptor = JsonMapper.builder().build().readTree(input);
        }
        require(descriptor.path("schemaVersion").asInt() == 1 && descriptor.path("platformVersion").asInt() == 14
                && "beauty-core/primary".equals(descriptor.path("owner").asString())
                && "bty_service_submission".equals(descriptor.path("table").asString())
                && SCRIPT.equals(descriptor.path("migrationScript").asString())
                && "ck_bty_task_accept".equals(descriptor.path("checkName").asString())
                && descriptor.path("triggers").size() == 2);
        try (InputStream input = migrations.getFirst().openStream()) {
            byte[] sql = input.readAllBytes();
            require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql))
                    .equals(descriptor.path("migrationSha256").asString()));
            var crc = new CRC32();
            crc.update(new String(sql, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "")
                    .replace("\r", "").replace("\n", "").getBytes(StandardCharsets.UTF_8));
            require((int) crc.getValue() == descriptor.path("flywayChecksum").asInt());
        }
        return descriptor;
    }
    private static List<Map<String, Object>> query(Connection connection, String sql, Object... values)
            throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (var result = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= result.getMetaData().getColumnCount(); index++) {
                        row.put(result.getMetaData().getColumnLabel(index).toLowerCase(Locale.ROOT),
                                result.getObject(index));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value.toString();
    }
    private static void require(boolean value) {
        if (!value) {
            throw new IllegalStateException("TASK_ACCEPT_SCHEMA_CORRUPT");
        }
    }
}
