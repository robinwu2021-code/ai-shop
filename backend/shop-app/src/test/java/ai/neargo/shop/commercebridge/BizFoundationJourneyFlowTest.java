package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.spi.user.UserProvisionPort;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.*;

@SpringBootTest(
        properties = {
            "shop.auth.token-store=db",
            "commerce.enabled=true",
            "commerce.access.active-key-id=test",
            "commerce.access.keys.test=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "spring.autoconfigure.exclude=ai.neargo.commerce.starter.CommercePersistenceAutoConfiguration,"
                + "ai.neargo.commerce.starter.CommerceStoreRelinkAutoConfiguration,"
                + "ai.neargo.common.security.SecurityAutoConfiguration,ai.neargo.common.security.SessionStoreAutoConfiguration,"
                + "ai.neargo.common.data.DataAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:bf_login;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
class BizFoundationJourneyFlowTest {
    // H2 exercises the real host services. Native MySQL exercises production commerce
    // migrations and FREE initialization in CommerceStoreCreationFlowTest.
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    // Native store relinking requires the real migration owner and is outside this H2 UOW fixture.
    static class TestPersistence {
        @org.springframework.context.annotation.Bean
        ai.neargo.commerce.core.spi.CommerceUnitOfWork uow(
                javax.sql.DataSource ds,
                org.springframework.transaction.PlatformTransactionManager manager) {
            return new ai.neargo.commerce.persistence.JdbcCommerceUnitOfWork(ds, manager);
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenStore tokens;
    @Autowired OtpStore otp;
    @Autowired UserProvisionPort users;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    JsonNode login(String phone) throws Exception {
        mvc.perform(
                post("/biz/auth/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("phone", phone))));
        var response =
                mvc.perform(
                                post("/biz/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        java.util.Map.of(
                                                                "grantType",
                                                                "PHONE_OTP",
                                                                "principal",
                                                                phone,
                                                                "agreed",
                                                                true,
                                                                "credential",
                                                                otp.peek(phone).orElseThrow()))))
                        .andReturn()
                        .getResponse();
        var envelope = json.readTree(response.getContentAsString());
        assertEquals(0, envelope.path("code").asInt(), envelope.toString());
        return envelope.path("data");
    }

    JsonNode cold(String token) throws Exception {
        return json.readTree(
                        mvc.perform(
                                        get("/biz/merchant/profile")
                                                .header("Authorization", "Bearer " + token))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .path("data");
    }

    int live(String pool, String no) {
        return jdbc.queryForObject(
                "select count(*) from " + pool + "_session where revoked_at is null and user_no=?",
                Integer.class,
                no);
    }

    @Test
    void firstOtpRegistersPersonalAccountAndLogoutRevokesOnlyPresentedMerchantSession()
            throws Exception {
        String phone = "15965010101";
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from usr_account where phone=?", Integer.class, phone));
        var login = login(phone);
        String token = login.path("token").asText();
        assertTrue(token.startsWith("btk_"));
        var user = tokens.get(token).orElseThrow().user();
        assertEquals(ai.neargo.auth.store.SubjectKind.USR, user.subjectKind());
        assertEquals(
                user.userNo(),
                jdbc.queryForObject(
                        "select user_no from usr_account where phone=?", String.class, phone));
        assertEquals(0, live("usr", user.userNo()));
        assertEquals(login.path("merchant"), cold(token));
        String another = tokens.issue(TokenStore.SessionData.of(user));
        mvc.perform(
                post("/biz/auth/logout")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-No", "foreign"));
        assertTrue(tokens.get(another).isPresent());
        assertTrue(tokens.get(token).isEmpty(), "presented BTK must be revoked");
    }

    @Test
    void staffOtpIgnoresCollidingOwnerAndLeavesOnlyReturnedStaffSession() throws Exception {
        String phone = "15965010102";
        String personal = users.ensureUserByPhone(phone);
        jdbc.update(
                "insert into mch_entity(entity_no,name,status,created_at,updated_at) values"
                        + " ('BF_M','Staff shop','ACTIVE',now(),now())");
        jdbc.update(
                "insert into mch_store(entity_no,store_no,is_default,created_at,updated_at) values"
                        + " ('BF_M','BF_S',1,now(),now())");
        // The signed USR number is also the MCH number of a different account.
        jdbc.update(
                "insert into"
                    + " mch_account(mch_account_no,entity_no,user_no,display_name,is_owner,created_at,updated_at)"
                    + " values (?,'BF_M','BF_other','Other owner',1,now(),now())",
                personal);
        jdbc.update(
                "insert into"
                    + " mch_account(mch_account_no,entity_no,login_phone,display_name,created_at,updated_at)"
                    + " values ('BF_staff','BF_M',?,'Staff name',now(),now())",
                phone);
        jdbc.update(
                "insert into mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                        + " values ('BF_staff','BF_S','STAFF',now(),now())");
        var login = login(phone);
        String token = login.path("token").asText();
        assertEquals("BF_staff", tokens.get(token).orElseThrow().user().userNo());
        assertEquals(login.path("merchant"), cold(token));
        assertEquals("Staff name", login.path("merchant").path("displayName").asText());
        assertEquals(0, live("usr", personal));
        assertEquals(0, live("mch", personal));
        assertEquals(1, live("mch", "BF_staff"));
        for (var req :
                java.util.List.of(
                        get("/biz/merchant/apply"),
                        post("/biz/merchant/agreement/accept"),
                        post("/biz/merchant/apply")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"),
                        post("/biz/merchant/quick-start")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"storeName\":\"forged\"}"),
                        post("/biz/auth/password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"forgedPassword\"}"))) {
            var result =
                    mvc.perform(req.header("Authorization", "Bearer " + token))
                            .andReturn()
                            .getResponse();
            assertEquals(
                    ai.neargo.shop.common.ErrorCode.FORBIDDEN.code(),
                    json.readTree(result.getContentAsString()).path("code").asInt());
        }
    }

    @Test
    void selectedPendingOwnerSubmitsLicenseAcceptsAgreementAndReadsPaymentStatus()
            throws Exception {
        var firstLogin = login("15965010103");
        FoundationHttpExamples.data("login", firstLogin);
        String token = firstLogin.path("token").asText();
        String bearer = "Bearer " + token;
        var user = tokens.get(token).orElseThrow().user();
        var profile =
                json.readTree(
                                mvc.perform(
                                                post("/biz/merchant/quick-start")
                                                        .header("Authorization", bearer)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                "{\"storeName\":\"Selected pending"
                                                                        + " shop\"}"))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("data");
        FoundationHttpExamples.data("quickStart", profile);
        String entity = profile.path("merchantNo").asText();
        String store = profile.path("currentStoreNo").asText();
        assertFalse(store.isBlank());
        jdbc.update(
                "update mch_store set commerce_model='STORE_V1', business_type_code='RETAIL' where"
                        + " store_no=?",
                store);
        jdbc.update(
                "insert into"
                    + " mch_account(mch_account_no,entity_no,login_phone,display_name,created_at,updated_at)"
                    + " values ('BF_secondJob','BF_job','15965010103','Other job',now(),now())");
        var loginOwner = login("15965010103");
        assertEquals(
                ai.neargo.auth.store.SubjectKind.USR,
                tokens.get(loginOwner.path("token").asText()).orElseThrow().user().subjectKind());
        assertEquals(profile.path("merchantNo"), loginOwner.path("merchant").path("merchantNo"));
        assertEquals(loginOwner.path("merchant"), cold(token));

        jdbc.update(
                "insert into mch_entity(entity_no,name,status,created_at,updated_at) values"
                        + " ('BF_other','Active sibling','ACTIVE',now(),now())");
        jdbc.update(
                "insert into"
                    + " mch_account(mch_account_no,entity_no,user_no,is_owner,created_at,updated_at)"
                    + " values ('BF_own2','BF_other',?,1,now(),now())",
                user.userNo());
        jdbc.update(
                "insert into mch_store(entity_no,store_no,is_default,created_at,updated_at) values"
                        + " ('BF_other','BF_otherS',1,now(),now())");
        String application =
                """
{"name":"License shop","subject":"ENTERPRISE","industry":"RETAIL","serviceScope":"COMMUNITY",
 "communityNos":["CM001"],"licenses":["example-license"],"contactPhone":"15965010103","contactName":"Owner"}
""";
        var submittedElsewhere =
                mvc.perform(
                                post("/biz/merchant/apply")
                                        .header("Authorization", bearer)
                                        .header("X-Store-No", "BF_otherS")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(application))
                        .andReturn()
                        .getResponse();
        assertNotEquals(
                0, json.readTree(submittedElsewhere.getContentAsString()).path("code").asInt());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from mch_entity_apply where user_no=?",
                        Integer.class,
                        user.userNo()));
        var examples = java.util.List.of("apply","agreement","application","channels").iterator();
        for (var req :
                java.util.List.of(
                        post("/biz/merchant/apply")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(application),
                        post("/biz/merchant/agreement/accept"),
                        get("/biz/merchant/apply"),
                        get("/biz/merchant/pay-channel"),
                        get("/biz/merchant/payment"),
                        get("/biz/qualifications"))) {
            var result =
                    mvc.perform(req.header("Authorization", bearer).header("X-Store-No", store))
                            .andReturn()
                            .getResponse();
            if (examples.hasNext())
                FoundationHttpExamples.data(examples.next(), json.readTree(result.getContentAsString()).get("data"));
            assertEquals(
                    0,
                    json.readTree(result.getContentAsString()).path("code").asInt(),
                    result.getContentAsString());
        }
        assertEquals(
                entity,
                jdbc.queryForObject(
                        "select entity_no from mch_entity_apply where user_no=? and agreed_at is"
                                + " not null",
                        String.class,
                        user.userNo()));
        String applyNo =
                jdbc.queryForObject(
                        "select apply_no from mch_entity_apply where user_no=?",
                        String.class,
                        user.userNo());
        String ops = ai.neargo.shop.support.TestLogin.admin(mvc, json);
        mvc.perform(
                        post("/ops/merchant/apply/" + applyNo + "/audit")
                                .header("Authorization", "Bearer " + ops)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"approved\":true}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.code")
                                .value(0));
        assertEquals(
                "ACTIVE",
                jdbc.queryForObject(
                        "select status from mch_entity where entity_no=?", String.class, entity));
        assertEquals(
                "AGGREGATED",
                jdbc.queryForObject(
                        "select funds_mode from mch_entity where entity_no=?",
                        String.class,
                        entity));
        // Simulate the platform assigning DIRECT. The B-side request has no field for this
        // decision.
        jdbc.update("update mch_entity set funds_mode='DIRECT' where entity_no=?", entity);
        mvc.perform(
                        post("/biz/merchant/payment")
                                .header("Authorization", bearer)
                                .header("X-Store-No", store)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
{"payChannel":"WECHAT","settleAccountType":"PERSONAL_BANK_CARD","settleAccount":"TEST00009999",
 "licenses":["example-license"],"contactName":"Test owner","contactPhone":"15965010103"}
"""))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.code")
                                .value(0))
                .andDo(result -> FoundationHttpExamples.data("payment",
                        json.readTree(result.getResponse().getContentAsString()).get("data")))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.data.submitted")
                                .value(true));
        assertEquals(
                "DIRECT",
                jdbc.queryForObject(
                        "select funds_mode from mch_entity where entity_no=?",
                        String.class,
                        entity));
    }

    @Test
    void foreignQualificationNumberCannotBeEditedEvenWhenSubmittedEntityMatches() throws Exception {
        String token = login("15965010104").path("token").asText();
        String bearer = "Bearer " + token;
        var profile =
                json.readTree(
                                mvc.perform(
                                                post("/biz/merchant/quick-start")
                                                        .header("Authorization", bearer)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                "{\"storeName\":\"License shop\"}"))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("data");
        String store = profile.path("currentStoreNo").asText();
        jdbc.update(
                "update mch_store set commerce_model='STORE_V1',business_type_code='RETAIL' where"
                        + " store_no=?",
                store);
        jdbc.update(
                "insert into"
                    + " mch_qualification(qual_no,entity_no,qual_type,qual_name,created_at,updated_at)"
                    + " values ('BF_qual','FOREIGN','OTHER','original',now(),now())");
        var response =
                mvc.perform(
                                post("/biz/qualifications/save")
                                        .header("Authorization", bearer)
                                        .header("X-Store-No", store)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        java.util.Map.of(
                                                                "entityNo",
                                                                profile.path("merchantNo").asText(),
                                                                "qualNo",
                                                                "BF_qual",
                                                                "qualName",
                                                                "modified",
                                                                "qualType",
                                                                "OTHER"))))
                        .andReturn()
                        .getResponse();
        assertNotEquals(0, json.readTree(response.getContentAsString()).path("code").asInt());
        assertEquals(
                "original",
                jdbc.queryForObject(
                        "select qual_name from mch_qualification where qual_no='BF_qual'",
                        String.class));
    }

    @Test
    void legacyApplicantCanFileDirectlyButCannotClaimInactiveOrDifferentPendingShop()
            throws Exception {
        String token = login("15965010109").path("token").asText();
        String user = tokens.get(token).orElseThrow().user().userNo();
        var request =
                post("/biz/merchant/apply")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"name\":\"Initial"
                                    + " shop\",\"subject\":\"PERSONAL\",\"contactName\":\"Owner\",\"contactPhone\":\"15965010109\"}");
        mvc.perform(request)
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.code")
                                .value(0));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from mch_entity_apply where user_no=? and entity_no is"
                            + " null",
                        Integer.class,
                        user));
        jdbc.update("delete from mch_entity_apply where user_no=?", user);
        jdbc.update(
                "insert into mch_entity(entity_no,name,status,created_at,updated_at) values"
                    + " ('R1_A','A','ACTIVE',now(),now()),('R1_B','B','PENDING_LICENSE',now(),now())");
        jdbc.update(
                "insert into"
                    + " mch_account(mch_account_no,user_no,entity_no,is_owner,status,created_at,updated_at)"
                    + " values"
                    + " ('R1_MA',?,'R1_A',1,'ACTIVE',now(),now()),('R1_MB',?,'R1_B',1,'DISABLED',now(),now())",
                user,
                user);
        jdbc.update(
                "insert into"
                    + " mch_store(store_no,entity_no,is_default,commerce_model,business_type_code,created_at,updated_at)"
                    + " values"
                    + " ('R1_SA','R1_A',1,'LEGACY',null,now(),now()),('R1_SB','R1_B',1,'STORE_V1','RETAIL',now(),now())");
        for (String header : java.util.List.of("", "R1_SA")) {
            var req =
                    post("/biz/merchant/apply")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Claim\",\"subject\":\"PERSONAL\"}");
            if (!header.isBlank()) req.header("X-Store-No", header);
            var response = mvc.perform(req).andReturn().getResponse();
            assertEquals(
                    ai.neargo.shop.common.ErrorCode.FORBIDDEN.code(),
                    json.readTree(response.getContentAsString()).path("code").asInt(),
                    header);
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "select count(*) from mch_entity_apply where user_no=?",
                            Integer.class,
                            user));
        }
        jdbc.update("update mch_account set status='ACTIVE' where mch_account_no='R1_MB'");
        jdbc.update("update mch_store set commerce_model='LEGACY' where store_no='R1_SB'");
        mvc.perform(request)
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.code")
                                .value(0));
        assertEquals(
                "R1_B",
                jdbc.queryForObject(
                        "select entity_no from mch_entity_apply where user_no=?",
                        String.class,
                        user));
    }

    @Test
    void loginAndSelectedProfilePromptOnlyForTheCurrentEntityWhileApplyRemainsAccountWide()
            throws Exception {
        String phone = "15965010110";
        String user = users.ensureUserByPhone(phone);
        for (String entity : java.util.List.of("R3_A", "R3_B")) {
            jdbc.update(
                    "insert into mch_entity(entity_no,name,status,created_at,updated_at) values"
                        + " (?,?,'ACTIVE',now(),now())",
                    entity,
                    entity);
            jdbc.update(
                    "insert into"
                        + " mch_account(mch_account_no,user_no,entity_no,is_owner,created_at,updated_at)"
                        + " values (?,?,?,1,now(),now())",
                    entity,
                    user,
                    entity);
            jdbc.update(
                    "insert into mch_store(entity_no,store_no,created_at,updated_at) values"
                        + " (?,?,now(),now())",
                    entity,
                    entity + "_S");
        }
        jdbc.update(
                "insert into"
                    + " mch_entity_apply(apply_no,user_no,entity_no,name,submitted_by,created_at,updated_at)"
                    + " values ('R3_old',?,'R3_A','A','OPS',now(),now())",
                user);
        jdbc.update(
                "insert into"
                    + " mch_entity_apply(apply_no,user_no,entity_no,name,submitted_by,created_at,updated_at)"
                    + " values ('R3_apply',?,'R3_B','B','OPS',now(),now())",
                user);
        var login = login(phone);
        assertEquals("R3_A", login.path("merchant").path("merchantNo").asText());
        assertFalse(login.path("merchant").path("agreementPending").asBoolean());
        String bearer = "Bearer " + login.path("token").asText();
        for (String entity : java.util.List.of("R3_A", "R3_B")) {
            var profile =
                    json.readTree(
                                    mvc.perform(
                                                    get("/biz/merchant/profile")
                                                            .header("Authorization", bearer)
                                                            .header("X-Store-No", entity + "_S"))
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString())
                            .path("data");
            assertEquals(entity, profile.path("merchantNo").asText());
            assertEquals(entity.equals("R3_B"), profile.path("agreementPending").asBoolean());
            var apply =
                    json.readTree(
                                    mvc.perform(
                                                    get("/biz/merchant/apply")
                                                            .header("Authorization", bearer)
                                                            .header("X-Store-No", entity + "_S"))
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString())
                            .path("data");
            assertEquals("R3_apply", apply.path("applyNo").asText());
        }
    }

    @Test
    void sessionEntryNeedsNoMerchantDirectoryEvenWhenAnOldSessionIsPresented() throws Exception {
        var user = LoginUser.merchantByUser("owner", "owner");
        var identity =
                new BizIdentityResolver() {
                    public BizContext resolve(String no, String store) {
                        throw new AssertionError("bare resolution");
                    }

                    public BizContext resolveAuthenticated(LoginUser principal, String store) {
                        throw new AssertionError(
                                "accessed merchant directory during account entry");
                    }
                };
        var filter = new BizContextFilter(identity);
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(
                                user, null, java.util.List.of()));
        try {
            for (String path :
                    java.util.List.of(
                            "/biz/auth/otp/send",
                            "/biz/auth/email-otp/send",
                            "/biz/auth/login",
                            "/biz/auth/staff-login",
                            "/biz/auth/logout")) {
                var policy = new ai.neargo.commerce.starter.security.CommerceRoutePolicy();
                assertTrue(
                        policy.allowsAccountRecovery(
                                policy.match("POST", path), "POST", path, "old-store", null));
                var request = new org.springframework.mock.web.MockHttpServletRequest("POST", path);
                request.addHeader("X-Store-No", "old-store");
                var chain = new org.springframework.mock.web.MockFilterChain();
                filter.doFilter(
                        request, new org.springframework.mock.web.MockHttpServletResponse(), chain);
                assertNotNull(chain.getRequest());
            }
            for (String path : java.util.List.of("/biz/auth/password", "/biz/auth/login/extra")) {
                var policy = new ai.neargo.commerce.starter.security.CommerceRoutePolicy();
                assertFalse(
                        policy.allowsAccountRecovery(
                                policy.match("POST", path), "POST", path, "old-store", null));
                var request = new org.springframework.mock.web.MockHttpServletRequest("POST", path);
                assertThrows(
                        AssertionError.class,
                        () ->
                                filter.doFilter(
                                        request,
                                        new org.springframework.mock.web.MockHttpServletResponse(),
                                        new org.springframework.mock.web.MockFilterChain()));
            }
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
