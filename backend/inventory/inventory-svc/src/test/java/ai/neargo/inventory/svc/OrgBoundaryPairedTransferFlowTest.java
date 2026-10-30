package ai.neargo.inventory.svc;

import ai.neargo.inventory.domain.service.TransferService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 组织边界与成对单据（TDD §2.19，AC-29 ~ AC-35）：账本带宿主组织类型、仓库带宿主编号与持有方、
 * 出库对象关联对方账本、发给它的出库一出一入、多账本批量余额。
 */
@SpringBootTest(properties = {
        "inventory.flyway-locations=classpath:db/inventory-h2",
        "inventory.datasource.url=jdbc:h2:mem:inv_org_boundary;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "inventory.datasource.username=sa",
        "inventory.datasource.password=",
        "inventory.instance.code=TEST",
        "inventory.instance.allowed-systems=SHOP,SHAREHUB,CHARGING",
        "inventory.admin.token=admin-test-token"})
class OrgBoundaryPairedTransferFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 电商档案：门店是出库对象，调店与加盟供货都一出一入 */
    private static final String SHOP = """
            {"schemaVersion":1,"capabilities":["RESERVATION"],
             "targetTypes":[{"code":"STORE","nameKey":"s","parentType":null}],
             "inboundPurposes":[{"code":"PURCHASE","basisRequired":false,"attachmentRequired":false},
                                {"code":"STORE_IN","basisRequired":false,"attachmentRequired":false}],
             "outboundPurposes":[{"code":"STORE_OUT","requiredTargets":["STORE"],"optionalTargets":[],
                                  "handover":"RECEIPT","receiptPurpose":"STORE_IN"},
                                 {"code":"SCRAP","requiredTargets":[],"optionalTargets":[],"handover":"NONE"}],
             "gateRefTypes":[],"countReview":{"required":false,"reviewerMustDiffer":false}}""";

    @Autowired WebApplicationContext context;
    @Autowired List<FilterRegistrationBean<?>> filters;
    @Autowired TransferService transfers;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        var builder = MockMvcBuilders.webAppContextSetup(context);
        filters.forEach(f -> builder.addFilters(f.getFilter()));
        mvc = builder.build();
    }

    @Test
    void ownersOfDifferentHostLevelsMayShareANumberAndTheTypeHeaderPicksOne() throws Exception {
        String no = "X" + UUID.randomUUID().toString().substring(0, 8);
        open("SHOP", "ENTITY", no).andExpect(status().isOk());
        String store = ownerId(open("SHOP", "STORE", no).andExpect(status().isOk()));
        open("SHOP", "STORE", no).andExpect(status().isConflict());
        admin(put("/api/inventory/admin/v1/owners/" + store + "/profile")
                .content("{\"profile\":" + SHOP + ",\"reason\":\"init\"}")).andExpect(status().isOk());

        // AC-29：系统凭证只给编号 → 撞号要求带类型；带上类型 → 命中门店那本
        String system = systemBearer();
        mvc.perform(get("/api/inventory/v1/warehouses").header("Authorization", system)
                        .header("X-Inventory-Owner-Ref", no))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.args[0]").value("owner_type_required"));
        mvc.perform(get("/api/inventory/v1/warehouses").header("Authorization", system)
                        .header("X-Inventory-Owner-Ref", no).header("X-Inventory-Owner-Type", "STORE"))
                .andExpect(status().isOk());
    }

    @Test
    void warehousesCarryHostRefsAndFilterByHolder() throws Exception {
        Tenant t = tenant("STORE");
        // AC-34：带宿主编号与持有方建仓；按持有方列；同账本编号重复 → 409
        warehouse(t, "S-A", "E1");
        warehouse(t, "S-B", "E2");
        write(t, post("/api/inventory/v1/warehouses")
                .content("{\"name\":\"dup\",\"externalRef\":\"S-A\"}")).andExpect(status().isConflict());
        JsonNode e1 = JSON.readTree(body(read(t, "/api/inventory/v1/warehouses?holderRef=E1")));
        assertThat(e1.findValuesAsString("externalRef")).containsExactly("S-A");
        write(t, patch("/api/inventory/v1/warehouses/" + e1.get(0).get("locationId").asString())
                .content("{\"holderRef\":\"E2\"}")).andExpect(status().isOk());
        assertThat(JSON.readTree(body(read(t, "/api/inventory/v1/warehouses?holderRef=E2")))
                .findValuesAsString("externalRef")).containsExactlyInAnyOrder("S-A", "S-B");
    }

    @Test
    void shippingToALinkedStoreCreatesItsPendingInboundAndReceiptReportsTheDifference() throws Exception {
        Tenant a = tenant("CHAIN");
        Tenant b = tenant("STORE");
        String fromLoc = warehouse(a, "HQ", null);
        String toLoc = warehouse(b, b.ref, null);
        String apple = item(a, "SKU-APPLE");
        String pear = item(a, "SKU-PEAR");
        stockIn(a, fromLoc, apple, 10);
        stockIn(a, fromLoc, pear, 5);
        syncStore(a, b, true);

        // AC-30：出库过账 → A 扣在手、B 有待收入库单、成对记录 SHIPPED、B 收到事件
        String out = outbound(a, fromLoc, b.ref, List.of(apple, pear), List.of(10, 5));
        write(a, post("/api/inventory/v1/outbounds/" + out + "/post")).andExpect(status().isOk());
        assertOnHand(a, apple, "0");
        JsonNode pair = JSON.readTree(body(read(b, "/api/inventory/v1/paired-transfers?direction=IN"))).get(0);
        assertThat(pair.get("status").asString()).isEqualTo("SHIPPED");
        assertThat(pair.get("from").get("docNo").asString()).isEqualTo(out);
        assertThat(pair.get("from").get("ownerType").asString()).isEqualTo("CHAIN");
        assertThat(pair.get("to").get("locationId").asString()).isEqualTo(toLoc);
        String inboundNo = pair.get("to").get("docNo").asString();
        JsonNode inbound = JSON.readTree(body(read(b, "/api/inventory/v1/inbounds/" + inboundNo)));
        assertThat(inbound.toString()).contains("DRAFT", "STORE_IN");
        assertThat(JSON.readTree(body(read(b, "/api/inventory/v1/events?limit=100"))).get("events")
                .findValuesAsString("type")).contains("PairedInboundCreated");

        // AC-31：成对入库单不能直接过账或作废，只能确认收货
        write(b, post("/api/inventory/v1/inbounds/" + inboundNo + "/post")).andExpect(status().isConflict());
        write(b, post("/api/inventory/v1/inbounds/" + inboundNo + "/cancel")).andExpect(status().isConflict());
        String appleAtB = pair.get("lines").get(0).get("toItemId").asString();
        String pearAtB = pair.get("lines").get(1).get("toItemId").asString();
        write(b, post("/api/inventory/v1/inbounds/" + inboundNo + "/receive").content(
                "{\"lines\":[{\"itemId\":\"" + appleAtB + "\",\"qty\":9},{\"itemId\":\"" + pearAtB + "\",\"qty\":5}]}"))
                .andExpect(status().isOk());
        assertOnHand(b, appleAtB, "9");
        assertOnHand(b, pearAtB, "5");
        JsonNode received = JSON.readTree(body(read(a,
                "/api/inventory/v1/paired-transfers/" + pair.get("pairNo").asString())));
        assertThat(received.get("status").asString()).isEqualTo("RECEIVED");
        assertThat(received.get("lines").get(0).get("received").asInt()).isEqualTo(9);
        assertThat(JSON.readTree(body(read(a, "/api/inventory/v1/events?limit=100"))).get("events")
                .findValuesAsString("type")).contains("PairedTransferReceived");
        write(b, post("/api/inventory/v1/inbounds/" + inboundNo + "/receive")
                .content("{\"lines\":[]}")).andExpect(status().isConflict());
    }

    @Test
    void shippingIsRefusedWhenTheStoreIsNotLinkedOrItsBookIsNotServiceWritten() throws Exception {
        Tenant a = tenant("CHAIN");
        Tenant b = tenant("STORE");
        String fromLoc = warehouse(a, "HQ", null);
        warehouse(b, b.ref, null);
        String apple = item(a, "SKU-APPLE");
        stockIn(a, fromLoc, apple, 3);

        // AC-32：没关联账本 → 建单 400，什么都不落
        syncStore(a, b, false);
        write(a, post("/api/inventory/v1/outbounds").content(outboundBody(fromLoc, b.ref, List.of(apple), List.of(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.args[0]").value("target.not_linked"));

        // 关联了但对方还由宿主内嵌在写 → 过账 409 NOT_WRITER，A 的在手不动
        syncStore(a, b, true);
        String out = outbound(a, fromLoc, b.ref, List.of(apple), List.of(1));
        admin(put("/api/inventory/admin/v1/owners/" + b.owner + "/write-authority")
                .content("{\"writeAuthority\":\"EMBEDDED\"}")).andExpect(status().isOk());
        write(a, post("/api/inventory/v1/outbounds/" + out + "/post")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("inventory.owner.not_writer"));
        assertOnHand(a, apple, "3");
    }

    @Test
    void systemCredentialReadsBalancesAcrossOwnersAndReportsEachFailureSeparately() throws Exception {
        Tenant s1 = tenant("STORE");
        Tenant s2 = tenant("STORE");
        String loc = warehouse(s1, s1.ref, null);
        String apple = item(s1, "SKU-APPLE");
        stockIn(s1, loc, apple, 4);
        admin(put("/api/inventory/admin/v1/owners/" + s2.owner + "/write-authority")
                .content("{\"writeAuthority\":\"EMBEDDED\"}")).andExpect(status().isOk());

        // AC-33：每本账单独判；账本凭证不能调系统级接口
        String query = "{\"owners\":[{\"type\":\"STORE\",\"ref\":\"" + s1.ref + "\"},{\"type\":\"STORE\",\"ref\":\""
                + s2.ref + "\"},{\"ref\":\"NOPE\"}],\"itemRefs\":[\"SKU-APPLE\"]}";
        JsonNode result = JSON.readTree(body(mvc.perform(post("/api/inventory/v1/system/balances/query")
                .contentType(MediaType.APPLICATION_JSON).header("Authorization", systemBearer()).content(query))
                .andExpect(status().isOk()).andReturn()));
        assertThat(result.get(0).get("balances").get(0).get("itemRef").asString()).isEqualTo("SKU-APPLE");
        assertThat(new BigDecimal(result.get(0).get("balances").get(0).get("onHand").asString()))
                .isEqualByComparingTo("4");
        assertThat(result.get(1).get("error").asString()).isEqualTo("NOT_WRITER");
        assertThat(result.get(2).get("error").asString()).isEqualTo("NOT_FOUND");
        mvc.perform(post("/api/inventory/v1/system/balances/query").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", s1.bearer).content(query)).andExpect(status().isForbidden());
    }

    @Test
    void receiptHandoverNeedsExactlyOneTargetAndARegisteredReceiptPurpose() throws Exception {
        String owner = ownerId(open("SHOP", "STORE", "P" + UUID.randomUUID()).andExpect(status().isOk()));
        // AC-35：收货用途未登记 / 必填对象不止一个 / 非 RECEIPT 却带了收货用途
        for (String bad : List.of(SHOP.replace("\"receiptPurpose\":\"STORE_IN\"", "\"receiptPurpose\":\"NOPE\""),
                SHOP.replace("\"requiredTargets\":[\"STORE\"]", "\"requiredTargets\":[\"STORE\",\"STORE2\"]")
                        .replace("{\"code\":\"STORE\",\"nameKey\":\"s\",\"parentType\":null}",
                                "{\"code\":\"STORE\",\"nameKey\":\"s\",\"parentType\":null},"
                                        + "{\"code\":\"STORE2\",\"nameKey\":\"s\",\"parentType\":null}"),
                SHOP.replace("\"handover\":\"NONE\"}]", "\"handover\":\"NONE\",\"receiptPurpose\":\"PURCHASE\"}]"))) {
            admin(put("/api/inventory/admin/v1/owners/" + owner + "/profile")
                    .content("{\"profile\":" + bad + ",\"reason\":\"bad\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("inventory.profile.invalid"));
        }
    }

    @Test
    void purposesAsLongAsTheProfileAllowsCanBePostedEndToEnd() throws Exception {
        // AC-36：档案允许 32 字符的用途码，单据与流水都要装得下（评审 D1）
        String in32 = "PURCHASE_FROM_CENTRAL_KITCHEN_AB";
        String out32 = "SCRAP_FOR_QUALITY_INSPECTION_AB";
        assertThat(in32).hasSize(32);
        String profile = SHOP.replace("{\"code\":\"PURCHASE\",", "{\"code\":\"" + in32 + "\",")
                .replace("{\"code\":\"SCRAP\",", "{\"code\":\"" + out32 + "\",");
        Tenant t = tenant("STORE", profile);
        String loc = warehouse(t, t.ref, null);
        String item = item(t, "SKU-LONG");
        String in = JSON.readTree(body(write(t, post("/api/inventory/v1/inbounds").content(
                "{\"locationId\":\"" + loc + "\",\"purpose\":\"" + in32 + "\",\"lines\":[{\"itemId\":\"" + item
                        + "\",\"qty\":4}]}")).andExpect(status().isOk()).andReturn())).get("docNo").asString();
        write(t, post("/api/inventory/v1/inbounds/" + in + "/post")).andExpect(status().isOk());
        String out = JSON.readTree(body(write(t, post("/api/inventory/v1/outbounds").content(
                "{\"locationId\":\"" + loc + "\",\"purpose\":\"" + out32 + "\",\"lines\":[{\"itemId\":\"" + item
                        + "\",\"qty\":1}]}")).andExpect(status().isOk()).andReturn())).get("docNo").asString();
        write(t, post("/api/inventory/v1/outbounds/" + out + "/post")).andExpect(status().isOk());
        assertOnHand(t, item, "3");
        assertThat(body(read(t, "/api/inventory/v1/outbounds/" + out))).contains(out32);
    }

    @Test
    void inTransitListsPairedShipmentsAndInBookTransfersAndZeroReceiptsLeaveTheInbound() throws Exception {
        Tenant a = tenant("CHAIN");
        Tenant b = tenant("STORE");
        String hq = warehouse(a, "HQ", null);
        String backroom = warehouse(a, "BACK", null);
        warehouse(b, b.ref, null);
        String apple = item(a, "SKU-APPLE");
        String pear = item(a, "SKU-PEAR");
        stockIn(a, hq, apple, 10);
        stockIn(a, hq, pear, 4);
        syncStore(a, b, true);
        String out = outbound(a, hq, b.ref, List.of(apple, pear), List.of(3, 2));
        write(a, post("/api/inventory/v1/outbounds/" + out + "/post")).andExpect(status().isOk());
        String transfer = transfers.create(a.owner, hq, backroom, List.of(new TransferService.Line(apple, 1)), "T");
        transfers.ship(a.owner, transfer, null, null, null, "T");

        // AC-38：没开出库池的账本也能查；成对与调拨各自一类、带单号
        JsonNode transit = JSON.readTree(body(read(a, "/api/inventory/v1/in-transit")));
        assertThat(transit.findValuesAsString("kind")).containsExactlyInAnyOrder("PAIRED", "PAIRED", "TRANSFER");
        assertThat(transit.findValuesAsString("docNo")).contains(out, transfer);
        for (JsonNode row : transit) {
            if ("PAIRED".equals(row.get("kind").asString())) {
                assertThat(row.get("rootTargetType").asString()).isEqualTo("STORE");
                assertThat(row.get("rootTargetRef").asString()).isEqualTo(b.ref);
            } else {
                assertThat(row.get("rootTargetRef").asString()).isEqualTo(backroom);
            }
        }

        // AC-37：梨一件没收到 → 不在入库单上，总数 = 实收；收完后不再在途
        JsonNode pair = JSON.readTree(body(read(b, "/api/inventory/v1/paired-transfers?direction=IN"))).get(0);
        String inboundNo = pair.get("to").get("docNo").asString();
        String appleAtB = pair.get("lines").get(0).get("toItemId").asString();
        String pearAtB = pair.get("lines").get(1).get("toItemId").asString();
        // AC-44：收货方按宿主物料号确认，不需要知道自己账本里的内部号
        write(b, post("/api/inventory/v1/inbounds/" + inboundNo + "/receive")
                .content("{\"lines\":[{\"itemRef\":\"SKU-APPLE\",\"qty\":3}]}")).andExpect(status().isOk());
        String inbound = body(read(b, "/api/inventory/v1/inbounds/" + inboundNo));
        assertThat(inbound).contains(appleAtB).doesNotContain(pearAtB).contains("POSTED");
        assertOnHand(b, appleAtB, "3");
        assertThat(JSON.readTree(body(read(a, "/api/inventory/v1/in-transit"))).findValuesAsString("kind"))
                .containsExactly("TRANSFER");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private record Tenant(String owner, String ref, String bearer) {
    }

    private Tenant tenant(String type) throws Exception {
        return tenant(type, SHOP);
    }

    private Tenant tenant(String type, String profile) throws Exception {
        String ref = type.charAt(0) + UUID.randomUUID().toString().substring(0, 12);
        String owner = ownerId(open("SHOP", type, ref).andExpect(status().isOk()));
        admin(put("/api/inventory/admin/v1/owners/" + owner + "/profile")
                .content("{\"profile\":" + profile + ",\"reason\":\"init\"}")).andExpect(status().isOk());
        JsonNode issued = JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners/" + owner + "/credentials")
                .content("{\"name\":\"t\"}")).andExpect(status().isOk()).andReturn()));
        return new Tenant(owner, ref,
                "Bearer " + issued.get("appKey").asString() + ":" + issued.get("appSecret").asString());
    }

    private ResultActions open(String system, String type, String ref) throws Exception {
        return admin(post("/api/inventory/admin/v1/owners").content("{\"systemCode\":\"" + system
                + "\",\"externalType\":\"" + type + "\",\"externalRef\":\"" + ref + "\",\"name\":\"n\"}"));
    }

    private String systemBearer() throws Exception {
        JsonNode issued = JSON.readTree(body(admin(post("/api/inventory/admin/v1/systems/SHOP/credentials")
                .content("{\"name\":\"shop\"}")).andExpect(status().isOk()).andReturn()));
        return "Bearer " + issued.get("appKey").asString() + ":" + issued.get("appSecret").asString();
    }

    private String warehouse(Tenant t, String externalRef, String holderRef) throws Exception {
        String body = "{\"name\":\"" + externalRef + "\",\"externalRef\":\"" + externalRef + "\""
                + (holderRef == null ? "" : ",\"holderRef\":\"" + holderRef + "\"") + "}";
        return JSON.readTree(body(write(t, post("/api/inventory/v1/warehouses").content(body))
                .andExpect(status().isOk()).andReturn())).get("locationId").asString();
    }

    private String item(Tenant t, String ref) throws Exception {
        return JSON.readTree(body(write(t, put("/api/inventory/v1/items/by-ref/" + ref)
                        .content("{\"name\":\"" + ref + "\"}")).andExpect(status().isOk()).andReturn()))
                .get("itemId").asString();
    }

    private void stockIn(Tenant t, String location, String item, int qty) throws Exception {
        String no = JSON.readTree(body(write(t, post("/api/inventory/v1/inbounds").content(
                "{\"locationId\":\"" + location + "\",\"purpose\":\"PURCHASE\",\"lines\":[{\"itemId\":\"" + item
                        + "\",\"qty\":" + qty + "}]}")).andExpect(status().isOk()).andReturn()))
                .get("docNo").asString();
        write(t, post("/api/inventory/v1/inbounds/" + no + "/post")).andExpect(status().isOk());
    }

    private void syncStore(Tenant from, Tenant store, boolean linked) throws Exception {
        String link = linked ? ",\"linkedOwner\":{\"type\":\"STORE\",\"ref\":\"" + store.ref
                + "\"},\"linkedLocationRef\":\"" + store.ref + "\"" : "";
        write(from, put("/api/inventory/v1/external-targets/STORE/" + store.ref)
                .content("{\"name\":\"store\",\"sourceVersion\":" + System.nanoTime() + link + "}"))
                .andExpect(status().isOk());
    }

    private String outbound(Tenant t, String location, String storeRef, List<String> items, List<Integer> qty)
            throws Exception {
        return JSON.readTree(body(write(t, post("/api/inventory/v1/outbounds")
                        .content(outboundBody(location, storeRef, items, qty)))
                .andExpect(status().isOk()).andReturn())).get("docNo").asString();
    }

    private static String outboundBody(String location, String storeRef, List<String> items, List<Integer> qty) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            lines.append(i == 0 ? "" : ",").append("{\"itemId\":\"").append(items.get(i)).append("\",\"qty\":")
                    .append(qty.get(i)).append('}');
        }
        return "{\"locationId\":\"" + location + "\",\"purpose\":\"STORE_OUT\",\"targets\":{\"STORE\":\""
                + storeRef + "\"},\"lines\":[" + lines + "]}";
    }

    private void assertOnHand(Tenant t, String item, String expected) throws Exception {
        JsonNode rows = JSON.readTree(body(read(t, "/api/inventory/v1/balances?itemId=" + item)));
        BigDecimal onHand = BigDecimal.ZERO;
        for (JsonNode r : rows) {
            onHand = onHand.add(new BigDecimal(r.get("onHand").asString()));
        }
        assertThat(onHand).isEqualByComparingTo(expected);
    }

    private ResultActions write(Tenant t, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).header("Authorization", t.bearer)
                .header("Idempotency-Key", "K-" + UUID.randomUUID()).header("X-Inventory-Actor", "STAFF:E1"));
    }

    private MvcResult read(Tenant t, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", t.bearer)).andExpect(status().isOk()).andReturn();
    }

    private ResultActions admin(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON)
                .header("X-Inventory-Admin-Token", "admin-test-token").header("X-Inventory-Actor", "OPS:test"));
    }

    private static String ownerId(ResultActions r) throws Exception {
        return JSON.readTree(body(r.andReturn())).get("ownerId").asString();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }
}
