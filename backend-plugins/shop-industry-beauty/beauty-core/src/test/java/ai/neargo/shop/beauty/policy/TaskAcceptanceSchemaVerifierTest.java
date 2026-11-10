package ai.neargo.shop.beauty.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/**
 * SOURCE ONLY / NOT_RUN. Exercises the real verifier's physical metadata/history classification.
 */
class TaskAcceptanceSchemaVerifierTest {
    @Test
    void absencePartialPhysicalDriftAndEveryHistoryEntryAreClassified() throws Exception {
        assertEquals(
                TaskAcceptanceSchemaVerifier.Installation.ABSENT,
                verifier("ABSENT").installation());
        assertEquals(
                TaskAcceptanceSchemaVerifier.Installation.INSTALLED,
                verifier("NONE").installation());
        for (String damage : List.of("TABLE", "COLUMN", "CHECK", "TRIGGER", "HISTORY", "INDEX")) {
            var verifier = verifier(damage);
            assertEquals(
                    TaskAcceptanceSchemaVerifier.Installation.CORRUPT,
                    verifier.installation(),
                    damage);
            assertThrows(BizException.class, verifier::requireAvailable);
        }
    }

    private TaskAcceptanceSchemaVerifier verifier(String damage) throws Exception {
        var descriptor =
                JsonMapper.builder()
                        .build()
                        .readTree(
                                getClass()
                                        .getResourceAsStream(
                                                "/META-INF/shop/beauty-task-acceptance-schema.json"));
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("beauty_test");
        when(connection.prepareStatement(anyString()))
                .thenAnswer(
                        invocation -> {
                            String sql = invocation.getArgument(0);
                            var statement = mock(PreparedStatement.class);
                            List<Map<String, Object>> rows = new ArrayList<>();
                            if (sql.contains("'execution_no'")
                                    || sql.contains("trg_bty_task_start")
                                    || sql.contains("ck_bty_task_start")
                                    || sql.contains("uk_bty_submission_execution")
                                    || sql.contains("WHERE version='15'")
                                    || sql.contains("step_policy")
                                    || sql.contains("WHERE version='16'")) {
                                // Exact historical V14 fixture: V15/V16 objects must be absent for
                                // every damage variant.
                            } else if ("ABSENT".equals(damage)) {
                                if (sql.contains("TABLE_NAME='bty_flyway_history'"))
                                    rows.add(row("TABLE_NAME", "bty_flyway_history"));
                            } else if (sql.contains("information_schema.TRIGGERS")) {
                                for (var expected : descriptor.path("triggers")) {
                                    if ("TRIGGER".equals(damage)) break;
                                    rows.add(
                                            row(
                                                    "TRIGGER_NAME",
                                                    expected.path("name").asString(),
                                                    "EVENT_MANIPULATION",
                                                    expected.path("event").asString(),
                                                    "EVENT_OBJECT_TABLE",
                                                    "bty_service_submission",
                                                    "ACTION_TIMING",
                                                    "BEFORE",
                                                    "ACTION_STATEMENT",
                                                    expected.path("body").asString()));
                                }
                            } else if (sql.contains("information_schema.COLUMNS")) {
                                for (var column : descriptor.path("slotTable").path("columns")) {
                                    String definition = column.path("definition").asString();
                                    String type = definition.split(" ")[0].toLowerCase();
                                    rows.add(
                                            row(
                                                    "COLUMN_NAME",
                                                    column.path("name").asString(),
                                                    "COLUMN_TYPE",
                                                    "COLUMN".equals(damage) ? "varchar(1)" : type,
                                                    "IS_NULLABLE",
                                                    definition.contains("NOT NULL") ? "NO" : "YES",
                                                    "COLLATION_NAME",
                                                    type.startsWith("varchar")
                                                            ? "utf8mb4_bin"
                                                            : type.startsWith("char")
                                                                    ? "ascii_bin"
                                                                    : null,
                                                    "COLUMN_DEFAULT",
                                                    definition.contains("DEFAULT 'MAIN'")
                                                            ? "MAIN"
                                                            : definition.contains("DEFAULT 0")
                                                                    ? "0"
                                                                    : null,
                                                    "EXTRA",
                                                    definition.contains("AUTO_INCREMENT")
                                                            ? "auto_increment"
                                                            : ""));
                                }
                            } else if (sql.contains("information_schema.STATISTICS")) {
                                descriptor
                                        .path("slotTable")
                                        .path("indexes")
                                        .properties()
                                        .forEach(
                                                entry -> {
                                                    int index = 1;
                                                    for (var column : entry.getValue()) {
                                                        rows.add(
                                                                row(
                                                                        "INDEX_NAME",
                                                                        entry.getKey(),
                                                                        "NON_UNIQUE",
                                                                        "INDEX".equals(damage)
                                                                                ? 1
                                                                                : 0,
                                                                        "COLUMN_NAME",
                                                                        column.asString(),
                                                                        "SEQ_IN_INDEX",
                                                                        index++,
                                                                        "INDEX_TYPE",
                                                                        "BTREE",
                                                                        "IS_VISIBLE",
                                                                        "YES",
                                                                        "SUB_PART",
                                                                        null,
                                                                        "COLLATION",
                                                                        "A"));
                                                    }
                                                });
                            } else if (sql.contains("CHECK_CONSTRAINTS")
                                    && sql.contains("bty_task_assignment_slot")) {
                                descriptor
                                        .path("slotTable")
                                        .path("checks")
                                        .properties()
                                        .forEach(
                                                entry ->
                                                        rows.add(
                                                                row(
                                                                        "CONSTRAINT_NAME",
                                                                        entry.getKey(),
                                                                        "CHECK_CLAUSE",
                                                                        entry.getValue().asString(),
                                                                        "ENFORCED",
                                                                        "YES")));
                            } else if (sql.contains("CHECK_CONSTRAINTS")) {
                                rows.add(
                                        row(
                                                "CHECK_CLAUSE",
                                                "CHECK".equals(damage)
                                                        ? "1=1"
                                                        : descriptor.path("check").asString(),
                                                "ENFORCED",
                                                "YES"));
                            } else if (sql.contains("information_schema.TABLES")
                                    && sql.contains("bty_task_assignment_slot")) {
                                if (!"TABLE".equals(damage))
                                    rows.add(
                                            row(
                                                    "ENGINE",
                                                    "InnoDB",
                                                    "TABLE_COLLATION",
                                                    "utf8mb4_bin"));
                            } else if (sql.contains("information_schema.TABLES")) {
                                rows.add(row("TABLE_NAME", "bty_flyway_history"));
                            } else if (sql.contains("WHERE version='14'")) {
                                rows.add(
                                        row(
                                                "script",
                                                descriptor.path("migrationScript").asString(),
                                                "type",
                                                "SQL",
                                                "checksum",
                                                descriptor.path("flywayChecksum").asInt(),
                                                "success",
                                                1));
                            } else if (sql.contains("WHERE type<>'BASELINE'")) {
                                int version = 1;
                                for (var history : descriptor.path("history")) {
                                    rows.add(
                                            row(
                                                    "version",
                                                    Integer.toString(version++),
                                                    "script",
                                                    history.path("script").asString(),
                                                    "type",
                                                    "SQL",
                                                    "checksum",
                                                    "HISTORY".equals(damage)
                                                            ? 123
                                                            : history.path("checksum").asInt(),
                                                    "success",
                                                    1));
                                }
                            }
                            var preparedResult = result(rows);
                            when(statement.executeQuery()).thenReturn(preparedResult);
                            return statement;
                        });
        return new TaskAcceptanceSchemaVerifier(source);
    }

    private Map<String, Object> row(Object... fields) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }

    private ResultSet result(List<Map<String, Object>> rows) throws Exception {
        var result = mock(ResultSet.class);
        var metadata = mock(ResultSetMetaData.class);
        var index = new AtomicInteger(-1);
        var names = rows.isEmpty() ? List.<String>of() : new ArrayList<>(rows.getFirst().keySet());
        when(result.next()).thenAnswer(ignored -> index.incrementAndGet() < rows.size());
        when(result.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(names.size());
        when(metadata.getColumnLabel(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> names.get((Integer) call.getArgument(0) - 1));
        when(result.getObject(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(
                        call ->
                                rows.get(index.get())
                                        .get(names.get((Integer) call.getArgument(0) - 1)));
        return result;
    }
}
