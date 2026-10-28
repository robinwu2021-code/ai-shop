package ai.neargo.shop.beauty.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.config.BeautyPersistenceConfiguration;
import ai.neargo.shop.beauty.config.BeautySchemaValidator;
import ai.neargo.shop.beauty.dto.AppointmentAssignmentVO;
import ai.neargo.shop.beauty.dto.AppointmentPlanItemVO;
import ai.neargo.shop.beauty.dto.WorkOrderRequirementVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskSnapshotVO;
import ai.neargo.shop.beauty.entity.BtyServiceTask;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ReceptionIdentity.VerifiedIdentity;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.policy.ServiceExecutionSchemaVerifier;
import ai.neargo.shop.beauty.service.impl.AppointmentMatching;
import ai.neargo.shop.beauty.service.impl.AppointmentPersistence;
import ai.neargo.shop.beauty.service.impl.FrozenAppointmentScope;
import ai.neargo.shop.beauty.service.impl.HoldEvidence;
import ai.neargo.shop.beauty.service.impl.ServiceExecutionFenceCoordinator;
import ai.neargo.shop.beauty.service.impl.ServiceSourceObservation;
import ai.neargo.shop.beauty.service.impl.ServiceSubmissionQuery;
import ai.neargo.shop.beauty.service.impl.ServiceTaskExecutionServiceImpl;
import ai.neargo.shop.beauty.service.impl.WorkOrderCoordinator;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualSourceFacts;
import ai.neargo.shop.spi.trade.ServiceExecutionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.FreezeStart;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntent;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntentLookup;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Native beauty V1-V10 with real mappers/coordinator and actual Task62 merchant implementations.
 * Unified B must add current shop-merchant classes/resources to this neutral-module test classpath.
 * The pre-V532 auth tables are a disclosed structural fixture; exact V532/V533 bytes create personnel tables.
 * Beauty local originals are typed persisted fixtures, not claims of Core/stock/resource success.
 */
public class ManualExecutionSourceIT {
    private static final Instant AT = Instant.parse("2026-10-26T12:00:00.123Z");
    private static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);
    private static final LocalDateTime T0 = LocalDateTime.ofInstant(AT, ZoneOffset.UTC);
    private static final String HASH = "a".repeat(64);
    private static final ExecutionScope SCOPE = new ExecutionScope("MAIN", "E", "S");
    private final ObjectMapper json = new ObjectMapper();
    private MysqlDataSource source;
    private JdbcTemplate sql;
    private String bookingPlan;
    private String executionSnapshot;

    @Configuration(proxyBeanMethods = false)
    @MapperScan("ai.neargo.shop.merchant.mapper")
    static class ActualMerchantMappers {}

    @BeforeEach
    public void database() throws Exception {
        String adminUrl = System.getProperty("beauty.test.admin-url");
        assertNotNull(adminUrl, "Unified B owns the disposable native database and combined owner classpath");
        assertThat(adminUrl).startsWith("jdbc:mysql://127.0.0.1:");
        var admin = new MysqlDataSource();
        admin.setUrl(adminUrl); admin.setUser("root"); admin.setPassword(password());
        String database = "execution_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        source = new MysqlDataSource();
        source.setUrl(adminUrl.replace("/mysql?", "/" + database + "?"));
        source.setUser("root"); source.setPassword(password());
        new BeautySchemaValidator().initialize(source, "migrate");
        assertThat(new ServiceExecutionSchemaVerifier(source).installation())
                .isEqualTo(ServiceExecutionSchemaVerifier.Installation.INSTALLED);
        sql = new JdbcTemplate(source);
        authFixture();
        localSourceFixture();
    }

    private static String password() { return System.getenv().getOrDefault("BEAUTY_NATIVE_MYSQL_PASSWORD", ""); }
    private static Class<?> actual(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException missing) {
            throw new AssertionError("Unified B requires actual current owner class: " + name, missing);
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class,
                BeautyPersistenceConfiguration.class, ActualMerchantMappers.class,
                actual("ai.neargo.shop.merchant.port.MerchantOperatorAccessPortImpl"),
                actual("ai.neargo.shop.merchant.port.PersonnelOperatorFactsPortImpl"))
                .withBean(AuditMetaObjectHandler.class).withBean(ObjectMapper.class, () -> json)
                .withBean(Clock.class, () -> CLOCK)
                .withPropertyValues("shop.beauty.enabled=true", "spring.datasource.url=" + source.getUrl(),
                        "spring.datasource.username=root", "spring.datasource.password=" + password());
    }

    private record Harness(ServiceTaskExecutionServiceImpl service, ServiceSourceObservation observations,
            ServiceExecutionFenceCoordinator coordinator, AppointmentPersistence db) {}

    private Harness harness(ApplicationContext c) {
        var db = new AppointmentPersistence(c.getBean(BeautyMappers.AppointmentMapper.class),
                c.getBean(BeautyMappers.AppointmentPreparationMapper.class),
                c.getBean(BeautyMappers.AppointmentOperationMapper.class),
                c.getBean(BeautyMappers.AppointmentCurrentMapper.class),
                c.getBean(BeautyMappers.AppointmentArrangementMapper.class),
                c.getBean(BeautyMappers.AppointmentResourceIntentMapper.class),
                c.getBean(BeautyMappers.AppointmentResourceFenceMapper.class),
                c.getBean(BeautyMappers.AppointmentItemMapper.class), c.getBean(BeautyMappers.AppointmentAssignmentMapper.class),
                c.getBean(BeautyMappers.ActionLogMapper.class), json, c.getBean(PlatformTransactionManager.class));
        var observations = c.getBean(BeautyMappers.SourceObservationMapper.class);
        var guards = c.getBean(BeautyMappers.SourceAllocationGuardMapper.class);
        var orders = c.getBean(BeautyMappers.WorkOrderMapper.class);
        var tasks = c.getBean(BeautyMappers.ServiceTaskMapper.class);
        var starts = c.getBean(BeautyMappers.ServiceSubmissionMapper.class);
        var bindings = c.getBean(BeautyMappers.TradeBindingMapper.class);
        var fences = c.getBean(BeautyMappers.ServiceExecutionFenceMapper.class);
        var actualSchema = new ServiceExecutionSchemaVerifier(c.getBean("dataSource", DataSource.class));
        var coordinator = new ServiceExecutionFenceCoordinator(actualSchema, fences, observations, guards, orders,
                tasks, starts, bindings, json, CLOCK);
        var query = new ServiceSubmissionQuery(db, orders, c.getBean(BeautyMappers.WorkOrderContextMapper.class), tasks,
                c.getBean(BeautyMappers.ReceptionOperationMapper.class), starts,
                c.getBean(BeautyMappers.ServiceDefinitionMapper.class),
                c.getBean(BeautyMappers.ServiceDefinitionVersionMapper.class));
        var service = new ServiceTaskExecutionServiceImpl(db, query,
                new ServiceExecutionAccess(c.getBeanProvider(PersonnelOperatorFactsPort.class), CLOCK), actualSchema,
                observations, guards, orders, tasks, bindings, fences, starts,
                c.getBean(BeautyMappers.GuestClaimReceiptMapper.class), c.getBeanProvider(ServiceExecutionPort.class), CLOCK);
        var observer = new ServiceSourceObservation(observations, c.getBeanProvider(CheckoutPort.class),
                c.getBeanProvider(ManualServiceAdmissionPort.class), c.getBean(PlatformTransactionManager.class),
                json, CLOCK, coordinator);
        return new Harness(service, observer, coordinator, db);
    }

    private <T> T human(String actor, String kind, Supplier<T> body) {
        return DelegatedIdentityContext.call(new DelegatedIdentity("MAIN", "MERCHANT", kind, actor), body);
    }
    private FreezeStart start(String key) { return new FreezeStart(SCOPE, "WO", "TASK", 0, 1, 1, key); }
    private StartIntent freeze(Harness h) { return human("100", "MCH", () -> h.service().freeze(start("START"))); }
    private int count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    @Test
    void freezeCommitsBeforeCoreIo() {
        var calls = new AtomicInteger();
        var failure = (ServiceExecutionPort) Proxy.newProxyInstance(ServiceExecutionPort.class.getClassLoader(),
                new Class<?>[] {ServiceExecutionPort.class}, (proxy, method, args) -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(count("bty_service_execution_fence")).isOne();
                    assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_service_submission WHERE action='START'",
                            Integer.class)).isOne();
                    calls.incrementAndGet();
                    throw new UncheckedIOException(new IOException("explicit lost owner response fixture"));
                });
        runner().withBean(ServiceExecutionPort.class, () -> failure).run(c -> {
            assertThat(c).hasNotFailed(); var h = harness(c);
            var result = human("100", "MCH", () -> h.service().prepareTask(start("START")));
            assertThat(result.grant()).isNull();
            assertThat(result.pendingReason()).isEqualTo("RESOURCE_EXECUTION_UNAVAILABLE");
            assertThat(calls).hasValue(1);
            assertThat(sql.queryForObject("SELECT intent_state FROM bty_service_execution_fence", String.class))
                    .isEqualTo("FROZEN");
            assertThat(sql.queryForObject("SELECT started_at FROM bty_service_task", LocalDateTime.class)).isNull();
        });
    }

    @Test
    void selfNeedsNoOrderView() {
        runner().run(c -> {
            var h = harness(c); var intent = freeze(h);
            assertThat(intent.personnel().personnelNo()).isEqualTo("P");
            assertThat(intent.personnel().assignmentNo()).isEqualTo("AS");
            assertThat(sql.queryForObject("SELECT perms FROM mch_role WHERE role_code='TECH'", String.class))
                    .doesNotContain("order:view");
            assertThat(c.getBean(PersonnelOperatorFactsPort.class).getClass().getName())
                    .isEqualTo("ai.neargo.shop.merchant.port.PersonnelOperatorFactsPortImpl");
            assertThat(intent.binding().unitOrdinal()).isOne();
        });
    }

    @Test
    void integerOneLegacyBindingAdoptsOnce() {
        sql.update("UPDATE bty_service_task SET qty=1.000000");
        runner().run(c -> {
            var h = harness(c); var first = freeze(h); var second = freeze(h);
            assertThat(second.binding()).isEqualTo(first.binding());
            assertThat(count("bty_service_execution_fence")).isOne();
            assertThat(first.continuity().state()).isEqualTo("READY_UNCHANGED");
        });
    }

    @Test
    void legacyDecimalAndAggregatedTasksRemainClosed() {
        runner().run(c -> {
            var h = harness(c);
            for (String quantity : List.of("0.5", "1.5", "2")) {
                sql.update("UPDATE bty_service_task SET qty=?", new BigDecimal(quantity));
                assertThatThrownBy(() -> freeze(h)).isInstanceOf(BizException.class);
                assertThat(count("bty_service_execution_fence")).isZero();
            }
        });
    }

    @Test
    void wrongGroupOrSourceLineNeverFreezes() {
        sql.update("UPDATE bty_trade_binding SET source_line_ref='FOREIGN'");
        runner().run(c -> {
            assertThatThrownBy(() -> freeze(harness(c))).isInstanceOf(BizException.class);
            assertThat(count("bty_service_execution_fence")).isZero();
        });
    }

    @Test
    void foreignHoldAndAmbiguousGroupRejectBeforeFence() {
        sql.update("UPDATE bty_appointment_assignment SET hold_no='FOREIGN'");
        runner().run(c -> {
            assertThatThrownBy(() -> freeze(harness(c))).isInstanceOf(BizException.class);
            assertThat(count("bty_service_execution_fence")).isZero();
        });
    }

    @Test
    void frozenPlanRetainsBookingHashAndResourceOrdinal() {
        runner().run(c -> {
            var intent = freeze(harness(c));
            var plan = json.readValue(intent.frozenPlan(), ServiceTaskExecutionServiceImpl.ExecutionPlan.class);
            assertThat(plan.bookingPlan()).isEqualTo(bookingPlan);
            assertThat(plan.bookingPlanHash()).isEqualTo(ResourcePlanContract.hash(bookingPlan));
            assertThat(plan.taskSnapshotHash()).isEqualTo(IdempotencyScope.requestHash(executionSnapshot));
            assertThat(ResourcePlanContract.decode(plan.taskPlan()).demands().getFirst().sourceLineRef())
                    .isEqualTo("ITEM:REQ:0");
            assertThat(intent.binding().unitOrdinal()).isOne();
        });
    }

    @Test
    void revokedPermissionOrLinkCannotCreateNewStart() {
        runner().run(c -> {
            var h = harness(c);
            sql.update("UPDATE mch_role SET perms='[]'");
            assertThatThrownBy(() -> freeze(h)).isInstanceOf(BizException.class);
            sql.update("UPDATE mch_role SET perms='[\"biz:service:task:self\"]'");
            sql.update("UPDATE mch_personnel_account SET deleted=1");
            assertThatThrownBy(() -> freeze(h)).isInstanceOf(BizException.class);
            assertThat(count("bty_service_execution_fence")).isZero();
        });
    }

    @Test
    void ownerWithoutPersonCannotActAsTechnician() {
        runner().run(c -> {
            var h = harness(c);
            assertThatThrownBy(() -> human("OWNER", "MCH", () -> h.service().freeze(start("OWNER"))))
                    .isInstanceOf(BizException.class);
            assertThat(count("bty_service_execution_fence")).isZero();
        });
    }

    @Test
    void equalActorNumbersKeepSeparateNamespaceAndForeignStoreClosed() {
        runner().run(c -> {
            var h = harness(c);
            assertThatThrownBy(() -> human("100", "USR", () -> h.service().freeze(start("USR"))))
                    .isInstanceOf(BizException.class);
            var foreign = new FreezeStart(new ExecutionScope("MAIN", "E", "OTHER"), "WO", "TASK", 0, 1, 1, "F");
            assertThatThrownBy(() -> human("100", "MCH", () -> h.service().freeze(foreign)))
                    .isInstanceOf(BizException.class);
            assertThat(count("bty_service_execution_fence")).isZero();
        });
    }

    @Test
    void changedKeyCannotReplacePermanentTaskOrRecycleTerminalOriginal() {
        runner().run(c -> {
            var h = harness(c); var first = freeze(h);
            assertThatThrownBy(() -> human("100", "MCH", () -> h.service().freeze(start("CHANGED"))))
                    .isInstanceOf(BizException.class);
            sql.update("UPDATE bty_service_execution_fence SET intent_state='REJECTED'");
            assertThat(freeze(h).binding()).isEqualTo(first.binding());
            assertThat(count("bty_service_execution_fence")).isOne();
            assertThatThrownBy(() -> sql.update("DELETE FROM bty_service_execution_fence"))
                    .isInstanceOf(RuntimeException.class);
        });
    }

    @Test
    void unitFenceSurvivesCancellationAndRecreation() {
        runner().run(c -> {
            var h = harness(c); var first = freeze(h);
            sql.update("UPDATE bty_service_task SET status='VOIDED',voided_qty=1");
            assertThat(h.service().inspectOriginal(new StartIntentLookup(first.binding())).binding())
                    .isEqualTo(first.binding());
            assertThatThrownBy(() -> sql.update("UPDATE bty_service_execution_fence SET task_no='RECREATED'"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count("bty_service_execution_fence")).isOne();
        });
    }

    @Test
    void providerAbsenceRetainsOriginalWithoutInventingGrant() {
        runner().run(c -> {
            var h = harness(c);
            var result = human("100", "MCH", () -> h.service().prepareTask(start("START")));
            assertThat(result.grant()).isNull();
            assertThat(result.pendingReason()).isEqualTo("RESOURCE_EXECUTION_UNAVAILABLE");
            assertThat(sql.queryForObject("SELECT observed_grant_no FROM bty_service_execution_fence", String.class))
                    .isNull();
        });
    }

    @Test
    void currentAuthorizationIsRepeatedAfterSlowPreflight() {
        var observations = new AtomicInteger();
        runner().withInitializer(application -> application.getBeanFactory().addBeanPostProcessor(
                new BeanPostProcessor() {
                    @Override public Object postProcessAfterInitialization(Object bean, String name) {
                        if (!(bean instanceof PersonnelOperatorFactsPort delegate)) return bean;
                        return (PersonnelOperatorFactsPort) query -> {
                            var result = delegate.current(query);
                            if (observations.incrementAndGet() == 1) sql.update("UPDATE mch_role SET perms='[]'");
                            return result;
                        };
                    }
                })).run(c -> {
                    assertThatThrownBy(() -> freeze(harness(c))).isInstanceOf(BizException.class);
                    assertThat(count("bty_service_execution_fence")).isZero();
                });
    }

    @Test
    void staleExecutionEpochCannotApplyOldObservation() {
        runner().run(c -> {
            var h = harness(c); var intent = freeze(h);
            var mapper = c.getBean(BeautyMappers.ServiceExecutionFenceMapper.class);
            h.db().tx(() -> {
                var row = mapper.lockOriginal("E", "S", intent.binding().executionNo());
                long oldVersion = row.getVersion(), oldEpoch = row.getObservationEpoch();
                assertThat(mapper.claimExecutionObservation(row, T0.plusSeconds(30), T0, "READ")).isOne();
                row.setUpdatedAt(T0); row.setUpdatedBy("READ"); row.setNextRetryAt(T0.plusSeconds(30));
                assertThat(mapper.applyExecutionObservation(row, oldVersion, row.getSourceFenceRevision(), oldEpoch))
                        .isZero();
                return null;
            });
            assertThat(sql.queryForObject("SELECT observed_grant_no FROM bty_service_execution_fence", String.class))
                    .isNull();
        });
    }

    @Test
    void installedDamageBlocksLocalGuardsEvenWithProviderAbsent() {
        runner().run(c -> {
            var h = harness(c); freeze(h);
            sql.execute("DROP TRIGGER trg_bsef_no_delete");
            assertThatThrownBy(() -> h.db().tx(() -> {
                h.coordinator().beforeScopeMutationLocked("E", "S", "WO"); return null;
            })).isInstanceOf(BizException.class);
            assertThat(count("bty_service_execution_fence")).isOne();
        });
    }

    @Test
    void originalInspectionNeedsNoStoredHumanReplay() {
        runner().run(c -> {
            var h = harness(c); var original = freeze(h);
            sql.update("UPDATE mch_role SET perms='[]'");
            var inspected = h.service().inspectOriginal(new StartIntentLookup(original.binding()));
            assertThat(inspected.binding()).isEqualTo(original.binding());
            assertThatThrownBy(() -> human("100", "MCH", () -> h.service().inspect(new StartIntentLookup(original.binding()))))
                    .isInstanceOf(BizException.class);
        });
    }

    @Test
    void twoTasksSerializeLowestUnusedOrdinalsAcrossCompleteHistory() {
        secondTaskFixture();
        runner().run(c -> {
            var h = harness(c);
            var entered = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var one = pool.submit(() -> {
                    entered.await();
                    return human("100", "MCH", () -> h.service().freeze(start("FIRST")));
                });
                var two = pool.submit(() -> {
                    entered.await();
                    return human("100", "MCH", () -> h.service().freeze(
                            new FreezeStart(SCOPE, "WO", "TASK2", 0, 1, 1, "SECOND")));
                });
                entered.countDown();
                try {
                    var originals = List.of(one.get(8, TimeUnit.SECONDS), two.get(8, TimeUnit.SECONDS));
                    assertThat(originals.stream().map(i -> i.binding().unitOrdinal()).collect(Collectors.toSet()))
                            .isEqualTo(Set.of(1, 2));
                    assertThat(count("bty_service_execution_fence")).isEqualTo(2);
                } catch (Exception failed) { throw new AssertionError(failed); }
            }
        });
    }

    @Test
    void terminalOriginalDoesNotDonateItsOrdinalToAnotherTask() {
        secondTaskFixture();
        runner().run(c -> {
            var h = harness(c); var first = freeze(h);
            sql.update("UPDATE bty_service_execution_fence SET intent_state='REJECTED'");
            var second = human("100", "MCH", () -> h.service().freeze(
                    new FreezeStart(SCOPE, "WO", "TASK2", 0, 1, 1, "SECOND")));
            assertThat(first.binding().unitOrdinal()).isOne();
            assertThat(second.binding().unitOrdinal()).isEqualTo(2);
            assertThat(count("bty_service_execution_fence")).isEqualTo(2);
        });
    }

    @Test
    void unsupportedDirectPlanAndStartedTaskRemainClosed() {
        runner().run(c -> {
            var h = harness(c);
            sql.update("UPDATE bty_service_task SET started_at=?,status='IN_SERVICE'", T0);
            assertThatThrownBy(() -> freeze(h)).isInstanceOf(BizException.class);
            sql.update("UPDATE bty_service_task SET started_at=NULL,status='PENDING'");
            var original = json.readValue(executionSnapshot, WorkOrderCoordinator.TaskExecutionSnapshot.class);
            var direct = new WorkOrderCoordinator.TaskExecutionSnapshot(1, "UNASSIGNED", original.taskSnapshot(),
                    List.of(), null, null);
            sql.update("UPDATE bty_service_task SET execution_snapshot=?", json.writeValueAsString(direct));
            assertThatThrownBy(() -> freeze(h)).isInstanceOf(BizException.class);
            assertThat(count("bty_service_execution_fence")).isZero();
        });
    }

    @Test
    void cancelledOwnerReceiptSuspendsFrozenOriginal() {
        observationScenario("CANCELLED");
    }

    @Test
    void awaitingReceiptOwnerResultSuspendsPreviouslyAdmittedOriginal() {
        observationScenario("AWAITING_RECEIPT");
    }

    /** Called by the exact observation test methods; only returned owner facts may create a suspension. */
    public void observationScenario(String scenario) {
        try { database(); }
        catch (Exception failed) { throw new AssertionError(failed); }
        runner().withBean(ManualServiceAdmissionPort.class, () -> sourceOwner(scenario)).run(c -> {
            var h = harness(c); var original = freeze(h);
            var canonical = new ServiceSourceObservation.ManualCanonical("E", "S",
                    new CustomerParty("USER", "BUYER", null, null), "OP", "ORDER", "SOURCE");
            h.observations().invalidateManual(canonical, "READ");
            var pending = h.service().inspectOriginal(new StartIntentLookup(original.binding()));
            assertThat(pending.continuity().state()).isEqualTo("PENDING");
            assertThat(pending.suspension()).isNull();
            if ("CAS_FAIL".equals(scenario)) {
                sql.update("UPDATE bty_service_submission SET status='RECEIVED' WHERE action='START'");
                assertThatThrownBy(() -> h.observations().observeManual(canonical, "READ"))
                        .isInstanceOf(BizException.class);
                assertThat(sql.queryForObject("SELECT intent_state FROM bty_service_execution_fence", String.class))
                        .isEqualTo("FROZEN");
                assertThat(sql.queryForObject("SELECT suspension_proof_hash FROM bty_service_execution_fence", String.class))
                        .isNull();
                assertThat(sql.queryForObject("SELECT manual_revision FROM bty_source_observation", Long.class))
                        .isEqualTo(1L);
                return;
            }
            h.observations().observeManual(canonical, "READ");
            var observed = h.service().inspectOriginal(new StartIntentLookup(original.binding()));
            if (List.of("CONTRADICTION", "CANCELLED", "AWAITING_RECEIPT").contains(scenario)) {
                assertThat(observed.continuity().state()).isEqualTo("SUSPENDED");
                assertThat(observed.suspension()).isNotNull();
                assertThat(sql.queryForObject("SELECT status FROM bty_service_submission WHERE action='START'",
                        String.class)).isEqualTo("EXECUTION_SUSPENDED");
                var cause = observed.suspension();
                // A subsequent unchanged completion cannot erase the first genuine contradiction.
                h.observations().observeManual(canonical, "READ");
                assertThat(h.service().inspectOriginal(new StartIntentLookup(original.binding())).suspension())
                        .isEqualTo(cause);
            } else {
                assertThat(observed.suspension()).isNull();
                assertThat(observed.continuity().state()).isEqualTo("UNKNOWN".equals(scenario)
                        ? "PENDING" : "READY_UNCHANGED");
                assertThat(observed.binding()).isEqualTo(original.binding());
                assertThat(sql.queryForObject("SELECT intent_state FROM bty_service_execution_fence", String.class))
                        .isEqualTo("FROZEN");
            }
        });
    }

    /** Two actual observation tickets ensure a late unchanged reply cannot clear a newer durable cause. */
    public void staleCompletionScenario() {
        try { database(); } catch (Exception failed) { throw new AssertionError(failed); }
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var owner = (ManualServiceAdmissionPort) Proxy.newProxyInstance(ManualServiceAdmissionPort.class.getClassLoader(),
                new Class<?>[] {ManualServiceAdmissionPort.class}, (proxy, method, args) -> {
                    if (!"source".equals(method.getName())) throw new AssertionError("No source mutation");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    int call = calls.incrementAndGet();
                    if (call == 1) {
                        entered.countDown();
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    }
                    return facts(call == 1 ? 1 : 2, call == 1 ? "SKU" : "CHANGED", call == 1 ? HASH : "b".repeat(64));
                });
        runner().withBean(ManualServiceAdmissionPort.class, () -> owner).run(c -> {
            var h = harness(c); var original = freeze(h);
            var canonical = new ServiceSourceObservation.ManualCanonical("E", "S",
                    new CustomerParty("USER", "BUYER", null, null), "OP", "ORDER", "SOURCE");
            try (var pool = Executors.newSingleThreadExecutor()) {
                var old = pool.submit(() -> h.observations().observeManual(canonical, "OLD_READ"));
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    h.observations().observeManual(canonical, "NEW_READ");
                    var cause = h.service().inspectOriginal(new StartIntentLookup(original.binding())).suspension();
                    assertThat(cause).isNotNull();
                    release.countDown(); old.get(5, TimeUnit.SECONDS);
                    assertThat(h.service().inspectOriginal(new StartIntentLookup(original.binding())).suspension())
                            .isEqualTo(cause);
                } catch (Exception failed) { throw new AssertionError(failed); }
                finally { release.countDown(); }
            }
        });
    }

    private ManualSourceFacts facts(long revision, String sku, String hash) {
        return new ManualSourceFacts(new CheckoutPort.OperationRef("BEAUTY", "OP"), "ORDER", "SOURCE",
                new CustomerParty("USER", "BUYER", null, null), "E", "S", "MANUAL_OFFLINE", revision, hash,
                1, 1, "ADMITTED", List.of(new ManualServiceAdmissionPort.ManualSourceLine("LINE", "CLIENT", "G",
                        sku, 1, 1, "COUNT")), AT, null, null, null, "CONSUMER_SESSION");
    }

    private ManualServiceAdmissionPort sourceOwner(String scenario) {
        var calls = new AtomicInteger();
        return (ManualServiceAdmissionPort) Proxy.newProxyInstance(ManualServiceAdmissionPort.class.getClassLoader(),
                new Class<?>[] {ManualServiceAdmissionPort.class}, (proxy, method, args) -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    if (!"source".equals(method.getName())) throw new AssertionError("No source-owner mutation");
                    int call = calls.incrementAndGet();
                    boolean unknown = "UNKNOWN".equals(scenario);
                    boolean withdrawn = List.of("CANCELLED", "AWAITING_RECEIPT").contains(scenario) && call == 1;
                    boolean changed = List.of("CONTRADICTION", "CAS_FAIL").contains(scenario) && call == 1;
                    return new ManualSourceFacts(new CheckoutPort.OperationRef("BEAUTY", "OP"), "ORDER", "SOURCE",
                            new CustomerParty("USER", "BUYER", null, null), "E", "S", "MANUAL_OFFLINE",
                            unknown ? 0 : changed ? 2 : 1, unknown ? null : changed ? "b".repeat(64) : HASH,
                            1, 1, unknown ? "UNKNOWN" : withdrawn ? scenario : "ADMITTED",
                            unknown || withdrawn ? List.of() : List.of(
                                    new ManualServiceAdmissionPort.ManualSourceLine("LINE", "CLIENT", "G",
                                            changed ? "OTHER_SKU" : "SKU", 1, 1, "COUNT")), AT,
                            null, null, null, "CONSUMER_SESSION");
                });
    }

    private void authFixture() throws Exception {
        // Same disclosed structural auth baseline used by PersonnelOperatorFactsIT, with real permission SQL.
        sql.execute("CREATE TABLE mch_entity(tenant_no VARCHAR(32),entity_no VARCHAR(32),status VARCHAR(24),"
                + "archived_at DATETIME(3),deleted INT)");
        sql.execute("CREATE TABLE mch_store(tenant_no VARCHAR(32),entity_no VARCHAR(32),store_no VARCHAR(32),"
                + "status VARCHAR(24),deleted INT)");
        sql.execute("CREATE TABLE mch_account(tenant_no VARCHAR(32),entity_no VARCHAR(32),mch_account_no VARCHAR(32),"
                + "user_no VARCHAR(64),is_owner INT,status VARCHAR(24),deleted INT)");
        sql.execute("CREATE TABLE mch_role(tenant_no VARCHAR(32),entity_no VARCHAR(32),role_code VARCHAR(32),"
                + "perms JSON,deleted INT)");
        sql.execute("CREATE TABLE mch_store_role(tenant_no VARCHAR(32),mch_account_no VARCHAR(32),store_no VARCHAR(32),"
                + "role VARCHAR(32),deleted INT)");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V532__shared_personnel_foundation.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V533__shared_resource_identity.sql"));
        }
        sql.execute("INSERT INTO mch_entity VALUES('MAIN','E','ACTIVE',NULL,0)");
        sql.execute("INSERT INTO mch_store VALUES('MAIN','E','S','ACTIVE',0)");
        sql.execute("INSERT INTO mch_account VALUES('MAIN','E','100','200',0,'ACTIVE',0),"
                + "('MAIN','E','300','100',0,'ACTIVE',0),('MAIN','E','OWNER',NULL,1,'ACTIVE',0)");
        sql.execute("INSERT INTO mch_role VALUES('MAIN','E','TECH','[\"biz:service:task:self\"]',0)");
        sql.execute("INSERT INTO mch_store_role VALUES('MAIN','100','S','TECH',0),('MAIN','300','S','TECH',0)");
        seed("mch_personnel", "personnel_no", "P", "name", "Technician", "status", "ACTIVE");
        seed("mch_personnel", "personnel_no", "Q", "name", "Other", "status", "ACTIVE");
        seed("mch_personnel_store", "assignment_no", "AS", "personnel_no", "P", "store_no", "S",
                "valid_from", T0.minusDays(1), "valid_to", T0.plusDays(1), "status", "ACTIVE", "public_visible", 0,
                "sort_order", 0);
        seed("mch_personnel_store", "assignment_no", "OTHER_AS", "personnel_no", "Q", "store_no", "S",
                "valid_from", T0.minusDays(1), "valid_to", T0.plusDays(1), "status", "ACTIVE", "public_visible", 0,
                "sort_order", 0);
        seed("mch_personnel_account", "link_no", "LINK", "personnel_no", "P", "mch_account_no", "100");
        seed("mch_personnel_account", "link_no", "OTHER_LINK", "personnel_no", "Q", "mch_account_no", "300");
    }

    private void localSourceFixture() {
        var start = AT.atOffset(ZoneOffset.UTC).plusDays(1);
        var end = start.plusMinutes(20);
        var assignment = new AppointmentAssignmentVO("ITEM", "REQ", "P", "RESOURCE", start, end);
        var definition = new WorkOrderTaskSnapshotVO(1, "G", "SKU", "DV", HASH, 20, 0, 0,
                List.of(new WorkOrderRequirementVO("REQ", "MAIN", "STAFF", "G", 1, 0, 0, 20, "ANY_QUALIFIED")),
                List.of(), List.of());
        var item = new AppointmentPlanItemVO("ITEM", "SKU", "G", "DV", 1, 0, 20, start, end, 1L, 1L);
        bookingPlan = ResourcePlanContract.encode(List.of(new ResourcePlanContract.Demand("RESOURCE", "ITEM:REQ:0", 1,
                List.of(new ResourcePlanContract.Interval(start.toInstant(), end.toInstant())))),
                List.of(new ResourcePlanContract.Qualification("RESOURCE", "ITEM:REQ:0", "G", "SKU", true)));
        String snapshot = json.writeValueAsString(new AppointmentMatching.Snapshot(List.of(item), List.of(assignment),
                0, null));
        var ownerBinding = new ResourceSourceAuthorityPort.Binding("MAIN", "E", "S", "BOOK_COMMAND",
                "BEAUTY_APPOINTMENT", "APPOINTMENT", 1, "CONFIRM", HASH, ResourcePlanContract.hash(bookingPlan));
        var hold = new ResourceBookingPort.Hold("HOLD", "CONFIRMED", 1, 1, 1, 1, ownerBinding.planHash());
        var evidence = new HoldEvidence(ownerBinding, hold, 1,
                IdempotencyScope.requestHash("PREVIEW", "HOLD", snapshot));
        var frozen = new FrozenAppointmentScope(List.of(item), List.of(definition), "PV", null, List.of(), evidence);
        var reference = new WorkOrderCoordinator.TaskReference("TASK", "SKU", "DV", BigDecimal.ONE, "COUNT");
        var scope = new WorkOrderCoordinator.WorkOrderScopeSnapshot(1, 1, HASH, "PV", null, frozen,
                List.of(assignment), List.of(reference));
        var open = new WorkOrderCoordinator.OpenSubmissionDraft(1, 1, HASH, new VerifiedIdentity("USER", "BUYER"),
                List.of(reference));
        executionSnapshot = json.writeValueAsString(new WorkOrderCoordinator.TaskExecutionSnapshot(1, "ASSIGNED",
                definition, List.of(assignment), start, end));
        seed("bty_service_definition", "definition_no", "DEF", "store_no", "S", "sku_no", "SKU",
                "current_definition_version_no", "DV", "status", "ACTIVE");
        seed("bty_service_definition_version", "definition_no", "DEF", "definition_version_no", "DV",
                "revision_index", 1, "duration_min_snapshot", 20, "buffer_before_min", 0, "buffer_after_min", 0,
                "steps_template", "[]", "notes", "[]", "schema_version", 1, "snapshot_hash", HASH);
        seed("bty_appointment", "appointment_no", "APPOINTMENT", "store_no", "S", "source", "PHONE",
                "status", "CHECKED_IN", "start_at", start.toLocalDateTime(), "end_at", end.toLocalDateTime(),
                "timezone", "UTC", "hold_no", "HOLD", "policy_version_no", "PV", "customer_ref", "BUYER");
        seed("bty_appointment_item", "appointment_item_no", "ITEM", "appointment_no", "APPOINTMENT", "sku_no",
                "SKU", "definition_version_no", "DV", "qty", 1, "uom", "COUNT", "sequence_no", 0,
                "execution_snapshot", "{}");
        seed("bty_appointment_assignment", "planned_assignment_no", "PA", "appointment_item_no", "ITEM",
                "appointment_revision", 1, "requirement_no", "REQ", "personnel_no", "P", "resource_no", "RESOURCE",
                "hold_no", "HOLD", "start_at", start.toLocalDateTime(), "end_at", end.toLocalDateTime(),
                "skill_snapshot", "{}");
        seed("bty_appointment_preparation", "preview_no", "PREVIEW", "store_no", "S", "actor_no", "100",
                "actor_realm", "MERCHANT", "actor_kind", "MCH", "expected_revision", 1, "policy_version_no", "PV",
                "start_at", start.toLocalDateTime(), "end_at", end.toLocalDateTime(), "timezone", "UTC",
                "frozen_plan", bookingPlan, "plan_hash", ownerBinding.planHash(), "snapshot", snapshot,
                "expires_at", end.toLocalDateTime());
        seed("bty_appointment_arrangement", "appointment_no", "APPOINTMENT", "arrangement_revision", 1,
                "preview_no", "PREVIEW", "hold_no", "HOLD", "snapshot", snapshot);
        seed("bty_appointment_current", "appointment_no", "APPOINTMENT", "arrangement_revision", 1);
        var accept = new ResourceBookingPort.Accept(new ResourceBookingPort.Scope("MAIN", "E", "S"), "CONFIRM",
                "BOOK_KEY", "BEAUTY_APPOINTMENT", "APPOINTMENT", 1, 1, null, bookingPlan);
        var receipt = new ResourceBookingPort.Receipt(ownerBinding, "RESOURCE_OWNER", "SUCCEEDED", 1, 1, null,
                bookingPlan, json.writeValueAsString(Map.of("holdNo", "HOLD", "status", "CONFIRMED", "ownerRevision", 1)),
                null);
        seed("bty_appointment_operation", "operation_no", "BOOK_OPERATION", "store_no", "S", "actor_no", "100",
                "actor_realm", "MERCHANT", "actor_kind", "MCH", "key_hash", HASH, "request_hash", HASH,
                "appointment_no", "APPOINTMENT", "preview_no", "PREVIEW", "action", "CREATE", "state", "SUCCEEDED",
                "accept_payload", json.writeValueAsString(accept), "receipt", json.writeValueAsString(receipt),
                "next_retry_at", T0);
        seed("bty_work_order", "work_order_no", "WO", "store_no", "S", "appointment_no", "APPOINTMENT",
                "customer_ref", "USER:BUYER", "status", "OPEN", "scope_revision", 1, "scope_frozen", 0);
        seed("bty_service_task", "task_no", "TASK", "work_order_no", "WO", "scope_revision", 1, "sku_no", "SKU",
                "definition_version_no", "DV", "appointment_item_no", "ITEM", "qty", 1, "done_qty", 0,
                "voided_qty", 0, "uom", "COUNT", "status", "PENDING", "binding_revision", 1,
                "execution_snapshot", executionSnapshot);
        seed("bty_work_order_context", "store_no", "S", "work_order_no", "WO", "identity_kind", "USER",
                "identity_ref", "BUYER", "source_reception_operation_no", "RECEPTION",
                "appointment_arrangement_revision", 1, "frozen_scope", json.writeValueAsString(scope));
        seed("bty_reception_operation", "store_no", "S", "operation_no", "RECEPTION", "actor_no", "100",
                "actor_realm", "MERCHANT", "actor_kind", "MCH", "endpoint", "BAPI-054", "action", "OPEN_WORK_ORDER",
                "key_hash", HASH, "request_hash", HASH, "work_order_no", "WO", "identity_kind", "USER",
                "identity_ref", "BUYER", "phase", "SUCCEEDED", "frozen_payload", "{}");
        seed("bty_service_submission", "store_no", "S", "submission_no", "OPEN", "work_order_no", "WO",
                "action", "OPEN", "request_key_hash", HASH,
                "payload_hash", IdempotencyScope.requestHash(json.writeValueAsString(open)), "status", "RECEIVED",
                "frozen_command", json.writeValueAsString(open));
        seed("bty_service_submission", "store_no", "S", "submission_no", "BIND", "work_order_no", "WO",
                "action", "BIND", "request_key_hash", "b".repeat(64), "payload_hash", HASH, "status", "BOUND",
                "frozen_command", "{}");
        String lines = json.writeValueAsString(List.of(new ServiceSourceObservation.ManualQuantityLine("LINE", "CLIENT",
                "G", "SKU", 1, 1, "COUNT")));
        seed("bty_source_observation", "store_no", "S", "source_kind", "STORE_ORDER", "source_no", "SOURCE",
                "checkout_caller", "BEAUTY", "checkout_operation_no", "OP", "order_no", "ORDER", "buyer_no", "BUYER",
                "state", "UNKNOWN", "failure_code", "MANUAL_OBSERVATION", "admission_kind", "MANUAL_OFFLINE",
                "customer_kind", "USER", "manual_epoch", 1, "manual_state", "READY", "manual_revision", 1,
                "manual_hash", HASH, "manual_verified_revision", 1, "manual_verified_hash", HASH,
                "manual_verified_lines", lines, "manual_verified_at", T0, "manual_observed_at", T0,
                "manual_admission_revision", 1, "manual_admission_state", "ADMITTED");
        seed("bty_source_allocation_guard", "guard_no", "GUARD", "source_kind", "STORE_ORDER", "source_no", "SOURCE",
                "source_line_ref", "LINE", "source_qty", 1, "allocated_qty", 1, "uom", "COUNT",
                "admission_kind", "MANUAL_OFFLINE", "customer_kind", "USER", "buyer_no", "BUYER",
                "manual_source_revision", 1, "manual_source_hash", HASH);
        seed("bty_trade_binding", "binding_no", "BINDING", "submission_no", "BIND", "task_no", "TASK",
                "binding_revision", 1, "source_kind", "STORE_ORDER", "source_no", "SOURCE", "source_line_ref", "LINE",
                "qty", 1, "uom", "COUNT", "guard_no", "GUARD", "admission_kind", "MANUAL_OFFLINE", "customer_kind",
                "USER", "buyer_no", "BUYER", "manual_source_revision", 1, "manual_source_hash", HASH);
    }

    /** Two distinct qty1 appointment items share one genuine source line with Q2; no synthetic grant is used. */
    private void secondTaskFixture() {
        var original = json.readValue(sql.queryForObject("SELECT frozen_scope FROM bty_work_order_context", String.class),
                WorkOrderCoordinator.WorkOrderScopeSnapshot.class);
        var item = original.frozen().items().getFirst();
        var second = new AppointmentPlanItemVO("ITEM2", item.skuNo(), item.goodsNo(), item.definitionVersionNo(), 1,
                0, item.durationMin(), item.serviceEndAt(), item.serviceEndAt().plusMinutes(20), 1L, 1L);
        var assignment = new AppointmentAssignmentVO("ITEM2", "REQ", "P", "RESOURCE", second.serviceStartAt(),
                second.serviceEndAt());
        var items = new ArrayList<>(original.frozen().items()); items.add(second);
        var assignments = new ArrayList<>(original.assignments()); assignments.add(assignment);
        var oldPlan = ResourcePlanContract.decode(bookingPlan);
        var demands = new ArrayList<>(oldPlan.demands());
        demands.add(new ResourcePlanContract.Demand("RESOURCE", "ITEM2:REQ:0", 1,
                List.of(new ResourcePlanContract.Interval(assignment.startAt().toInstant(), assignment.endAt().toInstant()))));
        var qualifications = new ArrayList<>(oldPlan.qualifications());
        qualifications.add(new ResourcePlanContract.Qualification("RESOURCE", "ITEM2:REQ:0", "G", "SKU", true));
        bookingPlan = ResourcePlanContract.encode(demands, qualifications);
        String planHash = ResourcePlanContract.hash(bookingPlan);
        String snapshot = json.writeValueAsString(new AppointmentMatching.Snapshot(items, assignments, 0, null));
        var binding = new ResourceSourceAuthorityPort.Binding("MAIN", "E", "S", "BOOK_COMMAND", "BEAUTY_APPOINTMENT",
                "APPOINTMENT", 1, "CONFIRM", HASH, planHash);
        var hold = new ResourceBookingPort.Hold("HOLD", "CONFIRMED", 1, 1, 1, 1, planHash);
        var evidence = new HoldEvidence(binding, hold, 1, IdempotencyScope.requestHash("PREVIEW", "HOLD", snapshot));
        var definitions = new ArrayList<>(original.frozen().definitions()); definitions.add(definitions.getFirst());
        var frozen = new FrozenAppointmentScope(items, definitions, "PV", null, List.of(), evidence);
        var refs = new ArrayList<>(original.taskRefs());
        refs.add(new WorkOrderCoordinator.TaskReference("TASK2", "SKU", "DV", BigDecimal.ONE, "COUNT"));
        var scope = new WorkOrderCoordinator.WorkOrderScopeSnapshot(1, 1, HASH, "PV", null, frozen, assignments, refs);
        var draft = new WorkOrderCoordinator.OpenSubmissionDraft(1, 1, HASH, new VerifiedIdentity("USER", "BUYER"), refs);
        sql.update("UPDATE bty_work_order_context SET frozen_scope=?", json.writeValueAsString(scope));
        sql.update("UPDATE bty_service_submission SET frozen_command=?,payload_hash=? WHERE action='OPEN'",
                json.writeValueAsString(draft), IdempotencyScope.requestHash(json.writeValueAsString(draft)));
        sql.update("UPDATE bty_appointment_preparation SET frozen_plan=?,plan_hash=?,snapshot=?,end_at=?",
                bookingPlan, planHash, snapshot, second.serviceEndAt().toLocalDateTime());
        sql.update("UPDATE bty_appointment_arrangement SET snapshot=?", snapshot);
        var accept = new ResourceBookingPort.Accept(new ResourceBookingPort.Scope("MAIN", "E", "S"), "CONFIRM",
                "BOOK_KEY", "BEAUTY_APPOINTMENT", "APPOINTMENT", 1, 1, null, bookingPlan);
        var receipt = new ResourceBookingPort.Receipt(binding, "RESOURCE_OWNER", "SUCCEEDED", 1, 1, null, bookingPlan,
                json.writeValueAsString(Map.of("holdNo", "HOLD", "status", "CONFIRMED", "ownerRevision", 1)), null);
        sql.update("UPDATE bty_appointment_operation SET accept_payload=?,receipt=?", json.writeValueAsString(accept),
                json.writeValueAsString(receipt));
        seed("bty_appointment_item", "appointment_item_no", "ITEM2", "appointment_no", "APPOINTMENT", "sku_no",
                "SKU", "definition_version_no", "DV", "qty", 1, "uom", "COUNT", "sequence_no", 1,
                "execution_snapshot", "{}");
        seed("bty_appointment_assignment", "planned_assignment_no", "PA2", "appointment_item_no", "ITEM2",
                "appointment_revision", 1, "requirement_no", "REQ", "personnel_no", "P", "resource_no", "RESOURCE",
                "hold_no", "HOLD", "start_at", assignment.startAt().toLocalDateTime(), "end_at",
                assignment.endAt().toLocalDateTime(), "skill_snapshot", "{}");
        seed("bty_service_task", "task_no", "TASK2", "work_order_no", "WO", "scope_revision", 1, "sku_no", "SKU",
                "definition_version_no", "DV", "appointment_item_no", "ITEM2", "qty", 1, "done_qty", 0,
                "voided_qty", 0, "uom", "COUNT", "status", "PENDING", "binding_revision", 1,
                "execution_snapshot", json.writeValueAsString(new WorkOrderCoordinator.TaskExecutionSnapshot(1, "ASSIGNED",
                        definitions.getFirst(), List.of(assignment), assignment.startAt(), assignment.endAt())));
        seed("bty_trade_binding", "binding_no", "BINDING2", "submission_no", "BIND", "task_no", "TASK2",
                "binding_revision", 1, "source_kind", "STORE_ORDER", "source_no", "SOURCE", "source_line_ref", "LINE",
                "qty", 1, "uom", "COUNT", "guard_no", "GUARD", "admission_kind", "MANUAL_OFFLINE", "customer_kind",
                "USER", "buyer_no", "BUYER", "manual_source_revision", 1, "manual_source_hash", HASH);
        sql.update("UPDATE bty_source_allocation_guard SET source_qty=2,allocated_qty=2");
        sql.update("UPDATE bty_source_observation SET manual_verified_lines=?", json.writeValueAsString(List.of(
                new ServiceSourceObservation.ManualQuantityLine("LINE", "CLIENT", "G", "SKU", 2, 2, "COUNT"))));
    }

    private void seed(String table, Object... fields) {
        var values = new LinkedHashMap<String, Object>();
        values.put("tenant_no", "MAIN"); values.put("entity_no", "E"); values.put("created_at", T0);
        values.put("updated_at", T0); values.put("version", 0); values.put("deleted", 0);
        for (int i = 0; i < fields.length; i += 2) values.put((String) fields[i], fields[i + 1]);
        sql.update("INSERT INTO " + table + " (" + String.join(",", values.keySet()) + ") VALUES ("
                + String.join(",", Collections.nCopies(values.size(), "?")) + ")", values.values().toArray());
    }

    @AfterEach
    public void clear() { DataScopeContext.clear(); }
}
