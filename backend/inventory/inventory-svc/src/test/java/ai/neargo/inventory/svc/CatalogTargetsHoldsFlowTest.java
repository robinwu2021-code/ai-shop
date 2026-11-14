package ai.neargo.inventory.svc;

import ai.neargo.inventory.domain.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.inventory.domain.service.StockPostingService;
import ai.neargo.inventory.domain.service.StockPostingService.Line;
import ai.neargo.inventory.domain.service.StockPostingService.PostingDoc;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.inventory.domain.support.InvKeys;
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
 * M2 T2.4 / T2.5 端到端：物料推送（跟踪方式、放行策略、有流水后不可改）、外部目标（档案类型与层级、版本）、
 * 冻结（不可用数量、同一件至多计 1）、扣减守卫（不侵占预留与冻结）、仓库停用守卫。
 */
@SpringBootTest(properties = {
        "inventory.flyway-locations=classpath:db/inventory-h2",
        "inventory.datasource.url=jdbc:h2:mem:inv_catalog;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "inventory.datasource.username=sa",
        "inventory.datasource.password=",
        "inventory.instance.code=TEST",
        "inventory.instance.allowed-systems=SHOP,SHAREHUB,CHARGING",
        "inventory.admin.token=admin-test-token"})
class CatalogTargetsHoldsFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SHAREHUB = """
            {"schemaVersion":1,"capabilities":["SERIAL","DISPATCH_POOL"],
             "targetTypes":[{"code":"MERCHANT","nameKey":"m","parentType":null},
                            {"code":"SITE","nameKey":"s","parentType":"MERCHANT"},
                            {"code":"PERSON","nameKey":"p","parentType":null}],
             "inboundPurposes":[{"code":"PURCHASE","basisRequired":false,"attachmentRequired":false}],
             "outboundPurposes":[{"code":"ISSUE","requiredTargets":["MERCHANT","PERSON"],
                                  "optionalTargets":["SITE"],"handover":"POOL"}],
             "gateRefTypes":["QC"],"countReview":{"required":true,"reviewerMustDiffer":true}}""";
    private static final String SHOP = """
            {"schemaVersion":1,"capabilities":["RESERVATION"],"targetTypes":[],"inboundPurposes":[],
             "outboundPurposes":[],"gateRefTypes":[],"countReview":{"required":false,"reviewerMustDiffer":false}}""";

    @Autowired WebApplicationContext context;
    @Autowired List<FilterRegistrationBean<?>> filters;
    @Autowired StockPostingService posting;
    @Autowired BalanceMapper balanceMapper;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        var builder = MockMvcBuilders.webAppContextSetup(context);
        filters.forEach(f -> builder.addFilters(f.getFilter()));
        mvc = builder.build();
    }

    @Test
    void itemsFollowTheProfileAndFreezeTrackingOnceTheyHaveLedger() throws Exception {
        Tenant t = tenant("SHAREHUB", SHAREHUB);
        String cabinet = itemId(upsertItem(t, "CAB-1",
                "{\"name\":\"柜\",\"trackingMode\":\"SERIAL\",\"gatePolicy\":\"REF_REQUIRED\",\"gateRefType\":\"QC\"}")
                .andExpect(status().isOk()));
        upsertItem(t, "CAB-2", "{\"name\":\"柜\",\"gatePolicy\":\"REF_REQUIRED\",\"gateRefType\":\"ACCEPTANCE\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.args[0]").value("gateRefType"));
        upsertItem(t, "CAB-3", "{\"name\":\"柜\",\"trackingMode\":\"BATCH\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.args[0]").value("trackingMode"));

        // 有流水之前可以改
        String pb = itemId(upsertItem(t, "PB", "{\"name\":\"宝\"}").andExpect(status().isOk()));
        assertThat(itemId(upsertItem(t, "PB", "{\"name\":\"宝\",\"trackingMode\":\"SERIAL\"}")
                .andExpect(status().isOk()))).isEqualTo(pb);
        // 有流水之后，跟踪方式不可改
        String wh = warehouse(t, "W1");
        stockIn(t, pb, wh, 1);
        upsertItem(t, "PB", "{\"name\":\"宝\",\"trackingMode\":\"QUANTITY\"}").andExpect(status().isConflict());
        // 改名照常
        upsertItem(t, "PB", "{\"name\":\"充电宝\",\"trackingMode\":\"SERIAL\"}").andExpect(status().isOk());

        assertThat(JSON.readTree(body(read(t, "/api/inventory/v1/items"))).findValuesAsString("itemId"))
                .contains(cabinet, pb);

        // 商城档案没开 SERIAL：逐件物料建不了
        Tenant shop = tenant("SHOP", SHOP);
        upsertItem(shop, "SKU-1", "{\"name\":\"米\",\"trackingMode\":\"SERIAL\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("inventory.capability.disabled"));
    }

    @Test
    void targetsUseProfileTypesHierarchyAndSourceVersion() throws Exception {
        Tenant t = tenant("SHAREHUB", SHAREHUB);
        syncTarget(t, "MERCHANT", "AG001", "{\"name\":\"商家甲\",\"sourceVersion\":1}").andExpect(status().isOk());
        syncTarget(t, "SITE", "ST01", "{\"name\":\"站点一\",\"parentRef\":\"AG001\",\"sourceVersion\":1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.parentType").value("MERCHANT"));
        // 站点必须挂在已同步的商家下
        syncTarget(t, "SITE", "ST02", "{\"name\":\"站点二\",\"sourceVersion\":1}").andExpect(status().isBadRequest());
        syncTarget(t, "SITE", "ST03", "{\"name\":\"站点三\",\"parentRef\":\"NOPE\",\"sourceVersion\":1}")
                .andExpect(status().isBadRequest());
        // 档案里没有的类型
        syncTarget(t, "PROJECT", "P1", "{\"name\":\"项目\",\"sourceVersion\":1}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.args[0]").value("type"));
        // 新版本改名生效；旧版本晚到被忽略
        syncTarget(t, "MERCHANT", "AG001", "{\"name\":\"商家甲（新）\",\"sourceVersion\":3}").andExpect(status().isOk());
        syncTarget(t, "MERCHANT", "AG001", "{\"name\":\"商家甲（旧）\",\"sourceVersion\":2}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("商家甲（新）"));

        JsonNode sites = JSON.readTree(body(read(t, "/api/inventory/v1/external-targets?parentRef=AG001")));
        assertThat(sites.findValuesAsString("ref")).containsExactly("ST01");
    }

    @Test
    void holdsReduceAvailabilityCountEachUnitOnceAndBlockDeduction() throws Exception {
        Tenant t = tenant("SHAREHUB", SHAREHUB);
        String item = itemId(upsertItem(t, "SPARE", "{\"name\":\"线材\"}").andExpect(status().isOk()));
        String wh = warehouse(t, "W-HOLD");
        stockIn(t, item, wh, 10);

        String h1 = holdId(placeHold(t, item, wh, "3").andExpect(status().isOk()));
        placeHold(t, item, wh, "2").andExpect(status().isOk());
        assertBalance(t, item, "10", "5", "5");
        // 可用只剩 5：再冻 6 会把货冻进不存在的余量
        placeHold(t, item, wh, "6").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("inventory.stock.short"));
        assertBalance(t, item, "10", "5", "5");

        // 扣减守卫（T2.5）：不能扣进冻结的那部分
        assertThat(balanceMapper.applyDelta(t.owner, item, wh, -6)).isZero();
        assertThat(balanceMapper.applyDelta(t.owner, item, wh, -5)).isOne();
        assertBalance(t, item, "5", "5", "0");

        // 解冻一条：不可用随之下降；重复解冻是空操作
        release(t, h1).andExpect(status().isOk());
        assertBalance(t, item, "5", "2", "3");
        mvc.perform(post("/api/inventory/v1/holds/" + h1 + "/release").header("Authorization", t.bearer)
                .header("Idempotency-Key", "K-" + UUID.randomUUID())).andExpect(status().isOk());
        assertBalance(t, item, "5", "2", "3");
    }

    @Test
    void ordinaryDeductionNoLongerEatsReservedStock() throws Exception {
        Tenant t = tenant("SHAREHUB", SHAREHUB);
        String item = itemId(upsertItem(t, "RSV", "{\"name\":\"预留件\"}").andExpect(status().isOk()));
        String wh = warehouse(t, "W-RSV");
        stockIn(t, item, wh, 4);
        assertThat(balanceMapper.hold(t.owner, item, wh, 3)).isOne();
        // 修复前：on_hand + delta >= 0 即放行，报废 2 件会扣掉已付款订单预留的货
        assertThat(balanceMapper.applyDelta(t.owner, item, wh, -2)).isZero();
        assertThat(balanceMapper.applyDelta(t.owner, item, wh, -1)).isOne();
        // 销售出库的顺序：先释放预留、再扣实存 —— 照常通过
        assertThat(balanceMapper.unhold(t.owner, item, wh, 3)).isOne();
        assertThat(balanceMapper.applyDelta(t.owner, item, wh, -3)).isOne();
    }

    @Test
    void warehouseCannotBeDisabledWhileItStillHoldsStockOrFreezes() throws Exception {
        Tenant t = tenant("SHAREHUB", SHAREHUB);
        String item = itemId(upsertItem(t, "DIS", "{\"name\":\"件\"}").andExpect(status().isOk()));
        String busy = warehouse(t, "W-BUSY");
        stockIn(t, item, busy, 2);
        placeHold(t, item, busy, "1").andExpect(status().isOk());
        write(t, post("/api/inventory/v1/warehouses/" + busy + "/disable")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.args[0]").value("BALANCE"))
                .andExpect(jsonPath("$.data.args[1]").value("HOLDS"));

        String empty = warehouse(t, "W-EMPTY");
        write(t, patch("/api/inventory/v1/warehouses/" + empty).content("{\"name\":\"空仓\",\"regionRef\":\"R-SZ\"}"))
                .andExpect(status().isOk());
        write(t, post("/api/inventory/v1/warehouses/" + empty + "/disable")).andExpect(status().isOk());
        JsonNode list = JSON.readTree(body(read(t, "/api/inventory/v1/warehouses")));
        for (JsonNode w : list) {
            if (w.get("locationId").asString().equals(empty)) {
                assertThat(w.get("status").asString()).isEqualTo(InvEnums.MasterStatus.DISABLED);
                assertThat(w.get("regionRef").asString()).isEqualTo("R-SZ");
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private record Tenant(String owner, String bearer) {
    }

    private Tenant tenant(String system, String profile) throws Exception {
        String owner = JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners").content(
                "{\"systemCode\":\"" + system + "\",\"externalRef\":\"" + UUID.randomUUID() + "\",\"name\":\"n\"}"))
                .andExpect(status().isOk()).andReturn())).get("ownerId").asString();
        admin(put("/api/inventory/admin/v1/owners/" + owner + "/profile")
                .content("{\"profile\":" + profile + ",\"reason\":\"init\"}")).andExpect(status().isOk());
        JsonNode issued = JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners/" + owner + "/credentials")
                .content("{\"name\":\"t\"}")).andExpect(status().isOk()).andReturn()));
        return new Tenant(owner,
                "Bearer " + issued.get("appKey").asString() + ":" + issued.get("appSecret").asString());
    }

    private String warehouse(Tenant t, String name) throws Exception {
        return JSON.readTree(body(write(t, post("/api/inventory/v1/warehouses").content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk()).andReturn())).get("locationId").asString();
    }

    private void stockIn(Tenant t, String item, String location, int qty) {
        posting.post(new PostingDoc(t.owner, InvEnums.DocKind.IN, InvKeys.next(InvKeys.INBOUND), "PURCHASE",
                LocalDateTime.now(), "TEST", List.of(new Line(1, item, location, qty, null))));
    }

    private ResultActions upsertItem(Tenant t, String ref, String spec) throws Exception {
        return write(t, put("/api/inventory/v1/items/by-ref/" + ref).content(spec));
    }

    private ResultActions syncTarget(Tenant t, String type, String ref, String sync) throws Exception {
        return write(t, put("/api/inventory/v1/external-targets/" + type + "/" + ref).content(sync));
    }

    private ResultActions placeHold(Tenant t, String item, String location, String qty) throws Exception {
        return write(t, post("/api/inventory/v1/holds").content(
                "{\"itemId\":\"" + item + "\",\"locationId\":\"" + location + "\",\"qty\":" + qty + "}"));
    }

    private ResultActions release(Tenant t, String holdId) throws Exception {
        return write(t, post("/api/inventory/v1/holds/" + holdId + "/release"));
    }

    private void assertBalance(Tenant t, String item, String onHand, String unavailable, String available)
            throws Exception {
        JsonNode b = JSON.readTree(body(read(t, "/api/inventory/v1/balances?itemId=" + item))).get(0);
        assertThat(new BigDecimal(b.get("onHand").asString())).isEqualByComparingTo(onHand);
        assertThat(new BigDecimal(b.get("unavailable").asString())).isEqualByComparingTo(unavailable);
        assertThat(new BigDecimal(b.get("available").asString())).isEqualByComparingTo(available);
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

    private static String itemId(ResultActions r) throws Exception {
        return JSON.readTree(body(r.andReturn())).get("itemId").asString();
    }

    private static String holdId(ResultActions r) throws Exception {
        return JSON.readTree(body(r.andReturn())).get("holdId").asString();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }
}
