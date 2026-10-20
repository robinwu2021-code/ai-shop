package ai.neargo.shop.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.OutboxPump;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementInboxProcessor;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementOutboxRelay;
import ai.neargo.shop.pay.mapper.entitlement.PayEntInboxMapper;
import ai.neargo.shop.pay.mapper.entitlement.PayEntOutboxMapper;
import ai.neargo.shop.spi.entitlement.EntitlementEvents;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Transaction, immutable replay and restart-bridge checks against disposable native MySQL only. */
@EnabledIfEnvironmentVariable(named = "PAY_EVENT_NATIVE_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
class PayEntitlementEventNativeMysqlTest {
    @Test
    void payEventRollbackReplayInboxAndRestartBridgeAreAtomicAndIdempotent() throws Exception {
        String url = System.getenv("PAY_EVENT_NATIVE_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/entitlement_native_fixture\\?.*");
        var ds = new DriverManagerDataSource(url, "root", "");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V429__pay_entitlement_event_outbox_inbox.sql")).execute(ds);
        new JdbcTemplate(ds).execute("""
                CREATE TABLE sys_outbox (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    event_no VARCHAR(64) NOT NULL,
                    aggregate_type VARCHAR(32) NOT NULL,
                    aggregate_id VARCHAR(64) NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    payload TEXT NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    retry_count INT NOT NULL DEFAULT 0,
                    next_retry_at DATETIME NULL,
                    last_error VARCHAR(512) NULL,
                    created_at DATETIME NOT NULL,
                    sent_at DATETIME NULL,
                    UNIQUE KEY uk_event_no (event_no)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        SqlSessionFactory sessions = factory.getObject();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("payTxManager", PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(ds));
            registerMapper(context, PayEntOutboxMapper.class, sessions);
            registerMapper(context, PayEntInboxMapper.class, sessions);
            registerMapper(context, SysOutboxMapper.class, sessions);
            context.refresh();
            var outbox = context.getBean(PayEntOutboxMapper.class);
            var inbox = context.getBean(PayEntInboxMapper.class);
            var sys = context.getBean(SysOutboxMapper.class);
            var jdbc = new JdbcTemplate(ds);
            var tx = new TransactionTemplate(context.getBean("payTxManager", PlatformTransactionManager.class));
            ObjectMapper json = new ObjectMapper();
            @SuppressWarnings("unchecked")
            ObjectProvider<OutboxPump> noPump = mock(ObjectProvider.class);
            when(noPump.getIfAvailable()).thenReturn(null);
            var bus = new OutboxEventBus(sys, json, noPump);
            var relay = new PayEntitlementOutboxRelay(outbox, bus, json);
            @SuppressWarnings("unchecked")
            ObjectProvider<PayEntitlementOutboxRelay> relayProvider = mock(ObjectProvider.class);
            when(relayProvider.getIfAvailable()).thenReturn(relay);
            var publisher = new PayEntitlementEventPublisher(outbox, json, relayProvider);
            var recoveryOnlyPublisher = new PayEntitlementEventPublisher(outbox, json, noRelay());
            var processor = new PayEntitlementInboxProcessor(inbox, json);
            try {
                String eventNo = "EVT-" + UUID.randomUUID();
                var event = grant(eventNo, "GRANT-1");
                tx.execute(status -> {
                    publisher.publish(event);
                    status.setRollbackOnly();
                    return null;
                });
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox WHERE event_no=?", Long.class,
                        eventNo)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE event_no=?", Long.class,
                        eventNo)).isZero();

                tx.executeWithoutResult(status -> publisher.publish(event));
                awaitSystemEvent(jdbc, eventNo);
                awaitForwarded(jdbc, eventNo);
                assertThat(jdbc.queryForObject("SELECT status FROM pay_ent_outbox WHERE event_no=?", String.class,
                        eventNo)).isEqualTo("SENT");
                tx.executeWithoutResult(status -> publisher.publish(event));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE event_no=?", Long.class,
                        eventNo)).isEqualTo(1L);
                assertThatThrownBy(() -> tx.executeWithoutResult(status -> publisher.publish(grant(eventNo, "GRANT-2"))))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("immutable facts");

                String recoveredEventNo = "EVT-" + UUID.randomUUID();
                tx.executeWithoutResult(status -> recoveryOnlyPublisher.publish(grant(recoveredEventNo, "GRANT-3")));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE event_no=?", Long.class,
                        recoveredEventNo)).isZero();
                assertThat(relay.relayPending()).isEqualTo(1);
                awaitSystemEvent(jdbc, recoveredEventNo);
                awaitForwarded(jdbc, recoveredEventNo);

                String inboxEventNo = "EVT-" + UUID.randomUUID();
                String payload = "activity:scope-v1";
                var effects = new AtomicInteger();
                Boolean firstApply = tx.execute(status -> processor.process("MAIN", "CORE_ACTIVITY", inboxEventNo,
                        payload, effects::incrementAndGet));
                Boolean replayApply = tx.execute(status -> processor.process("MAIN", "CORE_ACTIVITY", inboxEventNo,
                        payload, effects::incrementAndGet));
                assertThat(firstApply).isTrue();
                assertThat(replayApply).isFalse();
                assertThat(effects).hasValue(1);
                assertThatThrownBy(() -> tx.execute(status -> processor.process("MAIN", "CORE_ACTIVITY", inboxEventNo,
                        "activity:scope-v2", effects::incrementAndGet)))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("payload conflict");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_inbox WHERE event_no=?", Long.class,
                        inboxEventNo)).isEqualTo(1L);
            } finally {
                relay.shutdown();
            }
        }
    }

    private static EntitlementEvents.EntitlementGranted grant(String eventNo, String grantNo) {
        return new EntitlementEvents.EntitlementGranted(eventNo, grantNo, "U-1", "E-1", "ETV-1",
                "PAID_ORDER", "ORDER-1", 1_797_000_000_000L);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PayEntitlementOutboxRelay> noRelay() {
        ObjectProvider<PayEntitlementOutboxRelay> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static void awaitSystemEvent(JdbcTemplate jdbc, String eventNo) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE event_no=?", Long.class, eventNo) == 1L)
                return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for bridged event " + eventNo);
    }

    private static void awaitForwarded(JdbcTemplate jdbc, String eventNo) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            String status = jdbc.queryForObject("SELECT status FROM pay_ent_outbox WHERE event_no=?", String.class,
                    eventNo);
            if ("SENT".equals(status)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for pay event forwarding " + eventNo);
    }

    private static <T> void registerMapper(AnnotationConfigApplicationContext context, Class<T> type,
                                            SqlSessionFactory sessions) {
        context.registerBean(type.getSimpleName(), MapperFactoryBean.class, () -> {
            var mapper = new MapperFactoryBean<>(type);
            mapper.setSqlSessionFactory(sessions);
            return mapper;
        });
    }
}
