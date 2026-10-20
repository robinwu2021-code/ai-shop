package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCreditService;
import ai.neargo.shop.support.StoredValueCreditFixture;
import ai.neargo.shop.support.TestLogin;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

@SpringBootTest(properties = {"shop.member-code.enabled=true", "shop.core.entitlement.authorization.enabled=true",
        "shop.pay.stored-value.enabled=true", "shop.pay.entitlement.use.enabled=true", "shop.pay.entitlement.code.enabled=true",
        "shop.pay.entitlement.code-key=test-only-code-key-at-least-thirty-two-characters",
        "spring.datasource.url=jdbc:h2:mem:sv-public;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class StoredValueConsumptionHttpFlowTest {
    @Autowired WebApplicationContext web;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenStores tokens;
    @Autowired OtpStore otp;
    @Autowired StoredValueCreditService credits;
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
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
    @Test
    void merchantPricedServiceRequiresConsumerConsentAndAppearsInConsumerHistory() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String ownerPhone = phone(), buyerPhone = phone();
        String ownerConsumer = TestLogin.consumer(mvc, json, otp, ownerPhone);
        String owner = tokens.of(Realm.CONSUMER).get(ownerConsumer).orElseThrow().user().userNo();
        String buyer = TestLogin.consumer(mvc, json, otp, buyerPhone);
        String user = tokens.of(Realm.CONSUMER).get(buyer).orElseThrow().user().userNo();
        String entity = id(), store = id(), goods = id(), sku = id();
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
        var ctx = new EntitlementContext("MAIN", owner, id(), "a".repeat(64));
        credits.credit(ctx, StoredValueCreditFixture.received(jdbc, json,
                user, entity, store, 1000, 100));
        String memberCode = postJson(mvc, "/mp/member-code", buyer, Map.of(), id()).path("data")
                .path("memberCode").asString();
        String ref = postJson(mvc, "/biz/member-code/resolve", merchant,
                Map.of("memberCode", memberCode, "storeNo", store), id()).path("data").path("customerRef").asString();
        var balance = getJson(mvc, "/biz/stored-value/accounts?customerRef=" + ref + "&storeNo=" + store, merchant);
        assertThat(balance.path("code").asInt()).isZero();
        assertThat(balance.path("data").path("balanceMinor").asLong()).isEqualTo(1100);
        assertThat(balance.path("data").toString()).doesNotContain("accountNo", "principal", "bonus");
        var previewRequest = Map.of("customerRef", ref, "storeNo", store,
                "items", List.of(Map.of("skuNo", sku, "quantity", 1)));
        String previewKey = id();
        var reply = postJson(mvc, "/biz/stored-value/consumptions/preview", merchant, previewRequest, previewKey);
        assertThat(reply.path("code").asInt()).as(reply.toString()).isZero();
        var preview = reply.path("data");
        String order = preview.path("service").path("serviceOrderNo").asString();
        var confirmation = preview.path("service").path("confirmation");
        var auth = postJson(mvc, "/mp/orders/" + order + "/entitlement-authorizations", buyer,
                Map.of("orderRevision", 0, "quoteRef", confirmation.path("quoteRef").asString(),
                        "selectionDigest", confirmation.path("selectionDigest").asString(), "requestKey", id()), id());
        assertThat(auth.path("code").asInt()).as(auth.toString()).isZero();
        var command = Map.of("customerRef", ref, "orderNo", order, "orderRevision", 0,
                "authorizationRef", auth.path("data").path("authorizationRef").asString());
        var used = postJson(mvc, "/biz/stored-value/consumptions", merchant, command, id());
        assertThat(used.path("code").asInt()).as(used.toString()).isZero();
        assertThat(used.path("data").path("status").asString()).isEqualTo("COMMITTED");
        var expiredAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        jdbc.update("UPDATE mbr_identity_ticket SET issued_at=?,expires_at=? WHERE user_no=?",
                expiredAt.minusMinutes(1), expiredAt, user);
        assertThat(postJson(mvc, "/biz/stored-value/consumptions", merchant, command, id()).path("data"))
                .isEqualTo(used.path("data"));
        var replay = postJson(mvc, "/biz/stored-value/consumptions/preview", merchant, previewRequest, previewKey);
        assertThat(replay.path("code").asInt()).as(replay.toString()).isZero();
        assertThat(replay.path("data").path("quote").path("quoteRef"))
                .isEqualTo(preview.path("quote").path("quoteRef"));
        assertThat(postJson(mvc, "/biz/stored-value/consumptions/preview", merchant, previewRequest, id())
                .path("code").asInt()).isNotZero();
        var stores = getJson(mvc, "/mp/stored-value/stores", buyer);
        assertThat(stores.path("data").path("records").toString()).contains(store);
        String historyPath = "/mp/stored-value/consumptions?entityNo=" + entity + "&storeNo=" + store;
        var history = getJson(mvc, historyPath, buyer);
        assertThat(history.path("data").path("records").size()).isEqualTo(1);
        assertThat(history.path("data").path("records").get(0).path("amountMinor").asLong()).isEqualTo(1000);
        assertThat(history.path("data").toString()).doesNotContain("principal", "bonus", "authorizationRef");
        var allStores = getJson(mvc, "/mp/stored-value/consumptions?entityNo=" + entity, buyer);
        assertThat(allStores.path("code").asInt()).as(allStores.toString()).isZero();
        assertThat(allStores.path("data")).isEqualTo(history.path("data"));
        assertThat(getJson(mvc, "/mp/stored-value/consumptions?entityNo=" + entity, ownerConsumer)
                .path("data").path("records").size()).isZero();
        assertThat(getJson(mvc, historyPath, ownerConsumer).path("data").path("records").size()).isZero();
    }
}
