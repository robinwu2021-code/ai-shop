package ai.neargo.inventory.svc;

import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.HostInboundService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3 端到端：按 SN 入库与出库（AC-01 / AC-02 / AC-04 / AC-24）、放行凭证、组装与扫柜码展开、处置出库与收货方规则。
 */
@SpringBootTest(properties = {
        "inventory.flyway-locations=classpath:db/inventory-h2",
        "inventory.datasource.url=jdbc:h2:mem:inv_serial;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "inventory.datasource.username=sa",
        "inventory.datasource.password=",
        "inventory.instance.code=TEST",
        "inventory.instance.allowed-systems=SHOP,SHAREHUB,CHARGING",
        "inventory.admin.token=admin-test-token"})
class SerialDocumentFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROFILE = """
            {"schemaVersion":1,"capabilities":["SERIAL","DISPATCH_POOL"],
             "targetTypes":[{"code":"MERCHANT","nameKey":"m","parentType":null},
                            {"code":"SITE","nameKey":"s","parentType":"MERCHANT"},
                            {"code":"PERSON","nameKey":"p","parentType":null}],
             "inboundPurposes":[{"code":"PURCHASE","basisRequired":false,"attachmentRequired":false},
                                {"code":"OPENING","basisRequired":true,"attachmentRequired":true},
                                {"code":"CORRECTION","basisRequired":false,"attachmentRequired":false}],
             "outboundPurposes":[{"code":"ISSUE","requiredTargets":["MERCHANT","PERSON"],
                                  "optionalTargets":["SITE"],"handover":"POOL"},
                                 {"code":"SCRAP","requiredTargets":[],"optionalTargets":[],"handover":"NONE"},
                                 {"code":"CONSUME","requiredTargets":["PERSON"],"optionalTargets":[],"handover":"NONE"}],
             "gateRefTypes":["QC"],"countReview":{"required":true,"reviewerMustDiffer":true}}""";

    @Autowired WebApplicationContext context;
    @Autowired List<FilterRegistrationBean<?>> filters;
    @Autowired HostInboundService inbounds;

    MockMvc mvc;
    Tenant t;
    String cabinet;
    String powerbank;
    String cable;
    String spare;
    String wh;

    @BeforeEach
    void setUp() throws Exception {
        var builder = MockMvcBuilders.webAppContextSetup(context);
        filters.forEach(f -> builder.addFilters(f.getFilter()));
        mvc = builder.build();
        t = tenant();
        cabinet = item("CAB", "{\"name\":\"柜\",\"trackingMode\":\"SERIAL\",\"gatePolicy\":\"REF_REQUIRED\",\"gateRefType\":\"QC\"}");
        powerbank = item("PB", "{\"name\":\"宝\",\"trackingMode\":\"SERIAL\",\"gatePolicy\":\"REF_REQUIRED\",\"gateRefType\":\"QC\"}");
        cable = item("CABLE", "{\"name\":\"线\",\"gatePolicy\":\"REF_REQUIRED\",\"gateRefType\":\"QC\"}");
        spare = item("SPARE", "{\"name\":\"备件\"}");
        wh = JSON.readTree(body(write(post("/api/inventory/v1/warehouses").content("{\"name\":\"W1\"}"))
                .andExpect(status().isOk()).andReturn())).get("locationId").asString();
    }

    @Test
    void missingGateRefsAreListedAllAtOnceAndNothingIsPosted() throws Exception {
        // AC-04：柜缺凭证、两块宝缺凭证、线缺整行凭证 —— 一次报全
        String no = createInbound("PURCHASE", """
                [{"itemId":"%s","serials":[{"serialNo":"C-MISS"}]},
                 {"itemId":"%s","serials":[{"serialNo":"P-MISS-1","parentSerialNo":"C-MISS"},
                                           {"serialNo":"P-MISS-2","gateRef":"QC-P2","parentSerialNo":"C-MISS"},
                                           {"serialNo":"P-MISS-3","parentSerialNo":"C-MISS"}]},
                 {"itemId":"%s","qty":5}]""".formatted(cabinet, powerbank, cable));
        postInbound(no).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("inventory.gate.ref_missing"));
        JsonNode args = JSON.readTree(body(postInbound(no).andReturn())).at("/data/args");
        List<String> missing = new ArrayList<>();
        args.forEach(a -> missing.add(a.asString()));
        assertThat(missing)
                .containsExactlyInAnyOrder("C-MISS", "P-MISS-1", "P-MISS-3", cable);
        assertThat(view("inbounds", no).get("status").asString()).isEqualTo("DRAFT");
        assertThat(balances(cabinet)).isEmpty();
    }

    @Test
    void cabinetWith48PowerbanksGoesInAndOutByScanningOnlyTheCabinet() throws Exception {
        // AC-24：入库登记柜 + 48 宝（宝挂柜下），出库只扫柜码
        StringBuilder pbs = new StringBuilder();
        for (int i = 1; i <= 48; i++) {
            pbs.append(i == 1 ? "" : ",").append("{\"serialNo\":\"P100-%02d\",\"gateRef\":\"QC-P100-%02d\",\"parentSerialNo\":\"C100\"}"
                    .formatted(i, i));
        }
        String in = createInbound("PURCHASE", """
                [{"itemId":"%s","serials":[{"serialNo":"C100","gateRef":"QC-C100"}]},
                 {"itemId":"%s","serials":[%s]}]""".formatted(cabinet, powerbank, pbs));
        postInbound(in).andExpect(status().isOk());
        assertThat(onHand(cabinet)).isEqualByComparingTo("1");
        assertThat(onHand(powerbank)).isEqualByComparingTo("48");
        assertThat(JSON.readTree(body(read("/api/inventory/v1/serials?parentSerialNo=C100")))).hasSize(48);

        String out = createOutbound("{\"locationId\":\"%s\",\"purpose\":\"SCRAP\",\"serialNos\":[\"C100\"]}"
                .formatted(wh));
        JsonNode doc = view("outbounds", out);
        assertThat(doc.get("serials")).hasSize(49);
        assertThat(doc.get("serials").findValuesAsString("parentSerialNo")).filteredOn("C100"::equals).hasSize(48);
        write(post("/api/inventory/v1/outbounds/" + out + "/post")).andExpect(status().isOk());
        assertThat(onHand(cabinet)).isEqualByComparingTo("0");
        assertThat(onHand(powerbank)).isEqualByComparingTo("0");
        assertThat(JSON.readTree(body(read("/api/inventory/v1/serials?locationId=" + wh)))).isEmpty();

        // 已过账的出库单不作废：货已经离开仓库
        write(post("/api/inventory/v1/outbounds/" + out + "/cancel")).andExpect(status().isConflict());
        // 流水可按单号追
        assertThat(JSON.readTree(body(read("/api/inventory/v1/ledger?docNo=" + out)))).hasSize(2);
    }

    @Test
    void sameSerialCanBeInStockOnlyOnceEvenUnderConcurrentPosting() throws Exception {
        // 顺序：第二张单的同一 SN 在过账时被拒
        String first = createInbound("PURCHASE", serialLine(cabinet, "C-DUP", "QC-DUP-1"));
        String second = createInbound("PURCHASE", serialLine(cabinet, "C-DUP", "QC-DUP-2"));
        postInbound(first).andExpect(status().isOk());
        postInbound(second).andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.args[0]").value("C-DUP:in_stock"));

        // AC-02：两张单并发过账同一个新 SN，只成功一个
        String a = createInbound("PURCHASE", serialLine(cabinet, "C-RACE", "QC-RACE-A"));
        String b = createInbound("PURCHASE", serialLine(cabinet, "C-RACE", "QC-RACE-B"));
        Caller caller = new Caller("SHAREHUB", t.owner, "STAFF:E1", null, null);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> results = new ArrayList<>();
        for (String no : List.of(a, b)) {
            results.add(pool.submit((Callable<String>) () -> {
                start.await();
                try {
                    inbounds.post(caller, no);
                    return "OK";
                } catch (InventoryException e) {
                    return e.error().name();
                } catch (RuntimeException e) {
                    return "DB:" + e.getClass().getSimpleName();
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();
        assertThat(outcomes).containsOnlyOnce("OK");
        assertThat(JSON.readTree(body(read("/api/inventory/v1/serials?itemId=" + cabinet)))
                .findValuesAsString("serialNo")).filteredOn("C-RACE"::equals).hasSize(1);
    }

    @Test
    void midwayFailureRollsBackQuantitiesSerialsAndLedger() throws Exception {
        // AC-01：备件 5 件 + 一件已在仓的 SN → 整单失败，备件余额、流水都不留
        postInbound(createInbound("PURCHASE", serialLine(cabinet, "C-HERE", "QC-HERE"))).andExpect(status().isOk());
        String no = createInbound("PURCHASE", """
                [{"itemId":"%s","qty":5},{"itemId":"%s","serials":[{"serialNo":"C-HERE","gateRef":"QC-HERE-2"},
                                                                {"serialNo":"C-NEW","gateRef":"QC-NEW"}]}]"""
                .formatted(spare, cabinet));
        postInbound(no).andExpect(status().isConflict());
        assertThat(balances(spare)).isEmpty();
        assertThat(JSON.readTree(body(read("/api/inventory/v1/ledger?docNo=" + no)))).isEmpty();
        assertThat(JSON.readTree(body(read("/api/inventory/v1/serials?itemId=" + cabinet)))
                .findValuesAsString("serialNo")).doesNotContain("C-NEW");
        assertThat(view("inbounds", no).get("status").asString()).isEqualTo("DRAFT");
    }

    @Test
    void gateRefsCannotBeReusedAndPurposesFollowTheProfile() throws Exception {
        postInbound(createInbound("PURCHASE", serialLine(cabinet, "C-G1", "QC-SAME"))).andExpect(status().isOk());
        postInbound(createInbound("PURCHASE", serialLine(cabinet, "C-G2", "QC-SAME"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.args[0]").value("C-G2:gate_reused"));

        // 期初要依据与附件；纠正入库不能手建；档案外的用途拒绝
        inboundDraft("{\"locationId\":\"%s\",\"purpose\":\"OPENING\",\"lines\":[{\"itemId\":\"%s\",\"qty\":1}]}"
                .formatted(wh, spare)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.args[0]").value("basis"));
        inboundDraft(("{\"locationId\":\"%s\",\"purpose\":\"OPENING\",\"basis\":\"MIGRATION\","
                + "\"attachmentRefs\":[\"att-1\"],\"lines\":[{\"itemId\":\"%s\",\"qty\":1}]}").formatted(wh, spare))
                .andExpect(status().isOk());
        inboundDraft("{\"locationId\":\"%s\",\"purpose\":\"CORRECTION\",\"lines\":[]}".formatted(wh))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.args[0]").value("purpose"));
        inboundDraft("{\"locationId\":\"%s\",\"purpose\":\"GIFT\",\"lines\":[]}".formatted(wh))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disposalOutboundFollowsTargetRulesAndSnapshotsNames() throws Exception {
        String in = createInbound("PURCHASE", "[{\"itemId\":\"%s\",\"qty\":10}]".formatted(spare));
        postInbound(in).andExpect(status().isOk());

        // 领用必须给接收人；接收人必须已同步
        outboundDraft("{\"locationId\":\"%s\",\"purpose\":\"CONSUME\",\"lines\":[{\"itemId\":\"%s\",\"qty\":2}]}"
                .formatted(wh, spare)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.args[0]").value("target.required:PERSON"));
        outboundDraft(consume("E404")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.args[0]").value("target:PERSON"));
        write(put("/api/inventory/v1/external-targets/PERSON/E1001").content("{\"name\":\"张工\",\"sourceVersion\":1}"))
                .andExpect(status().isOk());
        String out = JSON.readTree(body(outboundDraft(consume("E1001")).andExpect(status().isOk()).andReturn()))
                .get("docNo").asString();
        // 宿主改名之后，历史单据仍显示当时的名字
        write(put("/api/inventory/v1/external-targets/PERSON/E1001").content("{\"name\":\"张工（离职）\",\"sourceVersion\":2}"))
                .andExpect(status().isOk());
        assertThat(view("outbounds", out).at("/targets/0/nameSnapshot").asString()).isEqualTo("张工");
        write(post("/api/inventory/v1/outbounds/" + out + "/post")).andExpect(status().isOk());
        assertThat(onHand(spare)).isEqualByComparingTo("8");

        // 交接出库（出库池）在 M4；逐件物料不能按数量出
        outboundDraft("{\"locationId\":\"%s\",\"purpose\":\"ISSUE\",\"lines\":[]}".formatted(wh))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.args[0]").value("purpose.handover"));
        outboundDraft("{\"locationId\":\"%s\",\"purpose\":\"SCRAP\",\"lines\":[{\"itemId\":\"%s\",\"qty\":1}]}"
                .formatted(wh, cabinet)).andExpect(status().isBadRequest());
    }

    @Test
    void disassembledPowerbankNoLongerTravelsWithItsCabinet() throws Exception {
        String in = createInbound("PURCHASE", """
                [{"itemId":"%s","serials":[{"serialNo":"C200","gateRef":"QC-C200"}]},
                 {"itemId":"%s","serials":[{"serialNo":"P200-1","gateRef":"QC-P200-1","parentSerialNo":"C200"},
                                           {"serialNo":"P200-2","gateRef":"QC-P200-2","parentSerialNo":"C200"}]}]"""
                .formatted(cabinet, powerbank));
        postInbound(in).andExpect(status().isOk());
        write(post("/api/inventory/v1/serials/P200-1/disassemble")).andExpect(status().isOk());
        String out = createOutbound("{\"locationId\":\"%s\",\"purpose\":\"SCRAP\",\"serialNos\":[\"C200\"]}"
                .formatted(wh));
        assertThat(view("outbounds", out).get("serials").findValuesAsString("serialNo"))
                .containsExactlyInAnyOrder("C200", "P200-2");
        // 再装回去：只装一层，宝不能挂在宝下面
        write(post("/api/inventory/v1/serials/P200-1/assemble").content("{\"parentSerialNo\":\"P200-2\"}"))
                .andExpect(status().isConflict());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private record Tenant(String owner, String bearer) {
    }

    private String serialLine(String item, String serialNo, String gate) {
        return "[{\"itemId\":\"%s\",\"serials\":[{\"serialNo\":\"%s\",\"gateRef\":\"%s\"}]}]".formatted(item, serialNo, gate);
    }

    private String consume(String person) {
        return ("{\"locationId\":\"%s\",\"purpose\":\"CONSUME\",\"targets\":{\"PERSON\":\"%s\"},"
                + "\"lines\":[{\"itemId\":\"%s\",\"qty\":2}]}").formatted(wh, person, spare);
    }

    private String createInbound(String purpose, String lines) throws Exception {
        return JSON.readTree(body(inboundDraft("{\"locationId\":\"%s\",\"purpose\":\"%s\",\"lines\":%s}"
                .formatted(wh, purpose, lines)).andExpect(status().isOk()).andReturn())).get("docNo").asString();
    }

    private ResultActions inboundDraft(String json) throws Exception {
        return write(post("/api/inventory/v1/inbounds").content(json));
    }

    private ResultActions postInbound(String no) throws Exception {
        return write(post("/api/inventory/v1/inbounds/" + no + "/post"));
    }

    private String createOutbound(String json) throws Exception {
        return JSON.readTree(body(outboundDraft(json).andExpect(status().isOk()).andReturn())).get("docNo").asString();
    }

    private ResultActions outboundDraft(String json) throws Exception {
        return write(post("/api/inventory/v1/outbounds").content(json));
    }

    private JsonNode view(String kind, String no) throws Exception {
        return JSON.readTree(body(read("/api/inventory/v1/" + kind + "/" + no)));
    }

    private JsonNode balances(String item) throws Exception {
        return JSON.readTree(body(read("/api/inventory/v1/balances?itemId=" + item)));
    }

    private BigDecimal onHand(String item) throws Exception {
        JsonNode b = balances(item);
        return b.isEmpty() ? BigDecimal.ZERO : new BigDecimal(b.get(0).get("onHand").asString());
    }

    private Tenant tenant() throws Exception {
        String owner = JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners").content(
                "{\"systemCode\":\"SHAREHUB\",\"externalRef\":\"" + UUID.randomUUID() + "\",\"name\":\"n\"}"))
                .andExpect(status().isOk()).andReturn())).get("ownerId").asString();
        admin(put("/api/inventory/admin/v1/owners/" + owner + "/profile")
                .content("{\"profile\":" + PROFILE + ",\"reason\":\"init\"}")).andExpect(status().isOk());
        JsonNode issued = JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners/" + owner + "/credentials")
                .content("{\"name\":\"t\"}")).andExpect(status().isOk()).andReturn()));
        return new Tenant(owner, "Bearer " + issued.get("appKey").asString() + ":" + issued.get("appSecret").asString());
    }

    private String item(String ref, String spec) throws Exception {
        return JSON.readTree(body(write(put("/api/inventory/v1/items/by-ref/" + ref).content(spec))
                .andExpect(status().isOk()).andReturn())).get("itemId").asString();
    }

    private ResultActions write(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).header("Authorization", t.bearer)
                .header("Idempotency-Key", "K-" + UUID.randomUUID()).header("X-Inventory-Actor", "STAFF:E1"));
    }

    private MvcResult read(String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", t.bearer)).andExpect(status().isOk()).andReturn();
    }

    private ResultActions admin(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON)
                .header("X-Inventory-Admin-Token", "admin-test-token").header("X-Inventory-Actor", "OPS:test"));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }
}
