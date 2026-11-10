package ai.neargo.shop.beauty.policy;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.CRC32;

import javax.sql.DataSource;

/** Forward extension only. Original V1-V15 bytes and guards remain frozen. */
public final class TaskWorkflowSchemaVerifier {
    private TaskWorkflowSchemaVerifier() {}

    public static boolean installed(DataSource source) {
        var jdbc = new JdbcTemplate(source);
        String catalog = jdbc.queryForObject("SELECT DATABASE()", String.class);
        Integer markers =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND"
                                + " TABLE_NAME='bty_flyway_history'",
                        Integer.class,
                        catalog);
        if (markers == null || markers == 0) return false;
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM bty_flyway_history WHERE version='16'",
                        Integer.class);
        if (count == null || count == 0) {
            Integer partial =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=?"
                                    + " AND TABLE_NAME='bty_service_submission' AND"
                                    + " COLUMN_NAME='workflow_observation_json'",
                            Integer.class,
                            catalog);
            Integer guards =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE"
                                + " TRIGGER_SCHEMA=? AND TRIGGER_NAME IN"
                                + " ('trg_bty_task_flow_immutable','trg_bty_task_flow_no_delete')",
                            Integer.class,
                            catalog);
            Integer checks =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE"
                                    + " TABLE_SCHEMA=? AND TABLE_NAME='bty_service_submission' AND"
                                    + " CONSTRAINT_NAME='ck_bty_task_flow'",
                            Integer.class,
                            catalog);
            require(
                    partial != null
                            && partial == 0
                            && guards != null
                            && guards == 0
                            && checks != null
                            && checks == 0);
            return false;
        }
        try {
            var loader = TaskWorkflowSchemaVerifier.class.getClassLoader();
            var descriptor =
                    JsonMapper.builder()
                            .build()
                            .readTree(
                                    loader.getResourceAsStream(
                                            "META-INF/shop/beauty-task-workflow-schema.json"));
            byte[] bytes =
                    loader.getResourceAsStream(
                                    "db/industry/beauty/" + descriptor.path("script").asString())
                            .readAllBytes();
            var crc = new CRC32();
            new String(bytes, StandardCharsets.UTF_8)
                    .lines()
                    .forEach(line -> crc.update(line.getBytes(StandardCharsets.UTF_8)));
            require(
                    descriptor
                                    .path("sha256")
                                    .asString()
                                    .equals(
                                            HexFormat.of()
                                                    .formatHex(
                                                            MessageDigest.getInstance("SHA-256")
                                                                    .digest(bytes)))
                            && descriptor.path("checksum").asInt() == (int) crc.getValue());
            var marker =
                    jdbc.queryForMap(
                            "SELECT script,checksum,success FROM bty_flyway_history WHERE"
                                    + " version='16'");
            require(
                    descriptor.path("script").asString().equals(marker.get("script"))
                            && ((Number) marker.get("checksum")).intValue()
                                    == descriptor.path("checksum").asInt()
                            && (Boolean.TRUE.equals(marker.get("success"))
                                    || marker.get("success") instanceof Number n
                                            && n.intValue() == 1));
            var column =
                    jdbc.queryForMap(
                            "SELECT DATA_TYPE,IS_NULLABLE FROM information_schema.COLUMNS WHERE"
                                    + " TABLE_SCHEMA=? AND TABLE_NAME='bty_service_submission' AND"
                                    + " COLUMN_NAME='workflow_observation_json'",
                            catalog);
            require(
                    "json".equalsIgnoreCase(column.get("DATA_TYPE").toString())
                            && "YES".equals(column.get("IS_NULLABLE")));
            var trigger =
                    jdbc.queryForMap(
                            "SELECT ACTION_TIMING,EVENT_MANIPULATION,ACTION_STATEMENT FROM"
                                    + " information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? AND"
                                    + " TRIGGER_NAME='trg_bty_task_flow_immutable'",
                            catalog);
            String sql = new String(bytes, StandardCharsets.UTF_8);
            String expected = sql.substring(sql.indexOf("BEGIN"), sql.indexOf("END$$") + 3);
            require(
                    "BEFORE".equals(trigger.get("ACTION_TIMING"))
                            && "UPDATE".equals(trigger.get("EVENT_MANIPULATION"))
                            && ServiceExecutionSchemaVerifier.tokens(expected)
                                    .equals(
                                            ServiceExecutionSchemaVerifier.tokens(
                                                    trigger.get("ACTION_STATEMENT").toString())));
            var check =
                    jdbc.queryForMap(
                            "SELECT cc.CHECK_CLAUSE,tc.ENFORCED FROM"
                                + " information_schema.TABLE_CONSTRAINTS tc JOIN"
                                + " information_schema.CHECK_CONSTRAINTS cc ON"
                                + " tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA AND"
                                + " tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME WHERE tc.TABLE_SCHEMA=?"
                                + " AND tc.TABLE_NAME='bty_service_submission' AND"
                                + " tc.CONSTRAINT_NAME='ck_bty_task_flow'",
                            catalog);
            var deletion =
                    jdbc.queryForMap(
                            "SELECT ACTION_TIMING,EVENT_MANIPULATION,ACTION_STATEMENT FROM"
                                    + " information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? AND"
                                    + " TRIGGER_NAME='trg_bty_task_flow_no_delete'",
                            catalog);
            String deleteSql =
                    sql.substring(sql.indexOf("CREATE TRIGGER trg_bty_task_flow_no_delete"));
            require(
                    "BEFORE".equals(deletion.get("ACTION_TIMING"))
                            && "DELETE".equals(deletion.get("EVENT_MANIPULATION"))
                            && ServiceExecutionSchemaVerifier.tokens(
                                            deleteSql.substring(
                                                    deleteSql.indexOf("BEGIN"),
                                                    deleteSql.indexOf("END$$") + 3))
                                    .equals(
                                            ServiceExecutionSchemaVerifier.tokens(
                                                    deletion.get("ACTION_STATEMENT").toString())));
            String expression = sql.substring(sql.indexOf("CHECK (") + 6, sql.indexOf(";\n"));
            require(
                    "YES".equals(check.get("ENFORCED"))
                            && ServiceExecutionSchemaVerifier.expression(expression)
                                    .equals(
                                            ServiceExecutionSchemaVerifier.expression(
                                                    check.get("CHECK_CLAUSE").toString())));
            return true;
        } catch (Exception corrupt) {
            throw new IllegalStateException("Actual workflow schema does not match V16", corrupt);
        }
    }

    private static void require(boolean condition) {
        if (!condition)
            throw new IllegalStateException("Actual workflow schema does not match V16");
    }
}
