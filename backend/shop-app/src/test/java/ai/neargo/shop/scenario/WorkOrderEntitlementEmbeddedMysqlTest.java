package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.support.TestLogin;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Actual Core, Pay and beauty owners on MySQL. The catalog boundary supplies a stable published service. */
@SpringBootTest(properties = {
        "shop.beauty.enabled=true", "shop.beauty.deployment=embedded", "shop.beauty.migration.mode=migrate",
        "shop.member-code.enabled=true", "shop.core.entitlement.authorization.enabled=true",
        "shop.pay.entitlement.use.enabled=true", "shop.seed.enabled=false",
        "shop.outbox.sources.platform.mode=unified",
        // Reception notifications are outside this test; keep the real source journal without delivery.
        "shop.outbox.archive-only[0]=PLATFORM:BEAUTY_RECEPTION_STATE_CHANGED",
        "spring.sql.init.mode=never", "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.url=${B07_NATIVE_APP_URL}", "spring.datasource.username=root", "spring.datasource.password="})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "beauty.build.shape", matches = "embedded")
@EnabledIfEnvironmentVariable(named = "B07_NATIVE_APP_URL", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class WorkOrderEntitlementEmbeddedMysqlTest {
    @Autowired WebApplicationContext web;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenStores tokens;
    @Autowired OtpStore otp;
    @MockitoBean ServiceCatalogFactsPort catalog;
    private String entity;
    private String store;

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private String phone() {
        return "125" + String.format("%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100000000));
    }
    private JsonNode postJson(MockMvc mvc, String path, String token, Object body) throws Exception {
        var mvcResult = mvc.perform(post(path).header("Authorization", "Bearer " + token)
                .header(BizContextFilter.STORE_HEADER, store).header("Idempotency-Key", id())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andReturn();
        var result = mvcResult.getResponse();
        var reply = json.readTree(result.getContentAsString());
        if (reply.path("code").asInt() != 0) throw new AssertionError(path + " " + reply, mvcResult.getResolvedException());
        return reply.path("data");
    }

    @Test
    void actualOwnersBindThePersonallyConfirmedUsageAndLeaveTheTaskUnstarted() throws Exception {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("9.");
        var mvc = MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String ownerPhone = phone();
        String ownerToken = TestLogin.consumer(mvc, json, otp, ownerPhone);
        String owner = tokens.of(Realm.CONSUMER).get(ownerToken).orElseThrow().user().userNo();
        String buyer = TestLogin.consumer(mvc, json, otp, phone());
        String user = tokens.of(Realm.CONSUMER).get(buyer).orElseThrow().user().userNo();
        entity = id(); store = id();
        String goods = id(), sku = id(), instance = id();
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at)"
                + " VALUES (?,?,'Beauty','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity, owner);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at)"
                + " VALUES (?,?,'Beauty store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity);
        jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,tenant_no,"
                + "created_at,updated_at) VALUES (?,?,?,1,1,'ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id(), entity, owner);
        String merchant = TestLogin.merchantOwner(mvc, json, otp, ownerPhone);
        jdbc.update("INSERT INTO prd_goods(goods_no,entity_no,title,type,on_sale,audit_status,fulfillments,tenant_no,"
                + "created_at,updated_at) VALUES (?,?,'Service','SERVICE',1,'APPROVED','[\"STORE_VERIFY\"]',"
                + "'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", goods, entity);
        jdbc.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,tenant_no,"
                + "created_at,updated_at) VALUES (?,?,?,'CN',1000,0,0,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sku, goods, entity);
        var publicStore = new ServiceCatalogFactsPort.Store(entity, store, "Beauty", null, "ACTIVE", "always",
                "address", new StoreSalesCatalogPort.Snapshot(entity, store, null, 0, null, 0, null, false, null, null));
        when(catalog.store(store)).thenReturn(publicStore);
        when(catalog.service(store, sku)).thenReturn(new ServiceCatalogFactsPort.Service(publicStore, sku, goods,
                "Service", null, 1000, 17, 0L, 0L));
        definitions(sku, goods);
        String memberCode = postJson(mvc, "/mp/member-code", buyer, Map.of()).path("memberCode").asString();
        String reference = postJson(mvc, "/biz/member-code/resolve", merchant,
                Map.of("storeNo", store, "memberCode", memberCode)).path("customerRef").asString();
        var opened = postJson(mvc, "/biz/x/beauty/work-orders", merchant, Map.of("storeNo", store,
                "customerRef", reference, "items", List.of(Map.of("skuNo", sku, "qty", 1))));
        String workOrder = opened.path("workOrderNo").asString();
        String task = opened.path("taskNos").get(0).asString();
        assertThat(jdbc.queryForList("SELECT task_no,scope_revision,deleted FROM bty_service_task WHERE work_order_no=?", workOrder))
                .as("persisted original task").hasSize(1);


        var definition = new EntitlementDefinition("30 uses", "PAID_CARD", "MERCHANT", "CNY", "SERVICE_UNITS",
                null, null, null, null, null, "COUNT", 30, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "FIRST_USE", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("STORES", List.of(store)), List.of("PAID_ORDER"));
        jdbc.update("INSERT INTO ent_instance(instance_no,template_version_no,grant_no,source_unit_key,user_no,"
                + "entity_no,kind,status,rule_snapshot,unit_index,total_units,remaining_units,reserved_units,asset_seq,"
                + "tenant_no,created_at,updated_at,created_by,updated_by) VALUES (?,'V1',?,?,?,?,'PAID_CARD',"
                + "'PENDING_ACTIVATION',?,1,30,30,0,1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'TEST','TEST')",
                instance, id(), id() + id(), user, entity, json.writeValueAsString(definition));
        var intent = postJson(mvc, "/biz/entitlement-uses/work-order-intents", merchant,
                Map.of("storeNo", store, "workOrderNo", workOrder, "taskNo", task));
        String order = intent.path("serviceOrderNo").asString();
        var preview = postJson(mvc, "/biz/entitlement-uses/preview", merchant,
                Map.of("storeNo", store, "orderRef", order, "instanceNo", instance));
        var submit = Map.of("sourceRef", preview.path("sourceRef"),
                "previewRef", preview.path("preview").path("previewRef").asString(),
                "expectedAssetRevision", preview.path("preview").path("assetRevision").asLong());
        assertThat(postJson(mvc, "/biz/entitlement-uses", merchant, submit).path("status").asString())
                .isEqualTo("WAIT_AUTH");
        var ownReply = json.readTree(mvc.perform(get("/mp/entitlement-service-orders/" + order)
                .header("Authorization", "Bearer " + buyer)).andReturn().getResponse().getContentAsString());
        var confirmation = ownReply.path("data").path("confirmation");
        postJson(mvc, "/mp/orders/" + order + "/entitlement-authorizations", buyer, Map.of("orderRevision", 0,
                "quoteRef", confirmation.path("quoteRef").asString(),
                "selectionDigest", confirmation.path("selectionDigest").asString(), "requestKey", id()));
        var completed = postJson(mvc, "/biz/entitlement-uses", merchant, submit);
        assertThat(completed.path("status").asString()).isEqualTo("COMMITTED");
        assertThat(postJson(mvc, "/biz/entitlement-uses", merchant, submit)).isEqualTo(completed);
        String operation = completed.path("operationNo").asString(), usage = completed.path("usageNo").asString();
        String otherStore = id();
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at)"
                + " VALUES (?,?,'Other store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", otherStore, entity);
        var foreign = json.readTree(mvc.perform(get("/biz/x/beauty/work-orders/" + workOrder)
                .header("Authorization", "Bearer " + merchant).header(BizContextFilter.STORE_HEADER, otherStore))
                .andReturn().getResponse().getContentAsString());
        assertThat(foreign.path("code").asInt()).isNotZero();
        assertThat(jdbc.queryForObject("SELECT entitlement_usage_no FROM bty_service_submission "
                + "WHERE entity_no=? AND entitlement_operation_no=?", String.class, entity, operation)).isEqualTo(usage);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bty_trade_binding WHERE entity_no=? AND task_no=? "
                + "AND source_kind='SERVICE_USE' AND admission_kind='ENTITLEMENT_USE'",
                Integer.class, entity, task)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT allocated_qty FROM bty_source_allocation_guard "
                + "WHERE entity_no=? AND source_kind='SERVICE_USE' AND source_no=?",
                java.math.BigDecimal.class, entity, order)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isEqualTo(29);
        assertThat(jdbc.queryForObject("SELECT done_qty FROM bty_service_task WHERE task_no=?",
                java.math.BigDecimal.class, task)).isZero();
        assertThat(jdbc.queryForObject("SELECT started_at FROM bty_service_task WHERE task_no=?",
                java.sql.Timestamp.class, task)).isNull();
    }

    private void definitions(String sku, String goods) {
        String policy = id(), policyVersion = id(), definition = id(), version = id();
        seed("bty_store_policy", "policy_no", policy, "store_no", store,
                "current_policy_version_no", policyVersion, "status", "ACTIVE");
        var effective = LocalDateTime.of(2025, 1, 1, 0, 0);
        seed("bty_store_policy_version", "policy_version_no", policyVersion, "policy_no", policy,
                "revision_index", 1, "booking_ahead_days", 30,
                "cancel_rule", "{\"freeCancelBeforeMinutes\":60,\"allowCustomerCancel\":true}",
                "allowed_modes", "[\"PREPAID_SINGLE\"]", "effective_at", effective, "schema_version", 1,
                "snapshot_hash", IdempotencyScope.requestHash(effective.atOffset(ZoneOffset.UTC).toString(),
                        "30", "60", "true", "PREPAID_SINGLE"));
        seed("bty_service_definition", "definition_no", definition, "store_no", store, "sku_no", sku,
                "current_definition_version_no", version, "status", "ACTIVE");
        String duration = "{\"minutes\":17,\"bufferBeforeMin\":3,\"bufferAfterMin\":4}";
        String requirement = "[{\"roleCode\":\"MAIN\",\"resourceType\":\"STAFF\","
                + "\"skillGoodsNo\":\"" + goods + "\",\"qty\":1,\"sequenceNo\":0,\"offsetMin\":0,"
                + "\"durationMin\":17,\"selectionRule\":\"ANY_QUALIFIED\"}]";
        seed("bty_service_definition_version", "definition_no", definition, "definition_version_no", version,
                "revision_index", 1, "duration_min_snapshot", 17, "buffer_before_min", 3, "buffer_after_min", 4,
                "steps_template", "[{\"stepCode\":\"SERVICE\",\"sequenceNo\":0}]", "notes", "[]",
                "schema_version", 1, "snapshot_hash", IdempotencyScope.requestHash(sku, duration, requirement));
        seed("bty_service_requirement", "requirement_no", id(), "definition_version_no", version,
                "role_code", "MAIN", "resource_type", "STAFF", "skill_goods_no", goods, "qty", 1,
                "sequence_no", 0, "offset_min", 0, "duration_min", 17, "selection_rule", "ANY_QUALIFIED");
    }

    private void seed(String table, Object... fields) {
        var values = new LinkedHashMap<String, Object>();
        values.put("tenant_no", "MAIN"); values.put("entity_no", entity);
        values.put("created_at", LocalDateTime.now(ZoneOffset.UTC));
        values.put("updated_at", values.get("created_at")); values.put("created_by", "TEST");
        values.put("updated_by", "TEST"); values.put("version", 0); values.put("deleted", 0);
        for (int index = 0; index < fields.length; index += 2) values.put((String) fields[index], fields[index + 1]);
        jdbc.update("INSERT INTO " + table + " (" + String.join(",", values.keySet()) + ") VALUES ("
                + String.join(",", Collections.nCopies(values.size(), "?")) + ")", values.values().toArray());
    }
}
