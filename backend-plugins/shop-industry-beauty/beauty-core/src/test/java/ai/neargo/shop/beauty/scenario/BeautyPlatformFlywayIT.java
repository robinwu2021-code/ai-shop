package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.beauty.config.BeautyPersistenceAutoConfiguration;
import ai.neargo.shop.beauty.config.BeautySchemaReady;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises the actual platform Flyway autoconfiguration, not a mocked call or a second manual bean. */
class BeautyPlatformFlywayIT {
    private BeautySchemaMigrationIT fixture;
    private JdbcTemplate sql;

    @BeforeEach
    void database() throws Exception {
        fixture = new BeautySchemaMigrationIT();
        fixture.isolatedDatabase();
        sql = new JdbcTemplate(fixture.source);
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class)
                .withBean(AuditMetaObjectHandler.class)
                .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class,
                        BeautyPersistenceAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=" + fixture.source.getUrl(),
                        "spring.datasource.username=root", "spring.datasource.password=",
                        "spring.flyway.enabled=true", "spring.flyway.locations=classpath:beauty-test-platform",
                        "spring.flyway.baseline-on-migrate=true", "spring.flyway.baseline-version=0");
    }

    @Test
    void disabledDoesNotSuppressRetailFlyway() {
        runner().withPropertyValues("shop.beauty.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(Flyway.class).doesNotHaveBean(BeautySchemaReady.class);
            assertThat(context.getBean(Flyway.class).getConfiguration().getTable()).isEqualTo("flyway_schema_history");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM platform_migration_probe", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE "
                    + "table_schema=DATABASE() AND table_name='bty_flyway_history'", Integer.class)).isZero();
        });
    }

    @Test
    void enabledValidationCoexistsWithPlatformMigrations() {
        fixture.initialize("migrate");
        runner().withPropertyValues("shop.beauty.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(Flyway.class).hasSingleBean(BeautySchemaReady.class);
            assertThat(context.getBean(Flyway.class).getConfiguration().getTable()).isEqualTo("flyway_schema_history");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM platform_migration_probe", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_flyway_history WHERE version='1'", Integer.class))
                    .isEqualTo(1);
        });
    }
}
