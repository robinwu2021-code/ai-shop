package ai.neargo.inventory.svc;

import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.DispatchService;
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
 * M4 端到端：交接出库与出库池（AC-05 ~ AC-10、AC-13、AC-14、AC-18）。
 *
 * <p>剧本是 ShareHub 的：柜 + 柜内宝发给商家 / 站点、运维签收、调整、撤回、退货与业务确认、事件拉取。
 */
@SpringBootTest(properties = {
        "inventory.flyway-locations=classpath:db/inventory-h2",
        "inventory.datasource.url=jdbc:h2:mem:inv_dispatch;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "inventory.datasource.username=sa",
        "inventory.datasource.password=",
        "inventory.instance.code=TEST",
        "inventory.instance.allowed-systems=SHOP,SHAREHUB,CHARGING",
        "inventory.admin.token=admin-test-token"})
class DispatchPoolFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROFILE = """
            {"schemaVersion":1,"capabilities":["SERIAL","DISPATCH_POOL"],
             "targetTypes":[{"code":"MERCHANT","nameKey":"m","parentType":null},
                            {"code":"SITE","nameKey":"s","parentType":"MERCHANT"},
                            {"code":"PERSON","nameKey":"p","parentType":null}],
             "inboundPurposes":[{"code":"PURCHASE","basisRequired":false,"attachmentRequired":false},
                                {"code":"RETURN","basisRequired":false,"attachmentRequired":false},
                                {"code":"CORRECTION","basisRequired":false,"attachmentRequired":false}],
             "outboundPurposes":[{"code":"ISSUE","requiredTargets":["MERCHANT","PERSON"],
                                  "optionalTargets":["SITE"],"handover":"POOL"},
                                 {"code":"SCRAP","requiredTargets":[],"optionalTargets":[],"handover":"NONE"}],
             "gateRefTypes":["QC"],"countReview":{"required":true,"reviewerMustDiffer":true}}""";

    @Autowired WebApplicationContext context;
    @Autowired List<FilterRegistrationBean<?>> filters;
    @Autowired DispatchService dispatch;

    MockMvc mvc;
    String owner;
    String bearer;
    String cabinet;
    String powerbank;
    String wh;

    @BeforeEach
    void setUp() throws Exception {
        var builder = MockMvcBuilders.webAppContextSetup(context);
        filters.forEach(f -> builder.addFilters(f.getFilter()));
        mvc = builder.build();
        tenant();
        cabinet = id(write(put("/api/inventory/v1/items/by-ref/CAB").content(
                "{\"name\":\"柜\",\"trackingMode\":\"SERIAL\",\"gatePolicy\":\"REF_REQUIRED\",\"gateRefType\":\"QC\"}")), "itemId");
        powerbank = id(write(put("/api/inventory/v1/items/by-ref/PB").content(
                "{\"name\":\"宝\",\"trackingMode\":\"SERIAL\",\"gatePolicy\":\"REF_REQUIRED\",\"gateRefType\":\"QC\"}")), "itemId");
        wh = id(write(post("/api/inventory/v1/warehouses").content("{\"name\":\"W1\"}")), "locationId");
        target("MERCHANT", "AG001", "商家甲", null);
        target("MERCHANT", "AG002", "商家乙", null);
        target("SITE", "ST01", "站点一", "AG001");
        target("SITE", "ST02", "站点二", "AG001");
        target("SITE", "SX", "乙的站点", "AG002");
        target("PERSON", "E1001", "运维甲", null);
        target("PERSON", "E1002", "运维乙", null);
    }

    @Test
    void issueBuildsAKitInThePoolAndRedirectMovesTheWholeKit() throws Exception {
        stockKit("C1", 8);
        // AC-05：站点必须属于所选商家
        issueDraft("AG001", "SX", "E1001", "C1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.args[0]").value("target.parent:SITE"));

        // AC-06：过账后余额、周期、出库池、流水、事件都在
        String out = postIssue("AG001", "ST01", "E1001", "C1");
        assertThat(onHand(cabinet)).isEqualByComparingTo("0");
        assertThat(onHand(powerbank)).isEqualByComparingTo("0");
        JsonNode pool = pool("outboundNo=" + out);
        assertThat(pool).hasSize(9);
        String head = headOf(pool);
        assertThat(pool.findValuesAsString("status")).containsOnly("AVAILABLE");
        assertThat(pool.findValuesAsString("rootTargetRef")).containsOnly("AG001");
        assertThat(JSON.readTree(body(read("/api/inventory/v1/ledger?docNo=" + out)))).hasSize(2);

        // AC-18：从属记录不能单独操作
        String child = childOf(pool);
        write(post("/api/inventory/v1/dispatch-pool/" + child + "/redirect")
                .content("{\"targets\":{\"MERCHANT\":\"AG001\",\"PERSON\":\"E1001\"},\"reason\":\"x\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("inventory.dispatch.child_locked"));

        // AC-07：待签收可调整，整套随动、留快照；按站点多值查得到
        write(post("/api/inventory/v1/dispatch-pool/" + head + "/redirect").content(
                "{\"targets\":{\"MERCHANT\":\"AG001\",\"SITE\":\"ST02\",\"PERSON\":\"E1002\"},\"reason\":\"站点选错\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resourceVersion").value(2));
        assertThat(pool("targetType=SITE&targetRef=ST02&targetRef=NOPE")).hasSize(9);
        assertThat(pool("targetType=PERSON&targetRef=E1002")).hasSize(9);
        assertThat(pool("rootTargetRef=AG002&rootTargetRef=AG001&outboundNo=" + out)).hasSize(9);

        // 签收之后不能再调整（AC-07 后半）
        claim(head).andExpect(status().isOk());
        write(post("/api/inventory/v1/dispatch-pool/" + head + "/redirect").content(
                "{\"targets\":{\"MERCHANT\":\"AG001\",\"PERSON\":\"E1001\"},\"reason\":\"再改\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("inventory.dispatch.not_available"));
        assertThat(pool("outboundNo=" + out).findValuesAsString("status")).containsOnly("CLAIMED");

        // AC-13：已过账的交接出库不能作废
        write(post("/api/inventory/v1/outbounds/" + out + "/cancel")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("inventory.issue.void_forbidden"));
    }

    @Test
    void revokeReturnsTheWholeKitToStockWithANewEpisode() throws Exception {
        stockKit("C3", 2);
        String out = postIssue("AG001", "ST01", "E1001", "C3");
        String head = headOf(pool("outboundNo=" + out));
        String correction = id(write(post("/api/inventory/v1/dispatch-pool/" + head + "/revoke")
                .content("{\"reason\":\"发错了\"}")).andExpect(status().isOk()), "docNo");

        // AC-08：池记录撤回、回到在仓（新周期）、余额恢复、组装关系照原样
        assertThat(pool("outboundNo=" + out).findValuesAsString("status")).containsOnly("REVOKED");
        assertThat(onHand(cabinet)).isEqualByComparingTo("1");
        assertThat(onHand(powerbank)).isEqualByComparingTo("2");
        assertThat(JSON.readTree(body(read("/api/inventory/v1/serials?parentSerialNo=C3")))).hasSize(2);
        JsonNode doc = JSON.readTree(body(read("/api/inventory/v1/inbounds/" + correction)));
        assertThat(doc.get("purpose").asString()).isEqualTo("CORRECTION");
        // 撤回过的记录不能再签收
        claim(head).andExpect(status().isConflict());
        // 回仓的柜可以再次发出
        assertThat(postIssue("AG001", "ST01", "E1001", "C3")).isNotBlank();
    }

    @Test
    void concurrentClaimsOnTheSameBatchLetOnlyOneWinAndReleaseReopens() throws Exception {
        stockKit("C5", 1);
        stockKit("C6", 0);
        postIssue("AG001", null, "E1001", "C5");
        postIssue("AG001", null, "E1001", "C6");
        String c5 = headOf(pool("serialNo=C5"));
        String c6 = headOf(pool("serialNo=C6"));

        // AC-09：两个签收批次抢同一条，只一方成功，整批全成全败
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        List<Future<String>> results = new ArrayList<>();
        for (List<String> batch : List.of(List.of(c5, c6), List.of(c6))) {
            results.add(threads.submit((Callable<String>) () -> {
                start.await();
                try {
                    dispatch.claim(new Caller("SHAREHUB", owner, "STAFF:E1001", "R-" + UUID.randomUUID(), null),
                            batch, "SIGN");
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
        threads.shutdown();
        assertThat(outcomes).containsOnlyOnce("OK");
        JsonNode c5Pool = pool("serialNo=C5");
        boolean bigBatchWon = "CLAIMED".equals(c5Pool.get(0).get("status").asString());
        // 输的一方整批都没动
        if (!bigBatchWon) {
            assertThat(pool("outboundNo=" + c5Pool.get(0).get("outboundNo").asString())
                    .findValuesAsString("status")).containsOnly("AVAILABLE");
        }

        // 按幂等键查签收结果；释放后回到待签收
        String key = "K-" + UUID.randomUUID();
        String claimNo = id(mvc.perform(post("/api/inventory/v1/dispatch-pool/claims")
                .contentType(MediaType.APPLICATION_JSON).header("Authorization", bearer)
                .header("Idempotency-Key", key).header("X-Inventory-Actor", "STAFF:E1001")
                .content("{\"dispatchIds\":[\"%s\"],\"businessRef\":\"S-1\"}".formatted(bigBatchWon ? c6 : c5)))
                .andExpect(status().isOk()), "claimNo");
        assertThat(id(mvc.perform(get("/api/inventory/v1/dispatch-pool/claims/by-request/" + key)
                .header("Authorization", bearer)).andExpect(status().isOk()), "claimNo")).isEqualTo(claimNo);
        write(post("/api/inventory/v1/dispatch-pool/claims/" + claimNo + "/release")).andExpect(status().isOk());
        assertThat(pool("serialNo=" + (bigBatchWon ? "C6" : "C5")).get(0).get("status").asString())
                .isEqualTo("AVAILABLE");
    }

    @Test
    void claimedReturnIsHeldUntilBusinessClearance() throws Exception {
        stockKit("C7", 0);
        String out = postIssue("AG002", "SX", "E1002", "C7");
        String head = headOf(pool("outboundNo=" + out));
        claim(head).andExpect(status().isOk());

        // AC-10：已签收的设备退货 → 新周期 + 退货待确认冻结；确认前不能再发
        String ret = createInbound("RETURN", "C7", "QC-C7-R1");
        write(post("/api/inventory/v1/inbounds/" + ret + "/post")).andExpect(status().isOk());
        assertThat(pool("outboundNo=" + out).get(0).get("status").asString()).isEqualTo("RETURNED");
        JsonNode held = JSON.readTree(body(read("/api/inventory/v1/serials?itemId=" + cabinet)));
        for (JsonNode s : held) {
            if (s.get("serialNo").asString().equals("C7")) {
                assertThat(s.get("holdReasons").get(0).asString()).isEqualTo("RETURN_PENDING");
            }
        }
        String blocked = id(issueDraft("AG001", null, "E1001", "C7").andExpect(status().isOk()), "docNo");
        write(post("/api/inventory/v1/outbounds/" + blocked + "/post")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.args[0]").value("C7:held"));

        write(post("/api/inventory/v1/inbounds/" + ret + "/business-clearance")).andExpect(status().isOk());
        write(post("/api/inventory/v1/outbounds/" + blocked + "/post")).andExpect(status().isOk());

        // 在途：待签收的按商家汇总
        JsonNode transit = JSON.readTree(body(read("/api/inventory/v1/in-transit")));
        assertThat(transit.findValuesAsString("rootTargetRef")).contains("AG001");
    }

    @Test
    void eventsArePulledInOrderAckedAndAnExpiredCursorIsReported() throws Exception {
        stockKit("C9", 1);
        postIssue("AG001", null, "E1001", "C9");

        // AC-14：拉 → 确认 → 清理 → 旧游标过期 → 从确认点继续，不漏不重
        JsonNode page = JSON.readTree(body(read("/api/inventory/v1/events?limit=500")));
        List<String> types = page.get("events").findValuesAsString("type");
        assertThat(types).contains("InboundPosted", "OutboundPosted", "DispatchCreated");
        assertThat(types.stream().filter("DispatchCreated"::equals)).hasSize(2);
        long last = page.get("lastSeq").asLong();
        write(post("/api/inventory/v1/event-acks").content("{\"seq\":" + last + "}")).andExpect(status().isOk());
        admin(post("/api/inventory/admin/v1/owners/" + owner + "/events/prune")).andExpect(status().isOk());

        mvc.perform(get("/api/inventory/v1/events?afterSeq=0").header("Authorization", bearer))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("inventory.events.cursor_expired"));
        assertThat(JSON.readTree(body(read("/api/inventory/v1/events"))).get("events")).isEmpty();

        String head = headOf(pool("serialNo=C9"));
        claim(head).andExpect(status().isOk());
        JsonNode next = JSON.readTree(body(read("/api/inventory/v1/events"))).get("events");
        assertThat(next.findValuesAsString("type")).containsOnly("DispatchClaimed");
        assertThat(next.get(0).get("seq").asLong()).isGreaterThan(last);
        assertThat(next.findValuesAsString("resourceId")).hasSize(2);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void stockKit(String cabinetNo, int powerbanks) throws Exception {
        StringBuilder pbs = new StringBuilder();
        for (int i = 1; i <= powerbanks; i++) {
            pbs.append(i == 1 ? "" : ",").append(("{\"serialNo\":\"%s-P%d\",\"gateRef\":\"QC-%s-P%d\","
                    + "\"parentSerialNo\":\"%s\"}").formatted(cabinetNo, i, cabinetNo, i, cabinetNo));
        }
        String lines = "[{\"itemId\":\"%s\",\"serials\":[{\"serialNo\":\"%s\",\"gateRef\":\"QC-%s\"}]}%s]".formatted(
                cabinet, cabinetNo, cabinetNo,
                powerbanks == 0 ? "" : ",{\"itemId\":\"%s\",\"serials\":[%s]}".formatted(powerbank, pbs));
        String no = id(write(post("/api/inventory/v1/inbounds").content(
                "{\"locationId\":\"%s\",\"purpose\":\"PURCHASE\",\"lines\":%s}".formatted(wh, lines))), "docNo");
        write(post("/api/inventory/v1/inbounds/" + no + "/post")).andExpect(status().isOk());
    }

    private String createInbound(String purpose, String serialNo, String gate) throws Exception {
        return id(write(post("/api/inventory/v1/inbounds").content(
                ("{\"locationId\":\"%s\",\"purpose\":\"%s\",\"lines\":[{\"itemId\":\"%s\","
                        + "\"serials\":[{\"serialNo\":\"%s\",\"gateRef\":\"%s\"}]}]}")
                        .formatted(wh, purpose, cabinet, serialNo, gate))), "docNo");
    }

    private ResultActions issueDraft(String merchant, String site, String person, String serialNo) throws Exception {
        String targets = "\"MERCHANT\":\"%s\",\"PERSON\":\"%s\"".formatted(merchant, person)
                + (site == null ? "" : ",\"SITE\":\"%s\"".formatted(site));
        return write(post("/api/inventory/v1/outbounds").content(
                "{\"locationId\":\"%s\",\"purpose\":\"ISSUE\",\"targets\":{%s},\"serialNos\":[\"%s\"]}"
                        .formatted(wh, targets, serialNo)));
    }

    private String postIssue(String merchant, String site, String person, String serialNo) throws Exception {
        String no = id(issueDraft(merchant, site, person, serialNo).andExpect(status().isOk()), "docNo");
        write(post("/api/inventory/v1/outbounds/" + no + "/post")).andExpect(status().isOk());
        return no;
    }

    private ResultActions claim(String dispatchId) throws Exception {
        return write(post("/api/inventory/v1/dispatch-pool/claims")
                .content("{\"dispatchIds\":[\"%s\"],\"businessRef\":\"SIGN-1\"}".formatted(dispatchId)));
    }

    private JsonNode pool(String query) throws Exception {
        return JSON.readTree(body(read("/api/inventory/v1/dispatch-pool?" + query)));
    }

    private static String headOf(JsonNode pool) {
        for (JsonNode r : pool) {
            if (r.get("parentDispatchId").isNull()) {
                return r.get("dispatchId").asString();
            }
        }
        throw new AssertionError("no head");
    }

    private static String childOf(JsonNode pool) {
        for (JsonNode r : pool) {
            if (!r.get("parentDispatchId").isNull()) {
                return r.get("dispatchId").asString();
            }
        }
        throw new AssertionError("no child");
    }

    private BigDecimal onHand(String item) throws Exception {
        JsonNode b = JSON.readTree(body(read("/api/inventory/v1/balances?itemId=" + item)));
        return b.isEmpty() ? BigDecimal.ZERO : new BigDecimal(b.get(0).get("onHand").asString());
    }

    private void target(String type, String ref, String name, String parent) throws Exception {
        write(put("/api/inventory/v1/external-targets/" + type + "/" + ref).content(
                "{\"name\":\"%s\",\"sourceVersion\":1%s}".formatted(name,
                        parent == null ? "" : ",\"parentRef\":\"" + parent + "\""))).andExpect(status().isOk());
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
        return JSON.readTree(body(r.andExpect(status().isOk()).andReturn())).get(field).asString();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }
}
