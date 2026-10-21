package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Actual private migration history, distinct from the platform structure fixtures used by module deployments. */
class ResourceIntentMigrationIT extends BeautyExecutionFixture {
    @BeforeEach
    void database() throws Exception {
        database = new BeautySchemaMigrationIT();
        database.isolatedDatabase();
        sql = new JdbcTemplate(database.source);
    }
    @Test
    void freshMigrateInstallsV2AndValidateDoesNotRepeatIt() {
        database.initialize("migrate");
        database.initialize("validate");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_flyway_history WHERE version='2' AND success=1",
                Integer.class)).isOne();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE()", Integer.class)).isEqualTo(19);
    }
    @Test
    void v1RequiresExplicitUpgradeAndRetainsExistingAppointments() {
        Flyway.configure().dataSource(database.source).locations("classpath:db/industry/beauty")
                .table("bty_flyway_history").target("1").baselineVersion("0").baselineOnMigrate(true)
                .createSchemas(false).placeholderReplacement(false).load().migrate();
        appointment("A", "S");
        assertThatThrownBy(() -> database.initialize("validate")).isInstanceOf(RuntimeException.class);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_flyway_history WHERE version='2'", Integer.class))
                .isZero();
        database.initialize("migrate");
        database.initialize("validate");
        assertThat(sql.queryForObject("SELECT hold_no FROM bty_appointment WHERE appointment_no='A'", String.class))
                .isEqualTo("HOLD");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_flyway_history WHERE version IN ('1','2') "
                + "AND success=1", Integer.class)).isEqualTo(2);
    }
}
