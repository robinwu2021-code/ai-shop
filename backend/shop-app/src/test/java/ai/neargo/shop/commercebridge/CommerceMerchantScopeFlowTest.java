package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.subscription.AccessSegment;
import ai.neargo.commerce.core.spi.Period;
import ai.neargo.commerce.persistence.*;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.member.dto.MemberVOs.MemberStatsVO;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.member.service.MemberAudienceService;
import ai.neargo.shop.member.service.MemberSegmentService;
import ai.neargo.shop.merchant.dto.MerchantAccountVO;
import ai.neargo.shop.merchant.service.*;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.spi.user.MerchantQueryPort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.util.*;

@EnabledIfEnvironmentVariable(
        named = "COMMERCE_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
class CommerceMerchantScopeFlowTest {
    private static String bearer(AccessMysqlFixture f) {
        return "Bearer "
                + f.store.issue(
                        TokenStore.SessionData.of(LoginUser.merchantByUser("U", "merchant")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void merchantWideBodyQueryAndDefaultPreviewCannotTargetUnassignedNewSibling(boolean enabled)
            throws Exception {
        try (var f = new AccessMysqlFixture();
                var web = new AnnotationConfigWebApplicationContext()) {
            f.jdbc.update("UPDATE mch_store SET commerce_model=NULL");
            f.jdbc.update(
                    "INSERT INTO mch_store(entity_no,store_no,created_at,updated_at) VALUES"
                        + " ('M','L',NOW(),NOW())");
            f.jdbc.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no='MA'");
            f.jdbc.update(
                    "INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                        + " VALUES ('MA','L','STAFF_L',NOW(),NOW())");
            f.jdbc.update(
                    """
                    INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at)
                    VALUES ('M','STAFF_L','staff','["biz:store","biz:customer"]',NOW(),NOW())
                    """);
            f.jdbc.update(
                    "INSERT INTO cmt_community(community_no,name,created_at,updated_at) VALUES"
                        + " ('C','Community',NOW(),NOW())");
            var mvc = CommerceAccessSecurityFlowTest.merchantMvc(f, web, enabled);
            var bearer = bearer(f);
            var merchant = web.getBean(MerchantQueryPort.class);
            when(merchant.storeNos("M")).thenReturn(List.of("S", "L"));
            var members = web.getBean(MemberService.class);
            when(members.stats("M", "S")).thenReturn(new MemberStatsVO(7, 0, 0, 0, 0, 0, 0, null));
            var goods = web.getBean(MerchantGoodsService.class);
            var stats =
                    get("/biz/members/stats")
                            .param("storeNo", "S")
                            .header("Authorization", bearer)
                            .header("X-Store-No", "L");
            var preview =
                    get("/biz/stores/default/fulfillment/EXPRESS/impact")
                            .header("Authorization", bearer)
                            .header("X-Store-No", "L");
            f.jdbc.update("UPDATE mch_store SET commerce_model='LEGACY' WHERE store_no='S'");
            // Same real controllers, employee and body/query target while the whole merchant is
            // LEGACY.
            // 统一项目线（ab665c74e）起，门店级写入必须是本人被授权的店：只在 L 的员工不能借 body 指向 S，
            // 旧模型下也一样拒绝（10403），不再只靠商城新模型来挡。
            mvc.perform(pickup(bearer, "legacy-point"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(10403));
            mvc.perform(stats).andExpect(jsonPath("$.newCount").value(7));
            mvc.perform(preview).andExpect(status().isOk());
            verify(goods).onlyFulfillment("M", null, "EXPRESS");
            assertEquals(0, pickupCount(f));
            clearInvocations(merchant, members, goods);

            // A different instance introduces the new model after this instance has started.
            f.jdbc.update("UPDATE mch_store SET commerce_model='STORE_V1' WHERE store_no='S'");
            int rejection = enabled ? 403 : 503;
            assertAll(
                    () ->
                            assertMemberDenied(mvc, pickup(bearer, "mixed-point"), enabled),
                    () -> assertMemberDenied(mvc, stats, enabled),
                    () -> mvc.perform(preview).andExpect(status().is(rejection)),
                    () ->
                            mvc.perform(
                                            get("/biz/store/S/pay-setting")
                                                    .header("Authorization", bearer)
                                                    .header("X-Store-No", "S"))
                                    .andExpect(status().is(rejection)),
                    () -> assertEquals(0, pickupCount(f)),
                    () -> {
                        // Registered pickup handlers perform their own target authorization.
                        if (enabled) verify(merchant).storeNos("M");
                        verifyNoMoreInteractions(merchant);
                        verifyNoInteractions(members, goods);
                    });
            // Unregistered handlers still cannot fall through after their selected member row
            // changes. Registered pickup handlers instead authorize their actual target above.
            var user = LoginUser.merchantByUser("U", "merchant");
            var scope = web.getBean(BizIdentityResolver.class).resolveStrict(user, "L");
            assertEquals("M", scope.merchantNo());
            f.jdbc.update("UPDATE mch_account SET status='DISABLED' WHERE mch_account_no='MA'");
            f.jdbc.update("UPDATE mch_store SET status='SUSPENDED' WHERE store_no='S'");
            var req =
                    new org.springframework.mock.web.MockHttpServletRequest(
                            "POST", "/biz/not-registered");
            req.addHeader("X-Store-No", "L");
            for (String model : List.of("STORE_V1", "UNKNOWN", "legacy")) {
                f.jdbc.update("UPDATE mch_store SET commerce_model=? WHERE store_no='S'", model);
                var response = new org.springframework.mock.web.MockHttpServletResponse();
                assertFalse(
                        web.getBean(BizContextFilter.Admission.class)
                                .admit(user, scope, req, response),
                        model);
                assertEquals(rejection, response.getStatus());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void legacyOwnerCannotReachNewMerchantThroughSameNumberStaffAccount(boolean enabled)
            throws Exception {
        try (var f = new AccessMysqlFixture();
                var web = new AnnotationConfigWebApplicationContext()) {
            f.jdbc.update("UPDATE mch_store SET commerce_model=NULL");
            f.jdbc.update("UPDATE mch_account SET is_primary=0");
            f.jdbc.update(
                    "INSERT INTO mch_entity(entity_no,name,created_at,updated_at) VALUES"
                        + " ('OTHER','private-other',NOW(),NOW())");
            f.jdbc.update(
                    "INSERT INTO"
                        + " mch_account(mch_account_no,user_no,entity_no,is_owner,is_primary,created_at,updated_at)"
                        + " VALUES ('U','ELSE','OTHER',1,1,NOW(),NOW())");
            f.jdbc.update(
                    "INSERT INTO mch_store(entity_no,store_no,created_at,updated_at) VALUES"
                        + " ('OTHER','N',NOW(),NOW())");
            var mvc = CommerceAccessSecurityFlowTest.merchantMvc(f, web, enabled);
            var bearer = bearer(f);
            var own = get("/biz/entity/M").header("Authorization", bearer);
            var other = get("/biz/entity/OTHER").header("Authorization", bearer);
            var preview =
                    get("/biz/stores/default/fulfillment/EXPRESS/impact")
                            .header("Authorization", bearer);
            var entities = web.getBean(MerchantEntityService.class);
            // The original trusted, bare-number in-process contract has no signed subject kind.
            assertTrue(SecurityUtils.currentUser().isEmpty());
            assertEquals(2, entities.myEntities("U").size());
            when(web.getBean(MerchantService.class).account("M"))
                    .thenReturn(
                            new MerchantAccountVO(
                                    "M", "own", "", "PERSONAL", "SMALL", "ACTIVE", null, null));
            int forbidden = ai.neargo.shop.common.ErrorCode.FORBIDDEN.code();
            assertAll(
                    () -> mvc.perform(own).andExpect(jsonPath("$.entity.entityNo").value("M")),
                    () ->
                            mvc.perform(other)
                                    .andExpect(jsonPath("$.code").value(forbidden))
                                    .andExpect(jsonPath("$.entity").doesNotExist()),
                    () ->
                            mvc.perform(get("/biz/entities").header("Authorization", bearer))
                                    .andExpect(jsonPath("$.length()").value(1))
                                    .andExpect(jsonPath("$[0].entityNo").value("M")),
                    () ->
                            mvc.perform(
                                            get("/biz/merchant/payment")
                                                    .param("entityNo", "OTHER")
                                                    .header("Authorization", bearer))
                                    .andExpect(jsonPath("$.code").value(forbidden)),
                    () -> mvc.perform(preview).andExpect(status().isOk()),
                    () -> {
                        // Registered entity reads use the signed subject namespace. A colliding
                        // account in another namespace must neither expose OTHER nor block M.
                        f.jdbc.update(
                                "UPDATE mch_store SET"
                                    + " commerce_model='STORE_V1',business_type_code='RETAIL' WHERE"
                                    + " store_no='N'");
                        int denial = enabled ? 403 : 503;
                        assertAll(
                                () ->
                                        assertMemberDenied(mvc, other, enabled),
                                () -> {
                                    if (enabled) mvc.perform(own)
                                            .andExpect(status().isOk())
                                            .andExpect(jsonPath("$.entity.entityNo").value("M"));
                                    else mvc.perform(own).andExpect(status().is(denial));
                                },
                                () -> mvc.perform(preview).andExpect(status().is(denial)),
                                () ->
                                        mvc.perform(
                                                        get("/biz/stores/mine")
                                                                .header("Authorization", bearer))
                                                .andExpect(jsonPath("$.length()").value(1))
                                                .andExpect(
                                                        jsonPath("$[0].entity.entityNo")
                                                                .value("M")),
                                () ->
                                        mvc.perform(
                                                        get("/biz/merchant/profile")
                                                                .header("Authorization", bearer))
                                                .andExpect(jsonPath("$.merchantNo").value("M")),
                                () ->
                                        mvc.perform(
                                                        get("/biz/merchant/profile")
                                                                .header("X-Store-No", "S")
                                                                .header("Authorization", bearer))
                                                .andExpect(jsonPath("$.merchantNo").value("M")),
                                () ->
                                        mvc.perform(
                                                        get("/biz/merchant/profile")
                                                                .header("X-Store-No", "N")
                                                                .header("Authorization", bearer))
                                                .andExpect(status().is(denial)));
                    });
        }
    }

    @Test
    void mixedMemberQueriesRequireTargetCustomerPermissionAndCheckEntityAggregates()
            throws Exception {
        try (var f = new AccessMysqlFixture();
                var web = new AnnotationConfigWebApplicationContext()) {
            f.jdbc.update("UPDATE mch_store SET commerce_model='LEGACY'");
            f.jdbc.update("INSERT INTO mch_store(entity_no,store_no,created_at,updated_at)"
                    + " VALUES ('M','L',NOW(),NOW())");
            f.jdbc.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no='MA'");
            f.jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                    + " VALUES ('MA','L','CUSTOMER_L',NOW(),NOW())");
            f.jdbc.update("INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at)"
                    + " VALUES ('M','CUSTOMER_L','customer','[\"biz:customer\"]',NOW(),NOW()),"
                    + " ('M','REGISTER_S','register','[\"biz:store\"]',NOW(),NOW())");
            var mvc = CommerceAccessSecurityFlowTest.merchantMvc(f, web, true);
            var token = bearer(f);
            var members = web.getBean(MemberService.class);
            when(members.stats("M", "S")).thenReturn(new MemberStatsVO(7, 0, 0, 0, 0, 0, 0, null));
            when(members.stats("M", null)).thenReturn(new MemberStatsVO(9, 0, 0, 0, 0, 0, 0, null));
            // Whole-LEGACY entity queries retain their original aggregate and store filters.
            mvc.perform(memberStats(token, "S")).andExpect(jsonPath("$.newCount").value(7));
            mvc.perform(memberStats(token, null)).andExpect(jsonPath("$.newCount").value(9));
            clearInvocations(members);
            f.jdbc.update("UPDATE mch_store SET commerce_model='STORE_V1' WHERE store_no='S'");
            assertMemberDenied(mvc, memberStats(token, "S"), true);
            assertMemberDenied(mvc, memberStats(token, null), true);
            assertMemberDenied(mvc, memberRead(token, "/biz/members").param("storeNo", "S"), true);
            assertMemberDenied(mvc, memberRead(token, "/biz/members"), true);
            // Body query entry points normalize storeNo to scopeStoreNo; authorize that target.
            for (String path : List.of("/biz/members/tags/batch", "/biz/member-segments",
                    "/biz/member-segments/preview")) {
                for (String scope : List.of("\"S\"", "null")) {
                    assertMemberDenied(mvc, post(path).header("Authorization", token)
                            .header("X-Store-No", "L").contentType("application/json")
                            .content("{\"name\":\"segment\",\"tagNo\":\"T\",\"scopeStoreNo\":" + scope
                                    + ",\"rule\":{\"storeNo\":\"L\"}}"), true);
                }
            }
            verifyNoInteractions(members, web.getBean(MemberAudienceService.class),
                    web.getBean(MemberSegmentService.class));
            // A real membership in S is insufficient when only L grants CUSTOMER.
            f.jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                    + " VALUES ('MA','S','REGISTER_S',NOW(),NOW())");
            assertMemberDenied(mvc, memberStats(token, "S"), true);
            assertMemberDenied(mvc, memberStats(token, null), true);
            verifyNoInteractions(members);
            f.jdbc.update("UPDATE mch_role SET perms='[\"biz:customer\"]' WHERE role_code='REGISTER_S'");
            mvc.perform(memberStats(token, "S")).andExpect(jsonPath("$.newCount").value(7));
            mvc.perform(memberStats(token, null)).andExpect(jsonPath("$.newCount").value(9));
            // Owner access retains the entity aggregate even without a staff CUSTOMER grant in S.
            f.jdbc.update("UPDATE mch_role SET perms='[]' WHERE role_code='REGISTER_S'");
            f.jdbc.update("UPDATE mch_account SET is_owner=1 WHERE mch_account_no='MA'");
            mvc.perform(memberStats(token, null)).andExpect(jsonPath("$.newCount").value(9));
            clearInvocations(members);
            // Neither a target outside this entity nor unrecognized model metadata proves LEGACY.
            f.jdbc.update("INSERT INTO mch_entity(entity_no,name,created_at,updated_at)"
                    + " VALUES ('OTHER','other',NOW(),NOW())");
            f.jdbc.update("INSERT INTO mch_store(entity_no,store_no,commerce_model,created_at,updated_at)"
                    + " VALUES ('OTHER','N','STORE_V1',NOW(),NOW())");
            mvc.perform(memberStats(token, "N")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.newCount").doesNotExist());
            f.jdbc.update("UPDATE mch_store SET commerce_model='UNKNOWN' WHERE store_no='S'");
            for (String target : Arrays.asList("S", null)) {
                mvc.perform(memberStats(token, target)).andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.newCount").doesNotExist());
            }
            verifyNoInteractions(members);
        }
    }

    private static MockHttpServletRequestBuilder memberRead(
            String token, String path) {
        return get(path).header("Authorization", token).header("X-Store-No", "L");
    }

    private static RequestBuilder memberStats(String token, String storeNo) {
        var request = memberRead(token, "/biz/members/stats");
        return storeNo == null ? request : request.param("storeNo", storeNo);
    }

    private static void assertMemberDenied(MockMvc mvc, RequestBuilder request, boolean enabled)
            throws Exception {
        var result = mvc.perform(request);
        if (enabled) result.andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10403));
        else result.andExpect(status().isServiceUnavailable());
        result.andExpect(jsonPath("$.newCount").doesNotExist())
                .andExpect(jsonPath("$.entity").doesNotExist())
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist());
    }

    private static int pickupCount(AccessMysqlFixture f) {
        return f.jdbc.queryForObject(
                "SELECT COUNT(*) FROM cmt_pickup_point WHERE owner_ref='S'", Integer.class);
    }

    private static RequestBuilder pickup(String bearer, String name) {
        return post("/biz/pickup-points")
                .header("Authorization", bearer)
                .header("X-Store-No", "L")
                .contentType("application/json")
                .content(
                        """
{"storeNo":"S","name":"%s","address":"road","latE6":123,"lngE6":456,"communityNo":"C"}
"""
                                .formatted(name));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void singleLicensePickerAndColdProfileRecoverOnlyWithinAuthenticatedScope(boolean enabled)
            throws Exception {
        try (var f = new AccessMysqlFixture();
                var web = new AnnotationConfigWebApplicationContext()) {
            f.jdbc.update("UPDATE mch_store SET commerce_model=NULL");
            var mvc = CommerceAccessSecurityFlowTest.merchantMvc(f, web, enabled);
            var bearer = bearer(f);
            f.jdbc.update("UPDATE mch_store SET commerce_model='STORE_V1'");
            // A different staff account's account number collides with the signed USR identifier.
            f.jdbc.update("UPDATE mch_account SET is_primary=0 WHERE mch_account_no='MA'");
            f.jdbc.update(
                    "INSERT INTO mch_entity(entity_no,name,created_at,updated_at) VALUES"
                        + " ('OTHER','other',NOW(),NOW())");
            f.jdbc.update(
                    "INSERT INTO"
                        + " mch_account(mch_account_no,user_no,entity_no,is_owner,is_primary,created_at,updated_at)"
                        + " VALUES ('U','ELSE','OTHER',1,1,NOW(),NOW())");
            var merchants = web.getBean(MerchantService.class);
            when(merchants.account("M"))
                    .thenReturn(
                            new MerchantAccountVO(
                                    "M",
                                    "merchant",
                                    "",
                                    "PERSONAL",
                                    "SMALL",
                                    "ACTIVE",
                                    null,
                                    null));
            f.jdbc.update(
                    "UPDATE mch_account SET display_name='owner-name',login_phone='owner-phone'"
                        + " WHERE mch_account_no='MA'");
            f.jdbc.update(
                    "UPDATE mch_account SET display_name='staff-name',login_phone='staff-phone'"
                        + " WHERE mch_account_no='U'");
            String staffBearer =
                    "Bearer "
                            + f.store.issue(
                                    TokenStore.SessionData.of(LoginUser.merchant("U", "staff")));
            when(merchants.account("OTHER"))
                    .thenReturn(
                            new MerchantAccountVO(
                                    "OTHER",
                                    "other",
                                    "",
                                    "PERSONAL",
                                    "SMALL",
                                    "ACTIVE",
                                    null,
                                    null));
            String picker = "/biz/store/list", profile = "/biz/merchant/profile";
            assertAll(
                    () ->
                            mvc.perform(
                                            get(profile)
                                                    .header("Authorization", bearer)
                                                    .param("entityNo", "OTHER"))
                                    .andExpect(status().isOk())
                                    .andExpect(jsonPath("$.merchantNo").value("M"))
                                    .andExpect(jsonPath("$.displayName").value("owner-name"))
                                    .andExpect(jsonPath("$.phone").value("owner-phone")),
                    () -> {
                        var userProfiles =
                                web.getBean(ai.neargo.shop.user.service.UserService.class);
                        var ops = web.getBean(ai.neargo.shop.platform.OpsService.class);
                        clearInvocations(userProfiles, ops);
                        mvc.perform(get(profile).header("Authorization", staffBearer))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.merchantNo").value("OTHER"))
                                .andExpect(jsonPath("$.displayName").value("staff-name"))
                                .andExpect(jsonPath("$.phone").value("staff-phone"));
                        verifyNoInteractions(userProfiles, ops);
                    },
                    () -> {
                        mvc.perform(get(picker).header("Authorization", bearer))
                                .andExpect(status().isOk());
                        verify(web.getBean(StoreAdminService.class)).list("M");
                    },
                    () ->
                            mvc.perform(get("/biz/stores/mine").header("Authorization", bearer))
                                    .andExpect(status().isOk())
                                    .andExpect(jsonPath("$.length()").value(1))
                                    .andExpect(jsonPath("$[0].entity.entityNo").value("M")),
                    () ->
                            mvc.perform(
                                            get("/biz/stores/mine")
                                                    .header("Authorization", staffBearer))
                                    .andExpect(status().isOk())
                                    .andExpect(jsonPath("$.length()").value(1))
                                    .andExpect(jsonPath("$[0].entity.entityNo").value("OTHER")),
                    () -> {
                        for (String path : List.of(picker, profile)) {
                            mvc.perform(
                                            get(path)
                                                    .header("Authorization", bearer)
                                                    .header("X-Store-No", "S"))
                                    .andExpect(status().is(enabled ? 200 : 503));
                            mvc.perform(
                                            get(path)
                                                    .header("Authorization", bearer)
                                                    .header("X-Store-No", "missing"))
                                    .andExpect(status().is(enabled ? 403 : 503));
                        }
                    },
                    () -> {
                        f.jdbc.update("UPDATE mch_store SET status='SUSPENDED'");
                        mvc.perform(
                                        get(picker)
                                                .header("Authorization", bearer)
                                                .header("X-Store-No", "S"))
                                .andExpect(status().is(enabled ? 403 : 503));
                        mvc.perform(
                                        get(profile)
                                                .header("Authorization", bearer)
                                                .header("X-Store-No", "S"))
                                .andExpect(status().is(enabled ? 403 : 503));
                    });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPIRED", "NOT_PURCHASED"})
    void commercialResponsesDistinguishStoreBuyerFromEntryOnlyEmployee(String status)
            throws Exception {
        try (var f = new AccessMysqlFixture();
                var web = new AnnotationConfigWebApplicationContext()) {
            new CommerceMigrationRunner(f.ds).migrate();
            seedCommercialStatus(f, status);
            // The same USR is always an owner on another license. That duty cannot travel to S.
            f.jdbc.update(
                    "INSERT INTO mch_entity(entity_no,name,created_at,updated_at) VALUES"
                        + " ('M2','second',NOW(),NOW())");
            f.jdbc.update(
                    "INSERT INTO"
                        + " mch_account(mch_account_no,user_no,entity_no,is_owner,created_at,updated_at)"
                        + " VALUES ('MA2','U','M2',1,NOW(),NOW())");
            var mvc = CommerceAccessSecurityFlowTest.merchantMvc(f, web, true);
            var bearer = bearer(f);
            f.jdbc.update(
                    "INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                        + " VALUES ('MA','S','ENTRY',NOW(),NOW())");
            f.jdbc.update(
                    """
                    INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at)
                    VALUES ('M','ENTRY','entry','["biz:store"]',NOW(),NOW())
                    """);
            var merchants = web.getBean(MerchantService.class);
            when(merchants.account("M"))
                    .thenReturn(
                            new MerchantAccountVO(
                                    "M",
                                    "merchant",
                                    "",
                                    "PERSONAL",
                                    "SMALL",
                                    "ACTIVE",
                                    null,
                                    null));
            for (boolean buyer : List.of(true, false)) {
                f.jdbc.update("UPDATE mch_account SET is_owner=? WHERE mch_account_no='MA'", buyer);
                String action = buyer ? "VIEW_SUBSCRIPTION" : "CONTACT_OWNER";
                assertAll(
                        () ->
                                mvc.perform(
                                                get("/biz/stores/S/end-access")
                                                        .header("Authorization", bearer)
                                                        .header("X-Store-No", "S")
                                                        .param("endCode", "BIZ_APP")
                                                        .param("clientId", "biz-app")
                                                        .param("canProcure", "true"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.status").value(status))
                                        .andExpect(jsonPath("$.nextAction").value(action)),
                        () ->
                                commercialDenial(
                                        mvc,
                                        get("/biz/store/S/pay-setting")
                                                .header("Authorization", bearer)
                                                .header("X-Store-No", "S")
                                                .header("X-Client-Id", "biz-app"),
                                        status,
                                        action),
                        () ->
                                commercialDenial(
                                        mvc,
                                        post("/biz/stores/S/end-sessions")
                                                .header("Authorization", bearer)
                                                .header("X-Store-No", "S")
                                                .contentType("application/json")
                                                .content(
                                                        "{\"endCode\":\"BIZ_APP\",\"clientId\":\"biz-app\",\"canProcure\":true}"),
                                        status,
                                        action));
                if (!buyer)
                    mvc.perform(
                                    post("/biz/stores/S/mall/orders")
                                            .header("Authorization", bearer)
                                            .header("X-Store-No", "S"))
                            .andExpect(status().isForbidden())
                            .andExpect(jsonPath("$.msg").value("FORBIDDEN"));
            }
            mvc.perform(
                            get("/biz/merchant/profile")
                                    .header("Authorization", bearer)
                                    .header("X-Store-No", "S"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.merchantNo").value("M"));
        }
    }

    private static void seedCommercialStatus(AccessMysqlFixture f, String status) {
        var ref = new StoreRef("MAIN", "S");
        var repo = new JdbcSubscriptionRepository(f.ds, ns -> UUID.randomUUID().toString());
        String code = "END_BIZ_APP";
        f.jdbc.update(
                """
INSERT INTO sys_business_end(tenant_no,created_at,updated_at,version,deleted,end_code,entitlement_code,
name,scope,allowed_business_types,release_status,sale_enabled,bootstrap_required,enabled,client_id)
VALUES ('MAIN',NOW(),NOW(),1,0,'BIZ_APP','END_BIZ_APP','BIZ_APP','STORE','["RETAIL"]','VERIFIED',0,0,1,'biz-app')
""");
        new TransactionTemplate(f.manager)
                .executeWithoutResult(
                        t -> {
                            repo.ensureAccount(
                                    new StoreSnapshot(
                                            ref,
                                            "M",
                                            "RETAIL",
                                            StoreStatus.ACTIVE,
                                            CommerceModel.STORE_V1),
                                    "fixture");
                            if (status.equals("EXPIRED")) {
                                var account = repo.lock(ref);
                                var end = f.clock.instant();
                                var start = end.minusSeconds(60);
                                var period =
                                        new Period(
                                                "paid",
                                                "order",
                                                "ORDER",
                                                "plan",
                                                "v",
                                                "PAID",
                                                start.toEpochMilli(),
                                                start.toEpochMilli(),
                                                60_000L,
                                                Map.of(code, "BIZ_APP"),
                                                100,
                                                0,
                                                "CNY",
                                                "policy",
                                                false);
                                repo.insert(account, period, 1, "fixture");
                                repo.revise(
                                        ref,
                                        code,
                                        "paid",
                                        start.toEpochMilli(),
                                        1,
                                        List.of(),
                                        List.of(new AccessSegment("paid", code, start, end)),
                                        List.of(period),
                                        "fixture");
                                repo.updateVersion(account, 1, "fixture");
                            }
                        });
    }

    private static void commercialDenial(
            MockMvc mvc, RequestBuilder request, String status, String action) throws Exception {
        mvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.msg").value(status))
                .andExpect(jsonPath("$.data.nextAction").value(action));
    }
}
