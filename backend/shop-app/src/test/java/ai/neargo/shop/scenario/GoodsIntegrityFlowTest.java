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
