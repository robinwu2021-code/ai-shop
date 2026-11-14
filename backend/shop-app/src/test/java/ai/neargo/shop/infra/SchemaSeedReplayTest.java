package ai.neargo.shop.infra;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.assertThat;

/** Multiple Spring test contexts must preserve shared seeds without key collisions. */
class SchemaSeedReplayTest {
    @Test void full_schema_and_extra_seeds_can_be_replayed() {
        // Real SQL-init and pooled callers use different physical sessions. Closing
        // the creator must not leave CHECK comparators bound to that dead session.
        var ds = new DriverManagerDataSource(
            "jdbc:h2:mem:seed-replay-cross-connection;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "sa", "");
        var script=new ResourceDatabasePopulator(new ClassPathResource("schema-test.sql"),
                new ClassPathResource("schema-test-extra.sql"));
        script.execute(ds);
        var sql=new JdbcTemplate(ds);
        sql.update("UPDATE mch_admission_policy SET daily_amount_limit_minor=12345 WHERE legal_form='NATURAL_PERSON'");
        script.execute(ds);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_admission_policy WHERE legal_form='MICRO'",Long.class)).isZero();
        assertThat(sql.queryForObject("SELECT daily_amount_limit_minor FROM mch_admission_policy WHERE legal_form='NATURAL_PERSON'",Long.class)).isEqualTo(12345);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_pay_channel_market WHERE pay_channel IN ('WECHAT','ALIPAY')",Long.class)).isEqualTo(2);
    }
}
