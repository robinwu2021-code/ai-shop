package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.outbox.OutboxPublisher;
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
