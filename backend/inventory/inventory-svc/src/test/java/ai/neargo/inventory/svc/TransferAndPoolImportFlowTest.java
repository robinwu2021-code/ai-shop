package ai.neargo.inventory.svc;

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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 上线前补齐（TDD §2.21，AC-39 ~ AC-42）：宿主接口的账内调拨（SN 随件走、组装与凭证跟着走），
 * 以及导入「已发出未签收」的件进出库池。
 */
@SpringBootTest(properties = {
        "inventory.flyway-locations=classpath:db/inventory-h2",
        "inventory.datasource.url=jdbc:h2:mem:inv_transfer_pool;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "inventory.datasource.username=sa",
        "inventory.datasource.password=",
        "inventory.instance.code=TEST",
        "inventory.instance.allowed-systems=SHOP,SHAREHUB,CHARGING",
        "inventory.admin.token=admin-test-token"})
class TransferAndPoolImportFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROFILE = """
            {"schemaVersion":1,"capabilities":["SERIAL","DISPATCH_POOL"],
             "targetTypes":[{"code":"MERCHANT","nameKey":"m","parentType":null},
                            {"code":"SITE","nameKey":"s","parentType":"MERCHANT"},
                            {"code":"PERSON","nameKey":"p","parentType":null}],
             "inboundPurposes":[{"code":"PURCHASE","basisRequired":false,"attachmentRequired":false},
                                {"code":"OPENING","basisRequired":true,"attachmentRequired":true}],
             "outboundPurposes":[{"code":"ISSUE","requiredTargets":["MERCHANT","PERSON"],
                                  "optionalTargets":["SITE"],"handover":"POOL"}],
             "gateRefTypes":["QC"],"countReview":{"required":false,"reviewerMustDiffer":false}}""";

    @Autowired WebApplicationContext context;
    @Autowired List<FilterRegistrationBean<?>> filters;

    MockMvc mvc;
    String owner;
    String bearer;
    String cabinet;
    String powerbank;
    String cable;
    String whA;
    String whB;

    @BeforeEach
    void setUp() throws Exception {
        var builder = MockMvcBuilders.webAppContextSetup(context);
        filters.forEach(f -> builder.addFilters(f.getFilter()));
        mvc = builder.build();
        owner = id(admin(post("/api/inventory/admin/v1/owners").content("{\"systemCode\":\"SHAREHUB\",\"externalRef\":\""
                + UUID.randomUUID() + "\",\"name\":\"运营方\"}")), "ownerId");
        admin(put("/api/inventory/admin/v1/owners/" + owner + "/profile")
                .content("{\"profile\":" + PROFILE + ",\"reason\":\"init\"}")).andExpect(status().isOk());
        JsonNode issued = JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners/" + owner + "/credentials")
                .content("{\"name\":\"t\"}")).andExpect(status().isOk()).andReturn()));
        bearer = "Bearer " + issued.get("appKey").asString() + ":" + issued.get("appSecret").asString();
        cabinet = id(write(put("/api/inventory/v1/items/by-ref/CAB").content(
                "{\"name\":\"柜\",\"trackingMode\":\"SERIAL\",\"gatePolicy\":\"REF_REQUIRED\",\"gateRefType\":\"QC\"}")),
                "itemId");
        powerbank = id(write(put("/api/inventory/v1/items/by-ref/PB").content(
                "{\"name\":\"宝\",\"trackingMode\":\"SERIAL\",\"gatePolicy\":\"REF_REQUIRED\",\"gateRefType\":\"QC\"}")),
                "itemId");
        cable = id(write(put("/api/inventory/v1/items/by-ref/CABLE").content("{\"name\":\"线\"}")), "itemId");
        whA = id(write(post("/api/inventory/v1/warehouses").content("{\"name\":\"A\",\"externalRef\":\"WH-A\"}")),
                "locationId");
        whB = id(write(post("/api/inventory/v1/warehouses").content("{\"name\":\"B\",\"externalRef\":\"WH-B\"}")),
                "locationId");
        for (String t : List.of("MERCHANT/AG001:{\"name\":\"商家甲\"}", "SITE/ST01:{\"name\":\"站点\",\"parentRef\":\"AG001\"}",
                "PERSON/E1001:{\"name\":\"运维甲\"}")) {
            String[] kv = t.split(":", 2);
            write(put("/api/inventory/v1/external-targets/" + kv[0]).content(kv[1])).andExpect(status().isOk());
        }
    }

    @Test
    void transferMovesQuantitiesAndWholeKitsThroughTransitKeepingAssemblyAndGates() throws Exception {
        String in = id(write(post("/api/inventory/v1/inbounds").content(("{\"locationId\":\"%s\",\"purpose\":\"PURCHASE\","
                + "\"lines\":[{\"itemId\":\"%s\",\"qty\":5},{\"itemId\":\"%s\",\"serials\":[{\"serialNo\":\"C1\",\"gateRef\":\"QC-C1\"}]},"
                + "{\"itemId\":\"%s\",\"serials\":[{\"serialNo\":\"P1\",\"gateRef\":\"QC-P1\",\"parentSerialNo\":\"C1\"}]}]}")
                .formatted(whA, cable, cabinet, powerbank))), "docNo");
        write(post("/api/inventory/v1/inbounds/" + in + "/post")).andExpect(status().isOk());

        // AC-39：建单（扫柜带出宝）→ 发运：离开 A、进在途，组装与凭证跟着走
        String no = id(write(post("/api/inventory/v1/transfers").content(("{\"fromLocationId\":\"%s\",\"toLocationId\":\"%s\","
                + "\"lines\":[{\"itemId\":\"%s\",\"qty\":2}],\"serialNos\":[\"C1\"]}").formatted(whA, whB, cable))), "docNo");
        JsonNode draft = JSON.readTree(body(read("/api/inventory/v1/transfers/" + no)));
        assertThat(draft.toString()).contains("C1", "P1");
        write(post("/api/inventory/v1/transfers/" + no + "/ship")).andExpect(status().isOk());
        assertOnHand(cable, whA, "3");
        assertThat(serialsAt(whA)).doesNotContain("C1", "P1");
        JsonNode transit = JSON.readTree(body(read("/api/inventory/v1/in-transit")));
        assertThat(transit.findValuesAsString("kind")).contains("TRANSFER");
        assertThat(transit.findValuesAsString("docNo")).contains(no);
        write(post("/api/inventory/v1/transfers/" + no + "/cancel")).andExpect(status().isConflict());

        // AC-40：收货：进 B、仍组装、在途清零
        write(post("/api/inventory/v1/transfers/" + no + "/receive")).andExpect(status().isOk());
        assertOnHand(cable, whB, "2");
        assertThat(serialsAt(whB)).contains("C1", "P1");
        JsonNode inB = JSON.readTree(body(read("/api/inventory/v1/serials?locationId=" + whB)));
        for (JsonNode s : inB) {
            if ("P1".equals(s.get("serialNo").asString())) {
                assertThat(s.get("parentSerialNo").asString()).isEqualTo("C1");
                assertThat(s.get("gateRef").asString()).isEqualTo("QC-P1");
            }
        }
        assertThat(JSON.readTree(body(read("/api/inventory/v1/in-transit"))).findValuesAsString("kind"))
                .doesNotContain("TRANSFER");
        assertThat(JSON.readTree(body(read("/api/inventory/v1/transfers/" + no))).get("status").asString())
                .isEqualTo("RECEIVED");
        write(post("/api/inventory/v1/transfers/" + no + "/receive")).andExpect(status().isConflict());

        // 草稿可作废；空单、同库位拒绝
        String other = id(write(post("/api/inventory/v1/transfers").content(("{\"fromLocationId\":\"%s\","
                + "\"toLocationId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"qty\":1}]}").formatted(whB, whA, cable))),
                "docNo");
        write(post("/api/inventory/v1/transfers/" + other + "/cancel")).andExpect(status().isOk());
        write(post("/api/inventory/v1/transfers").content("{\"fromLocationId\":\"%s\",\"toLocationId\":\"%s\"}"
                .formatted(whA, whB))).andExpect(status().isBadRequest());
        write(post("/api/inventory/v1/transfers").content(("{\"fromLocationId\":\"%s\",\"toLocationId\":\"%s\","
                + "\"lines\":[{\"itemId\":\"%s\",\"qty\":1}]}").formatted(whA, whA, cable)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importPutsDispatchedButUnclaimedKitsIntoThePoolOnceAndReportsConflicts() throws Exception {
        String batch = """
                {"batchId":"MIG-POOL","pool":[{"warehouseRef":"WH-A",
                  "targets":{"MERCHANT":"AG001","SITE":"ST01","PERSON":"E1001"},
                  "serials":[{"itemRef":"CAB","serialNo":"C9","gateRef":"QC-C9"},
                             {"itemRef":"PB","serialNo":"P9","parentSerialNo":"C9"}]}]}""";
        // AC-41：预演只报告
        JsonNode dry = importBatch(batch, false);
        assertThat(dry.get("poolToIssue").toString()).contains("C9", "P9");
        assertThat(JSON.readTree(body(read("/api/inventory/v1/dispatch-pool?status=AVAILABLE")))).isEmpty();

        // 执行：整套进池待签收，余额不变（入了又出）
        JsonNode applied = importBatch(batch, true);
        assertThat(applied.get("outboundNos")).hasSize(1);
        JsonNode pool = JSON.readTree(body(read("/api/inventory/v1/dispatch-pool?status=AVAILABLE")));
        assertThat(pool.findValuesAsString("serialNo")).containsExactlyInAnyOrder("C9", "P9");
        assertThat(pool.findValuesAsString("rootTargetRef")).containsOnly("AG001");
        assertThat(serialsAt(whA)).doesNotContain("C9", "P9");

        // 再跑为空：不重复入池
        JsonNode again = importBatch(batch, true);
        assertThat(again.get("poolToIssue")).isEmpty();
        assertThat(again.get("outboundNos")).isEmpty();
        assertThat(JSON.readTree(body(read("/api/inventory/v1/dispatch-pool?status=AVAILABLE")))).hasSize(2);

        // AC-42：还在仓里的 SN、没有 SN 的池行 → 冲突，执行整批 409
        String in = id(write(post("/api/inventory/v1/inbounds").content(("{\"locationId\":\"%s\",\"purpose\":\"PURCHASE\","
                + "\"lines\":[{\"itemId\":\"%s\",\"serials\":[{\"serialNo\":\"C8\",\"gateRef\":\"QC-C8\"}]}]}")
                .formatted(whA, cabinet))), "docNo");
        write(post("/api/inventory/v1/inbounds/" + in + "/post")).andExpect(status().isOk());
        String bad = """
                {"batchId":"MIG-BAD","pool":[{"warehouseRef":"WH-A","targets":{"MERCHANT":"AG001","PERSON":"E1001"},
                  "serials":[{"itemRef":"CAB","serialNo":"C8","gateRef":"QC-C8"}]},
                  {"warehouseRef":"WH-A","targets":{"MERCHANT":"AG001","PERSON":"E1001"},"serials":[]}]}""";
        assertThat(importBatch(bad, false).get("conflicts").toString())
                .contains("C8:in_stock", "WH-A:pool.serials_required");
        admin(post("/api/inventory/admin/v1/owners/" + owner + "/imports?apply=true").content(bad))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("inventory.conflict"));
        assertThat(serialsAt(whA)).contains("C8");
    }

    @Test
    void hostNumbersWorkWhereverInternalIdsDoAndQueriesReturnBoth() throws Exception {
        // AC-43：只用宿主编号建入库、出库、调拨、开盘点，查余额 / SN / 流水
        String in = id(write(post("/api/inventory/v1/inbounds").content("""
                {"locationRef":"WH-A","purpose":"PURCHASE","lines":[{"itemRef":"CABLE","qty":6},
                 {"itemRef":"CAB","serials":[{"serialNo":"C5","gateRef":"QC-C5"}]}]}""")), "docNo");
        write(post("/api/inventory/v1/inbounds/" + in + "/post")).andExpect(status().isOk());
        String no = id(write(post("/api/inventory/v1/transfers").content("""
                {"fromLocationRef":"WH-A","toLocationRef":"WH-B","lines":[{"itemRef":"CABLE","qty":2}]}""")), "docNo");
        write(post("/api/inventory/v1/transfers/" + no + "/ship")).andExpect(status().isOk());
        write(post("/api/inventory/v1/transfers/" + no + "/receive")).andExpect(status().isOk());
        JsonNode atB = JSON.readTree(body(read("/api/inventory/v1/balances?itemRef=CABLE&locationRef=WH-B")));
        assertThat(atB).hasSize(1);
        assertThat(atB.get(0).get("itemRef").asString()).isEqualTo("CABLE");
        assertThat(atB.get(0).get("locationRef").asString()).isEqualTo("WH-B");
        assertThat(atB.get(0).get("itemId").asString()).isEqualTo(cable);
        assertThat(new BigDecimal(atB.get(0).get("onHand").asString())).isEqualByComparingTo("2");
        JsonNode sn = JSON.readTree(body(read("/api/inventory/v1/serials?itemRef=CAB&locationRef=WH-A")));
        assertThat(sn.findValuesAsString("serialNo")).containsExactly("C5");
        assertThat(sn.get(0).get("itemRef").asString()).isEqualTo("CAB");
        assertThat(JSON.readTree(body(read("/api/inventory/v1/ledger?itemRef=CABLE&locationRef=WH-B")))).hasSize(1);
        write(post("/api/inventory/v1/holds").content("""
                {"itemRef":"CABLE","locationRef":"WH-A","qty":1,"note":"t"}""")).andExpect(status().isOk());
        String count = id(write(post("/api/inventory/v1/counts").content("""
                {"locationRef":"WH-B","itemRefs":["CABLE"]}""")), "docNo");
        assertThat(body(read("/api/inventory/v1/counts/" + count))).contains(cable);

        // AC-45：编号不存在 404，什么都没给 400
        write(post("/api/inventory/v1/outbounds").content("""
                {"locationRef":"WH-X","purpose":"ISSUE","lines":[]}""")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.args[0]").value("locationRef:WH-X"));
        write(post("/api/inventory/v1/inbounds").content("""
                {"locationRef":"WH-A","purpose":"PURCHASE","lines":[{"itemRef":"NOPE","qty":1}]}"""))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.data.args[0]").value("itemRef:NOPE"));
        write(post("/api/inventory/v1/inbounds").content("""
                {"purpose":"PURCHASE","lines":[{"itemRef":"CABLE","qty":1}]}"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.args[0]").value("locationId"));
        mvc.perform(get("/api/inventory/v1/balances?itemRef=NOPE").header("Authorization", bearer))
                .andExpect(status().isNotFound());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private List<String> serialsAt(String location) throws Exception {
        return JSON.readTree(body(read("/api/inventory/v1/serials?locationId=" + location)))
                .findValuesAsString("serialNo");
    }

    private void assertOnHand(String item, String location, String expected) throws Exception {
        JsonNode rows = JSON.readTree(body(read("/api/inventory/v1/balances?itemId=" + item + "&locationId=" + location)));
        BigDecimal onHand = BigDecimal.ZERO;
        for (JsonNode r : rows) {
            onHand = onHand.add(new BigDecimal(r.get("onHand").asString()));
        }
        assertThat(onHand).isEqualByComparingTo(expected);
    }

    private JsonNode importBatch(String batch, boolean apply) throws Exception {
        return JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners/" + owner + "/imports?apply=" + apply)
                .content(batch)).andExpect(status().isOk()).andReturn()));
    }

    private ResultActions write(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).header("Authorization", bearer)
                .header("Idempotency-Key", "K-" + UUID.randomUUID()).header("X-Inventory-Actor", "STAFF:E1"));
    }

    private MvcResult read(String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", bearer)).andExpect(status().isOk()).andReturn();
    }

    private ResultActions admin(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON)
                .header("X-Inventory-Admin-Token", "admin-test-token").header("X-Inventory-Actor", "OPS:test"));
    }

    private static String id(ResultActions r, String field) throws Exception {
        MvcResult result = r.andExpect(status().isOk()).andReturn();
        return JSON.readTree(body(result)).get(field).asString();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }
}
