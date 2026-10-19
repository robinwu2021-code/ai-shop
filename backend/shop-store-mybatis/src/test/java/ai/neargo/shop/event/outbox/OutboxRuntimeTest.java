package ai.neargo.shop.event.outbox;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.outbox.runtime.TransactionalOutboxHandler;
import ai.neargo.shop.event.outbox.runtime.OutboxRunner;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class OutboxRuntimeTest extends OutboxTestFixture {
    @Test
    void targetFailureRollsBackBusinessAndInboxTogether() {
        jdbc.execute("CREATE TABLE sys_event_consumed (event_no VARCHAR(64),handler VARCHAR(128),"
                + "consumed_at TIMESTAMP,UNIQUE(event_no,handler))");
        jdbc.execute("CREATE TABLE business_effect (effect_count INT)");
        var consumer = new OutboxConsumer() {
            public String handlerId() { return "a"; }
            public boolean supports(String type) { return true; }
            public void consume(SysOutbox e) {
                jdbc.update("INSERT INTO business_effect VALUES (1)");
                throw new IllegalStateException("business failed");
            }
        };
        var handler=new TransactionalOutboxHandler(consumer,jdbc.getDataSource(),
                new DataSourceTransactionManager(jdbc.getDataSource()),"sys_event_consumed");
        assertThat(handler.handle(event("E1","{}")).kind()).isEqualTo(DeliveryResult.Kind.RETRYABLE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_effect",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_event_consumed",Long.class)).isZero();
    }
    @Test
    void lostNotificationIsRecoveredWithoutRerunningSuccessfulHandler() throws Exception {
        append("E1");
        var calls=new AtomicInteger();
        OutboxHandler handler=new OutboxHandler() {
            public String handlerId() { return "a"; }
            public boolean supports(String type) { return true; }
            public DeliveryResult handle(OutboxEnvelope e) { calls.incrementAndGet(); return DeliveryResult.sent(); }
        };
        var facade=new UnifiedOutboxFacade(new ObjectMapper());
        facade.register(store,List.of(handler));
        try (var runner=new OutboxRunner(facade,Clock.fixed(now,ZoneOffset.UTC),5,500)) {
            runner.recoverDue();
            assertThat(runner.awaitIdle(Duration.ofSeconds(2))).isTrue();
            runner.recoverDue();
            assertThat(runner.awaitIdle(Duration.ofSeconds(2))).isTrue();
            assertThat(calls.get()).isEqualTo(1);
            // Unregistered handler b is a visible failure, not falsely acknowledged.
            assertThat(jdbc.queryForObject("SELECT status FROM sys_outbox_delivery WHERE handler_id='b'",String.class))
                    .isEqualTo("FAILED");
        }
    }
}
