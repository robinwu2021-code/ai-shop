package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.idem.IdempotencyService;
import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class FoodOutboxWiringTest extends FoodWriteGuardFixture {
    private static final PublishOptions OPTIONS = new PublishOptions("FOOD:GUARD_FIXTURE:A", 1);
    record ProbeEvent(String aggregateId) implements DomainEvent {
        public String aggregateType() { return "FOOD_GUARD_FIXTURE"; }
        public String eventType() { return "FOOD_GUARD_FIXTURE_CREATED"; }
    }
    record ArchivedProbe(String aggregateId) implements DomainEvent {
        public String aggregateType(){return "FOOD_TABLE";}
        public String eventType(){return "FOOD_TABLE_CHANGED";}
    }
    @Test void ordinaryMaintenanceDoesNotNeedOrWriteAnOutbox() {
        runner().run(context->{
            var fixture=prepare(context);
            var publisher=context.getBean("foodOutboxPublisher",OutboxPublisher.class);
            var receipt=fixture.tx().execute(tx->publisher.publish(new ArchivedProbe("T"),OPTIONS));
            assertThat(receipt.created()).isFalse();assertThat(receipt.eventNo()).isNull();
            assertThat(fixture.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class)).isZero();
        });
    }
    @Test void aRealSubscriberStillGetsReliableDelivery() {
        runner().withUserConfiguration(Subscribed.class).run(context->{
            var fixture=prepare(context);
            fixture.tx().executeWithoutResult(tx->context.getBean("foodOutboxPublisher",OutboxPublisher.class).publish(new ArchivedProbe("T"),OPTIONS));
            assertThat(fixture.sql().queryForObject("SELECT status FROM sys_outbox",String.class)).isEqualTo("PENDING");
            assertThat(fixture.sql().queryForObject("SELECT handler_id FROM sys_outbox_delivery",String.class)).isEqualTo("actual-subscriber");
        });
    }
    @Test void refusesMissingUnifiedPlatformSourceInsteadOfUsingLegacyFallback() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasBean("foodOutboxPublisher");
            var fixture = prepare(context);
            var publisher = context.getBean("foodOutboxPublisher", OutboxPublisher.class);
            assertThatThrownBy(() -> fixture.tx().execute(status -> publisher.publish(new ProbeEvent("A"), OPTIONS)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("PLATFORM");
            assertThat(fixture.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isZero();
        });
    }
    @Test void rollbackWithSourceTransaction() {
        runner().withUserConfiguration(Unified.class).run(context -> {
            var fixture = prepare(context);
            var publisher = context.getBean("foodOutboxPublisher", OutboxPublisher.class);
            var idem = context.getBean(IdempotencyService.class);
            var source = context.getBean(SourceMapper.class);
            login("trusted-actor");
            assertThatThrownBy(() -> fixture.tx().execute(status -> idem.execute("key", "FOOD:fixture:E:A",
                    "trusted-actor", "request-hash", String.class, () -> {
                        var row = new SourceRow(); row.setName("source"); source.insert(row);
                        fixture.sql().update("INSERT INTO food_guard_intent_fixture(id) VALUES ('I')");
                        fixture.sql().update("INSERT INTO food_guard_audit_fixture(id,actor_no) VALUES ('A',?)",
                                row.getCreatedBy());
                        publisher.publish(new ProbeEvent("A"), OPTIONS);
                        throw new IllegalStateException("rollback-probe");
                    }))).hasMessage("rollback-probe");
            for (String table : List.of("food_guard_source_fixture", "food_guard_intent_fixture",
                    "food_guard_audit_fixture",
                    "sys_idempotent", "sys_outbox", "sys_outbox_stream", "sys_outbox_delivery")) {
                assertThat(fixture.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                        .as(table).isZero();
            }
        });
    }
    @Test void rollbackAfterOutboxFlushUndoesActualEventAndStreamRows() {
        runner().withUserConfiguration(Unified.class).run(context -> {
            var fixture = prepare(context);
            var publisher = context.getBean("foodOutboxPublisher", OutboxPublisher.class);
            assertThatThrownBy(() -> fixture.tx().execute(status -> {
                fixture.sql().update("INSERT INTO food_guard_intent_fixture(id) VALUES ('I')");
                publisher.publish(new ProbeEvent("A"), OPTIONS);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        assertThat(fixture.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class))
                                .isOne();
                        throw new IllegalStateException("after-flush");
                    }
                });
                return null;
            })).hasMessage("after-flush");
            for (String table : List.of("food_guard_intent_fixture", "sys_outbox", "sys_outbox_stream")) {
                assertThat(fixture.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
            }
        });
    }
    @Test void commitUsesPlatformAuditAndPublishesExactlyOnceOnReplay() {
        runner().withUserConfiguration(Unified.class).run(context -> {
            var fixture = prepare(context);
            var publisher = context.getBean("foodOutboxPublisher", OutboxPublisher.class);
            var idem = context.getBean(IdempotencyService.class);
            var source = context.getBean(SourceMapper.class);
            login("trusted-actor");
            for (int retry = 0; retry < 2; retry++) fixture.tx().execute(status -> idem.execute(
                    "key", "FOOD:fixture:E:A",
                    "trusted-actor", "request-hash", String.class, () -> {
                        var row = new SourceRow(); row.setName("source"); source.insert(row);
                        assertThat(row.getCreatedBy()).isEqualTo("trusted-actor");
                        fixture.sql().update("INSERT INTO food_guard_audit_fixture(id,actor_no) VALUES ('A',?)",
                                row.getCreatedBy());
                        publisher.publish(new ProbeEvent("A"), OPTIONS);
                        return "first-result";
                    }));
            assertThat(fixture.sql().queryForObject("SELECT COUNT(*) FROM food_guard_source_fixture", Integer.class))
                    .isOne();
            assertThat(fixture.sql().queryForObject("SELECT actor_no FROM food_guard_audit_fixture", String.class))
                    .isEqualTo("trusted-actor");
            assertThat(fixture.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class))
                                .isOne();
            assertThat(fixture.sql().queryForObject("SELECT source_system FROM sys_outbox", String.class))
                    .isEqualTo("PLATFORM");
        });
    }
    @Test void rejectPublishingOutsideTheSourceTransaction() {
        runner().withUserConfiguration(Unified.class).run(context -> {
            prepare(context);
            assertThatThrownBy(() -> context.getBean("foodOutboxPublisher", OutboxPublisher.class)
                    .publish(new ProbeEvent("A"), OPTIONS)).isInstanceOf(IllegalStateException.class);
        });
    }
    @Test void refuseAPlatformFacadeBoundToAnotherDataSource() {
        runner().withUserConfiguration(WrongSource.class).run(context -> {
            var fixture = prepare(context);
            var publisher = context.getBean("foodOutboxPublisher", OutboxPublisher.class);
            assertThatThrownBy(() -> fixture.tx().execute(status -> publisher.publish(new ProbeEvent("A"), OPTIONS)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(fixture.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isZero();
        });
    }
    @Test void disabledAndSkeletonModesRegisterNoWriteGuards() {
        for (String property : new String[] {"shop.food.enabled=false", "shop.food.migration.mode=off"}) {
            runner().withPropertyValues(property).run(context -> assertThat(context).hasNotFailed()
                    .doesNotHaveBean(IdempotencyService.class).doesNotHaveBean("foodOutboxPublisher"));
        }
    }
    private static void login(String actor) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(LoginUser.merchant(actor, actor), null, List.of()));
    }
    @Test void channelSubscribersDoNotCreateDraftOrNonHandoverDeliveries() {
        runner().withUserConfiguration(ChannelSubscribers.class).run(c -> {
            var f = prepare(c);
            var publisher = c.getBean("foodOutboxPublisher", OutboxPublisher.class);
            var draft = new ai.neargo.shop.spi.food.MenuChanged("E", "S", "M", "UPDATE_MENU", 1, "OP", null);
            var skipped = f.tx().execute(t -> publisher.publish(draft, new PublishOptions("MENU:M", 1)));
            assertThat(skipped.created()).isFalse();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Long.class)).isZero();
            var disabled = new ai.neargo.shop.spi.food.MenuChanged("E", "S", "M", "DISABLE_MENU", 2, "OP2", null);
            f.tx().executeWithoutResult(t -> publisher.publish(disabled, new PublishOptions("MENU:M", 1)));
            for (String action : List.of("START", "READY", "CUSTODY_HANDOVER")) {
                var fact = new ai.neargo.shop.spi.food.DiningServiceEvent(action, "E", "S", "B", "K", "T", "L", null,
                        "ST", "MAKE", 1, action, java.math.BigDecimal.ONE, null, "A", java.time.Instant.now());
                f.tx().executeWithoutResult(t -> publisher.publish(fact, new PublishOptions("FOOD_KITCHEN_TASK:T", 1)));
            }
            assertThat(f.sql().queryForList("SELECT e.event_type FROM sys_outbox e JOIN sys_outbox_delivery d ON e.event_no=d.event_no WHERE d.handler_id='commerce-food-source-events-v1'", String.class))
                    .containsExactly("FOOD_MENU_DISABLED");
            assertThat(f.sql().queryForList("SELECT e.event_type FROM sys_outbox e JOIN sys_outbox_delivery d ON e.event_no=d.event_no WHERE d.handler_id='commerce-food-fulfillment-events-v1'", String.class))
                    .containsExactly("DINING_CUSTODY_HANDOVER");
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox_delivery WHERE handler_id='food-kds-business-events-v1'", Long.class))
                    .isEqualTo(3);
            long before = f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Long.class);
            f.tx().executeWithoutResult(t -> {
                publisher.publish(disabled, new PublishOptions("MENU:M", 1)); t.setRollbackOnly();
            });
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Long.class)).isEqualTo(before);
        });
    }

    @Configuration(proxyBeanMethods=false)
    static class ChannelSubscribers {
        @Bean UnifiedOutboxFacade channelSubscribers(@Qualifier("dataSource") DataSource source,
                @Qualifier("transactionManager") PlatformTransactionManager manager, ObjectMapper json) {
            var facade = new UnifiedOutboxFacade(json);
            var handlers = List.of(
                    target("commerce-food-source-events-v1", Set.of("FOOD_MENU_DISABLED")),
                    target("commerce-food-fulfillment-events-v1", Set.of("DINING_CUSTODY_HANDOVER")),
                    target("food-kds-business-events-v1", Set.of("DINING_SERVICE_EVENT", "DINING_CUSTODY_HANDOVER")));
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, source, manager, json), handlers);
            return facade;
        }
        private static OutboxHandler target(String id, Set<String> types) {
            return new OutboxHandler() {
                public String handlerId() { return id; }
                public boolean externalWorkflow() { return true; }
                public boolean supports(String type) { return types.contains(type); }
                public DeliveryResult handle(OutboxEnvelope event) { return DeliveryResult.sent(); }
            };
        }
    }

    @Configuration(proxyBeanMethods=false)
    static class Subscribed {
        @Bean UnifiedOutboxFacade subscribed(@Qualifier("dataSource") DataSource source,
                @Qualifier("transactionManager") PlatformTransactionManager tx,ObjectMapper json){
            var facade=new UnifiedOutboxFacade(json);
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM,source,tx,json),List.of(new OutboxHandler(){
                public String handlerId(){return "actual-subscriber";}
                public boolean supports(String type){return "FOOD_TABLE_CHANGED".equals(type);}
                public DeliveryResult handle(OutboxEnvelope event){return DeliveryResult.sent();}
            }));return facade;
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class WrongSource {
        @Bean UnifiedOutboxFacade wrongSourceFacade(Environment env, ObjectMapper json) {
            var other = new DriverManagerDataSource(env.getProperty("spring.datasource.url"),
                    env.getProperty("spring.datasource.username"), "");
            var facade = new UnifiedOutboxFacade(json);
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, other,
                    new DataSourceTransactionManager(other), json), List.of());
            return facade;
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class Unified {
        @Bean UnifiedOutboxFacade unifiedOutboxFacade(@Qualifier("dataSource") DataSource source,
                @Qualifier("transactionManager") PlatformTransactionManager tx, ObjectMapper json) {
            var facade = new UnifiedOutboxFacade(json);
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, source, tx, json), List.of());
            // Test-only archive route, no business event/routing contract is introduced by this fixture.
            facade.setArchiveOnly(Set.of("PLATFORM:FOOD_GUARD_FIXTURE_CREATED"));
            return facade;
        }
    }
}
