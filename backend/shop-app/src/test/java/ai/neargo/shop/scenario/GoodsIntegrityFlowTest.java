package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestStoreCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 商品写链路的完整性：评审逐条核实过的十个缺口，每条一个用例。
 *
 * <p>共同点是<b>全部零报错</b>：SKU 编号被别家接管、发布把卖掉的库存加回去、
 * 绕开准入闸上架、审核期间线上版消失、买家看到没过审的货、页面价与扣款价不一致、
 * 过审回放一份过期草稿、乐观锁静默 0 行 —— 接口都返回成功，坏的是数据。
 */
@SpringBootTest
@ActiveProfiles("test")
class GoodsIntegrityFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.spi.product.GoodsQueryPort goodsQueryPort;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    // ------------------------------------------------------------------ ① SKU 编号接管

    @Test
    @DisplayName("★★★ 端上带一个不属于本商品的 skuNo → 拒；别家那件货的下单快照不受影响")
    void foreignSkuNoCannotBeTakenOver() throws Exception {
        String b = merchant("13700777101", "接管·受害店");
        String goodsB = createGoods(b, "受害商品", 1000, 10);
        String skuB = detail(b, goodsB).get("skus").get(0).get("skuNo").asString();
        String merchantB = merchantNoOf(b);

        String a = merchant("13700777102", "接管·攻击店");
        JsonNode r = postJson(a, "/biz/goods/save",
                "{\"categoryNo\":\"CAT210\",\"title\":\"借壳商品\",\"subtitle\":\"t\",\"cover\":\"c\","
                        + "\"images\":[],\"specGroups\":[],"
                        + "\"skus\":[{\"skuNo\":\"" + skuB + "\",\"optionValues\":[],\"price\":1,\"stock\":999}]}");
        assertThat(r.get("code").asInt()).as("别家的 skuNo 不能被当成本商品的 SKU 写进来").isNotZero();

        assertThat(jdbc.queryForObject("select count(*) from prd_sku where sku_no=? and deleted=0",
                Integer.class, skuB)).as("同一个 skuNo 只能有 B 那一行").isEqualTo(1);
        var snap = goodsQueryPort.snapshot(List.of(skuB), Map.of()).get(skuB);
        assertThat(snap.merchantNo()).as("B 的下单快照仍指向 B").isEqualTo(merchantB);
        assertThat(snap.price()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("★ 本商品自己的 skuNo 照常回传、照常更新 —— 编辑页的正常路径")
    void ownSkuNoStillUpdates() throws Exception {
        String a = merchant("13700777103", "接管·正常店");
        String goodsNo = createGoods(a, "正常商品", 1000, 10);
        String skuNo = detail(a, goodsNo).get("skus").get(0).get("skuNo").asString();
        JsonNode r = postJson(a, "/biz/goods/save", goodsBody(goodsNo, skuNo, "正常商品·改", 1200, 10));
        assertThat(r.get("code").asInt()).isZero();
        assertThat(detail(a, goodsNo).get("skus").get(0).get("price").asLong()).isEqualTo(1200L);
    }

    // ------------------------------------------------------------------ ② 保存/发布不覆盖库存

    @Test
    @DisplayName("★★★ 草稿与发布之间卖掉 40 件 → 发布后库存仍是 60，不被草稿里的 100 盖回去")
    void publishDoesNotOverwriteSoldStock() throws Exception {
        String biz = merchant("13700777201", "库存·发布店");
        String goodsNo = onSaleGoods(biz, "库存商品", 100);
        String skuNo = detail(biz, goodsNo).get("skus").get(0).get("skuNo").asString();
        // 在售商品的编辑只落草稿；草稿里抄的是打开编辑页那一刻的 100
        assertThat(postJson(biz, "/biz/goods/save", goodsBody(goodsNo, skuNo, "库存商品·改", 1000, 100))
                .get("code").asInt()).isZero();
        // 这期间卖掉 40 件
        jdbc.update("update prd_sku set stock = stock - 40 where sku_no=?", skuNo);

        withAuditOff(() -> assertThat(postJson(biz, "/biz/goods/" + goodsNo + "/publish", "{}")
                .get("code").asInt()).isZero());

        assertThat(jdbc.queryForObject("select stock from prd_sku where sku_no=? and market='CN'",
                Integer.class, skuNo)).as("发布把卖掉的 40 件加回去了 —— 超卖").isEqualTo(60);
        assertThat(jdbc.queryForObject("select title from prd_goods where goods_no=?", String.class, goodsNo))
                .as("内容照常换版").isEqualTo("库存商品·改");
    }

    @Test
    @DisplayName("★★ 保存不改已有 SKU 的库存（改库存走库存入口）；新 SKU 的初始库存不能是负数")
    void saveLeavesExistingStockAlone() throws Exception {
        String biz = merchant("13700777202", "库存·保存店");
        String goodsNo = createGoods(biz, "保存商品", 1000, 30);
        String skuNo = detail(biz, goodsNo).get("skus").get(0).get("skuNo").asString();
        assertThat(postJson(biz, "/biz/goods/save", goodsBody(goodsNo, skuNo, "保存商品", 1000, -5))
                .get("code").asInt()).isZero();
        assertThat(jdbc.queryForObject("select stock from prd_sku where sku_no=? and market='CN'",
                Integer.class, skuNo)).isEqualTo(30);

        JsonNode neg = postJson(biz, "/biz/goods/save", goodsBody(null, null, "负库存新品", 1000, -1));
        assertThat(neg.get("code").asInt()).as("新 SKU 的初始库存为负应被拒").isNotZero();
    }

    // ------------------------------------------------------------------ ③ 每条上架路径都过准入闸

    @Test
    @DisplayName("★★★ 免审提交：类目被禁售的主体提交不能直接上架")
    void auditOffSubmitHonorsAdmission() throws Exception {
        String biz = merchant("13700777301", "准入·免审提交店");
        String goodsNo = createGoods(biz, "禁售提交品", 1000, 5);
        withBannedCategory(biz, "CAT210", () -> withAuditOff(() -> {
            JsonNode r = postJson(biz, "/biz/goods/" + goodsNo + "/submit", "{}");
            assertThat(r.get("code").asInt()).as("免审提交绕过了准入闸")
                    .isEqualTo(ai.neargo.shop.common.ErrorCode.CATEGORY_BANNED.code());
        }));
        assertThat(onSale(goodsNo)).isFalse();
    }

    @Test
    @DisplayName("★★★ 过审兑现上架意向：主体此刻不准卖这一类 → 过审但不上架")
    void approvalHonorsAdmission() throws Exception {
        String biz = merchant("13700777302", "准入·过审店");
        String goodsNo = createGoods(biz, "禁售过审品", 1000, 5);
        assertThat(postJson(biz, "/biz/goods/" + goodsNo + "/submit", "{}").get("code").asInt()).isZero();
        withBannedCategory(biz, "CAT210", () ->
                assertThat(approve(goodsNo).get("code").asInt()).isZero());
        assertThat(onSale(goodsNo)).as("过审把禁售类目的商品推上了架").isFalse();
        assertThat(jdbc.queryForObject("select audit_status from prd_goods where goods_no=?",
                String.class, goodsNo)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("★★★ 免审发布草稿：草稿把类目换成禁售类目 → 发布被拒，线上旧版不动")
    void auditOffPublishHonorsAdmissionForNewCategory() throws Exception {
        String biz = merchant("13700777303", "准入·发布店");
        String goodsNo = onSaleGoods(biz, "换类发布品", 5);
        String skuNo = detail(biz, goodsNo).get("skus").get(0).get("skuNo").asString();
        String draft = goodsBody(goodsNo, skuNo, "换类发布品·改", 1000, 5).replace("CAT210", "CAT110");
        assertThat(postJson(biz, "/biz/goods/save", draft).get("code").asInt()).isZero();
        withBannedCategory(biz, "CAT110", () -> withAuditOff(() -> {
            JsonNode r = postJson(biz, "/biz/goods/" + goodsNo + "/publish", "{}");
            assertThat(r.get("code").asInt()).as("发布换版绕过了准入闸")
                    .isEqualTo(ai.neargo.shop.common.ErrorCode.CATEGORY_BANNED.code());
        }));
        assertThat(jdbc.queryForObject("select category_no from prd_goods where goods_no=?",
                String.class, goodsNo)).isEqualTo("CAT210");
        assertThat(onSale(goodsNo)).isTrue();
    }

    @Test
    @DisplayName("★★★ 过审换版：草稿换进禁售类目 → 不换版，旧版继续卖，草稿退回编辑")
    void approvalSwapHonorsAdmissionForNewCategory() throws Exception {
        String biz = merchant("13700777304", "准入·过审换版店");
        String goodsNo = onSaleGoods(biz, "换类过审品", 5);
        String skuNo = detail(biz, goodsNo).get("skus").get(0).get("skuNo").asString();
        String draft = goodsBody(goodsNo, skuNo, "换类过审品·改", 1000, 5).replace("CAT210", "CAT110");
        assertThat(postJson(biz, "/biz/goods/save", draft).get("code").asInt()).isZero();
        assertThat(postJson(biz, "/biz/goods/" + goodsNo + "/publish", "{}").get("code").asInt()).isZero();
        withBannedCategory(biz, "CAT110", () -> assertThat(approve(goodsNo).get("code").asInt()).isZero());
        assertThat(jdbc.queryForObject("select category_no from prd_goods where goods_no=?",
                String.class, goodsNo)).as("过审把商品换进了禁售类目").isEqualTo("CAT210");
        assertThat(onSale(goodsNo)).as("旧版继续卖").isTrue();
        assertThat(jdbc.queryForObject("select status from prd_goods_draft where goods_no=?",
                String.class, goodsNo)).isEqualTo("EDITING");
    }

    private boolean onSale(String goodsNo) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select on_sale from prd_goods where goods_no=?",
                Boolean.class, goodsNo));
    }

    /**
     * 让这个主体此刻「不准卖这一类」：给它换一个专用的主体形态，并给该形态配一条禁售类目策略。
     * 只动这一家 —— 准入策略按主体形态全局生效，改共享的那几档会波及别的用例。
     */
    private void withBannedCategory(String token, String categoryNo, Body body) throws Exception {
        String merchantNo = merchantNoOf(token);
        String form = "ZZB" + Math.abs(merchantNo.hashCode() % 100000);
        String was = jdbc.queryForObject("select legal_form from mch_entity where entity_no=?",
                String.class, merchantNo);
        jdbc.update("delete from mch_admission_policy where legal_form=?", form);
        jdbc.update("insert into mch_admission_policy (legal_form, required_deposit_minor, single_order_limit_minor,"
                + " daily_amount_limit_minor, ban_qualified_category, banned_category_codes, enabled, remark,"
                + " tenant_no, created_at, created_by, updated_at, version, deleted)"
                + " values (?, 0, 0, 0, 0, ?, 1, 'test', 'MAIN', now(), 'TEST', now(), 0, 0)",
                form, "[\"" + categoryNo + "\"]");
        jdbc.update("update mch_entity set legal_form=? where entity_no=?", form, merchantNo);
        try {
            body.run();
        } finally {
            jdbc.update("update mch_entity set legal_form=? where entity_no=?", was, merchantNo);
            jdbc.update("delete from mch_admission_policy where legal_form=?", form);
        }
    }

    // ------------------------------------------------------------------ ④ 审核开着发布：线上版不能消失

    @Test
    @DisplayName("★★★ 审核开着发布草稿 → 待审期间买家列表/详情/下单快照照旧是旧版；进待审队列；过审换版")
    void publishUnderAuditKeepsLiveVersionBuyable() throws Exception {
        String biz = merchant("13700777401", "双版本·审核店");
        String goodsNo = onSaleGoods(biz, "旧版商品", 20);
        String skuNo = detail(biz, goodsNo).get("skus").get(0).get("skuNo").asString();
        String merchantNo = merchantNoOf(biz);
        assertThat(postJson(biz, "/biz/goods/save", goodsBody(goodsNo, skuNo, "新版商品", 1000, 20))
                .get("code").asInt()).isZero();
        assertThat(postJson(biz, "/biz/goods/" + goodsNo + "/publish", "{}").get("code").asInt()).isZero();

        JsonNode d = buyerGet("/mp/goods/" + goodsNo);
        assertThat(d.get("code").asInt()).as("待审期间买家详情 404 了 —— 线上版消失").isZero();
        assertThat(d.get("data").get("title").asString()).isEqualTo("旧版商品");
        JsonNode list = buyerGet("/mp/goods?merchantNo=" + merchantNo + "&size=50").get("data").get("records");
        assertThat(list.findValuesAsString("goodsNo")).as("待审期间从买家列表里消失了").contains(goodsNo);
        assertThat(goodsQueryPort.snapshot(List.of(skuNo), Map.of()).get(skuNo).onSale())
                .as("待审期间下单快照判成不可售").isTrue();

        JsonNode queue = json.readTree(mvc().perform(get("/ops/goods/audit-queue?page=1&size=200")
                        .header("Authorization", "Bearer " + opsLogin()))
                .andReturn().getResponse().getContentAsString()).get("data").get("records");
        assertThat(queue.findValuesAsString("goodsNo")).as("提交的草稿要进待审队列").contains(goodsNo);

        assertThat(approve(goodsNo).get("code").asInt()).isZero();
        assertThat(buyerGet("/mp/goods/" + goodsNo).get("data").get("title").asString()).isEqualTo("新版商品");
        assertThat(onSale(goodsNo)).isTrue();
    }

    @Test
    @DisplayName("★★ 驳回待审草稿：线上旧版照卖，草稿退回编辑，驳回原因商家看得到")
    void rejectingDraftKeepsLiveSelling() throws Exception {
        String biz = merchant("13700777402", "双版本·驳回店");
        String goodsNo = onSaleGoods(biz, "驳回旧版", 20);
        String skuNo = detail(biz, goodsNo).get("skus").get(0).get("skuNo").asString();
        assertThat(postJson(biz, "/biz/goods/save", goodsBody(goodsNo, skuNo, "驳回新版", 1000, 20))
                .get("code").asInt()).isZero();
        assertThat(postJson(biz, "/biz/goods/" + goodsNo + "/publish", "{}").get("code").asInt()).isZero();
        assertThat(postJson(opsLogin(), "/ops/goods/" + goodsNo + "/audit",
                "{\"approved\":false,\"reason\":\"图不清楚\"}").get("code").asInt()).isZero();

        assertThat(onSale(goodsNo)).as("驳回的是草稿，不该把线上旧版拉下架").isTrue();
        assertThat(buyerGet("/mp/goods/" + goodsNo).get("data").get("title").asString()).isEqualTo("驳回旧版");
        assertThat(jdbc.queryForObject("select status from prd_goods_draft where goods_no=?",
                String.class, goodsNo)).isEqualTo("EDITING");
        assertThat(detail(biz, goodsNo).get("auditReason").asString()).contains("图不清楚");
    }

    private JsonNode buyerGet(String path) throws Exception {
        return json.readTree(mvc().perform(get(path)).andReturn().getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------ ⑨ SKU 价格校验

    @Test
    @DisplayName("★★ SKU 价格：负数、市场价为 null（含带划线价时的 NPE）一律 400，不落库")
    void skuPricesAreValidated() throws Exception {
        String biz = merchant("13700777901", "价格校验店");
        int bad = ai.neargo.shop.common.ErrorCode.BAD_REQUEST.code();
        JsonNode negative = postJson(biz, "/biz/goods/save", goodsBody(null, null, "负价商品", -1, 5));
        assertThat(negative.get("code").asInt()).as("负价").isEqualTo(bad);

        String nullMarket = "{\"categoryNo\":\"CAT210\",\"title\":\"空市场价\",\"subtitle\":\"t\",\"cover\":\"c\","
                + "\"images\":[],\"specGroups\":[],\"skus\":[{\"optionValues\":[],\"price\":100,"
                + "\"priceByMarket\":{\"AE\":null},\"stock\":1}]}";
        assertThat(postJson(biz, "/biz/goods/save", nullMarket).get("code").asInt()).as("市场价 null").isEqualTo(bad);

        String nullWithOrigin = nullMarket.replace("\"stock\":1", "\"stock\":1,\"originPrice\":200")
                .replace("空市场价", "空市场价带划线");
        assertThat(postJson(biz, "/biz/goods/save", nullWithOrigin).get("code").asInt())
                .as("带划线价时 null 市场价曾经 NPE → 500").isEqualTo(bad);

        String negMarket = nullMarket.replace("\"AE\":null", "\"AE\":-3").replace("空市场价", "负市场价");
        assertThat(postJson(biz, "/biz/goods/save", negMarket).get("code").asInt()).as("负市场价").isEqualTo(bad);
        assertThat(jdbc.queryForObject("select count(*) from prd_goods where title in "
                + "('负价商品','空市场价','空市场价带划线','负市场价')", Integer.class)).isZero();
    }

    // ------------------------------------------------------------------ 工具

    /** 建一件过审且在售的商品（单 SKU，CAT210） */
    private String onSaleGoods(String biz, String title, int stock) throws Exception {
        String goodsNo = createGoods(biz, title, 1000, stock);
        approve(goodsNo);
        assertThat(postJson(biz, "/biz/goods/" + goodsNo + "/toggle", "{\"onSale\":true}")
                .get("code").asInt()).isZero();
        return goodsNo;
    }

    private JsonNode approve(String goodsNo) throws Exception {
        return postJson(opsLogin(), "/ops/goods/" + goodsNo + "/audit", "{\"approved\":true}");
    }

    private interface Body {
        void run() throws Exception;
    }

    /** 关掉 goods.audit 跑一段。**必须还原** —— 开关在共享库里，不还原会把后面所有审核用例改成免审 */
    private void withAuditOff(Body body) throws Exception {
        String admin = TestLogin.admin(mvc(), json);
        assertThat(postJson(admin, "/ops/feature-flags/goods.audit", "{\"enabled\":false,\"rolloutPercent\":0}")
                .get("code").asInt()).isZero();
        try {
            body.run();
        } finally {
            postJson(admin, "/ops/feature-flags/goods.audit", "{\"enabled\":true,\"rolloutPercent\":0}");
        }
    }

    private String goodsBody(String goodsNo, String skuNo, String title, long price, int stock) {
        return "{" + (goodsNo == null ? "" : "\"goodsNo\":\"" + goodsNo + "\",")
                + "\"categoryNo\":\"CAT210\",\"title\":\"" + title + "\",\"subtitle\":\"t\","
                + "\"cover\":\"c\",\"images\":[],\"specGroups\":[],"
                + "\"skus\":[{" + (skuNo == null ? "" : "\"skuNo\":\"" + skuNo + "\",")
                + "\"optionValues\":[],\"price\":" + price + ",\"stock\":" + stock + "}]}";
    }

    private String createGoods(String token, String title, long price, int stock) throws Exception {
        JsonNode r = postJson(token, "/biz/goods/save", goodsBody(null, null, title, price, stock));
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
        return r.get("data").get("goodsNo").asString();
    }

    private JsonNode postJson(String token, String path, String body) throws Exception {
        return json.readTree(mvc().perform(post(path).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode detail(String token, String goodsNo) throws Exception {
        return json.readTree(mvc().perform(get("/biz/goods/" + goodsNo)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).get("data");
    }

    private String merchantNoOf(String token) throws Exception {
        String body = mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
    }

    private String opsLogin() throws Exception {
        return TestLogin.operator(mvc(), json, "goods", "goods123");
    }

    /** 走完「入驻 → 通过 → 重新登录」，返回可用于 /biz/** 的 token。 */
    private String merchant(String phone, String name) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        String bd = TestLogin.operator(mvc(), json, "bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + bd)
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        for (String cat : new String[]{"CAT110", "CAT120", "CAT210"}) {
            TestStoreCategory.open(mvc(), json, token, cat);
        }
        return token;
    }
}
