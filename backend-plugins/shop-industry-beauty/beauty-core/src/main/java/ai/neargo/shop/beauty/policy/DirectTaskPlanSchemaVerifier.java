package ai.neargo.shop.beauty.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import org.springframework.jdbc.datasource.DataSourceUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
import java.util.Set;
import java.util.zip.CRC32;

import javax.sql.DataSource;

/**
 * Per-use V12 authority inspection distinguishes absent migration from partial or changed
 * installation.
 */
public final class DirectTaskPlanSchemaVerifier {
    private static final String SCRIPT = "V12__beauty_direct_task_plan.sql";
    private static final String DESCRIPTOR = "META-INF/shop/beauty-direct-task-plan-schema.json";
    private static final Set<String> COLUMNS =
            Set.of("direct_plan_no", "direct_plan_revision", "direct_plan_hash");
    private final DataSource source;

    public DirectTaskPlanSchemaVerifier(DataSource source) {
        this.source = Objects.requireNonNull(source);
    }

    public enum Installation {
        ABSENT,
        INSTALLED,
        CORRUPT
    }

    /**
     * No cached marker or feature flag may conceal physical drift after a previously valid request.
     */
    public Installation installation() {
        Connection connection = null;
        try {
            connection = DataSourceUtils.getConnection(source);
            String schema = connection.getCatalog();
            if (schema == null || !schema.matches("[a-zA-Z0-9_]+")) return Installation.CORRUPT;
            var columns =
                    query(
                            connection,
                            "SELECT"
                                + " COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLLATION_NAME,COLUMN_DEFAULT,EXTRA"
                                + " FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND"
                                + " TABLE_NAME='bty_service_task' AND COLUMN_NAME IN"
                                + " ('direct_plan_no','direct_plan_revision','direct_plan_hash')",
                            schema);
            var triggers =
                    query(
                            connection,
                            "SELECT"
                                + " TRIGGER_NAME,EVENT_MANIPULATION,EVENT_OBJECT_TABLE,ACTION_TIMING,ACTION_STATEMENT"
                                + " FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? AND"
                                + " TRIGGER_NAME IN"
                                + " ('trg_bty_direct_plan_immutable','trg_bty_direct_plan_no_delete',"
                                + "'trg_bty_task_direct_plan_fence')",
                            schema);
            var checks =
                    query(
                            connection,
                            "SELECT cc.CHECK_CLAUSE,tc.ENFORCED FROM"
                                + " information_schema.TABLE_CONSTRAINTS tc JOIN"
                                + " information_schema.CHECK_CONSTRAINTS cc ON"
                                + " tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA AND"
                                + " tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME WHERE tc.TABLE_SCHEMA=?"
                                + " AND tc.TABLE_NAME='bty_service_task' AND"
                                + " tc.CONSTRAINT_NAME='ck_bty_task_direct_plan' AND"
                                + " tc.CONSTRAINT_TYPE='CHECK'",
                            schema);
            boolean history =
                    !query(
                                    connection,
                                    "SELECT TABLE_NAME FROM information_schema.TABLES WHERE"
                                        + " TABLE_SCHEMA=? AND TABLE_NAME='bty_flyway_history'",
                                    schema)
                            .isEmpty();
            var markers =
                    history
                            ? query(
                                    connection,
                                    "SELECT script,type,checksum,success FROM `"
                                            + schema
                                            + "`.bty_flyway_history WHERE version='12'")
                            : List.<Map<String, Object>>of();
            if (columns.isEmpty() && triggers.isEmpty() && checks.isEmpty() && markers.isEmpty())
                return Installation.ABSENT;
            require(
                    columns.size() == 3
                            && triggers.size() == 3
                            && checks.size() == 1
                            && markers.size() == 1);
            JsonNode descriptor = descriptor();
            var marker = markers.getFirst();
            require(
                    SCRIPT.equals(text(marker, "script"))
                            && "SQL".equals(text(marker, "type"))
                            && (Boolean.TRUE.equals(marker.get("success"))
                                    || marker.get("success") instanceof Number successNumber
                                            && successNumber.intValue() == 1)
                            && marker.get("checksum") instanceof Number checksumNumber
                            && checksumNumber.intValue()
                                    == descriptor.path("flywayChecksum").asInt());
            for (var expected : descriptor.path("columns")) {
                String name = expected.path("name").asString();
                require(COLUMNS.contains(name));
                var actual =
                        columns.stream()
                                .filter(c -> name.equals(text(c, "column_name")))
                                .findFirst()
                                .orElseThrow();
                require(
                        expected.path("type").asString().equals(text(actual, "column_type"))
                                && "YES".equals(text(actual, "is_nullable"))
                                && actual.get("column_default") == null
                                && text(actual, "extra").isEmpty()
                                && Objects.equals(
                                        expected.path("collation").isNull()
                                                ? null
                                                : expected.path("collation").asString(),
                                        actual.get("collation_name")));
            }
            require(
                    "YES".equals(text(checks.getFirst(), "enforced"))
                            && ServiceExecutionSchemaVerifier.expression(
                                            descriptor.path("check").asString())
                                    .equals(
                                            ServiceExecutionSchemaVerifier.expression(
                                                    text(checks.getFirst(), "check_clause"))));
            for (var expected : descriptor.path("triggers")) {
                var actual =
                        triggers.stream()
                                .filter(
                                        t ->
                                                expected.path("name")
                                                        .asString()
                                                        .equals(text(t, "trigger_name")))
                                .findFirst()
                                .orElseThrow();
                require(
                        expected.path("table").asString().equals(text(actual, "event_object_table"))
                                && expected.path("event")
                                        .asString()
                                        .equals(text(actual, "event_manipulation"))
                                && "BEFORE".equals(text(actual, "action_timing"))
                                && ServiceExecutionSchemaVerifier.tokens(
                                                expected.path("body").asString())
                                        .equals(
                                                ServiceExecutionSchemaVerifier.tokens(
                                                        text(actual, "action_statement"))));
            }
            return Installation.INSTALLED;
        } catch (Exception damaged) {
            return Installation.CORRUPT;
        } finally {
            if (connection != null) DataSourceUtils.releaseConnection(connection, source);
        }
    }

    public void requireAvailable() {
        var state = installation();
        if (state != Installation.INSTALLED)
            throw BizException.of(
                    ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE,
                    state == Installation.ABSENT
                            ? "DIRECT_PLAN_SCHEMA_UNAVAILABLE"
                            : "DIRECT_PLAN_SCHEMA_CORRUPT");
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
        require(
                descriptor.path("schemaVersion").asInt() == 1
                        && descriptor.path("platformVersion").asInt() == 12
                        && "beauty-core/primary".equals(descriptor.path("owner").asString())
                        && "bty_service_task".equals(descriptor.path("table").asString())
                        && SCRIPT.equals(descriptor.path("migrationScript").asString())
                        && descriptor.path("columns").size() == 3
                        && descriptor.path("triggers").size() == 3);
        try (InputStream input = migrations.getFirst().openStream()) {
            byte[] sql = input.readAllBytes();
            require(
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(sql))
                            .equals(descriptor.path("migrationSha256").asString()));
            var crc = new CRC32();
            crc.update(
                    new String(sql, StandardCharsets.UTF_8)
                            .replaceFirst("^\\uFEFF", "")
                            .replace("\r", "")
                            .replace("\n", "")
                            .getBytes(StandardCharsets.UTF_8));
            require((int) crc.getValue() == descriptor.path("flywayChecksum").asInt());
        }
        return descriptor;
    }

    private static List<Map<String, Object>> query(
            Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++)
                statement.setObject(index + 1, values[index]);
            try (var result = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= result.getMetaData().getColumnCount(); index++)
                        row.put(
                                result.getMetaData().getColumnLabel(index).toLowerCase(Locale.ROOT),
                                result.getObject(index));
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
        if (!value) throw new IllegalStateException("DIRECT_PLAN_SCHEMA_CORRUPT");
    }
}
