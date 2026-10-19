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
    @Test
    void disabledRunnerIgnoresCommitAndReplaySignals() throws Exception {
        var called = new java.util.concurrent.CountDownLatch(1);
        var facade = new UnifiedOutboxFacade(new ObjectMapper());
        facade.register(store, List.of(new OutboxHandler() {
            public String handlerId() { return "a"; }
            public boolean supports(String type) { return true; }
            public DeliveryResult handle(OutboxEnvelope e) { called.countDown(); return DeliveryResult.sent(); }
        }));
        try (var runner = new OutboxRunner(facade, Clock.fixed(now, ZoneOffset.UTC), 1, 10)) {
            append("E1");
            facade.signal("PLATFORM");
            assertThat(called.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse();
            assertThat(store.counts().get("PENDING")).isEqualTo(2L);
            runner.start();
            assertThat(called.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rawBridgeFailureRecordsCauseAndRetryInsteadOfWaitingForLeaseExpiry() throws Exception {
        tx.executeWithoutResult(s -> store.append(event("BRIDGE", "{}"), java.util.Map.of("bridge", false), false));
        var facade = new UnifiedOutboxFacade(new ObjectMapper());
        facade.register(store, List.of(new OutboxHandler() {
            public String handlerId() { return "bridge"; }
            public boolean supports(String type) { return true; }
            public DeliveryResult handle(OutboxEnvelope e) { throw new org.springframework.dao.DataAccessResourceFailureException("offline"); }
        }));
        try (var runner = new OutboxRunner(facade, Clock.fixed(now, ZoneOffset.UTC), 1, 10)) {
            runner.recoverDue();
            assertThat(runner.awaitIdle(Duration.ofSeconds(2))).isTrue();
            var row = jdbc.queryForMap("SELECT * FROM sys_outbox_delivery");
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("last_error_code")).isEqualTo("TARGET_UNAVAILABLE");
            assertThat(row.get("next_retry_at")).isNotNull();
        }
    }
    private record Changed(String id) implements ai.neargo.shop.event.DomainEvent {
        public String aggregateType() { return "ORDER"; }
        public String aggregateId() { return id; }
        public String eventType() { return "ORDER_PAID"; }
    }
    @Test
    void producerLoopsReserveStreamsInSortedOrderAndIsolateRequiresNewBuffers() {
        var facade = new UnifiedOutboxFacade(new ObjectMapper());
        facade.register(store,List.of());
        var inner = new org.springframework.transaction.support.TransactionTemplate(tx.getTransactionManager());
        inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(s -> {
            facade.publish("PLATFORM",new Changed("Z"));
            facade.publish("PLATFORM",new Changed("A"));
            inner.executeWithoutResult(n -> facade.publish("PLATFORM",new Changed("INNER")));
            s.setRollbackOnly();
        });
        assertThat(jdbc.queryForList("SELECT aggregate_id FROM sys_outbox",String.class)).containsExactly("INNER");
        tx.executeWithoutResult(s -> {
            facade.publish("PLATFORM",new Changed("Z"));
            facade.publish("PLATFORM",new Changed("A"));
        });
        assertThat(jdbc.queryForList("SELECT aggregate_id FROM sys_outbox ORDER BY id",String.class))
                .containsExactly("INNER","Z","A");
    }
    @Test
    void noConsumerAndExplicitArchiveCannotBeReportedAsSuccessfulDelivery() {
        var facade = new UnifiedOutboxFacade(new ObjectMapper());
        facade.register(store,List.of());
        tx.executeWithoutResult(s -> facade.publish("PLATFORM",new Changed("UNROUTED")));
        assertThat(jdbc.queryForObject("SELECT status FROM sys_outbox WHERE aggregate_id='UNROUTED'",String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM sys_outbox WHERE aggregate_id='UNROUTED'",String.class)).isEqualTo("NO_CONSUMER");
        facade.setArchiveOnly(java.util.Set.of("PLATFORM:ORDER_PAID"));
        tx.executeWithoutResult(s -> facade.publish("PLATFORM",new Changed("ARCHIVE")));
        assertThat(jdbc.queryForObject("SELECT status FROM sys_outbox WHERE aggregate_id='ARCHIVE'",String.class)).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox_delivery",Long.class)).isZero();
    }
}
