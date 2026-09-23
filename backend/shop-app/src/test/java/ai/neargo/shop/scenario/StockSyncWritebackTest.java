package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.inventory.entity.InvOutbox;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboxMapper;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.InventoryEventSink;
import ai.neargo.shop.invbridge.InventoryWritebackService;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStockSyncLog;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockSyncLogMapper;
import ai.neargo.shop.product.service.StockSyncService;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestStoreCategory;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 门店库存第二期：进销存 → 商城写回（TDD-商品纳入进销存开关 §18.8）。
 *
 * <p><b>走真实的事件链</b>：接口过账 → {@code inv_outbox(DocumentPosted)} → sink 转进平台 outbox
 * （类型带 {@code INV_} 前缀）→ 写回消费者。测试里没有调度器，{@link #pump} 手动推这两段 ——
 * 与 {@code InvOutboxDispatchJob} 同一套动作，不绕过消费者直接调服务。
 *
 * <p>每个用例一家新店（单店主体，商城库存是主体级那一档）。
 */
@SpringBootTest
@ActiveProfiles("test")
class StockSyncWritebackTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String FRUIT = "CAT110";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OtpStore otpStore;
    @Autowired
    private InventoryAclService acl;
    @Autowired
    private ai.neargo.shop.event.OutboxDispatcher dispatcher;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private OutboxMapper invOutbox;
    @Autowired
    private SysOutboxMapper sysOutbox;
    @Autowired
    private List<InventoryEventSink> sinks;
    @Autowired
    private StockSyncService stockSync;
    @Autowired
    private StockSyncLogMapper syncLog;
    @Autowired
    private InventoryWritebackService writeback;

    @Test
    @DisplayName("★★★ 进货过账 → 线上可卖自动等于可用（全部可售）")
    void inboundPostingRaisesOnlineSellable() throws Exception {
        Shop s = syncingShop();
        assertThat(sellable(s)).isZero();

        postInbound(s, 10);
        pump();

        assertThat(sellable(s)).as("进货 10 件，线上应能卖 10 件 —— 不用再去商品页手改").isEqualTo(10);
        assertThat(acl.stockAt(s.entityNo, s.storeNo, s.skuNo).onHand())
                .as("写回只改商城，不许经 ADJUST 镜像回头改进销存的实存").isEqualTo(10);
    }

    @Test
    @DisplayName("★★★ 保留线下 3 件：可卖 = 可用 − 3；改规则当场重算")
    void reserveRuleHoldsBackForCounter() throws Exception {
        Shop s = syncingShop();
        postInbound(s, 10);
        pump();

        ok(put("/biz/store/" + s.storeNo + "/sell-rules")
                .content("{\"scopeType\":\"STORE\",\"ruleType\":\"RESERVE\",\"param\":3}"), s.token);
        assertThat(sellable(s)).as("本店默认保留 3 件给柜台").isEqualTo(7);

        // 单品规则盖过本店默认
        ok(put("/biz/store/" + s.storeNo + "/sell-rules")
                .content("{\"scopeType\":\"GOODS\",\"scopeRef\":\"" + s.goodsNo + "\",\"ruleType\":\"CAP\",\"param\":4}"),
                s.token);
        assertThat(sellable(s)).as("单品封顶 4 件，优先于本店默认").isEqualTo(4);
    }

    @Test
    @DisplayName("★★ 手动额度只降不升：进货不抬，实物少了才压")
    void manualQuotaOnlyGoesDown() throws Exception {
        Shop s = syncingShop();
        postInbound(s, 10);
        pump();
        ok(put("/biz/store/" + s.storeNo + "/sell-rules")
                .content("{\"scopeType\":\"GOODS\",\"scopeRef\":\"" + s.goodsNo + "\",\"ruleType\":\"MANUAL\",\"param\":6}"),
                s.token);
        assertThat(sellable(s)).as("手动 6 件：可卖 10 不会被抬，也不会被压（10 ≤ 可用 10）——等于不动").isEqualTo(10);

        // 店主改库存把线上设到 6（主体级那一档直接改 prd_sku）
        DataScopeContext.executeWithoutScope(() -> skuMapper.setStock(s.skuNo, 6));
        postInbound(s, 5);
        pump();
        assertThat(sellable(s)).as("进货不抬手动额度").isEqualTo(6);
    }

    @Test
    @DisplayName("★★★ 没做期初对齐不许开同步；没开同步的店，过账不动商城")
    void syncNeedsAlignmentAndIsOffByDefault() throws Exception {
        Shop s = shop();
        String body = send(put("/biz/store/" + s.storeNo + "/stock-sync").content("{\"enabled\":true}"), s.token);
        assertThat(json.readTree(body).get("code").asInt()).as("没对齐 → 70069").isEqualTo(70069);

        postInbound(s, 10);
        pump();
        assertThat(sellable(s)).as("默认不同步：进货不改商城").isZero();
    }

    @Test
    @DisplayName("★★ 同一张单据重投：只写一次、明细一行")
    void redeliveryIsIdempotent() throws Exception {
        Shop s = syncingShop();
        String no = postInbound(s, 10);
        pump();
        writeback.onDocumentPosted(no);
        writeback.onDocumentPosted(no);
        Long rows = DataScopeContext.executeWithoutScope(() -> syncLog.selectCount(
                Wrappers.<PrdStockSyncLog>lambdaQuery().eq(PrdStockSyncLog::getSourceRef, no)
                        .eq(PrdStockSyncLog::getSkuNo, s.skuNo)));
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 下单锁定的镜像还没投到进销存：先不写回（否则会把刚锁掉的那件再放出来）")
    void waitsForMirrorToCatchUp() throws Exception {
        Shop s = syncingShop();
        postInbound(s, 10);
        pump();

        SysOutbox pending = new SysOutbox();
        pending.setEventNo("EVT-TEST-" + s.skuNo);
        pending.setAggregateType("INVENTORY");
        pending.setAggregateId("L-TEST");
        pending.setEventType("INV_MIRROR_RESERVE");
        pending.setPayload("{\"type\":\"INV_MIRROR_RESERVE\",\"ref\":\"L-TEST\",\"items\":[{\"skuNo\":\""
                + s.skuNo + "\",\"qty\":1,\"storeNo\":\"" + s.storeNo + "\"}]}");
        pending.setStatus(SysOutbox.PENDING);
        pending.setRetryCount(0);
        pending.setCreatedAt(java.time.LocalDateTime.now());
        pending.setNextRetryAt(java.time.LocalDateTime.now().plusHours(1));
        sysOutbox.insert(pending);
        try {
            assertThatThrownBy(() -> writeback.syncStore(s.entityNo, s.storeNo, "TEST:" + s.skuNo, false))
                    .isInstanceOf(InventoryWritebackService.MirrorNotCaughtUp.class);
        } finally {
            sysOutbox.deleteById(pending.getId());
        }
    }

    @Test
    @DisplayName("★★★ 同步开着时「改库存」改的是线上额度，不动实存（§8）")
    void editStockBecomesOnlineQuota() throws Exception {
        Shop s = syncingShop();
        postInbound(s, 10);
        pump();
        assertThat(sellable(s)).isEqualTo(10);

        ok(post("/biz/goods/" + s.goodsNo + "/stock")
                .content("{\"skuNo\":\"" + s.skuNo + "\",\"stock\":4}"), s.token);

        assertThat(sellable(s)).as("线上放 4 件").isEqualTo(4);
        assertThat(acl.stockAt(s.entityNo, s.storeNo, s.skuNo).onHand())
                .as("实物一件都没动 —— 实存只由单据改").isEqualTo(10);

        // 再进一笔货：手动额度只降不升，仍是 4
        postInbound(s, 5);
        pump();
        assertThat(sellable(s)).as("进货不抬手动额度").isEqualTo(4);
    }

    @Test
    @DisplayName("★★ 没开同步的店：「改库存」还是直接改商城库存（老行为一个字没变）")
    void editStockStaysPlainWhenSyncOff() throws Exception {
        Shop s = shop();
        ok(post("/biz/goods/" + s.goodsNo + "/stock")
                .content("{\"skuNo\":\"" + s.skuNo + "\",\"stock\":7}"), s.token);
        assertThat(sellable(s)).isEqualTo(7);
    }

    @Test
    @DisplayName("★★★ 线下卖出 8 件：实存 −8，线上跟着降 —— 柜台卖掉的货不会被线上再卖一次")
    void offlineSaleCutsOnHandAndOnlineStock() throws Exception {
        Shop s = syncingShop();
        postInbound(s, 10);
        pump();
        assertThat(sellable(s)).isEqualTo(10);

        String docNo = ok(post("/biz/store/" + s.storeNo + "/offline-sale")
                .content("{\"lines\":[{\"skuNo\":\"" + s.skuNo + "\",\"qty\":8}]}"), s.token)
                .get("docNo").asString();
        pump();

        assertThat(acl.stockAt(s.entityNo, s.storeNo, s.skuNo).onHand()).as("实存 10 − 8").isEqualTo(2);
        assertThat(sellable(s)).as("线上可卖跟着降到 2 —— 没有这一步就会超卖").isEqualTo(2);

        // 报表口径（§9）：线下卖出算「销」，不算兜底的「调」—— 归错了报表会说「没怎么卖，倒是调了很多」
        JsonNode monthly = ok(get("/biz/inventory/report/monthly")
                .param("month", java.time.YearMonth.now().toString()), s.token);
        assertThat(monthly.get("sold").asInt()).as("线下卖出 8 件计入「销」").isEqualTo(8);
        assertThat(monthly.get("balanced").asBoolean()).as("分类之和仍等于净变动").isTrue();

        JsonNode rows = ok(get("/biz/store/" + s.storeNo + "/offline-sale"), s.token);
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).get("docNo").asString()).isEqualTo(docNo);
        assertThat(rows.get(0).get("totalQty").asInt()).isEqualTo(8);
        assertThat(rows.get(0).get("revoked").asBoolean()).isFalse();
        assertThat(rows.get(0).get("items").get(0).get("qty").asInt()).isEqualTo(8);
        assertThat(rows.get(0).get("items").get(0).get("skuNo").asString()).isEqualTo(s.skuNo);
    }

    @Test
    @DisplayName("★★ 撤销线下卖出：货加回来，原单留着，不许撤两次")
    void offlineSaleCanBeRevokedOnce() throws Exception {
        Shop s = syncingShop();
        postInbound(s, 10);
        pump();
        String docNo = ok(post("/biz/store/" + s.storeNo + "/offline-sale")
                .content("{\"lines\":[{\"skuNo\":\"" + s.skuNo + "\",\"qty\":3}]}"), s.token)
                .get("docNo").asString();
        pump();
        assertThat(sellable(s)).isEqualTo(7);

        ok(post("/biz/store/" + s.storeNo + "/offline-sale/" + docNo + "/revoke"), s.token);
        pump();
        assertThat(acl.stockAt(s.entityNo, s.storeNo, s.skuNo).onHand()).as("撤销把 3 件加回来").isEqualTo(10);
        assertThat(sellable(s)).isEqualTo(10);

        JsonNode rows = ok(get("/biz/store/" + s.storeNo + "/offline-sale"), s.token);
        assertThat(rows.size()).as("原单留着 —— 删单等于账上从没发生过").isEqualTo(1);
        assertThat(rows.get(0).get("revoked").asBoolean()).isTrue();

        mvc().perform(post("/biz/store/" + s.storeNo + "/offline-sale/" + docNo + "/revoke")
                        .header("Authorization", "Bearer " + s.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("★★ 期初对齐「以商城为准」：实存调成商城的数")
    void alignToMallAdjustsOnHand() throws Exception {
        Shop s = shop();
        DataScopeContext.executeWithoutScope(() -> skuMapper.setStock(s.skuNo, 8));
        JsonNode rows = ok(get("/biz/store/" + s.storeNo + "/stock-alignment"), s.token);
        JsonNode row = rows.get(0);
        assertThat(row.get("mallStock").asInt()).isEqualTo(8);
        assertThat(row.get("diff").asInt()).as("进销存 0、商城 8").isEqualTo(-8);

        JsonNode r = ok(post("/biz/store/" + s.storeNo + "/stock-alignment/confirm").content("{\"mode\":\"MALL\"}"),
                s.token);
        assertThat(r.get("adjusted").asInt()).isEqualTo(1);
        assertThat(acl.stockAt(s.entityNo, s.storeNo, s.skuNo).onHand()).isEqualTo(8);
        assertThat(ok(get("/biz/store/" + s.storeNo + "/stock-sync"), s.token).get("state").asString())
                .as("对齐后是「已对齐未开启」—— 打开是店主另一个动作").isEqualTo("ALIGNED");
    }

    // ------------------------------------------------------------------ 种子

    private record Shop(String token, String entityNo, String storeNo, String goodsNo, String skuNo) {
    }

    /** 一家对齐过、开了同步的店 */
    private Shop syncingShop() throws Exception {
        Shop s = shop();
        ok(post("/biz/store/" + s.storeNo + "/stock-alignment/confirm").content("{\"mode\":\"COUNT\"}"), s.token);
        JsonNode st = ok(put("/biz/store/" + s.storeNo + "/stock-sync").content("{\"enabled\":true}"), s.token);
        assertThat(st.get("state").asString()).isEqualTo("SYNCING");
        return s;
    }

    private Shop shop() throws Exception {
        int seq = SEQ.incrementAndGet();
        String token = merchant("1260936%04d".formatted(seq), "库存同步测试店-" + seq);
        TestStoreCategory.open(mvc(), json, token, FRUIT);
        JsonNode ctx = ok(get("/biz/context"), token);
        String entityNo = ctx.get("merchantNo").asString();
        String storeNo = ctx.get("currentStoreNo").asString();
        String goodsNo = ok(post("/biz/goods/save").content("{\"categoryNo\":\"" + FRUIT + "\",\"title\":\"同步香梨"
                + seq + "\",\"subtitle\":\"测试\",\"cover\":\"🍐\",\"images\":[],\"specGroups\":[],"
                + "\"skus\":[{\"optionValues\":[],\"price\":5000,\"stock\":0,\"saleUnit\":\"箱\"}]}"), token)
                .get("goodsNo").asString();
        String skuNo = DataScopeContext.executeWithoutScope(() -> skuMapper.selectList(
                Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getGoodsNo, goodsNo)).get(0).getSkuNo());
        pump();
        assertThat(acl.itemIdOfSku(skuNo)).as("物料应已投影").isNotNull();
        return new Shop(token, entityNo, storeNo, goodsNo, skuNo);
    }

    private String postInbound(Shop s, int qty) throws Exception {
        String no = ok(post("/biz/inventory/inbounds").content("""
                {"sourceType":"PURCHASE","supplierName":"果园直供","occurredAt":"2026-09-22T00:00:00",
                 "lines":[{"itemId":"%s","qty":%d,"uom":"箱","unitCostMinor":3000}]}
                """.formatted(acl.itemIdOfSku(s.skuNo), qty)), s.token).get("no").asString();
        ok(post("/biz/inventory/inbounds/" + no + "/post"), s.token);
        return no;
    }

    private int sellable(Shop s) {
        return stockSync.sellableOf(s.storeNo, s.skuNo).sellable();
    }

    /** 推两段 outbox 到清空：inv_outbox → sink（与 InvOutboxDispatchJob 同一动作），再平台 outbox → 消费者 */
    private void pump() {
        for (int round = 0; round < 10; round++) {
            List<InvOutbox> pending = invOutbox.selectList(Wrappers.<InvOutbox>lambdaQuery()
                    .eq(InvOutbox::getStatus, "PENDING").orderByAsc(InvOutbox::getId));
            for (InvOutbox e : pending) {
                boolean ok = sinks.stream().allMatch(k -> k.deliver(e.getEventNo(), e.getOwnerId(),
                        e.getEventType(), e.getPayload()));
                if (ok) {
                    e.setStatus("SENT");
                    invOutbox.updateById(e);
                }
            }
            int sent = dispatcher.dispatchPending();
            if (pending.isEmpty() && sent == 0) {
                return;
            }
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private String send(MockHttpServletRequestBuilder req, String token) throws Exception {
        return mvc().perform(req.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private JsonNode ok(MockHttpServletRequestBuilder req, String token) throws Exception {
        String body = send(req, token);
        JsonNode env = json.readTree(body);
        assertThat(env.get("code").asInt()).as("业务码：%s", body).isZero();
        return env.get("data");
    }

    private String merchant(String phone, String name) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        String bd = json.readTree(mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bd\",\"password\":\"bd123\"}"))
                .andReturn().getResponse().getContentAsString()).get("data").get("token").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }
}
