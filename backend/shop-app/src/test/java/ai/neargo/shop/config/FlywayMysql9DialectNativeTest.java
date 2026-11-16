package ai.neargo.shop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Original frozen migrations, native MySQL9, and a test-owned disposable schema. */
@EnabledIfEnvironmentVariable(named = "FLYWAY_MYSQL9_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:3308/\\?.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlywayMysql9DialectNativeTest {
    private final String schema = "flyway_mysql9_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate admin;
    private DriverManagerDataSource original;
    private JdbcTemplate sql;

    @BeforeAll
    void migrateOriginalFrozenChainThroughConditionalIndexDdl() {
        String url = System.getenv("FLYWAY_MYSQL9_URL");
        String user = System.getenv("FLYWAY_MYSQL9_USER");
        String password = System.getenv("FLYWAY_MYSQL9_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        assertThat(admin.queryForObject("SELECT VERSION()", String.class)).startsWith("9.");
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        original = new DriverManagerDataSource(url.replace("/?", "/" + schema + "?"), user, password);
        var config = Flyway.configure().dataSource(original).locations("classpath:db/migration").target("18");
        new FlywayDialectConfig().migrationDialect().customize(config);
        var flyway = config.load();
        flyway.migrate();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        sql = new JdbcTemplate(original);
    }

    @Test
    void frozenBaselineAndConditionalIndexMigrationExecuteAndRepeatWithOriginalChecksums() {
        assertThat(sql.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history", Integer.class)).isEqualTo(18);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0", Integer.class)).isZero();
        var wire = new FlywayMariaDbDialectDataSource(original);
        new ResourceDatabasePopulator(new FileSystemResource("src/main/resources/db/migration/V18__store_role_multi.sql")).execute(wire);
        assertThat(sql.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema=? AND table_name='mch_store_role' AND index_name='uk_store_role' ORDER BY seq_in_index", String.class, schema))
                .containsExactly("mch_account_no", "store_no", "role");
        assertThat(sql.queryForObject("SELECT table_collation FROM information_schema.tables WHERE table_schema=? AND table_name='mch_store_role'", String.class, schema))
                .isEqualTo("utf8mb4_0900_ai_ci");
    }

    @Test
    void conditionalMultiColumnDdlPreservesExistingColumnsAndQuotedCommaValues() {
        sql.execute("CREATE TABLE dialect_repeat(id BIGINT PRIMARY KEY, existing VARCHAR(20))");
        sql.execute("INSERT INTO dialect_repeat VALUES(1,'retain,me')");
        var wire = new JdbcTemplate(new FlywayMariaDbDialectDataSource(original));
        String ddl = "ALTER TABLE dialect_repeat ADD COLUMN IF NOT EXISTS existing VARCHAR(1), "
                + "ADD COLUMN IF NOT EXISTS amount DECIMAL(12,2), ADD COLUMN IF NOT EXISTS note VARCHAR(20) COMMENT 'a,b'";
        wire.execute(ddl);
        wire.execute(ddl);
        String index = "CREATE UNIQUE INDEX IF NOT EXISTS uk_dialect_existing ON dialect_repeat(existing)";
        wire.execute(index);
        wire.execute(index);
        assertThatThrownBy(() -> sql.execute("INSERT INTO dialect_repeat(id,existing) VALUES(2,'retain,me')"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(sql.queryForObject("SELECT existing FROM dialect_repeat WHERE id=1", String.class)).isEqualTo("retain,me");
        assertThat(sql.queryForObject("SELECT character_maximum_length FROM information_schema.columns WHERE table_schema=? AND table_name='dialect_repeat' AND column_name='existing'", Integer.class, schema)).isEqualTo(20);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name='dialect_repeat'", Integer.class, schema)).isEqualTo(4);
    }

    @AfterAll
    void dropOnlyOwnedSchema() {
        if (admin != null) admin.execute("DROP DATABASE IF EXISTS " + schema);
    }
}
