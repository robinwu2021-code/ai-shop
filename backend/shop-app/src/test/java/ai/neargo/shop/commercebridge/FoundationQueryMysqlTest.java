package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.port.CommerceIdGenerator;
import ai.neargo.commerce.core.access.EndScheduleService;
import ai.neargo.commerce.core.subscription.SubscriptionService;
import ai.neargo.commerce.persistence.CommerceMigrationRunner;
import ai.neargo.commerce.persistence.JdbcCatalogRepository;
import ai.neargo.commerce.persistence.JdbcCommerceEventPersistence;
import ai.neargo.commerce.persistence.JdbcCommerceUnitOfWork;
import ai.neargo.commerce.persistence.JdbcSubscriptionRepository;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.*;
import ai.neargo.shop.merchant.port.MerchantPortImpl;
import ai.neargo.shop.merchant.service.*;
import ai.neargo.shop.merchant.service.impl.MerchantServiceImpl;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.spi.notify.OpsAlertPort;
import ai.neargo.shop.spi.user.MerchantAdminPort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import tools.jackson.databind.*;

import java.util.*;

@EnabledIfEnvironmentVariable(
        named = "COMMERCE_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
class FoundationQueryMysqlTest {
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode ok(MockMvc mvc, MockHttpServletRequestBuilder req, String bearer)
            throws Exception {
        var body =
                mvc.perform(req.header("Authorization", bearer))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(0))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body).get("data");
    }

    @Test
    void ownerCreatesSelectsFilesRecoversAndStaffReadsThePinnedFree() throws Exception {
        try (var f = new AccessMysqlFixture(java.time.Clock.systemUTC());
                var web = new AnnotationConfigWebApplicationContext();
                var services = new AnnotationConfigApplicationContext()) {
            new CommerceMigrationRunner(f.ds).migrate();
            CommerceStoreCreationFlowTest.seedPublishedCatalog(f.jdbc);
            f.jdbc.update(
                    "insert into usr_account(user_no,created_at,updated_at) values"
                            + " ('N',now(),now())");
            var authority = new HostStoreCreationAuthority(f.ds, Map.of("CN", "CNY"));
            var facade =
                    new CommerceStoreCreationConfiguration()
                            .storeProvisioningFacade(
                                    f.ds,
                                    new JdbcCommerceUnitOfWork(f.ds, f.manager),
                                    new JdbcCommerceEventPersistence(f.ds),
                                    authority);
            var creation =
                    new MerchantStoreCreation(true, Optional.of(authority), Optional.of(facade));
            var entities = f.mybatis.getMapper(MchEntityMapper.class);
            var stores = f.mybatis.getMapper(MchStoreMapper.class);
            var port =
                    new MerchantPortImpl(
                            creation,
                            entities,
                            null,
                            null,
                            null,
                            null,
                            null,
                            f.mybatis.getMapper(MchAccountMapper.class),
                            stores,
                            null,
                            null,
                            null,
                            null,
                            null,
                            f.mybatis.getMapper(EntityPlanMapper.class),
                            f.mybatis.getMapper(PlanDefMapper.class),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null);
            services.registerBean(MerchantPortImpl.class, () -> port);
            BizFoundationAccessMysqlTest.registerApplication(
                    f, services, port, mock(OpsAlertPort.class));
            services.refresh();
            web.register(
                    ApiResponseWrapper.class,
                    ai.neargo.shop.portal.biz.BizFoundationController.class);
            var mvc =
                    CommerceAccessSecurityFlowTest.merchantMvc(
                            f,
                            web,
                            true,
                            Map.of(
                                    MerchantAdminPort.class,
                                    services.getBean(MerchantAdminPort.class),
                                    OpsService.class,
                                    services.getBean(OpsService.class),
                                    MerchantService.class,
                                    new MerchantServiceImpl(entities, null, json, stores, null)));
            String bearer =
                    "Bearer "
                            + f.store.issue(
                                    TokenStore.SessionData.of(
                                            LoginUser.merchantByUser("N", "new")));
            assertEquals(
                    "NONE", ok(mvc, get("/biz/merchant/profile"), bearer).get("status").asText());
            assertEquals(
                    FoundationHttpExamples.example("types").at("/response/data"),
                    ok(mvc, get("/biz/business-types"), bearer));
            var catalog = ok(mvc, get("/biz/business-types/RETAIL/plans"), bearer);
            assertEquals(FoundationHttpExamples.example("plans").at("/response/data"), catalog);
            var create =
                    post("/biz/merchant/quick-start")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeName\":\"Example\",\"businessTypeCode\":\"RETAIL\"}");
            var profile = ok(mvc, create, bearer);
            String store = profile.get("currentStoreNo").asText(),
                    entity = profile.get("merchantNo").asText();
            assertEquals(store, ok(mvc, create, bearer).get("currentStoreNo").asText());
            var scope = "/biz/stores/" + store;
            var ctx = ok(mvc, get(scope + "/context").header("X-Store-No", store), bearer);
            assertEquals("PENDING_LICENSE", ctx.get("entityStatus").asText());
            assertEquals("ACTIVE", ctx.get("storeStatus").asText());
            assertEquals("FREE-v", ctx.at("/subscription/free/planVersionNo").asText());
            assertEquals("ACTIVE", ctx.at("/entitlements/items/0/commercialStatus").asText());
            FoundationHttpExamples.data("context", ctx);
            FoundationHttpExamples.data(
                    "subscription",
                    ok(mvc, get(scope + "/subscription").header("X-Store-No", store), bearer));
            FoundationHttpExamples.data(
                    "entitlements",
                    ok(mvc, get(scope + "/entitlements").header("X-Store-No", store), bearer));
            var application =
                    post("/biz/merchant/apply")
                            .header("X-Store-No", store)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    "{\"subject\":\"ENTERPRISE\",\"name\":\"Example\",\"contactName\":\"Owner\",\"contactPhone\":\"15965010103\",\"industry\":\"RETAIL\",\"serviceScope\":\"COMMUNITY\",\"communityNos\":[\"CM001\"],\"licenses\":[\"example-license\"]}");
            ok(mvc, application, bearer);
            assertEquals(
                    "Example",
                    ok(mvc, get("/biz/merchant/apply").header("X-Store-No", store), bearer)
                            .get("name")
                            .asText());
            assertEquals(
                    store,
                    ok(mvc, get("/biz/merchant/profile").header("X-Store-No", store), bearer)
                            .get("currentStoreNo")
                            .asText());
            f.jdbc.update(
                    "insert into"
                        + " sys_industry_plan_version(tenant_no,created_at,updated_at,version,deleted,plan_version_no,plan_no,revision,display_name,status,policy_version,published_at)"
                        + " values"
                        + " ('MAIN',now(),now(),1,0,'FREE-new','FREE',2,'changed','PUBLISHED','policy',234)");
            f.jdbc.update(
                    "update sys_industry_plan set current_version_no='FREE-new' where"
                            + " tier_code='FREE'");
            assertEquals(
                    "FREE-new",
                    ok(mvc, get("/biz/business-types/RETAIL/plans"), bearer)
                            .at("/plans/0/planVersionNo")
                            .asText());
            f.jdbc.update("update sys_business_type set enabled=0");
            assertTrue(ok(mvc, get("/biz/business-types"), bearer).isEmpty());
            f.jdbc.update(
                    "insert into"
                        + " mch_account(mch_account_no,entity_no,display_name,created_at,updated_at)"
                        + " values ('worker',?,'worker',now(),now())",
                    entity);
            f.jdbc.update(
                    "insert into mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                            + " values ('worker',?,'VIEWER',now(),now())",
                    store);
            f.jdbc.update(
                    "insert into mch_role(entity_no,role_code,name,perms,created_at,updated_at)"
                            + " values (?,'VIEWER','viewer','[]',now(),now())",
                    entity);
            String worker =
                    "Bearer "
                            + f.store.issue(
                                    TokenStore.SessionData.of(
                                            LoginUser.merchant("worker", "worker")));
            var selected = get(scope + "/context").header("X-Store-No", store);
            var staff = ok(mvc, selected, worker);
            assertFalse(staff.get("canProcure").asBoolean());
            assertEquals("VIEWER", staff.at("/roles/0").asText());
            assertEquals(ctx.at("/subscription/free"), staff.at("/subscription/free"));
            for (var privateKey :
                    List.of(
                            "valuationBasis",
                            "cash",
                            "credit",
                            "policyVersion",
                            "originNo",
                            "reason",
                            "expectedVersion"))
                assertFalse(staff.toString().contains("\"" + privateKey + "\""));
            for (var request :
                    List.of(
                            get(scope + "/context"),
                            get(scope + "/subscription").header("X-Store-No", "S"),
                            get(scope + "/entitlements")
                                    .header("X-Store-No", store)
                                    .param("entityNo", "M"),
                            get("/biz/stores/S/context").header("X-Store-No", "S")))
                mvc.perform(request.header("Authorization", worker))
                        .andExpect(status().isForbidden());
            f.jdbc.update(
                    """
INSERT INTO sys_business_end(tenant_no,created_at,updated_at,version,deleted,end_code,entitlement_code,
  name,scope,allowed_business_types,release_status,sale_enabled,bootstrap_required,enabled,client_id)
VALUES ('MAIN',NOW(),NOW(),1,0,'EXTRA','EXTRA','extra','STORE','["RETAIL"]','VERIFIED',1,0,1,'test-client')
""");
            f.jdbc.update(
                    "update sys_business_type set enabled=1 where business_type_code='RETAIL'");
            // A verified registration without publication or store evidence stays private.
            assertEquals(
                    1,
                    ok(mvc, get(scope + "/entitlements").header("X-Store-No", store), worker)
                            .get("items")
                            .size());
            assertEquals(
                    1,
                    ok(mvc, get(scope + "/context").header("X-Store-No", store), bearer)
                            .at("/entitlements/items")
                            .size());

            f.jdbc.update(
                    """
INSERT INTO sys_business_end(tenant_no,created_at,updated_at,version,deleted,end_code,entitlement_code,
  name,scope,allowed_business_types,release_status,sale_enabled,bootstrap_required,enabled,client_id)
VALUES ('MAIN',now(),now(),1,0,'OFFERED','OFFERED','published','STORE','["RETAIL"]','VERIFIED',1,0,1,'test-client')
""");
            for (var tier : List.of("PRO", "CHAIN")) {
                f.jdbc.update(
                        """
INSERT INTO sys_industry_plan_version(tenant_no,created_at,updated_at,version,deleted,plan_version_no,
  plan_no,revision,display_name,status,policy_version,published_at)
VALUES ('MAIN',now(),now(),1,0,?,?,2,'current offer','PUBLISHED','policy',456)
""",
                        tier + "-offer",
                        tier);
                for (String code : List.of("BASE", "OFFERED")) {
                    f.jdbc.update(
                            """
INSERT INTO sys_plan_version_end(tenant_no,created_at,updated_at,version,deleted,
  plan_version_no,end_code,entitlement_code,sort)
VALUES ('MAIN',now(),now(),1,0,?,?,?,?)
""",
                            tier + "-offer",
                            code.equals("BASE") ? "desk" : code,
                            code,
                            code.equals("BASE") ? 1 : 2);
                }
                f.jdbc.update(
                        "update sys_industry_plan set current_version_no=? where plan_no=?",
                        tier + "-offer",
                        tier);
            }
            var offered =
                    ok(mvc, get(scope + "/entitlements").header("X-Store-No", store), worker)
                            .get("items");
            assertEquals(2, offered.size());
            assertEquals("OFFERED", offered.at("/1/entitlementCode").asText());
            assertEquals("NOT_PURCHASED", offered.at("/1/commercialStatus").asText());
            assertEquals("CONTACT_OWNER", offered.at("/1/nextAction").asText());
            assertEquals(
                    "VIEW_SUBSCRIPTION",
                    ok(mvc, get(scope + "/entitlements").header("X-Store-No", store), bearer)
                            .at("/items/1/nextAction")
                            .asText());
            f.jdbc.update(
                    "update sys_business_type set enabled=0 where business_type_code='RETAIL'");
            assertEquals(
                    1,
                    ok(mvc, get(scope + "/entitlements").header("X-Store-No", store), worker)
                            .get("items")
                            .size());
            // Re-enable with the earlier package: OFFERED exists in history only.
            f.jdbc.update(
                    "update sys_industry_plan set current_version_no=concat(tier_code,'-v') where"
                        + " tier_code in ('PRO','CHAIN')");
            f.jdbc.update(
                    "update sys_business_type set enabled=1 where business_type_code='RETAIL'");
            assertEquals(
                    1,
                    ok(mvc, get(scope + "/entitlements").header("X-Store-No", store), worker)
                            .get("items")
                            .size());
            verifyCommittedGrantFromOlderSnapshot(f, mvc, scope, store, worker);
            // Seed an inconsistent partial schedule. The read must not return an invented
            // empty/free fallback.
            f.jdbc.update(
                    "update mch_end_schedule_event set after_snapshot='[]' where store_no=?",
                    store);
            mvc.perform(
                            get(scope + "/entitlements")
                                    .header("X-Store-No", store)
                                    .header("Authorization", worker))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.msg").value("CHECK_UNAVAILABLE"));
            f.jdbc.update("delete from mch_store_role where mch_account_no='worker'");
            mvc.perform(selected.header("Authorization", worker)).andExpect(status().isForbidden());
        }
    }

    private void verifyCommittedGrantFromOlderSnapshot(
            AccessMysqlFixture f, MockMvc mvc, String scope, String store, String worker) {
        var tx = new TransactionTemplate(f.manager);
        var ref = new StoreRef("MAIN", store);
        var uow = new JdbcCommerceUnitOfWork(f.ds, f.manager);
        var events = new JdbcCommerceEventPersistence(f.ds);
        CommerceIdGenerator ids = ns -> UUID.randomUUID().toString();
        var repo = new JdbcSubscriptionRepository(f.ds, ids);
        var schedule = new EndScheduleService(repo, uow, events, ids, f.clock);
        var service =
                new SubscriptionService(
                        repo, new JdbcCatalogRepository(f.ds), uow, schedule, events, ids, f.clock);
        tx.executeWithoutResult(
                txStatus -> {
                    assertEquals(
                            1,
                            f.jdbc.queryForObject(
                                    "select entitlement_version from mch_subscription_account where"
                                            + " store_no=?",
                                    Long.class,
                                    store));
                    try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                        pool.submit(
                                        () ->
                                                service.deliver(
                                                        ref,
                                                        "deliver",
                                                        List.of(
                                                                new SubscriptionService.Delivery(
                                                                        "fixture-grant",
                                                                        SubscriptionService.Origin
                                                                                .GRANT,
                                                                        null,
                                                                        "EXTRA",
                                                                        1,
                                                                        0,
                                                                        0,
                                                                        "CNY",
                                                                        true))))
                                .get(5, java.util.concurrent.TimeUnit.SECONDS);
                        pool.submit(
                                        () ->
                                                f.jdbc.update(
                                                        "update sys_business_end set"
                                                            + " enabled=0,release_status='RETIRED'"
                                                            + " where entitlement_code='EXTRA'"))
                                .get(5, java.util.concurrent.TimeUnit.SECONDS);
                        Thread.sleep(2); // the test grant lasts one millisecond
                        // The outer RR read is still old. The HTTP projection must use current
                        // account,
                        // parent and accepted grant reads, and return an expired commercial result
                        // on a live BTK.
                        assertEquals(
                                1,
                                f.jdbc.queryForObject(
                                        "select entitlement_version from mch_subscription_account"
                                                + " where store_no=?",
                                        Long.class,
                                        store));
                        var result =
                                ok(
                                        mvc,
                                        get(scope + "/context").header("X-Store-No", store),
                                        worker);
                        assertEquals(2, result.get("entitlementVersion").asLong());
                        assertEquals(2, result.at("/subscription/sources").size());
                        assertTrue(result.at("/subscription/sources/1/gift").asBoolean());
                        assertEquals(
                                "EXPIRED",
                                result.at("/entitlements/items/1/commercialStatus").asText());
                        assertEquals(
                                "CONTACT_OWNER",
                                result.at("/entitlements/items/1/nextAction").asText());
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
        // Represent a frozen source using an accepted empty schedule; raw stale rows alone must
        // never be reinterpreted as a purchasable missing entitlement.
        tx.executeWithoutResult(
                status -> {
                    var account = repo.lock(ref);
                    var before = repo.segments(ref, "EXTRA");
                    repo.revise(
                            ref,
                            "EXTRA",
                            "fixture-freeze",
                            f.clock.millis(),
                            3,
                            before,
                            List.of(),
                            List.of(),
                            "fixture");
                    f.jdbc.update(
                            "update mch_store_end_grant set state='FROZEN' where store_no=? and"
                                    + " entitlement_code='EXTRA'",
                            store);
                    f.jdbc.update(
                            "update mch_subscription_period set state='FROZEN' where store_no=? and"
                                    + " period_no=?",
                            store,
                            before.getFirst().sourceNo());
                    repo.updateVersion(account, 3, "fixture");
                });
        try {
            var frozen = ok(mvc, get(scope + "/entitlements").header("X-Store-No", store), worker);
            assertEquals("REVOKED", frozen.at("/items/1/commercialStatus").asText());
            assertEquals("CONTACT_ADMIN", frozen.at("/items/1/nextAction").asText());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void publicCatalogIsPublishedOnlyAndLegacyHasNoSubscription() throws Exception {
        try (var f = new AccessMysqlFixture();
                var web = new AnnotationConfigWebApplicationContext()) {
            new CommerceMigrationRunner(f.ds).migrate();
            web.register(
                    ApiResponseWrapper.class,
                    ai.neargo.shop.portal.biz.BizFoundationController.class);
            var mvc = CommerceAccessSecurityFlowTest.merchantMvc(f, web, true);
            var bearer =
                    "Bearer "
                            + f.store.issue(
                                    TokenStore.SessionData.of(
                                            LoginUser.merchantByUser("U", "owner")));
            mvc.perform(get("/biz/business-types").header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
            CommerceStoreCreationFlowTest.seedPublishedCatalog(f.jdbc);
            for (var table :
                    List.of(
                            "sys_business_type",
                            "sys_business_end",
                            "sys_industry_plan",
                            "sys_industry_plan_version",
                            "sys_plan_version_end"))
                f.jdbc.update("update " + table + " set tenant_no='OTHER'");
            mvc.perform(get("/biz/business-types").header("Authorization", bearer))
                    .andExpect(jsonPath("$.data.length()").value(0));
            CommerceStoreCreationFlowTest.seedPublishedCatalog(f.jdbc);
            mvc.perform(
                            get("/biz/business-types")
                                    .header("Authorization", bearer)
                                    .param("entityNo", "other"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/biz/business-types/RETAIL/plans").header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.plans.length()").value(3))
                    .andExpect(jsonPath("$.data.plans[0].tier").value("FREE"))
                    .andExpect(jsonPath("$.data.plans[1].purchaseAvailable").value(false))
                    .andExpect(jsonPath("$.data.plans[1].priceOptions.length()").value(0))
                    .andExpect(jsonPath("$.data.plans[2].tier").value("CHAIN"));
            f.jdbc.update(
                    "update mch_store set commerce_model='LEGACY',business_type_code=null where"
                            + " store_no='S'");
            mvc.perform(
                            get("/biz/stores/S/subscription")
                                    .header("Authorization", bearer)
                                    .header("X-Store-No", "S"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.commerceModel").value("LEGACY"))
                    .andExpect(jsonPath("$.data.free").doesNotExist());
            mvc.perform(get("/biz/stores/S/subscription").header("Authorization", bearer))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void singleEndUsesItsExistingTimeAndProofWireTypes() throws Exception {
        try (var f = new AccessMysqlFixture();
                var web = new AnnotationConfigWebApplicationContext()) {
            new CommerceMigrationRunner(f.ds).migrate();
            CommerceAccessSecurityFlowTest.seedSubscriptions(
                    f,
                    new StoreRef("MAIN", "S"),
                    new JdbcSubscriptionRepository(f.ds, ns -> UUID.randomUUID().toString()),
                    new TransactionTemplate(f.manager));
            web.register(ApiResponseWrapper.class);
            var mvc = CommerceAccessSecurityFlowTest.merchantMvc(f, web, true);
            var bearer =
                    "Bearer "
                            + f.store.issue(
                                    TokenStore.SessionData.of(
                                            LoginUser.merchantByUser("U", "owner")));
            var result =
                    ok(
                            mvc,
                            get("/biz/stores/S/end-access")
                                    .header("X-Store-No", "S")
                                    .param("endCode", "BIZ_APP")
                                    .param("clientId", "biz-app"),
                            bearer);
            assertEquals(FoundationHttpExamples.example("endAccess").at("/response/data"), result);
            assertEquals("ALLOWED", result.get("status").asText());
            var proof =
                    ok(
                            mvc,
                            post("/biz/stores/S/end-sessions")
                                    .header("X-Store-No", "S")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"endCode\":\"BIZ_APP\",\"clientId\":\"biz-app\"}"),
                            bearer);
            assertTrue(proof.get("expiresAt").isString());
            assertFalse(proof.get("value").asText().isBlank());
        }
    }
}
