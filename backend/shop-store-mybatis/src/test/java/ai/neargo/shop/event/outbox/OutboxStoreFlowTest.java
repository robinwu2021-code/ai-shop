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

class OutboxStoreFlowTest extends OutboxTestFixture {
    @Test
    void rollbackAndWrongTransactionCannotLeaveDurableEvents() {
        assertThatThrownBy(() -> store.append(event("E0","{}"),Map.of("a",false),false))
                .isInstanceOf(IllegalStateException.class);
        tx.executeWithoutResult(s -> { append("E1"); s.setRollbackOnly(); });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox_stream",Long.class)).isZero();
    }
    @Test
    void failureBlocksOnlySameHandlerAndDueTimeIsEnforced() {
        append("E1"); append("E2");
        var refs=store.findDue(now,100);
        assertThat(refs).hasSize(2);
        var first=store.tryClaim(refs.getFirst(),"token",now,Duration.ofSeconds(120)).orElseThrow();
        assertThat(store.tryClaim(refs.getFirst(),"other",now,Duration.ofSeconds(120))).isEmpty();
        assertThat(store.complete(first,DeliveryResult.retry("TEMP","temporary"),now,RetryPolicy.defaults())).isTrue();
        assertThat(store.tryClaim(refs.getFirst(),"early",now.plusSeconds(9),Duration.ofSeconds(120))).isEmpty();
        assertThat(store.findDue(now,100)).hasSize(1);
        assertThat(store.findDue(now.plusSeconds(10),100)).hasSize(2);
    }
    @Test
    void staleTokenCannotAcknowledgeRecoveredClaim() {
        append("E1");
        var ref=store.findDue(now,1).getFirst();
        var old=store.tryClaim(ref,"old",now,Duration.ofSeconds(1)).orElseThrow();
        store.recoverExpired(now.plusSeconds(2),100);
        var next=store.tryClaim(ref,"new",now.plusSeconds(2),Duration.ofSeconds(120)).orElseThrow();
        assertThat(store.complete(old,DeliveryResult.sent(),now.plusSeconds(2),RetryPolicy.defaults())).isFalse();
        assertThat(store.complete(next,DeliveryResult.sent(),now.plusSeconds(2),RetryPolicy.defaults())).isTrue();
    }
    @Test
    void ingressIsIdempotentAndRejectsConflictingSourcePayload() {
        var e=event("E1","{\"a\":1,\"b\":2}");
        var first=store.accept(e,Map.of("a",false));
        assertThat(store.accept(event("E1","{\"b\":2,\"a\":1}"),Map.of("a",false)).eventNo())
                .isEqualTo(first.eventNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox",Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> store.accept(event("E1","{\"a\":9}"),Map.of("a",false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SOURCE_EVENT_CONFLICT");
    }

    @Test
    void unknownExpiredExternalAttemptIsNotAutomaticallyRepeated() {
        tx.executeWithoutResult(s -> store.append(event("E1","{}"),Map.of("external",true),false));
        var ref=store.findDue(now,1).getFirst();
        store.tryClaim(ref,"lost",now,Duration.ofSeconds(1)).orElseThrow();
        store.recoverExpired(now.plusSeconds(2),100);
        assertThat(store.findDue(now.plusSeconds(2),100)).isEmpty();
        var row=jdbc.queryForMap("SELECT status,last_error_code FROM sys_outbox_delivery WHERE id=?",ref.id());
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("last_error_code")).isEqualTo("OUTCOME_UNKNOWN");
    }
    @Test
    void failedDeliveryStopsAfterTenAttempts() {
        tx.executeWithoutResult(s -> store.append(event("E1","{}"),Map.of("a",false),false));
        for (int i=0;i<10;i++) {
            var time=now.plusSeconds(86400L*i);
            var ref=store.findDue(time,1).getFirst();
            var claim=store.tryClaim(ref,"attempt-"+i,time,Duration.ofSeconds(120)).orElseThrow();
            store.complete(claim,DeliveryResult.retry("TEMP","retry"),time,RetryPolicy.defaults());
        }
        assertThat(store.findDue(now.plusSeconds(864000),100)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM sys_outbox WHERE event_no='E1'",String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM sys_outbox_delivery",Integer.class)).isEqualTo(10);
    }
    @Test
    void readBoundAutocommitConnectionIsNotSourceTransaction() {
        var other = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
        var otherTx = new TransactionTemplate(new DataSourceTransactionManager(other));
        otherTx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT 1", Integer.class);
            assertThatThrownBy(() -> store.append(event("WRONG", "{}"), Map.of("a", false), false))
                    .isInstanceOf(IllegalStateException.class);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox", Long.class)).isZero();
    }
}
