package ai.neargo.shop.beauty.policy;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

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
 * SOURCE ONLY / NOT_RUN. Metadata is controlled; this is not a Native or Core business-success
 * fixture.
 */
class TaskStartSchemaVerifierTest {
    @Test
    void finiteSuccessorNeedsEveryColumnIndexCheckTriggerAndHistoryRow() throws Exception {
        assertEquals(TaskStartSchemaExtension.Installation.INSTALLED, inspect("NONE"));
        for (String damage :
                List.of("COLUMN", "INDEX", "CHECK", "TRIGGER", "HISTORY", "UNKNOWN_SUCCESSOR"))
            assertEquals(TaskStartSchemaExtension.Installation.CORRUPT, inspect(damage), damage);
    }

    @Test
    void aMarkerWithoutPhysicalSchemaIsCorruptWhileGenuineAbsenceIsAbsent() throws Exception {
        assertEquals(TaskStartSchemaExtension.Installation.ABSENT, inspect("ABSENT"));
        assertEquals(TaskStartSchemaExtension.Installation.CORRUPT, inspect("MARKER_ONLY"));
    }

    private TaskStartSchemaExtension.Installation inspect(String damage) throws Exception {
        var d = TaskStartSchemaExtension.descriptor();
        var source = mock(DataSource.class);
        var c = mock(Connection.class);
        when(source.getConnection()).thenReturn(c);
        when(c.getCatalog()).thenReturn("beauty_test");
        when(c.prepareStatement(anyString()))
                .thenAnswer(
                        invocation -> {
                            String sql = invocation.getArgument(0);
                            var statement = mock(PreparedStatement.class);
                            var rows = new ArrayList<Map<String, Object>>();
                            boolean physical = !List.of("ABSENT", "MARKER_ONLY").contains(damage);
                            if (sql.contains("information_schema.TABLES"))
                                rows.add(row("TABLE_NAME", "bty_flyway_history"));
                            else if (sql.contains("step_policy")
                                    || sql.contains("WHERE version='16'")) {
                                /* Genuine V16 absence in this V15 fixture. */
                            } else if (sql.contains("WHERE version='15'")
                                    && !"ABSENT".equals(damage))
                                rows.add(history(15, d.path("history").get(14)));
                            else if (physical && sql.contains("information_schema.COLUMNS")) {
                                rows.add(
                                        row(
                                                "COLUMN_NAME",
                                                "execution_no",
                                                "COLUMN_TYPE",
                                                "varchar(32)",
                                                "IS_NULLABLE",
                                                "YES",
                                                "COLLATION_NAME",
                                                "utf8mb4_bin",
                                                "COLUMN_DEFAULT",
                                                null,
                                                "EXTRA",
                                                ""));
                                if (!"COLUMN".equals(damage))
                                    rows.add(
                                            row(
                                                    "COLUMN_NAME",
                                                    "action_observation_json",
                                                    "COLUMN_TYPE",
                                                    "json",
                                                    "IS_NULLABLE",
                                                    "YES",
                                                    "COLLATION_NAME",
                                                    null,
                                                    "COLUMN_DEFAULT",
                                                    null,
                                                    "EXTRA",
                                                    ""));
                            } else if (physical && sql.contains("information_schema.STATISTICS")) {
                                int position = 0;
                                for (var name : d.path("index").path("columns"))
                                    rows.add(
                                            row(
                                                    "INDEX_NAME",
                                                    "uk_bty_submission_execution",
                                                    "NON_UNIQUE",
                                                    "INDEX".equals(damage) ? 1 : 0,
                                                    "COLUMN_NAME",
                                                    name.asString(),
                                                    "SEQ_IN_INDEX",
                                                    ++position,
                                                    "INDEX_TYPE",
                                                    "BTREE",
                                                    "IS_VISIBLE",
                                                    "YES",
                                                    "SUB_PART",
                                                    null,
                                                    "COLLATION",
                                                    "A"));
                            } else if (physical && sql.contains("CHECK_CONSTRAINTS")) {
                                boolean action = sql.contains("='ck_bty_task_action'");
                                for (var entry : d.path("checks").properties()) {
                                    if (action != entry.getKey().equals("ck_bty_task_action"))
                                        continue;
                                    rows.add(
                                            row(
                                                    "CONSTRAINT_NAME",
                                                    entry.getKey(),
                                                    "CHECK_CLAUSE",
                                                    "CHECK".equals(damage)
                                                            ? "1=1"
                                                            : entry.getValue().asString(),
                                                    "ENFORCED",
                                                    "YES"));
                                }
                            } else if (physical && sql.contains("information_schema.TRIGGERS")) {
                                for (var t : d.path("triggers"))
                                    rows.add(
                                            row(
                                                    "TRIGGER_NAME",
                                                    t.path("name").asString(),
                                                    "EVENT_MANIPULATION",
                                                    t.path("event").asString(),
                                                    "EVENT_OBJECT_TABLE",
                                                    "bty_service_submission",
                                                    "ACTION_TIMING",
                                                    "BEFORE",
                                                    "ACTION_STATEMENT",
                                                    "TRIGGER".equals(damage)
                                                            ? "BEGIN END"
                                                            : t.path("body").asString()));
                            } else if (physical && sql.contains("WHERE type<>'BASELINE'")) {
                                int version = 0;
                                for (var h : d.path("history")) {
                                    var r = history(++version, h);
                                    if ("HISTORY".equals(damage) && version == 3)
                                        r.put("checksum", 123);
                                    rows.add(r);
                                }
                                if ("UNKNOWN_SUCCESSOR".equals(damage))
                                    rows.add(
                                            row(
                                                    "version",
                                                    "16",
                                                    "script",
                                                    "V16__unknown.sql",
                                                    "type",
                                                    "SQL",
                                                    "checksum",
                                                    1,
                                                    "success",
                                                    1));
                            }
                            var preparedResult = result(rows);
                            when(statement.executeQuery()).thenReturn(preparedResult);
                            return statement;
                        });
        return TaskStartSchemaExtension.inspect(source).installation();
    }

    private Map<String, Object> history(int version, JsonNode item) {
        return row(
                "version",
                Integer.toString(version),
                "script",
                item.path("script").asString(),
                "type",
                "SQL",
                "checksum",
                item.path("checksum").asInt(),
                "success",
                1);
    }

    private Map<String, Object> row(Object... fields) {
        var r = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.length; i += 2) r.put((String) fields[i], fields[i + 1]);
        return r;
    }

    private ResultSet result(List<Map<String, Object>> rows) throws Exception {
        var result = mock(ResultSet.class);
        var meta = mock(ResultSetMetaData.class);
        var position = new AtomicInteger(-1);
        var keys = rows.isEmpty() ? List.<String>of() : new ArrayList<>(rows.getFirst().keySet());
        when(result.next()).thenAnswer(i -> position.incrementAndGet() < rows.size());
        when(result.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(keys.size());
        when(meta.getColumnLabel(anyInt()))
                .thenAnswer(i -> keys.get((Integer) i.getArgument(0) - 1));
        when(result.getObject(anyInt()))
                .thenAnswer(
                        i ->
                                rows.get(position.get())
                                        .get(keys.get((Integer) i.getArgument(0) - 1)));
        return result;
    }
}
