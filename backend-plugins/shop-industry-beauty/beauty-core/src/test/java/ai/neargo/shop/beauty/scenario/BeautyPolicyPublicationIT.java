package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Uses only a disposable database from the existing runner; no application DB settings are consumed. */
class BeautyPolicyPublicationIT {
    private BeautySchemaMigrationIT fixture;
    private JdbcTemplate sql;
    private final ObjectMapper json = new ObjectMapper();
    private static final String EVENT = "BEAUTY_STORE_POLICY_VERSION_PUBLISHED";

    private Class<?> required(String name) {
        return assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty." + name));
    }

    @BeforeEach
    void database() throws Exception {
        required("service.StorePolicyService");
        fixture = new BeautySchemaMigrationIT();
        fixture.isolatedDatabase();
        fixture.initialize("migrate");
        new ResourceDatabasePopulator(new ClassPathResource("platform-write-fixture.sql")).execute(fixture.source);
        sql = new JdbcTemplate(fixture.source);
        login("ACTOR");
    }

    private static void login(String actor) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant(actor, "operator"), null, List.of()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class,
                required("config.BeautyPersistenceConfiguration"), required("config.BeautyWriteGuardConfiguration"),
                required("config.BeautyPolicyConfiguration"))
                .withBean(ObjectMapper.class, () -> json).withBean(AuditMetaObjectHandler.class)
                .withPropertyValues("shop.beauty.enabled=true", "spring.datasource.url=" + fixture.source.getUrl(),
                        "spring.datasource.username=root", "spring.datasource.password=");
    }

    private Object publish(ApplicationContext context, String key, long revision, int days) {
        Class<?> requestType = required("dto.PublishStorePolicyVersionReq");
        String input = "{\"expectedRevision\":" + revision + ",\"validFrom\":\"2026-10-20T10:00:00Z\","
                + "\"policy\":{\"bookingAheadDays\":" + days + ",\"cancelRule\":{"
                + "\"freeCancelBeforeMinutes\":60,\"allowCustomerCancel\":true},"
                + "\"allowedModes\":[\"PREPAID_SINGLE\"]}}";
        Object request = json.readValue(input, requestType);
        try {
            return required("service.StorePolicyService").getMethod("publishPolicyVersion", String.class,
                    String.class, String.class, requestType, String.class)
                    .invoke(context.getBean(required("service.StorePolicyService")), "E", "S", "ACTOR", request, key);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private int count(String table) {
        return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void emptyBusiness() {
        for (String table : List.of("bty_store_policy", "bty_store_policy_version", "bty_action_log",
                "sys_idempotent", "sys_outbox", "sys_outbox_stream", "sys_outbox_delivery")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    @Test
    void publicationReusesTransactionConnectionWithSingleConnectionPool() {
        runner().withUserConfiguration(Routed.class).run(context -> {
            HikariDataSource pool = context.getBean(DataSource.class).unwrap(HikariDataSource.class);
            pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(0);
            pool.setConnectionTimeout(500);
            pool.getHikariPoolMXBean().softEvictConnections();
            publish(context, "ONE_CONNECTION", 0, 7);
            assertThat(count("bty_store_policy_version")).isOne();
            assertThat(count("sys_outbox")).isOne();
        });
    }

    @Test
    void commitsOneImmutableVersionAuditAndEventAndReplaysOriginalResult() {
        runner().withUserConfiguration(Routed.class).run(context -> {
            assertThat(context).hasNotFailed();
            Object first = publish(context, "K", 0, 7);
            assertThat(json.writeValueAsString(publish(context, "K", 0, 7)))
                    .isEqualTo(json.writeValueAsString(first));
            for (String table : List.of("bty_store_policy", "bty_store_policy_version", "bty_action_log",
                    "sys_idempotent", "sys_outbox")) { assertThat(count(table)).as(table).isOne(); }
            assertThat(sql.queryForObject("SELECT operator_ref FROM bty_action_log", String.class)).isEqualTo("ACTOR");
            assertThat(sql.queryForObject("SELECT created_by FROM bty_store_policy_version", String.class))
                    .isEqualTo("ACTOR");
            assertThat(sql.queryForObject("SELECT endpoint FROM sys_idempotent", String.class))
                    .startsWith("BEAUTY:v1:");
        });
    }

    @Test
    void rejectsChangedPayloadAndStaleRevisionWithoutChangingHistory() {
        runner().withUserConfiguration(Routed.class).run(context -> {
            publish(context, "K", 0, 7);
            assertThatThrownBy(() -> publish(context, "K", 0, 8)).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> publish(context, "OTHER", 0, 8)).isInstanceOf(BizException.class);
            publish(context, "NEW", 1, 8);
            assertThat(count("bty_store_policy_version")).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT booking_ahead_days FROM bty_store_policy_version "
                    + "WHERE revision_index=1", Integer.class)).isEqualTo(7);
            assertThat(sql.queryForObject("SELECT version FROM bty_store_policy", Long.class)).isEqualTo(2L);
        });
    }

    @Test
    void missingSourceOrFormalRouteRollsBackEverything() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> publish(context, "K", 0, 7)).isInstanceOf(IllegalStateException.class);
            emptyBusiness();
        });
        runner().withUserConfiguration(Unrouted.class).run(context -> {
            assertThatThrownBy(() -> publish(context, "K", 0, 7)).isInstanceOf(IllegalStateException.class);
            emptyBusiness();
        });
    }

    @Test
    void failureAfterActualOutboxFlushRollsBackAllRows() {
        runner().withUserConfiguration(Routed.class).run(context -> {
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(() -> tx.execute(status -> {
                publish(context, "K", 0, 7);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        JdbcTemplate transactionSql = new JdbcTemplate(context.getBean(DataSource.class));
                        assertThat(transactionSql.queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class))
                                .isOne();
                        assertThat(transactionSql.queryForObject("SELECT COUNT(*) FROM bty_action_log", Integer.class))
                                .isOne();
                        throw new IllegalStateException("after-outbox-flush");
                    }
                });
                return null;
            })).hasMessage("after-outbox-flush");
            emptyBusiness();
        });
    }

    @Test
    void sameKeyCannotRevealResultToAnotherActor() {
        runner().withUserConfiguration(Routed.class).run(context -> {
            publish(context, "K", 0, 7);
            var idem = context.getBean(IdempotencyService.class);
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            String endpoint = sql.queryForObject("SELECT endpoint FROM sys_idempotent", String.class);
            String hash = sql.queryForObject("SELECT request_hash FROM sys_idempotent", String.class);
            assertThatThrownBy(() -> tx.execute(status -> idem.execute("K", endpoint, "OTHER", hash,
                    String.class, () -> "unexpected"))).isInstanceOf(BizException.class);
            assertThat(count("sys_idempotent")).isOne();
        });
    }

    @Test
    void strictKeysRejectBypassButRetailLegacyExecutorRemainsCompatible() {
        runner().withUserConfiguration(Routed.class).run(context -> {
            assertThatThrownBy(() -> publish(context, " ", 0, 7)).isInstanceOf(BizException.class);
            emptyBusiness();
            var idem = context.getBean(IdempotencyService.class);
            assertThat(idem.execute(null, "POST /mp/order", "ACTOR", String.class, () -> "retail-compatible"))
                    .isEqualTo("retail-compatible");
            assertThat(count("sys_idempotent")).isZero();
        });
    }

    @Test
    void concurrentInitialPublicationHasOneWinnerAndNoOrphans() {
        runner().withUserConfiguration(Routed.class).run(context -> {
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> race(context, start, "A"));
                var second = pool.submit(() -> race(context, start, "B"));
                start.countDown();
                assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
            }
            for (String table : List.of("bty_store_policy", "bty_store_policy_version", "bty_action_log",
                    "sys_idempotent", "sys_outbox")) { assertThat(count(table)).as(table).isOne(); }
        });
    }

    private int race(ApplicationContext context, CountDownLatch start, String key) throws Exception {
        login("ACTOR");
        try {
            if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("start timeout"); }
            publish(context, key, 0, 7);
            return 1;
        } catch (BizException conflict) { return 0; }
        finally { SecurityContextHolder.clearContext(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class Routed {
        @Bean
        UnifiedOutboxFacade facade(DataSource source, PlatformTransactionManager manager, ObjectMapper json) {
            var facade = new UnifiedOutboxFacade(json);
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, source, manager, json), List.of());
            // Test route only; no production readiness claim.
            facade.setArchiveOnly(Set.of("PLATFORM:" + EVENT, "PLATFORM:BEAUTY_SERVICE_DEFINITION_VERSION_PUBLISHED"));
            return facade;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Unrouted {
        @Bean
        UnifiedOutboxFacade facade(DataSource source, PlatformTransactionManager manager, ObjectMapper json) {
            var facade = new UnifiedOutboxFacade(json);
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, source, manager, json), List.of());
            return facade;
        }
    }
}
