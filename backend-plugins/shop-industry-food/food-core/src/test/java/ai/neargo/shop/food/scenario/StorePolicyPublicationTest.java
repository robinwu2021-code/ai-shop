package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.dto.CreatePolicyReq;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceCalendarMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.impl.StoreOperationServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class StorePolicyPublicationTest extends FoodWriteGuardFixture {
    private static final Instant FIRST = Instant.parse("2026-10-20T12:00:00Z");
    private Fixture fixture(AssertableApplicationContext context) {
        var f = prepare(context);
        createPolicyTables(f);
        return f;
    }
    static void createPolicyTables(Fixture f) {
        String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
                + "updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
        f.sql().execute("CREATE TABLE fnb_store_policy (" + common
                + "policy_no VARCHAR(32) UNIQUE,store_no VARCHAR(32),timezone VARCHAR(64),cutover_minute INT,"
                + "effective_from DATETIME(3),pay_policy VARCHAR(16),capabilities VARCHAR(2048),settings VARCHAR(2048),"
                + "UNIQUE(store_no,effective_from))");
        f.sql().execute("CREATE TABLE fnb_action_log (" + common
                + "action_no VARCHAR(32) UNIQUE,store_no VARCHAR(32),object_type VARCHAR(32),object_no VARCHAR(32),"
                + "action VARCHAR(32),operator_no VARCHAR(32),request_no VARCHAR(64),reason VARCHAR(255),"
                + "before_ref VARCHAR(64),after_ref VARCHAR(64))");
    }
    private static CreatePolicyReq request(long version, Instant time) {
        return new CreatePolicyReq("Asia/Shanghai", 240, time, "POSTPAY", List.of(), null, version);
    }
    private void empty(Fixture f) {
        for (String table : List.of("fnb_store_policy", "fnb_action_log", "sys_idempotent", "sys_outbox")) {
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
        }
    }
    @Test void publishesAndReplaysExactlyOneDurableCommand() {
        runner().withUserConfiguration(Publication.class).run(context -> {
            var f = fixture(context);
            var service = context.getBean(StoreOperationService.class);
            var first = service.createPolicy("E", "A", "actor", request(0, FIRST), "key");
            assertThat(first).isNotNull();
            assertThat(first.status()).isEqualTo("SUCCEEDED");
            assertThat(first.version()).isEqualTo(1);
            assertThat(service.createPolicy("E", "A", "actor", request(0, FIRST), "key")).isEqualTo(first);
            for (String table : List.of("fnb_store_policy", "fnb_action_log", "sys_idempotent", "sys_outbox")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isOne();
            }
            assertThat(f.sql().queryForObject("SELECT operator_no FROM fnb_action_log", String.class))
                    .isEqualTo("actor");
            assertThat(f.sql().queryForObject("SELECT policy_no FROM fnb_store_policy", String.class))
                    .isEqualTo(first.resultRef());
        });
    }
    @Test void rejectsChangedPayloadOrActorUnderTheOriginalKey() {
        runner().withUserConfiguration(Publication.class).run(context -> {
            fixture(context);
            var service = context.getBean(StoreOperationService.class);
            assertThat(service.createPolicy("E", "A", "actor", request(0, FIRST), "key")).isNotNull();
            assertCode(() -> service.createPolicy("E", "A", "actor", request(0, FIRST.plusSeconds(1)), "key"),
                    ErrorCode.CONFLICT);
            assertCode(() -> service.createPolicy("E", "A", "other", request(0, FIRST), "key"), ErrorCode.NOT_FOUND);
            for (CreatePolicyReq changed : List.of(
                    new CreatePolicyReq("UTC", 240, FIRST, "POSTPAY", List.of(), null, 0L),
                    new CreatePolicyReq("Asia/Shanghai", 300, FIRST, "POSTPAY", List.of(), null, 0L),
                    new CreatePolicyReq("Asia/Shanghai", 240, FIRST, "PREPAY", List.of(), null, 0L),
                    request(1, FIRST))) {
                assertCode(() -> service.createPolicy("E", "A", "actor", changed, "key"), ErrorCode.CONFLICT);
            }
            var replay = service.createPolicy("E", "A", "actor",
                    new CreatePolicyReq("Asia/Shanghai", 240, FIRST, "POSTPAY", List.of(), " ", 0L), "key");
            assertThat(replay).isNotNull();
            assertThat(replay.version()).isEqualTo(1);
        });
    }
    @Test void appendsFuturePolicyAndRejectsStaleVersionOrTimelineInsertion() {
        runner().withUserConfiguration(Publication.class).run(context -> {
            var f = fixture(context);
            var service = context.getBean(StoreOperationService.class);
            assertThat(service.createPolicy("E", "A", "actor", request(0, FIRST), "one")).isNotNull();
            var next = service.createPolicy("E", "A", "actor", request(1, FIRST.plusSeconds(86400)), "two");
            assertThat(next).isNotNull();
            assertThat(next.version()).isEqualTo(2);
            assertCode(() -> service.createPolicy("E", "A", "actor", request(1, FIRST.plusSeconds(172800)), "old"),
                    ErrorCode.CONFLICT);
            assertCode(() -> service.createPolicy("E", "A", "actor", request(2, FIRST.plusSeconds(1)), "middle"),
                    ErrorCode.CONFLICT);
            assertCode(() -> service.createPolicy("E", "A", "actor", request(2, FIRST.plusSeconds(86400)), "equal"),
                    ErrorCode.CONFLICT);
            assertThat(f.sql().queryForList("SELECT version FROM fnb_store_policy ORDER BY effective_from", Long.class))
                    .containsExactly(1L, 2L);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isEqualTo(2);
        });
    }
    @Test void sameKeyCanBeUsedIndependentlyInAnotherStore() {
        runner().withUserConfiguration(Publication.class).run(context -> {
            var f = fixture(context);
            var service = context.getBean(StoreOperationService.class);
            assertThat(service.createPolicy("E", "A", "actor", request(0, FIRST), "same")).isNotNull();
            assertThat(service.createPolicy("E", "B", "actor", request(0, FIRST), "same")).isNotNull();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isEqualTo(2);
        });
    }
    @Test void rejectsInvalidInputAndUnverifiedCapabilitiesWithoutWrites() {
        runner().withUserConfiguration(Publication.class).run(context -> {
            var f = fixture(context);
            var service = context.getBean(StoreOperationService.class);
            assertCode(() -> service.createPolicy("E", "A", "actor", request(0, FIRST), " "), ErrorCode.BAD_REQUEST);
            assertCode(() -> service.createPolicy("E", "A", " ", request(0, FIRST), "key"), ErrorCode.FORBIDDEN);
            for (CreatePolicyReq req : List.of(
                    new CreatePolicyReq("Invalid/Zone", 240, FIRST, "PREPAY", List.of(), null, 0L),
                    new CreatePolicyReq("Asia/Shanghai", 1440, FIRST, "PREPAY", List.of(), null, 0L),
                    request(0, FIRST.plusNanos(1)))) {
                assertCode(() -> service.createPolicy("E", "A", "actor", req, "key"), ErrorCode.BAD_REQUEST);
            }
            assertCode(() -> service.createPolicy("E", "A", "actor",
                    new CreatePolicyReq("Asia/Shanghai", 240, FIRST, "PREPAY", List.of("CASHIER"), null, 0L), "key"),
                    ErrorCode.FORBIDDEN);
            assertCode(() -> service.createPolicy("E", "A", "actor",
                    new CreatePolicyReq("Asia/Shanghai", 240, FIRST, "PREPAY", List.of(), "unverified", 0L), "key"),
                    ErrorCode.FORBIDDEN);
            empty(f);
        });
    }
    @Test void missingUnifiedSourceRollsBackPolicyAuditAndIdempotency() {
        runner().withUserConfiguration(Publication.class)
                .withPropertyValues("test.policy.source=false").run(context -> {
            var f = fixture(context);
            assertThatThrownBy(() -> context.getBean(StoreOperationService.class)
                    .createPolicy("E", "A", "actor", request(0, FIRST), "key"))
                    .isInstanceOf(IllegalStateException.class);
            empty(f);
        });
    }
    @Test void missingConsumerPreservesPlatformFailedEventForRecovery() {
        runner().withUserConfiguration(Publication.class)
                .withPropertyValues("test.policy.archive=false").run(context -> {
            var f = fixture(context);
            assertThat(context.getBean(StoreOperationService.class)
                    .createPolicy("E", "A", "actor", request(0, FIRST), "key")).isNotNull();
            assertThat(f.sql().queryForObject("SELECT status FROM sys_outbox", String.class)).isEqualTo("FAILED");
            assertThat(f.sql().queryForObject("SELECT last_error FROM sys_outbox", String.class))
                    .isEqualTo("NO_CONSUMER");
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_store_policy", Integer.class)).isOne();
        });
    }
    @Test void rollbackAfterActualOutboxFlushAlsoRemovesPolicyAndAudit() {
        runner().withUserConfiguration(Publication.class).run(context -> {
            var f = fixture(context);
            context.getBean(Signals.class).afterFlush.set(true);
            assertThatThrownBy(() -> context.getBean(StoreOperationService.class)
                    .createPolicy("E", "A", "actor", request(0, FIRST), "key"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("after-policy-event-flush");
            empty(f);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox_stream", Integer.class)).isZero();
        });
    }
    @Test void rejectsPublicationInsideAnExistingUnknownIsolationTransaction() {
        runner().withUserConfiguration(Publication.class).run(context -> {
            var f = fixture(context);
            assertThatThrownBy(() -> f.tx().execute(status -> context.getBean(StoreOperationService.class)
                    .createPolicy("E", "A", "actor", request(0, FIRST), "key")))
                    .isInstanceOf(IllegalStateException.class);
            empty(f);
        });
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "FOOD_TEST_DISPOSABLE", matches = "true")
    void concurrentFirstPublicationsAtDifferentTimesHaveExactlyOneWinner() {
        runner().withUserConfiguration(Publication.class).run(context -> {
            var f = fixture(context);
            var barrier = new CyclicBarrier(2);
            context.getBean(SqlSessionFactory.class).getConfiguration().addInterceptor(new EmptyReadBarrier(barrier));
            var service = context.getBean(StoreOperationService.class);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var left = workers.submit(() -> concurrent(service, FIRST, "left"));
                var right = workers.submit(() -> concurrent(service, FIRST.plusSeconds(1), "right"));
                var results = List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
                assertThat(results.stream().filter(OperationVO.class::isInstance).count()).isEqualTo(1);
                assertThat(results).contains(ErrorCode.CONFLICT);
            }
            for (String table : List.of("fnb_store_policy", "fnb_action_log", "sys_idempotent", "sys_outbox")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isOne();
            }
        });
    }
    private static Object concurrent(StoreOperationService service, Instant at, String key) {
        try { return service.createPolicy("E", "A", "actor", request(0, at), key); }
        catch (BizException failure) { return failure.errorCode(); }
    }
    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class EmptyReadBarrier implements Interceptor {
        private final CyclicBarrier barrier;
        EmptyReadBarrier(CyclicBarrier barrier) { this.barrier = barrier; }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            var statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().endsWith("StorePolicyMapper.selectList") && result instanceof List<?> list
                    && list.isEmpty()) barrier.await(20, TimeUnit.SECONDS);
            return result;
        }
    }
    static class Signals { final AtomicBoolean afterFlush = new AtomicBoolean(); }
    private static void assertCode(Runnable command, ErrorCode code) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }
    @Configuration(proxyBeanMethods = false)
    static class Publication {
        @Bean MapperFactoryBean<StorePolicyMapper> policies(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate template) {
            var factory = new MapperFactoryBean<>(StorePolicyMapper.class);
            factory.setSqlSessionTemplate(template);
            return factory;
        }
        @Bean MapperFactoryBean<ServiceCalendarMapper> calendars(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate template) {
            var factory = new MapperFactoryBean<>(ServiceCalendarMapper.class);
            factory.setSqlSessionTemplate(template);
            return factory;
        }
        @Bean MapperFactoryBean<ActionLogMapper> actions(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate template) {
            var factory = new MapperFactoryBean<>(ActionLogMapper.class);
            factory.setSqlSessionTemplate(template);
            return factory;
        }
        @Bean StoreOperationService publication(StorePolicyMapper policies, ActionLogMapper actions,
            ServiceCalendarMapper calendars,
                IdempotencyService idem, @Qualifier("foodOutboxPublisher") OutboxPublisher publisher,
                ObjectMapper json, PlatformTransactionManager manager, Signals signals, DataSource source) {
            OutboxPublisher checked = (event, options) -> {
                var receipt = publisher.publish(event, options);
                if (signals.afterFlush.get()) TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override public void beforeCommit(boolean readOnly) {
                                assertThat(new JdbcTemplate(source).queryForObject(
                                        "SELECT COUNT(*) FROM sys_outbox", Integer.class)).isOne();
                                throw new IllegalStateException("after-policy-event-flush");
                            }
                        });
                return receipt;
            };
            return new StoreOperationServiceImpl(policies, actions, calendars, idem, checked, json, manager,
                    Clock.fixed(Instant.parse("2026-10-20T15:30:00Z"), ZoneOffset.UTC));
        }
        @Bean Signals signals() { return new Signals(); }
        @Bean
        @ConditionalOnProperty(name = "test.policy.source", havingValue = "true", matchIfMissing = true)
        UnifiedOutboxFacade facade(DataSource source, PlatformTransactionManager manager,
                ObjectMapper json, Environment env) {
            var facade = new UnifiedOutboxFacade(json);
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, source, manager, json), List.of());
            if (env.getProperty("test.policy.archive", Boolean.class, true)) {
                facade.setArchiveOnly(Set.of("PLATFORM:FOOD_STORE_POLICY_PUBLISHED",
                    "PLATFORM:FOOD_SERVICE_CALENDAR_SAVED", "PLATFORM:FOOD_TABLE_AREA_CHANGED", "PLATFORM:FOOD_TABLE_CHANGED", "PLATFORM:FOOD_DINING_SESSION_CHANGED", "PLATFORM:FOOD_CHECK_CHANGED"));
            }
            return facade;
        }
    }
}
