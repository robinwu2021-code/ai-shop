package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Executes the real V427 DDL and its native unique/CHECK behavior on a disposable MySQL only. */
@EnabledIfEnvironmentVariable(named = "ENTITLEMENT_AUTH_NATIVE_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
class EntitlementAuthorizationNativeMysqlTest {
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(System.getenv("ENTITLEMENT_AUTH_NATIVE_MYSQL_URL"));
        dataSource.setUsername("root");
        dataSource.setPassword("");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V427__entitlement_authorization_source_decision.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void close() { if (dataSource != null) dataSource.close(); }

    @Test
    void pendingIsNotAuthorizedAndSettledSuccessIsUniqueAcrossRevisions() {
        String tenant = "MAIN";
        String now = "2026-10-18 00:00:00.000";
        jdbc.update("INSERT INTO ord_ent_authorization(tenant_no,intent_no,payload_hash,source_key,source_snapshot,"
                        + "source_revision,order_no,user_no,entity_no,store_no,quote_ref,selection_digest,"
                        + "order_snapshot_hash,amount_minor,currency,status,expires_at,created_at,updated_at,created_by,"
                        + "updated_by) VALUES (?,?,?,?,CAST(? AS JSON),?,?,?,?,?,?,?,?,?,'CNY','PENDING',?,?,?,?,?)",
                tenant, "INTENT-1", "a".repeat(64), "b".repeat(64), "{}", 0, "ORDER-1", "USER-1", "ENTITY-1",
                "STORE-1", "QUOTE-1", "c".repeat(64), "d".repeat(64), 1000, "2026-10-18 00:05:00.000",
                now, now, "USER-1", "USER-1");
        assertThat(jdbc.queryForObject("SELECT status FROM ord_ent_authorization WHERE intent_no='INTENT-1'", String.class))
                .isEqualTo("PENDING");
        assertThatThrownBy(() -> jdbc.update("UPDATE ord_ent_authorization SET status='AUTHORIZED' WHERE intent_no='INTENT-1'"))
                .isInstanceOf(RuntimeException.class);

        sourceDecision("DECISION-CANCEL", "intent-v0", null, 0, "USE_CANCEL", "CANCELLED");
        sourceDecision("DECISION-COMMIT", "intent-v1", "settled-source", 1, "USE_COMMIT", "COMMITTED");
        assertThatThrownBy(() -> sourceDecision("DECISION-COMMIT-2", "intent-v2", "settled-source", 2,
                "USE_COMMIT", "COMMITTED")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_ent_source_decision WHERE settled_source_key=?",
                Integer.class, "settled-source")).isOne();
    }

    private void sourceDecision(String no, String sourceKey, String settledKey, long revision, String type,
                                String status) {
        jdbc.update("INSERT INTO ord_ent_source_decision(tenant_no,decision_no,purpose,source_key,source_snapshot,"
                        + "settled_source_key,source_revision,decision_type,status,user_no,entity_no,currency,"
                        + "amount_minor,snapshot_hash,occurred_at,created_at,updated_at,created_by,updated_by) "
                        + "VALUES ('MAIN',?,'USE',?,CAST(? AS JSON),?,?,?,?, 'USER-1','ENTITY-1','CNY',1000,?,"
                        + "'2026-10-18 00:00:00.000','2026-10-18 00:00:00.000','2026-10-18 00:00:00.000',"
                        + "'SYSTEM','SYSTEM')",
                no, sourceKey, "{}", settledKey, revision, type, status, "e".repeat(64));
    }
}
