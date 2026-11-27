package ai.neargo.shop.platform.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.RequestMetaContext;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 统一审计在业务提交后写（TDD-基础能力库对齐 §2.2 1i）：业务回滚不留审计，审计写失败不连累已提交的业务，
 * 换了线程也记得是谁、从哪来。
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditLogAfterCommitTest {

    @Autowired
    private AuditLogPort audit;

    private final TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    AuditLogAfterCommitTest(PlatformTransactionManager transactions) {
        this.tx = new TransactionTemplate(transactions);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        RequestMetaContext.clear();
    }

    @Test
    @DisplayName("业务回滚：审计不落库")
    void rolledBackBusinessLeavesNoAudit() {
        String target = target();
        tx.executeWithoutResult(status -> {
            audit.record("AFTER_COMMIT_TEST", target, "rolled back");
            assertThat(count(target)).as("提交前还看不到审计").isZero();
            status.setRollbackOnly();
        });
        assertThat(count(target)).isZero();
    }

    @Test
    @DisplayName("业务提交：审计在提交后落库，并记下调用线程上的操作人与 IP")
    void committedBusinessWritesAuditWithCallerIdentity() {
        String target = target();
        var user = LoginUser.operator("OPS-AUDIT-1", "审计员", List.of(), List.of());
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
        RequestMetaContext.set(new RequestMetaContext.Meta("10.1.2.3", "OPS", "junit"));

        tx.executeWithoutResult(status -> {
            audit.record("AFTER_COMMIT_TEST", target, "committed", true, "{\"a\":1}", "{\"a\":2}");
            assertThat(count(target)).as("在调用方事务里不写审计").isZero();
        });

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT staff_no, staff_name, ip, client_type, critical, before_json, after_json FROM sys_audit_log"
                        + " WHERE target = ?", target);
        assertThat(row.get("STAFF_NO")).isEqualTo("OPS-AUDIT-1");
        assertThat(row.get("STAFF_NAME")).isEqualTo("审计员");
        assertThat(row.get("IP")).isEqualTo("10.1.2.3");
        assertThat(row.get("CLIENT_TYPE")).isEqualTo("OPS");
        assertThat(row.get("CRITICAL").toString()).isIn("1", "true");
        assertThat(row.get("AFTER_JSON")).isEqualTo("{\"a\":2}");
    }

    @Test
    @DisplayName("审计写失败：已提交的业务不受影响，调用方也不收到异常")
    void failedAuditDoesNotHurtCommittedBusiness() {
        String target = target();
        String settingKey = "audit.test." + target.substring(0, 8);
        String tooLongAction = "X".repeat(200);
        assertThatCode(() -> tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO sys_setting(tenant_no, setting_key, setting_value, created_at, updated_at,"
                    + " version, deleted) VALUES('MAIN', ?, '1', NOW(), NOW(), 1, 0)", settingKey);
            audit.record(tooLongAction, target, "fails on insert");
        })).doesNotThrowAnyException();
        try {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_setting WHERE setting_key = ?", Integer.class,
                    settingKey)).isEqualTo(1);
            assertThat(count(target)).isZero();
        } finally {
            jdbc.update("DELETE FROM sys_setting WHERE setting_key = ?", settingKey);
        }
    }

    @Test
    @DisplayName("不在事务里：立即写")
    void withoutTransactionWritesRightAway() {
        String target = target();
        audit.record("AFTER_COMMIT_TEST", target, "no transaction");
        assertThat(count(target)).isEqualTo(1);
    }

    private int count(String target) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_log WHERE target = ?", Integer.class, target);
    }

    private static String target() {
        return UUID.randomUUID().toString();
    }
}
