package ai.neargo.shop.scenario;

import ai.neargo.shop.infra.ResourceOccupationFixtureConfiguration;

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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

/** Executes the actual platform migration and owner Port against a disposable MySQL database. */
class ResourceFactsPersistenceIT {
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
        required("spi.resource.ResourceFactsPort");
        String adminUrl = System.getProperty("resource.test.admin-url");
        assertThat(adminUrl).isNotBlank();
        String database = "resource_" + UUID.randomUUID().toString().replace("-", "");
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
                    new ClassPathResource("db/migration/V592__shared_personnel_foundation.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V593__shared_resource_identity.sql"));
        }
        sql.execute("INSERT INTO mch_entity(tenant_no,entity_no,status,deleted) VALUES('MAIN','E','ACTIVE',0)");
        sql.execute("INSERT INTO mch_store(tenant_no,entity_no,store_no,status,deleted) "
                + "VALUES('MAIN','E','S','ACTIVE',0),('MAIN','E','OTHER','ACTIVE',0)");
        seed("mch_personnel", "personnel_no", "P", "name", "PERSON", "status", "ACTIVE", "contact_ref", "SECRET");
        assignment("A", "S", T0, T0.plusHours(2));
        resource("R", "STAFF", null, "P", 1);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant("ACTOR", "operator"), null, List.of()));
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(ResourceOccupationFixtureConfiguration.class, PrimaryPersistenceConfiguration.class,
                Mappers.class, required("merchant.config.PersonnelDataScopeRegistration"),
                required("merchant.port.ResourceFactsPortImpl")).withBean(AuditMetaObjectHandler.class)
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=root",
                        "spring.datasource.password=");
    }

    private Object query(String entity, String store, Set<String> resources, Instant at) {
        return assertDoesNotThrow(() -> required("spi.resource.ResourceFactsPort$Query")
                .getConstructor(String.class, String.class, String.class, Set.class, Instant.class)
                .newInstance("MAIN", entity, store, resources, at));
    }

    private List<?> facts(ApplicationContext context, String store, Instant at) {
        return facts(context, query("E", store, Set.of("R"), at));
    }

    private List<?> facts(ApplicationContext context, Object query) {
        try {
            return (List<?>) required("spi.resource.ResourceFactsPort").getMethod("getBatch", query.getClass())
                    .invoke(context.getBean(required("spi.resource.ResourceFactsPort")), query);
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

    private void resource(String no, String type, String store, String person, int capacity) {
        seed("mch_resource", "resource_no", no, "resource_type", type, "store_no", store,
                "personnel_no", person, "name", "RESOURCE", "capacity", capacity, "status", "ACTIVE");
    }

    @AfterEach
    void cleanup() {
        DataScopeContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void staffHasOneCanonicalResourceAcrossEffectiveStoreAssignmentsWithoutLogin() {
        assignment("A2", "OTHER", T0, null);
        runner().run(context -> {
            var original = facts(context, "S", AT);
            assertThatThrownBy(original::clear).isInstanceOf(UnsupportedOperationException.class);
            Object first = original.getFirst();
            Object second = facts(context, "OTHER", AT).getFirst();
            assertThat(field(first, "resourceNo")).isEqualTo("R");
            assertThat(field(second, "resourceNo")).isEqualTo("R");
            assertThat(field(first, "storeNo")).isNull();
            assertThat(field(first, "capacity")).isEqualTo(1);
            assertThat(field(first, "personnelNo")).isEqualTo("P");
            assertThat(first.toString()).doesNotContain("SECRET", "ACTOR");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_account", Integer.class)).isZero();
        });
    }

    @Test
    void physicalResourcesUseTheirStoreAndDoNotInventPersonnel() {
        resource("ROOM", "ROOM", "S", null, 3);
        resource("SEAT", "SEAT", "S", null, 1);
        resource("DEVICE", "DEVICE", "OTHER", null, 2);
        runner().run(context -> {
            var rows = facts(context, query("E", "S", Set.of("ROOM", "SEAT", "DEVICE"), AT));
            assertThat(rows).hasSize(2);
            assertThat(field(rows.getFirst(), "resourceNo")).isEqualTo("ROOM");
            assertThat(field(rows.getFirst(), "resourceType").toString()).isEqualTo("ROOM");
            assertThat(field(rows.getFirst(), "capacity")).isEqualTo(3);
            assertThat(field(rows.getFirst(), "storeNo")).isEqualTo("S");
            assertThat(field(rows.getFirst(), "personnelNo")).isNull();
        });
    }

    @Test
    void missingAssignmentAndHalfOpenValidityCannotMatchStaff() {
        runner().run(context -> {
            assertThat(facts(context, "OTHER", AT)).isEmpty();
            assertThat(facts(context, "S", AT.minusMillis(1))).isEmpty();
            assertThat(facts(context, "S", AT)).hasSize(1);
            assertThat(facts(context, "S", AT.plusSeconds(7200))).isEmpty();
        });
    }

    @Test
    void inactivePersonnelAndAssignmentBlockStaffFacts() {
        sql.update("UPDATE mch_personnel SET status='SUSPENDED'");
        runner().run(context -> assertThat(facts(context, "S", AT)).isEmpty());
        sql.update("UPDATE mch_personnel SET status='ACTIVE'");
        sql.update("UPDATE mch_personnel_store SET status='INACTIVE'");
        runner().run(context -> assertThat(facts(context, "S", AT)).isEmpty());
        sql.update("UPDATE mch_personnel_store SET status='ACTIVE',deleted=1");
        runner().run(context -> assertThat(facts(context, "S", AT)).isEmpty());
    }

    @Test
    void suspendedOrDeletedResourcesAreOmitted() {
        sql.update("UPDATE mch_resource SET status='SUSPENDED'");
        runner().run(context -> assertThat(facts(context, "S", AT)).isEmpty());
        sql.update("UPDATE mch_resource SET status='ACTIVE',deleted=1");
        runner().run(context -> assertThat(facts(context, "S", AT)).isEmpty());
    }

    @Test
    void inactiveForeignOrDeletedStoreBlocksPhysicalAndStaffResources() {
        resource("ROOM", "ROOM", "S", null, 2);
        sql.update("UPDATE mch_store SET status='INACTIVE' WHERE store_no='S'");
        runner().run(context -> assertThat(facts(context, query("E", "S", Set.of("R", "ROOM"), AT))).isEmpty());
        sql.update("UPDATE mch_store SET status='ACTIVE',entity_no='FOREIGN' WHERE store_no='S'");
        runner().run(context -> assertThat(facts(context, query("E", "S", Set.of("R", "ROOM"), AT))).isEmpty());
        sql.update("UPDATE mch_store SET entity_no='E',deleted=1 WHERE store_no='S'");
        runner().run(context -> assertThat(facts(context, query("E", "S", Set.of("R", "ROOM"), AT))).isEmpty());
    }

    @Test
    void archivedOrInactiveEntityCannotReturnAnyResource() {
        sql.update("UPDATE mch_entity SET archived_at=?", T0);
        runner().run(context -> assertThat(facts(context, "S", AT)).isEmpty());
        sql.update("UPDATE mch_entity SET archived_at=NULL,status='INACTIVE'");
        runner().run(context -> assertThat(facts(context, "S", AT)).isEmpty());
    }

    @Test
    void personnelAndAssignmentParentChainsCannotCrossEntityOrTenant() {
        sql.update("UPDATE mch_personnel SET entity_no='FOREIGN'");
        runner().run(context -> assertThat(facts(context, "S", AT)).isEmpty());
        sql.update("UPDATE mch_personnel SET entity_no='E'");
        sql.update("UPDATE mch_personnel_store SET tenant_no='FOREIGN'");
        runner().run(context -> assertThat(facts(context, "S", AT)).isEmpty());
    }

    @Test
    void queryFailurePropagatesInsteadOfBeingReportedAsNoMatchingResource() {
        runner().run(context -> {
            sql.execute("DROP TABLE mch_resource");
            assertThatThrownBy(() -> facts(context, "S", AT)).hasRootCauseInstanceOf(SQLException.class);
        });
    }

    @Test
    void explicitScopeAndExplicitNamesDoNotReturnOtherResources() {
        resource("ROOM", "ROOM", "S", null, 2);
        runner().run(context -> {
            assertThat(facts(context, "S", AT)).hasSize(1);
            assertThat(facts(context, query("FOREIGN", "S", Set.of("R", "ROOM"), AT))).isEmpty();
            assertThat(facts(context, query("E", "S", Set.of("MISSING"), AT))).isEmpty();
            Object foreignTenant = assertDoesNotThrow(() -> required("spi.resource.ResourceFactsPort$Query")
                    .getConstructor(String.class, String.class, String.class, Set.class, Instant.class)
                    .newInstance("FOREIGN", "E", "S", Set.of("R", "ROOM"), AT));
            assertThat(facts(context, foreignTenant)).isEmpty();
        });
    }

    @Test
    void caseEquivalentReferencesKeepCanonicalPersonnelResourceAndStoreIdentity() {
        sql.update("UPDATE mch_resource SET personnel_no='p'");
        sql.update("UPDATE mch_personnel_store SET personnel_no='p',store_no='s'");
        resource("ROOM", "ROOM", "s", null, 2);
        runner().run(context -> {
            Object staff = facts(context, query("E", "s", Set.of("r"), AT)).getFirst();
            assertThat(field(staff, "resourceNo")).isEqualTo("R");
            assertThat(field(staff, "personnelNo")).isEqualTo("P");
            Object room = facts(context, query("E", "s", Set.of("room"), AT)).getFirst();
            assertThat(field(room, "storeNo")).isEqualTo("S");
        });
    }

    @Test
    void databaseEquivalentTypeValuesProduceCanonicalSharedCategories() {
        sql.update("UPDATE mch_resource SET resource_type='stáff'");
        resource("ROOM", "room", "S", null, 2);
        runner().run(context -> {
            Object staff = assertDoesNotThrow(() -> facts(context, "S", AT)).getFirst();
            assertThat(field(staff, "resourceType").toString()).isEqualTo("STAFF");
            Object room = assertDoesNotThrow(() -> facts(context, query("E", "S", Set.of("ROOM"), AT))).getFirst();
            assertThat(field(room, "resourceType").toString()).isEqualTo("ROOM");
        });
    }

    @Test
    void overlappingAssignmentsCannotBeSilentlyCollapsedIntoAnEligibleResource() {
        seed("mch_personnel_store", "assignment_no", "A2", "personnel_no", "p", "store_no", "s",
                "valid_from", T0, "status", "ACTIVE", "public_visible", 0, "sort_order", 0);
        runner().run(context -> assertThatThrownBy(() -> facts(context, "S", AT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ambiguous"));
    }

    @Test
    void oversizedJoinResultsFailInsteadOfTruncatingOrDeduplicating() {
        for (int i = 0; i < 200; i++) {
            assignment("A" + (i + 1), "S", T0, null);
        }
        runner().run(context -> assertThatThrownBy(() -> facts(context, "S", AT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too many"));
    }

    @Test
    void openOccupationIsOnlyABlockingFactAndItsPrivateReferenceIsNotExported() {
        sql.update("UPDATE mch_resource SET open_occupation_no='PRIVATE_OCCUPATION'");
        runner().run(context -> {
            Object row = facts(context, "S", AT).getFirst();
            assertThat(field(row, "openOccupied")).isEqualTo(true);
            assertThat(row.toString()).doesNotContain("PRIVATE_OCCUPATION");
        });
    }

    @Test
    void formalMigrationRejectsInvalidResourceShapesIncludingNullCombinations() {
        assertThatThrownBy(() -> resource("B1", "STAFF", "S", "P2", 1))
                .hasRootCauseInstanceOf(SQLException.class).hasMessageContaining("ck_mch_resource_shape");
        assertThatThrownBy(() -> resource("B2", "STAFF", null, null, 1))
                .hasRootCauseInstanceOf(SQLException.class).hasMessageContaining("ck_mch_resource_shape");
        assertThatThrownBy(() -> resource("B3", "STAFF", null, "P2", 2))
                .hasRootCauseInstanceOf(SQLException.class).hasMessageContaining("ck_mch_resource_shape");
        assertThatThrownBy(() -> resource("B4", "ROOM", null, null, 1))
                .hasRootCauseInstanceOf(SQLException.class).hasMessageContaining("ck_mch_resource_shape");
        assertThatThrownBy(() -> resource("B5", "ROOM", "S", "P2", 1))
                .hasRootCauseInstanceOf(SQLException.class).hasMessageContaining("ck_mch_resource_shape");
        assertThatThrownBy(() -> resource("B6", "ROOM", "S", null, 0))
                .hasRootCauseInstanceOf(SQLException.class).hasMessageContaining("ck_mch_resource_capacity");
        assertThatThrownBy(() -> resource("B7", "UNKNOWN", "S", null, 1))
                .hasRootCauseInstanceOf(SQLException.class).hasMessageMatching("(?s).*ck_mch_resource_(type|shape).*");
        assertThatThrownBy(() -> sql.update("UPDATE mch_resource SET version=-1"))
                .hasRootCauseInstanceOf(SQLException.class).hasMessageContaining("ck_mch_resource_version");
        assertThatThrownBy(() -> sql.update("UPDATE mch_resource SET deleted=2"))
                .hasRootCauseInstanceOf(SQLException.class).hasMessageContaining("ck_mch_resource_deleted");
        assertThatThrownBy(() -> sql.update("UPDATE mch_resource SET status='UNKNOWN'"))
                .hasRootCauseInstanceOf(SQLException.class).hasMessageContaining("ck_mch_resource_status");
    }

    @Test
    void onePersonnelResourceSurvivesSuspensionAndSoftDeletionAndUsesScopedUniqueness() {
        assertThatThrownBy(() -> resource("R2", "STAFF", null, "p", 1))
                .isInstanceOf(DuplicateKeyException.class);
        sql.update("UPDATE mch_resource SET deleted=1,status='SUSPENDED'");
        assertThatThrownBy(() -> resource("R3", "STAFF", null, "P", 1))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> resource("r", "ROOM", "S", null, 1))
                .isInstanceOf(DuplicateKeyException.class);
        resource("ROOM1", "ROOM", "S", null, 1);
        resource("ROOM2", "ROOM", "S", null, 1);
        seed("mch_resource", "entity_no", "OTHER", "resource_no", "R", "resource_type", "STAFF",
                "personnel_no", "P", "name", "RESOURCE", "capacity", 1, "status", "ACTIVE");
        seed("mch_resource", "tenant_no", "OTHER", "resource_no", "R", "resource_type", "STAFF",
                "personnel_no", "P", "name", "RESOURCE", "capacity", 1, "status", "ACTIVE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualMapperInsertUsesAuditAndSubjectScopeRestoresAfterTrustedLookup() {
        runner().run(context -> {
            var bean = new BeanWrapperImpl(required("merchant.entity.MchResource"));
            bean.setPropertyValue("entityNo", "E");
            bean.setPropertyValue("resourceNo", "NEW");
            bean.setPropertyValue("resourceType", "DEVICE");
            bean.setPropertyValue("storeNo", "S");
            bean.setPropertyValue("name", "DEVICE");
            bean.setPropertyValue("capacity", 1);
            bean.setPropertyValue("status", "ACTIVE");
            var mapper = (BaseMapper<Object>) context.getBean(
                    required("merchant.mapper.MerchantMappers$ResourceMapper"));
            assertThat(DataScopeContext.executeWithoutScope(() -> mapper.insert(bean.getWrappedInstance()))).isOne();
            assertThat(sql.queryForObject("SELECT created_by FROM mch_resource WHERE resource_no='NEW'", String.class))
                    .isEqualTo("ACTOR");
            DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("FOREIGN")));
            assertThat(facts(context, "S", AT)).hasSize(1);
            assertThat(mapper.selectList(null)).isEmpty();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryIsExplicitBoundedImmutableAndNormalizedToPersistedMilliseconds() throws Exception {
        Class<?> type = required("spi.resource.ResourceFactsPort$Query");
        var constructor = type.getConstructor(String.class, String.class, String.class, Set.class, Instant.class);
        assertThatThrownBy(() -> constructor.newInstance("MAIN", "E", "S", Set.of(), AT))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        Set<String> tooMany = IntStream.range(0, 201).mapToObj(i -> "R" + i).collect(Collectors.toSet());
        assertThatThrownBy(() -> constructor.newInstance("MAIN", "E", "S", tooMany, AT))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> constructor.newInstance("MAIN", "E", "", Set.of("R"), AT))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> constructor.newInstance("", "E", "S", Set.of("R"), AT))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> constructor.newInstance("MAIN", "E", "S", Set.of(" "), AT))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        Object query = query("E", "S", Set.of("R"), AT.plusNanos(999999));
        assertThat(field(query, "at")).isEqualTo(AT);
        assertThatThrownBy(() -> ((Set<Object>) field(query, "resourceNos")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
