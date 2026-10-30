package ai.neargo.shop.scenario;

import ai.neargo.shop.infra.ResourceOccupationNativeFixture;
import ai.neargo.shop.infra.ResourceOccupationFixtureConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.idem.SysIdempotentMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.service.MerchantStaffService;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.merchant.port.ScheduleWindowsPortImpl;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Disposable real MySQL structure fixture, not a replay of historical platform Flyway migrations. */
class ScheduleMaintenanceIT {
    private final ObjectMapper json = new ObjectMapper();
    private String database;
    private String url;
    private JdbcTemplate sql;
    private Class<?> required(String name) {
        return assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.merchant." + name));
    }
    @BeforeEach
    void database() throws Exception {
        required("service.impl.ScheduleServiceImpl");
        String admin = System.getProperty("schedule-maintenance.test.admin-url");
        database = "schedule_maintenance_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(admin, "root", ""))
                .execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        url = admin.replace("/mysql?", "/" + database + "?");
        var source = new DriverManagerDataSource(url, "root", "");
        sql = new JdbcTemplate(source);
        String schema = Files.readString(Path.of(System.getProperty("schedule-maintenance.test.root"),
                "backend/shop-app/src/test/resources/schema-test.sql"));
        schema = ResourceOccupationNativeFixture.beforeNativeUpgrade(schema);
        schema = schema.replaceAll("\\b([A-Za-z_]\\w*) IS JSON\\b", "JSON_VALID($1)");
        new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .execute(source);
        ResourceOccupationNativeFixture.install(source);
        sql.update("INSERT INTO mch_entity(entity_no,name,owner_user_no,status,created_at,updated_at) "
                + "VALUES('E','entity','OWNER','ACTIVE',NOW(),NOW())");
        sql.update("INSERT INTO mch_store(store_no,entity_no,name,is_default,status,created_at,updated_at) "
                + "VALUES('S','E','store',1,'ACTIVE',NOW(),NOW()),('B','E','branch',0,'ACTIVE',NOW(),NOW())");
        sql.update("INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,"
                + "created_at,updated_at) VALUES('A','E','USER',0,0,'ACTIVE',NOW(),NOW())");
        sql.update("INSERT INTO mch_resource(entity_no,resource_no,store_no,name,resource_type,capacity,status,"
                + "version,created_at,updated_at) VALUES('E','R','S','room','ROOM',2,'ACTIVE',1,NOW(),NOW())");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant("OWNER", "owner"), null, List.of()));
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of("S", "B"), "S", true));
    }
    @AfterEach
    void clean() {
        BizContext.clear();
        SecurityContextHolder.clearContext();
        if (sql != null) { sql.execute("DROP DATABASE " + database); }
    }
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(ResourceOccupationFixtureConfiguration.class, PrimaryPersistenceConfiguration.class,
                required("service.impl.ScheduleServiceImpl"), required("service.impl.PersonnelServiceImpl"),
                ScheduleWindowsPortImpl.class, Mappers.class)
                .withBean(MerchantStaffService.class, () -> mock(MerchantStaffService.class))
                .withBean(GoodsQueryPort.class, () -> mock(GoodsQueryPort.class))
                .withBean(ObjectMapper.class, () -> json).withBean(AuditMetaObjectHandler.class)
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=root",
                        "spring.datasource.password=", "shop.schedule.publication.archive-only=true",
                        "shop.personnel.publication.archive-only=true");
    }
    private Object req(String type, String value) { return json.readValue(value, required("dto." + type)); }
    private Object call(ApplicationContext context, String name, Object... args) {
        var ctx = context;
        Object target = ctx.getBean(required(name.equals("assignPersonnel")
                ? "service.PersonnelService" : "service.ScheduleService"));
        var method = Arrays.stream(target.getClass().getMethods()).filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow();
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private JsonNode tree(Object value) { return json.readTree(json.writeValueAsString(value)); }
    private String body(String resource, String rule, long revision, int start, int end, String status) {
        return "{\"resourceNo\":\"" + resource + "\",\"ruleNo\":" + (rule == null ? "null" : "\"" + rule + "\"")
                + ",\"expectedRevision\":" + revision + ",\"timezone\":\"UTC\",\"weekdayMask\":127,"
                + "\"startMinute\":" + start + ",\"endMinute\":" + end + ",\"endDayOffset\":0,"
                + "\"validFrom\":\"2026-10-22\",\"validTo\":\"2026-10-23\",\"status\":\"" + status
                + "\",\"reason\":\"排班\"}";
    }
    private JsonNode save(ApplicationContext ctx, String store, String body, String key) {
        return tree(call(ctx, "saveScheduleRule", "E", store, "OWNER", req("SaveScheduleRuleReq", body), key));
    }
    private JsonNode schedule(ApplicationContext ctx, String resource) {
        return tree(call(ctx, "listSchedules", "E", "S", "OWNER", req("ScheduleQuery",
                "{\"resourceNos\":[\"" + resource + "\"],\"from\":\"2026-10-22T00:00:00Z\","
                        + "\"to\":\"2026-10-24T00:00:00Z\"}")));
    }
    private void staff() {
        sql.update("INSERT INTO mch_personnel(entity_no,personnel_no,name,status,created_at,updated_at) "
                + "VALUES('E','P','person','ACTIVE',NOW(),NOW())");
        sql.update("INSERT INTO mch_personnel_store(entity_no,assignment_no,personnel_no,store_no,valid_from,"
                + "valid_to,public_visible,sort_order,status,created_at,updated_at) VALUES"
                + "('E','AS','P','S','2026-01-01','2028-01-01',1,0,'ACTIVE',NOW(),NOW()),"
                + "('E','AB','P','B','2026-01-01','2028-01-01',1,0,'ACTIVE',NOW(),NOW())");
        sql.update("INSERT INTO mch_resource(entity_no,resource_no,personnel_no,name,resource_type,capacity,status,"
                + "version,created_at,updated_at) VALUES('E','T','P','person','STAFF',1,'ACTIVE',1,NOW(),NOW())");
    }
    @Test
    void persistsWeeklyRuleAndReadsActualWindowsWithIdempotentRecovery() {
        runner().run(ctx -> {
            String body = body("R", null, 1, 600, 660, "ACTIVE");
            var saved = save(ctx, "S", body, "CREATE");
            assertThat(saved.get("resourceRevision").asLong()).isEqualTo(2);
            assertThat(save(ctx, "S", body, "CREATE")).isEqualTo(saved);
            var view = schedule(ctx, "R");
            assertThat(view.get("rules").size()).isOne();
            assertThat(view.get("windows").size()).isEqualTo(2);
            assertThat(view.get("windows").get(0).get("startAt").asText()).isEqualTo("2026-10-22T10:00:00Z");
            assertThat(view.get("resourceRevisions").get("R").asLong()).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_schedule_change_log", Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE "
                    + "event_type='MERCHANT_SCHEDULE_CHANGED' AND status='ARCHIVED'", Integer.class)).isOne();
            assertThatThrownBy(() -> save(ctx, "S", body.replace("600", "610"), "CREATE"))
                    .isInstanceOf(BizException.class);
        });
    }
    @Test
    void replacementPreservesHistoryAndInactiveCommandDoesNotCreateAnotherRule() {
        runner().run(ctx -> {
            String old = save(ctx, "S", body("R", null, 1, 600, 660, "ACTIVE"), "FIRST").get("ruleNo").asText();
            var next = save(ctx, "S", body("R", old, 2, 660, 720, "ACTIVE"), "REPLACE");
            assertThat(next.get("ruleNo").asText()).isNotEqualTo(old);
            assertThat(sql.queryForObject("SELECT status FROM mch_schedule_rule WHERE rule_no=?",
                    String.class, old)).isEqualTo("INACTIVE");
            assertThat(schedule(ctx, "R").get("windows").get(0).get("startAt").asText())
                    .isEqualTo("2026-10-22T11:00:00Z");
            save(ctx, "S", body("R", next.get("ruleNo").asText(), 3, 660, 720, "INACTIVE"), "STOP");
            assertThat(schedule(ctx, "R").get("windows").size()).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_schedule_rule", Integer.class)).isEqualTo(2);
            assertThatThrownBy(() -> save(ctx, "S", body("R", null, 1, 600, 660, "ACTIVE"), "STALE"))
                    .isInstanceOf(BizException.class);
        });
    }
    @Test
    void rejectsCrossStoreConflictAfterFirstMonthAndRollsBackParentRevision() {
        staff();
        runner().run(ctx -> {
            save(ctx, "B", body("T", null, 1, 600, 660, "ACTIVE").replace("2026-10-22", "2026-12-20")
                    .replace("2026-10-23", "2026-12-21"), "OTHER");
            assertThatThrownBy(() -> save(ctx, "S", body("T", null, 2, 600, 660, "ACTIVE")
                    .replace("2026-10-23", "2026-12-31"), "CONFLICT"))
                    .isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT version FROM mch_resource WHERE resource_no='T'", Long.class))
                    .isEqualTo(2);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_schedule_rule", Integer.class)).isOne();
        });
    }
    @Test
    void effectiveEmploymentClipsWindowsAndAdjacentCrossStoreShiftsAreAllowed() {
        staff();
        sql.update("UPDATE mch_personnel_store SET valid_from='2026-10-22 10:30:00',"
                + "valid_to='2026-10-22 11:00:00' WHERE assignment_no='AS'");
        runner().run(ctx -> {
            save(ctx, "S", body("T", null, 1, 600, 660, "ACTIVE"), "STORE_S");
            save(ctx, "B", body("T", null, 2, 660, 720, "ACTIVE"), "STORE_B");
            var windows = schedule(ctx, "T").get("windows");
            assertThat(windows.size()).isOne();
            assertThat(windows.get(0).get("startAt").asText()).isEqualTo("2026-10-22T10:30:00Z");
            assertThat(windows.get(0).get("endAt").asText()).isEqualTo("2026-10-22T11:00:00Z");
        });
    }
    @Test
    void rejectsDstAmbiguousAndMissingEndpointsForEntireEffectivePeriod() {
        runner().run(ctx -> {
            String repeated = body("R", null, 1, 90, 150, "ACTIVE").replace("UTC", "America/New_York")
                    .replace("2026-10-22", "2026-11-01").replace("2026-10-23", "2026-11-01");
            assertThatThrownBy(() -> save(ctx, "S", repeated, "FOLD")).isInstanceOf(BizException.class);
            String missing = body("R", null, 1, 150, 210, "ACTIVE").replace("UTC", "America/New_York")
                    .replace("2026-10-22", "2027-03-14").replace("2026-10-23", "2027-03-14");
            assertThatThrownBy(() -> save(ctx, "S", missing, "GAP")).isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_schedule_rule", Integer.class)).isZero();
        });
    }
    @Test
    void canonicalScopeAndOccupancyGuardRejectWithoutPartialWrites() {
        runner().run(ctx -> {
            String body = body("R", null, 1, 600, 660, "ACTIVE");
            assertThatThrownBy(() -> save(ctx, "B", body, "FOREIGN")).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> save(ctx, "S", body.replace("\"R\"", "\"r\""), "ALIAS"))
                    .isInstanceOf(BizException.class);
            sql.update("UPDATE mch_resource SET open_occupation_no='OPEN'");
            assertThatThrownBy(() -> save(ctx, "S", body, "OCCUPIED")).isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_schedule_rule", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT version FROM mch_resource", Long.class)).isOne();
        });
    }
    @Test
    void strictPublishFailureRollsBackRuleHistoryAuditAndRevision() {
        runner().withPropertyValues("shop.schedule.publication.archive-only=false").run(ctx -> {
            assertThatThrownBy(() -> save(ctx, "S", body("R", null, 1, 600, 660, "ACTIVE"), "FAIL"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_schedule_rule", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_schedule_change_log", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT version FROM mch_resource", Long.class)).isOne();
        });
    }
    @Test
    void concurrentSameRevisionAllowsOnlyOneRuleAndOneParentAdvance() {
        runner().run(ctx -> {
            var gate = new CountDownLatch(1);
            var results = IntStream.range(0, 2).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                try {
                    gate.await();
                    save(ctx, "S", body("R", null, 1, 600, 660, "ACTIVE"), "RACE" + i);
                    return "SAVED";
                } catch (BizException conflict) { return "CONFLICT"; }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
            })).toList();
            gate.countDown();
            assertThat(results.stream().map(CompletableFuture::join).toList())
                    .containsExactlyInAnyOrder("SAVED", "CONFLICT");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_schedule_rule", Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT version FROM mch_resource", Long.class)).isEqualTo(2);
        });
    }
    @Test
    void employmentExtensionCannotReactivateClippedCrossStoreOverlap() {
        staff();
        sql.update("UPDATE mch_personnel_store SET valid_from='2026-10-22 10:00:00',"
                + "valid_to='2026-10-22 10:30:00' WHERE assignment_no='AS'");
        runner().run(ctx -> {
            var saved = save(ctx, "S", body("T", null, 1, 600, 660, "ACTIVE"), "S_RULE");
            save(ctx, "B", body("T", null, 2, 630, 660, "ACTIVE"), "B_RULE");
            String assignment = "{\"expectedRevision\":0,\"assignmentNo\":\"AS\",\"storeNo\":\"S\","
                    + "\"validFrom\":\"2026-10-22T10:00:00Z\",\"validTo\":\"2026-10-22T11:00:00Z\","
                    + "\"publicVisible\":true,\"sortOrder\":0,\"positions\":[\"TECHNICIAN\"],\"skills\":[]}";
            assertThatThrownBy(() -> call(ctx, "assignPersonnel", "E", "S", "OWNER", "P",
                    req("AssignPersonnelReq", assignment), "EXTEND")).isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT version FROM mch_personnel", Long.class)).isZero();
            assertThat(schedule(ctx, "T").get("windows").size()).isOne();
            save(ctx, "S", body("T", saved.get("ruleNo").asText(), 3, 600, 660, "INACTIVE"), "REMOVE_RULE");
            assertDoesNotThrow(() -> call(ctx, "assignPersonnel", "E", "S", "OWNER", "P",
                    req("AssignPersonnelReq", assignment), "EXTEND_AFTER_REMOVAL"));
        });
    }
    @Test
    void newEmploymentInAnUnassignedGapCannotActivateStoredWeeklyConflict() {
        staff();
        sql.update("UPDATE mch_personnel_store SET valid_to='2026-10-22 11:00:00' WHERE assignment_no='AS'");
        sql.update("UPDATE mch_personnel_store SET valid_from='2026-10-23 00:00:00' WHERE assignment_no='AB'");
        runner().run(ctx -> {
            save(ctx, "S", body("T", null, 1, 600, 660, "ACTIVE"), "S_RULE");
            save(ctx, "B", body("T", null, 2, 600, 660, "ACTIVE"), "B_RULE");
            String assignment = "{\"expectedRevision\":0,\"storeNo\":\"S\","
                    + "\"validFrom\":\"2026-10-23T00:00:00Z\",\"validTo\":\"2026-10-24T00:00:00Z\","
                    + "\"publicVisible\":true,\"sortOrder\":0,\"positions\":[\"TECHNICIAN\"],\"skills\":[]}";
            assertThatThrownBy(() -> call(ctx, "assignPersonnel", "E", "S", "OWNER", "P",
                    req("AssignPersonnelReq", assignment), "GAP")).isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT version FROM mch_personnel", Long.class)).isZero();
        });
    }
    @Configuration(proxyBeanMethods = false)
    @MapperScan(basePackageClasses = MerchantMappers.class)
    static class Mappers {
        @Bean
        IdempotencyService idempotency(SqlSessionFactory factory, ObjectMapper json) {
            if (!factory.getConfiguration().hasMapper(SysIdempotentMapper.class)) {
                factory.getConfiguration().addMapper(SysIdempotentMapper.class);
            }
            return new IdempotencyService(new SqlSessionTemplate(factory).getMapper(SysIdempotentMapper.class), json);
        }
    }
}
