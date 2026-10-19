package ai.neargo.shop.event.outbox;

import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxTestFixture {
    JdbcOutboxStore store;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    Instant now = Instant.parse("2026-10-19T00:00:00Z");

    @BeforeEach
    void setup() {
        String url = System.getenv("OUTBOX_TEST_JDBC_URL");
        var ds = url == null
                ? new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")
                : new DriverManagerDataSource(url,"codex_outbox_test",System.getenv("OUTBOX_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        if (url != null) {
            if (!url.contains("/codex_outbox_test_")) { throw new IllegalArgumentException("Dedicated test DB required"); }
            for (String table : new String[]{"sys_outbox_delivery","sys_outbox_stream","sys_outbox_operation",
                    "sys_outbox","sys_event_consumed","business_effect"}) {
                jdbc.execute("DROP TABLE IF EXISTS " + table);
            }
        }
        jdbc.execute("CREATE TABLE sys_outbox (id BIGINT AUTO_INCREMENT PRIMARY KEY,event_no VARCHAR(64) UNIQUE,"
                + "aggregate_type VARCHAR(32),aggregate_id VARCHAR(64),event_type VARCHAR(64),payload TEXT,"
                + "status VARCHAR(16),retry_count INT DEFAULT 0,next_retry_at TIMESTAMP,last_error VARCHAR(512),"
                + "created_at TIMESTAMP,sent_at TIMESTAMP)");
        new ResourceDatabasePopulator(new FileSystemResource(
                "../shop-app/src/main/resources/db/migration/V471__outbox_delivery.sql")).execute(ds);
        var manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        store = new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, ds, manager, new ObjectMapper());
    }
    OutboxEnvelope event(String id, String payload) {
        return new OutboxEnvelope(id,"PLATFORM",id,"ORDER","O1","ORDER_PAID",1,"ORDER:O1",0,payload,now);
    }
    void append(String id) {
        tx.executeWithoutResult(s -> store.append(event(id,"{}"),Map.of("a",false,"b",false),false));
    }
}
