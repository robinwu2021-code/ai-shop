package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import org.springframework.http.HttpMethod;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

/** Real realm-specific tokens traverse the configured security filters and merchant permission evaluator. */
@SpringBootTest(properties = {"shop.pay.entitlement.catalog.enabled=true", "shop.pay.entitlement.issue.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:ent-template-http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementTemplateHttpFlowTest {
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

    private Map<String, Object> draft() {
        return Map.ofEntries(Map.entry("title", "Care coupon"), Map.entry("kind", "ORDER_COUPON"),
                Map.entry("funder", "MERCHANT"), Map.entry("currency", "CNY"),
                Map.entry("benefitMode", "CASH"), Map.entry("benefitValue", 500),
                Map.entry("minAmountMinor", 0), Map.entry("usageModel", "COUNT"), Map.entry("totalUnits", 1),
                Map.entry("validity", Map.of("mode", "RELATIVE", "validDays", 30, "startBasis", "GRANTED",
                        "periodBasis", "ROLLING_DAYS", "zone", "Asia/Shanghai")),
                Map.entry("redemptionScope", Map.of("mode", "ALL", "refs", List.of())),
                Map.entry("allowedIssueModes", List.of("CENTER", "TARGETED", "ACTIVITY")));
    }

    @Test
    void ownerManagesCatalogAndPolicyWithStableIdempotency() throws Exception {
        String key = UUID.randomUUID().toString();
        var created = call("POST", "", merchant, draft(), key);
        assertThat(created.path("code").asInt()).isZero();
        String no = created.path("data").path("templateNo").asString();
        long revision = created.path("data").path("revision").asLong();
        var publish = call("POST", "/" + no + "/publish", merchant,
                Map.of("expectedRevision", revision), key + "p");
        assertThat(publish.path("code").asInt()).isZero();
        var policy = call("PUT", "/" + no + "/issue-policy", merchant,
                Map.of("expectedRevision", 0, "limitCount", 20, "maxExposureMinor", 10000,
                        "perUserLimit", 2, "enabled", true), key + "i");
        assertThat(policy.path("code").asInt()).isZero();
        var list = call("GET", "", merchant, null, null);
        assertThat(list.path("code").asInt()).isZero();
        var item = list.path("data").path("records").get(0);
        assertThat(item.path("templateNo").asString()).isEqualTo(no);
        assertThat(item.path("issuePolicy").path("limitCount").asInt()).isEqualTo(20);
        assertThat(call("POST", "", merchant, draft(), key)).isEqualTo(created);
        var pause = Map.of("expectedRevision", item.path("revision").asLong(), "status", "PAUSED");
        assertThat(call("PUT", "/" + no + "/status", merchant, pause, key + "s")
                .path("code").asInt()).isZero();
        assertThat(call("POST", "", merchant, draft(), key)).isEqualTo(created);
    }

    @Test
    void draftRevisionAndOwnerAreCheckedAndPolicyInputsAreValidated() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = call("POST", "", merchant, draft(), key).path("data");
        String no = first.path("templateNo").asString();
        var changed = new HashMap<String, Object>(draft());
        changed.put("title", "Updated coupon");
        changed.put("expectedRevision", first.path("revision").asLong());
        var result = call("PUT", "/" + no + "/draft", merchant, changed, key + "d");
        assertThat(result.path("code").asInt()).isZero();
        assertThat(result.path("data").path("draft").path("title").asString()).isEqualTo("Updated coupon");
        assertThat(call("PUT", "/" + no + "/draft", merchant, changed, key + "d")).isEqualTo(result);
        assertThat(call("PUT", "/" + no + "/draft", merchant, changed, key + "stale")
                .path("code").asInt()).isEqualTo(10409);
        assertThat(call("PUT", "/FOREIGN/draft", merchant, changed, key + "foreign")
                .path("code").asInt()).isEqualTo(10404);
        assertThat(call("PUT", "/" + no + "/issue-policy", merchant,
                Map.of("expectedRevision", 0, "perUserLimit", 0, "enabled", true), key + "invalid")
                .path("code").asInt()).isEqualTo(10400);
    }

    @Test
    void rejectsWrongRealmInvalidBodyAndStoreLimitedMarketingStaff() throws Exception {
        assertThat(call("GET", "", consumer, null, null).path("code").asInt()).isNotZero();
        assertThat(call("POST", "", merchant, Map.of(), "bad").path("code").asInt()).isEqualTo(10400);
        jdbc.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no=?", account);
        jdbc.update("INSERT INTO mch_role(entity_no,role_code,name,perms,tenant_no,created_at,updated_at) "
                + "VALUES (?,'ENT_STAFF','staff','[\"biz:campaign\"]','MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity);
        jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'ENT_STAFF','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", account, store);
        assertThat(call("GET", "", merchant, null, null).path("code").asInt()).isEqualTo(70006);
        jdbc.update("UPDATE mch_role SET perms='[\"pay:ent:template:read\"]' WHERE entity_no=?", entity);
        assertThat(call("GET", "", merchant, null, null).path("code").asInt()).isEqualTo(10403);
    }

    private JsonNode call(String method, String suffix, String token, Object body, String key) throws Exception {
        var req = request(HttpMethod.valueOf(method), "/biz/entitlement-templates" + suffix)
                .header("Authorization", "Bearer " + token).header("X-Store-No", store);
        if (key != null) req.header("Idempotency-Key", key);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        var response = mvc.perform(req).andReturn().getResponse();
        assertThat(response.getContentType()).contains("json");
        return json.readTree(response.getContentAsString());
    }
}
