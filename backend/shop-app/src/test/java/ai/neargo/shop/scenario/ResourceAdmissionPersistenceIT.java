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
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Plan;
import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
import org.springframework.transaction.support.TransactionTemplate;

/** Tests locked local admission against real MySQL; this does not reserve capacity or authorize a source. */
class ResourceAdmissionPersistenceIT {
    private static final String ADMISSION = "ai.neargo.shop.merchant.service.ResourceAdmissionService";
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
        required(ADMISSION);
        String admin = System.getProperty("resource-admission.test.admin-url");
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
            for (String migration : List.of("V592__shared_personnel_foundation.sql",
                    "V593__shared_resource_identity.sql", "V594__shared_schedule_rules.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + migration));
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V595__shared_resource_booking_foundation.sql"));
        }
        sql.update("INSERT INTO mch_personnel(entity_no,created_at,updated_at,personnel_no,name,status) "
                + "VALUES('E',?,?,'P','PERSON','ACTIVE')", T0, T0);
        sql.update("INSERT INTO mch_personnel_store(entity_no,created_at,updated_at,assignment_no,personnel_no,"
                + "store_no,valid_from,status,public_visible,sort_order) "
                + "VALUES('E',?,?,'A','P','S',?,'ACTIVE',1,0)", T0, T0, T0.minusDays(1));
        sql.update("INSERT INTO mch_resource(entity_no,created_at,updated_at,resource_no,resource_type,"
                + "personnel_no,name,capacity,status) VALUES('E',?,?,'R','STAFF','P','STAFF',1,'ACTIVE')", T0, T0);
        sql.update("INSERT INTO mch_personnel_skill(entity_no,created_at,updated_at,skill_no,personnel_no,"
                + "goods_no,valid_from,status) VALUES('E',?,?,'K','P','G',?,'VERIFIED')", T0, T0, T0.minusDays(1));
        sql.update("INSERT INTO mch_schedule_rule(entity_no,created_at,updated_at,rule_no,resource_no,store_no,"
                + "timezone,weekday_mask,start_minute,end_minute,end_day_offset,valid_from,status) "
                + "VALUES('E',?,?,'W','R','S','UTC',127,540,1020,0,'2020-01-01','ACTIVE')", T0, T0);
        login("ACTOR");
    }

    @AfterEach
    void clear() {
        DataScopeContext.clear();
        SecurityContextHolder.clearContext();
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class,
                Mappers.class, required("ai.neargo.shop.merchant.config.PersonnelDataScopeRegistration"),
                required("ai.neargo.shop.merchant.service.impl.ResourceCommandServiceImpl"),
                required("ai.neargo.shop.merchant.service.impl.ResourceAdmissionServiceImpl"))
                .withBean(AuditMetaObjectHandler.class).withPropertyValues("spring.datasource.url=" + url,
                        "spring.datasource.username=root", "spring.datasource.password=");
    }

    private static final Instant START = Instant.parse("2026-10-21T09:02:00Z");
    private static final Instant END = Instant.parse("2026-10-21T09:08:00Z");

    @Test
    void returnsCanonicalImmutablePlanWithoutWritingCapacityOrChangingCommand() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            tx(ctx).execute(status -> {
                Plan plan = guard(ctx, lock(ctx, receipt), demands("r", START, END), qualifications("r", "g", null));
                assertThat(plan.units()).hasSize(2);
                assertThat(plan.units().getFirst().resourceNo()).isEqualTo("R");
                assertThat(plan.units().getFirst().startAt()).isEqualTo(START.minusSeconds(120));
                assertThatThrownBy(() -> plan.units().clear()).isInstanceOf(UnsupportedOperationException.class);
                return null;
            });
            assertThat(count("mch_booking_hold")).isZero();
            assertThat(count("mch_resource_bucket")).isZero();
            assertThat(get(find(ctx, scope(), get(receipt, "commandNo")), "status").toString()).isEqualTo("ACCEPTED");
        });
    }

    @Test
    void requiresOriginalActiveExecutionAndMarksCaughtFailureRollbackOnly() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            Object stale = tx(ctx).execute(status -> lock(ctx, receipt));
            assertThatThrownBy(() -> tx(ctx).execute(status -> {
                jdbc(ctx).update("INSERT INTO command_test_effect VALUES('E')");
                assertThatThrownBy(() -> guard(ctx, stale, demands("R", START, END), qualifications("R", "G", null)))
                        .isInstanceOf(IllegalStateException.class);
                return null;
            })).isInstanceOf(RuntimeException.class);
            assertThat(count("command_test_effect")).isZero();
            assertThatThrownBy(() -> guard(ctx, stale, demands("R", START, END), qualifications("R", "G", null)))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void rejectsSuspendedResourcesAndPersonnel() {
        rejectMutation("UPDATE mch_resource SET status='SUSPENDED'");
        sql.update("UPDATE mch_resource SET status='ACTIVE'");
        rejectMutation("UPDATE mch_personnel SET status='LEFT'");
    }

    @Test
    void rejectsAnyOpenOccupationPointer() {
        rejectMutation("UPDATE mch_resource SET open_occupation_no='UNKNOWN'");
    }

    @Test
    void missingScheduleNeverFallsBackToLegacySlotPermission() {
        rejectMutation("DELETE FROM mch_schedule_rule");
    }

    @Test
    void roundedWholeBucketMustFitSchedule() {
        rejectMutation("UPDATE mch_schedule_rule SET start_minute=542");
    }

    @Test
    void employmentMustCoverWholeRoundedBucket() {
        rejectMutation("UPDATE mch_personnel_store SET valid_to='2026-10-21 09:09:00'");
    }

    @Test
    void overlappingEmploymentsAndCrossStoreSchedulesFailClosed() {
        sql.update("INSERT INTO mch_personnel_store(entity_no,created_at,updated_at,assignment_no,personnel_no,"
                + "store_no,valid_from,status,public_visible,sort_order) "
                + "VALUES('E',?,?,'A2','P','OTHER',?,'ACTIVE',1,0)", T0, T0, T0.minusDays(1));
        rejectMutation("INSERT INTO mch_schedule_rule(entity_no,created_at,updated_at,rule_no,resource_no,store_no,"
                + "timezone,weekday_mask,start_minute,end_minute,end_day_offset,valid_from,status) "
                + "SELECT entity_no,created_at,updated_at,'W2',resource_no,'OTHER',timezone,weekday_mask,"
                + "start_minute,end_minute,end_day_offset,valid_from,status FROM mch_schedule_rule WHERE rule_no='W'");
    }

    @Test
    void staffNeedsAnExplicitQualificationRequirement() {
        reject(List.of(), demands("R", START, END));
    }

    @Test
    void wrongGoodsSkuStoreAndUnverifiedQualificationsAreRejected() {
        reject(qualifications("R", "WRONG", null), demands("R", START, END));
        rejectMutation("UPDATE mch_personnel_skill SET sku_no='SPECIFIC'");
        sql.update("UPDATE mch_personnel_skill SET sku_no=NULL,store_no='OTHER'");
        reject(qualifications("R", "G", null), demands("R", START, END));
        sql.update("UPDATE mch_personnel_skill SET store_no=NULL,status='REVOKED'");
        reject(qualifications("R", "G", null), demands("R", START, END));
    }

    @Test
    void qualificationMustCoverTheEntireRoundedBucket() {
        rejectMutation("UPDATE mch_personnel_skill SET valid_to='2026-10-21 09:09:00'");
    }

    @Test
    void specificSkuRequiresExactDatabaseIdentityAndGenericQualificationAllowsIt() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            tx(ctx).execute(status -> guard(ctx, lock(ctx, receipt), demands("R", START, END),
                    qualifications("R", "G", "SKU")));
            sql.update("UPDATE mch_personnel_skill SET sku_no='SKU'");
            tx(ctx).execute(status -> guard(ctx, lock(ctx, receipt), demands("R", START, END),
                    qualifications("R", "g", "sku")));
        });
    }

    @Test
    void physicalResourceMustBelongToCurrentStoreAndNeedsNoPersonnel() {
        sql.update("INSERT INTO mch_resource(entity_no,created_at,updated_at,resource_no,resource_type,store_no,"
                + "name,capacity,status) VALUES('E',?,?,'ROOM','ROOM','S','ROOM',2,'ACTIVE')", T0, T0);
        sql.update("UPDATE mch_schedule_rule SET resource_no='ROOM'");
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            tx(ctx).execute(status -> guard(ctx, lock(ctx, receipt), demands("ROOM", START, END), List.of()));
            sql.update("UPDATE mch_resource SET store_no='OTHER' WHERE resource_no='ROOM'");
            assertThatThrownBy(() -> tx(ctx).execute(status ->
                    guard(ctx, lock(ctx, receipt), demands("ROOM", START, END), List.of())))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void aliasCannotCreateDuplicateGroupsAndMissingResourceIsRejected() {
        var one = demands("R", START, END).getFirst();
        reject(qualifications("R", "G", null), List.of(one, demands("r", START, END).getFirst()));
        reject(qualifications("R", "G", null), demands("MISSING", START, END));
    }

    @Test
    void noPartialPlanForMoreThan31DaysOrExcessiveQualificationChecks() {
        reject(qualifications("R", "G", null), demands("R", START, START.plusSeconds(32 * 86400L)));
        List<Object> many = IntStream.range(0, 5001)
                .mapToObj(i -> qualification("R", "G" + i, null)).toList();
        reject(many, demands("R", START, END));
    }

    @Test
    void jdbcChangesCannotBeHiddenBySameTransactionScheduleCache() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            assertThatThrownBy(() -> tx(ctx).execute(status -> {
                Object execution = lock(ctx, receipt);
                guard(ctx, execution, demands("R", START, END), qualifications("R", "G", null));
                jdbc(ctx).update("UPDATE mch_schedule_rule SET status='INACTIVE'");
                return guard(ctx, execution, demands("R", START, END), qualifications("R", "G", null));
            })).isInstanceOf(IllegalStateException.class);
            assertThat(sql.queryForObject("SELECT status FROM mch_schedule_rule", String.class)).isEqualTo("ACTIVE");
        });
    }

    @Test
    void holdsPersonnelAndResourceRootLocksUntilCallerTransactionEnds() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            tx(ctx).execute(status -> {
                guard(ctx, lock(ctx, receipt), demands("R", START, END), qualifications("R", "G", null));
                try (var pool = Executors.newSingleThreadExecutor()) {
                    var blocked = pool.submit(() -> {
                        try (var connection = new DriverManagerDataSource(url, "root", "").getConnection();
                                var statement = connection.createStatement()) {
                            statement.execute("SET innodb_lock_wait_timeout=1");
                            for (String table : List.of("mch_personnel", "mch_resource")) {
                                try {
                                    statement.executeUpdate("UPDATE " + table + " SET version=version+1");
                                    return false;
                                } catch (SQLException expected) {
                                    if (expected.getErrorCode() != 1205) {
                                        throw expected;
                                    }
                                }
                            }
                            return true;
                        }
                    });
                    assertThat(blocked.get(10, TimeUnit.SECONDS)).isTrue();
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
                return null;
            });
            assertThat(sql.update("UPDATE mch_personnel SET version=version+1")).isOne();
            assertThat(sql.update("UPDATE mch_resource SET version=version+1")).isOne();
        });
    }

    @Test
    void qualificationReferenceMayUseAnotherDatabaseAliasOfTheDemandResource() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            tx(ctx).execute(status -> guard(ctx, lock(ctx, receipt), demands("r", START, END),
                    qualifications("R", "G", null)));
        });
    }

    @Test
    void caughtAdmissionFailureCannotCommitEarlierCallerEffects() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            assertThatThrownBy(() -> tx(ctx).execute(status -> {
                Object execution = lock(ctx, receipt);
                jdbc(ctx).update("INSERT INTO command_test_effect VALUES('E')");
                jdbc(ctx).update("UPDATE mch_resource SET status='SUSPENDED'");
                assertThatThrownBy(() -> guard(ctx, execution, demands("R", START, END),
                        qualifications("R", "G", null))).isInstanceOf(IllegalStateException.class);
                return null;
            })).isInstanceOf(RuntimeException.class);
            assertThat(count("command_test_effect")).isZero();
            assertThat(sql.queryForObject("SELECT status FROM mch_resource", String.class)).isEqualTo("ACTIVE");
        });
    }

    @Test
    void jdbcChangesCannotBeHiddenByQualificationCache() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            assertThatThrownBy(() -> tx(ctx).execute(status -> {
                Object execution = lock(ctx, receipt);
                guard(ctx, execution, demands("R", START, END), qualifications("R", "G", null));
                jdbc(ctx).update("UPDATE mch_personnel_skill SET status='REVOKED'");
                return guard(ctx, execution, demands("R", START, END), qualifications("R", "G", null));
            })).isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void explicitNoQualificationPolicySupportsGeneralIndustryStaffWithoutInventedSkills() {
        sql.update("DELETE FROM mch_personnel_skill");
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            tx(ctx).execute(status -> guard(ctx, lock(ctx, receipt), demands("R", START, END),
                    List.of(noQualification())));
        });
    }

    @Test
    void noQualificationPolicyCannotSilentlyOverrideRequiredProjects() {
        Object none = noQualification();
        reject(List.of(none, qualification("R", "G", null)), demands("R", START, END));
    }

    private static Object noQualification() {
        return invoke(() -> required(ADMISSION + "$Qualification")
                .getConstructor(String.class, String.class, String.class, String.class, boolean.class)
                .newInstance("R", "L", null, null, false));
    }

    private void rejectMutation(String mutation) {
        sql.update(mutation);
        reject(qualifications("R", "G", null), demands("R", START, END));
    }

    private void reject(List<Object> qualifications, List<Demand> demands) {
        runner().run(ctx -> {
            Object receipt = accept(ctx, UUID.randomUUID().toString(), "A");
            assertThatThrownBy(() -> tx(ctx).execute(status ->
                    guard(ctx, lock(ctx, receipt), demands, qualifications))).isInstanceOf(RuntimeException.class);
        });
    }

    private static List<Demand> demands(String resource, Instant start, Instant end) {
        return List.of(new Demand(resource, "L", 1, List.of(new Interval(start, end))));
    }

    private static List<Object> qualifications(String resource, String goods, String sku) {
        return List.of(qualification(resource, goods, sku));
    }

    private static Object qualification(String resource, String goods, String sku) {
        return invoke(() -> required(ADMISSION + "$Qualification")
                .getConstructor(String.class, String.class, String.class, String.class)
                .newInstance(resource, "L", goods, sku));
    }

    private static Plan guard(ApplicationContext ctx, Object execution, List<Demand> demands, List<Object> skills) {
        return (Plan) invoke(() -> required(ADMISSION).getMethod("lockAndValidate", required(CONTRACT + "$Execution"),
                List.class, List.class).invoke(ctx.getBean(required(ADMISSION)), execution, demands, skills));
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
