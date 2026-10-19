package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.support.TestLogin;
import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
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

@SpringBootTest(properties = {"shop.member-code.enabled=true",
        "shop.pay.entitlement.cursor-key=consumer-query-key-over-thirty-two-characters",
        "spring.datasource.url=jdbc:h2:mem:consumer-ent-query;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementConsumerQueryHttpFlowTest {
    @Autowired WebApplicationContext web;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otp;
    @Autowired TokenStores tokens;
    private MockMvc mvc;
    private String token;
    private String phone;
    private String user;
    private String instance;
    private String entity;
    @BeforeEach void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        phone = "126" + String.format("%08d",
                Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100000000));
        token = TestLogin.consumer(mvc, json, otp, phone);
        user = tokens.of(Realm.CONSUMER).get(token).orElseThrow().user().userNo();
        instance = UUID.randomUUID().toString(); entity = UUID.randomUUID().toString();
        seed(instance, user);
    }
    private void seed(String no, String recipient) {
        var d = new EntitlementDefinition("30次游泳", "PAID_CARD", "MERCHANT", "CNY", "SERVICE_UNITS",
                null, null, null, null, null, "COUNT", 30, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "FIRST_USE", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("ALL", List.of()), List.of("PAID_ORDER"));
        jdbc.update("INSERT INTO ent_instance(instance_no,template_version_no,grant_no,source_unit_key,user_no,"
                + "entity_no,kind,status,rule_snapshot,unit_index,total_units,remaining_units,reserved_units,asset_seq,"
                + "tenant_no,created_at,updated_at,created_by,updated_by) VALUES (?,'V1',?,?,?,?,'PAID_CARD',"
                + "'PENDING_ACTIVATION',?,1,30,30,0,1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'TEST','TEST')",
                no, UUID.randomUUID().toString(), UUID.randomUUID().toString() + "a".repeat(28), recipient, entity,
                json.writeValueAsString(d));
    }
    private JsonNode read(String path) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
    }
    @Test void unifiedListAndInstanceRouteNeedNoClientSuppliedRecipientOrOwner() throws Exception {
        var list = read("/mp/entitlements?size=1");
        assertThat(list.path("code").asInt()).isZero();
        assertThat(list.path("data").path("records").get(0).path("instanceNo").asString()).isEqualTo(instance);
        var detail = read("/mp/entitlements/" + instance);
        assertThat(detail.path("code").asInt()).isZero();
        assertThat(detail.path("data").path("entitlement").path("remainingUnits").asInt()).isEqualTo(30);
        assertThat(detail.toString()).doesNotContain("principal", "bonus", "authorizationRef", "ruleSnapshot");
    }
    @Test void foreignRecipientAndWrongExplicitOwnerCannotBeReadThroughAnyRoute() throws Exception {
        String foreign = UUID.randomUUID().toString(); seed(foreign, "SOMEONE_ELSE");
        assertThat(read("/mp/entitlements/" + foreign).path("code").asInt()).isNotZero();
        assertThat(read("/mp/entitlements/" + instance + "?entityNo=OTHER").path("code").asInt()).isNotZero();
        assertThat(read("/mp/entitlements/" + foreign + "/allowances").path("code").asInt()).isNotZero();
    }
    @Test void activationQuoteAndAllowanceReadCannotActivateOrCreateUsageFacts() throws Exception {
        var response = mvc.perform(post("/mp/entitlements/" + instance + "/activation-quotes")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse();
        var quote = json.readTree(response.getContentAsString());
        assertThat(quote.path("code").asInt()).isZero();
        assertThat(quote.path("data").path("activationAction").asString()).isEqualTo("ON_FIRST_COMMIT");
        assertThat(read("/mp/entitlements/" + instance + "/allowances").path("data")
                .path("totalRemaining").asInt()).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT status FROM ent_instance WHERE instance_no=?", String.class, instance))
                .isEqualTo("PENDING_ACTIVATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_period_bucket WHERE instance_no=?", Integer.class,
                instance)).isZero();
    }
    @Test void merchantMustIdentifyTheCustomerAndCannotUseConsumerOrForeignStoreReferences() throws Exception {
        String store = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'query merchant','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity, user);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'query store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity);
        jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,"
                + "tenant_no,created_at,updated_at) VALUES (?,?,?,1,1,'ACTIVE','MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), entity, user);
        String merchant = TestLogin.merchantOwner(mvc, json, otp, phone);
        String memberCode = json.readTree(mvc.perform(post("/mp/member-code")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString())
                .path("data").path("memberCode").asString();
        String reference = json.readTree(mvc.perform(post("/biz/member-code/resolve")
                .header("Authorization", "Bearer " + merchant).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("memberCode", memberCode, "storeNo", store))))
                .andReturn().getResponse().getContentAsString()).path("data").path("customerRef").asString();
        var detail = mvc.perform(get("/biz/entitlements/" + instance).header("Authorization", "Bearer " + merchant)
                .param("customerRef", reference).param("storeNo", store)).andReturn().getResponse();
        assertThat(json.readTree(detail.getContentAsString()).path("code").asInt()).isZero();
        var wrongStore = mvc.perform(get("/biz/entitlements/" + instance)
                .header("Authorization", "Bearer " + merchant).param("customerRef", reference)
                .param("storeNo", "OTHER-STORE")).andReturn().getResponse();
        assertThat(json.readTree(wrongStore.getContentAsString()).path("code").asInt()).isNotZero();
        var denied = mvc.perform(get("/biz/entitlement-usage").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(denied.getStatus()).isEqualTo(401);
        var records = mvc.perform(get("/biz/entitlement-usage").header("Authorization", "Bearer " + merchant))
                .andReturn().getResponse();
        assertThat(json.readTree(records.getContentAsString()).path("code").asInt()).isZero();
    }
    @Test void historicalUsageLookupBindsTheSessionUserEvenWithoutAnOwnerParameter() throws Exception {
        String usage = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ent_usage(usage_no,operation_no,user_no,entity_no,store_no,store_name,"
                + "asset_kind,title,units,items_snapshot,operator_no,status,occurred_at,tenant_no,created_at,"
                + "updated_at,created_by,updated_by) VALUES (?,?,?,?,?,'store','PAID_CARD','swimming',1,"
                + "'[]','ACTOR','COMMITTED',?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'T','T')",
                usage, UUID.randomUUID().toString(), user, entity, "S1", LocalDateTime.now(ZoneOffset.UTC));
        var detail = read("/mp/entitlement-usage/" + usage);
        assertThat(detail.path("code").asInt()).isZero();
        assertThat(detail.path("data").path("usage").path("units").asInt()).isEqualTo(1);
        jdbc.update("UPDATE ent_usage SET user_no='OTHER' WHERE usage_no=?", usage);
        assertThat(read("/mp/entitlement-usage/" + usage).path("code").asInt()).isNotZero();
    }
    @Test void consumerQueriesRequireAuthenticationAndOptionalCodesRemainOff() throws Exception {
        assertThat(mvc.perform(get("/mp/entitlements")).andReturn().getResponse().getStatus()).isEqualTo(401);
        var response = mvc.perform(post("/mp/entitlements/" + instance + "/redeem-codes")
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", "code")
                .contentType(MediaType.APPLICATION_JSON).content("{\"useContext\":\"STORE_EXCHANGE\"}"))
                .andReturn().getResponse();
        assertThat(json.readTree(response.getContentAsString()).path("code").asInt()).isNotZero();
    }
}
