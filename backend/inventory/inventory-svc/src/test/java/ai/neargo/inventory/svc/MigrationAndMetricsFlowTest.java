package ai.neargo.inventory.svc;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** M6：旧仓储数据导入（预演 → 执行 → 重复执行无变化）与监控指标。 */
@SpringBootTest(properties = {
        "inventory.flyway-locations=classpath:db/inventory-h2",
        "inventory.datasource.url=jdbc:h2:mem:inv_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "inventory.datasource.username=sa",
        "inventory.datasource.password=",
        "inventory.instance.code=DEVICE",
        "inventory.instance.allowed-systems=SHAREHUB,CHARGING",
        "inventory.admin.token=admin-test-token"})
class MigrationAndMetricsFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROFILE = """
            {"schemaVersion":1,"capabilities":["SERIAL","DISPATCH_POOL"],
             "targetTypes":[{"code":"MERCHANT","nameKey":"m","parentType":null},
                            {"code":"SITE","nameKey":"s","parentType":"MERCHANT"},
                            {"code":"PERSON","nameKey":"p","parentType":null}],
             "inboundPurposes":[{"code":"OPENING","basisRequired":true,"attachmentRequired":true}],
             "outboundPurposes":[{"code":"SCRAP","requiredTargets":[],"optionalTargets":[],"handover":"NONE"}],
             "gateRefTypes":["QC"],"countReview":{"required":false,"reviewerMustDiffer":false}}""";
    private static final String BATCH = """
            {"batchId":"MIG-1",
             "warehouses":[{"ref":"WH-SZ","name":"深圳仓","regionRef":"R-SZ"}],
             "items":[{"ref":"CAB","spec":{"name":"柜","trackingMode":"SERIAL","gatePolicy":"REF_REQUIRED","gateRefType":"QC"}},
                      {"ref":"CABLE","spec":{"name":"线"}}],
             "targets":[{"type":"SITE","ref":"ST01","name":"站点一","parentRef":"AG001"},
                        {"type":"MERCHANT","ref":"AG001","name":"商家甲"}],
             "stock":[{"warehouseRef":"WH-SZ","itemRef":"CAB",
                       "serials":[{"serialNo":"M-C1","gateRef":"QC-M1"},{"serialNo":"M-C2","gateRef":"QC-M2"}]},
                      {"warehouseRef":"WH-SZ","itemRef":"CABLE","qty":30}]}""";

    @Autowired WebApplicationContext context;
    @Autowired List<FilterRegistrationBean<?>> filters;
    @Autowired MeterRegistry registry;

    MockMvc mvc;
    String owner;
    String bearer;

    @BeforeEach
    void setUp() throws Exception {
        var builder = MockMvcBuilders.webAppContextSetup(context);
        filters.forEach(f -> builder.addFilters(f.getFilter()));
        mvc = builder.build();
        owner = JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners").content(
                "{\"systemCode\":\"SHAREHUB\",\"externalRef\":\"" + UUID.randomUUID() + "\",\"name\":\"n\"}"))
                .andExpect(status().isOk()).andReturn())).get("ownerId").asString();
        admin(put("/api/inventory/admin/v1/owners/" + owner + "/profile")
                .content("{\"profile\":" + PROFILE + ",\"reason\":\"init\"}")).andExpect(status().isOk());
        JsonNode issued = JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners/" + owner + "/credentials")
                .content("{\"name\":\"t\"}")).andExpect(status().isOk()).andReturn()));
        bearer = "Bearer " + issued.get("appKey").asString() + ":" + issued.get("appSecret").asString();
    }

    @Test
    void dryRunReportsThenApplyImportsThenRerunIsANoOp() throws Exception {
        JsonNode dry = importBatch(BATCH, false);
        assertThat(dry.get("applied").asBoolean()).isFalse();
        assertThat(dry.get("warehousesToCreate").get(0).asString()).isEqualTo("WH-SZ");
        assertThat(dry.get("itemsToCreate")).hasSize(2);
        assertThat(dry.get("targetsToUpsert")).hasSize(2);
        assertThat(dry.get("stock")).hasSize(2);
        // 预演不落任何东西
        assertThat(JSON.readTree(body(read("/api/inventory/v1/items")))).isEmpty();

        JsonNode applied = importBatch(BATCH, true);
        assertThat(applied.get("inboundNos")).hasSize(1);
        assertThat(JSON.readTree(body(read("/api/inventory/v1/serials"))).findValuesAsString("serialNo"))
                .containsExactlyInAnyOrder("M-C1", "M-C2");
        JsonNode inbound = JSON.readTree(body(read("/api/inventory/v1/inbounds/" + applied.at("/inboundNos/0").asString())));
        assertThat(inbound.get("purpose").asString()).isEqualTo("OPENING");
        assertThat(JSON.readTree(body(read("/api/inventory/v1/external-targets?parentRef=AG001")))
                .findValuesAsString("ref")).containsExactly("ST01");

        // 重复执行：差异为空，什么都不再建
        JsonNode again = importBatch(BATCH, true);
        assertThat(again.get("inboundNos")).isEmpty();
        assertThat(again.get("stock")).isEmpty();
        assertThat(again.get("itemsToCreate")).isEmpty();
        assertThat(again.get("warehousesToCreate")).isEmpty();
    }

    @Test
    void batchBelowBookIsAConflictNotASilentLoss() throws Exception {
        importBatch(BATCH, true);
        String fewer = BATCH.replace("\"MIG-1\"", "\"MIG-2\"").replace("\"qty\":30", "\"qty\":20");
        JsonNode dry = importBatch(fewer, false);
        assertThat(dry.get("conflicts").get(0).asString()).isEqualTo("WH-SZ/CABLE:book_exceeds_batch");
        admin(post("/api/inventory/admin/v1/owners/" + owner + "/imports?apply=true").content(fewer))
                .andExpect(status().isConflict());
    }

    @Test
    void metricsExposeEventBacklogAndErrorsByCode() throws Exception {
        importBatch(BATCH, true);
        double pending = registry.get("inventory.events.pending").tag("instance", "DEVICE").gauge().value();
        assertThat(pending).isGreaterThan(0);
        mvc.perform(get("/api/inventory/v1/events").header("Authorization", bearer)).andExpect(status().isOk());
        assertThat(registry.get("inventory.events.pending").gauge().value()).isZero();
        assertThat(registry.get("inventory.events.unacked").gauge().value()).isGreaterThan(0);

        mvc.perform(get("/api/inventory/v1/inbounds/NOPE").header("Authorization", bearer))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("inventory.not_found"));
        assertThat(registry.get("inventory.errors").tag("code", "inventory.not_found").counter().count())
                .isGreaterThanOrEqualTo(1);
    }

    private JsonNode importBatch(String batch, boolean apply) throws Exception {
        return JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners/" + owner + "/imports?apply=" + apply)
                .content(batch)).andExpect(status().isOk()).andReturn()));
    }

    private MvcResult read(String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", bearer)).andExpect(status().isOk()).andReturn();
    }

    private ResultActions admin(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON)
                .header("X-Inventory-Admin-Token", "admin-test-token").header("X-Inventory-Actor", "OPS:test"));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }
}
