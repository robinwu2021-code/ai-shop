package ai.neargo.shop.infra;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.assertThat;

/** Multiple Spring test contexts must preserve shared seeds without key collisions. */
class SchemaSeedReplayTest {
    @Test void full_schema_and_extra_seeds_can_be_replayed() {
        // H2 2.4 binds IN-check comparators to the connection that created the constraint.
        // Closing that connection between replays makes the second INSERT report database-closed,
        // even while another connection keeps the database alive. Replay both complete scripts
        // on the same physical session, retaining auto-commit and all constraint/seed assertions.
        try(var ds=new SingleConnectionDataSource("jdbc:h2:mem:seed-replay;MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","",true)) {
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
}
