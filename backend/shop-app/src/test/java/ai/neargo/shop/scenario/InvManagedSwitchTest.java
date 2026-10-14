package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.inventory.entity.InvItem;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemMapper;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
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

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 记库存开关（TDD-商品纳入进销存开关 §3，原型 inv-managed-switch s02–s07）。
 *
 * <p>打真实 HTTP 层与真实 outbox：设置存下来<b>不等于</b>进销存那边真的建 / 停了物料，
 * 两边之间隔着一次投递，所以每条都推一把 {@code dispatchPending} 再看物料状态。
 *
 * <p>水果用 CAT110（生鲜，平台默认记），服务用 CAT330（洗衣洗鞋，平台默认不记）。
 * 每个用例一家新店，不共享种子。
 */
@SpringBootTest
@ActiveProfiles("test")
class InvManagedSwitchTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String FRUIT = "CAT110";
    private static final String SERVICE = "CAT330";

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
    private ItemMapper itemMapper;

    @Test
    @DisplayName("★★★ 设置页：各门店经营类目一类一行；服务类默认不记、生鲜默认记，都标成「默认」")
    void categoryRowsCarryPlatformDefaults() throws Exception {
        String token = shop();
        TestStoreCategory.open(mvc(), json, token, SERVICE);

        JsonNode rows = ok(get("/biz/inventory/category-setting"), token);
        JsonNode fruit = row(rows, FRUIT);
        JsonNode service = row(rows, SERVICE);
        assertThat(fruit).as("经营类目里的水果要出现在设置页").isNotNull();
        assertThat(service).as("经营类目里的洗衣要出现在设置页").isNotNull();
        assertThat(fruit.get("managed").asBoolean()).as("生鲜平台默认记库存").isTrue();
        assertThat(service.get("managed").asBoolean()).as("服务平台默认不记库存").isFalse();
        assertThat(service.get("isDefault").asBoolean()).as("没设过 → 界面写「默认不记」").isTrue();
        for (String f : new String[]{"categoryNo", "name", "managed", "isDefault", "goodsCount"}) {
            assertThat(fruit.has(f)).as("CategorySetting 读 %s", f).isTrue();
        }
    }

    @Test
    @DisplayName("★★★ 关一个没有库存的品类：直接改好，物料停用；再打开原样恢复")
    void closingEmptyCategoryArchivesAndReopenRestores() throws Exception {
        String token = shop();
        Goods g = goods(token, "香梨空仓");
        settle();
        assertThat(itemStatus(g.skuNo)).as("记库存的货建品即上账").isEqualTo("ACTIVE");

        JsonNode r = ok(put("/biz/inventory/category-setting/" + FRUIT)
                .content("{\"managed\":false}"), token);
        assertThat(r.get("status").asString()).isEqualTo("DONE");
        settle();
        assertThat(itemStatus(g.skuNo)).as("关掉品类 → 物料停用，不再出现在进货盘点里").isEqualTo("ARCHIVED");
        assertThat(mode(token, g.goodsNo).get("managed").asBoolean()).isFalse();

        // 保存一次商品：投影事件带着「不记」，不许把物料又建回来
        resave(token, g, "香梨空仓");
        settle();
        assertThat(itemStatus(g.skuNo)).as("编辑一次商品就把停用的物料复活 = 开关形同虚设").isEqualTo("ARCHIVED");

        ok(put("/biz/inventory/category-setting/" + FRUIT).content("{\"managed\":true}"), token);
        settle();
        assertThat(itemStatus(g.skuNo)).as("改回记库存 → 原样恢复").isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("★★★ 关一个还有库存的品类：先要确认、什么都不改；确认后停用，余额原样留着")
    void closingStockedCategoryNeedsConfirmAndKeepsBalance() throws Exception {
        String token = shop();
        Goods g = goods(token, "阳光玫瑰");
        settle();
        String inbound = inbound(token, g.skuNo, 12);
        ok(post("/biz/inventory/inbounds/" + inbound + "/post"), token);

        JsonNode r = ok(put("/biz/inventory/category-setting/" + FRUIT)
                .content("{\"managed\":false}"), token);
        assertThat(r.get("status").asString()).isEqualTo("NEEDS_CONFIRM");
        assertThat(r.get("goods").get(0).get("goodsNo").asString()).isEqualTo(g.goodsNo);
        assertThat(r.get("goods").get(0).get("onHand").asInt()).as("s03 列出实存").isEqualTo(12);
        assertThat(row(ok(get("/biz/inventory/category-setting"), token), FRUIT).get("managed").asBoolean())
                .as("没确认之前一个字都不许改").isTrue();

        r = ok(put("/biz/inventory/category-setting/" + FRUIT)
                .content("{\"managed\":false,\"confirm\":true}"), token);
        assertThat(r.get("status").asString()).isEqualTo("DONE");
        settle();
        assertThat(itemStatus(g.skuNo)).as("有库存也停：店主已经确认过").isEqualTo("ARCHIVED");
        assertThat(acl.stateOf(entityOf(token), java.util.List.of(g.skuNo)).get(g.skuNo).onHand())
                .as("停用不是报损：余额原样留着").isEqualTo(12);
    }

    @Test
    @DisplayName("★★★ 有未收货的进货单：拒绝，带上单号；带 confirm 也不行")
    void openInboundBlocksEvenWithConfirm() throws Exception {
        String token = shop();
        Goods g = goods(token, "香梨在途");
        settle();
        String inbound = inbound(token, g.skuNo, 5);   // 草稿，不过账

        for (String body : new String[]{"{\"managed\":false}", "{\"managed\":false,\"confirm\":true}"}) {
            JsonNode r = ok(put("/biz/inventory/category-setting/" + FRUIT).content(body), token);
            assertThat(r.get("status").asString()).as(body).isEqualTo("BLOCKED");
            JsonNode b = r.get("goods").get(0).get("blockers").get(0);
            assertThat(b.get("kind").asString()).isEqualTo("INBOUND");
            assertThat(b.get("docNo").asString()).as("s04 要能点进那张单").isEqualTo(inbound);
        }
        settle();
        assertThat(itemStatus(g.skuNo)).as("被拒 = 什么都没改").isEqualTo("ACTIVE");
        assertThat(row(ok(get("/biz/inventory/category-setting"), token), FRUIT).get("managed").asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName("★★ 单件优先于品类：品类记、这件设不记；品类再怎么拨它都不动")
    void goodsOverrideBeatsCategory() throws Exception {
        String token = shop();
        Goods a = goods(token, "代卖香蕉");
        Goods b = goods(token, "自营苹果");
        settle();

        JsonNode r = ok(put("/biz/goods/" + a.goodsNo + "/inv-mode").content("{\"mode\":\"OFF\"}"), token);
        assertThat(r.get("status").asString()).isEqualTo("DONE");
        settle();
        assertThat(itemStatus(a.skuNo)).isEqualTo("ARCHIVED");
        assertThat(itemStatus(b.skuNo)).as("只动这一件").isEqualTo("ACTIVE");

        JsonNode m = mode(token, a.goodsNo);
        assertThat(m.get("mode").asString()).isEqualTo("OFF");
        assertThat(m.get("categoryManaged").asBoolean()).as("s05 括号里写的是品类那一级").isTrue();

        // 关掉再打开品类：设过「不记」的那件不跟着动
        ok(put("/biz/inventory/category-setting/" + FRUIT).content("{\"managed\":false}"), token);
        ok(put("/biz/inventory/category-setting/" + FRUIT).content("{\"managed\":true}"), token);
        settle();
        assertThat(itemStatus(a.skuNo)).as("单件设置优先").isEqualTo("ARCHIVED");
        assertThat(itemStatus(b.skuNo)).isEqualTo("ACTIVE");

        ok(put("/biz/goods/" + a.goodsNo + "/inv-mode").content("{\"mode\":\"INHERIT\"}"), token);
        settle();
        assertThat(itemStatus(a.skuNo)).as("改回跟随品类（记）→ 恢复").isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("★★★ 不记库存的品类下新建商品：进销存里不建物料")
    void newGoodsInUntrackedCategoryNeverLandsOnTheBooks() throws Exception {
        String token = shop();
        ok(put("/biz/inventory/category-setting/" + FRUIT).content("{\"managed\":false}"), token);
        Goods g = goods(token, "临时代卖橙子");
        settle();
        assertThat(acl.itemIdOfSku(g.skuNo))
                .as("不记库存的货出现在库存页 = 这一期要解决的那件事没解决")
                .isNull();
    }

    // ------------------------------------------------------------------ 种子

    /**
     * 推到队列清空。**一次 dispatchPending 只取最老的 200 条** —— 全量跑时别的用例留下的积压
     * 会把这里刚发的事件排在后面，推一把看到的是「还没投到」，而断言读起来像「开关没生效」。
     * 上限 50 轮：一直失败重投的事件不该让这里死循环。
     */
    private void settle() {
        for (int i = 0; i < 50 && dispatcher.dispatchPending() > 0; i++) {
            // 继续推
        }
    }

    private record Goods(String goodsNo, String skuNo) {
    }

    private String shop() throws Exception {
        int seq = SEQ.incrementAndGet();
        String token = merchant("1260934%04d".formatted(seq), "记库存开关测试店-" + seq);
        TestStoreCategory.open(mvc(), json, token, FRUIT);
        return token;
    }

    private Goods goods(String token, String title) throws Exception {
        String goodsNo = save(token, null, title);
        String skuNo = DataScopeContext.executeWithoutScope(() -> skuMapper.selectList(
                Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getGoodsNo, goodsNo)).get(0).getSkuNo());
        return new Goods(goodsNo, skuNo);
    }

    private void resave(String token, Goods g, String title) throws Exception {
        save(token, g, title);
    }

    private String save(String token, Goods existing, String title) throws Exception {
        String head = existing == null ? "" : "\"goodsNo\":\"" + existing.goodsNo + "\",";
        String sku = existing == null ? "" : "\"skuNo\":\"" + existing.skuNo + "\",";
        JsonNode d = ok(post("/biz/goods/save").content("{" + head + "\"categoryNo\":\"" + FRUIT + "\",\"title\":\""
                + title + "\",\"subtitle\":\"测试\",\"cover\":\"🍐\",\"images\":[],\"specGroups\":[],"
                + "\"skus\":[{" + sku + "\"optionValues\":[],\"price\":5000,\"stock\":0,\"saleUnit\":\"箱\"}]}"), token);
        return d.get("goodsNo").asString();
    }

    private String inbound(String token, String skuNo, int qty) throws Exception {
        String itemId = acl.itemIdOfSku(skuNo);
        assertThat(itemId).as("进货前物料应已投影：%s", skuNo).isNotNull();
        return ok(post("/biz/inventory/inbounds").content("""
                {"sourceType":"PURCHASE","supplierName":"果园直供",
                 "occurredAt":"2026-09-21T00:00:00",
                 "lines":[{"itemId":"%s","qty":%d,"uom":"箱","unitCostMinor":3000}]}
                """.formatted(itemId, qty)), token).get("no").asString();
    }

    private String itemStatus(String skuNo) {
        String itemId = acl.itemIdOfSku(skuNo);
        assertThat(itemId).as("物料应已投影：%s", skuNo).isNotNull();
        return itemMapper.selectOne(Wrappers.<InvItem>lambdaQuery().eq(InvItem::getItemId, itemId)).getStatus();
    }

    private JsonNode mode(String token, String goodsNo) throws Exception {
        return ok(get("/biz/goods/inv-mode?goodsNos=" + goodsNo), token).get(0);
    }

    private String entityOf(String token) throws Exception {
        return ok(get("/biz/context"), token).get("merchantNo").asString();
    }

    private static JsonNode row(JsonNode rows, String categoryNo) {
        for (JsonNode r : rows) {
            if (categoryNo.equals(r.get("categoryNo").asString())) {
                return r;
            }
        }
        return null;
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private JsonNode ok(MockHttpServletRequestBuilder req, String token) throws Exception {
        String body = mvc().perform(req.header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
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
