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
import javax.sql.DataSource;

class CommerceStoreCreationFlowTest {
    @Test
    void nullCategoryReturnsBadRequestForLegacyAndTypedCommands() {
        assertAll(
                Arrays.asList(null, "RETAIL").stream()
                        .<org.junit.jupiter.api.function.Executable>map(
                                type -> () -> {
                                    var error = assertThrows(
                                            BizException.class,
                                            () -> new StoreAdminService.CreateCommand(
                                                    "branch", "address", type,
                                                    type == null ? null : "branch-op",
                                                    Arrays.asList("CAT210", null)));
                                    assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
                                }));
    }

    @Test
    void categoryCommandKeepsAnImmutableOrderedSnapshotAndOptionalListSemantics() {
        var categories = new ArrayList<>(List.of("CAT220", "CAT210"));
        var command = new StoreAdminService.CreateCommand(
                "branch", "address", "RETAIL", "branch-op", categories);
        categories.clear();
        assertEquals(List.of("CAT220", "CAT210"), command.categoryNos());
        assertThrows(UnsupportedOperationException.class,
                () -> command.categoryNos().add("CAT230"));
        assertNull(new StoreAdminService.CreateCommand(
                "branch", "address", null, null, null).categoryNos());
        assertEquals(List.of(), new StoreAdminService.CreateCommand(
                "branch", "address", null, null, List.of()).categoryNos());
    }

    @Test
    void creationCarriesAnExplicitTypeAndRetainsTheLegacyConstructor() {
        assertNull(new QuickStartCommand("user", "store", null).businessTypeCode());
        assertEquals(
                "RETAIL",
                new QuickStartCommand("user", "store", null, "RETAIL").businessTypeCode());
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}

    @Test
    @EnabledIfEnvironmentVariable(
            named = "COMMERCE_MYSQL_URL",
            matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
    void nativeQuickStartAndOperatorApprovalCommitHostAndCommerceTogether() throws Exception {
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
            context.registerBean("dataSource", DataSource.class, () -> ds);
            var jdbc = new JdbcTemplate(ds);
            var schema =
                    new ClassPathResource("schema-test.sql")
                            .getContentAsString(StandardCharsets.UTF_8);
            for (String table :
                    List.of(
                            "usr_account", "sys_ops_staff", "sys_store_industry", "mch_entity_apply", "sys_audit_log",
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
            jdbc.execute("ALTER TABLE mch_entity_apply DROP store_profile,DROP store_no");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V662__operator_initial_store_profile.sql")).execute(ds);
            jdbc.execute(
                    "ALTER TABLE mch_store DROP business_type_code,DROP commerce_model,DROP"
                            + " business_type_changed_at");
            new ResourceDatabasePopulator(
                            new ClassPathResource("db/migration/V623__store_commerce_model.sql"))
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
            var plugin=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
            plugin.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor());
            factory.setPlugins(plugin);
            factory.setGlobalConfig(
                    new GlobalConfig()
                            .setMetaObjectHandler(new AuditMetaObjectHandler())
                            .setDbConfig(new GlobalConfig.DbConfig()));
            var sessions = new SqlSessionTemplate(factory.getObject());
            for (var mapper :
                    List.of(
                            MchEntityMapper.class, SysStoreIndustryMapper.class,
                            ai.neargo.shop.platform.mapper.PlatformMappers.MerchantApplyMapper.class,
                            ai.neargo.shop.platform.mapper.PlatformMappers.AuditLogMapper.class,
                            MchStoreMapper.class,
                            MchAccountMapper.class,
                            EntityPlanMapper.class,
                            PlanDefMapper.class,
                            MchPaymentMapper.class,
                            ServiceAreaMapper.class,
                            MchStoreRoleMapper.class)) config.addMapper(mapper);
            var operatorPermissions=org.mockito.Mockito.mock(ai.neargo.shop.auth.PermChecker.class);
            org.mockito.Mockito.when(operatorPermissions.can(org.mockito.ArgumentMatchers.anyString())).thenAnswer(v->
                    ai.neargo.common.security.rbac.Permissions.matches(ai.neargo.shop.auth.SecurityUtils.requireUser().grantedPermissions(),v.getArgument(0)));
            var authority = new HostStoreCreationAuthority(ds, Map.of("CN", "CNY", "TW", "TWD"),operatorPermissions);
            var facade =
                    new CommerceStoreCreationConfiguration()
                            .storeProvisioningFacade(
                                    ds,
                                    new JdbcCommerceUnitOfWork(ds, txManager),
                                    new JdbcCommerceEventPersistence(ds),
                                    authority);
            var creation =
                    new MerchantStoreCreation(true, Optional.of(authority), Optional.of(facade));
            var master=org.mockito.Mockito.mock(ai.neargo.shop.spi.platform.MasterDataPort.class);
            org.mockito.Mockito.when(master.canonicalSubject(org.mockito.ArgumentMatchers.anyString())).thenAnswer(v->v.getArgument(0));
            org.mockito.Mockito.when(master.settleAccountType(org.mockito.ArgumentMatchers.anyString())).thenReturn("CORPORATE");
            var marketPolicy=org.mockito.Mockito.mock(ai.neargo.shop.platform.MarketPhonePolicyService.class);
            org.mockito.Mockito.when(marketPolicy.policy("TW")).thenReturn(new ai.neargo.shop.platform.MarketPhonePolicyService.Policy("TW","+886",true,false,false,true));
            org.mockito.Mockito.when(marketPolicy.policy("CN")).thenReturn(new ai.neargo.shop.platform.MarketPhonePolicyService.Policy("CN","+86",false,true,true,true));
            context.registerBean(ai.neargo.shop.platform.MarketPhonePolicyService.class,()->marketPolicy);
            context.registerBean(SysStoreIndustryMapper.class,()->sessions.getMapper(SysStoreIndustryMapper.class));
            context.registerBean(ai.neargo.shop.spi.user.StoreOwnershipWritePort.class,()->org.mockito.Mockito.mock(ai.neargo.shop.spi.user.StoreOwnershipWritePort.class));
            context.register(Transactions.class);
            context.registerBean(DataSourceTransactionManager.class, () -> txManager);
            context.registerBean(
                    MerchantPortImpl.class,
                    () ->
                            new MerchantPortImpl(
                                    creation,
                                    sessions.getMapper(MchEntityMapper.class),
                                    null,
                                    sessions.getMapper(MchPaymentMapper.class),
                                    org.mockito.Mockito.mock(MerchantStoreService.class),
                                    null,
                                    master,
                                    sessions.getMapper(MchAccountMapper.class),
                                    sessions.getMapper(MchStoreMapper.class),
                                    null,
                                    new tools.jackson.databind.ObjectMapper(),
                                    null,
                                    sessions.getMapper(ServiceAreaMapper.class),
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
                                    null,
                                    (ai.neargo.shop.merchant.service.PayMerchantMirror) entityNo -> {},
                                    new org.springframework.beans.factory.support.StaticListableBeanFactory()
                                            .getBeanProvider(ai.neargo.shop.spi.pay.PayMerchantReadSwitch.class),
                                    new org.springframework.beans.factory.support.StaticListableBeanFactory()
                                            .getBeanProvider(ai.neargo.shop.spi.pay.PayMerchantQueryPort.class)));
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
                                    sessions.getMapper(ServiceAreaMapper.class),
                                    entityNo -> {}));
            context.registerBean(ai.neargo.shop.platform.impl.OpsServiceImpl.class,()->{
                try {
                    var ctor=ai.neargo.shop.platform.impl.OpsServiceImpl.class.getConstructors()[0];
                    var values=new Object[ctor.getParameterCount()];var types=ctor.getParameterTypes();
                    for(int k=0;k<values.length;k++) {
                        var type=types[k];
                        if(type==MerchantAdminPort.class) values[k]=context.getBean(MerchantAdminPort.class);
                        else if(type==ai.neargo.shop.platform.mapper.PlatformMappers.MerchantApplyMapper.class) values[k]=sessions.getMapper(ai.neargo.shop.platform.mapper.PlatformMappers.MerchantApplyMapper.class);
                        else if(type==ai.neargo.shop.platform.mapper.PlatformMappers.AuditLogMapper.class) values[k]=sessions.getMapper(ai.neargo.shop.platform.mapper.PlatformMappers.AuditLogMapper.class);
                        else if(type==tools.jackson.databind.ObjectMapper.class) values[k]=new tools.jackson.databind.ObjectMapper();
                        else if(type==String.class) values[k]="response";
                        else if(type==ai.neargo.shop.platform.MasterDataService.class) {
                            // 主体门禁对查不到的主体 fail-closed：替身须认得本测试提交的 NATURAL_PERSON
                            var subjects=org.mockito.Mockito.mock(ai.neargo.shop.platform.MasterDataService.class);
                            org.mockito.Mockito.when(subjects.canonicalSubject("NATURAL_PERSON")).thenReturn("NATURAL_PERSON");
                            org.mockito.Mockito.when(subjects.enabledSubjects()).thenReturn(List.of("NATURAL_PERSON"));
                            values[k]=subjects;
                        }
                        else values[k]=org.mockito.Mockito.mock(type);
                    }
                    return (ai.neargo.shop.platform.impl.OpsServiceImpl)ctor.newInstance(values);
                } catch(Exception e) { throw new AssertionError(e); }
            });
            context.refresh();
            plans.setOwnership(context.getBean(ai.neargo.shop.spi.user.StoreOwnershipWritePort.class));
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
            var ops=context.getBean(ai.neargo.shop.platform.OpsService.class);
            jdbc.update("INSERT INTO sys_ops_staff(staff_no,username,password,status,created_at,updated_at) VALUES ('maker','maker','TEST-ONLY','ACTIVE',NOW(),NOW()),('reviewer','reviewer','TEST-ONLY','ACTIVE',NOW(),NOW())");
            jdbc.update("INSERT INTO usr_account(user_no,created_at,updated_at) VALUES ('operator-owner',NOW(),NOW()),('rollback-owner',NOW(),NOW())");
            jdbc.update("INSERT INTO sys_store_industry(industry_code,sub_industry_code,business_type_code,name_zh,name_tw,enabled,created_at,updated_at) VALUES ('RETAIL_TEST','','RETAIL','Retail','Retail',1,NOW(),NOW()),('RETAIL_TEST','SHOP','RETAIL','Shop','Shop',1,NOW(),NOW())");
            var draft=new MerchantAdminPort.InitialStore("Taiwan operator store","Taipei test-only address","RETAIL_TEST","SHOP","RETAIL");
            var operatorCommand=new ai.neargo.shop.platform.OpsService.SubmitApplyCommand("operator-owner","Test entity","NATURAL_PERSON", "TEST", "09 raw / ext",null,null,null,"PLATFORM",List.of(),List.of(),false,null,null,List.of(),"TW",draft);
            setOperator("maker",ai.neargo.shop.auth.Perms.MERCHANT_APPLY_ONBEHALF,ai.neargo.shop.auth.Perms.MERCHANT_APPLY_AUDIT);
            jdbc.update("INSERT INTO sys_store_industry(industry_code,sub_industry_code,business_type_code,name_zh,name_tw,enabled,created_at,updated_at) VALUES ('RETAIL_TEST','NOT_PUBLISHED','FOOD','Food','Food',1,NOW(),NOW()),('DISABLED','','RETAIL','Disabled','Disabled',0,NOW(),NOW()),('DISABLED','SHOP','RETAIL','Disabled child','Disabled child',1,NOW(),NOW()),('EMPTY','','FOOD','Empty','Empty',1,NOW(),NOW()),('EMPTY','CHILD','FOOD','Unpublished','Unpublished',1,NOW(),NOW())");
            var setup=new OpsStoreSetupQuery(ds,new JdbcCommerceUnitOfWork(ds,txManager),context.getBean(ai.neargo.shop.spi.user.MerchantQueryPort.class));
            var choices=tx.execute(status->setup.choices(authority.lockCreator("operator-owner")));
            assertEquals(2,choices.size());assertTrue(choices.stream().allMatch(r->r.industryCode().equals("RETAIL_TEST")));
            assertTrue(choices.stream().anyMatch(r->"SHOP".equals(r.subIndustryCode())));
            String application=ops.createApplyOnBehalf(operatorCommand,"maker");
            assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM mch_entity_apply WHERE apply_no=?",String.class,application));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mch_entity WHERE owner_user_no='operator-owner'",Integer.class));
            // Current onboarding policy allows the submitter to audit with permission;
            // self-audit coverage lives in OnBehalfMerchantFlowTest, not the old four-eyes assertion.
            setOperator("reviewer");
            assertEquals(ErrorCode.FORBIDDEN,assertThrows(BizException.class,()->ops.auditApply(application,true,null,"PLATFORM",List.of(),List.of())).errorCode());
            setOperator("reviewer",ai.neargo.shop.auth.Perms.MERCHANT_APPLY_AUDIT);
            jdbc.update("UPDATE sys_store_industry SET enabled=0 WHERE sub_industry_code='SHOP'");
            assertEquals(ErrorCode.BAD_REQUEST,assertThrows(BizException.class,()->ops.auditApply(application,true,null,"PLATFORM",List.of(),List.of())).errorCode());
            assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM mch_entity_apply WHERE apply_no=?",String.class,application));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mch_entity WHERE owner_user_no='operator-owner'",Integer.class));
            jdbc.update("UPDATE sys_store_industry SET enabled=1 WHERE sub_industry_code='SHOP'");
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                java.util.concurrent.Callable<ai.neargo.shop.platform.dto.OpsVOs.MerchantApplyVO> approve=()->{
                    setOperator("reviewer","merchant:apply:*");
                    try { return ops.auditApply(application,true,null,"PLATFORM",List.of(),List.of()); }
                    finally { SecurityContextHolder.clearContext(); }
                };
                var one=pool.submit(approve);var two=pool.submit(approve);
                var receipt=one.get(15,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(receipt.storeNo(),two.get(15,java.util.concurrent.TimeUnit.SECONDS).storeNo());
                assertNotNull(receipt.storeNo());assertEquals("APPROVED",receipt.status());assertEquals("operator-owner",receipt.ownerUserNo());assertEquals("TW",receipt.marketCode());
                assertEquals(draft,receipt.storeProfile());assertEquals(0,receipt.agreedAt());
                assertEquals("STORE_V1",jdbc.queryForObject("SELECT commerce_model FROM mch_store WHERE store_no=?",String.class,receipt.storeNo()));
                assertEquals("Taiwan operator store",jdbc.queryForObject("SELECT name FROM mch_store WHERE store_no=?",String.class,receipt.storeNo()));
                assertEquals("Taipei test-only address",jdbc.queryForObject("SELECT address FROM mch_store WHERE store_no=?",String.class,receipt.storeNo()));
                assertEquals("RETAIL_TEST",jdbc.queryForObject("SELECT industry_code FROM mch_store WHERE store_no=?",String.class,receipt.storeNo()));
                assertEquals("SHOP",jdbc.queryForObject("SELECT sub_industry_code FROM mch_store WHERE store_no=?",String.class,receipt.storeNo()));
                assertEquals("operator-owner",jdbc.queryForObject("SELECT owner_user_no FROM mch_entity WHERE entity_no=?",String.class,receipt.merchantNo()));
                assertEquals("TW",jdbc.queryForObject("SELECT market FROM mch_entity WHERE entity_no=?",String.class,receipt.merchantNo()));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_period WHERE store_no=? AND source_type='FREE' AND currency='TWD'",Integer.class,receipt.storeNo()));
                assertEquals("reviewer",jdbc.queryForObject("SELECT created_by FROM mch_subscription_period WHERE store_no=?",String.class,receipt.storeNo()));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mch_account WHERE entity_no=? AND user_no='operator-owner' AND is_owner=1",Integer.class,receipt.merchantNo()));
                setOperator("reviewer",ai.neargo.shop.auth.Perms.MERCHANT_APPLY_AUDIT);
                assertEquals(receipt.storeNo(),ops.auditApply(application,true,null,"PLATFORM",List.of(),List.of()).storeNo());
                assertEquals(ErrorCode.CONFLICT,assertThrows(BizException.class,()->ops.auditApply(application,true,null,"CITY",List.of(),List.of())).errorCode());
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mch_store WHERE entity_no=?",Integer.class,receipt.merchantNo()));
                var otherDraft=new MerchantAdminPort.InitialStore("Store","Address","RETAIL_TEST","SHOP","FOOD");
                assertEquals(ErrorCode.BAD_REQUEST,assertThrows(BizException.class,()->port.validateInitialStore(otherDraft)).errorCode());
                assertEquals(ErrorCode.FORBIDDEN,assertThrows(BizException.class,()->tx.executeWithoutResult(status->{
                    var actor=authority.lockCreator("operator-owner");
                    assertEquals(SubjectKind.OPERATOR,actor.subjectKind());
                    facade.initializeFree(actor,new InitializeStoreCommand(new StoreRef("MAIN",receipt.storeNo()),"RETAIL",receipt.storeNo()));
                })).errorCode());
            }
            setOperator("maker",ai.neargo.shop.auth.Perms.MERCHANT_APPLY_ONBEHALF);
            var rollbackCommand=new ai.neargo.shop.platform.OpsService.SubmitApplyCommand("rollback-owner","Rollback entity","NATURAL_PERSON",null,null,null,null,null,"PLATFORM",List.of(),List.of(),false,null,null,List.of(),"TW",draft);
            String rollbackApplication=ops.createApplyOnBehalf(rollbackCommand,"maker");
            setOperator("reviewer",ai.neargo.shop.auth.Perms.MERCHANT_APPLY_AUDIT);
            String priorAggregates=jdbc.queryForList("SELECT DISTINCT aggregate_no FROM commerce_outbox",String.class).stream()
                    .map(v->"'"+v.replace("'","''")+"'").collect(java.util.stream.Collectors.joining(","));
            int beforeRollbackStores=jdbc.queryForObject("SELECT COUNT(*) FROM mch_store",Integer.class);
            int beforeRollbackPeriods=jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_period",Integer.class);
            jdbc.execute("ALTER TABLE commerce_outbox ADD CONSTRAINT reject_ops_event CHECK(event_type <> 'ENTITLEMENT_CHANGED' OR aggregate_no IN ("+priorAggregates+"))");
            assertThrows(org.springframework.dao.DataAccessException.class,()->ops.auditApply(rollbackApplication,true,null,"PLATFORM",List.of(),List.of()));
            assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM mch_entity_apply WHERE apply_no=?",String.class,rollbackApplication));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mch_entity WHERE owner_user_no='rollback-owner'",Integer.class));
            assertEquals(beforeRollbackStores,jdbc.queryForObject("SELECT COUNT(*) FROM mch_store",Integer.class));
            assertEquals(beforeRollbackPeriods,jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_period",Integer.class));
            jdbc.execute("ALTER TABLE commerce_outbox DROP CHECK reject_ops_event");
            // Operator on-behalf creates an independent entity; the old LEGACY store remains untouched.
            var previousName=jdbc.queryForObject("SELECT name FROM mch_store WHERE store_no=?",String.class,store.storeNo());
            var previousPeriods=jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_period WHERE store_no=?",Integer.class,store.storeNo());
            setOperator("maker",ai.neargo.shop.auth.Perms.MERCHANT_APPLY_ONBEHALF);
            var legacyCommand=new ai.neargo.shop.platform.OpsService.SubmitApplyCommand("owner","Legacy certification","NATURAL_PERSON",null,null,null,null,null,"PLATFORM",List.of(),List.of(),false,null,null,List.of(),"CN",draft);
            String legacyApplication=ops.createApplyOnBehalf(legacyCommand,"maker");
            setOperator("reviewer","merchant:*");
            var certified=ops.auditApply(legacyApplication,true,null,"PLATFORM",List.of(),List.of());
            assertNotEquals(store.storeNo(),certified.storeNo());
            assertEquals("STORE_V1",jdbc.queryForObject("SELECT commerce_model FROM mch_store WHERE store_no=?",String.class,certified.storeNo()));
            assertEquals("LEGACY",jdbc.queryForObject("SELECT commerce_model FROM mch_store WHERE store_no=?",String.class,store.storeNo()));
            assertEquals(previousName,jdbc.queryForObject("SELECT name FROM mch_store WHERE store_no=?",String.class,store.storeNo()));
            assertEquals(previousPeriods,jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_period WHERE store_no=?",Integer.class,store.storeNo()));


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

    private static void setOperator(String no,String...perms) {
        setLogin(LoginUser.operator(no,no,List.of(),List.of(perms)));
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

    static void seedPublishedCatalog(JdbcTemplate jdbc) {
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
