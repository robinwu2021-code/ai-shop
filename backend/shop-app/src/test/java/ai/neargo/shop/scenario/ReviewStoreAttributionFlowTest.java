package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestPlan;
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
 * 评价归门店（ADR-011 决定表第 3 行，TDD-评价归门店，V155）。
 *
 * <p>ADR 的原话：「顾客评的是<b>楼下那家</b>，三家店评分混成一个，好店会被差店拖下去。」
 * 这个文件把那句话变成可执行的断言。
 *
 * <p>三条最容易做错、且做错了不报错的：
 * <ul>
 *   <li><b>评价跟着「当前默认店」走</b> —— 商家换一次默认店，历史评价集体搬家，
 *       两家店的分同时变，而没有任何人评过新的一条</li>
 *   <li><b>门店分与主体分两套口径</b> —— 「主体 4.6，三家店 4.8/4.7/4.9」，
 *       没有人能解释那个 4.6 是怎么来的</li>
 *   <li><b>处置只回主体分</b> —— 平台把一条差评裁掉了，门店分里还压着它，
 *       申诉只赢了一半</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class ReviewStoreAttributionFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private EntityPlanMapper planMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 两家店各评一次，分不一样 —— 而且换默认店不会让老评价搬家")
    void eachStoreKeepsItsOwnRating() throws Exception {
        String biz = merchant("12601300001", "评价归店·总店");
        String merchantNo = merchantNoOf(biz);
        TestPlan.grantPro(planMapper, merchantNo);
        String storeA = defaultStoreNo(biz);

        String goodsNo = listedGoods(biz, "评价归店·测试商品");

        // ① 第一单落在默认店 A —— 给 5 星
        review(biz, "13001300011", goodsNo, 5);

        /*
         * ② 开第二家店并把它设成默认。
         *
         * 这一步是这条用例的**判别器**：下一单会落到 B，而**上一条评价必须留在 A**。
         * 如果实现是「按商家当前默认店归属」，A 的分会在这一刻凭空清零、
         * 全部搬到 B 上 —— 两家店的分同时变，而没有任何人评过新的一条。
         */
        String storeB = createStore(biz, "评价归店·分店");
        mvc().perform(post("/biz/store/" + storeB + "/default")
                        .header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.code").value(0));

        // ③ 第二单落在 B —— 给 2 星
        review(biz, "13001300012", goodsNo, 2);

        var stores = storeRatings(biz);
        assertThat(stores.get(storeA).count()).as("A 店那条评价还在 A 上").isEqualTo(1);
        assertThat(stores.get(storeB).count()).as("B 店只有自己那一条").isEqualTo(1);
        assertThat(stores.get(storeA).ratingX10())
                .as("5 星的店不该和 2 星的店同分 —— 那正是 ADR-011 要避免的「好店被差店拖下去」")
                .isGreaterThan(stores.get(storeB).ratingX10());

        /*
         * ④ 主体分是两家的合成：**夹在两者之间**。
         *
         * 断区间而不是断具体数值：口径带 180 天时间加权，两条评价的时间差会让
         * 精确值随执行时刻微动。而「夹在中间」正是「同一份明细、同一套口径」的可观测形状 ——
         * 门店分若另算一套，这一条立刻不成立。
         */
        int entity = entityRatingX10(biz);
        assertThat(entity)
                .as("主体分要落在两家店之间，否则门店与主体不是同一套口径")
                .isBetween(stores.get(storeB).ratingX10(), stores.get(storeA).ratingX10());
    }

    @Test
    @DisplayName("★★ 差评被平台裁掉，门店分跟着回去 —— 只回主体分等于申诉只赢一半")
    void rejectingAReviewAlsoLowersTheStoreRating() throws Exception {
        String biz = merchant("12601300020", "评价归店·处置店");
        TestPlan.grantPro(planMapper, merchantNoOf(biz));
        String store = defaultStoreNo(biz);
        String goodsNo = listedGoods(biz, "评价归店·处置商品");

        String reviewNo = review(biz, "13001300021", goodsNo, 1);
        assertThat(storeRatings(biz).get(store).count()).isEqualTo(1);

        // 平台驳回这条评价
        mvc().perform(post("/ops/reviews/" + reviewNo + "/decide")
                        .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pass\":false,\"reason\":\"内容与商品无关\"}"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(storeRatings(biz).get(store).count())
                .as("被裁掉的评价还留在门店分里 —— 平台判他赢了，分却还压着")
                .isZero();
    }

    @Test
    @DisplayName("★★ 跨店对比每行有自己的分（V155 之前只能在顶部给一个主体分）")
    void compareRowsCarryTheirOwnRating() throws Exception {
        String biz = merchant("12601300030", "评价归店·对比店");
        TestPlan.grantPro(planMapper, merchantNoOf(biz));
        String storeA = defaultStoreNo(biz);
        String goodsNo = listedGoods(biz, "评价归店·对比商品");
        review(biz, "13001300031", goodsNo, 5);
        createStore(biz, "评价归店·对比分店");

        String body = mvc().perform(get("/biz/cross-store/compare?days=30")
                        .header("Authorization", "Bearer " + biz))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).get("data");

        // 顶层的主体分还在（C 端商家卡显示的就是它），但不再是**唯一**的评分
        assertThat(data.has("rating")).isTrue();

        boolean seenRated = false;
        for (JsonNode row : data.get("stores")) {
            assertThat(row.has("rating"))
                    .as("每一行都要有自己的评分字段，否则页面还是只能显示主体分").isTrue();
            if (storeA.equals(row.get("storeNo").asString())) {
                assertThat(row.get("ratingCount").asInt()).isEqualTo(1);
                assertThat(row.get("rating").asDouble()).isEqualTo(5.0);
                seenRated = true;
            } else {
                // 还没人评过的分店：**条数 0**，端上据此显示「暂无评价」而不是 0 颗星
                assertThat(row.get("ratingCount").asInt()).isZero();
            }
        }
        assertThat(seenRated).as("没找到默认店那一行，用例没验到东西").isTrue();
    }

    // ---------------------------------------------------------------- 装配

    private record Rated(int ratingX10, int count) {
    }

    /** 门店号 → 评分。走 {@code /biz/store/list}，即商家自己看到的那份 */
    private java.util.Map<String, Rated> storeRatings(String bizToken) throws Exception {
        String body = mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + bizToken))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        var out = new java.util.LinkedHashMap<String, Rated>();
        for (JsonNode s : json.readTree(body).get("data")) {
            out.put(s.get("storeNo").asString(),
                    new Rated(s.get("rating").asInt(), s.get("ratingCount").asInt()));
        }
        return out;
    }

    /**
     * 主体评分 ×10。
     *
     * <p>取自跨店对比的顶层 {@code rating} —— **那正是商家自己看到的那个数**，
     * 也是这条用例要证明「与门店分同源」的对象。
     * （运营端商家档案的 VO 里没有这个字段，别绕到那边去取。）
     */
    private int entityRatingX10(String bizToken) throws Exception {
        String body = mvc().perform(get("/biz/cross-store/compare?days=30")
                        .header("Authorization", "Bearer " + bizToken))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return (int) Math.round(json.readTree(body).get("data").get("rating").asDouble() * 10);
    }

    /**
     * 走完整链路发一条评价：买家下单 → 付款 → 商家发货 → 送达 → 评价。
     *
     * <p>**不往 rvw_review 里直插**：这条用例要验的正是「写入时门店归属取自子单」，
     * 直插的话 store_no 是测试自己填的 —— 而那恰好是最可能出错的一步。
     *
     * @return reviewNo
     */
    private String review(String bizToken, String buyerPhone, String goodsNo, int rating)
            throws Exception {
        String buyer = login(buyerPhone);
        String skuNo = json.readTree(mvc().perform(get("/mp/goods/" + goodsNo))
                        .andReturn().getResponse().getContentAsString())
                .get("data").get("skus").get(0).get("skuNo").asString();

        String payOrderNo = json.readTree(mvc().perform(post("/mp/order")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo
                                + "\",\"qty\":1}],\"fulfillment\":\"EXPRESS\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("payOrderNo").asString();
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + payOrderNo
                        + "\",\"sign\":\"stub-secret\"}"));

        /*
         * 子单号从**买家自己**的「我的订单」取，不从商家的订单列表取第一行。
         *
         * 这一条是踩出来的：这个文件里同一个商家会先后收到两个买家的单，
         * 商家列表的第一行未必是刚下的那一笔 —— 拿错了之后，
         * 第二个买家去确认第一个买家的单，报的是 10404（不是你的单），
         * 而那个报错看起来像评价链路坏了。
         */
        String subOrderNo = json.readTree(mvc().perform(get("/mp/order")
                        .header("Authorization", "Bearer " + buyer))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("records").get(0).get("orderNo").asString();
        mvc().perform(post("/biz/order/" + subOrderNo + "/ship")
                .header("Authorization", "Bearer " + bizToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expressNo\":\"SF-RV-1\"}"));
        // 端点是 confirm-receipt（收货确认）。写成 /confirm 的话请求 404，
        // 单还停在 SHIPPED，下一步「发表评价」返回 20004（订单状态不允许）——
        // 而那个报错指向的是评价，看起来像评价链路坏了
        mvc().perform(post("/mp/order/" + subOrderNo + "/confirm-receipt")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(jsonPath("$.code").value(0));

        String body = mvc().perform(post("/mp/review").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + subOrderNo + "\",\"goodsNo\":\"" + goodsNo
                                + "\",\"rating\":" + rating + ",\"content\":\"评价归门店用例\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("reviewNo").asString();
    }

    private String listedGoods(String bizToken, String title) throws Exception {
        String goodsNo = json.readTree(mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + bizToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"" + title + "\",\"subtitle\":\"测试\",\"type\":\"NORMAL\","
                                + "\"cover\":\"📦\",\"images\":[],\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":50}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("goodsNo").asString();
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                .header("Authorization", "Bearer " + TestLogin.operator(mvc(), json, "goods", "goods123"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                .header("Authorization", "Bearer " + bizToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));
        return goodsNo;
    }

    private String createStore(String bizToken, String name) throws Exception {
        String body = mvc().perform(post("/biz/store/create").header("Authorization", "Bearer " + bizToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"address\":\"某路 3 号\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("storeNo").asString();
    }

    private String defaultStoreNo(String bizToken) throws Exception {
        String body = mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + bizToken))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode s : json.readTree(body).get("data")) {
            if (s.get("isDefault").asBoolean()) {
                return s.get("storeNo").asString();
            }
        }
        throw new AssertionError("没有默认店");
    }

    private String merchantNoOf(String bizToken) throws Exception {
        String body = mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + bizToken))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
    }

    private String merchant(String phone, String name) throws Exception {
        String user = login(phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
