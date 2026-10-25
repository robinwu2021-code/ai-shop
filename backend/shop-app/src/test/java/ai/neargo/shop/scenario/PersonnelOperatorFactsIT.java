package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.port.MerchantOperatorAccessPortImpl;
import ai.neargo.shop.merchant.port.PersonnelOperatorFactsPortImpl;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.LinkState;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real canonical permission SQL and frozen V532/V533 over a named pre-V532 structural auth fixture.
 */
class PersonnelOperatorFactsIT {
    private static final Instant AT = Instant.parse("2026-10-25T00:00:00.123Z");
    private static final LocalDateTime T0 = LocalDateTime.ofInstant(AT, ZoneOffset.UTC);
    private String url;
    private String database;
    private JdbcTemplate admin;
    private JdbcTemplate sql;
    private final String password = System.getenv("PERSONNEL_OPERATOR_TEST_PASSWORD");

    @Configuration(proxyBeanMethods = false)
    @MapperScan("ai.neargo.shop.merchant.mapper")
    static class Mappers {}

    @BeforeEach
    void database() throws Exception {
        String adminUrl = System.getProperty("personnel.operator.test.admin-url");
        assertThat(adminUrl).isNotBlank();
        assertThat(password).isNotBlank();
        database = "operator_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl, "root", password));
        admin.execute(
                "CREATE DATABASE "
                        + database
                        + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        url = adminUrl.replace("/mysql?", "/" + database + "?");
        var source = new DriverManagerDataSource(url, "root", password);
        sql = new JdbcTemplate(source);
        // Structural pre-V532 authentication fixture; not historical platform migration replay.
        sql.execute(
                "CREATE TABLE mch_entity (tenant_no VARCHAR(32),entity_no VARCHAR(32),"
                        + "status VARCHAR(24),archived_at DATETIME(3),deleted INT)");
        sql.execute(
                "CREATE TABLE mch_store (tenant_no VARCHAR(32),entity_no VARCHAR(32),"
                        + "store_no VARCHAR(32),status VARCHAR(24),deleted INT)");
        sql.execute(
                "CREATE TABLE mch_account (tenant_no VARCHAR(32),entity_no"
                        + " VARCHAR(32),mch_account_no VARCHAR(32),user_no VARCHAR(64),is_owner"
                        + " INT,status VARCHAR(24),deleted INT)");
        sql.execute(
                "CREATE TABLE mch_role (tenant_no VARCHAR(32),entity_no VARCHAR(32),"
                        + "role_code VARCHAR(32),perms JSON,deleted INT)");
        sql.execute(
                "CREATE TABLE mch_store_role (tenant_no VARCHAR(32),mch_account_no VARCHAR(32),"
                        + "store_no VARCHAR(32),role VARCHAR(32),deleted INT)");
        try (var c = source.getConnection()) {
            ScriptUtils.executeSqlScript(
                    c, new ClassPathResource("db/migration/V532__shared_personnel_foundation.sql"));
            ScriptUtils.executeSqlScript(
                    c, new ClassPathResource("db/migration/V533__shared_resource_identity.sql"));
        }
        sql.execute(
                "INSERT INTO mch_entity"
                        + " VALUES('MAIN','E','ACTIVE',NULL,0),('MAIN','F','ACTIVE',NULL,0)");
        sql.execute(
                "INSERT INTO mch_store"
                        + " VALUES('MAIN','E','S','ACTIVE',0),('MAIN','F','T','ACTIVE',0)");
        sql.execute(
                "INSERT INTO mch_account VALUES('MAIN','E','100','200',0,'ACTIVE',0),"
                    + "('MAIN','E','300','100',0,'ACTIVE',0),('MAIN','E','OWNER',NULL,1,'ACTIVE',0)");
        sql.execute(
                "INSERT INTO mch_role VALUES('MAIN','E','TECH','[\"biz:service:task:self\"]',0)");
        sql.execute(
                "INSERT INTO mch_store_role"
                        + " VALUES('MAIN','100','S','TECH',0),('MAIN','300','S','TECH',0)");
        person("P");
        person("Q");
        person("ACCOUNTLESS");
        assignment("A", "P", T0.minusHours(1), T0.plusHours(1));
        assignment("B", "Q", T0.minusHours(1), T0.plusHours(1));
        seed("mch_personnel_account", "link_no", "L", "personnel_no", "P", "mch_account_no", "100");
        seed("mch_personnel_account", "link_no", "M", "personnel_no", "Q", "mch_account_no", "300");
    }

    @AfterEach
    void cleanup() {
        if (admin != null && database != null) {
            admin.execute("DROP DATABASE " + database);
        }
    }

    private ApplicationContextRunner runner(Instant at) {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        PrimaryPersistenceConfiguration.class,
                        Mappers.class,
                        MerchantOperatorAccessPortImpl.class,
                        PersonnelOperatorFactsPortImpl.class)
                .withBean(AuditMetaObjectHandler.class)
                .withBean(Clock.class, () -> Clock.fixed(at, ZoneOffset.UTC))
                .withPropertyValues(
                        "spring.datasource.url=" + url,
                        "spring.datasource.username=root",
                        "spring.datasource.password=" + password);
    }

    private PersonnelOperatorFactsPort.Query query(
            String entity, String store, String actor, String kind, String permission) {
        return new PersonnelOperatorFactsPort.Query(
                new MerchantOperatorAccessPort.Query(
                        "MAIN", entity, store, actor, "MERCHANT", kind, permission));
    }

    private PersonnelOperatorFactsPort.Query own(String actor, String kind) {
        return query("E", "S", actor, kind, "biz:service:task:self");
    }

    private PersonnelOperatorFactsPort.Observation current(
            PersonnelOperatorFactsPort port, PersonnelOperatorFactsPort.Query q) {
        var a = q.access();
        return DelegatedIdentityContext.call(
                new DelegatedIdentity(a.tenantNo(), a.realm(), a.subjectKind(), a.operatorNo()),
                () -> port.current(q));
    }

    private void seed(String table, Object... fields) {
        var values = new LinkedHashMap<String, Object>();
        values.put("tenant_no", "MAIN");
        values.put("entity_no", "E");
        values.put("created_at", T0);
        values.put("updated_at", T0);
        values.put("version", 0);
        values.put("deleted", 0);
        for (int i = 0; i < fields.length; i += 2) {
            values.put((String) fields[i], fields[i + 1]);
        }
        sql.update(
                "INSERT INTO "
                        + table
                        + " ("
                        + String.join(",", values.keySet())
                        + ") VALUES ("
                        + String.join(",", Collections.nCopies(values.size(), "?"))
                        + ")",
                values.values().toArray());
    }

    private void person(String p) {
        seed("mch_personnel", "personnel_no", p, "name", p, "status", "ACTIVE");
    }

    private void assignment(String no, String person, LocalDateTime from, LocalDateTime to) {
        seed(
                "mch_personnel_store",
                "assignment_no",
                no,
                "personnel_no",
                person,
                "store_no",
                "S",
                "valid_from",
                from,
                "valid_to",
                to,
                "status",
                "ACTIVE",
                "public_visible",
                0,
                "sort_order",
                0);
    }

    @Test
    void linkedMchAndUsrResolveOnlyTheirOwnPersonnel() {
        runner(AT)
                .run(
                        c -> {
                            var canonical = c.getBean(MerchantOperatorAccessPort.class);
                            var port = c.getBean(PersonnelOperatorFactsPort.class);
                            assertThat(port)
                                    .isExactlyInstanceOf(PersonnelOperatorFactsPortImpl.class);
                            assertThat(canonical)
                                    .isExactlyInstanceOf(MerchantOperatorAccessPortImpl.class);
                            assertThat(
                                            canonical
                                                    .resolveAuthorizedOperator(
                                                            own("200", "USR").access())
                                                    .orElseThrow()
                                                    .accountNo())
                                    .isEqualTo("100");
                            assertThat(current(port, own("100", "MCH")).personnel().personnelNo())
                                    .isEqualTo("P");
                            assertThat(current(port, own("200", "USR")).personnel().personnelNo())
                                    .isEqualTo("P");
                            var config = c.getBean(SqlSessionFactory.class).getConfiguration();
                            assertThat(
                                            config.hasStatement(
                                                    MerchantMappers.PersonnelAccountMapper.class
                                                                    .getName()
                                                            + ".selectCurrentOperatorPersonnel"))
                                    .isTrue();
                            var sourceRoot =
                                    Path.of(
                                                    System.getProperty(
                                                            "personnel.operator.test.source-root"))
                                            .toRealPath();
                            assertThat(
                                            Path.of(
                                                            PersonnelOperatorFactsPort.class
                                                                    .getProtectionDomain()
                                                                    .getCodeSource()
                                                                    .getLocation()
                                                                    .toURI())
                                                    .toRealPath())
                                    .isEqualTo(
                                            sourceRoot
                                                    .resolve("backend/shop-base/target/classes")
                                                    .toRealPath());
                            assertThat(
                                            Path.of(
                                                            port.getClass()
                                                                    .getProtectionDomain()
                                                                    .getCodeSource()
                                                                    .getLocation()
                                                                    .toURI())
                                                    .toRealPath())
                                    .isEqualTo(
                                            sourceRoot
                                                    .resolve("backend/shop-merchant/target/classes")
                                                    .toRealPath());
                            System.out.println(
                                    "TASK62_ORIGIN shop-base="
                                            + PersonnelOperatorFactsPort.class
                                                    .getProtectionDomain()
                                                    .getCodeSource()
                                                    .getLocation()
                                            + " shop-merchant="
                                            + port.getClass()
                                                    .getProtectionDomain()
                                                    .getCodeSource()
                                                    .getLocation());
                        });
    }

    @Test
    void sameNumericNamespacesCannotBorrowLink() {
        runner(AT)
                .run(
                        c -> {
                            var p = c.getBean(PersonnelOperatorFactsPort.class);
                            assertThat(current(p, own("100", "MCH")).personnel().personnelNo())
                                    .isEqualTo("P");
                            assertThat(current(p, own("100", "USR")).personnel().personnelNo())
                                    .isEqualTo("Q");
                            assertThatThrownBy(() -> current(p, own("100 ", "MCH")))
                                    .isInstanceOf(IllegalArgumentException.class);
                        });
    }

    @Test
    void foreignScopeAndInactiveAccountAreDenied() {
        runner(AT)
                .run(
                        c -> {
                            var p = c.getBean(PersonnelOperatorFactsPort.class);
                            for (var q :
                                    new PersonnelOperatorFactsPort.Query[] {
                                        query("F", "T", "100", "MCH", "biz:service:task:self"),
                                        query("E", "T", "100", "MCH", "biz:service:task:self"),
                                        own("missing", "MCH"),
                                        own("owner", "MCH")
                                    }) {
                                assertThatThrownBy(() -> current(p, q))
                                        .isInstanceOf(BizException.class);
                            }
                            sql.update(
                                    "UPDATE mch_account SET status='INACTIVE' WHERE"
                                            + " mch_account_no='100'");
                            assertThatThrownBy(() -> current(p, own("100", "MCH")))
                                    .isInstanceOf(BizException.class);
                            sql.update(
                                    "UPDATE mch_account SET status='ACTIVE',deleted=1 WHERE"
                                            + " mch_account_no='100'");
                            assertThatThrownBy(() -> current(p, own("100", "MCH")))
                                    .isInstanceOf(BizException.class);
                        });
    }

    @Test
    void caseVariantParentScopesCannotBorrowPersonnel() {
        runner(AT)
                .run(
                        c -> {
                            var mapper = c.getBean(MerchantMappers.PersonnelAccountMapper.class);
                            var port = c.getBean(PersonnelOperatorFactsPort.class);
                            assertThat(current(port, own("100", "MCH")).state())
                                    .isEqualTo(LinkState.LINKED);
                            String[][] variants = {
                                {"mch_personnel", "personnel_no", "P", "entity_no", "E", "e"},
                                {"mch_personnel", "personnel_no", "P", "tenant_no", "MAIN", "main"},
                                {
                                    "mch_personnel_store",
                                    "assignment_no",
                                    "A",
                                    "entity_no",
                                    "E",
                                    "e"
                                },
                                {
                                    "mch_personnel_store",
                                    "assignment_no",
                                    "A",
                                    "tenant_no",
                                    "MAIN",
                                    "main"
                                },
                                {"mch_personnel_store", "assignment_no", "A", "store_no", "S", "s"},
                                {"mch_store", "store_no", "S", "entity_no", "E", "e"},
                                {"mch_store", "store_no", "S", "tenant_no", "MAIN", "main"},
                                {"mch_entity", "entity_no", "E", "entity_no", "E", "e"},
                                {"mch_entity", "entity_no", "E", "tenant_no", "MAIN", "main"}
                            };
                            for (var v : variants) {
                                try {
                                    sql.update(
                                            "UPDATE "
                                                    + v[0]
                                                    + " SET "
                                                    + v[3]
                                                    + "=? WHERE "
                                                    + v[1]
                                                    + "=?",
                                            v[5],
                                            v[2]);
                                    assertThat(
                                                    mapper.selectCurrentOperatorPersonnel(
                                                            "MAIN", "E", "S", "100", T0))
                                            .as("binary parent scope %s.%s", v[0], v[3])
                                            .isEmpty();
                                } finally {
                                    // The structural fixture is CI; restore only this case variant,
                                    // even after assertion RED.
                                    sql.update(
                                            "UPDATE "
                                                    + v[0]
                                                    + " SET "
                                                    + v[3]
                                                    + "=? WHERE "
                                                    + v[1]
                                                    + "=?",
                                            v[4],
                                            v[2]);
                                }
                            }
                        });
    }

    @Test
    void permissionRevokedBetweenSnapshotsIsDenied() {
        runner(AT)
                .run(
                        c -> {
                            var canonical = c.getBean(MerchantOperatorAccessPort.class);
                            var calls = new AtomicInteger();
                            MerchantOperatorAccessPort observed =
                                    new MerchantOperatorAccessPort() {
                                        public boolean allowed(Query q) {
                                            return canonical.allowed(q);
                                        }

                                        public java.util.Optional<AuthorizedOperator>
                                                resolveAuthorizedOperator(Query q) {
                                            var result = canonical.resolveAuthorizedOperator(q);
                                            assertThat(
                                                            org.springframework.transaction.support
                                                                    .TransactionSynchronizationManager
                                                                    .isActualTransactionActive())
                                                    .isFalse();
                                            if (calls.incrementAndGet() == 1) {
                                                sql.update("UPDATE mch_role SET perms='[]'");
                                            }
                                            return result;
                                        }
                                    };
                            var p =
                                    new PersonnelOperatorFactsPortImpl(
                                            observed,
                                            c.getBean(MerchantMappers.OperatorAccessMapper.class),
                                            c.getBean(MerchantMappers.PersonnelAccountMapper.class),
                                            c.getBean(PlatformTransactionManager.class),
                                            c.getBeanProvider(Clock.class));
                            assertThatThrownBy(() -> current(p, own("100", "MCH")))
                                    .isInstanceOf(BizException.class);
                            assertThat(calls.get()).isGreaterThanOrEqualTo(1);
                            sql.update("UPDATE mch_role SET perms='[\"biz:service:task:self\"]'");
                            var postCalls = new AtomicInteger();
                            MerchantOperatorAccessPort postSnapshot =
                                    new MerchantOperatorAccessPort() {
                                        public boolean allowed(Query q) {
                                            return canonical.allowed(q);
                                        }

                                        public java.util.Optional<AuthorizedOperator>
                                                resolveAuthorizedOperator(Query q) {
                                            if (postCalls.incrementAndGet() == 2) {
                                                sql.update("UPDATE mch_role SET perms='[]'");
                                            }
                                            return canonical.resolveAuthorizedOperator(q);
                                        }
                                    };
                            var after =
                                    new PersonnelOperatorFactsPortImpl(
                                            postSnapshot,
                                            c.getBean(MerchantMappers.OperatorAccessMapper.class),
                                            c.getBean(MerchantMappers.PersonnelAccountMapper.class),
                                            c.getBean(PlatformTransactionManager.class),
                                            c.getBeanProvider(Clock.class));
                            assertThatThrownBy(() -> current(after, own("100", "MCH")))
                                    .isInstanceOf(BizException.class);
                            assertThat(postCalls.get())
                                    .as("canonical resolver also reruns after snapshot")
                                    .isEqualTo(2);
                        });
    }

    @Test
    void bookingManageAloneDoesNotGrantServiceAction() {
        sql.update("UPDATE mch_role SET perms='[\"biz:booking:manage\"]'");
        runner(AT)
                .run(
                        c ->
                                assertThatThrownBy(
                                                () ->
                                                        current(
                                                                c.getBean(
                                                                        PersonnelOperatorFactsPort
                                                                                .class),
                                                                own("100", "MCH")))
                                        .isInstanceOf(BizException.class));
        assertThatThrownBy(() -> query("E", "S", "100", "MCH", "biz:booking:manage"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unlinkedOwnerAndAccountlessPersonAreNotImpersonated() {
        runner(AT)
                .run(
                        c -> {
                            var r =
                                    current(
                                            c.getBean(PersonnelOperatorFactsPort.class),
                                            own("OWNER", "MCH"));
                            assertThat(r.state()).isEqualTo(LinkState.UNLINKED);
                            assertThat(r.personnel()).isNull();
                            sql.update(
                                    "UPDATE mch_personnel_account SET mch_account_no=NULL WHERE"
                                            + " link_no='L'");
                            assertThat(
                                            current(
                                                            c.getBean(
                                                                    PersonnelOperatorFactsPort
                                                                            .class),
                                                            own("100", "MCH"))
                                                    .state())
                                    .isEqualTo(LinkState.UNLINKED);
                        });
    }

    @Test
    void expiredAssignmentAndStoppedPersonReturnUnlinked() {
        sql.update("UPDATE mch_personnel_store SET valid_to=? WHERE assignment_no='A'", T0);
        runner(AT)
                .run(
                        c -> {
                            var r =
                                    assertDoesNotThrow(
                                            () ->
                                                    current(
                                                            c.getBean(
                                                                    PersonnelOperatorFactsPort
                                                                            .class),
                                                            own("100", "MCH")),
                                            "expired assignment must be authorized absence, never a"
                                                    + " linked or inconsistent projection");
                            assertThat(r.state())
                                    .as("expired assignment at exact upper boundary")
                                    .isEqualTo(LinkState.UNLINKED);
                            sql.update(
                                    "UPDATE mch_personnel_store SET valid_to=? WHERE"
                                            + " assignment_no='A'",
                                    T0.plusHours(1));
                            sql.update(
                                    "UPDATE mch_personnel SET status='SUSPENDED' WHERE"
                                            + " personnel_no='P'");
                            assertThat(
                                            current(
                                                            c.getBean(
                                                                    PersonnelOperatorFactsPort
                                                                            .class),
                                                            own("100", "MCH"))
                                                    .state())
                                    .isEqualTo(LinkState.UNLINKED);
                        });
    }

    @Test
    void duplicateEffectiveAssignmentsFailClosed() {
        assignment("DUP", "P", T0.minusMinutes(1), T0.plusMinutes(1));
        runner(AT)
                .run(
                        c ->
                                assertThatThrownBy(
                                                () ->
                                                        current(
                                                                c.getBean(
                                                                        PersonnelOperatorFactsPort
                                                                                .class),
                                                                own("100", "MCH")))
                                        .isInstanceOf(IllegalStateException.class));
    }

    @Test
    void relinkAdvancesObservedVersions() {
        runner(AT)
                .run(
                        c -> {
                            var p = c.getBean(PersonnelOperatorFactsPort.class);
                            var before = current(p, own("100", "MCH"));
                            sql.update(
                                    "UPDATE mch_personnel_account SET"
                                            + " mch_account_no=NULL,version=version+1 WHERE"
                                            + " link_no='L'");
                            sql.update(
                                    "UPDATE mch_personnel_account SET"
                                            + " mch_account_no='100',version=version+2 WHERE"
                                            + " link_no='L'");
                            sql.update("UPDATE mch_personnel SET version=7 WHERE personnel_no='P'");
                            sql.update(
                                    "UPDATE mch_personnel_store SET version=9 WHERE"
                                            + " assignment_no='A'");
                            var after = current(p, own("100", "MCH")).personnel();
                            assertThat(after.linkRevision())
                                    .isGreaterThan(before.personnel().linkRevision())
                                    .isEqualTo(3);
                            assertThat(after.personnelRevision()).isEqualTo(7);
                            assertThat(after.assignmentRevision()).isEqualTo(9);
                        });
    }

    @Test
    void callerTransactionIsRejected() {
        runner(AT)
                .run(
                        c ->
                                new TransactionTemplate(c.getBean(PlatformTransactionManager.class))
                                        .execute(
                                                s -> {
                                                    assertThatThrownBy(
                                                                    () ->
                                                                            c.getBean(
                                                                                            PersonnelOperatorFactsPort
                                                                                                    .class)
                                                                                    .current(
                                                                                            own(
                                                                                                    "100",
                                                                                                    "MCH")))
                                                            .isInstanceOf(
                                                                    IllegalStateException.class);
                                                    return null;
                                                }));
    }

    @Test
    void indefiniteAssignmentReturnsLinked() {
        sql.update(
                "UPDATE mch_personnel_store SET valid_to=NULL,version=11 WHERE assignment_no='A'");
        sql.update("UPDATE mch_personnel SET version=7 WHERE personnel_no='P'");
        sql.update("UPDATE mch_personnel_account SET version=13 WHERE link_no='L'");
        runner(AT)
                .run(
                        c -> {
                            var r =
                                    current(
                                            c.getBean(PersonnelOperatorFactsPort.class),
                                            own("200", "USR"));
                            assertThat(r.state()).isEqualTo(LinkState.LINKED);
                            assertThat(r.observedAt()).isEqualTo(AT);
                            assertThat(r.personnel())
                                    .isEqualTo(
                                            new PersonnelOperatorFactsPort.OperatorPersonnelFact(
                                                    "P",
                                                    "A",
                                                    7,
                                                    11,
                                                    13,
                                                    AT.minusSeconds(3600),
                                                    null));
                            var row =
                                    c.getBean(MerchantMappers.PersonnelAccountMapper.class)
                                            .selectCurrentOperatorPersonnel(
                                                    "MAIN", "E", "S", "100", T0)
                                            .getFirst();
                            assertThat(row.validTo()).isNull();
                            assertThat(row.validFrom()).isEqualTo(T0.minusHours(1));
                        });
    }

    @Test
    void finiteAssignmentBoundaryIsHalfOpen() {
        runner(AT.minusSeconds(3600))
                .run(
                        c ->
                                assertThat(
                                                current(
                                                                c.getBean(
                                                                        PersonnelOperatorFactsPort
                                                                                .class),
                                                                own("100", "MCH"))
                                                        .state())
                                        .isEqualTo(LinkState.LINKED));
        runner(AT.plusSeconds(3600))
                .run(
                        c ->
                                assertThat(
                                                current(
                                                                c.getBean(
                                                                        PersonnelOperatorFactsPort
                                                                                .class),
                                                                own("100", "MCH"))
                                                        .state())
                                        .isEqualTo(LinkState.UNLINKED));
    }
}
