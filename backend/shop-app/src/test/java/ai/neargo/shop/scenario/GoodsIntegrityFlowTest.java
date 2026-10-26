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

    // ------------------------------------------------------------------ 工具

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
