package ai.neargo.shop.commercebridge;

import static ai.neargo.commerce.starter.security.CommerceRoutePolicy.Lane.*;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.commerce.api.access.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.subscription.AccessSegment;
import ai.neargo.commerce.core.spi.Period;
import ai.neargo.commerce.persistence.*;
import ai.neargo.commerce.starter.security.CommerceRoutePolicy;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.auth.store.DbAccountSessionVerifier;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.community.mapper.CommunityMappers.*;
import ai.neargo.shop.community.service.PlaceResolver;
import ai.neargo.shop.community.service.impl.CommunityServiceImpl;
import ai.neargo.shop.community.support.MapBreaker;
import ai.neargo.shop.config.SecurityConfig;
import ai.neargo.shop.fulfillment.service.PickupService;
import ai.neargo.shop.member.service.*;
import ai.neargo.shop.merchant.api.biz.BizStorePayController;
import ai.neargo.shop.merchant.mapper.MerchantMappers.*;
import ai.neargo.shop.merchant.port.StoreOwnershipWritePortImpl;
import ai.neargo.shop.merchant.service.*;
import ai.neargo.shop.merchant.service.impl.*;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.api.biz.BizMessageController;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.portal.biz.*;
import ai.neargo.shop.product.api.biz.BizGoodsController;
import ai.neargo.shop.product.service.*;
import ai.neargo.shop.spi.platform.GeoPort;
import ai.neargo.shop.spi.platform.MasterDataPort;
import ai.neargo.shop.spi.platform.PlatformSwitchPort;
import ai.neargo.shop.spi.product.GoodsVisionPort;
import ai.neargo.shop.spi.trade.AfterSalePendingQueryPort;
import ai.neargo.shop.spi.user.*;
import ai.neargo.shop.user.service.StoreFavoriteService;
import ai.neargo.shop.user.service.UserService;

import com.tngtech.archunit.core.importer.ClassFileImporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

class CommerceAccessSecurityFlowTest {
    final CommerceRoutePolicy policy = new CommerceRoutePolicy();

    @Test
    void newStoreNamespaceIsClosedExceptForExactMethodAndRegisteredRecoveryActions() {
        assertEquals(ACCOUNT, policy.match("POST", "/biz/auth/login").lane());
        assertEquals(ACCOUNT, policy.match("POST", "/biz/merchant/quick-start").lane());
        assertEquals(BOOTSTRAP, policy.match("GET", "/biz/stores/S/mall/products").lane());
        assertEquals(BOOTSTRAP, policy.match("POST", "/biz/stores/S/mall/orders").lane());
        assertEquals(BOOTSTRAP, policy.match("GET", "/biz/order/123/trace").lane());
        assertEquals(DENY, policy.match("POST", "/biz/order/123/trace").lane());
        assertEquals(DENY, policy.match("POST", "/biz/order/new").lane());
        assertEquals(BOOTSTRAP, policy.match("POST", "/biz/order/123/delivered").lane());
        assertEquals(DENY, policy.match("GET", "/biz/stores/S/other").lane());
        var pos = policy.match("GET", "/biz/stores/S/pos/access");
        assertEquals(END_PROTECTED, pos.lane());
        assertEquals("END_APP_POS", pos.entitlement());
        assertEquals("S", pos.pathStore());
        assertEquals(
                "biz:store:admin", policy.match("PUT", "/biz/store/S/pay-setting").capability());
    }

    @Test
    void missingFreshVerifierPreventsAssemblyOnMerchantApi() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=api", "commerce.enabled=true")
                .withBean(javax.sql.DataSource.class, () -> mock(javax.sql.DataSource.class))
                .withBean(
                        org.springframework.transaction.PlatformTransactionManager.class,
                        () ->
                                mock(
                                        org.springframework.transaction.PlatformTransactionManager
                                                .class))
                .withPropertyValues(
                        "commerce.access.active-key-id=t",
                        "commerce.access.keys.t="
                                + Base64.getEncoder().encodeToString(new byte[32]))
                .withBean(
                        ai.neargo.commerce.core.spi.CommerceUnitOfWork.class,
                        () -> mock(ai.neargo.commerce.core.spi.CommerceUnitOfWork.class))
                .withBean(BizIdentityResolver.class, () -> BizIdentityResolver.NONE)
                .withUserConfiguration(CommerceAccessConfiguration.class)
                .run(
                        ctx -> {
                            var failure = ctx.getStartupFailure();
                            assertNotNull(failure);
                            while (failure.getCause() != null) failure = failure.getCause();
                            assertTrue(
                                    failure.getMessage().contains("AccountSessionVerifier"),
                                    failure.toString());
                        });
    }

    @Test
    @EnabledIfEnvironmentVariable(
            named = "COMMERCE_MYSQL_URL",
            matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
    void disabledHostRefusesExistingNewStoresAndStopsNewStoresAppearingAfterStartup()
            throws Exception {
        try (var f = new AccessMysqlFixture()) {
            var runner =
                    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                            .withPropertyValues(
                                    "spring.profiles.active=api", "commerce.enabled=false")
                            .withBean(javax.sql.DataSource.class, () -> f.ds)
                            .withBean(
                                    org.springframework.transaction.PlatformTransactionManager
                                            .class,
                                    () -> f.manager)
                            .withUserConfiguration(DisabledCommerceConfiguration.class);
            runner.run(
                    ctx -> {
                        var failure = ctx.getStartupFailure();
                        assertNotNull(failure);
                        while (failure.getCause() != null) failure = failure.getCause();
                        assertTrue(failure.getMessage().contains("STORE_V1"), failure.toString());
                    });
            f.jdbc.update("UPDATE mch_store SET commerce_model=NULL");
            verifyDisabledEncodedRequests(f);
            runner.run(
                    ctx -> {
                        var guard = ctx.getBean(BizContextFilter.Admission.class);
                        var user = LoginUser.merchantByUser("U", "owner");
                        var scope = new BizContext("M", Set.of(), Set.of(), Set.of("S"), "S", true);
                        var req =
                                new org.springframework.mock.web.MockHttpServletRequest(
                                        "POST", "/biz/unknown");
                        req.addHeader("X-Store-No", "S");
                        assertTrue(
                                guard.admit(
                                        user,
                                        scope,
                                        req,
                                        new org.springframework.mock.web
                                                .MockHttpServletResponse()));
                        f.jdbc.update("UPDATE mch_store SET commerce_model='STORE_V1'");
                        var denied = new org.springframework.mock.web.MockHttpServletResponse();
                        assertFalse(guard.admit(user, scope, req, denied));
                        assertEquals(503, denied.getStatus());
                        for (String path : List.of("/biz/stores/mine", "/biz/context")) {
                            var selection =
                                    new org.springframework.mock.web.MockHttpServletRequest(
                                            "GET", path);
                            assertTrue(
                                    guard.admit(
                                            user,
                                            scope,
                                            selection,
                                            new org.springframework.mock.web
                                                    .MockHttpServletResponse()));
                            selection.addHeader("X-Store-No", "S");
                            var rejectedSelection =
                                    new org.springframework.mock.web.MockHttpServletResponse();
                            assertFalse(guard.admit(user, scope, selection, rejectedSelection));
                            assertEquals(503, rejectedSelection.getStatus());
                        }
                        req.setRequestURI("/biz/auth/logout");
                        assertTrue(
                                guard.admit(
                                        user,
                                        scope,
                                        req,
                                        new org.springframework.mock.web
                                                .MockHttpServletResponse()));
                    });
        }
    }

    @Test
    void accessBridgeUsesOnlyPublicCommerceApiAndStarter() {
        var classes =
                new ClassFileImporter()
                        .importClasses(
                                CommerceAccessConfiguration.class,
                                ShopCommerceActorAdapter.class,
                                ShopCommerceEndContextAdapter.class,
                                ShopCommerceStoreAdapter.class,
                                FoundationPaymentAccess.class,
                                FoundationTarget.class,
                                CommerceEndController.class,
                                ai.neargo.shop.portal.biz.BizFoundationController.class,
                                DisabledCommerceConfiguration.class,
                                CommerceAccessErrors.class);
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("ai.neargo.commerce.core..", "ai.neargo.commerce.persistence..")
                .check(classes);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Web {}

    @Test
    @EnabledIfEnvironmentVariable(
            named = "COMMERCE_MYSQL_URL",
            matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
    void realMerchantChainAndActualWriteTransactionUseFreshFactsAndTheSharedAccountLock()
            throws Exception {
        try (var f = new AccessMysqlFixture();
                var web = new AnnotationConfigWebApplicationContext()) {
            new CommerceMigrationRunner(f.ds).migrate();
            var ref = new StoreRef("MAIN", "S");
            var repo = new JdbcSubscriptionRepository(f.ds, ns -> UUID.randomUUID().toString());
            var tx = new TransactionTemplate(f.manager);
            seedSubscriptions(f, ref, repo, tx);
            var mvc = merchantMvc(f, web, true);
            var resolver = web.getBean(BizIdentityResolver.class);
            var qualification = web.getBean(QualificationPort.class);
            String token =
                    f.store.issue(
                            TokenStore.SessionData.of(LoginUser.merchantByUser("U", "owner")));
            f.jdbc.update(
                    "INSERT INTO mch_store(entity_no,store_no,created_at,updated_at) VALUES"
                            + " ('M','legacy',NOW(),NOW())");
            f.jdbc.update(
                    "INSERT INTO mch_entity(entity_no,name,created_at,updated_at) VALUES"
                            + " ('M2','second',NOW(),NOW())");
            f.jdbc.update(
                    "INSERT INTO"
                        + " mch_account(mch_account_no,user_no,entity_no,is_owner,created_at,updated_at)"
                        + " VALUES ('MA2','U','M2',1,NOW(),NOW())");
            f.jdbc.update("UPDATE mch_store SET entity_no='M2' WHERE store_no='legacy'");
            var bearer = "Bearer " + token;
            // All-legacy identity as well as mixed identity: use raw URIs to avoid MockMvc double
            // encoding.
            mvc.perform(
                            get(URI.create("/%62iz/stores/S/pos/access"))
                                    .header("Authorization", bearer))
                    .andExpect(status().isBadRequest());
            f.jdbc.update("UPDATE mch_store SET commerce_model=NULL WHERE store_no='S'");
            assertRecovery(mvc, bearer, false);
            assertAll(
                    () -> assertLegacyImpact(mvc, web.getBean(MerchantGoodsService.class), bearer),
                    () -> assertApplicantRecovery(mvc, f, web.getBean(OpsService.class), bearer));
            assertEquals(
                    400,
                    mvc.perform(
                                    get(URI.create("/biz/%73tores/S/pos/access"))
                                            .header("Authorization", bearer))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            // MVC also accepts HEAD for GET handlers and arbitrary non-slash path variables.
            mvc.perform(
                            head("/biz/stores/S/pos/access")
                                    .header("Authorization", bearer)
                                    .header("X-Store-No", "S"))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            get("/biz/stores/S.dot/pos/access")
                                    .header("Authorization", bearer)
                                    .header("X-Store-No", "S"))
                    .andExpect(status().isForbidden());
            f.jdbc.update("UPDATE mch_store SET commerce_model='STORE_V1' WHERE store_no='S'");
            assertRecovery(mvc, bearer, true);
            mvc.perform(
                            get(URI.create("/%62iz/stores/S/pos/access"))
                                    .header("Authorization", bearer))
                    .andExpect(status().isBadRequest());
            mvc.perform(
                            post("/biz/unknown")
                                    .header("Authorization", "Bearer " + token)
                                    .header("X-Store-No", "legacy")
                                    .contentType("application/json")
                                    .content("{\"storeNo\":\"S\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            get("/biz/store/legacy/pay-setting")
                                    .header("Authorization", "Bearer " + token)
                                    .header("X-Store-No", "legacy"))
                    .andExpect(status().isOk());
            var issue =
                    post("/biz/stores/S/end-sessions")
                            .header("Authorization", "Bearer " + token)
                            .header("X-Store-No", "S")
                            .contentType("application/json")
                            .content("{\"endCode\":\"BIZ_APP\",\"clientId\":\"biz-app\"}");
            var body =
                    mvc.perform(issue)
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            var proof =
                    new tools.jackson.databind.ObjectMapper()
                            .readTree(body)
                            .get("value")
                            .asString();
            verifyOldSnapshot(
                    f, ref, repo, tx, resolver, web.getBean(EndAccessFacade.class), token, proof);
            var refreshed =
                    mvc.perform(issue)
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            proof =
                    new tools.jackson.databind.ObjectMapper()
                            .readTree(refreshed)
                            .get("value")
                            .asString();
            var write =
                    put("/biz/store/S/pay-setting")
                            .header("Authorization", "Bearer " + token)
                            .header("X-Store-No", "S")
                            .header("X-Client-Id", "biz-app")
                            .header("X-End-Context", proof)
                            .contentType("application/json")
                            .content("{\"offlinePayEnabled\":true,\"codEnabled\":true}");
            mvc.perform(
                            get("/biz/store/O/pay-setting")
                                    .header("Authorization", "Bearer " + token)
                                    .header("X-Store-No", "S")
                                    .header("X-End-Context", proof))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            get("/biz/stores/S/pos/access")
                                    .header("Authorization", "Bearer " + token)
                                    .header("X-Store-No", "S")
                                    .header("X-Client-Id", "pos-app")
                                    .header("X-End-Context", proof))
                    .andExpect(status().isConflict());
            mvc.perform(
                            get("/ops/after-sales")
                                    .header("Authorization", "Bearer " + token)
                                    .header("X-End-Context", proof))
                    .andExpect(status().isUnauthorized());
            mvc.perform(
                            post("/biz/stores/S/unknown")
                                    .header("Authorization", "Bearer " + token)
                                    .header("X-Store-No", "S")
                                    .contentType("application/json")
                                    .content("{\"subjectKind\":\"SYSTEM\"}"))
                    .andExpect(status().isForbidden());
            // Business validation after admission rolls back. A legitimate admitted write then
            // holds
            // the exact lock used by entitlement changes until the actual service transaction
            // commits.
            f.jdbc.update(
                    "INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                            + " VALUES ('MA','S','VIEWER',NOW(),NOW())");
            f.jdbc.update(
                    "INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at)"
                            + " VALUES ('M','VIEWER','view','[\"biz:store\"]',NOW(),NOW())");
            f.jdbc.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no='MA'");
            mvc.perform(
                            post("/biz/stores/S/end-sessions")
                                    .header("Authorization", bearer)
                                    .header("X-Store-No", "S")
                                    .contentType("application/json")
                                    .content(
                                            "{\"endCode\":\"APP_POS\",\"clientId\":\"pos-app\",\"actor\":{\"subjectKind\":\"SYSTEM\"}}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ErrorCode.BIZ_ROLE_FORBIDDEN.code()))
                    .andExpect(jsonPath("$.value").doesNotExist());
            mvc.perform(
                            put("/biz/store/S/pay-setting")
                                    .header("Authorization", bearer)
                                    .header("X-Store-No", "S")
                                    .header("X-Client-Id", "biz-app")
                                    .header("X-End-Context", proof)
                                    .contentType("application/json")
                                    .content(
                                            "{\"actor\":{\"subjectKind\":\"SYSTEM\",\"principalNo\":\"owner\"},\"offlinePayEnabled\":true}"))
                    .andExpect(status().isForbidden());
            assertEquals(
                    0,
                    f.jdbc.queryForObject(
                            "SELECT offline_pay_enabled FROM mch_store WHERE store_no='S'",
                            Integer.class));
            mvc.perform(write)
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("FORBIDDEN")));
            // The same member can still enter the base end with the read duty.
            mvc.perform(issue).andExpect(status().isOk());
            f.jdbc.update("UPDATE mch_account SET is_owner=1 WHERE mch_account_no='MA'");
            when(qualification.hasValidQualification(anyString(), anyString()))
                    .thenReturn(true)
                    .thenThrow(new IllegalStateException("failed after store update"));
            mvc.perform(write).andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR.code()));
            assertEquals(
                    0,
                    f.jdbc.queryForObject(
                            "SELECT offline_pay_enabled FROM mch_store WHERE store_no='S'",
                            Integer.class));
            verifyLockUntilCommit(f, ref, repo, tx, qualification, mvc, write);
            assertEquals(
                    1,
                    f.jdbc.queryForObject(
                            "SELECT cod_enabled FROM mch_store WHERE store_no='S'", Integer.class));
            mvc.perform(write)
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("REVOKED")));
            mvc.perform(
                            get("/biz/stores/S/end-access")
                                    .param("endCode", "BIZ_APP")
                                    .param("clientId", "biz-app")
                                    .header("Authorization", "Bearer " + token)
                                    .header("X-Store-No", "S"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REVOKED"));
            mvc.perform(
                            get("/biz/merchant/profile")
                                    .header("Authorization", bearer)
                                    .header("X-Store-No", "S"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").exists());
            f.dao.revoke(
                    ai.neargo.auth.store.TokenHash.of(token),
                    ai.neargo.auth.store.RevokeReason.LOGOUT,
                    java.time.LocalDateTime.now(f.clock));
            assertTrue(f.store.get(token).isPresent());
            mvc.perform(
                            get("/biz/stores/S/end-access")
                                    .param("endCode", "BIZ_APP")
                                    .param("clientId", "biz-app")
                                    .header("Authorization", "Bearer " + token)
                                    .header("X-Store-No", "S"))
                    .andExpect(status().isUnauthorized());
        }
    }

    static MockMvc merchantMvc(
            AccessMysqlFixture f, AnnotationConfigWebApplicationContext web, boolean enabled) {
        return merchantMvc(f, web, enabled, Map.of());
    }

    static MockMvc merchantMvc(AccessMysqlFixture f, AnnotationConfigWebApplicationContext web,
            boolean enabled, Map<Class<?>, Object> realServices) {
        var resolver =
                new BizIdentityResolverImpl(
                        f.mybatis.getMapper(MchEntityMapper.class),
                        mock(PickupQueryPort.class),
                        f.mybatis.getMapper(MchAccountMapper.class),
                        f.mybatis.getMapper(MchStoreMapper.class),
                        f.mybatis.getMapper(MchStoreRoleMapper.class),
                        f.mybatis.getMapper(MchRoleMapper.class));
        var sessions = new DbAccountSessionVerifier(f.dao, f.loader, f.clock, f.manager);
        var qualification = mock(QualificationPort.class);
        web.setServletContext(new MockServletContext());
        web.getEnvironment().setActiveProfiles("api", "ops");
        var key =
                Base64.getEncoder()
                        .encodeToString(new java.security.SecureRandom().generateSeed(32));
        web.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "accessTest",
                                Map.of(
                                        "commerce.enabled",
                                        Boolean.toString(enabled),
                                        "commerce.access.active-key-id",
                                        "test",
                                        "commerce.access.keys.test",
                                        key)));
        web.addBeanFactoryPostProcessor(
                b -> {
                    for (var type :
                            List.of(
                                    PickupQueryPort.class,
                                    MemberService.class,
                                    MemberTagService.class,
                                    MemberSegmentService.class,
                                    MemberReachService.class,
                                    MemberAudienceService.class,
                                    PlaceResolver.class,
                                    GeoPlaceMapper.class,
                                    GeoPort.class,
                                    MasterDataPort.class,
                                    MapBreaker.class,
                                    MerchantGoodsService.class,
                                    SpecLibraryService.class,
                                    GoodsRevisionService.class,
                                    CategoryService.class,
                                    GoodsVisionPort.class,
                                    SpuStdService.class,
                                    OpsService.class,
                                    ai.neargo.shop.user.service.AuthService.class,
                                    ai.neargo.shop.auth.captcha.BizCaptchaService.class,
                                    ai.neargo.shop.common.otp.service.OtpSendService.class,
                                    LoginIdentityService.class,
                                    LoginAuditor.class,
                                    UserService.class,
                                    MerchantService.class,
                                    MerchantStoreService.class,
                                    MerchantPaymentService.class,
                                    StoreAdminService.class,
                                    CommunityQueryPort.class,
                                    StoreCategoryService.class,
                                    MerchantGovernService.class,
                                    MerchantAdminPort.class,
                                    DebtService.class,
                                    StoreFavoriteService.class,
                                    TokenStores.class,
                                    MchStaffLogMapper.class,
                                    StaffAuditLogger.class,
                                    ai.neargo.shop.common.OtpStore.class,
                                    PickupService.class,
                                    StoreCodeService.class,
                                    StoreLinkService.class,
                                    MerchantQueryPort.class,
                                    PlatformSwitchPort.class,
                                    MessageService.class,
                                    AfterSalePendingQueryPort.class)) {
                        if (realServices.containsKey(type))
                            b.registerSingleton(type.getSimpleName(),realServices.get(type));
                        else if (realServices.values().stream().noneMatch(type::isInstance))
                            b.registerSingleton(type.getSimpleName(),mock(type));
                    }
                    b.registerSingleton("accounts", f.mybatis.getMapper(MchAccountMapper.class));
                    b.registerSingleton("entityRows", f.mybatis.getMapper(MchEntityMapper.class));
                    b.registerSingleton("qualificationRows", f.mybatis.getMapper(ai.neargo.shop.merchant.mapper.MerchantMappers.QualificationMapper.class));
                    b.registerSingleton("roleRows", f.mybatis.getMapper(MchRoleMapper.class));
                    b.registerSingleton(
                            "storeRoleRows", f.mybatis.getMapper(MchStoreRoleMapper.class));
                    b.registerSingleton("communities", f.mybatis.getMapper(CommunityMapper.class));
                    b.registerSingleton(
                            "pickupPoints", f.mybatis.getMapper(PickupPointMapper.class));
                    b.registerSingleton("clock", f.clock);
                    b.registerSingleton("dataSource", f.ds);
                    b.registerSingleton("ownershipWrites", new StoreOwnershipWritePortImpl(f.jdbc));
                    b.registerSingleton("transactionManager", f.manager);
                    b.registerSingleton("uow", new JdbcCommerceUnitOfWork(f.ds, f.manager));
                    b.registerSingleton("resolver", resolver);
                    b.registerSingleton("verifier", sessions);
                    b.registerSingleton("tokenStore", realServices.getOrDefault(TokenStore.class, f.store));
                    b.registerSingleton("qual", qualification);
                    b.registerSingleton("stores", f.mybatis.getMapper(MchStoreMapper.class));
                    b.registerSingleton("portalFilters", portalFilters());
                });
        web.register(
                Web.class,
                SecurityConfig.class,
                CommerceAccessConfiguration.class,
                DisabledCommerceConfiguration.class,
                MerchantEntityServiceImpl.class,
                MerchantStaffServiceImpl.class,
                CommunityServiceImpl.class,
                BizPickupPointController.class,
                BizMemberController.class,
                MemberQueryScopeAuthority.class,
                CommerceEndController.class,
                BizEntityController.class,
                ai.neargo.shop.merchantbridge.EntityReviewAssembler.class,
                BizGoodsController.class,
                FoundationPaymentAccess.class,
                BizMerchantController.class,
                ai.neargo.shop.portal.biz.BizAuthController.class,
                ai.neargo.shop.portal.biz.BizIdentityController.class,
                BizMessageController.class,
                BizPickupController.class,
                CommerceAccessErrors.class,
                GlobalExceptionHandler.class,
                BizStorePayController.class,
                StorePaySettingServiceImpl.class,
                PermChecker.class);
        web.refresh();
        return MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
    }

    /**
     * 统一线的 SecurityConfig 给每条链挂支付接入守卫（pay-guard）。这里的端点都不在守卫前缀下
     * （守卫只管 /biz/pay、/pay-ops/v1 等），所以商家 / 运营用真的会话守卫，其余入口给一个一律拒绝的占位。
     */
    static ai.neargo.shop.pay.api.guard.core.GuardFilters portalFilters() {
        var guards = new java.util.ArrayList<ai.neargo.shop.pay.api.guard.core.EntryGuard>();
        guards.add(new ai.neargo.shop.pay.api.guard.biz.BizSessionAuthenticator());
        guards.add(new ai.neargo.shop.pay.api.guard.ops.OpsSessionAuthenticator());
        for (var face : ai.neargo.shop.pay.api.guard.PortalFace.values()) {
            if (face == ai.neargo.shop.pay.api.guard.PortalFace.BIZ
                    || face == ai.neargo.shop.pay.api.guard.PortalFace.OPS) continue;
            guards.add(
                    new ai.neargo.shop.pay.api.guard.core.EntryGuard() {
                        @Override
                        public ai.neargo.shop.pay.api.guard.PortalFace entry() {
                            return face;
                        }

                        @Override
                        public ai.neargo.shop.pay.api.guard.core.GuardResult authenticate(
                                jakarta.servlet.http.HttpServletRequest request) {
                            return ai.neargo.shop.pay.api.guard.core.GuardResult.unauthorized(
                                    "NOT_UNDER_TEST");
                        }
                    });
        }
        return new ai.neargo.shop.pay.api.guard.core.GuardFilters(
                guards, mock(ai.neargo.shop.pay.api.guard.core.RequestLogWriter.class));
    }

    private static void verifyDisabledEncodedRequests(AccessMysqlFixture f) throws Exception {
        try (var web = new AnnotationConfigWebApplicationContext()) {
            var entities = mock(MerchantEntityService.class);
            var review = mock(ai.neargo.shop.merchantbridge.EntityReviewAssembler.class);
            web.setServletContext(new MockServletContext());
            web.getEnvironment().setActiveProfiles("api");
            web.addBeanFactoryPostProcessor(
                    b -> {
                        b.registerSingleton("dataSource", f.ds);
                        b.registerSingleton("transactionManager", f.manager);
                        b.registerSingleton("resolver", BizIdentityResolver.NONE);
                        b.registerSingleton("tokenStore", f.store);
                        b.registerSingleton("entities", entities);
                        b.registerSingleton("review", review);
                        b.registerSingleton("portalFilters", portalFilters());
                    });
            web.register(
                    Web.class,
                    SecurityConfig.class,
                    DisabledCommerceConfiguration.class,
                    BizEntityController.class);
            web.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
            String bearer =
                    "Bearer "
                            + f.store.issue(
                                    TokenStore.SessionData.of(
                                            LoginUser.merchantByUser("U", "owner")));
            mvc.perform(get(URI.create("/biz/%73tores/mine")).header("Authorization", bearer))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(entities, review);
        }
    }

    private static void assertLegacyImpact(
            org.springframework.test.web.servlet.MockMvc mvc,
            MerchantGoodsService goods,
            String bearer)
            throws Exception {
        String path = "/biz/stores/{store}/fulfillment/EXPRESS/impact";
        when(goods.onlyFulfillment("M", "S", "EXPRESS"))
                .thenReturn(List.of(new MerchantGoodsService.GoodsBrief("G", "preview")));
        mvc.perform(get(path, "S").header("Authorization", bearer).header("X-Store-No", "S"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].goodsNo").value("G"));
        mvc.perform(head(path, "S").header("Authorization", bearer).header("X-Store-No", "S"))
                .andExpect(status().isOk());
        verify(goods, times(2)).onlyFulfillment("M", "S", "EXPRESS");
        mvc.perform(get(path, "default").header("Authorization", bearer).header("X-Store-No", "S"))
                .andExpect(status().isOk());
        verify(goods).onlyFulfillment("M", null, "EXPRESS");
        for (String target : List.of("missing", "legacy")) {
            mvc.perform(get(path, target).header("Authorization", bearer).header("X-Store-No", "S"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post(path, "S").header("Authorization", bearer).header("X-Store-No", "S"))
                .andExpect(status().isForbidden());
        verifyNoMoreInteractions(goods);
    }

    private static void assertApplicantRecovery(
            org.springframework.test.web.servlet.MockMvc mvc,
            AccessMysqlFixture f,
            OpsService ops,
            String bearer)
            throws Exception {
        String application = "/biz/merchant/apply";
        var matching = get(application).header("Authorization", bearer).header("X-Store-No", "S");
        var picker =
                get("/biz/stores/mine").header("Authorization", bearer).header("X-Store-No", "S");
        try {
            f.jdbc.update(
                    "UPDATE mch_store SET commerce_model='STORE_V1',status='SUSPENDED' WHERE"
                            + " store_no='S'");
            mvc.perform(matching).andExpect(status().isOk());
            verify(ops).myApply("U");
            mvc.perform(picker).andExpect(status().isForbidden());
            mvc.perform(
                            get(application)
                                    .header("Authorization", bearer)
                                    .header("X-Store-No", "missing"))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            get("/biz/stores/legacy/fulfillment/EXPRESS/impact")
                                    .header("Authorization", bearer)
                                    .header("X-Store-No", "legacy"))
                    .andExpect(status().isForbidden());
            // An unreadable commercial model does not prevent the applicant checking their account.
            f.jdbc.update("UPDATE mch_store SET commerce_model='UNRECOGNIZED' WHERE store_no='S'");
            mvc.perform(matching).andExpect(status().isOk());
            verify(ops, times(2)).myApply("U");
            mvc.perform(picker).andExpect(status().isServiceUnavailable());
        } finally {
            f.jdbc.update(
                    "UPDATE mch_store SET commerce_model=NULL,status='ACTIVE' WHERE store_no='S'");
        }
    }

    private static void assertRecovery(
            org.springframework.test.web.servlet.MockMvc mvc, String bearer, boolean mixed)
            throws Exception {
        assertAll(
                () ->
                        mvc.perform(get("/biz/stores/mine").header("Authorization", bearer))
                                .andExpect(status().isOk()),
                () ->
                        mvc.perform(get("/biz/context").header("Authorization", bearer))
                                .andExpect(status().isOk()),
                () ->
                        mvc.perform(
                                        get("/biz/context")
                                                .header("Authorization", bearer)
                                                .header("X-Store-No", "S"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.currentStoreNo").value("S")),
                () ->
                        mvc.perform(
                                        get("/biz/message")
                                                .header("Authorization", bearer)
                                                .header("X-Store-No", "S"))
                                .andExpect(status().isOk()),
                () ->
                        mvc.perform(
                                        get("/biz/message/unread-count")
                                                .header("Authorization", bearer)
                                                .header("X-Store-No", "S"))
                                .andExpect(status().isOk()),
                () ->
                        mvc.perform(
                                        post("/biz/message/M1/read")
                                                .header("Authorization", bearer)
                                                .header("X-Store-No", "S"))
                                .andExpect(status().isOk()),
                () ->
                        mvc.perform(
                                        post("/biz/message/read-all")
                                                .header("Authorization", bearer)
                                                .header("X-Store-No", "S"))
                                .andExpect(status().isOk()),
                () ->
                        mvc.perform(get("/biz/message").header("Authorization", bearer))
                                .andExpect(mixed ? status().isForbidden() : status().isOk()),
                () ->
                        mvc.perform(
                                        get("/biz/stores/mine")
                                                .header("Authorization", bearer)
                                                .header("X-Store-No", "missing"))
                                .andExpect(mixed ? status().isForbidden() : status().isOk()));
    }

    static void seedSubscriptions(
            AccessMysqlFixture f,
            StoreRef ref,
            JdbcSubscriptionRepository repo,
            TransactionTemplate tx) {
        tx.executeWithoutResult(
                status -> {
                    repo.ensureAccount(
                            new StoreSnapshot(
                                    ref, "M", "RETAIL", StoreStatus.ACTIVE, CommerceModel.STORE_V1),
                            "U");
                    var account = repo.lock(ref);
                    var bindings = Map.of("END_BIZ_APP", "BIZ_APP", "END_APP_POS", "APP_POS");
                    var p =
                            new Period(
                                    "free",
                                    "init",
                                    "FREE",
                                    "plan",
                                    "v",
                                    "FREE",
                                    f.clock.millis(),
                                    f.clock.millis(),
                                    null,
                                    bindings,
                                    0,
                                    0,
                                    "CNY",
                                    "policy",
                                    false);
                    repo.insert(account, p, 1, "U");
                    bindings.forEach(
                            (code, end) -> {
                                String client = end.equals("BIZ_APP") ? "biz-app" : "pos-app";
                                f.jdbc.update(
                                        """
INSERT INTO sys_business_end(tenant_no,created_at,updated_at,version,deleted,end_code,entitlement_code,
  name,scope,allowed_business_types,release_status,sale_enabled,bootstrap_required,enabled,client_id)
VALUES ('MAIN',NOW(),NOW(),1,0,?,?,?,'STORE','["RETAIL"]','VERIFIED',0,0,1,?)
""",
                                        end,
                                        code,
                                        end,
                                        client);
                                repo.revise(
                                        ref,
                                        code,
                                        "op",
                                        f.clock.millis(),
                                        1,
                                        List.of(),
                                        List.of(
                                                new AccessSegment(
                                                        "free", code, f.clock.instant(), null)),
                                        List.of(p),
                                        "U");
                            });
                    repo.updateVersion(account, 1, "U");
                });
    }

    private static void verifyOldSnapshot(
            AccessMysqlFixture f,
            StoreRef ref,
            JdbcSubscriptionRepository repo,
            TransactionTemplate tx,
            BizIdentityResolver resolver,
            EndAccessFacade business,
            String token,
            String proof) {
        // Keep both JDBC and MyBatis stale in a caller transaction. Each fresh host snapshot
        // must see the independently committed loss of membership or governance.
        var principal = LoginUser.merchantByUser("U", "owner");
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        authentication.setDetails(token);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var check =
                new AccessCheck(
                        ShopCommerceActorAdapter.current(),
                        ref,
                        ref,
                        Optional.of(ref),
                        "BIZ_APP",
                        "END_BIZ_APP",
                        "biz-app",
                        "biz:store:admin",
                        Optional.of(proof));
        var change = new TransactionTemplate(f.manager);
        change.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            for (var target : List.of("mch_account", "mch_store")) {
                tx.executeWithoutResult(
                        st -> {
                            assertEquals(
                                    "ACTIVE",
                                    f.jdbc.queryForObject(
                                            "SELECT status FROM "
                                                    + target
                                                    + " WHERE "
                                                    + (target.equals("mch_store")
                                                            ? "store_no='S'"
                                                            : "mch_account_no='MA'"),
                                            String.class));
                            assertEquals(
                                    "S", resolver.resolveStrict(principal, "S").currentStoreNo());
                            change.executeWithoutResult(
                                    t ->
                                            f.jdbc.update(
                                                    "UPDATE "
                                                            + target
                                                            + " SET status='SUSPENDED' WHERE "
                                                            + (target.equals("mch_store")
                                                                    ? "store_no='S'"
                                                                    : "mch_account_no='MA'")));
                            assertEquals(
                                    "ACTIVE",
                                    f.jdbc.queryForObject(
                                            "SELECT status FROM "
                                                    + target
                                                    + " WHERE "
                                                    + (target.equals("mch_store")
                                                            ? "store_no='S'"
                                                            : "mch_account_no='MA'"),
                                            String.class));
                            var denied =
                                    assertThrows(
                                            AccessDenied.class,
                                            () -> business.requireForWrite(check));
                            assertEquals(
                                    target.equals("mch_store")
                                            ? AccessStatus.STORE_DISABLED
                                            : AccessStatus.FORBIDDEN,
                                    denied.status());
                        });
                f.jdbc.update("UPDATE " + target + " SET status='ACTIVE'");
            }
            tx.executeWithoutResult(
                    st -> {
                        assertEquals(
                                1L,
                                f.jdbc.queryForObject(
                                        "SELECT entitlement_version FROM"
                                                + " mch_subscription_account",
                                        Long.class));
                        change.executeWithoutResult(
                                t -> repo.updateVersion(repo.lock(ref), 2, "U"));
                        assertEquals(
                                1L,
                                f.jdbc.queryForObject(
                                        "SELECT entitlement_version FROM"
                                                + " mch_subscription_account",
                                        Long.class));
                        assertEquals(
                                AccessStatus.CONTEXT_REFRESH_REQUIRED,
                                assertThrows(
                                                AccessDenied.class,
                                                () -> business.requireForWrite(check))
                                        .status());
                    });
            assertThrows(IllegalStateException.class, () -> business.requireForWrite(check));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void verifyLockUntilCommit(
            AccessMysqlFixture f,
            StoreRef ref,
            JdbcSubscriptionRepository repo,
            TransactionTemplate tx,
            QualificationPort qualification,
            org.springframework.test.web.servlet.MockMvc mvc,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder write)
            throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempted = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            assertTrue(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            entered.countDown();
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                            return true;
                        })
                .when(qualification)
                .hasValidQualification(anyString(), anyString());
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var accepted =
                    workers.submit(() -> mvc.perform(write).andReturn().getResponse().getStatus());
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var revoked =
                        workers.submit(
                                () ->
                                        tx.execute(
                                                st -> {
                                                    attempted.countDown();
                                                    var account = repo.lock(ref);
                                                    var before = repo.segments(ref, "END_BIZ_APP");
                                                    repo.revise(
                                                            ref,
                                                            "END_BIZ_APP",
                                                            "revoke",
                                                            f.clock.millis(),
                                                            3,
                                                            before,
                                                            List.of(),
                                                            List.of(),
                                                            "U");
                                                    f.jdbc.update(
                                                            "UPDATE mch_store_end_grant SET"
                                                                + " state='REVOKED' WHERE"
                                                                + " entitlement_code='END_BIZ_APP'");
                                                    repo.updateVersion(account, 3, "U");
                                                    return true;
                                                }));
                assertTrue(attempted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> revoked.get(250, TimeUnit.MILLISECONDS));
                // Host governance changes after admission can commit while this business TX holds
                // its commercial lock.
                f.jdbc.update(
                        "UPDATE mch_account SET status='SUSPENDED' WHERE mch_account_no='MA'");
                release.countDown();
                assertEquals(200, accepted.get(10, TimeUnit.SECONDS));
                assertTrue(revoked.get(10, TimeUnit.SECONDS));
                f.jdbc.update("UPDATE mch_account SET status='ACTIVE' WHERE mch_account_no='MA'");
            } finally {
                release.countDown();
            }
        }
    }
}
