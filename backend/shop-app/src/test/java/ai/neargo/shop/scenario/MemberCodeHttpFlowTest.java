package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real realm-specific tokens traverse the configured security filters and merchant permission evaluator. */
@SpringBootTest(properties = {"shop.member-code.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:member-code;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class MemberCodeHttpFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private TokenStores tokens;
    @Autowired private JdbcTemplate jdbc;
    private MockMvc mvc;
    private String consumer;
    private String merchant;
    private String user;
    private String entity;
    private String store;
    private String account;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String phone = "126" + String.format("%08d",
                Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100000000));
        consumer = TestLogin.consumer(mvc, json, otp, phone);
        user = tokens.of(Realm.CONSUMER).get(consumer).orElseThrow().user().userNo();
        entity = "MC-HTTP-M-" + UUID.randomUUID();
        store = "MC-HTTP-S-" + UUID.randomUUID();
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'code http merchant','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                entity, user);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'code http store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity);
        account = "MC-HTTP-A-" + UUID.randomUUID();
        jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,"
                + "tenant_no,created_at,updated_at) VALUES (?,?,?,1,1,'ACTIVE','MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", account, entity, user);
        merchant = TestLogin.merchantOwner(mvc, json, otp, phone);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM mbr_identity_ticket WHERE user_no=?", user);
        jdbc.update("DELETE FROM mch_store_role WHERE mch_account_no=?", account);
        jdbc.update("DELETE FROM mch_role WHERE entity_no=?", entity);
        jdbc.update("DELETE FROM mch_account WHERE entity_no=?", entity);
        jdbc.update("DELETE FROM mch_store WHERE entity_no=?", entity);
        jdbc.update("DELETE FROM mch_entity WHERE entity_no=?", entity);
        // Login creates identity/session facts; cleanup only this test's account and realm tokens.
        if (consumer != null) { tokens.of(Realm.CONSUMER).revoke(consumer); }
        if (merchant != null) { tokens.of(Realm.MERCHANT).revoke(merchant); }
    }

    @Test
    void consumerIssuesCodeAndMerchantResolvesWithoutLeakingAccountIdentifiers() throws Exception {
        var issued = call("/mp/member-code", consumer, null);
        assertThat(issued.path("code").asInt()).isZero();
        var data = issued.path("data");
        String code = data.path("memberCode").asString();
        assertThat(code).startsWith("mc1_");
        var result = call("/biz/member-code/resolve", merchant,
                json.writeValueAsString(new ResolveBody(code, store)));
        assertThat(result.path("code").asInt()).isZero();
        assertThat(result.path("data").path("customerRef").asString()).startsWith("cr1_");
        assertThat(result.path("data").has("userNo")).isFalse();
        assertThat(result.path("data").has("phone")).isFalse();
        assertThat(result.path("data").has("memberCode")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mbr_identity_ticket WHERE user_no=? "
                + "AND entity_no=? AND store_no=? AND actor_no=?", Integer.class, user, entity, store, user)).isOne();
    }

    @Test
    void wrongRealmsCannotMintOrResolveAndMalformedRequestsCannotCreateReferences() throws Exception {
        assertThat(call("/mp/member-code", merchant, null).path("code").asInt()).isNotZero();
        assertThat(call("/biz/member-code/resolve", consumer, "{}").path("code").asInt()).isNotZero();
        var code = call("/mp/member-code", consumer, null).path("data").path("memberCode").asString();
        assertThat(call("/biz/member-code/resolve", merchant, "{}").path("code").asInt()).isEqualTo(10400);
        assertThat(call("/biz/member-code/resolve", merchant,
                json.writeValueAsString(new ResolveBody(code, "FOREIGN-STORE"))).path("code").asInt()).isNotZero();
        assertThat(call("/biz/member-code/resolve", merchant,
                json.writeValueAsString(new ResolveBody(consumer, store))).path("code").asInt()).isNotZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mbr_identity_ticket WHERE user_no=? "
                + "AND purpose='CUSTOMER_REFERENCE'", Integer.class, user)).isZero();
    }

    @Test
    void staffNeedsLiveCustomerPermissionAndCannotScanForAnotherStore() throws Exception {
        String other = "MC-HTTP-S-" + UUID.randomUUID();
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'other store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", other, entity);
        jdbc.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no=?", account);
        jdbc.update("INSERT INTO mch_role(entity_no,role_code,name,perms,tenant_no,created_at,updated_at) "
                + "VALUES (?,'CODE_CUSTOMER','code customer','[\"biz:customer\"]','MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity);
        jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'CODE_CUSTOMER','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", account, store);
        String code = call("/mp/member-code", consumer, null).path("data").path("memberCode").asString();
        assertThat(call("/biz/member-code/resolve", merchant,
                json.writeValueAsString(new ResolveBody(code, store))).path("code").asInt()).isZero();
        // Other belongs to the same active merchant: only the staff subset can reject it.
        assertThat(call("/biz/member-code/resolve", merchant,
                json.writeValueAsString(new ResolveBody(code, other))).path("code").asInt()).isEqualTo(10404);
        jdbc.update("UPDATE mch_role SET perms='[]' WHERE entity_no=?", entity);
        assertThat(call("/biz/member-code/resolve", merchant,
                json.writeValueAsString(new ResolveBody(code, store))).path("code").asInt()).isEqualTo(70006);
    }

    @Test
    void customerPermissionAtOneStoreCannotBeBorrowedForAnotherAssignedStore() throws Exception {
        String other = "MC-HTTP-S-" + UUID.randomUUID();
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'assigned second store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                other, entity);
        jdbc.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no=?", account);
        jdbc.update("INSERT INTO mch_role(entity_no,role_code,name,perms,tenant_no,created_at,updated_at) "
                + "VALUES (?,'CODE_CUSTOMER','code customer','[\"biz:customer\"]','MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity);
        jdbc.update("INSERT INTO mch_role(entity_no,role_code,name,perms,tenant_no,created_at,updated_at) "
                + "VALUES (?,'CODE_GOODS','code goods','[\"biz:goods\"]','MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity);
        jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'CODE_CUSTOMER','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", account, store);
        jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'CODE_GOODS','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", account, other);
        String code = call("/mp/member-code", consumer, null).path("data").path("memberCode").asString();
        assertThat(call("/biz/member-code/resolve", merchant,
                json.writeValueAsString(new ResolveBody(code, store))).path("code").asInt()).isZero();
        // Both stores are assigned, and the active header store has customer permission.
        // Only target-store permission can reject this request.
        assertThat(call("/biz/member-code/resolve", merchant,
                json.writeValueAsString(new ResolveBody(code, other))).path("code").asInt()).isEqualTo(70006);
    }

    @Test
    void successAndValidationErrorsDoNotWriteRawCredentialsToLogs() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var capture = new ListAppender<ILoggingEvent>();
        capture.start();
        logger.addAppender(capture);
        try {
            String code = call("/mp/member-code", consumer, null).path("data").path("memberCode").asString();
            var result = call("/biz/member-code/resolve", merchant,
                    json.writeValueAsString(new ResolveBody(code, store)));
            assertThat(result.path("code").asInt()).isZero();
            String ref = result.path("data").path("customerRef").asString();
            assertThat(call("/biz/member-code/resolve", merchant,
                    json.writeValueAsString(new ResolveBody(code, ""))).path("code").asInt()).isEqualTo(10400);
            String logs = capture.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(logs).contains("storeNo").doesNotContain(code, ref);
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }

    private JsonNode call(String path, String token, String body) throws Exception {
        var request = post(path).header("Authorization", "Bearer " + token);
        if (path.startsWith("/biz/")) { request.header("X-Store-No", store); }
        if (body != null) { request.contentType(MediaType.APPLICATION_JSON).content(body); }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getContentType()).as("registered handler or JSON authentication rejection")
                .contains("json");
        return json.readTree(response.getContentAsString());
    }

    private record ResolveBody(String memberCode, String storeNo) { }
}
