package ai.neargo.shop.event.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.OutboxProperties;
import ai.neargo.shop.event.outbox.runtime.OutboxRunner;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Two real source adapters/runners share one database while owning different executable handlers. */
class OutboxHeterogeneousHostTest extends OutboxTestFixture {
    @Test void earlierForeignRowsBeyondBatchLimitCannotStarveOrBeClaimedByAnotherHost() throws Exception {
        for(int i=0;i<3;i++) publish("FOREIGN"+i,"foreign-owner",true);
        publish("LOCAL","local-owner",false);
        var localCalls=new AtomicInteger(); var foreignCalls=new AtomicInteger();
        var local=host("local-owner",localCalls,false); var foreign=host("foreign-owner",foreignCalls,true);
        try(var runner=new OutboxRunner(local,Clock.fixed(now,ZoneOffset.UTC),properties())) {
            runner.recoverDue(); assertThat(runner.awaitIdle(Duration.ofSeconds(2))).isTrue();
            assertThat(localCalls.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM sys_outbox WHERE aggregate_id='LOCAL'",String.class))
                    .isEqualTo("SENT");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox_delivery WHERE handler_id='foreign-owner'"
                    + " AND status='PENDING' AND attempt_count=0 AND claim_token IS NULL",Long.class)).isEqualTo(3);
            assertThat(foreignCalls.get()).isZero();
        }
        try(var runner=new OutboxRunner(foreign,Clock.fixed(now,ZoneOffset.UTC),properties())) {
            runner.recoverDue(); assertThat(runner.awaitIdle(Duration.ofSeconds(2))).isTrue();
            runner.recoverDue(); assertThat(runner.awaitIdle(Duration.ofSeconds(2))).isTrue();
            assertThat(foreignCalls.get()).isEqualTo(3);
            assertThat(localCalls.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox_delivery WHERE status='SENT'",Long.class))
                    .isEqualTo(4);
        }
    }

    @Test void hostsWithoutExecutableHandlersCannotClaimProducerMetadataOrForeignRows() throws Exception {
        publish("FOREIGN","foreign-owner",true);
        var producer=new UnifiedOutboxFacade(new ObjectMapper());
        producer.registerProducer(adapter(),Map.of("ORDER_PAID",Map.of("foreign-owner",true)));
        assertThat(producer.localHandlerIds("PLATFORM")).isEmpty();
        assertThat(store.findDue(now,2,Set.of())).isEmpty();
        try(var runner=new OutboxRunner(producer,Clock.fixed(now,ZoneOffset.UTC),properties())) {
            runner.recoverDue(); assertThat(runner.awaitIdle(Duration.ofSeconds(2))).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM sys_outbox_delivery",String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM sys_outbox_delivery",Integer.class)).isZero();
    }

    @Test void expiredForeignLeasesAreUntouchedAndCannotStarveLocalRecovery() throws Exception {
        for(int i=0;i<3;i++) publish("FOREIGN"+i,"foreign-owner",true);
        publish("LOCAL","local-owner",false);
        for(var ref:store.findDue(now,100)) {
            assertThat(store.tryClaim(ref,"old-"+ref.id(),now.minusSeconds(5),Duration.ofSeconds(1))).isPresent();
        }
        var calls=new AtomicInteger();
        try(var runner=new OutboxRunner(host("local-owner",calls,false),Clock.fixed(now,ZoneOffset.UTC),properties())) {
            runner.recoverDue(); assertThat(runner.awaitIdle(Duration.ofSeconds(2))).isTrue();
            assertThat(calls.get()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox_delivery WHERE handler_id='foreign-owner'"
                + " AND status='PROCESSING' AND attempt_count=1 AND last_error_code IS NULL",Long.class)).isEqualTo(3);
        assertThat(store.recoverExpired(now,2,Set.of())).isZero();
        assertThat(store.recoverExpired(now,2,Set.of("foreign-owner"))).isEqualTo(2);
        assertThat(store.recoverExpired(now,2,Set.of("foreign-owner"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox_delivery WHERE handler_id='foreign-owner'"
                + " AND status='FAILED' AND last_error_code='OUTCOME_UNKNOWN'",Long.class)).isEqualTo(3);
    }

    private void publish(String id,String handler,boolean external) {
        var event=new OutboxEnvelope(id,"PLATFORM",id,"ORDER",id,"ORDER_PAID",1,"ORDER:"+id,0,"{}",now);
        tx.executeWithoutResult(status -> store.append(event,Map.of(handler,external),false));
    }
    private JdbcOutboxStore adapter() {
        var source=jdbc.getDataSource();
        return new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM,source,
                new DataSourceTransactionManager(source),new ObjectMapper());
    }
    private UnifiedOutboxFacade host(String id,AtomicInteger calls,boolean external) {
        var facade=new UnifiedOutboxFacade(new ObjectMapper());
        facade.register(adapter(),List.of(new OutboxHandler() {
            @Override public String handlerId() { return id; }
            @Override public boolean supports(String type) { return true; }
            @Override public boolean externalWorkflow() { return external; }
            @Override public DeliveryResult handle(OutboxEnvelope event) { calls.incrementAndGet(); return DeliveryResult.sent(); }
        }));
        return facade;
    }
    private OutboxProperties properties() {
        var properties=new OutboxProperties(); properties.setConcurrency(1); properties.setQueueCapacity(4);
        properties.setBatchSize(2); return properties;
    }
}
