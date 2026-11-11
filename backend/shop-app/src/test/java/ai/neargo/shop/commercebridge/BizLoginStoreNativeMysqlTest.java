package ai.neargo.shop.commercebridge;

import ai.neargo.shop.platform.MarketPhonePolicyService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.common.ratelimit.InMemoryRateLimiter;
import ai.neargo.shop.common.ratelimit.OtpSendGuard;
import ai.neargo.shop.merchant.service.MerchantPaymentService;
import ai.neargo.shop.merchant.service.StoreAdminService;
import ai.neargo.shop.merchant.service.StoreCategoryService;
import ai.neargo.shop.merchant.service.StoreLinkService;
import ai.neargo.shop.merchant.service.MerchantPlanService;
import ai.neargo.shop.merchant.service.MerchantStoreCreation;
import ai.neargo.shop.merchant.service.impl.StoreAdminServiceImpl;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreRoleMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchPaymentMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.spi.marketing.FissionPort;
import ai.neargo.shop.spi.notify.MailPort;
import ai.neargo.shop.spi.notify.SmsPort;
import ai.neargo.shop.spi.user.WxAuthPort;
import ai.neargo.shop.spi.user.WxPhonePort;
import ai.neargo.shop.user.mapper.UserMappers.IdentityMapper;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import ai.neargo.shop.user.service.*;
import ai.neargo.shop.user.service.impl.AuthServiceImpl;
import ai.neargo.shop.user.service.impl.EmailOtpServiceImpl;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual credential login and host security chain; only loopback disposable MySQL 9. */
@EnabledIfEnvironmentVariable(named = "COMMERCE_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class BizLoginStoreNativeMysqlTest {
    private final JsonMapper json = JsonMapper.builder().build();

    final class LoginFixture implements AutoCloseable {
        final AccessMysqlFixture db;
        final AnnotationConfigWebApplicationContext web = new AnnotationConfigWebApplicationContext();
        final InMemoryRateLimiter limiter = new InMemoryRateLimiter();
        final OtpStore otp = new OtpStore(limiter);
        final RealmRoutingTokenStore tokens;
        final AuthServiceImpl auth;
        final MockMvc mvc;
        final java.util.Map<Class<?>, Object> realServices = new java.util.HashMap<>();

        LoginFixture() throws Exception { this(f -> {}); }

        LoginFixture(java.util.function.Consumer<LoginFixture> beforeMvc) throws Exception {
            this(null, false, beforeMvc);
        }

        LoginFixture(String existingSchema, boolean preserveSchema,
                java.util.function.Consumer<LoginFixture> beforeMvc) throws Exception {
            db = new AccessMysqlFixture(java.time.Clock.systemDefaultZone(),
                    java.util.function.UnaryOperator.identity(), existingSchema, preserveSchema);
            String ddl = new ClassPathResource("schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
            var table = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS usr_identity\\s*\\(.*?\n\\);").matcher(ddl);
            assertTrue(table.find());
            if (existingSchema == null) db.jdbc.execute(table.group());
            db.mybatis.getConfiguration().addMapper(IdentityMapper.class);
            tokens = new RealmRoutingTokenStore(Map.of(
                    Realm.MERCHANT, db.store,
                    Realm.CONSUMER, new MemoryTokenStore(Duration.ofHours(1)),
                    Realm.OPERATOR, new MemoryTokenStore(Duration.ofHours(1))));
            var guard = new OtpSendGuard(limiter, true, 60, 10, 20, 15);
            var email = new EmailOtpServiceImpl(mock(MailPort.class), otp, guard, false, false, 60);
            auth = new AuthServiceImpl(db.mybatis.getMapper(UserMapper.class),
                    db.mybatis.getMapper(IdentityMapper.class), mock(WxPhonePort.class),
                    mock(MpSessionService.class), tokens, otp, guard, mock(SmsPort.class),
                    mock(WxAuthPort.class), new PasswordHasher(), limiter, mock(PersonService.class),
                    "", mock(FissionPort.class), mock(OtpTestPhoneService.class), email, mock(MarketPhonePolicyService.class));
            auth.setRegistrationMarkets("CN,TW");
            var stores = new StoreAdminServiceImpl(mock(MerchantStoreCreation.class),
                    mock(StoreCategoryService.class), db.mybatis.getMapper(MchStoreMapper.class),
                    db.mybatis.getMapper(MchStoreRoleMapper.class), db.mybatis.getMapper(MchPaymentMapper.class),
                    mock(MerchantPlanService.class), db.mybatis.getMapper(MchEntityMapper.class),
                    mock(StoreLinkService.class), null, null);
            stores.setOwnership(new ai.neargo.shop.merchant.service.impl.StoreOwnershipWritePortImpl(db.jdbc));
            realServices.putAll(Map.of(AuthService.class, auth, OtpStore.class, otp,
                    StoreAdminService.class, stores, TokenStore.class, tokens));
            try {
                beforeMvc.accept(this);
                mvc = CommerceAccessSecurityFlowTest.merchantMvc(db, web, true, realServices);
            } catch (RuntimeException | Error failure) {
                web.close();
                db.close();
                throw failure;
            }
        }

        JsonNode postJson(String path, Object body, String token) throws Exception {
            var req = post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
            if (token != null) req.header("Authorization", "Bearer " + token);
            var response = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse();
            return response.getContentAsString().isBlank() ? json.createObjectNode()
                    : json.readTree(response.getContentAsString());
        }

        JsonNode login(String grant, String principal, String credential, boolean agreed) throws Exception {
            return postJson("/biz/auth/login", Map.of("grantType", grant, "principal", principal,
                    "credential", credential, "agreed", agreed), null);
        }

        String phoneLogin(String phone) throws Exception {
            postJson("/biz/auth/otp/send", Map.of("phone", phone), null);
            var response = login("PHONE_OTP", phone, otp.peek(phone).orElseThrow(), true);
            return token(response);
        }

        String token(JsonNode response) {
            // This fixture registers MVC directly, without the application's response advice.
            var data = response.has("data") ? response.path("data") : response;
            String token = data.path("token").asString();
            assertTrue(token.startsWith("btk_"), response.toString());
            assertEquals(tokens.get(token).orElseThrow().user().subjectKind().name(),
                    data.path("merchant").path("subjectKind").asString());
            assertTrue(tokens.get(token).isPresent());
            return token;
        }

        void ownerPhone(String phone) {
            db.jdbc.update("UPDATE usr_account SET phone=? WHERE user_no='U'", phone);
            db.jdbc.update("INSERT INTO usr_identity(user_no,identity_type,identity_value,created_at,updated_at)"
                    + " VALUES('U','PHONE',?,NOW(),NOW())", phone);
        }

        void secondEntity() {
            db.jdbc.update("INSERT INTO mch_entity(entity_no,name,created_at,updated_at) VALUES('M2','second',NOW(),NOW())");
            db.jdbc.update("INSERT INTO mch_account(mch_account_no,user_no,entity_no,is_owner,created_at,updated_at)"
                    + " VALUES('MA2','U','M2',1,NOW(),NOW())");
            db.jdbc.update("INSERT INTO mch_store(entity_no,store_no,name,commerce_model,business_type_code,created_at,updated_at)"
                    + " VALUES('M2','S2','second','STORE_V1','RETAIL',NOW(),NOW())");
        }

        void read(String token, String store, String entity) throws Exception {
            mvc.perform(get("/biz/merchant/pay-channel").header("Authorization", "Bearer " + token)
                            .header("X-Store-No", store).header("X-Entity-No", entity))
                    .andExpect(status().isOk());
        }

        @Override public void close() {
            web.close();
            db.close();
        }
    }

    @Test
    void phoneCredentialThenPasswordSwitchesEntitiesAndFreshRevocationPreventsReuse() throws Exception {
        try (var f = new LoginFixture()) {
            f.ownerPhone("15965040101");
            f.secondEntity();
            String token = f.phoneLogin("15965040101");
            f.mvc.perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.subjectKind").value("USR"));
            assertEquals(SubjectKind.USR, f.tokens.get(token).orElseThrow().user().subjectKind());
            assertEquals("U", f.tokens.get(token).orElseThrow().user().userNo());
            var groups = f.mvc.perform(get("/biz/stores/mine").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(groups.contains("S2") && groups.contains("\"S\""), groups);
            var payments = f.web.getBean(MerchantPaymentService.class);
            f.read(token, "S", "M");
            f.read(token, "S2", "M2");
            verify(payments).availableChannels("M");
            verify(payments).availableChannels("M2");
            clearInvocations(payments);
            f.mvc.perform(get("/biz/merchant/pay-channel").header("Authorization", "Bearer " + token)
                    .header("X-Store-No", "S2").header("X-Entity-No", "M"))
                    .andExpect(status().isForbidden());
            f.postJson("/biz/auth/password", Map.of("password", "local-pass-2026"), token);
            String passwordToken = f.token(f.login("PASSWORD", "15965040101", "local-pass-2026", false));
            f.read(passwordToken, "S", "M");
            clearInvocations(payments);
            f.db.jdbc.update("UPDATE mch_account SET status='DISABLED' WHERE mch_account_no='MA2'");
            f.mvc.perform(get("/biz/merchant/pay-channel").header("Authorization", "Bearer " + token)
                    .header("X-Store-No", "S2")).andExpect(status().isForbidden());
            verifyNoInteractions(payments);
            f.mvc.perform(post("/biz/auth/logout").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            assertTrue(f.tokens.get(token).isEmpty());
            assertTrue(f.tokens.get(passwordToken).isPresent());
            f.mvc.perform(get("/biz/stores/mine").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
            f.db.jdbc.update("UPDATE mch_session SET expires_at=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE revoked_at IS NULL");
            f.mvc.perform(get("/biz/merchant/pay-channel").header("Authorization", "Bearer " + passwordToken)
                    .header("X-Store-No", "S")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void emailRegistrationRequiresAgreementConsumesOtpAndHasNoInventedPhoneOrStores() throws Exception {
        try (var f = new LoginFixture()) {
            String email = "Native.Login@Example.test";
            f.postJson("/biz/auth/email-otp/send", Map.of("email", email, "locale", "zh-Hant"), null);
            String code = f.otp.peek(EmailOtpService.key(EmailOtpService.normalize(email))).orElseThrow();
            var rejected = f.login("EMAIL_OTP", email, code, false);
            assertFalse(rejected.has("token"), rejected.toString());
            assertEquals(0, f.db.jdbc.queryForObject("SELECT COUNT(*) FROM usr_identity WHERE identity_type='EMAIL'", Integer.class));
            // The first valid OTP was consumed even though registration did not get consent.
            assertTrue(f.otp.peek(EmailOtpService.key(EmailOtpService.normalize(email))).isEmpty());
            String next = "246813";
            f.otp.save(EmailOtpService.key(EmailOtpService.normalize(email)), next);
            String token = f.token(f.login("EMAIL_OTP", email.toUpperCase(), next, true));
            String user = f.tokens.get(token).orElseThrow().user().userNo();
            assertNull(f.db.jdbc.queryForObject("SELECT phone FROM usr_account WHERE user_no=?", String.class, user));
            assertEquals("native.login@example.test", f.db.jdbc.queryForObject(
                    "SELECT identity_value FROM usr_identity WHERE user_no=? AND identity_type='EMAIL'", String.class, user));
            var groups = f.mvc.perform(get("/biz/stores/mine").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals("[]", groups);
            var replay = f.login("EMAIL_OTP", email, next, true);
            assertFalse(replay.has("token"), replay.toString());
        }
    }

    @Test
    void phoneStaffFallbackUsesMchIdentityAndOnlyItsGrantedStore() throws Exception {
        try (var f = new LoginFixture()) {
            f.db.jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,display_name,login_phone,created_at,updated_at)"
                    + " VALUES('STAFF','M','staff','15965040103',NOW(),NOW())");
            f.db.jdbc.update("INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at)"
                    + " VALUES('M','FIN','finance','[\"biz:finance\"]',NOW(),NOW())");
            f.db.jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                    + " VALUES('STAFF','S','FIN',NOW(),NOW())");
            f.secondEntity();
            String token = f.phoneLogin("15965040103");
            f.mvc.perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.subjectKind").value("MCH"));
            assertEquals(SubjectKind.MCH, f.tokens.get(token).orElseThrow().user().subjectKind());
            assertEquals("STAFF", f.tokens.get(token).orElseThrow().user().userNo());
            f.read(token, "S", "M");
            var payments = f.web.getBean(MerchantPaymentService.class);
            verify(payments).availableChannels("M");
            clearInvocations(payments);
            f.mvc.perform(get("/biz/merchant/pay-channel").header("Authorization", "Bearer " + token)
                    .header("X-Store-No", "S2")).andExpect(status().isForbidden());
            f.db.jdbc.update("UPDATE mch_account SET status='DISABLED' WHERE mch_account_no='STAFF'");
            f.mvc.perform(get("/biz/merchant/pay-channel").header("Authorization", "Bearer " + token)
                    .header("X-Store-No", "S")).andExpect(status().isUnauthorized());
            verifyNoInteractions(payments);
        }
    }
}
