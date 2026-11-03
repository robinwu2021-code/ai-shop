package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.subscription.*;
import ai.neargo.commerce.persistence.*;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.mapper.MerchantMappers.*;
import ai.neargo.shop.merchant.port.MerchantPortImpl;
import ai.neargo.shop.merchant.service.*;
import ai.neargo.shop.merchant.service.impl.MerchantPlanServiceImpl;
import ai.neargo.shop.merchant.service.impl.StoreAdminServiceImpl;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import ai.neargo.shop.spi.user.MerchantAdminPort.QuickStartCommand;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

class CommerceStoreCreationFlowTest {
    @Test
    void creationCarriesAnExplicitTypeAndRetainsTheLegacyConstructor() {
        assertNull(new QuickStartCommand("user", "store", null).businessTypeCode());
        assertEquals(
                "RETAIL",
                new QuickStartCommand("user", "store", null, "RETAIL").businessTypeCode());
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {}

    @Test
    @EnabledIfEnvironmentVariable(
            named = "COMMERCE_MYSQL_URL",
            matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
    void nativeQuickStartCommitsHostAndCommerceTogether() throws Exception {
        String url = System.getenv("COMMERCE_MYSQL_URL"),
                user = System.getenv("COMMERCE_MYSQL_USER"),
                password = System.getenv("COMMERCE_MYSQL_PASSWORD");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.*"));
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        var name = "commerce_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + name);
        try (var context = new AnnotationConfigApplicationContext()) {
            var ds =
                    new DriverManagerDataSource(
                            url.replace("/?", "/" + name + "?"), user, password);
            var jdbc = new JdbcTemplate(ds);
            var schema =
                    new ClassPathResource("schema-test.sql")
                            .getContentAsString(StandardCharsets.UTF_8);
            for (String table :
                    List.of(
                            "usr_account",
                            "mch_entity",
                            "mch_account",
                            "mch_store",
                            "mch_entity_plan",
                            "sys_merchant_plan_def",
                            "mch_service_area",
                            "mch_payment_merchant",
                            "mch_store_role")) {
                var matcher =
                        Pattern.compile(
                                        "(?s)CREATE TABLE IF NOT EXISTS "
                                                + table
                                                + "\\s*\\(.*?\n\\);")
                                .matcher(schema);
                assertTrue(matcher.find(), table);
                jdbc.execute(matcher.group());
            }
            jdbc.execute(
                    "ALTER TABLE mch_store DROP business_type_code,DROP commerce_model,DROP"
                            + " business_type_changed_at");
            new ResourceDatabasePopulator(
                            new ClassPathResource("db/migration/V554__store_commerce_model.sql"))
                    .execute(ds);
            new CommerceMigrationRunner(ds).migrate();
            seedPublishedCatalog(jdbc);
            jdbc.update(
                    "INSERT INTO usr_account(user_no,created_at,updated_at) VALUES"
                            + " ('owner',NOW(),NOW()),('other',NOW(),NOW())");
            var txManager = new DataSourceTransactionManager(ds);
            var tx = new TransactionTemplate(txManager);
            var config = new MybatisConfiguration();
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setConfiguration(config);
            factory.setDataSource(ds);
            factory.setGlobalConfig(
                    new GlobalConfig()
                            .setMetaObjectHandler(new AuditMetaObjectHandler())
                            .setDbConfig(new GlobalConfig.DbConfig()));
            var sessions = new SqlSessionTemplate(factory.getObject());
            for (var mapper :
                    List.of(
                            MchEntityMapper.class,
                            MchStoreMapper.class,
                            MchAccountMapper.class,
                            EntityPlanMapper.class,
                            PlanDefMapper.class,
                            MchPaymentMapper.class,
                            ServiceAreaMapper.class,
                            MchStoreRoleMapper.class)) config.addMapper(mapper);
            var authority = new HostStoreCreationAuthority(ds, Map.of("CN", "CNY"));
            var facade =
                    new CommerceStoreCreationConfiguration()
                            .storeProvisioningFacade(
                                    ds,
                                    new JdbcCommerceUnitOfWork(ds, txManager),
                                    new JdbcCommerceEventPersistence(ds),
                                    authority);
            var creation =
                    new MerchantStoreCreation(true, Optional.of(authority), Optional.of(facade));
            context.register(Transactions.class);
            context.registerBean(DataSourceTransactionManager.class, () -> txManager);
            context.registerBean(
                    MerchantPortImpl.class,
                    () ->
                            new MerchantPortImpl(
                                    creation,
                                    sessions.getMapper(MchEntityMapper.class),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    sessions.getMapper(MchAccountMapper.class),
                                    sessions.getMapper(MchStoreMapper.class),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    sessions.getMapper(EntityPlanMapper.class),
                                    sessions.getMapper(PlanDefMapper.class),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null));
            var plans =
                    new MerchantPlanServiceImpl(
                            sessions.getMapper(EntityPlanMapper.class),
                            sessions.getMapper(PlanDefMapper.class),
                            sessions.getMapper(MchEntityMapper.class),
                            sessions.getMapper(MchStoreMapper.class),
                            sessions.getMapper(MchAccountMapper.class));
            context.registerBean(
                    StoreAdminServiceImpl.class,
                    () ->
                            new StoreAdminServiceImpl(
                                    creation,
                                    org.mockito.Mockito.mock(StoreCategoryService.class),
                                    sessions.getMapper(MchStoreMapper.class),
                                    sessions.getMapper(MchStoreRoleMapper.class),
                                    sessions.getMapper(MchPaymentMapper.class),
                                    plans,
                                    sessions.getMapper(MchEntityMapper.class),
                                    org.mockito.Mockito.mock(StoreLinkService.class),
                                    sessions.getMapper(ServiceAreaMapper.class)));
            context.refresh();
            var port = context.getBean(MerchantAdminPort.class);
            login("owner");
            var cmd = new QuickStartCommand("owner", "Shop", null, "RETAIL");
            assertEquals(
                    ErrorCode.STORE_BUSINESS_TYPE_REQUIRED,
                    assertThrows(
                                    BizException.class,
                                    () ->
                                            port.quickStart(
                                                    new QuickStartCommand(
                                                            "owner", "Shop", null, "MISSING")))
                            .errorCode());
            assertCounts(jdbc, 0);
            // The last commerce write fails after store, owner, period, segments and schedule event
            // exist.
            jdbc.execute(
                    "ALTER TABLE commerce_outbox ADD CONSTRAINT reject_test_event CHECK (event_type"
                            + " <> 'ENTITLEMENT_CHANGED')");
            assertThrows(
                    org.springframework.dao.DataAccessException.class, () -> port.quickStart(cmd));
            assertCounts(jdbc, 0);
            jdbc.execute("ALTER TABLE commerce_outbox DROP CHECK reject_test_event");
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            tx.executeWithoutResult(
                                    status -> {
                                        port.quickStart(cmd);
                                        assertCounts(jdbc, 1);
                                        throw new IllegalStateException("outer rollback");
                                    }));
            assertCounts(jdbc, 0);
            // Begin a repeatable-read snapshot before a different connection commits this same
            // owner and type.
            var store =
                    tx.execute(
                            status -> {
                                assertCounts(jdbc, 0);
                                try (var pool =
                                        java.util.concurrent.Executors
                                                .newVirtualThreadPerTaskExecutor()) {
                                    var start = new java.util.concurrent.CountDownLatch(1);
                                    java.util.concurrent.Callable<
                                                    MerchantAdminPort.QuickStartResult>
                                            create =
                                                    () -> {
                                                        login("owner");
                                                        try {
                                                            start.await();
                                                            return port.quickStart(cmd);
                                                        } finally {
                                                            SecurityContextHolder.clearContext();
                                                        }
                                                    };
                                    var first = pool.submit(create);
                                    var second = pool.submit(create);
                                    start.countDown();
                                    var created =
                                            first.get(15, java.util.concurrent.TimeUnit.SECONDS);
                                    assertEquals(
                                            created,
                                            second.get(15, java.util.concurrent.TimeUnit.SECONDS));
                                    assertEquals(created, port.quickStart(cmd));
                                    assertThrows(
                                            BizException.class,
                                            () ->
                                                    port.quickStart(
                                                            new QuickStartCommand(
                                                                    "owner", "Shop", null,
                                                                    "FOOD")));
                                    // The intentionally rejected nested write marks this reader for
                                    // rollback.
                                    status.setRollbackOnly();
                                    return created;
                                } catch (Exception e) {
                                    throw new AssertionError(e);
                                }
                            });
            assertCounts(jdbc, 1);
            assertEquals(store, port.quickStart(cmd));
            assertCounts(jdbc, 1);
            assertEquals(
                    "PENDING_LICENSE",
                    jdbc.queryForObject("SELECT status FROM mch_entity", String.class));
            assertEquals(
                    "STORE_V1",
                    jdbc.queryForObject("SELECT commerce_model FROM mch_store", String.class));
            assertThrows(
                    BizException.class,
                    () -> port.quickStart(new QuickStartCommand("owner", "Shop", null, "FOOD")));
            login("other");
            assertThrows(BizException.class, () -> port.quickStart(cmd));
            var ref = new StoreRef("MAIN", store.storeNo());
            assertThrows(
                    BizException.class,
                    () ->
                            tx.execute(
                                    status ->
                                            facade.initializeFree(
                                                    authority.lockCreator("other"),
                                                    new InitializeStoreCommand(
                                                            ref, "RETAIL", store.storeNo()))));
            assertCounts(jdbc, 1);
            var branches = context.getBean(StoreAdminService.class);
            jdbc.update("update mch_entity_plan set store_quota_override=3");
            assertThrows(
                    BizException.class,
                    () ->
                            branches.create(
                                    store.entityNo(),
                                    new StoreAdminService.CreateCommand(
                                            "unauthorized", null, "RETAIL", "op", List.of())));
            assertCounts(jdbc, 1);
            login("owner");
            assertThrows(
                    BizException.class,
                    () -> branches.create(store.entityNo(), "missing type", null));
            jdbc.update(
                    "insert into"
                        + " mch_account(mch_account_no,entity_no,user_no,is_owner,status,created_at,updated_at)"
                        + " values ('employee',?,'other',0,'ACTIVE',now(),now())",
                    store.entityNo());
            login("other");
            assertThrows(
                    BizException.class,
                    () ->
                            branches.create(
                                    store.entityNo(),
                                    new StoreAdminService.CreateCommand(
                                            "staff", null, "RETAIL", "op", List.of())));
            var ownerAccount =
                    jdbc.queryForObject(
                            "select mch_account_no from mch_account where is_owner=1",
                            String.class);
            setLogin(LoginUser.merchant(ownerAccount, "Owner"));
            var branchCommand =
                    new StoreAdminService.CreateCommand(
                            "second", null, "RETAIL", "branch-1", List.of());
            jdbc.execute(
                    "ALTER TABLE commerce_outbox ADD CONSTRAINT reject_branch CHECK"
                            + " (aggregate_type<>'SUBSCRIPTION' OR aggregate_no='"
                            + store.storeNo()
                            + "')");
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () -> branches.create(store.entityNo(), branchCommand));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "select count(*) from commerce_event_inbox where"
                                    + " source_system='STORE_CREATION'",
                            Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from mch_store", Integer.class));
            jdbc.execute("ALTER TABLE commerce_outbox DROP CHECK reject_branch");
            var branch =
                    tx.execute(
                            status -> {
                                assertEquals(
                                        1,
                                        jdbc.queryForObject(
                                                "select count(*) from mch_store", Integer.class));
                                try (var pool =
                                        java.util.concurrent.Executors
                                                .newVirtualThreadPerTaskExecutor()) {
                                    var start = new java.util.concurrent.CountDownLatch(1);
                                    java.util.concurrent.Callable<String> request =
                                            () -> {
                                                setLogin(LoginUser.merchant(ownerAccount, "Owner"));
                                                try {
                                                    start.await();
                                                    return branches.create(
                                                                    store.entityNo(), branchCommand)
                                                            .storeNo();
                                                } finally {
                                                    SecurityContextHolder.clearContext();
                                                }
                                            };
                                    var first = pool.submit(request);
                                    var second = pool.submit(request);
                                    start.countDown();
                                    String original =
                                            first.get(15, java.util.concurrent.TimeUnit.SECONDS);
                                    assertEquals(
                                            original,
                                            second.get(15, java.util.concurrent.TimeUnit.SECONDS));
                                    var replay = branches.create(store.entityNo(), branchCommand);
                                    assertEquals(original, replay.storeNo());
                                    return replay;
                                } catch (Exception e) {
                                    throw new AssertionError(e);
                                }
                            });
            assertEquals(
                    ErrorCode.CONFLICT,
                    assertThrows(
                                    BizException.class,
                                    () ->
                                            branches.create(
                                                    store.entityNo(),
                                                    new StoreAdminService.CreateCommand(
                                                            "second",
                                                            null,
                                                            "RETAIL",
                                                            "branch-1",
                                                            List.of("different"))))
                            .errorCode());
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "select count(*) from commerce_event_inbox where"
                                    + " source_system='STORE_CREATION'",
                            Integer.class));
            assertNotEquals(store.storeNo(), branch.storeNo());
            assertEquals(
                    2,
                    jdbc.queryForObject(
                            "select count(*) from mch_subscription_period", Integer.class));
            assertEquals(
                    2,
                    jdbc.queryForObject(
                            "select count(*) from mch_store where commerce_model='STORE_V1'",
                            Integer.class));
            assertEquals(
                    ownerAccount,
                    jdbc.queryForObject(
                            "select created_by from mch_subscription_period where store_no=?",
                            String.class,
                            branch.storeNo()));
            login("owner");
            jdbc.update("UPDATE mch_store SET commerce_model='LEGACY',business_type_code=NULL");
            assertEquals(store, port.quickStart(new QuickStartCommand("owner", "old", null)));
            assertEquals(
                    ErrorCode.STORE_COMMERCE_MIGRATION_REQUIRED,
                    assertThrows(BizException.class, () -> port.quickStart(cmd)).errorCode());
            assertCurrentLicenseQuota(port, jdbc, tx);
        } finally {
            SecurityContextHolder.clearContext();
            admin.execute("DROP DATABASE " + name);
        }
    }

    private static void assertCurrentLicenseQuota(
            MerchantAdminPort port, JdbcTemplate jdbc, TransactionTemplate tx) {
        login("quota-owner");
        jdbc.update(
                "INSERT INTO usr_account(user_no,created_at,updated_at) VALUES"
                    + " ('quota-owner',NOW(),NOW())");
        for (int i = 1; i <= 4; i++) insertActiveEntity(jdbc, i);
        var independent = new TransactionTemplate(tx.getTransactionManager());
        independent.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var baseline =
                tx.execute(
                        status -> {
                            var count =
                                    "SELECT COUNT(*) FROM mch_account WHERE user_no='quota-owner'"
                                        + " AND is_owner=1";
                            assertEquals(4, jdbc.queryForObject(count, Integer.class));
                            var committed =
                                    independent.execute(
                                            inner -> {
                                                insertActiveEntity(jdbc, 5);
                                                assertEquals(
                                                        5,
                                                        jdbc.queryForObject(count, Integer.class));
                                                return creationCounts(jdbc);
                                            });
                            assertEquals(4, jdbc.queryForObject(count, Integer.class));
                            assertEquals(
                                    ErrorCode.ENTITY_QUOTA_EXCEEDED,
                                    assertThrows(
                                                    BizException.class,
                                                    () ->
                                                            port.quickStart(
                                                                    new QuickStartCommand(
                                                                            "quota-owner",
                                                                            "sixth",
                                                                            null,
                                                                            "RETAIL")))
                                            .errorCode());
                            status.setRollbackOnly();
                            return committed;
                        });
        assertEquals(baseline, creationCounts(jdbc));
    }

    private static void insertActiveEntity(JdbcTemplate jdbc, int i) {
        String no = "quota-entity-" + i;
        jdbc.update(
                "INSERT INTO mch_entity(entity_no,name,owner_user_no,status,created_at,updated_at)"
                    + " VALUES (?,?,'quota-owner','ACTIVE',NOW(),NOW())",
                no,
                no);
        jdbc.update(
                "INSERT INTO"
                    + " mch_account(mch_account_no,entity_no,user_no,is_owner,status,created_at,updated_at)"
                    + " VALUES (?,?,'quota-owner',1,'ACTIVE',NOW(),NOW())",
                "quota-account-" + i,
                no);
    }

    private static Map<String, Integer> creationCounts(JdbcTemplate jdbc) {
        var counts = new TreeMap<String, Integer>();
        for (var table :
                List.of(
                        "mch_entity",
                        "mch_account",
                        "mch_store",
                        "mch_entity_plan",
                        "mch_subscription_account",
                        "mch_subscription_period",
                        "mch_store_end_grant",
                        "mch_end_schedule_event",
                        "commerce_outbox",
                        "commerce_event_inbox"))
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        return counts;
    }

    private static void login(String no) {
        setLogin(LoginUser.merchantByUser(no, no));
    }

    private static void setLogin(LoginUser user) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
        auth.setDetails("test-session-" + user.userNo());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void assertCounts(JdbcTemplate jdbc, int expected) {
        creationCounts(jdbc).forEach((table, count) -> assertEquals(expected, count, table));
    }

    private static void seedPublishedCatalog(JdbcTemplate jdbc) {
        String audit = "tenant_no,created_at,updated_at,version,deleted,",
                values = "'MAIN',NOW(),NOW(),1,0,";
        jdbc.update(
                "INSERT INTO sys_business_type("
                        + audit
                        + "business_type_code,name,enabled,sort,plan_catalog_version) VALUES ("
                        + values
                        + "'RETAIL','Retail',1,1,1)");
        jdbc.update(
                "INSERT INTO sys_business_end("
                        + audit
                        + "end_code,entitlement_code,name,scope,allowed_business_types,release_status,sale_enabled,bootstrap_required,enabled)"
                        + " VALUES ("
                        + values
                        + "'desk','BASE','Desk','STORE','[\"RETAIL\"]','VERIFIED',0,1,1)");
        for (String tier : List.of("FREE", "PRO", "CHAIN")) {
            int rank = List.of("FREE", "PRO", "CHAIN").indexOf(tier);
            jdbc.update(
                    "INSERT INTO sys_industry_plan("
                            + audit
                            + "plan_no,business_type_code,tier_code,`rank`,current_version_no,sale_status,sort)"
                            + " VALUES ("
                            + values
                            + "?,'RETAIL',?,?,?,'ON_SALE',?)",
                    tier,
                    tier,
                    rank,
                    tier + "-v",
                    rank);
            jdbc.update(
                    "INSERT INTO sys_industry_plan_version("
                            + audit
                            + "plan_version_no,plan_no,revision,display_name,summary,status,policy_version,published_at)"
                            + " VALUES ("
                            + values
                            + "?,?,1,?,'summary','PUBLISHED','policy',123)",
                    tier + "-v",
                    tier,
                    tier);
            jdbc.update(
                    "INSERT INTO sys_plan_version_end("
                            + audit
                            + "plan_version_no,end_code,entitlement_code,sort) VALUES ("
                            + values
                            + "?,'desk','BASE',1)",
                    tier + "-v");
        }
    }
}
