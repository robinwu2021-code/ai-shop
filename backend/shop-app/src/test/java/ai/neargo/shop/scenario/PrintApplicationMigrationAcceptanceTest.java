package ai.neargo.shop.scenario;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit, destructive ONLY to its own random schemas on a disposable loopback MySQL. */
@EnabledIfEnvironmentVariable(named = "PRINT_DB_ACCEPTANCE", matches = "1")
class PrintApplicationMigrationAcceptanceTest {
    @Test void freshApplicationHistory() throws Exception { rehearse(false); }
    @Test void upgradeFullApplicationFrom587() throws Exception { rehearse(true); }

    private void rehearse(boolean upgrade) throws Exception {
        String base = System.getenv("PRINT_DB_ACCEPTANCE_URL");
        assertNotNull(base);
        // No remote hosts, existing schema names, parameters, passwords, or arbitrary SQL identifiers.
        assertTrue(base.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"), "Only disposable loopback root URL allowed");
        String schema = "print_full_accept_" + UUID.randomUUID().toString().replace("-", "");
        String url = base + schema + "?allowPublicKeyRetrieval=true&useSSL=false";
        var result = new LinkedHashMap<String,Object>();
        result.put("scenario", upgrade ? "FULL_APPLICATION_587_TO_LATEST" : "FULL_APPLICATION_FRESH");
        result.put("mode", "RAW_UNMODIFIED_APPLICATION_FLYWAY_HISTORY");
        result.put("status", "FAILED");
        try (Connection root = DriverManager.getConnection(base, "root", "")) {
            try (var statement = root.createStatement()) { statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4"); }
            String stage = upgrade ? "BUILD_FULL_587_BASELINE" : "FRESH_MIGRATE";
            try {
                if (upgrade) {
                    flyway(url, "587").migrate();
                    result.put("baselineVersion", flyway(url, null).info().current().getVersion().toString());
                    seedLegacy(url);
                    result.put("before", snapshot(url));
                    stage = "UPGRADE_588_AND_589";
                }
                Flyway latest = flyway(url, null);
                latest.migrate(); latest.validate();
                assertEquals("589", latest.info().current().getVersion().toString());
                assertEquals(0, latest.info().pending().length);
                try (var connection = DriverManager.getConnection(url, "root", ""); var sql = connection.createStatement();
                     var rows = sql.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")) {
                    assertTrue(rows.next()); int tables = rows.getInt(1); assertTrue(tables > 100); result.put("tableCount", tables);
                }
                if (upgrade) assertEquals(result.get("before"), snapshot(url), "Frozen jobs, leases, gate or custom role changed");
                assertEquals(0, latest.migrate().migrationsExecuted, "Second run must be a no-op");
                result.put("version", "589"); result.put("status", "PASS");
            } catch (Throwable failure) {
                result.put("failedStage", stage);
                Throwable cause = failure; while (cause.getCause() != null) cause = cause.getCause();
                result.put("errorType", cause.getClass().getSimpleName());
                result.put("error", cause.getMessage() == null ? "" : cause.getMessage());
                throw failure;
            } finally {
                Path output = Path.of(System.getProperty("java.io.tmpdir"), "print-release-20261026"); Files.createDirectories(output);
                Files.writeString(output.resolve(upgrade ? "full-upgrade.json" : "full-fresh.json"),
                        new ObjectMapper().writeValueAsString(result));
                try (var statement = root.createStatement()) { statement.execute("DROP DATABASE `" + schema + "`"); }
            }
        }
    }
    private Flyway flyway(String url, String target) {
        var config = Flyway.configure().dataSource(url, "root", "")
                .locations("filesystem:src/main/resources/db/migration").baselineOnMigrate(true).baselineVersion("0")
                .validateOnMigrate(true).cleanDisabled(true);
        if (target != null) config.target(target);
        return config.load();
    }
    private void seedLegacy(String url) throws Exception {
        // Focused rows are inserted only AFTER Flyway built a complete real 587 application schema.
        try (var connection = DriverManager.getConnection(url,"root",""); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO mch_role(entity_no,role_code,name,builtin,perms,created_at,updated_at) VALUES('PRINT_ACCEPT','PRINT_CUSTOM','custom role',0,'[\"custom:owned\"]',now(),now())");
            sql.executeUpdate("INSERT INTO prn_device(tenant_no,entity_no,store_no,device_no,vendor,connection_type,credential_ref,bridge_actor_no,status,capabilities,active_job_no,active_attempt_no,created_at,updated_at,authorization_version) VALUES('MAIN','PRINT_ACCEPT','S1','PRINT_ACCEPT_D1','TEST','LOCAL','C1','BRIDGE','ACTIVE','{}','PRINT_ACCEPT_J1','PRINT_ACCEPT_A1',now(),now(),7)");
            int i=0;
            for (String status : new String[]{"UNKNOWN","SENT","SUCCESS"}) {
                i++; String j="PRINT_ACCEPT_J"+i, a="PRINT_ACCEPT_A"+i;
                sql.executeUpdate("INSERT INTO prn_job(tenant_no,entity_no,store_no,job_no,source_event_no,scene,device_no,part_no,copy_index,template_no,document_snapshot,rendered_content,status,dedup_key,request_hash,event_root_key,requested_by,lease_until,current_attempt_no,lease_token,attempt_count,created_at,updated_at,version) VALUES('MAIN','PRINT_ACCEPT','S1','"+j+"','EV"+i+"','ORDER','PRINT_ACCEPT_D1','P1',1,'T1','{\"frozen\":true}','Frozen original receipt','"+status+"',SHA2('D"+i+"',256),SHA2('R"+i+"',256),SHA2('E"+i+"',256),'STAFF','2099-01-01','"+a+"','opaque-token-"+i+"',1,now(),now(),"+i+")");
                sql.executeUpdate("INSERT INTO prn_attempt(tenant_no,entity_no,store_no,attempt_no,job_no,sequence_no,status,started_at,lease_token,bridge_actor_no,created_at,updated_at) VALUES('MAIN','PRINT_ACCEPT','S1','"+a+"','"+j+"',1,'"+status+"',now(),'opaque-token-"+i+"','BRIDGE',now(),now())");
            }
        }
    }
    private Map<String,String> snapshot(String url) throws Exception {
        Map<String,String> result = new LinkedHashMap<>();
        try (var connection=DriverManager.getConnection(url,"root",""); var sql=connection.createStatement()) {
            for (String table : new String[]{"prn_job","prn_attempt","prn_device","mch_role"}) {
                try (var rows=sql.executeQuery("SELECT * FROM "+table+" WHERE entity_no='PRINT_ACCEPT' ORDER BY id")) {
                    var value=new StringBuilder(); int count=rows.getMetaData().getColumnCount();
                    while(rows.next()) for(int i=1;i<=count;i++) value.append(i).append(':').append(rows.getString(i)).append('\n');
                    result.put(table,value.toString());
                }
            }
        }
        return result;
    }
}
