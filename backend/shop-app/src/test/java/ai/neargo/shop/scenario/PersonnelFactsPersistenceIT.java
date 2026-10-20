package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Executes the actual platform migration and owner Port against a disposable MySQL database. */
class PersonnelFactsPersistenceIT {
    private static final Instant AT = Instant.parse("2026-10-21T00:00:00Z");
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 21, 0, 0);
    private JdbcTemplate sql;
    private String url;

    @Configuration(proxyBeanMethods = false)
    @MapperScan("ai.neargo.shop.merchant.mapper")
    static class Mappers {
    }

    private Class<?> required(String name) {
        return assertDoesNotThrow(() -> Class.forName("ai.neargo.shop." + name));
    }

    @BeforeEach
    void database() throws Exception {
        required("spi.personnel.PersonnelFactsPort");
        String adminUrl = System.getProperty("personnel.test.admin-url");
        assertThat(adminUrl).isNotBlank();
        String database = "personnel_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(adminUrl, "root", ""))
                .execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        url = adminUrl.replace("/mysql?", "/" + database + "?");
        var source = new DriverManagerDataSource(url, "root", "");
        sql = new JdbcTemplate(source);
        sql.execute("CREATE TABLE mch_entity (id BIGINT PRIMARY KEY AUTO_INCREMENT,tenant_no VARCHAR(32),"
                + "entity_no VARCHAR(32),status VARCHAR(24),archived_at DATETIME(3),deleted TINYINT)");
        sql.execute("CREATE TABLE mch_store (id BIGINT PRIMARY KEY AUTO_INCREMENT,tenant_no VARCHAR(32),"
                + "entity_no VARCHAR(32),store_no VARCHAR(32),status VARCHAR(24),deleted TINYINT)");
        sql.execute("CREATE TABLE mch_account (id BIGINT PRIMARY KEY AUTO_INCREMENT,tenant_no VARCHAR(32),"
                + "entity_no VARCHAR(32),mch_account_no VARCHAR(32),status VARCHAR(24),deleted TINYINT)");
        sql.execute("CREATE TABLE mch_store_role (mch_account_no VARCHAR(32),store_no VARCHAR(32),role VARCHAR(32))");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V532__shared_personnel_foundation.sql"));
        }
        sql.execute("INSERT INTO mch_entity(tenant_no,entity_no,status,deleted) VALUES('MAIN','E','ACTIVE',0)");
        sql.execute("INSERT INTO mch_store(tenant_no,entity_no,store_no,status,deleted) "
                + "VALUES('MAIN','E','S','ACTIVE',0),('MAIN','E','OTHER','ACTIVE',0)");
        seed("mch_personnel", "personnel_no", "P", "name", "PERSON", "status", "ACTIVE", "contact_ref", "SECRET");
        assignment("A", "S", T0, T0.plusHours(2));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant("ACTOR", "operator"), null, List.of()));
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class,
                Mappers.class, required("merchant.config.PersonnelDataScopeRegistration"),
                required("merchant.port.PersonnelFactsPortImpl")).withBean(AuditMetaObjectHandler.class)
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=root",
                        "spring.datasource.password=");
    }

    private Object query(String entity, String store, Set<String> people, Instant at) {
        return assertDoesNotThrow(() -> required("spi.personnel.PersonnelFactsPort$Query")
                .getConstructor(String.class, String.class, String.class, Set.class, Instant.class)
                .newInstance("MAIN", entity, store, people, at));
    }

    private List<?> facts(ApplicationContext context, String store, Instant at) {
        return facts(context, query("E", store, Set.of("P"), at));
    }

    private List<?> facts(ApplicationContext context, Object query) {
        try {
            return (List<?>) required("spi.personnel.PersonnelFactsPort").getMethod("getBatch", query.getClass())
                    .invoke(context.getBean(required("spi.personnel.PersonnelFactsPort")), query);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private Object field(Object value, String name) {
        return new BeanWrapperImpl(value).getPropertyValue(name);
    }

    private void seed(String table, Object... fields) {
        var values = new LinkedHashMap<String, Object>();
        values.put("tenant_no", "MAIN");
        values.put("entity_no", "E");
        values.put("created_at", T0);
        values.put("updated_at", T0);
        values.put("created_by", "SEED");
        values.put("updated_by", "SEED");
        values.put("version", 0L);
        values.put("deleted", 0);
        for (int i = 0; i < fields.length; i += 2) {
            values.put((String) fields[i], fields[i + 1]);
        }
        sql.update("INSERT INTO " + table + " (" + String.join(",", values.keySet()) + ") VALUES ("
                + String.join(",", Collections.nCopies(values.size(), "?")) + ")", values.values().toArray());
    }

    private void assignment(String no, String store, LocalDateTime from, LocalDateTime to) {
        seed("mch_personnel_store", "assignment_no", no, "personnel_no", "P", "store_no", store,
                "valid_from", from, "valid_to", to, "status", "ACTIVE", "public_visible", 0, "sort_order", 0);
    }

    private void skill(String no, String store, String status, LocalDateTime until) {
        seed("mch_personnel_skill", "skill_no", no, "personnel_no", "P", "store_no", store,
                "goods_no", "G", "sku_no", "SKU", "valid_from", until != null && !until.isAfter(T0)
                        ? T0.minusHours(1) : T0, "valid_to", until, "status", status);
    }

    @Test
    void personnelWithoutLoginAccountHasStableBusinessIdentityAndNoContactLeak() {
        runner().run(context -> {
            var rows = facts(context, "S", AT);
            assertThat(rows).hasSize(1);
            assertThat(field(rows.getFirst(), "personnelNo")).isEqualTo("P");
            assertThat(field(rows.getFirst(), "assignmentNo")).isEqualTo("A");
            assertThat(rows.toString()).doesNotContain("SECRET");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_account", Integer.class)).isZero();
        });
    }

    @Test
    void samePersonnelCanHaveIndependentStoreAssignments() {
        assignment("B", "OTHER", T0, T0.plusHours(2));
        runner().run(context -> {
            assertThat(field(facts(context, "OTHER", AT).getFirst(), "assignmentNo")).isEqualTo("B");
            assertThat(field(facts(context, "S", AT).getFirst(), "assignmentNo")).isEqualTo("A");
            assertThat(facts(context, "MISSING", AT)).isEmpty();
        });
    }

    @Test
    void effectiveIntervalsAreInclusiveAtStartAndExclusiveAtEnd() {
        skill("SK", null, "VERIFIED", T0.plusHours(1));
        runner().run(context -> {
            assertThat(facts(context, "S", AT.minusMillis(1))).isEmpty();
            assertThat((List<?>) field(facts(context, "S", AT).getFirst(), "qualifications")).hasSize(1);
            assertThat((List<?>) field(facts(context, "S", AT.plusSeconds(3600)).getFirst(), "qualifications"))
                    .isEmpty();
            assertThat(facts(context, "S", AT.plusSeconds(7200))).isEmpty();
        });
    }

    @Test
    void onlyVerifiedCurrentStoreOrEntityWideQualificationsAreReturned() {
        skill("GLOBAL", null, "VERIFIED", null);
        skill("LOCAL", "S", "VERIFIED", T0.plusHours(1));
        skill("OTHER", "OTHER", "VERIFIED", null);
        skill("PENDING", null, "PENDING", null);
        skill("REVOKED", null, "REVOKED", null);
        skill("EXPIRED", null, "VERIFIED", T0);
        sql.execute("UPDATE mch_personnel_skill SET valid_from=DATE_SUB(valid_from,INTERVAL 1 HOUR) "
                + "WHERE skill_no='EXPIRED'");
        runner().run(context -> assertThat((List<?>) field(facts(context, "S", AT).getFirst(), "qualifications"))
                .hasSize(2));
    }

    @Test
    void loginPermissionsDoNotCreateBusinessPositionOrQualification() {
        sql.execute("INSERT INTO mch_account(tenant_no,entity_no,mch_account_no,status,deleted) "
                + "VALUES('MAIN','E','ACCOUNT','DISABLED',0)");
        sql.execute("INSERT INTO mch_store_role VALUES('ACCOUNT','S','MANAGER')");
        seed("mch_personnel_account", "link_no", "L", "personnel_no", "P", "mch_account_no", "ACCOUNT");
        runner().run(context -> {
            Object person = facts(context, "S", AT).getFirst();
            assertThat((List<?>) field(person, "positionCodes")).isEmpty();
            assertThat((List<?>) field(person, "qualifications")).isEmpty();
        });
    }

    @Test
    void businessPositionIsReadFromActiveAssignmentRowsOnly() {
        seed("mch_personnel_position", "position_no", "POS", "assignment_no", "A", "position_code",
                "TECHNICIAN", "status", "ACTIVE");
        seed("mch_personnel_position", "position_no", "OLD", "assignment_no", "A", "position_code",
                "WAITER", "status", "INACTIVE");
        runner().run(context -> assertThat((List<?>) field(facts(context, "S", AT).getFirst(), "positionCodes"))
                .isEqualTo(List.of("TECHNICIAN")));
    }

    @Test
    void foreignSubjectRowsCannotQualifyCurrentPersonnel() {
        skill("FOREIGN", null, "VERIFIED", null);
        sql.execute("UPDATE mch_personnel_skill SET entity_no='FOREIGN'");
        runner().run(context -> {
            assertThat((List<?>) field(facts(context, "S", AT).getFirst(), "qualifications")).isEmpty();
            assertThat(facts(context, query("FOREIGN", "S", Set.of("P"), AT))).isEmpty();
        });
    }

    @Test
    void inactivePersonnelAreNotFactsForMatching() {
        sql.execute("UPDATE mch_personnel SET status='SUSPENDED'");
        runner().run(context -> assertThat(facts(context, "S", AT)).isEmpty());
    }

    @Test
    void inactiveOrForeignStoreParentBlocksFacts() {
        sql.execute("UPDATE mch_store SET entity_no='FOREIGN' WHERE store_no='S'");
        runner().run(context -> assertThat(facts(context, "S", AT)).isEmpty());
    }

    @Test
    void archivedEntityCannotReturnPersonnelFacts() {
        sql.execute("UPDATE mch_entity SET archived_at='2026-10-21 00:00:00'");
        runner().run(context -> assertThat(facts(context, "S", AT)).isEmpty());
    }

    @Test
    void overlappingEffectiveAssignmentsFailInsteadOfPickingAnArbitraryRow() {
        assignment("DUP", "S", T0, null);
        runner().run(context -> assertThatThrownBy(() -> facts(context, "S", AT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ambiguous"));
    }

    @Test
    void tooManyChildFactsFailInsteadOfTruncatingSilently() {
        for (int i = 0; i < 2001; i++) {
            seed("mch_personnel_position", "position_no", "POS" + i, "assignment_no", "A",
                    "position_code", "CODE" + i, "status", "ACTIVE");
        }
        runner().run(context -> assertThatThrownBy(() -> facts(context, "S", AT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too many"));
    }

    @Test
    void factsMustBeReadOutsideCallerWriteTransactions() {
        runner().run(context -> {
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(() -> transaction.execute(status -> facts(context, "S", AT)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualMapperInsertUsesAuditAndDataScopeRestoresAfterTrustedLookup() {
        runner().run(context -> {
            var bean = new BeanWrapperImpl(required("merchant.entity.MchPersonnel"));
            bean.setPropertyValue("entityNo", "E");
            bean.setPropertyValue("personnelNo", "NEW");
            bean.setPropertyValue("name", "NEW");
            bean.setPropertyValue("status", "ACTIVE");
            var mapper = (BaseMapper<Object>) context.getBean(
                    required("merchant.mapper.MerchantMappers$PersonnelMapper"));
            assertThat(DataScopeContext.executeWithoutScope(() -> mapper.insert(bean.getWrappedInstance()))).isOne();
            assertThat(sql.queryForObject("SELECT created_by FROM mch_personnel WHERE personnel_no='NEW'",
                    String.class))
                    .isEqualTo("ACTOR");
            DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("FOREIGN")));
            assertThat(facts(context, "S", AT)).hasSize(1);
            assertThat(mapper.selectList(null)).isEmpty();
        });
    }

    @Test
    void formalMigrationEnforcesIntervalsStatusesAndNullableAccountUniqueness() {
        assertThatThrownBy(() -> assignment("BAD", "S", T0, T0.minusSeconds(1)))
                .hasRootCauseInstanceOf(SQLException.class).hasMessageContaining("ck_mch_personnel_store_3");
        assertThatThrownBy(() -> seed("mch_personnel", "personnel_no", "BAD", "name", "BAD", "status", "UNKNOWN"))
                .hasRootCauseInstanceOf(SQLException.class).hasMessageContaining("ck_mch_personnel_3");
        seed("mch_personnel_account", "link_no", "L1", "personnel_no", "P", "mch_account_no", null);
        seed("mch_personnel_account", "link_no", "L2", "personnel_no", "P2", "mch_account_no", null);
        assertThatThrownBy(() -> seed("mch_personnel_account", "link_no", "L3", "personnel_no", "P"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void queryRequiresBoundedExplicitNamesAndNormalizesToMilliseconds() throws Exception {
        Class<?> type = required("spi.personnel.PersonnelFactsPort$Query");
        var constructor = type.getConstructor(String.class, String.class, String.class, Set.class, Instant.class);
        assertThatThrownBy(() -> constructor.newInstance("MAIN", "E", "S", Set.of(), AT))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        Set<String> tooMany = IntStream.range(0, 201).mapToObj(i -> "P" + i).collect(Collectors.toSet());
        assertThatThrownBy(() -> constructor.newInstance("MAIN", "E", "S", tooMany, AT))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> constructor.newInstance("MAIN", "E", "", Set.of("P"), AT))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        Object query = query("E", "S", Set.of("P"), AT.plusNanos(1));
        assertThat(field(query, "at")).isEqualTo(AT);
    }

    @AfterEach
    void cleanup() {
        DataScopeContext.clear();
        SecurityContextHolder.clearContext();
    }
    @Test
    void databaseEquivalentPersonnelRefsCannotBypassAmbiguity() {
        assignment("CASE", "S", T0, null);
        sql.execute("UPDATE mch_personnel_store SET personnel_no='p' WHERE assignment_no='CASE'");
        runner().run(context -> assertThatThrownBy(() -> facts(context, "S", AT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ambiguous"));
    }

    @Test
    void databaseEquivalentAssignmentRefKeepsPositionFact() {
        seed("mch_personnel_position", "position_no", "POS", "assignment_no", "a", "position_code",
                "TECHNICIAN", "status", "ACTIVE");
        runner().run(context -> assertThat((List<?>) field(facts(context, "S", AT).getFirst(), "positionCodes"))
                .isEqualTo(List.of("TECHNICIAN")));
    }

    @Test
    void databaseEquivalentPersonnelRefKeepsQualificationFact() {
        skill("SK", null, "VERIFIED", null);
        sql.execute("UPDATE mch_personnel_skill SET personnel_no='p'");
        runner().run(context -> assertThat((List<?>) field(facts(context, "S", AT).getFirst(), "qualifications"))
                .hasSize(1));
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class AfterAssignmentMutation implements Interceptor {
        private final Runnable mutation;
        private final AtomicBoolean changed = new AtomicBoolean();

        AfterAssignmentMutation(Runnable mutation) {
            this.mutation = mutation;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            var statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().endsWith("selectEffectiveAssignments") && changed.compareAndSet(false, true)) {
                mutation.run();
            }
            return result;
        }
    }

    @Test
    void laterFactQueriesUseSameSnapshotDespiteAnotherConnectionChangingRows() {
        seed("mch_personnel_position", "position_no", "POS", "assignment_no", "A", "position_code",
                "TECHNICIAN", "status", "ACTIVE");
        skill("SK", null, "VERIFIED", null);
        runner().run(context -> {
            var mutation = new AfterAssignmentMutation(() -> {
                sql.update("UPDATE mch_personnel_position SET status='INACTIVE'");
                sql.update("UPDATE mch_personnel_skill SET status='REVOKED'");
            });
            context.getBean(SqlSessionFactory.class).getConfiguration().addInterceptor(mutation);
            Object original = facts(context, "S", AT).getFirst();
            assertThat(mutation.changed).isTrue();
            assertThat((List<?>) field(original, "positionCodes")).isEqualTo(List.of("TECHNICIAN"));
            assertThat((List<?>) field(original, "qualifications")).hasSize(1);
            Object refreshed = facts(context, "S", AT).getFirst();
            assertThat((List<?>) field(refreshed, "positionCodes")).isEmpty();
            assertThat((List<?>) field(refreshed, "qualifications")).isEmpty();
        });
    }

}
