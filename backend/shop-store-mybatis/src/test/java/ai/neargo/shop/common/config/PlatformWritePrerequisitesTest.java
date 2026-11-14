package ai.neargo.shop.common.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class PlatformWritePrerequisitesTest {
    private DataSource source;
    private JdbcTemplate sql;

    @BeforeEach
    void schema() {
        // Keep the H2 fixture alive across connections.
        source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("platform-write-fixture.sql")).execute(source);
        sql = new JdbcTemplate(source);
    }

    @SuppressWarnings("unchecked")
    private List<String> missing() {
        var type = assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.common.config.PlatformWritePrerequisites"));
        return assertDoesNotThrow(() -> (List<String>) type.getMethod("missing", DataSource.class)
                .invoke(type.getConstructor().newInstance(), source));
    }

    @Test
    void acceptsActualSharedWriteSchema() {
        var missing = missing();
        assertTrue(missing.isEmpty(), missing.toString());
    }

    @Test
    void rejectsMissingRequestHashAndLookalikeTables() {
        sql.execute("ALTER TABLE sys_idempotent DROP COLUMN request_hash");
        sql.execute("CREATE TABLE sysXidempotent (request_hash VARCHAR(64))");
        assertTrue(missing().contains("sys_idempotent.request_hash"));
    }

    @Test
    void rejectsMissingSourceUniqueness() {
        sql.execute("DROP INDEX uk_sys_outbox_source");
        assertFalse(missing().isEmpty());
        assertTrue(missing().stream().anyMatch(value -> value.contains("source_system,source_event_no")));
    }
}
