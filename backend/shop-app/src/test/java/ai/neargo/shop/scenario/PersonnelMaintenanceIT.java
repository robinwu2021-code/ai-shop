package ai.neargo.shop.scenario;

import ai.neargo.shop.infra.ResourceOccupationNativeFixture;
import ai.neargo.shop.infra.ResourceOccupationFixtureConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.service.impl.MerchantStaffServiceImpl;
import ai.neargo.shop.merchant.service.impl.StaffAuditLogger;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.context.annotation.Primary;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.idem.SysIdempotentMapper;
import ai.neargo.shop.merchant.service.MerchantStaffService;
import ai.neargo.shop.merchant.port.PersonnelFactsPortImpl;
import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import java.time.Instant;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

/** Actual MySQL rows/transactions; product and legacy staff collaborators are explicit controlled boundaries. */
class PersonnelMaintenanceIT {
    private final ObjectMapper json = new ObjectMapper();
    private String database;
    private String url;
    private JdbcTemplate sql;
    private static final String ASSIGN = "{\"expectedRevision\":0,\"storeNo\":\"S\","
            + "\"validFrom\":\"2026-01-01T00:00:00Z\",\"validTo\":null,\"publicVisible\":true,"
            + "\"sortOrder\":0,\"positions\":[\"TECHNICIAN\"],\"skills\":[{\"goodsNo\":\"G\",\"skuNo\":\"SKU\"}]}";
    private Class<?> required(String name) {
        return assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.merchant." + name));
    }
    @BeforeEach
    void database() throws Exception {
        required("service.impl.PersonnelServiceImpl");
        String admin = System.getProperty("personnel-maintenance.test.admin-url");
        database = "personnel_maintenance_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(admin, "root", ""))
                .execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        url = admin.replace("/mysql?", "/" + database + "?");
        var source = new DriverManagerDataSource(url, "root", "");
        sql = new JdbcTemplate(source);
        String schema = Files.readString(Path.of(System.getProperty("personnel-maintenance.test.root"),
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
                required("service.impl.PersonnelServiceImpl"), PersonnelFactsPortImpl.class, Mappers.class)
                .withBean(ObjectMapper.class, () -> json).withBean(AuditMetaObjectHandler.class)
                .withBean(MerchantStaffService.class, () -> mock(MerchantStaffService.class))
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=root",
                        "spring.datasource.password=", "shop.personnel.publication.archive-only=true");
    }
    private Object req(String type, String value) { return json.readValue(value, required("dto." + type)); }
    private Object call(ApplicationContext context, String name, Object... args) {
        Object target = context.getBean(required("service.PersonnelService"));
        var method = Arrays.stream(target.getClass().getMethods()).filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow();
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private JsonNode tree(Object value) { return json.readTree(json.writeValueAsString(value)); }
    private JsonNode create(ApplicationContext context, String key) {
        return tree(call(context, "createPersonnel", "E", "S", "OWNER", req("CreatePersonnelReq",
                "{\"displayName\":\"技师\",\"assignment\":" + ASSIGN + "}"), key));
    }
    private Object assign(ApplicationContext context, JsonNode person, long revision, String definition, String key) {
        return call(context, "assignPersonnel", "E", "S", "OWNER", person.get("personnelNo").asText(),
                req("AssignPersonnelReq", definition.replace("\"expectedRevision\":0",
                        "\"expectedRevision\":" + revision)), key);
    }
    @Test
    void createsNoLoginPersonWithOneCanonicalStaffAndDurableReplay() {
        runner().run(context -> {
            var person = create(context, "CREATE");
            assertThat(create(context, "CREATE")).isEqualTo(person);
            assertThat(person.get("revision").asLong()).isEqualTo(1);
            var facts = context.getBean(PersonnelFactsPort.class).getBatch(new PersonnelFactsPort.Query(
                    "MAIN", "E", "S", Set.of(person.get("personnelNo").asText()),
                    Instant.parse("2026-10-22T00:00:00Z")));
            assertThat(facts).hasSize(1);
            assertThat(facts.getFirst().positionCodes()).containsExactly("TECHNICIAN");
            assertThat(facts.getFirst().qualifications().getFirst().skuNo()).isEqualTo("SKU");
            var page = tree(call(context, "listPersonnel", "E", "S", "OWNER", req("PersonnelQuery", "{}")));
            assertThat(page.get("total").asLong()).isOne();
            var matched = tree(call(context, "listPersonnel", "E", "S", "OWNER",
                    req("PersonnelQuery", "{\"skill\":\"G\"}")));
            assertThat(matched.get("total").asLong()).isOne();
            var unmatched = tree(call(context, "listPersonnel", "E", "S", "OWNER",
                    req("PersonnelQuery", "{\"skill\":\"OTHER\"}")));
            assertThat(unmatched.get("total").asLong()).isZero();
            assertThatThrownBy(() -> call(context, "listPersonnel", "E", "S", "OWNER",
                    req("PersonnelQuery", "{\"storeNo\":\"B\"}"))).isInstanceOf(BizException.class);
            assertThat(person.toString()).contains("TECHNICIAN", "SKU").doesNotContain("contactRef", "\"id\"");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_resource WHERE entity_no='E' "
                    + "AND resource_type='STAFF' AND store_no IS NULL AND capacity=1", Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_personnel_account WHERE entity_no='E'",
                    Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE "
                    + "event_type='MERCHANT_PERSONNEL_CHANGED' AND status='ARCHIVED'", Integer.class)).isOne();
        });
    }
    @Test
    void overlappingEmploymentAndStaleParentRevisionDoNotWriteChildren() {
        runner().run(context -> {
            var person = create(context, "P");
            assertThatThrownBy(() -> assign(context, person, 1, ASSIGN, "OVERLAP"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> assign(context, person, 0, ASSIGN, "STALE"))
                    .isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_personnel_store WHERE entity_no='E'",
                    Integer.class)).isOne();
        });
    }
    @Test
    void foreignSkuAndStoreCannotCreatePersonnelOrLeaveIdempotencyRows() {
        runner().run(context -> {
            Object request = req("CreatePersonnelReq", "{\"displayName\":\"技师\",\"assignment\":"
                    + ASSIGN.replace("SKU", "FOREIGN") + "}");
            assertThatThrownBy(() -> call(context, "createPersonnel", "E", "S", "OWNER", request, "BAD"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> call(context, "createPersonnel", "E", "B", "OWNER",
                    req("CreatePersonnelReq", "{\"displayName\":\"技师\",\"assignment\":" + ASSIGN + "}"), "CROSS"))
                    .isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_personnel WHERE entity_no='E'", Integer.class))
                    .isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_idempotent WHERE idem_key='BAD'", Integer.class))
                    .isZero();
        });
    }
    @Test
    void linkedAccountCannotBelongToTwoPeopleAndUnlinkWritesActualNull() {
        runner().run(context -> {
            var a = create(context, "P1");
            var b = create(context, "P2");
            Object link = req("LinkPersonnelAccountReq", "{\"expectedRevision\":1,\"accountNo\":\"A\"}");
            call(context, "linkPersonnelAccount", "E", "S", "OWNER", a.get("personnelNo").asText(), link, "LINK1");
            assertThatThrownBy(() -> call(context, "linkPersonnelAccount", "E", "S", "OWNER",
                    b.get("personnelNo").asText(), link, "LINK2")).isInstanceOf(BizException.class);
            call(context, "linkPersonnelAccount", "E", "S", "OWNER", a.get("personnelNo").asText(),
                    req("LinkPersonnelAccountReq", "{\"expectedRevision\":2,\"accountNo\":null}"), "UNLINK");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_personnel_account "
                    + "WHERE entity_no='E' AND mch_account_no IS NOT NULL", Integer.class)).isZero();
            var last = json.readTree(sql.queryForList("SELECT detail FROM mch_staff_log WHERE entity_no='E' "
                    + "AND action='PERSONNEL_ACCOUNT_LINKED' ORDER BY id", String.class).getLast());
            assertThat(last.has("previousAccountNo")).isTrue();
            assertThat(last.get("previousAccountNo").asText()).isEqualTo("A");
            assertThat(last.get("accountNo").isNull()).isTrue();
        });
    }
    @Test
    void openOccupationBlocksPersonnelSuspensionAndKeepsRevision() {
        runner().run(context -> {
            var person = create(context, "P");
            sql.update("UPDATE mch_resource SET open_occupation_no='OPEN' WHERE entity_no='E'");
            assertThatThrownBy(() -> call(context, "changePersonnelStatus", "E", "S", "OWNER",
                    person.get("personnelNo").asText(), req("ChangePersonnelStatusReq",
                            "{\"expectedRevision\":1,\"status\":\"SUSPENDED\",\"reason\":\"停用\"}"), "STOP"))
                    .isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT version FROM mch_personnel WHERE entity_no='E'", Long.class))
                    .isEqualTo(1);
        });
    }
    @Test
    void committedCreateReplaySurvivesProductOutage() {
        runner().run(context -> {
            var person = create(context, "RECOVER");
            when(context.getBean(GoodsQueryPort.class).snapshot(List.of("SKU"), Map.of("E", "S")))
                    .thenThrow(new IllegalStateException("owner offline"));
            assertThat(assertDoesNotThrow(() -> create(context, "RECOVER"))).isEqualTo(person);
            assertThatThrownBy(() -> create(context, "NEW"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("owner offline");
        });
    }
    @Test
    void replacementPreservesOldEmploymentAndRevokesOnlyItsQualificationPeriod() {
        runner().run(context -> {
            var person = create(context, "P");
            String previous = person.get("assignments").get(0).get("assignmentNo").asText();
            String replacement = ASSIGN.replace("TECHNICIAN", "WAITER")
                    .replace("{\"expectedRevision\":0",
                            "{\"assignmentNo\":\"" + previous + "\",\"expectedRevision\":0");
            assign(context, person, 1, replacement, "REPLACE");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_personnel_store "
                    + "WHERE entity_no='E' AND status='INACTIVE'", Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_personnel_skill "
                    + "WHERE entity_no='E' AND status='REVOKED'", Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_resource WHERE entity_no='E'", Integer.class))
                    .isOne();
        });
    }
    @Test
    void suspensionDelegatesAccountDisableAndRetainsFullReasonInStrictAudit() {
        runner().withUserConfiguration(ActualStaff.class).run(context -> {
            var person = create(context, "P");
            String no = person.get("personnelNo").asText();
            call(context, "linkPersonnelAccount", "E", "S", "OWNER", no,
                    req("LinkPersonnelAccountReq", "{\"expectedRevision\":1,\"accountNo\":\"A\"}"), "LINK");
            String reason = "停".repeat(500);
            var result = tree(call(context, "changePersonnelStatus", "E", "S", "OWNER", no,
                    req("ChangePersonnelStatusReq", "{\"expectedRevision\":2,\"status\":\"SUSPENDED\",\"reason\":\""
                            + reason + "\"}"), "STOP"));
            assertThat(sql.queryForObject("SELECT status FROM mch_account WHERE mch_account_no='A'", String.class))
                    .isEqualTo("DISABLED");
            assertThat(result.get("accountDisabled").asBoolean()).isTrue();
            assertThat(sql.queryForObject("SELECT detail FROM mch_staff_log WHERE entity_no='E' "
                    + "AND action='PERSONNEL_STATUS_REASON'", String.class)).isEqualTo(reason);
        });
    }
    @Test
    void samePersonWorksInTwoStoresWithoutDuplicatingStaffIdentity() {
        runner().run(context -> {
            var person = create(context, "P");
            String branch = "{\"expectedRevision\":1,\"storeNo\":\"B\",\"validFrom\":\"2026-01-01T00:00:00Z\","
                    + "\"publicVisible\":false,\"sortOrder\":0,\"positions\":[\"WAITER\"],\"skills\":[]}";
            call(context, "assignPersonnel", "E", "B", "OWNER", person.get("personnelNo").asText(),
                    req("AssignPersonnelReq", branch), "BRANCH");
            var facts = context.getBean(PersonnelFactsPort.class).getBatch(new PersonnelFactsPort.Query(
                    "MAIN", "E", "B", Set.of(person.get("personnelNo").asText()),
                    Instant.parse("2026-10-22T00:00:00Z")));
            assertThat(facts.getFirst().positionCodes()).containsExactly("WAITER");
            assertThat(facts.getFirst().qualifications()).isEmpty();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_resource WHERE entity_no='E'", Integer.class))
                    .isOne();
        });
    }
    @Test
    void publicationFailureRollsBackPersonEmploymentResourceAndIdempotency() {
        runner().withPropertyValues("shop.personnel.publication.archive-only=false").run(context -> {
            assertThatThrownBy(() -> create(context, "FAIL")).isInstanceOf(IllegalStateException.class);
            for (String table : List.of("mch_personnel", "mch_personnel_store", "mch_personnel_position",
                    "mch_personnel_skill", "mch_resource", "mch_staff_log")) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE entity_no='E'", Integer.class))
                        .isZero();
            }
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_idempotent WHERE idem_key='FAIL'", Integer.class))
                    .isZero();
        });
    }
    @Test
    void committedCapacityCounterBlocksEvenWithoutUnitRows() {
        runner().run(context -> {
            var person = create(context, "P");
            sql.update("INSERT INTO mch_resource_bucket(bucket_no,entity_no,resource_no,start_at,end_at,capacity,"
                    + "held_qty,created_at,updated_at) VALUES('BUCKET','E',?,'2026-10-22 00:00:00',"
                    + "'2026-10-22 00:05:00',1,1,NOW(),NOW())", person.get("resourceNo").asText());
            assertThatThrownBy(() -> call(context, "changePersonnelStatus", "E", "S", "OWNER",
                    person.get("personnelNo").asText(), req("ChangePersonnelStatusReq",
                            "{\"expectedRevision\":1,\"status\":\"LEFT\",\"reason\":\"离职\"}"), "LEFT"))
                    .isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT status FROM mch_personnel WHERE entity_no='E'", String.class))
                    .isEqualTo("ACTIVE");
            sql.update("UPDATE mch_resource_bucket SET held_qty=0 WHERE entity_no='E'");
            sql.update("INSERT INTO mch_resource_command(command_no,entity_no,owner_no,store_no,action,"
                    + "request_key_hash,payload_hash,frozen_payload,expected_owner_version,source_revision,status,"
                    + "initiated_by,created_at,updated_at) VALUES('PENDING','E','UNRESOLVED','B','RESERVE',?,?,'{}',"
                    + "0,1,'ACCEPTED','OWNER',NOW(),NOW())", "a".repeat(64), "b".repeat(64));
            assertThatThrownBy(() -> call(context, "changePersonnelStatus", "E", "S", "OWNER",
                    person.get("personnelNo").asText(), req("ChangePersonnelStatusReq",
                            "{\"expectedRevision\":1,\"status\":\"LEFT\",\"reason\":\"离职\"}"), "PENDING-LEFT"))
                    .isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT version FROM mch_personnel WHERE entity_no='E'", Long.class))
                    .isEqualTo(1);
        });
    }
    @Test
    void repeatedReplacementDoesNotTurnHistoricalQualificationsIntoDirectoryFailure() {
        runner().run(context -> {
            var refs = IntStream.range(0, 100).mapToObj(i -> "SKU" + i).sorted().toList();
            var facts = new LinkedHashMap<String, GoodsQueryPort.SkuSnapshot>();
            for (String ref : refs) {
                facts.put(ref, new GoodsQueryPort.SkuSnapshot(ref, "G", "E", "service", null, null, "SERVICE", null,
                        9900, 0, true, List.of("SERVICE"), null, null, "NORMAL", null, null, "S"));
            }
            doReturn(facts).when(context.getBean(GoodsQueryPort.class)).snapshot(refs, Map.of("E", "S"));
            String definition = ASSIGN.replace("[{\"goodsNo\":\"G\",\"skuNo\":\"SKU\"}]",
                    json.writeValueAsString(refs.stream().map(ref -> Map.of("goodsNo", "G", "skuNo", ref)).toList()));
            var person = tree(call(context, "createPersonnel", "E", "S", "OWNER", req("CreatePersonnelReq",
                    "{\"displayName\":\"技师\",\"assignment\":" + definition + "}"), "BIG"));
            String previous = person.get("assignments").get(0).get("assignmentNo").asText();
            for (int i = 1; i <= 2; i++) {
                String replacement = definition.replace("{\"expectedRevision\":0",
                        "{\"assignmentNo\":\"" + previous + "\",\"expectedRevision\":0");
                var result = tree(assign(context, person, i, replacement, "REPLACE-" + i));
                previous = result.get("assignmentNo").asText();
            }
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_personnel_skill WHERE entity_no='E'",
                    Integer.class)).isEqualTo(300);
            var page = tree(assertDoesNotThrow(() -> call(context, "listPersonnel", "E", "S", "OWNER",
                    req("PersonnelQuery", "{}"))));
            assertThat(page.get("records").get(0).get("qualifications").size()).isEqualTo(100);
            String closed = definition.replace("\"validTo\":null", "\"validTo\":\"2027-01-01T00:00:00Z\"")
                    .replace("{\"expectedRevision\":0",
                            "{\"assignmentNo\":\"" + previous + "\",\"expectedRevision\":0");
            assign(context, person, 3, closed, "CLOSE");
            String future = definition.replace("2026-01-01", "2027-01-01")
                    .replace("\"validTo\":null", "\"validTo\":\"2028-01-01T00:00:00Z\"");
            assign(context, person, 4, future, "NEXT");
            String excessive = future.replace("2028-01-01", "2029-01-01").replace("2027-01-01", "2028-01-01");
            assertThatThrownBy(() -> assign(context, person, 5, excessive, "TOO-MANY"))
                    .isInstanceOf(BizException.class);
            var bounded = tree(call(context, "listPersonnel", "E", "S", "OWNER", req("PersonnelQuery", "{}")));
            assertThat(bounded.get("records").get(0).get("qualifications").size()).isEqualTo(200);
        });
    }
    @Test
    void realAccountDisableCannotBeLostToConcurrentMemberRename() {
        runner().withUserConfiguration(ActualStaff.class).run(context -> {
            var person = create(context, "P");
            String no = person.get("personnelNo").asText();
            call(context, "linkPersonnelAccount", "E", "S", "OWNER", no,
                    req("LinkPersonnelAccountReq", "{\"expectedRevision\":1,\"accountNo\":\"A\"}"), "LINK");
            var staff = context.getBean(MerchantStaffService.class);
            var race = new AccountRace(staff);
            context.getBean(SqlSessionFactory.class).getConfiguration().addInterceptor(race);
            var tokens = context.getBean(MemoryTokenStore.class);
            String token = tokens.issue(TokenStore.SessionData.of(LoginUser.merchant("A", "staff")));
            try {
                call(context, "changePersonnelStatus", "E", "S", "OWNER", no, req("ChangePersonnelStatusReq",
                        "{\"expectedRevision\":2,\"status\":\"SUSPENDED\",\"reason\":\"停用\"}"), "STOP");
            } finally {
                if (race.rename != null) { race.rename.get(10, TimeUnit.SECONDS); }
            }
            assertThat(race.started.get()).isTrue();
            assertThat(sql.queryForObject("SELECT status FROM mch_account WHERE mch_account_no='A'", String.class))
                    .isEqualTo("DISABLED");
            assertThat(tokens.get(token)).isEmpty();
        });
    }
    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static class AccountRace implements Interceptor {
        private final MerchantStaffService staff;
        final AtomicBoolean started = new AtomicBoolean();
        CompletableFuture<Void> rename;
        AccountRace(MerchantStaffService staff) { this.staff = staff; }
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            if (!((MappedStatement) invocation.getArgs()[0]).getId().endsWith("MchAccountMapper.updateById")) {
                return invocation.proceed();
            }
            Object parameter = invocation.getArgs()[1];
            Object entity = parameter instanceof Map<?, ?> map && map.containsKey("et") ? map.get("et") : parameter;
            if (entity instanceof MchAccount account && "DISABLED".equals(account.getStatus())
                    && started.compareAndSet(false, true)) {
                rename = CompletableFuture.runAsync(() -> staff.renameSelf("A", "renamed"));
                try { rename.get(3, TimeUnit.SECONDS); }
                catch (TimeoutException locked) { /* Disable owns the account root; rename must wait for commit. */ }
            }
            return invocation.proceed();
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class ActualStaff {
        @Bean
        MemoryTokenStore tokens() { return new MemoryTokenStore(Duration.ofMinutes(5)); }
        @Bean
        @Primary
        MerchantStaffService actualStaff(MerchantMappers.MchAccountMapper accounts,
                MerchantMappers.MchStoreMapper stores, MerchantMappers.MchStoreRoleMapper roles,
                MerchantMappers.MchStaffLogMapper logs, MerchantMappers.MchRoleMapper definitions,
                MemoryTokenStore tokens) {
            return new MerchantStaffServiceImpl(accounts, TokenStores.single(tokens), stores, roles, logs,
                    definitions, new StaffAuditLogger(logs), tokens, mock(OtpStore.class));
        }
    }
    @Configuration(proxyBeanMethods = false)
    @MapperScan("ai.neargo.shop.merchant.mapper")
    static class Mappers {
        @Bean
        IdempotencyService idempotency(SqlSessionFactory factory, ObjectMapper json) {
            if (!factory.getConfiguration().hasMapper(SysIdempotentMapper.class)) {
                factory.getConfiguration().addMapper(SysIdempotentMapper.class);
            }
            return new IdempotencyService(new SqlSessionTemplate(factory).getMapper(SysIdempotentMapper.class), json);
        }
        @Bean
        GoodsQueryPort goods() {
            var port = mock(GoodsQueryPort.class);
            var fact = new GoodsQueryPort.SkuSnapshot("SKU", "G", "E", "service", null, null, "SERVICE", null,
                    9900, 0, true, List.of("SERVICE"), null, null, "NORMAL", null, null, "S");
            doReturn(Map.of("SKU", fact)).when(port).snapshot(List.of("SKU"), Map.of("E", "S"));
            return port;
        }
    }
}
