package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.idem.SysIdempotentMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.port.ResourceFactsPortImpl;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
class ResourceMaintenanceIT {
    private final ObjectMapper json = new ObjectMapper();
    private String database;
    private String url;
    private JdbcTemplate sql;
    private Class<?> required(String name) {
        return assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.merchant." + name));
    }
    @BeforeEach
    void database() throws Exception {
        required("service.impl.ResourceServiceImpl");
        String admin = System.getProperty("resource-maintenance.test.admin-url");
        database = "resource_maintenance_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(admin, "root", ""))
                .execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        url = admin.replace("/mysql?", "/" + database + "?");
        var source = new DriverManagerDataSource(url, "root", "");
        sql = new JdbcTemplate(source);
        String schema = Files.readString(Path.of(System.getProperty("resource-maintenance.test.root"),
                "backend/shop-app/src/test/resources/schema-test.sql"));
        schema = schema.replaceAll("\\b([A-Za-z_]\\w*) IS JSON\\b", "JSON_VALID($1)");
        new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .execute(source);
        sql.update("INSERT INTO mch_entity(entity_no,name,owner_user_no,status,created_at,updated_at) "
                + "VALUES('E','entity','OWNER','ACTIVE',NOW(),NOW())");
        sql.update("INSERT INTO mch_store(store_no,entity_no,name,is_default,status,created_at,updated_at) "
                + "VALUES('S','E','store',1,'ACTIVE',NOW(),NOW()),('B','E','branch',0,'ACTIVE',NOW(),NOW())");
        sql.update("INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,"
                + "created_at,updated_at) VALUES('A','E','USER',0,0,'ACTIVE',NOW(),NOW())");
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
        return new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class,
                required("service.impl.ResourceServiceImpl"), ResourceFactsPortImpl.class, Mappers.class)
                .withBean(ObjectMapper.class, () -> json).withBean(AuditMetaObjectHandler.class)
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=root",
                        "spring.datasource.password=", "shop.resource.publication.archive-only=true");
    }
    private Object req(String type, String value) { return json.readValue(value, required("dto." + type)); }
    private Object call(ApplicationContext context, String name, Object... args) {
        var ctx = context;
        Object target = ctx.getBean(required("service.ResourceService"));
        var method = Arrays.stream(target.getClass().getMethods()).filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow();
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private JsonNode tree(Object value) { return json.readTree(json.writeValueAsString(value)); }
    private String body(String no, long revision, String type, int capacity, String status) {
        return "{\"resourceNo\":" + (no == null ? "null" : "\"" + no + "\"")
                + ",\"expectedRevision\":" + revision + ",\"type\":\"" + type + "\",\"name\":\"床位\","
                + "\"capacity\":" + capacity + ",\"status\":\"" + status + "\"}";
    }
    private JsonNode save(ApplicationContext ctx, String body, String key) {
        return tree(call(ctx, "saveResource", "E", "S", "OWNER", req("SaveResourceReq", body), key));
    }
    private JsonNode create(ApplicationContext ctx) { return save(ctx, body(null, 0, "ROOM", 2, "ACTIVE"), "CREATE"); }
    private void bucket(String no, int held) {
        sql.update("INSERT INTO mch_resource_bucket(bucket_no,entity_no,resource_no,start_at,end_at,capacity,"
                + "held_qty,confirmed_qty,capacity_revision,created_at,updated_at) VALUES('BUCKET','E',?,"
                + "'2026-10-22 10:00:00','2026-10-22 10:05:00',2,?,0,1,NOW(),NOW())", no, held);
    }
    @Test
    void createsPhysicalResourceWithRealFactsAndDurableReplay() {
        runner().run(ctx -> {
            var row = create(ctx);
            assertThat(row.get("revision").asLong()).isOne();
            assertThat(create(ctx)).isEqualTo(row);
            assertThat(row.has("id")).isFalse();
            assertThat(row.has("openOccupationNo")).isFalse();
            var facts = ctx.getBean(ResourceFactsPort.class).getBatch(new ResourceFactsPort.Query(
                    "MAIN", "E", "S", Set.of(row.get("resourceNo").asText()), Instant.now()));
            assertThat(facts).hasSize(1);
            assertThat(facts.getFirst().capacity()).isEqualTo(2);
            assertThat(facts.getFirst().storeNo()).isEqualTo("S");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_resource", Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_staff_log WHERE action='RESOURCE_SAVED'",
                    Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE "
                    + "event_type='MERCHANT_RESOURCE_CHANGED' AND status='ARCHIVED'", Integer.class)).isOne();
            assertThatThrownBy(() -> save(ctx, body(null, 0, "ROOM", 3, "ACTIVE"), "CREATE"))
                    .isInstanceOf(BizException.class);
        });
    }
    @Test
    void revisionsProtectCapacityAndSuspensionAndReplayReturnsCommittedResult() {
        runner().run(ctx -> {
            var row = create(ctx); String no = row.get("resourceNo").asText();
            var changed = save(ctx, body(no, 1, "ROOM", 3, "SUSPENDED"), "UPDATE");
            assertThat(changed.get("revision").asLong()).isEqualTo(2);
            assertThat(changed.get("capacity").asInt()).isEqualTo(3);
            assertThat(save(ctx, body(no, 1, "ROOM", 3, "SUSPENDED"), "UPDATE")).isEqualTo(changed);
            assertThatThrownBy(() -> save(ctx, body(no, 1, "ROOM", 2, "ACTIVE"), "STALE"))
                    .isInstanceOf(BizException.class);
            assertThat(ctx.getBean(ResourceFactsPort.class).getBatch(new ResourceFactsPort.Query(
                    "MAIN", "E", "S", Set.of(no), Instant.now()))).isEmpty();
            var restored = save(ctx, body(no, 2, "ROOM", 3, "ACTIVE"), "RESTORE");
            assertThat(restored.get("revision").asLong()).isEqualTo(3);
        });
    }
    @Test
    void scopeCanonicalIdentityAndTypeCannotBeChanged() {
        runner().run(ctx -> {
            String no = create(ctx).get("resourceNo").asText();
            assertThatThrownBy(() -> call(ctx, "saveResource", "E", "B", "OWNER", req("SaveResourceReq",
                    body(no, 1, "ROOM", 2, "ACTIVE")), "FOREIGN")).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> save(ctx, body(no.toLowerCase(), 1, "ROOM", 2, "ACTIVE"), "ALIAS"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> save(ctx, body(no, 1, "DEVICE", 2, "ACTIVE"), "TYPE"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> save(ctx, body(null, 0, "ROOM", 2, "ACTIVE")
                    .replace("\"name\"", "\"storeNo\":\"B\",\"name\""), "BAD_STORE"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> call(ctx, "listResources", "e", "S", "OWNER",
                    req("ResourceQuery", "{}"))).isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT version FROM mch_resource WHERE resource_no=?", Long.class, no))
                    .isOne();
        });
    }
    @Test
    void paginatesCurrentStoreResourcesAndOneStaffIdentityWithoutCopyingEmployment() {
        sql.update("INSERT INTO mch_personnel(entity_no,personnel_no,name,status,created_at,updated_at) "
                + "VALUES('E','P','person','ACTIVE',NOW(),NOW())");
        sql.update("INSERT INTO mch_personnel_store(entity_no,assignment_no,personnel_no,store_no,valid_from,"
                + "public_visible,sort_order,status,created_at,updated_at) VALUES"
                + "('E','AS','P','S','2026-01-01',1,0,'ACTIVE',NOW(),NOW()),"
                + "('E','AS2','P','S','2030-01-01',1,0,'ACTIVE',NOW(),NOW())");
        sql.update("INSERT INTO mch_resource(entity_no,resource_no,personnel_no,name,resource_type,capacity,status,"
                + "created_at,updated_at) VALUES('E','STAFF','P','person','STAFF',1,'ACTIVE',NOW(),NOW())");
        runner().run(ctx -> {
            create(ctx);
            call(ctx, "saveResource", "E", "B", "OWNER", req("SaveResourceReq",
                    body(null, 0, "DEVICE", 1, "ACTIVE")), "BRANCH");
            var page = tree(call(ctx, "listResources", "E", "S", "OWNER", req("ResourceQuery", "{\"size\":1}")));
            assertThat(page.get("total").asLong()).isEqualTo(2);
            assertThat(page.get("records").size()).isOne();
            var staff = tree(call(ctx, "listResources", "E", "S", "OWNER",
                    req("ResourceQuery", "{\"type\":\"STAFF\",\"keyword\":\"person\"}")));
            assertThat(staff.get("total").asLong()).isOne();
            assertThat(staff.get("records").get(0).get("resourceNo").asText()).isEqualTo("STAFF");
            assertThatThrownBy(() -> save(ctx, body("STAFF", 0, "ROOM", 1, "ACTIVE"), "STAFF_UPDATE"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> save(ctx, body(null, 0, "STAFF", 1, "ACTIVE"), "STAFF_CREATE"))
                    .hasRootCauseInstanceOf(BizException.class);
        });
    }
    @Test
    void usedCapacityIsFrozenAndOutstandingCountersOrOpenOccupationsBlockUpdates() {
        runner().run(ctx -> {
            String no = create(ctx).get("resourceNo").asText(); bucket(no, 1);
            assertThatThrownBy(() -> save(ctx, body(no, 1, "ROOM", 2, "SUSPENDED"), "HELD"))
                    .isInstanceOf(BizException.class);
            sql.update("UPDATE mch_resource_bucket SET held_qty=0");
            assertThatThrownBy(() -> save(ctx, body(no, 1, "ROOM", 3, "ACTIVE"), "BUCKET"))
                    .isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT capacity FROM mch_resource_bucket", Integer.class)).isEqualTo(2);
            sql.update("UPDATE mch_resource SET open_occupation_no='OPEN'");
            assertThatThrownBy(() -> save(ctx, body(no, 1, "ROOM", 2, "SUSPENDED"), "OPEN"))
                    .isInstanceOf(BizException.class);
            sql.update("UPDATE mch_resource SET open_occupation_no=NULL");
            assertThat(save(ctx, body(no, 1, "ROOM", 2, "SUSPENDED"), "FREE").get("revision").asLong())
                    .isEqualTo(2);
        });
    }
    @Test
    void otherStoreUnrecoveredCommandsConservativelyBlockAndLeaveNoPartialWrites() {
        runner().run(ctx -> {
            String no = create(ctx).get("resourceNo").asText();
            sql.update("INSERT INTO mch_resource_command(entity_no,command_no,owner_no,store_no,action,"
                    + "request_key_hash,payload_hash,frozen_payload,expected_owner_version,source_revision,status,"
                    + "initiated_by,created_at,updated_at) VALUES('E','C','O','B','RESERVE',?,?,'{}',0,1,"
                    + "'ACCEPTED','OWNER',NOW(),NOW())", "a".repeat(64), "b".repeat(64));
            assertThatThrownBy(() -> save(ctx, body(no, 1, "ROOM", 2, "SUSPENDED"), "PENDING"))
                    .isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT version FROM mch_resource", Long.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_staff_log", Integer.class)).isOne();
        });
    }
    @Test
    void strictPublicationFailureRollsBackResourceAuditAndKey() {
        runner().withPropertyValues("shop.resource.publication.archive-only=false").run(ctx -> {
            assertThatThrownBy(() -> create(ctx)).isInstanceOf(RuntimeException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_resource", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_staff_log", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isZero();
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
