package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.CreateBatch;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementOutboxRelay;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssuePolicyService;
import ai.neargo.shop.paybridge.EntitlementBatchPreparedConsumer;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementBatchPrepared;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventCodec;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import org.springframework.boot.test.web.server.LocalServerPort;
import ai.neargo.shop.support.TestLogin;
import java.util.List;
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
import tools.jackson.databind.ObjectMapper;

/** Real member audience, pay reservations, HTTP, durable bridge and retrying consumer; no success source mocks. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shop.services.internal-token=batch-wire-test","shop.pay.entitlement.catalog.enabled=true", "shop.pay.entitlement.issue.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:batch-public;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementBatchHttpFlowTest {
    @LocalServerPort int port;
    @Autowired WebApplicationContext web;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otp;
    @Autowired TokenStores tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberService members;
    @Autowired EntitlementCatalogService catalog;
    @Autowired EntitlementIssuePolicyService policies;
    @Autowired PayEntitlementOutboxRelay relay;
    @Autowired EntitlementBatchPreparedConsumer consumer;
    private String id() { return UUID.randomUUID().toString(); }
    @Test void createFreezesRealAudienceAndDurableDispatchRecoversWithoutRepeatingIssuance() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String phone = "126" + String.format("%08d",
                Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100000000));
        String buyer = TestLogin.consumer(mvc, json, otp, phone);
        String user = tokens.of(Realm.CONSUMER).get(buyer).orElseThrow().user().userNo();
        String entity = id(), store = id();
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'batch merchant','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity, user);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'batch store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity);
        jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,"
                + "tenant_no,created_at,updated_at) VALUES (?,?,?,1,1,'ACTIVE','MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id(), entity, user);
        String merchant = TestLogin.merchantOwner(mvc, json, otp, phone);
        members.enroll(entity, phone, null, List.of(), store, user);
        var scope = new CatalogScope("MAIN", entity, user, false);
        var definition = new EntitlementDefinition("targeted", "ORDER_COUPON", "MERCHANT", "CNY", "CASH", 100L,
                null, null, 0L, null, "COUNT", 1, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("ALL", List.of()), List.of("TARGETED"));
        var head = catalog.create(scope, definition, id());
        var version = catalog.publish(scope, head.templateNo(), head.revision(), id());
        policies.save(scope, head.templateNo(), new SavePolicy(0, 10L, 1000L, 1, true), id());
        String body = json.writeValueAsString(new CreateBatch(version.versionNo(), "@ALL"));
        String key = "long-original-key-" + "k".repeat(80);
        var response = mvc.perform(post("/biz/entitlement-issue-batches").header("Authorization", "Bearer " + merchant)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        var accepted = json.readTree(response.getContentAsString()).path("data");
        assertThat(accepted.path("plannedCount").asInt()).isEqualTo(1);
        String batch = accepted.path("batchNo").asString();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE entity_no=?", Integer.class, entity))
                .isZero();
        var query = mvc.perform(get("/biz/entitlement-issue-batches/" + batch)
                .header("Authorization", "Bearer " + merchant)).andReturn().getResponse();
        assertThat(json.readTree(query.getContentAsString()).path("data").path("pendingCount").asInt()).isEqualTo(1);
        String eventNo = jdbc.queryForObject("SELECT event_no FROM pay_ent_outbox WHERE aggregate_no=? "
                + "AND event_type='ENTITLEMENT_BATCH_PREPARED'", String.class, batch);
        relay.relay(eventNo);
        var locator = new ConfigServiceLocator();
        locator.getTargets().put(ServiceName.PLATFORM, "http://127.0.0.1:" + port);
        var api = new InternalHttp(locator, "batch-wire-test").client(ServiceName.PLATFORM,
                CoreEntitlementInternalApi.class, Duration.ofSeconds(5));
        var fact = json.readValue(jdbc.queryForObject("SELECT payload FROM pay_ent_outbox WHERE event_no=?",
                String.class, eventNo), EntitlementBatchPrepared.class);
        assertThat(api.receiveEvent(PayEntitlementEventCodec.encode(fact, json)).accepted()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE event_no=?", Integer.class, eventNo))
                .isEqualTo(1);
        var event = new SysOutbox(); event.setEventNo(eventNo);
        event.setPayload(jdbc.queryForObject("SELECT payload FROM sys_outbox WHERE event_no=?", String.class, eventNo));
        consumer.consume(event); consumer.consume(event);
        var finished = mvc.perform(get("/biz/entitlement-issue-batches/" + batch)
                .header("Authorization", "Bearer " + merchant)).andReturn().getResponse();
        var result = json.readTree(finished.getContentAsString()).path("data");
        assertThat(result.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(result.path("issuedCount").asInt()).isEqualTo(1);
        assertThat(result.path("results").get(0).path("userNo").asString()).isEqualTo(user);
        var replay = mvc.perform(post("/biz/entitlement-issue-batches").header("Authorization", "Bearer " + merchant)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        assertThat(json.readTree(replay.getContentAsString()).path("data")).isEqualTo(accepted);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE entity_no=?", Integer.class, entity))
                .isEqualTo(1);
        assertThat(mvc.perform(post("/biz/entitlement-issue-batches").header("Authorization", "Bearer " + buyer)
                .header("Idempotency-Key", "denied").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
