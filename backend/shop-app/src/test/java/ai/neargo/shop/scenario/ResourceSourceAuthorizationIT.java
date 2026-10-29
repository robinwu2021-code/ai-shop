package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Demand;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Interval;
import ai.neargo.shop.merchant.policy.ResourcePlanCodec;
import ai.neargo.shop.merchant.service.ResourceAdmissionService.Qualification;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort.Binding;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort.Decision;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort.Request;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort.State;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real command and transaction gate; the source provider is a contract double, not durable industry acceptance. */
class ResourceSourceAuthorizationIT {
    private static final String GATE = "ai.neargo.shop.merchant.service.ResourceSourceAuthorizationService";
    private static final String CONTRACT = "ai.neargo.shop.merchant.service.ResourceCommandService";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 21, 9, 0);
    private JdbcTemplate sql;
    private String url;

    @Configuration(proxyBeanMethods = false)
    @MapperScan("ai.neargo.shop.merchant.mapper")
    static class Mappers {
    }

    @BeforeEach
    void database() throws Exception {
        required(GATE);
        String admin = System.getProperty("resource-source.test.admin-url");
        assertThat(admin).isNotBlank();
        String name = "resource_command_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(admin, "root", ""))
                .execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        url = admin.replace("/mysql?", "/" + name + "?");
        var source = new DriverManagerDataSource(url, "root", "");
        sql = new JdbcTemplate(source);
        sql.execute("CREATE TABLE mch_entity (tenant_no VARCHAR(32),entity_no VARCHAR(32),status VARCHAR(24),"
                + "archived_at DATETIME(3),deleted INT,PRIMARY KEY(tenant_no,entity_no))");
        sql.execute("CREATE TABLE mch_store (tenant_no VARCHAR(32),entity_no VARCHAR(32),store_no VARCHAR(32),"
                + "status VARCHAR(24),deleted INT,PRIMARY KEY(tenant_no,entity_no,store_no))");
        sql.execute("INSERT INTO mch_entity VALUES('MAIN','E','ACTIVE',NULL,0)");
        sql.execute("INSERT INTO mch_store VALUES('MAIN','E','S','ACTIVE',0),('MAIN','E','OTHER','ACTIVE',0)");
        sql.execute("CREATE TABLE command_test_effect (effect_no VARCHAR(32) PRIMARY KEY)");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V595__shared_resource_booking_foundation.sql"));
        }
        login("ACTOR");
    }

    @AfterEach
    void clear() {
        DataScopeContext.clear();
        SecurityContextHolder.clearContext();
    }

    private ApplicationContextRunner runner() {
        return emptyRunner().withBean(ResourceSourceAuthorityPort.class, () -> authority);
    }

    private ApplicationContextRunner emptyRunner() {
        return new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class,
                Mappers.class, required("ai.neargo.shop.merchant.config.PersonnelDataScopeRegistration"),
                required("ai.neargo.shop.merchant.service.impl.ResourceCommandServiceImpl"),
                required("ai.neargo.shop.merchant.service.impl.ResourceSourceAuthorizationServiceImpl"))
                .withBean(AuditMetaObjectHandler.class).withBean(Clock.class, () -> clock)
                .withPropertyValues("spring.datasource.url=" + url,
                        "spring.datasource.username=root", "spring.datasource.password=");
    }

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");
    private final TestClock clock = new TestClock();
    private final TestAuthority authority = new TestAuthority();

    @Test
    void bindsCanonicalDurableCommandAndCurrentRecoveryActorOutsideTransaction() {
        runner().run(ctx -> {
            Object receipt = accepted(ctx, "K", "A");
            login("RECOVERY");
            Object resolution = gate(ctx, "authorize", scope("main", "e", "s"), get(receipt, "commandNo"));
            assertThat(authority.request.operatorNo()).isEqualTo("RECOVERY");
            assertThat(authority.request.realm()).isEqualTo(LoginUser.merchant("RECOVERY", "").realm().name());
            assertThat(authority.request.subjectKind())
                    .isEqualTo(LoginUser.merchant("RECOVERY", "").subjectKind().name());
            assertThat(authority.request.binding().tenantNo()).isEqualTo("MAIN");
            assertThat(authority.request.binding().entityNo()).isEqualTo("E");
            assertThat(authority.request.binding().storeNo()).isEqualTo("S");
            assertThat(authority.request.binding().commandHash())
                    .isEqualTo(sql.queryForObject("SELECT payload_hash FROM mch_resource_command", String.class));
            assertThat(authority.inTransaction).isFalse();
            tx(ctx).execute(status -> {
                Object execution = lock(ctx, receipt);
                Object plan = gate(ctx, "requireAuthorized", execution, get(resolution, "authorization"));
                assertThat((List<?>) get(plan, "demands")).hasSize(1);
                assertThat(authority.freezeCalls).isEqualTo(1);
                return call(ctx, "completeSuccess", execution, null, "{}");
            });
            assertThat(get(find(ctx, scope(), get(receipt, "commandNo")), "status").toString()).isEqualTo("SUCCEEDED");
        });
    }

    @Test
    void noProviderNeverFallsBackToPermission() {
        emptyRunner().run(ctx -> {
            Object receipt = accepted(ctx, "K", "A");
            assertThatThrownBy(() -> gate(ctx, "authorize", scope(), get(receipt, "commandNo")))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(authority.freezeCalls).isZero();
        });
    }

    @Test
    void duplicateSourceProvidersPreventStartup() {
        runner().withBean("duplicateAuthority", ResourceSourceAuthorityPort.class, TestAuthority::new).run(ctx ->
                assertThat(ctx.getStartupFailure()).isNotNull());
    }

    @Test
    void unknownPendingDeniedNeverMintAnAuthorizationOrRejectTheResourceCommand() {
        runner().run(ctx -> {
            Object receipt = accepted(ctx, "K", "A");
            for (State state : List.of(State.UNKNOWN, State.PENDING, State.DENIED)) {
                authority.state = state;
                Object resolution = gate(ctx, "authorize", scope(), get(receipt, "commandNo"));
                assertThat(get(resolution, "authorization")).isNull();
                assertThat(get(resolution, "decision")).isInstanceOf(Decision.class);
            }
            assertThat(get(find(ctx, scope(), get(receipt, "commandNo")), "status").toString()).isEqualTo("ACCEPTED");
        });
    }

    @Test
    void providerFailureAndRecoveryUseOriginalIdentityWithoutGuessingRejection() {
        runner().run(ctx -> {
            Object receipt = accepted(ctx, "K", "A");
            authority.failure = new IllegalStateException("uncertain source response");
            assertThatThrownBy(() -> gate(ctx, "authorize", scope(), get(receipt, "commandNo")))
                    .isSameAs(authority.failure);
            Binding original = authority.request.binding();
            authority.failure = null;
            gate(ctx, "recover", scope(), get(receipt, "commandNo"));
            assertThat(authority.queryCalls).isEqualTo(1);
            assertThat(authority.freezeCalls).isEqualTo(1);
            assertThat(authority.request.binding()).isEqualTo(original);
            assertThat(get(find(ctx, scope(), get(receipt, "commandNo")), "status").toString()).isEqualTo("ACCEPTED");
        });
    }

    @Test
    void remoteProviderIsNeverInvokedUnderAnAmbientTransaction() {
        runner().run(ctx -> {
            Object receipt = accepted(ctx, "K", "A");
            assertThatThrownBy(() -> tx(ctx).execute(status ->
                    gate(ctx, "authorize", scope(), get(receipt, "commandNo"))))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(authority.freezeCalls).isZero();
        });
    }

    @Test
    void planHashMismatchIsRejectedEvenWhenTheOriginalCommandHashMatches() {
        authority.change = b -> new Binding(b.tenantNo(), b.entityNo(), b.storeNo(), b.commandNo(), b.sourceType(),
                b.sourceNo(), b.sourceRevision(), b.action(), b.commandHash(), "b".repeat(64));
        rejectDecision();
    }

    @Test
    void wrongCommandScopeSourceRevisionActionAndCommandHashCannotAuthorize() {
        for (int field = 0; field < 9; field++) {
            int changed = field;
            authority.change = b -> new Binding(changed == 0 ? "OTHER" : b.tenantNo(),
                    changed == 1 ? "OTHER" : b.entityNo(), changed == 2 ? "OTHER" : b.storeNo(),
                    changed == 3 ? "OTHER" : b.commandNo(), changed == 4 ? "RETAIL_APPOINTMENT" : b.sourceType(),
                    changed == 5 ? "OTHER" : b.sourceNo(), changed == 6 ? 2 : b.sourceRevision(),
                    changed == 7 ? "RESERVE" : b.action(),
                    changed == 8 ? "a".repeat(64) : b.commandHash(), b.planHash());
            rejectDecision();
        }
    }

    @Test
    void expiredOrNotYetEffectivePermissionCannotBeConsumed() {
        runner().run(ctx -> {
            Object receipt = accepted(ctx, "K", "A");
            Object authorization = get(gate(ctx, "authorize", scope(), get(receipt, "commandNo")), "authorization");
            for (Instant time : List.of(NOW.minusSeconds(1), NOW.plusSeconds(60))) {
                clock.time.set(time);
                assertThatThrownBy(() -> tx(ctx).execute(status ->
                        gate(ctx, "requireAuthorized", lock(ctx, receipt), authorization)))
                        .isInstanceOf(IllegalStateException.class);
            }
        });
    }

    @Test
    void expirationBeforeCommitRollsBackEffectsOwnerAndSuccessfulResult() {
        runner().run(ctx -> {
            Object receipt = accepted(ctx, "K", "A");
            Object authorization = get(gate(ctx, "authorize", scope(), get(receipt, "commandNo")), "authorization");
            assertThatThrownBy(() -> tx(ctx).execute(status -> {
                Object execution = lock(ctx, receipt);
                gate(ctx, "requireAuthorized", execution, authorization);
                jdbc(ctx).update("INSERT INTO command_test_effect VALUES('E')");
                call(ctx, "completeSuccess", execution, null, "{}");
                clock.time.set(NOW.plusSeconds(60));
                return null;
            })).isInstanceOf(IllegalStateException.class);
            assertThat(count("command_test_effect")).isZero();
            assertThat(sql.queryForObject("SELECT owner_version FROM mch_booking_owner", Long.class)).isZero();
            assertThat(get(find(ctx, scope(), get(receipt, "commandNo")), "status").toString()).isEqualTo("ACCEPTED");
        });
    }

    @Test
    void foreignCommandCannotReuseAuthorizationAndCaughtFailureStillRollsBack() {
        runner().run(ctx -> {
            Object first = accepted(ctx, "K", "A");
            Object second = accepted(ctx, "K2", "B");
            Object authorization = get(gate(ctx, "authorize", scope(), get(first, "commandNo")), "authorization");
            assertThatThrownBy(() -> tx(ctx).execute(status -> {
                Object execution = lock(ctx, second);
                jdbc(ctx).update("INSERT INTO command_test_effect VALUES('E')");
                assertThatThrownBy(() -> gate(ctx, "requireAuthorized", execution, authorization))
                        .isInstanceOf(IllegalStateException.class);
                return null;
            })).isInstanceOf(RuntimeException.class);
            assertThat(count("command_test_effect")).isZero();
        });
    }

    @Test
    void forgedAuthorizationAndOldExecutionCannotBeUsed() {
        runner().run(ctx -> {
            Object receipt = accepted(ctx, "K", "A");
            Object authorization = get(gate(ctx, "authorize", scope(), get(receipt, "commandNo")), "authorization");
            Object stale = tx(ctx).execute(status -> lock(ctx, receipt));
            assertThatThrownBy(() -> tx(ctx).execute(status ->
                    gate(ctx, "requireAuthorized", stale, authorization))).isInstanceOf(IllegalStateException.class);
            Class<?> type = required(GATE + "$Authorization");
            Object forged = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                    (proxy, method, args) -> null);
            assertThatThrownBy(() -> tx(ctx).execute(status ->
                    gate(ctx, "requireAuthorized", lock(ctx, receipt), forged)))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void arbitraryLegacyPayloadAndTerminalCommandDoNotFreezeASource() {
        runner().run(ctx -> {
            Object legacy = accept(ctx, "LEGACY", "A");
            assertThatThrownBy(() -> gate(ctx, "authorize", scope(), get(legacy, "commandNo")))
                    .isInstanceOf(IllegalArgumentException.class);
            Object receipt = accepted(ctx, "K", "B");
            call(ctx, "rejectAfterRollback", scope(), get(receipt, "commandNo"), 0L, "CANCELLED", "{}");
            assertThatThrownBy(() -> gate(ctx, "authorize", scope(), get(receipt, "commandNo")))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(authority.freezeCalls).isZero();
        });
    }

    @Test
    void foreignTenantAndDisabledParentCannotTriggerSourceCalls() {
        runner().run(ctx -> {
            Object receipt = accepted(ctx, "K", "A");
            var user = LoginUser.merchant("FOREIGN", "operator");
            var foreign = new LoginUser(user.realm(), user.subjectKind(), user.userNo(), user.nickname(),
                    user.roles(), user.perms(), "FOREIGN", user.dataScope());
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(foreign, null, List.of()));
            assertThatThrownBy(() -> gate(ctx, "authorize", scope(), get(receipt, "commandNo")))
                    .isInstanceOf(IllegalStateException.class);
            login("ACTOR");
            sql.update("UPDATE mch_store SET status='INACTIVE'");
            assertThatThrownBy(() -> gate(ctx, "authorize", scope(), get(receipt, "commandNo")))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(authority.freezeCalls).isZero();
        });
    }

    @Test
    void anotherUserOrSubjectCannotConsumeAnIssuedPermission() {
        runner().run(ctx -> {
            Object receipt = accepted(ctx, "K", "A");
            Object authorization = get(gate(ctx, "authorize", scope(), get(receipt, "commandNo")), "authorization");
            for (LoginUser changed : List.of(LoginUser.merchant("OTHER", "other"),
                    LoginUser.merchantByUser("ACTOR", "actor"), LoginUser.consumer("ACTOR", "actor"))) {
                login("ACTOR");
                assertThatThrownBy(() -> tx(ctx).execute(status -> {
                    Object execution = lock(ctx, receipt);
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(changed, null, List.of()));
                    return gate(ctx, "requireAuthorized", execution, authorization);
                })).isInstanceOf(IllegalStateException.class);
            }
        });
    }

    @Test
    void principalChangeBeforeCommitAlsoRollsBack() {
        runner().run(ctx -> {
            Object receipt = accepted(ctx, "K", "A");
            Object authorization = get(gate(ctx, "authorize", scope(), get(receipt, "commandNo")), "authorization");
            assertThatThrownBy(() -> tx(ctx).execute(status -> {
                Object execution = lock(ctx, receipt);
                gate(ctx, "requireAuthorized", execution, authorization);
                jdbc(ctx).update("INSERT INTO command_test_effect VALUES('E')");
                call(ctx, "completeSuccess", execution, null, "{}");
                login("OTHER");
                return null;
            })).isInstanceOf(IllegalStateException.class);
            assertThat(count("command_test_effect")).isZero();
        });
    }

    private void rejectDecision() {
        runner().run(ctx -> {
            Object receipt = accepted(ctx, UUID.randomUUID().toString(), "A");
            assertThatThrownBy(() -> gate(ctx, "authorize", scope(), get(receipt, "commandNo")))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    private static Object accepted(ApplicationContext ctx, String key, String source) {
        String frozen = ResourcePlanCodec.encode(List.of(new Demand("R", "L", 1,
                List.of(new Interval(NOW.plusSeconds(300), NOW.plusSeconds(600))))),
                List.of(new Qualification("R", "L", "G", null)));
        return call(ctx, "accept", request(scope(), "RELEASE", key, source, 0, 1, null, frozen));
    }

    private static Object gate(ApplicationContext ctx, String name, Object... args) {
        for (var method : required(GATE).getMethods()) {
            if (method.getName().equals(name)) {
                return invoke(() -> method.invoke(ctx.getBean(required(GATE)), args));
            }
        }
        throw new AssertionError("missing gate method: " + name);
    }

    private static final class TestClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(NOW);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return time.get();
        }
    }

    /** Contract double only; no business freeze or durable source fencing is claimed. */
    private static final class TestAuthority implements ResourceSourceAuthorityPort {
        private State state = State.AUTHORIZED;
        private Request request;
        private int freezeCalls;
        private int queryCalls;
        private boolean inTransaction;
        private RuntimeException failure;
        private UnaryOperator<Binding> change = UnaryOperator.identity();

        @Override
        public String sourceType() {
            return "BEAUTY_APPOINTMENT";
        }

        @Override
        public Decision freeze(Request input) {
            freezeCalls++;
            return decide(input);
        }

        @Override
        public Decision query(Request input) {
            queryCalls++;
            return decide(input);
        }

        private Decision decide(Request input) {
            request = input;
            inTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            if (failure != null) {
                throw failure;
            }
            Binding binding = change.apply(input.binding());
            if (state == State.AUTHORIZED) {
                return new Decision(binding, state, "D", "F", 1L, NOW, NOW.plusSeconds(60), null);
            }
            return new Decision(binding, state, state == State.DENIED ? "D" : null, null, null,
                    null, null, state == State.DENIED ? "SOURCE_CANCELLED" : null);
        }
    }

    private int count(String table) {
        return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static JdbcTemplate jdbc(ApplicationContext ctx) {
        return new JdbcTemplate(ctx.getBean(DataSource.class));
    }

    private static TransactionTemplate tx(ApplicationContext ctx) {
        var tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    private static Object lock(ApplicationContext ctx, Object receipt) {
        return call(ctx, "lockForExecution", scope(), get(receipt, "commandNo"), get(receipt, "version"));
    }

    private static Object accept(ApplicationContext ctx, String key, String source) {
        return call(ctx, "accept", request(scope(), "RELEASE", key, source, 0, 1, null, "{}"));
    }

    private static Object find(ApplicationContext ctx, Object scope, Object no) {
        return ((Optional<?>) call(ctx, "find", scope, no)).orElse(null);
    }

    private static Object scope() {
        return scope("MAIN", "E", "S");
    }

    private static Object scope(String tenant, String entity, String store) {
        return construct("Scope", new Class<?>[] {String.class, String.class, String.class}, tenant, entity, store);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object request(Object scope, String action, String key, String source, long ownerVersion,
            long revision, Object target, String payload) {
        Class<?> actionType = required(CONTRACT + "$Action");
        return construct("Accept", new Class<?>[] {required(CONTRACT + "$Scope"), actionType, String.class,
                String.class, String.class, long.class, long.class, required(CONTRACT + "$Target"), String.class},
                scope, Enum.valueOf((Class) actionType, action), key, "BEAUTY_APPOINTMENT", source,
                ownerVersion, revision, target, payload);
    }

    private static Object construct(String type, Class<?>[] signature, Object... args) {
        return invoke(() -> required(CONTRACT + "$" + type).getConstructor(signature).newInstance(args));
    }

    private static Object call(ApplicationContext ctx, String name, Object... args) {
        var methods = required(CONTRACT).getMethods();
        for (var method : methods) {
            if (method.getName().equals(name)) {
                return invoke(() -> method.invoke(ctx.getBean(required(CONTRACT)), args));
            }
        }
        throw new AssertionError("missing command method: " + name);
    }

    private static Object get(Object value, String name) {
        return invoke(() -> {
            var method = value.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(value);
        });
    }

    private static Object invoke(ReflectiveCall action) {
        try {
            return action.run();
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Class<?> required(String name) {
        return assertDoesNotThrow(() -> Class.forName(name));
    }

    private static void login(String actor) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant(actor, "operator"), null, List.of()));
    }

    private interface ReflectiveCall {
        Object run() throws ReflectiveOperationException;
    }
}
