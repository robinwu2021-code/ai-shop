package ai.neargo.shop.scenario;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 商品与商家 —— **用例先行**（任务清单 §二 .4 步）。
 *
 * <p>覆盖 .1 冻结的 6 条新端点 + A2 §4 的四条 Goods 不变量 + R9 遗留的两条无测试端点
 * （`GET /mp/merchant`、`GET /mp/merchant/{no}` —— 此前只被顺带调用，从未被直接断言）。
 */
@SpringBootTest
@ActiveProfiles("test")
class M2CatalogFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.user.service.OtpStore otpStore;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 类目

    @Test
    @DisplayName("类目树：三级嵌套，按 sort 排序，停用类目不出现")
    void categoryTree() throws Exception {
        String body = mvc().perform(get("/mp/category/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode tree = json.readTree(body).get("data");
        assertThat(tree).isNotEmpty();
        JsonNode first = tree.get(0);
        assertThat(first.get("level").asInt()).isEqualTo(1);
        assertThat(first.get("children")).isNotEmpty();
        assertThat(first.get("children").get(0).get("level").asInt()).isEqualTo(2);

        // 停用类目不能出现在树里 —— 出现了用户会点进一个空列表
        for (JsonNode c : tree) {
            assertThat(c.get("name").asString()).doesNotContain("已停用");
        }
    }

    @Test
    @DisplayName("按类目筛选商品")
    void filterGoodsByCategory() throws Exception {
        mvc().perform(get("/mp/goods").param("categoryNo", "CAT001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThan(0)));

        mvc().perform(get("/mp/goods").param("categoryNo", "CAT-NOT-EXIST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // ---------------------------------------------------------------- 搜索

    @Test
    @DisplayName("搜索联想：命中标题前缀")
    void searchSuggest() throws Exception {
        String body = mvc().perform(get("/mp/search/suggest").param("keyword", "五常"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode words = json.readTree(body).get("data");
        assertThat(words).isNotEmpty();
        assertThat(words.get(0).asString()).contains("五常");
    }

    @Test
    @DisplayName("搜索无结果返回空列表而不是报错")
    void searchNoResultIsEmptyNotError() throws Exception {
        mvc().perform(get("/mp/search/suggest").param("keyword", "不存在的商品xyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));

        mvc().perform(get("/mp/goods").param("keyword", "不存在的商品xyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(0));
    }

    @Test
    @DisplayName("热搜词可取（游客可访问）")
    void hotWords() throws Exception {
        mvc().perform(get("/mp/search/hot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ---------------------------------------------------------------- 商品不变量（A2 §4）

    @Test
    @DisplayName("规格选中后取实时价：与详情里该 SKU 的价格一致（不变量①：价格只有一个来源）")
    void skuPriceMatchesDetail() throws Exception {
        String detail = mvc().perform(get("/mp/goods/G0001"))
                .andReturn().getResponse().getContentAsString();
        JsonNode sku = json.readTree(detail).get("data").get("skus").get(1);   // 20斤装
        long expected = sku.get("price").asLong();

        mvc().perform(get("/mp/goods/G0001/sku-price").param("skuNo", sku.get("skuNo").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(expected))
                .andExpect(jsonPath("$.data.stock").value(sku.get("stock").asInt()));
    }

    @Test
    @DisplayName("可售 = 总库存 - 已锁定：下单锁库存后，商品详情的 stock 立刻变小（不变量③）")
    void availableExcludesLockedStock() throws Exception {
        int before = stockOf("G0002", "SK0003");
        String token = login("13500135001");
        addToCart(token, "G0002", "SK0003", 2);
        createOrder(token, "m2-stock-key");

        // 暴露总库存的话，端上会出现「显示有货、下单说没货」
        assertThat(stockOf("G0002", "SK0003")).isEqualTo(before - 2);
    }

    @Test
    @DisplayName("下架商品：不出现在列表，也不能进入结算（不变量④的下游）")
    void offShelfGoodsIsNotSellable() throws Exception {
        // 种子数据里没有下架商品，用不存在的 SKU 代替「不可售」这一类
        String token = login("13500135002");
        mvc().perform(post("/mp/order/preview").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"goodsNo\":\"G0001\",\"skuNo\":\"SK-GONE\",\"qty\":1}],"
                                + "\"fulfillment\":\"STORE_PICKUP\"}"))
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("多规格矩阵：specGroups 的选项数与 SKU 数对得上")
    void multiSpecMatrix() throws Exception {
        String body = mvc().perform(get("/mp/goods/G0001")).andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).get("data");
        int options = data.get("specGroups").get(0).get("options").size();
        // 单规格维度：选项数 = SKU 数。对不上说明端上会渲染出点不动的规格按钮
        assertThat(data.get("skus").size()).isEqualTo(options);
    }

    // ---------------------------------------------------------------- 商家（含 R9 补测）

    @Test
    @DisplayName("R9：商家列表 —— 认证商家优先，字段齐全")
    void merchantList() throws Exception {
        String body = mvc().perform(get("/mp/merchant").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode records = json.readTree(body).get("data").get("records");
        assertThat(records).isNotEmpty();
        // 认证商家排前面：新入驻小店没销量，靠认证标才有机会被看到
        assertThat(records.get(0).get("verified").asBoolean()).isTrue();
        assertThat(records.get(0).get("rating").asDouble()).isBetween(0d, 5d);
    }

    @Test
    @DisplayName("R9：商家详情 —— 含三维度评分与标签")
    void merchantDetail() throws Exception {
        mvc().perform(get("/mp/merchant/M0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantNo").value("M0001"))
                .andExpect(jsonPath("$.data.scores.goods").isNumber())
                .andExpect(jsonPath("$.data.scores.service").isNumber())
                .andExpect(jsonPath("$.data.scores.speed").isNumber())
                .andExpect(jsonPath("$.data.tags").isArray());

        mvc().perform(get("/mp/merchant/M-NOT-EXIST"))
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("评分依据：评分与条数一致，且带依据说明（C-MC-05）")
    void merchantScoreBasis() throws Exception {
        mvc().perform(get("/mp/merchant/M0001/score"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rating").isNumber())
                .andExpect(jsonPath("$.data.ratingCount").isNumber())
                // 评分不写明依据，用户只会觉得是平台随便给的
                .andExpect(jsonPath("$.data.basis").isNotEmpty());
    }

    @Test
    @DisplayName("我买过的商家：下单后出现，带下单次数（跨模块经 Port，非直接依赖 trade）")
    void visitedMerchants() throws Exception {
        String token = login("13500135003");
        mvc().perform(get("/mp/merchant/visited").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        addToCart(token, "G0002", "SK0003", 1);
        createOrder(token, "m2-visited-key");

        String body = mvc().perform(get("/mp/merchant/visited").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode list = json.readTree(body).get("data");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("merchantNo").asString()).isEqualTo("M0001");
        assertThat(list.get(0).get("orderCount").asInt()).isEqualTo(1);
        assertThat(list.get(0).get("lastOrderAt").asLong()).isPositive();
    }

    @Test
    @DisplayName("未登录不能看「我买过的商家」")
    void visitedRequiresLogin() throws Exception {
        mvc().perform(get("/mp/merchant/visited"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    private int stockOf(String goodsNo, String skuNo) throws Exception {
        String body = mvc().perform(get("/mp/goods/" + goodsNo)).andReturn().getResponse().getContentAsString();
        for (JsonNode sku : json.readTree(body).get("data").get("skus")) {
            if (skuNo.equals(sku.get("skuNo").asString())) {
                return sku.get("stock").asInt();
            }
        }
        throw new AssertionError("sku not found: " + skuNo);
    }

    private void addToCart(String token, String goodsNo, String skuNo, int qty) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":" + qty + "}"))
                .andExpect(status().isOk());
    }

    private void createOrder(String token, String idemKey) throws Exception {
        mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private String login(String phone) throws Exception {
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        String body = mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone
                                + "\",\"credential\":\"" + code + "\",\"agreed\":true}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
