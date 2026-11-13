package ai.neargo.shop.commercebridge;

import ai.neargo.shop.spi.platform.SmsAdmissionPort;

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
import ai.neargo.shop.merchant.mapper.MerchantMappers.*;
import ai.neargo.shop.merchant.service.LoginIdentityService;
import ai.neargo.shop.merchantbridge.impl.LoginIdentityServiceImpl;
import ai.neargo.shop.spi.marketing.FissionPort;
import ai.neargo.shop.spi.notify.*;
import ai.neargo.shop.spi.user.*;
import ai.neargo.shop.user.mapper.UserMappers.*;
import ai.neargo.shop.user.service.*;
import ai.neargo.shop.user.service.impl.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real credentials, MySQL ticket locking and typed merchant security chain. */
@EnabledIfEnvironmentVariable(named="COMMERCE_MYSQL_URL", matches="jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class BizIdentityNativeMysqlTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final class Fixture implements AutoCloseable {
        final AccessMysqlFixture db = new AccessMysqlFixture(java.time.Clock.systemDefaultZone());
        final AnnotationConfigWebApplicationContext web = new AnnotationConfigWebApplicationContext();
        final InMemoryRateLimiter limiter = new InMemoryRateLimiter();
        final OtpStore otp = new OtpStore(limiter);
        final RealmRoutingTokenStore tokens = new RealmRoutingTokenStore(Map.of(
                Realm.MERCHANT, db.store, Realm.CONSUMER, new MemoryTokenStore(Duration.ofHours(1)),
                Realm.OPERATOR, new MemoryTokenStore(Duration.ofHours(1))));
        final LoginIdentityService identities;
        final AuthServiceImpl auth;
        final MockMvc mvc;
        Fixture() throws Exception {
            String schema = new ClassPathResource("schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
            for (String name : List.of("usr_identity", "usr_login_ticket")) {
                var m = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS " + name + "\\s*\\(.*?\n\\);").matcher(schema);
                assertTrue(m.find(), name);
                db.jdbc.execute(m.group());
            }
            db.mybatis.getConfiguration().addMapper(IdentityMapper.class);
            var guard = new OtpSendGuard(limiter, true, 60, 10, 20, 15);
            var otpService = mock(ai.neargo.shop.common.otp.service.OtpSendService.class);
            when(otpService.verifyAndConsume(any(), any())).thenAnswer(inv -> {
                ai.neargo.shop.common.otp.service.OtpSendService.VerifyCommand c = inv.getArgument(0);
                String recipient = ai.neargo.shop.common.otp.OtpKeys.normalize(c.channel(), c.recipient());
                return otp.verifyAndConsume(ai.neargo.shop.common.otp.OtpKeys.storageKey(c.scene(), c.channel(), recipient, c.businessKey()), c.code());
            });
            auth = new AuthServiceImpl(db.mybatis.getMapper(UserMapper.class),
                db.mybatis.getMapper(IdentityMapper.class),
                mock(WxPhonePort.class),
                mock(MpSessionService.class),
                tokens,
                mock(WxAuthPort.class),
                new PasswordHasher(),
                limiter,
                mock(PersonService.class),
                mock(FissionPort.class),
                otpService);
            var ticketService = new LoginTicketServiceImpl(db.jdbc, db.manager, Duration.ofMinutes(5));
            identities = new LoginIdentityServiceImpl(auth, ticketService, tokens, mock(LoginAuditor.class),
                    db.mybatis.getMapper(MchAccountMapper.class), db.mybatis.getMapper(MchEntityMapper.class),
                    db.mybatis.getMapper(MchStoreMapper.class), db.mybatis.getMapper(MchStoreRoleMapper.class),
                    db.mybatis.getMapper(IdentityMapper.class), db.mybatis.getMapper(UserMapper.class), new StaticMessageSource());
            mvc = CommerceAccessSecurityFlowTest.merchantMvc(db, web, true,
                    Map.of(AuthService.class, auth, OtpStore.class, otp, TokenStore.class, tokens,
                            LoginIdentityService.class, identities));
        }
        JsonNode post(String path, Map<String, ?> body) throws Exception {
            String raw = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            return json.readTree(raw);
        }
        JsonNode discover(String phone) throws Exception {
            otp.save(phone, "246813");
            return post("/biz/auth/identities", Map.of("grantType","PHONE_OTP","principal",phone,
                    "credential","246813","agreed",true));
        }
        JsonNode enter(JsonNode choice, String identity) throws Exception {
            return post("/biz/auth/identities/enter", Map.of("ticket",choice.path("ticket").asString(),"identityNo",identity));
        }
        void ownerPhone(String phone) {
            db.jdbc.update("INSERT INTO usr_identity(user_no,identity_type,identity_value,created_at,updated_at) VALUES('U','PHONE',?,NOW(),NOW())",phone);
        }
        void staff(String no, String phone) {
            db.jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,login_phone,created_at,updated_at) VALUES(?,'M',?,NOW(),NOW())",no,phone);
            db.jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at) VALUES(?,'S','CLERK',NOW(),NOW())",no);
        }
        @Override public void close() { web.close(); db.close(); }
    }

    @Test void discoveryDoesNotCreateAccountOrSessionAndNewOwnerRequiresEnter() throws Exception {
        try (var f = new Fixture()) {
            int users = f.db.jdbc.queryForObject("SELECT COUNT(*) FROM usr_account", Integer.class);
            var choice = f.discover("15965040201");
            assertEquals(0,choice.path("identities").size());
            assertTrue(choice.path("canCreateOwner").asBoolean());
            assertEquals(users,f.db.jdbc.queryForObject("SELECT COUNT(*) FROM usr_account",Integer.class));
            assertEquals(0,f.db.jdbc.queryForObject("SELECT COUNT(*) FROM mch_session",Integer.class));
            assertTrue(f.otp.peek("15965040201").isEmpty());
            var entered = f.enter(choice,"NEW_OWNER");
            String token = entered.path("token").asString();
            assertTrue(token.startsWith("btk_"),entered.toString());
            assertEquals(SubjectKind.USR,f.tokens.get(token).orElseThrow().user().subjectKind());
            assertEquals("OWNER",entered.path("merchant").path("currentIdentity").path("identityNo").asString());
            assertEquals(10474,f.enter(choice,"NEW_OWNER").path("code").asInt());
        }
    }

    @Test void disabledIdentityKeepsTicketAndNarrowsChoicesThenSwitchRevokesOnlyOrigin() throws Exception {
        try (var f = new Fixture()) {
            String phone="15965040202";
            f.ownerPhone(phone); f.staff("STAFF",phone);
            var choice=f.discover(phone);
            assertEquals(2,choice.path("identities").size());
            f.db.jdbc.update("UPDATE mch_account SET status='DISABLED' WHERE mch_account_no='STAFF'");
            var invalid=f.enter(choice,"STAFF-STAFF");
            assertEquals(10404,invalid.path("code").asInt());
            assertEquals("OWNER",invalid.path("data").path("identities").path(0).path("identityNo").asString());
            assertNull(f.db.jdbc.queryForObject("SELECT consumed_at FROM usr_login_ticket",java.time.LocalDateTime.class));
            String origin=f.enter(choice,"OWNER").path("token").asString();
            String other=f.enter(f.discover(phone),"OWNER").path("token").asString();
            f.db.jdbc.update("UPDATE mch_account SET status='ACTIVE' WHERE mch_account_no='STAFF'");
            String raw=f.mvc.perform(get("/biz/auth/identities/mine").header("Authorization","Bearer "+origin))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var mine=json.readTree(raw);
            assertEquals("OWNER",mine.path("currentIdentityNo").asString());
            String staff=f.enter(mine,"STAFF-STAFF").path("token").asString();
            assertEquals(SubjectKind.MCH,f.tokens.get(staff).orElseThrow().user().subjectKind());
            assertTrue(f.tokens.get(origin).isEmpty());
            assertTrue(f.tokens.get(other).isPresent());
        }
    }

    @Test void concurrentTicketEntrySucceedsOnceAndExpiredTicketCannotEnter() throws Exception {
        try (var f = new Fixture()) {
            f.ownerPhone("15965040203");
            var choice=f.discover("15965040203");
            var barrier=new CyclicBarrier(2);
            try (var pool=Executors.newFixedThreadPool(2)) {
                List<Future<Boolean>> calls=new ArrayList<>();
                for(int i=0;i<2;i++) calls.add(pool.submit(()->{
                    barrier.await();
                    try { f.identities.enter(choice.path("ticket").asString(),"OWNER",Locale.ENGLISH,(u,t)->t); return true; }
                    catch(ai.neargo.shop.common.BizException e) { assertEquals(10474,e.errorCode().code()); return false; }
                }));
                assertEquals(1,calls.stream().filter(call->{try{return call.get();}catch(Exception e){throw new AssertionError(e);}}).count());
            }
            var expired=f.discover("15965040203");
            f.db.jdbc.update("UPDATE usr_login_ticket SET expires_at=DATE_SUB(NOW(),INTERVAL 1 DAY)");
            assertEquals(10474,f.enter(expired,"OWNER").path("code").asInt());
        }
    }
    @Test void emailUsesOnlyItsAccountsVerifiedPhoneForStaffDiscovery() throws Exception {
        try (var f = new Fixture()) {
            f.ownerPhone("15965040204");
            f.staff("BOUND", "15965040204");
            f.staff("OTHER", "15965040205");
            f.db.jdbc.update("INSERT INTO usr_identity(user_no,identity_type,identity_value,created_at,updated_at) VALUES('U','EMAIL','owner@example.test',NOW(),NOW())");
            f.otp.save(EmailOtpService.key("owner@example.test"), "246813");
            var linked=f.post("/biz/auth/identities",Map.of("grantType","EMAIL_OTP","principal","OWNER@EXAMPLE.TEST","credential","246813"));
            assertEquals(2,linked.path("identities").size());
            assertEquals("STAFF-BOUND",linked.path("identities").path(1).path("identityNo").asString());
            assertFalse(linked.toString().contains("STAFF-OTHER"));
            f.otp.save(EmailOtpService.key("new@example.test"), "246813");
            var unlinked=f.post("/biz/auth/identities",Map.of("grantType","EMAIL_OTP","principal","new@example.test","credential","246813"));
            assertEquals(0,unlinked.path("identities").size());
            var noConsent=f.enter(unlinked,"NEW_OWNER");
            assertEquals(10400,noConsent.path("code").asInt());
            assertEquals(0,f.db.jdbc.queryForObject("SELECT COUNT(*) FROM usr_identity WHERE identity_value='new@example.test'",Integer.class));
        }
    }

    @Test void switchTicketCannotOutliveItsSourceSessionOrChooseAnUnlistedStaff() throws Exception {
        try(var f=new Fixture()) {
            f.ownerPhone("15965040206");
            f.staff("FOREIGN","15965040207");
            var choice=f.discover("15965040206");
            assertEquals(10404,f.enter(choice,"STAFF-FOREIGN").path("code").asInt());
            String origin=f.enter(choice,"OWNER").path("token").asString();
            String raw=f.mvc.perform(get("/biz/auth/identities/mine").header("Authorization","Bearer "+origin))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var mine=json.readTree(raw);
            f.tokens.revoke(origin);
            assertEquals(10474,f.enter(mine,"OWNER").path("code").asInt());
        }
    }

    @Test void switchSourceRevokedOutsideOldRepeatableReadSnapshotIsStillRejected() throws Exception {
        try(var f=new Fixture()) {
            f.ownerPhone("15965040208");
            String origin=f.enter(f.discover("15965040208"),"OWNER").path("token").asString();
            String raw=f.mvc.perform(get("/biz/auth/identities/mine").header("Authorization","Bearer "+origin))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String ticket=json.readTree(raw).path("ticket").asString();
            var outer=new org.springframework.transaction.support.TransactionTemplate(f.db.manager);
            outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            outer.execute(status->{
                String hash=ai.neargo.auth.store.TokenHash.of(origin);
                assertNull(f.db.jdbc.queryForObject("SELECT revoked_at FROM mch_session WHERE token_hash=?",java.time.LocalDateTime.class,hash));
                CompletableFuture.runAsync(()->f.tokens.revoke(origin)).join();
                assertNull(f.db.jdbc.queryForObject("SELECT revoked_at FROM mch_session WHERE token_hash=?",java.time.LocalDateTime.class,hash));
                var failure=assertThrows(ai.neargo.shop.common.BizException.class,
                        ()->f.identities.enter(ticket,"OWNER",Locale.ENGLISH,(u,t)->t));
                assertEquals(10474,failure.errorCode().code());
                return null;
            });
        }
    }

    @Test void cacheSourceSurvivesCommitFailureAndSameTicketRetriesWithoutKickingSibling() throws Exception {
        try (var f = new Fixture()) {
            String phone = "15965040209";
            f.ownerPhone(phone);
            var cache = new MemoryTokenStore(Duration.ofHours(1));
            var failNextCommit = new java.util.concurrent.atomic.AtomicBoolean();
            var controlled = new org.springframework.transaction.PlatformTransactionManager() {
                @Override public org.springframework.transaction.TransactionStatus getTransaction(
                        org.springframework.transaction.TransactionDefinition definition) {
                    return f.db.manager.getTransaction(definition);
                }
                @Override public void commit(org.springframework.transaction.TransactionStatus status) {
                    if (failNextCommit.getAndSet(false)) {
                        f.db.manager.rollback(status);
                        throw new org.springframework.transaction.TransactionSystemException("controlled commit failure");
                    }
                    f.db.manager.commit(status);
                }
                @Override public void rollback(org.springframework.transaction.TransactionStatus status) {
                    f.db.manager.rollback(status);
                }
            };
            var tickets = new LoginTicketServiceImpl(f.db.jdbc, controlled, Duration.ofMinutes(5));
            LoginIdentityService service = new LoginIdentityServiceImpl(f.auth, tickets, cache, mock(LoginAuditor.class),
                    f.db.mybatis.getMapper(MchAccountMapper.class), f.db.mybatis.getMapper(MchEntityMapper.class),
                    f.db.mybatis.getMapper(MchStoreMapper.class), f.db.mybatis.getMapper(MchStoreRoleMapper.class),
                    f.db.mybatis.getMapper(IdentityMapper.class), f.db.mybatis.getMapper(UserMapper.class), new StaticMessageSource());
            f.otp.save(phone, "246813");
            var first = service.verifyAndList("PHONE_OTP", phone, "246813", true, null, Locale.ENGLISH);
            String source = service.enter(first.ticket(), "OWNER", Locale.ENGLISH, (user, token) -> token);
            f.otp.save(phone, "246813");
            var second = service.verifyAndList("PHONE_OTP", phone, "246813", true, null, Locale.ENGLISH);
            String sibling = service.enter(second.ticket(), "OWNER", Locale.ENGLISH, (user, token) -> token);
            var switchChoice = service.mine(cache.get(source).orElseThrow().user(), source, Locale.ENGLISH);
            var attempted = new java.util.concurrent.atomic.AtomicReference<String>();
            failNextCommit.set(true);
            assertThrows(org.springframework.transaction.TransactionSystemException.class,
                    () -> service.enter(switchChoice.ticket(), "OWNER", Locale.ENGLISH, (user, token) -> {
                        attempted.set(token);
                        return token;
                    }));
            assertTrue(cache.get(source).isPresent(), "Failed switch must retain its source session");
            assertTrue(cache.get(sibling).isPresent());
            assertTrue(cache.get(attempted.get()).isEmpty(), "Rolled-back replacement must be revoked");
            assertNull(f.db.jdbc.queryForObject("SELECT consumed_at FROM usr_login_ticket WHERE ticket_hash=?",
                    java.time.LocalDateTime.class, ai.neargo.auth.store.TokenHash.of(switchChoice.ticket())));
            String replacement = service.enter(switchChoice.ticket(), "OWNER", Locale.ENGLISH, (user, token) -> token);
            assertTrue(cache.get(replacement).isPresent());
            assertTrue(cache.get(source).isEmpty(), "Successful commit revokes only its exact source");
            assertTrue(cache.get(sibling).isPresent());
        }
    }

}
