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
class TaskActionSchemaVerifierTest {
    @Test
    void genuineAbsenceIsDistinctFromEveryPartialInstallationAndMarkerDamage() throws Exception {
        assertEquals(
                TaskActionSchemaVerifier.Installation.ABSENT,
                verifier(false, false, false, false).installation());
        assertEquals(
                TaskActionSchemaVerifier.Installation.CORRUPT,
                verifier(true, false, false, false).installation());
        assertEquals(
                TaskActionSchemaVerifier.Installation.CORRUPT,
                verifier(false, true, false, false).installation());
        assertEquals(
                TaskActionSchemaVerifier.Installation.CORRUPT,
                verifier(false, false, true, false).installation());
        assertEquals(
                TaskActionSchemaVerifier.Installation.INSTALLED,
                verifier(true, true, true, false).installation());
        var corrupt = verifier(true, true, true, true);
        assertEquals(TaskActionSchemaVerifier.Installation.CORRUPT, corrupt.installation());
        assertThrows(BizException.class, corrupt::requireAvailable);
    }

    private TaskActionSchemaVerifier verifier(
            boolean triggers, boolean check, boolean marker, boolean tampered) throws Exception {
        var descriptor =
                JsonMapper.builder()
                        .build()
                        .readTree(
                                getClass()
                                        .getResourceAsStream(
                                                "/META-INF/shop/beauty-task-action-schema.json"));
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
                                    || sql.contains("uk_bty_submission_execution")) {
                                // V15 is genuinely absent in this historical V13/V14 fixture.
                            } else if (sql.contains("information_schema.TRIGGERS") && triggers) {
                                for (var expected : descriptor.path("triggers")) {
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
                            } else if (sql.contains("CHECK_CONSTRAINTS") && check) {
                                rows.add(
                                        row(
                                                "CHECK_CLAUSE",
                                                descriptor.path("check").asString(),
                                                "ENFORCED",
                                                "YES"));
                            } else if (sql.contains("information_schema.TABLES")) {
                                rows.add(row("TABLE_NAME", "bty_flyway_history"));
                            } else if (sql.contains("WHERE version='13'") && marker) {
                                rows.add(
                                        row(
                                                "script",
                                                descriptor.path("migrationScript").asString(),
                                                "type",
                                                "SQL",
                                                "checksum",
                                                tampered
                                                        ? 123
                                                        : descriptor.path("flywayChecksum").asInt(),
                                                "success",
                                                1));
                            }
                            var preparedResult = result(rows);
                            when(statement.executeQuery()).thenReturn(preparedResult);
                            return statement;
                        });
        return new TaskActionSchemaVerifier(source);
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
