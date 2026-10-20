package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import ai.neargo.shop.paybridge.EntitlementExpiryReminderService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:expiry-reminder;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class EntitlementExpiryReminderFlowTest {
    @Autowired EntitlementExpiryReminderService reminders;
    @Autowired JdbcTemplate jdbc;
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private String asset(String user, String status, int remaining, int hours) {
        String no = id();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO ent_instance(tenant_no,instance_no,template_version_no,grant_no,unit_index,"
                + "source_unit_key,user_no,entity_no,kind,status,total_units,remaining_units,reserved_units,"
                + "effective_from,effective_until,rule_snapshot,asset_seq,created_at,updated_at,created_by,updated_by) "
                + "VALUES('MAIN',?, 'V1',?,1,?,?,'M1','PAID_CARD',?,30,?,0,?,?,'{}',1,?,?,'TEST','TEST')",
                no, id(), id() + id(), user, status, remaining, LocalDateTime.ofInstant(now.minusSeconds(3600), ZoneOffset.UTC),
                LocalDateTime.ofInstant(now.plusSeconds(hours * 3600L), ZoneOffset.UTC), LocalDateTime.ofInstant(now, ZoneOffset.UTC), LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        return no;
    }
    @Test void oneReminderPerActualWindowSurvivesRescanAndSkipsIneligibleAssetsWithoutFinancialWrites() {
        String user = id();
        String first = asset(user, "ACTIVE", 30, 2), second = asset(user, "ACTIVE", 3, 20);
        jdbc.update("UPDATE ent_instance SET total_units=NULL,remaining_units=NULL WHERE instance_no=?", second);
        asset(user, "FROZEN", 30, 2); asset(user, "EXPIRED", 30, -1);
        asset(user, "ACTIVE", 0, 2); asset(user, "ACTIVE", 30, 30);
        asset(user, "PENDING_ACTIVATION", 30, 2);
        var page = reminders.run(0, 1);
        assertThat(page.scanned()).isEqualTo(1);
        assertThat(page.nextId()).isPositive();
        assertThat(reminders.run(page.nextId(), 1).scanned()).isEqualTo(1);
        reminders.run(0, 100); reminders.run(0, 100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE receiver_no=?", Integer.class, user))
                .isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT body FROM notify_message WHERE receiver_no=?", String.class, user))
                .allSatisfy(body -> assertThat(body).contains("到期").doesNotContain("本金", "赠金", "30"));
        // A new actual expiry window may produce another reminder; a retry of the same window cannot.
        jdbc.update("UPDATE ent_instance SET effective_until=? WHERE instance_no=?",
                LocalDateTime.ofInstant(Instant.now().plusSeconds(3600), ZoneOffset.UTC), first);
        reminders.run(0, 100); reminders.run(0, 100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE receiver_no=?", Integer.class, user))
                .isEqualTo(3);
        for (String no : List.of(first, second)) {
            assertThat(jdbc.queryForObject("SELECT asset_seq FROM ent_instance WHERE instance_no=?", Long.class, no))
                    .isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_usage", Integer.class)).isZero();
    }
}
