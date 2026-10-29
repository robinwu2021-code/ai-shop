package ai.neargo.inventory.svc;

import ai.neargo.inventory.domain.mapper.InventoryMappers.BalanceMapper;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** M5 端到端：盘点范围冻结（AC-11）、SN 实盘与复核、期间汇总（AC-12）。 */
@SpringBootTest(properties = {
        "inventory.flyway-locations=classpath:db/inventory-h2",
        "inventory.datasource.url=jdbc:h2:mem:inv_count;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "inventory.datasource.username=sa",
        "inventory.datasource.password=",
        "inventory.instance.code=TEST",
        "inventory.instance.allowed-systems=SHOP,SHAREHUB,CHARGING",
        "inventory.admin.token=admin-test-token"})
class CountAndSummaryFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROFILE = """
            {"schemaVersion":1,"capabilities":["SERIAL","DISPATCH_POOL"],
             "targetTypes":[{"code":"PERSON","nameKey":"p","parentType":null}],
             "inboundPurposes":[{"code":"PURCHASE","basisRequired":false,"attachmentRequired":false}],
             "outboundPurposes":[{"code":"SCRAP","requiredTargets":[],"optionalTargets":[],"handover":"NONE"}],
             "gateRefTypes":["QC"],"countReview":{"required":true,"reviewerMustDiffer":true}}""";

    @Autowired WebApplicationContext context;
    @Autowired List<FilterRegistrationBean<?>> filters;
    @Autowired BalanceMapper balanceMapper;

    MockMvc mvc;
    String owner;
    String bearer;
    String cabinet;
    String spare;
    String wh;

    @BeforeEach
    void setUp() throws Exception {
        var builder = MockMvcBuilders.webAppContextSetup(context);
        filters.forEach(f -> builder.addFilters(f.getFilter()));
        mvc = builder.build();
        tenant();
        cabinet = id(write(put("/api/inventory/v1/items/by-ref/CAB").content(
                "{\"name\":\"柜\",\"trackingMode\":\"SERIAL\",\"gatePolicy\":\"REF_REQUIRED\",\"gateRefType\":\"QC\"}"),
                "E1"), "itemId");
        spare = id(write(put("/api/inventory/v1/items/by-ref/SPARE").content("{\"name\":\"备件\"}"), "E1"), "itemId");
        wh = id(write(post("/api/inventory/v1/warehouses").content("{\"name\":\"W1\"}"), "E1"), "locationId");
        String in = id(write(post("/api/inventory/v1/inbounds").content(("{\"locationId\":\"%s\",\"purpose\":\"PURCHASE\","
                + "\"lines\":[{\"itemId\":\"%s\",\"serials\":[{\"serialNo\":\"C1\",\"gateRef\":\"Q1\"},"
                + "{\"serialNo\":\"C2\",\"gateRef\":\"Q2\"},{\"serialNo\":\"C3\",\"gateRef\":\"Q3\"},"
                + "{\"serialNo\":\"C4\",\"gateRef\":\"Q4\"}]},{\"itemId\":\"%s\",\"qty\":10}]}")
                .formatted(wh, cabinet, spare)), "E1"), "docNo");
        write(post("/api/inventory/v1/inbounds/" + in + "/post"), "E1").andExpect(status().isOk());
    }

    @Test
    void openCountFreezesItsScopeUntilCancelled() throws Exception {
        String count = open(cabinet);
        // AC-11：范围内（柜）出库被挡；范围外（备件）照常
        String scrapCab = scrap("\"serialNos\":[\"C1\"]");
        write(post("/api/inventory/v1/outbounds/" + scrapCab + "/post"), "E1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("inventory.count.frozen"))
                .andExpect(jsonPath("$.data.args[0]").value(count));
        String scrapSpare = scrap("\"lines\":[{\"itemId\":\"%s\",\"qty\":1}]".formatted(spare));
        write(post("/api/inventory/v1/outbounds/" + scrapSpare + "/post"), "E1").andExpect(status().isOk());
        // 同一库位同一时刻只一张
        write(post("/api/inventory/v1/counts").content(
                "{\"locationId\":\"%s\",\"itemIds\":[\"%s\"]}".formatted(wh, spare)), "E1")
                .andExpect(status().isConflict());

        // 作废立即解冻
        write(post("/api/inventory/v1/counts/" + count + "/cancel"), "E1").andExpect(status().isOk());
        write(post("/api/inventory/v1/outbounds/" + scrapCab + "/post"), "E1").andExpect(status().isOk());
    }

    @Test
    void countCannotOpenOverActiveReservations() throws Exception {
        assertThat(balanceMapper.hold(owner, spare, wh, 2)).isOne();
        write(post("/api/inventory/v1/counts").content(
                "{\"locationId\":\"%s\",\"itemIds\":[\"%s\"]}".formatted(wh, spare)), "E1")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.args[0]").value("reserved"));
        assertThat(balanceMapper.unhold(owner, spare, wh, 2)).isOne();
    }

    @Test
    void serialCountWithReviewPostsLossForMissingAndGainForQuantity() throws Exception {
        String count = open(cabinet, spare);
        // 扫码：重扫不重计；未知 SN 只列异常
        JsonNode scans = JSON.readTree(body(write(post("/api/inventory/v1/counts/" + count + "/scans")
                .content("{\"serialNos\":[\"C2\",\"C3\",\"C2\",\"X-404\"]}"), "E1").andExpect(status().isOk()).andReturn()));
        assertThat(scans.findValuesAsString("verdict")).containsExactly("IN_SCOPE", "IN_SCOPE", "IN_SCOPE", "UNKNOWN");
        write(put("/api/inventory/v1/counts/" + count + "/lines/" + spare).content("{\"countedQty\":12}"), "E1")
                .andExpect(status().isOk());
        JsonNode view = JSON.readTree(body(read("/api/inventory/v1/counts/" + count)));
        List<String> missing = new ArrayList<>();
        view.get("missingSerials").forEach(n -> missing.add(n.asString()));
        assertThat(missing).containsExactly("C1", "C4");
        assertThat(view.get("exceptions").findValuesAsString("serialNo")).containsExactly("X-404");

        // 没批准不能过账；复核人不能是提交人
        write(post("/api/inventory/v1/counts/" + count + "/submit"), "E1").andExpect(status().isOk());
        write(post("/api/inventory/v1/counts/" + count + "/post"), "E1").andExpect(status().isConflict());
        write(post("/api/inventory/v1/counts/" + count + "/approve").content("{\"note\":\"ok\"}"), "E1")
                .andExpect(status().isForbidden());
        // 驳回后补扫 C1，再提交、换人批准
        write(post("/api/inventory/v1/counts/" + count + "/reject").content("{\"note\":\"C1 在角落\"}"), "E2")
                .andExpect(status().isOk());
        write(post("/api/inventory/v1/counts/" + count + "/scans").content("{\"serialNos\":[\"C1\"]}"), "E1")
                .andExpect(status().isOk());
        write(post("/api/inventory/v1/counts/" + count + "/submit"), "E1").andExpect(status().isOk());
        write(post("/api/inventory/v1/counts/" + count + "/approve").content("{\"note\":\"ok\"}"), "E2")
                .andExpect(status().isOk());
        JsonNode posted = JSON.readTree(body(write(post("/api/inventory/v1/counts/" + count + "/post"), "E2")
                .andExpect(status().isOk()).andReturn()));
        assertThat(posted.get("gainInboundNo").isNull()).isFalse();
        assertThat(posted.get("lossOutboundNo").isNull()).isFalse();

        // C4 盘亏出库、备件盘盈 2；盘点过账后解冻
        assertThat(onHand(cabinet)).isEqualByComparingTo("3");
        assertThat(onHand(spare)).isEqualByComparingTo("12");
        assertThat(JSON.readTree(body(read("/api/inventory/v1/serials?itemId=" + cabinet)))
                .findValuesAsString("serialNo")).containsExactlyInAnyOrder("C1", "C2", "C3");
        String scrapCab = scrap("\"serialNos\":[\"C1\"]");
        write(post("/api/inventory/v1/outbounds/" + scrapCab + "/post"), "E1").andExpect(status().isOk());
    }

    @Test
    void periodSummaryReconcilesOpeningInOutAndClosing() throws Exception {
        String scrapSpare = scrap("\"lines\":[{\"itemId\":\"%s\",\"qty\":3}]".formatted(spare));
        write(post("/api/inventory/v1/outbounds/" + scrapSpare + "/post"), "E1").andExpect(status().isOk());

        DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        LocalDateTime now = LocalDateTime.now();
        // AC-12：期初 + 入 − 出 = 期末，且等于当前余额
        JsonNode all = summary(now.minusDays(1).format(iso), now.plusDays(1).format(iso));
        JsonNode row = all.get(0);
        assertThat(new BigDecimal(row.get("opening").asString())).isEqualByComparingTo("0");
        assertThat(new BigDecimal(row.at("/inByReason/PURCHASE").asString())).isEqualByComparingTo("10");
        assertThat(new BigDecimal(row.at("/outByReason/SCRAP").asString())).isEqualByComparingTo("3");
        assertThat(new BigDecimal(row.get("closing").asString())).isEqualByComparingTo(onHand(spare));

        // 之后的一段：期初就是上一段的期末，期间没有变动
        JsonNode later = summary(now.plusHours(1).format(iso), now.plusDays(2).format(iso)).get(0);
        assertThat(new BigDecimal(later.get("opening").asString())).isEqualByComparingTo("7");
        assertThat(later.get("inByReason")).isEmpty();
        assertThat(new BigDecimal(later.get("closing").asString())).isEqualByComparingTo("7");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private JsonNode summary(String from, String to) throws Exception {
        return JSON.readTree(body(read("/api/inventory/v1/reports/period-summary?from=%s&to=%s&itemId=%s"
                .formatted(from, to, spare))));
    }

    private String open(String... items) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (String i : items) {
            ids.append(ids.isEmpty() ? "" : ",").append('"').append(i).append('"');
        }
        return id(write(post("/api/inventory/v1/counts").content(
                "{\"locationId\":\"%s\",\"itemIds\":[%s]}".formatted(wh, ids)), "E1"), "docNo");
    }

    private String scrap(String body) throws Exception {
        return id(write(post("/api/inventory/v1/outbounds").content(
                "{\"locationId\":\"%s\",\"purpose\":\"SCRAP\",%s}".formatted(wh, body)), "E1"), "docNo");
    }

    private BigDecimal onHand(String item) throws Exception {
        JsonNode b = JSON.readTree(body(read("/api/inventory/v1/balances?itemId=" + item)));
        return b.isEmpty() ? BigDecimal.ZERO : new BigDecimal(b.get(0).get("onHand").asString());
    }

    private void tenant() throws Exception {
        owner = JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners").content(
                "{\"systemCode\":\"SHAREHUB\",\"externalRef\":\"" + UUID.randomUUID() + "\",\"name\":\"n\"}"))
                .andExpect(status().isOk()).andReturn())).get("ownerId").asString();
        admin(put("/api/inventory/admin/v1/owners/" + owner + "/profile")
                .content("{\"profile\":" + PROFILE + ",\"reason\":\"init\"}")).andExpect(status().isOk());
        JsonNode issued = JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners/" + owner + "/credentials")
                .content("{\"name\":\"t\"}")).andExpect(status().isOk()).andReturn()));
        bearer = "Bearer " + issued.get("appKey").asString() + ":" + issued.get("appSecret").asString();
    }

    private ResultActions write(MockHttpServletRequestBuilder req, String staff) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).header("Authorization", bearer)
                .header("Idempotency-Key", "K-" + UUID.randomUUID()).header("X-Inventory-Actor", "STAFF:" + staff));
    }

    private MvcResult read(String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", bearer)).andExpect(status().isOk()).andReturn();
    }

    private ResultActions admin(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON)
                .header("X-Inventory-Admin-Token", "admin-test-token").header("X-Inventory-Actor", "OPS:test"));
    }

    private static String id(ResultActions r, String field) throws Exception {
        return JSON.readTree(body(r.andExpect(status().isOk()).andReturn())).get(field).asString();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }
}
