package ai.neargo.shop.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher;
import ai.neargo.shop.pay.entitlement.service.impl.EntitlementPostingServiceImpl;
import ai.neargo.shop.pay.entitlement.service.EntitlementSourceFence;
import ai.neargo.shop.pay.mapper.entitlement.PayEntSourceFenceMapper;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort.SourceDecision;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseWriter;
import ai.neargo.shop.pay.entitlement.service.impl.EntitlementLifecycleServiceImpl;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntLedgerMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntPeriodBucketMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseAllocationMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseProposalMapper;
import ai.neargo.shop.pay.mapper.entitlement.PayEntOutboxMapper;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort.AuthorizationDecision;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort.AuthorizationState;
import ai.neargo.shop.spi.entitlement.SourceRef;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.time.Instant;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

/** Actual pay writer/proxies/optimistic mapper + native constraints. Never connects to the configured shop DB. */
@EnabledIfEnvironmentVariable(named = "USE_NATIVE_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
class EntitlementUseNativeMysqlTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    @Test
    void nativeUseTransactionsKeepBalancesDaysAndImmutableFactsAtomic() throws Exception {
        String url = System.getenv("USE_NATIVE_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/use_native_fixture\\?.*");
        var ds = new DriverManagerDataSource(url, "root", "");
        var jdbc = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V434__entitlement_grants_and_ledger.sql"),
                new ClassPathResource("db/migration/V429__pay_entitlement_event_outbox_inbox.sql"),
                new ClassPathResource("db/migration/V436__entitlement_use_and_daily_quota.sql"),
                new ClassPathResource("db/migration/V437__pay_entitlement_source_fence.sql")).execute(ds);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        var plugin = new MybatisPlusInterceptor();
        plugin.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        factory.setPlugins(plugin);
        var json = new ObjectMapper();
        var definition = new EntitlementDefinition("Daily swim", "PAID_CARD", "MERCHANT", "CNY", "SERVICE_UNITS",
                null, null, null, null, null, "PERIOD_DAILY", null, 2,
                new Validity("RELATIVE", null, null, 30, "FIRST_USE", "ROLLING_DAYS", "UTC"),
                new RedemptionScope("ALL", List.of()), List.of("PAID_ORDER"));
        jdbc.update("INSERT INTO ent_instance(tenant_no,instance_no,template_version_no,grant_no,source_unit_key,"
                + "unit_index,user_no,entity_no,kind,status,remaining_units,reserved_units,rule_snapshot,asset_seq,"
                + "created_at,updated_at,created_by,updated_by) VALUES ('MAIN','I1','V1','G1',REPEAT('a',64),1,"
                + "'U1','M1','PAID_CARD','PENDING_ACTIVATION',NULL,0,?,1,?,?, 'TEST','TEST')",
                json.writeValueAsString(definition), LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
                LocalDateTime.now(ZoneOffset.UTC));
        try (var beans = new AnnotationConfigApplicationContext()) {
            beans.register(Transactions.class);
            beans.registerBean("payTxManager", DataSourceTransactionManager.class,
                    () -> new DataSourceTransactionManager(ds));
            beans.registerBean(ObjectMapper.class, () -> json);
            for (var mapper : List.of(EntInstanceMapper.class, EntLedgerMapper.class, EntPeriodBucketMapper.class,
                    EntUsageMapper.class, EntUseAllocationMapper.class, EntUseIntentMapper.class,
                    EntUseProposalMapper.class, PayEntOutboxMapper.class, PayEntSourceFenceMapper.class)) {
                register(beans, mapper, factory.getObject());
            }
            beans.register(EntitlementUseWriter.class, EntitlementLifecycleServiceImpl.class,
                    EntitlementPostingServiceImpl.class, PayEntitlementEventPublisher.class, EntitlementSourceFence.class);
            beans.refresh();
            var writer = beans.getBean(EntitlementUseWriter.class);
            var caller = new EntitlementContext("MAIN", "TEST", "NATIVE", "b".repeat(64));
            var day = LocalDate.now(ZoneOffset.UTC);
            var late = prepared(definition, day, 0);
            var earlyCancel = new DecisionRef("EARLY-CANCEL", Instant.now(), 1, DecisionRef.DecisionType.USE_CANCEL);
            var negative = new SourceDecision(late.command().sourceRef(), SourceDecisionPort.State.CANCELLED,
                    earlyCancel, "U1", "M1", "S1", "CNY", 0L, late.source().orderSnapshotHash());
            assertThat(writer.cancelAbsent(caller, late.command().operationNo(), late.command().sourceRef(), negative))
                    .isTrue();
            // Model a request that passed source/auth preflight before cancellation, but entered pay locks later.
            assertThat(writer.prepare(caller, late).status()).isEqualTo(State.RELEASED);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_use_intent", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance", Integer.class)).isZero();
            var held = prepared(definition, day, 0);
            var work = new ArrayList<Callable<State>>();
            for (int i = 0; i < 20; i++) work.add(() -> writer.prepare(caller, held).status());
            try (var pool = Executors.newFixedThreadPool(6)) {
                for (var response : pool.invokeAll(work)) assertThat(response.get()).isEqualTo(State.HELD);
            }
            assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_period_bucket", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_use_allocation", Integer.class)).isOne();
            assertThatThrownBy(() -> jdbc.update("UPDATE ent_period_bucket SET used_units=1"))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE ent_use_intent SET command_snapshot='not-json'"))
                    .isInstanceOf(DataAccessException.class);
            var cancel = new DecisionRef("C1", Instant.now(), 1, DecisionRef.DecisionType.USE_CANCEL);
            finish(writer, caller, held, cancel, false);
            assertThat(jdbc.queryForObject("SELECT active_source_key FROM ent_use_intent", String.class)).isNull();
            var tomorrow = prepared(definition, day.plusDays(1), 2);
            writer.prepare(caller, tomorrow);
            var commit = new DecisionRef("D1", Instant.now(), 1, DecisionRef.DecisionType.USE_COMMIT);
            // Outbox insert must be in the same pay transaction; duplicate event is not used to hide failures.
            jdbc.execute("ALTER TABLE pay_ent_outbox ADD CONSTRAINT reject_usage "
                    + "CHECK (event_type<>'ENTITLEMENT_USED')");
            assertThatThrownBy(() -> finish(writer, caller, tomorrow, commit, true)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Entitlement event number already exists with different immutable facts");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_usage", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT activation_at FROM ent_instance", Timestamp.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_period_bucket WHERE service_day=?",
                    Integer.class, day.plusDays(1))).isEqualTo(2);
            jdbc.execute("ALTER TABLE pay_ent_outbox DROP CHECK reject_usage");
            var receipt = finish(writer, caller, tomorrow, commit, true);
            assertThat(receipt.status()).isEqualTo(State.COMMITTED);
            assertThat(finish(writer, caller, tomorrow, commit, true))
                    .isEqualTo(receipt);
            assertThat(jdbc.queryForObject("SELECT used_units FROM ent_period_bucket WHERE service_day=?",
                    Integer.class, day.plusDays(1))).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT used_units FROM ent_period_bucket WHERE service_day=?",
                    Integer.class, day)).isZero();
            assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_usage", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger", Integer.class)).isEqualTo(4);
        }
    }

    private HoldView finish(EntitlementUseWriter writer,
            EntitlementContext context, Prepared prepared, DecisionRef ref, boolean commit) {
        var c = prepared.command();
        var truth = new SourceDecision(c.sourceRef(), commit
                ? SourceDecisionPort.State.COMMITTED
                : SourceDecisionPort.State.CANCELLED,
                ref, c.userNo(), c.entityNo(), c.storeNo(), c.currency(), 0L, prepared.source().orderSnapshotHash());
        return writer.finish(context, c.operationNo(), c.sourceRef(), ref, commit, truth);
    }

    private Prepared prepared(EntitlementDefinition rule, LocalDate day, long revision) {
        String op = UUID.randomUUID().toString();
        var source = new SourceRef("CORE", "STORE_USE", op, null, null, null, 1);
        var items = List.of(new UseItem("L1", "G1", "Swimming", 2, 0));
        var c = new UseCommand(op, source, "U1", "M1", "S1", "CNY", AssetKind.PAID_CARD, "I1", null,
                day, 2, null, items, "AUTH1", null, revision, "PREVIEW1", null);
        var fact = new UseSourceFact("MAIN", source, "U1", "M1", "S1", "CNY", items,
                "c".repeat(64), "Pool", null, day);
        var auth = new AuthorizationDecision("AUTH1", AuthorizationState.AUTHORIZED, source, "U1", "M1", "S1",
                "CNY", 0L, "d".repeat(64), "c".repeat(64), Instant.now().plusSeconds(120));
        return new Prepared(c, fact, rule, auth, "d".repeat(64), day, 0, revision, List.of());
    }

    private static <T> void register(AnnotationConfigApplicationContext context, Class<T> mapper,
                                     SqlSessionFactory sessions) {
        context.registerBean(mapper.getSimpleName(), MapperFactoryBean.class, () -> {
            var bean = new MapperFactoryBean<T>(mapper);
            bean.setSqlSessionFactory(sessions);
            return bean;
        });
    }
}
