package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.support.TestLogin;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@SpringBootTest(properties = {"shop.member-code.enabled=true", "shop.core.entitlement.authorization.enabled=true",
        "shop.pay.entitlement.use.enabled=true", "shop.pay.entitlement.code.enabled=true",
        "shop.pay.entitlement.code-key=test-only-code-key-at-least-thirty-two-characters",
        "spring.datasource.url=jdbc:h2:mem:service-public;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementServiceUseHttpFlowTest {
    @Autowired WebApplicationContext web;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenStores tokens;
    @Autowired OtpStore otp;
    private String id() { return UUID.randomUUID().toString(); }
    private String phone() { return "126" + String.format("%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100000000));
    }
    private JsonNode postJson(MockMvc mvc, String path, String token, Object body, String key) throws Exception {
        return json.readTree(mvc.perform(post(path).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn().getResponse().getContentAsString());
    }
    private JsonNode getJson(MockMvc mvc, String path, String token) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void memberOrOptionalLocatorStillRequiresConsentAndProducesOneRecord(boolean locator) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String ownerPhone = phone(), buyerPhone = phone();
        String ownerConsumer = TestLogin.consumer(mvc, json, otp, ownerPhone);
        String owner = tokens.of(Realm.CONSUMER).get(ownerConsumer).orElseThrow().user().userNo();
        String buyer = TestLogin.consumer(mvc, json, otp, buyerPhone);
        String user = tokens.of(Realm.CONSUMER).get(buyer).orElseThrow().user().userNo();
        String entity = id(), store = id(), goods = id(), sku = id(), instance = id();
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'beauty','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity, owner);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'beauty store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity);
        jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,"
                + "tenant_no,created_at,updated_at) VALUES (?,?,?,1,1,'ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id(), entity, owner);
        String merchant = TestLogin.merchantOwner(mvc, json, otp, ownerPhone);
        jdbc.update("INSERT INTO prd_goods(goods_no,entity_no,title,type,on_sale,audit_status,fulfillments,tenant_no,"
                + "created_at,updated_at) VALUES (?,?,'beauty service','SERVICE',1,'APPROVED','[\"STORE_VERIFY\"]',"
                + "'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", goods, entity);
        jdbc.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,price,stock,locked_stock,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,?,1000,0,0,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sku, goods, entity);
        String kind = locator ? "STORE_EXCHANGE" : "PAID_CARD";
        var rule = new EntitlementDefinition("30 times", kind, "MERCHANT", "CNY", "SERVICE_UNITS",
                null, null, null, null, null, "COUNT", 30, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "FIRST_USE", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("STORES", List.of(store)), List.of(locator ? "TARGETED" : "PAID_ORDER"));
        jdbc.update("INSERT INTO ent_instance(instance_no,template_version_no,grant_no,source_unit_key,user_no,"
                + "entity_no,kind,status,rule_snapshot,unit_index,total_units,remaining_units,reserved_units,asset_seq,"
                + "tenant_no,created_at,updated_at,created_by,updated_by) VALUES (?,'V1',?,?,?,?,?,"
                + "'PENDING_ACTIVATION',?,1,30,30,0,1,'MAIN',?,?,'TEST','TEST')", instance, id(), id() + "a".repeat(28),
                user, entity, kind, json.writeValueAsString(rule), LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
                LocalDateTime.now(ZoneOffset.UTC));
        String memberCode = postJson(mvc, "/mp/member-code", buyer, Map.of(), id()).path("data")
                .path("memberCode").asString();
        String reference = postJson(mvc, "/biz/member-code/resolve", merchant,
                Map.of("memberCode", memberCode, "storeNo", store), id()).path("data").path("customerRef").asString();
        String key = id();
        String credential = locator ? postJson(mvc, "/mp/entitlements/" + instance + "/redeem-codes", buyer,
                Map.of("useContext", "STORE_EXCHANGE"), id()).path("data").path("token").asString() : reference;
        var request = Map.of(locator ? "code" : "customerRef", credential, "storeNo", store, "instanceNo", instance,
                "items", List.of(Map.of("skuNo", sku, "quantity", 1)));
        var previewReply = postJson(mvc, "/biz/entitlement-uses/preview", merchant, request, key);
        assertThat(previewReply.has("code")).isTrue();
        assertThat(previewReply.path("code").asInt()).isZero();
        assertThat(previewReply.path("data").isObject()).isTrue();
        var preview = previewReply.path("data"); String operation = preview.path("operationNo").asString();
        String order = preview.path("orderRef").asString();
        if (locator) {
            jdbc.update("UPDATE ent_code_token SET expires_at=? WHERE instance_no=?",
                    LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), instance);
            var changed = Map.of("code", "ec1_" + "x".repeat(43), "storeNo", store, "instanceNo", instance,
                    "items", List.of(Map.of("skuNo", sku, "quantity", 1)));
            assertThat(postJson(mvc, "/biz/entitlement-uses/preview", merchant, changed, key).path("code").asInt())
                    .isNotZero();
        }
        assertThat(postJson(mvc, "/biz/entitlement-uses/preview", merchant, request, key).path("data"))
                .isEqualTo(preview);
        var pending = getJson(mvc, "/mp/entitlement-service-orders", buyer);
        assertThat(pending.path("data").get(0).path("serviceOrderNo").asString()).isEqualTo(order);
        var own = getJson(mvc, "/mp/entitlement-service-orders/" + order, buyer).path("data");
        assertThat(own.path("items").get(0).path("title").asString()).isEqualTo("beauty service");
        assertThat(getJson(mvc, "/mp/entitlement-service-orders/" + order, ownerConsumer).path("code").asInt())
                .isNotZero();
        var submit = Map.of("sourceRef", preview.path("sourceRef"),
                "previewRef", preview.path("preview").path("previewRef").asString(),
                "expectedAssetRevision", preview.path("preview").path("assetRevision").asLong());
        assertThat(postJson(mvc, "/biz/entitlement-uses", merchant, submit, id()).path("data").path("status").asString())
                .isEqualTo("WAIT_AUTH");
        var confirmation = own.path("confirmation");
        assertThat(postJson(mvc, "/mp/orders/" + order + "/entitlement-authorizations", buyer,
                Map.of("orderRevision", 0, "quoteRef", confirmation.path("quoteRef").asString(),
                        "selectionDigest", confirmation.path("selectionDigest").asString(), "requestKey", id()), id())
                .path("code").asInt()).isZero();
        assertThat(getJson(mvc, "/biz/entitlement-uses/" + operation, merchant).path("data").path("status").asString())
                .isEqualTo("WAIT_SUBMIT");
        var completed = postJson(mvc, "/biz/entitlement-uses", merchant, submit, id()).path("data");
        assertThat(completed.path("status").asString()).isEqualTo("COMMITTED");
        assertThat(postJson(mvc, "/biz/entitlement-uses", merchant, submit, id()).path("data")).isEqualTo(completed);
        var detail = getJson(mvc, "/mp/entitlement-usage/" + completed.path("usageNo").asString(), buyer).path("data");
        assertThat(detail.path("usage").path("units").asInt()).isEqualTo(1);
        assertThat(detail.path("remainingUnits").asInt()).isEqualTo(29);
        assertThat(detail.toString()).doesNotContain("principal", "bonus", "authorizationRef", "commandSnapshot");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_order WHERE order_no=?", Integer.class, order)).isZero();
    }
}
