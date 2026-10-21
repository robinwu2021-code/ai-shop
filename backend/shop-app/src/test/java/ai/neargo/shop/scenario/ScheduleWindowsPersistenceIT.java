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
class ScheduleWindowsPersistenceIT {
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
        required("spi.schedule.ScheduleWindowsPort");
        String adminUrl = System.getProperty("schedule.test.admin-url");
        assertThat(adminUrl).isNotBlank();
        String database = "schedule_" + UUID.randomUUID().toString().replace("-", "");
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
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V533__shared_resource_identity.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V534__shared_schedule_rules.sql"));
        }
        sql.execute("INSERT INTO mch_entity(tenant_no,entity_no,status,deleted) VALUES('MAIN','E','ACTIVE',0)");
        sql.execute("INSERT INTO mch_store(tenant_no,entity_no,store_no,status,deleted) "
                + "VALUES('MAIN','E','S','ACTIVE',0),('MAIN','E','OTHER','ACTIVE',0)");
        seed("mch_personnel", "personnel_no", "P", "name", "PERSON", "status", "ACTIVE", "contact_ref", "SECRET");
        assignment("A", "S", T0.minusYears(1), null);
        resource("R", "STAFF", null, "P", 1);
        rule("W", "R", "S", 540, 1020, 0);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant("ACTOR", "operator"), null, List.of()));
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class,
                Mappers.class, required("merchant.config.PersonnelDataScopeRegistration"),
                required("merchant.port.ScheduleWindowsPortImpl")).withBean(AuditMetaObjectHandler.class)
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=root",
                        "spring.datasource.password=");
    }

    private Object query(String store, Set<String> resources, Instant from, Instant until) {
        return assertDoesNotThrow(() -> required("spi.schedule.ScheduleWindowsPort$Query")
                .getConstructor(String.class, String.class, String.class, Set.class, Instant.class, Instant.class)
                .newInstance("MAIN", "E", store, resources, from, until));
    }

    private List<?> windows(ApplicationContext context, String store, Instant from, Instant until) {
        return windows(context, query(store, Set.of("R"), from, until));
    }

    private List<?> windows(ApplicationContext context, Object query) {
        try {
            return (List<?>) required("spi.schedule.ScheduleWindowsPort").getMethod("getBatch", query.getClass())
                    .invoke(context.getBean(required("spi.schedule.ScheduleWindowsPort")), query);
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

    private void rule(String no, String resource, String store, int start, int end, int offset) {
        seed("mch_schedule_rule", "rule_no", no, "resource_no", resource, "store_no", store,
                "timezone", "UTC", "weekday_mask", 4, "start_minute", start, "end_minute", end,
                "end_day_offset", offset, "valid_from", T0.toLocalDate(), "status", "ACTIVE");
    }

    private void period(Object window, String from, String until) {
        assertThat(field(window, "startAt")).isEqualTo(Instant.parse(from));
        assertThat(field(window, "endAt")).isEqualTo(Instant.parse(until));
    }

    @AfterEach
    void cleanup() {
        DataScopeContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void isoWeekdayAndInclusiveRuleDatesProduceRealUtcWindows() {
        sql.update("UPDATE mch_schedule_rule SET valid_to=valid_from");
        runner().run(context -> {
            var rows = windows(context, "S", AT, AT.plusSeconds(86400));
            assertThat(rows).hasSize(1);
            period(rows.getFirst(), "2026-10-21T09:00:00Z", "2026-10-21T17:00:00Z");
            assertThat(windows(context, "S", AT.minusSeconds(86400), AT)).isEmpty();
            assertThat(windows(context, "S", AT.plusSeconds(604800), AT.plusSeconds(691200))).isEmpty();
        });
    }

    @Test
    void overnightUsesPreviousLocalAnchorIncludingItsLastValidDate() {
        sql.update("UPDATE mch_schedule_rule SET start_minute=1320,end_minute=120,end_day_offset=1,"
                + "weekday_mask=2,valid_from='2026-10-20',valid_to='2026-10-20'");
        runner().run(context -> {
            var rows = windows(context, "S", AT, AT.plusSeconds(10800));
            assertThat(rows).hasSize(1);
            period(rows.getFirst(), "2026-10-21T00:00:00Z", "2026-10-21T02:00:00Z");
        });
    }

    @Test
    void timezoneAndQueryClippingDoNotUseUtcWeekdayInsteadOfLocalWeekday() {
        sql.update("UPDATE mch_schedule_rule SET timezone='Asia/Shanghai',start_minute=0,end_minute=120");
        runner().run(context -> {
            var rows = windows(context, "S", AT.minusSeconds(32400), AT);
            assertThat(rows).hasSize(1);
            period(rows.getFirst(), "2026-10-20T16:00:00Z", "2026-10-20T18:00:00Z");
        });
    }

    @Test
    void midnightEndCanUseMinute1440WithoutInventingAnExtraDay() {
        sql.update("UPDATE mch_schedule_rule SET start_minute=1380,end_minute=1440");
        runner().run(context -> period(windows(context, "S", AT, AT.plusSeconds(86400)).getFirst(),
                "2026-10-21T23:00:00Z", "2026-10-22T00:00:00Z"));
    }

    @Test
    void sameAssignmentRulesMergeWithoutDuplicatingCapacityAndKeepProvenance() {
        rule("W2", "R", "S", 960, 1200, 0);
        runner().run(context -> {
            var rows = windows(context, "S", AT, AT.plusSeconds(86400));
            assertThat(rows).hasSize(1);
            period(rows.getFirst(), "2026-10-21T09:00:00Z", "2026-10-21T20:00:00Z");
            assertThat(field(rows.getFirst(), "ruleNos")).isEqualTo(List.of("W", "W2"));
        });
    }

    @Test
    void employmentClipsWindowsAndAdjacentAssignmentsRemainSeparate() {
        sql.update("UPDATE mch_personnel_store SET valid_from=?,valid_to=?", T0.plusHours(10), T0.plusHours(12));
        assignment("A2", "S", T0.plusHours(12), T0.plusHours(14));
        runner().run(context -> {
            var rows = windows(context, "S", AT, AT.plusSeconds(86400));
            assertThat(rows).hasSize(2);
            period(rows.getFirst(), "2026-10-21T10:00:00Z", "2026-10-21T12:00:00Z");
            period(rows.getLast(), "2026-10-21T12:00:00Z", "2026-10-21T14:00:00Z");
            assertThat(field(rows.getFirst(), "assignmentNo")).isEqualTo("A");
            assertThat(field(rows.getLast(), "assignmentNo")).isEqualTo("A2");
        });
    }

    @Test
    void crossStoreOverlapFailsEvenWhenCallerRequestsOnlyOneStore() {
        assignment("A2", "OTHER", T0, null);
        rule("PRIVATE_RULE", "R", "OTHER", 600, 720, 0);
        runner().run(context -> assertThatThrownBy(() -> windows(context, "S", AT, AT.plusSeconds(86400)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cross-store")
                .hasMessageNotContaining("PRIVATE_RULE").hasMessageNotContaining("OTHER"));
    }

    @Test
    void adjacentStoreHandoffIsAllowedAndForeignRulesAreNotExported() {
        assignment("A2", "OTHER", T0, null);
        rule("PRIVATE_RULE", "R", "OTHER", 1020, 1200, 0);
        runner().run(context -> {
            var rows = windows(context, "S", AT, AT.plusSeconds(86400));
            assertThat(rows).hasSize(1);
            assertThat(rows.toString()).doesNotContain("PRIVATE_RULE", "OTHER", "A2");
            period(windows(context, "OTHER", AT, AT.plusSeconds(86400)).getFirst(),
                    "2026-10-21T17:00:00Z", "2026-10-21T20:00:00Z");
        });
    }

    @Test
    void overlappingAssignmentsFailInsteadOfBeingMergedAsOneShift() {
        assignment("A2", "S", T0.plusHours(10), T0.plusHours(12));
        runner().run(context -> assertThatThrownBy(() -> windows(context, "S", AT, AT.plusSeconds(86400)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ambiguous"));
    }

    @Test
    void caseEquivalentReferencesReturnCanonicalResourceStoreAndAssignment() {
        sql.update("UPDATE mch_resource SET personnel_no='p'");
        sql.update("UPDATE mch_personnel_store SET personnel_no='p',store_no='s'");
        sql.update("UPDATE mch_schedule_rule SET resource_no='r',store_no='s'");
        runner().run(context -> {
            Object row = windows(context, query("s", Set.of("r"), AT, AT.plusSeconds(86400))).getFirst();
            assertThat(field(row, "resourceNo")).isEqualTo("R");
            assertThat(field(row, "storeNo")).isEqualTo("S");
            assertThat(field(row, "assignmentNo")).isEqualTo("A");
        });
    }

    @Test
    void physicalResourcesUseOnlyTheirOwnStoreWithoutPersonnelAssignment() {
        resource("ROOM", "ROOM", "S", null, 3);
        resource("DEVICE", "DEVICE", "OTHER", null, 1);
        rule("ROOM_RULE", "ROOM", "S", 600, 720, 0);
        rule("DEVICE_RULE", "DEVICE", "S", 600, 720, 0);
        runner().run(context -> {
            var rows = windows(context, query("S", Set.of("ROOM", "DEVICE"), AT, AT.plusSeconds(86400)));
            assertThat(rows).hasSize(1);
            assertThat(field(rows.getFirst(), "resourceNo")).isEqualTo("ROOM");
            assertThat(field(rows.getFirst(), "assignmentNo")).isNull();
        });
    }

    @Test
    void inactiveAndArchivedParentChainsBlockWindows() {
        String[] statements = {"UPDATE mch_resource SET status='SUSPENDED'",
                "UPDATE mch_personnel SET status='SUSPENDED'", "UPDATE mch_personnel_store SET deleted=1",
                "UPDATE mch_store SET status='INACTIVE' WHERE store_no='S'",
                "UPDATE mch_entity SET archived_at='2026-10-21 00:00:00'",
                "UPDATE mch_schedule_rule SET status='INACTIVE'"};
        for (String statement : statements) {
            sql.update(statement);
            runner().run(context -> assertThat(windows(context, "S", AT, AT.plusSeconds(86400))).isEmpty());
            sql.update("UPDATE mch_resource SET status='ACTIVE'");
            sql.update("UPDATE mch_personnel SET status='ACTIVE'");
            sql.update("UPDATE mch_personnel_store SET deleted=0");
            sql.update("UPDATE mch_store SET status='ACTIVE'");
            sql.update("UPDATE mch_entity SET archived_at=NULL");
            sql.update("UPDATE mch_schedule_rule SET status='ACTIVE'");
        }
    }

    @Test
    void foreignTenantAndSubjectReferencesCannotProduceWindows() {
        sql.update("UPDATE mch_schedule_rule SET tenant_no='FOREIGN'");
        runner().run(context -> assertThat(windows(context, "S", AT, AT.plusSeconds(86400))).isEmpty());
        sql.update("UPDATE mch_schedule_rule SET tenant_no='MAIN',entity_no='FOREIGN'");
        runner().run(context -> assertThat(windows(context, "S", AT, AT.plusSeconds(86400))).isEmpty());
        sql.update("UPDATE mch_schedule_rule SET entity_no='E'");
        sql.update("UPDATE mch_personnel_store SET entity_no='FOREIGN'");
        runner().run(context -> assertThat(windows(context, "S", AT, AT.plusSeconds(86400))).isEmpty());
    }

    @Test
    void dstGapIsRejectedOnlyWhenItsPossibleWindowAffectsTheQuery() {
        sql.update("UPDATE mch_schedule_rule SET timezone='America/New_York',weekday_mask=64,"
                + "valid_from='2026-01-01',start_minute=150,end_minute=240");
        Instant day = Instant.parse("2026-03-08T00:00:00Z");
        runner().run(context -> {
            assertThatThrownBy(() -> windows(context, "S", day, day.plusSeconds(86400)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("DST");
            assertThat(windows(context, "S", day.plusSeconds(86400), day.plusSeconds(172800))).isEmpty();
        });
    }

    @Test
    void midnightDstGapOnNextLocalDateStillChecksItsPossibleUtcRange() {
        sql.update("UPDATE mch_schedule_rule SET timezone='America/Santiago',weekday_mask=64,"
                + "valid_from='2026-01-01',start_minute=30,end_minute=120");
        Instant from = Instant.parse("2026-09-06T03:15:00Z");
        runner().run(context -> {
            assertThatThrownBy(() -> windows(context, "S", from, Instant.parse("2026-09-06T03:45:00Z")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("DST");
            assertThatThrownBy(() -> windows(context, "S", from, Instant.parse("2026-09-06T04:15:00Z")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("DST");
        });
    }

    @Test
    void dstOverlapIsRejectedRatherThanChoosingAnOffset() {
        sql.update("UPDATE mch_schedule_rule SET timezone='America/New_York',weekday_mask=64,"
                + "start_minute=90,end_minute=180");
        Instant day = Instant.parse("2026-11-01T00:00:00Z");
        runner().run(context -> assertThatThrownBy(() -> windows(context, "S", day, day.plusSeconds(86400)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DST"));
    }

    @Test
    void uniqueEndpointsAcrossDstReturnActualElapsedTime() {
        sql.update("UPDATE mch_schedule_rule SET timezone='America/New_York',weekday_mask=64,"
                + "start_minute=0,end_minute=240");
        Instant day = Instant.parse("2026-11-01T00:00:00Z");
        runner().run(context -> period(windows(context, "S", day, day.plusSeconds(86400)).getFirst(),
                "2026-11-01T04:00:00Z", "2026-11-01T09:00:00Z"));
    }

    @Test
    void invalidTimezoneAndDatabaseFailuresDoNotPretendNoShiftExists() {
        sql.update("UPDATE mch_schedule_rule SET timezone='INVALID/ZONE'");
        runner().run(context -> assertThatThrownBy(() -> windows(context, "S", AT, AT.plusSeconds(86400)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("timezone"));
        sql.execute("DROP TABLE mch_schedule_rule");
        runner().run(context -> assertThatThrownBy(() -> windows(context, "S", AT, AT.plusSeconds(86400)))
                .hasRootCauseInstanceOf(SQLException.class));
    }

    @Test
    void oversizedJoinedRowsAreRejectedBeforeExpansion() {
        for (int i = 0; i < 2000; i++) {
            assignment("A" + i, "S", T0, null);
        }
        runner().run(context -> assertThatThrownBy(() -> windows(context, "S", AT, AT.plusSeconds(86400)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too many"));
    }

    @Test
    void excessiveExpandedWindowsFailInsteadOfReturningPartialCalendar() {
        for (int i = 0; i < 333; i++) {
            rule("W" + i, "R", "S", 540, 1020, 0);
        }
        sql.update("UPDATE mch_schedule_rule SET weekday_mask=127");
        runner().run(context -> assertThatThrownBy(() -> windows(context, "S", AT, AT.plusSeconds(2678400)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too many"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestAndResultsAreBoundedImmutableAndUsePersistedMillisecondPrecision() throws Exception {
        var constructor = required("spi.schedule.ScheduleWindowsPort$Query").getConstructor(String.class,
                String.class, String.class, Set.class, Instant.class, Instant.class);
        assertThatThrownBy(() -> constructor.newInstance("MAIN", "E", "S", Set.of(), AT, AT.plusSeconds(1)))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        Set<String> excess = IntStream.range(0, 51).mapToObj(i -> "R" + i).collect(Collectors.toSet());
        assertThatThrownBy(() -> constructor.newInstance("MAIN", "E", "S", excess, AT, AT.plusSeconds(1)))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> constructor.newInstance("MAIN", "E", "S", Set.of("R"), AT, AT))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> constructor.newInstance("MAIN", "E", "S", Set.of("R"), AT,
                AT.plusSeconds(2678400).plusMillis(1))).hasCauseInstanceOf(IllegalArgumentException.class);
        Object query = query("S", Set.of("R"), AT.plusNanos(1), AT.plusSeconds(86400).plusNanos(1));
        assertThat(field(query, "startAt")).isEqualTo(AT);
        assertThatThrownBy(() -> ((Set<Object>) field(query, "resourceNos")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        runner().run(context -> {
            var rows = windows(context, query);
            assertThatThrownBy(rows::clear).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> ((List<Object>) field(rows.getFirst(), "ruleNos")).clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test
    void formalDdlRejectsInvalidRuleShapesAndMalformedChangeEvidence() {
        String[] updates = {"weekday_mask=0", "weekday_mask=128", "start_minute=-1", "end_minute=1441",
                "end_day_offset=2", "start_minute=600,end_minute=600", "end_day_offset=1,end_minute=1440",
                "valid_to='2026-10-20'", "status='UNKNOWN'", "version=-1", "deleted=2"};
        for (String update : updates) {
            assertThatThrownBy(() -> sql.update("UPDATE mch_schedule_rule SET " + update))
                    .hasRootCauseInstanceOf(SQLException.class);
        }
        seed("mch_schedule_change_log", "change_no", "C", "resource_no", "R", "source_ref", "W",
                "before_revision", 0L, "after_revision", 1L, "affected_refs", "[]", "reason", "EDIT");
        assertThatThrownBy(() -> sql.update("UPDATE mch_schedule_change_log SET affected_refs='BROKEN'"))
                .hasRootCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> sql.update("UPDATE mch_schedule_change_log SET after_revision=0"))
                .hasRootCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> rule("W", "R", "S", 540, 1020, 0)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualEntityInsertUsesAuditAndRuleAndHistorySubjectScopes() {
        runner().run(context -> {
            var bean = new BeanWrapperImpl(required("merchant.entity.MchScheduleChangeLog"));
            bean.setPropertyValue("entityNo", "E");
            bean.setPropertyValue("changeNo", "C");
            bean.setPropertyValue("resourceNo", "R");
            bean.setPropertyValue("sourceRef", "W");
            bean.setPropertyValue("beforeRevision", 0L);
            bean.setPropertyValue("afterRevision", 1L);
            bean.setPropertyValue("affectedRefs", "[]");
            bean.setPropertyValue("reason", "PRIVATE_REASON");
            var history = (BaseMapper<Object>) context.getBean(
                    required("merchant.mapper.MerchantMappers$ScheduleChangeLogMapper"));
            var rules = (BaseMapper<Object>) context.getBean(
                    required("merchant.mapper.MerchantMappers$ScheduleRuleMapper"));
            assertThat(DataScopeContext.executeWithoutScope(() -> history.insert(bean.getWrappedInstance()))).isOne();
            assertThat(sql.queryForObject("SELECT created_by FROM mch_schedule_change_log", String.class))
                    .isEqualTo("ACTOR");
            DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("FOREIGN")));
            assertThat(windows(context, "S", AT, AT.plusSeconds(86400))).hasSize(1);
            assertThat(rules.selectList(null)).isEmpty();
            assertThat(history.selectList(null)).isEmpty();
        });
    }
}
