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
 * D3–D7：工作台待办与经营数据、评价回复与差评申诉。
 *
 * <p>这几条端点在 b-app 里声明了很久但后端一直没有 —— 商家能看到差评却无法回应，
 * 唯一的出路是打客服；工作台首屏则是四个 404。
 */
@SpringBootTest
@ActiveProfiles("test")
class BizDashboardAndReviewFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 工作台

    @Test
    @DisplayName("★ 新店打开工作台是一串 0，不是报错 —— 报错会让店主以为账号没开通")
    void freshMerchantSeesZeros() throws Exception {
        String token = merchant("12600141001", "工作台测试·新店");

        String body = mvc().perform(get("/biz/dashboard/todo").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode todo = json.readTree(body).get("data");
        for (String k : new String[]{"toShip", "toDeliver", "toVerify", "toPick", "afterSale", "toReply"}) {
            assertThat(todo.get(k).asInt()).as(k).isZero();
        }

        String stats = mvc().perform(get("/biz/dashboard/stats").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode s = json.readTree(stats).get("data");
        assertThat(s.get("todayOrders").asInt()).isZero();
        assertThat(s.get("todayGmvMinor").asLong()).isZero();
        // 没有归因单时占比是 0 而不是 NaN —— 0/0 会让端上渲染出 "NaN%"
        assertThat(s.get("ownedTrafficRate").asDouble()).isZero();
    }

    @Test
    @DisplayName("不是商家的人打不开工作台（403，不是空对象）")
    void nonMerchantIsRejected() throws Exception {
        String consumer = login("12600141009");
        mvc().perform(get("/biz/dashboard/todo").header("Authorization", "Bearer " + consumer))
                .andExpect(jsonPath("$.code").value(10403));
    }

    @Test
    @DisplayName("未登录 401")
    void anonymousIsRejected() throws Exception {
        mvc().perform(get("/biz/dashboard/todo")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- 评价

    @Test
    @DisplayName("★ 回复只能回一次 —— 回复是公开表态，反复改会变成评论区来回改口")
    void replyOnlyOnce() throws Exception {
        var seeded = seedReview("12600142001", "评价测试·回复", 5);

        mvc().perform(post("/biz/review/" + seeded.reviewNo + "/reply")
                        .header("Authorization", "Bearer " + seeded.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reply\":\"谢谢支持\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reply").value("谢谢支持"));

        mvc().perform(post("/biz/review/" + seeded.reviewNo + "/reply")
                        .header("Authorization", "Bearer " + seeded.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reply\":\"改一下\"}"))
                .andExpect(jsonPath("$.code").value(10409));
    }

    @Test
    @DisplayName("★ 回不了别家的评价（且不区分「不存在」与「不是你的」）")
    void cannotReplyToAnotherMerchantsReview() throws Exception {
        var mine = seedReview("12600142002", "评价测试·本店", 5);
        String other = merchant("12600142003", "评价测试·别家");

        mvc().perform(post("/biz/review/" + mine.reviewNo + "/reply")
                        .header("Authorization", "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reply\":\"我来回一句\"}"))
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("★ 好评不能申诉 —— 放开的话「凡是不满意都申诉」会把裁决台淹掉")
    void goodReviewIsNotAppealable() throws Exception {
        var seeded = seedReview("12600142004", "评价测试·好评", 5);

        mvc().perform(post("/biz/review/" + seeded.reviewNo + "/appeal")
                        .header("Authorization", "Bearer " + seeded.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"我不服\"}"))
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("差评可申诉，但必须写理由，且只能申诉一次")
    void badReviewAppealFlow() throws Exception {
        var seeded = seedReview("12600142005", "评价测试·差评", 1);

        // 空理由：裁决台上无法处理，只会变成一条永远待办的单
        mvc().perform(post("/biz/review/" + seeded.reviewNo + "/appeal")
                        .header("Authorization", "Bearer " + seeded.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(10400));

        mvc().perform(post("/biz/review/" + seeded.reviewNo + "/appeal")
                        .header("Authorization", "Bearer " + seeded.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"买家未按说明冷藏，有聊天记录\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.appeal.status").value("PENDING"));

        mvc().perform(post("/biz/review/" + seeded.reviewNo + "/appeal")
                        .header("Authorization", "Bearer " + seeded.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"再来一次\"}"))
                .andExpect(jsonPath("$.code").value(10409));
    }

    @Test
    @DisplayName("★ 待回复评价进得了工作台待办，回复之后减一")
    void pendingReplyShowsInTodo() throws Exception {
        var seeded = seedReview("12600142006", "评价测试·待办", 4);

        assertThat(todoField(seeded.token, "toReply")).isEqualTo(1);
        mvc().perform(post("/biz/review/" + seeded.reviewNo + "/reply")
                        .header("Authorization", "Bearer " + seeded.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reply\":\"收到，下次改进\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(todoField(seeded.token, "toReply")).isZero();
    }

    // ---------------------------------------------------------------- 营销活动（D10）

    @Test
    @DisplayName("★ 结束早于开始的活动被拒 —— 它会「永远不生效」而状态看着正常")
    void campaignTimeWindowMustBeValid() throws Exception {
        String token = merchant("12600143001", "营销测试·时段");
        long now = System.currentTimeMillis();

        mvc().perform(post("/biz/campaign").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FULL_CUT\",\"name\":\"倒着来\",\"startAt\":" + now
                                + ",\"endAt\":" + (now - 1000) + ",\"goodsNos\":[]}"))
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("★ 活动类型创建后不可改 —— 改类型等于换一套优惠语义，应当新建")
    void campaignTypeIsImmutable() throws Exception {
        String token = merchant("12600143002", "营销测试·改类型");
        String no = createCampaign(token, "FULL_CUT", "满 50 减 5");

        mvc().perform(post("/biz/campaign").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campaignNo\":\"" + no + "\",\"type\":\"FLASH\",\"name\":\"改成秒杀\","
                                + "\"startAt\":1,\"endAt\":99999999999,\"goodsNos\":[]}"))
                .andExpect(jsonPath("$.code").value(10409));
    }

    @Test
    @DisplayName("新建是 DRAFT，启停在 RUNNING ↔ PAUSED 之间")
    void campaignToggleFlow() throws Exception {
        String token = merchant("12600143003", "营销测试·启停");
        String no = createCampaign(token, "COUPON", "新客券");

        assertThat(campaignStatus(token, no)).isEqualTo("DRAFT");
        mvc().perform(post("/biz/campaign/" + no + "/toggle").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"running\":true}"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
        mvc().perform(post("/biz/campaign/" + no + "/toggle").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"running\":false}"))
                .andExpect(jsonPath("$.data.status").value("PAUSED"));
    }

    @Test
    @DisplayName("★ 动不了别家的活动（不区分「不存在」与「不是你的」）")
    void cannotTouchAnotherMerchantsCampaign() throws Exception {
        String mine = merchant("12600143004", "营销测试·本店");
        String no = createCampaign(mine, "FULL_CUT", "本店活动");
        String other = merchant("12600143005", "营销测试·别家");

        mvc().perform(post("/biz/campaign/" + no + "/toggle").header("Authorization", "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"running\":true}"))
                .andExpect(jsonPath("$.code").value(10404));
    }

    // ---------------------------------------------------------------- 商家团（D11）

    @Test
    @DisplayName("★ 没配拼团价的商品开不了团 —— 开团这一步不能临时定价")
    void cannotOpenGroupWithoutGroupPrice() throws Exception {
        String token = merchant("12600144001", "开团测试·无拼团价");
        String goodsNo = saveGoods(token, "普通商品");
        approveGoods(goodsNo);
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));

        mvc().perform(post("/biz/groups").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\"}"))
                .andExpect(jsonPath("$.code").value(20004));
    }

    @Test
    @DisplayName("★ 开不了别家商品的团 —— 否则谁都能拿别人的货把单收进自己店")
    void cannotOpenGroupOnAnotherMerchantsGoods() throws Exception {
        String mine = merchant("12600144002", "开团测试·货主");
        String goodsNo = saveGoods(mine, "别家的货");
        String other = merchant("12600144003", "开团测试·外人");

        mvc().perform(post("/biz/groups").header("Authorization", "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\"}"))
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("商家团列表默认为空，不是报错")
    void merchantGroupsStartEmpty() throws Exception {
        String token = merchant("12600144004", "开团测试·空列表");
        mvc().perform(get("/biz/groups").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private String createCampaign(String token, String type, String name) throws Exception {
        long now = System.currentTimeMillis();
        String body = mvc().perform(post("/biz/campaign").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"" + type + "\",\"name\":\"" + name + "\",\"startAt\":" + now
                                + ",\"endAt\":" + (now + 86400000L) + ",\"goodsNos\":[]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("campaignNo").asString();
    }

    private String campaignStatus(String token, String campaignNo) throws Exception {
        String body = mvc().perform(get("/biz/campaign").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode c : json.readTree(body).get("data")) {
            if (campaignNo.equals(c.get("campaignNo").asString())) {
                return c.get("status").asString();
            }
        }
        throw new AssertionError("活动不在列表里：" + campaignNo);
    }

    // ---------------------------------------------------------------- 顾客与门店配置（D12）

    @Test
    @DisplayName("★ 顾客列表不下发完整手机号（B12）")
    void customerListNeverLeaksPhone() throws Exception {
        var seeded = seedReview("12600145001", "顾客测试·脱敏", 5);

        String body = mvc().perform(get("/biz/customers").header("Authorization", "Bearer " + seeded.token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode list = json.readTree(body).get("data");
        assertThat(list).isNotEmpty();
        // 买家手机号是 1370…（见 seedReview），整串都不该出现在响应里
        assertThat(body).doesNotContain("13700145001");
        assertThat(list.get(0).get("orderCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("新店的顾客列表是空数组，不是报错")
    void customerListStartsEmpty() throws Exception {
        String token = merchant("12600145002", "顾客测试·空");
        mvc().perform(get("/biz/customers").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("★ 没配过配送规则时返回默认值，不是空 —— 空会让店主以为功能坏了")
    void deliveryRuleHasDefaults() throws Exception {
        String token = merchant("12600145010", "配送测试·默认值");
        mvc().perform(get("/biz/delivery/rule").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.radius").value(3000))
                .andExpect(jsonPath("$.data.minOrderMinor").value(0));
    }

    @Test
    @DisplayName("★ 免运费门槛低于起送价被拒 —— 那等于每单都免运费，店主却以为设了门槛")
    void freeThresholdBelowMinOrderIsRejected() throws Exception {
        String token = merchant("12600145011", "配送测试·门槛");

        mvc().perform(post("/biz/delivery/rule").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"radius\":2000,\"minOrderMinor\":3000,\"feeMinor\":300,"
                                + "\"freeThresholdMinor\":2000}"))
                .andExpect(jsonPath("$.code").value(10400));

        // 合理配置存得下，并且读得回来
        mvc().perform(post("/biz/delivery/rule").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"radius\":2000,\"minOrderMinor\":2000,\"feeMinor\":300,"
                                + "\"freeThresholdMinor\":5000}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(get("/biz/delivery/rule").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.radius").value(2000))
                .andExpect(jsonPath("$.data.freeThresholdMinor").value(5000));
    }

    @Test
    @DisplayName("分享物料带得出店名与可点的链接；带 goodsNo 时指向单品")
    void shareKitCarriesShopNameAndLink() throws Exception {
        String token = merchant("12600145020", "分享测试·小店");

        String body = mvc().perform(get("/biz/store/share-kit").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode kit = json.readTree(body).get("data");
        assertThat(kit.get("text").asString()).contains("分享测试·小店").contains("http");

        String single = mvc().perform(get("/biz/store/share-kit")
                        .param("goodsNo", "G-XYZ")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(single).get("data").get("posterUrl").asString()).contains("g=G-XYZ");
    }

    // ---------------------------------------------------------------- helpers

    private int todoField(String token, String field) throws Exception {
        String body = mvc().perform(get("/biz/dashboard/todo").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get(field).asInt();
    }

    private record Seeded(String token, String reviewNo) {
    }

    /**
     * 造一条真评价：商家上架 → 买家下单付款 → 完成 → 评价。
     *
     * <p>不直接插库：评价的归属（entity_no）是下单链路写进去的，
     * 手工插一条能让用例绿，却验不到「这条评价到底算不算这家店的」。
     */
    private Seeded seedReview(String merchantPhone, String name, int rating) throws Exception {
        String token = merchant(merchantPhone, name);
        String goodsNo = saveGoods(token, name + " 商品");
        approveGoods(goodsNo);
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));

        String buyer = login("1370" + merchantPhone.substring(4));
        // 评价传的是**主单号**（C 端拿到的就是它），不是子单号 ——
        // 传子单号会 404，而那个 404 看起来像「这单不存在」
        String orderNo = orderAndComplete(buyer, token, goodsNo);

        String body = mvc().perform(post("/mp/review").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + orderNo + "\",\"goodsNo\":\"" + goodsNo
                                + "\",\"rating\":" + rating + ",\"content\":\"测试评价\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return new Seeded(token, json.readTree(body).get("data").get("reviewNo").asString());
    }

    /** 下单 → 支付 → 商家发货 → 送达，返回**主单号**（评价接口收的是主单号）。 */
    private String orderAndComplete(String buyer, String merchantToken, String goodsNo) throws Exception {
        String skuNo = json.readTree(mvc().perform(get("/mp/goods/" + goodsNo))
                        .andReturn().getResponse().getContentAsString())
                .get("data").get("skus").get(0).get("skuNo").asString();

        String order = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo
                                + "\",\"qty\":1}],\"fulfillment\":\"EXPRESS\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(order).get("data");
        /*
         * 回调用的是 **payOrderNo**，不是 orderNo —— 真正把单推到待履约的是通道回调，
         * `/pay` 只是拿收银台参数。用错单号的话回调静默无效，
         * 而下一步「商家看不到这单」看起来像数据域过滤问题。
         */
        String payOrderNo = data.get("payOrderNo").asString();
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + payOrderNo
                        + "\",\"sign\":\"stub-secret\"}"));

        // 商家订单列表里，子单号这一列叫 orderNo（OrderVO 对 B 端的口径）
        String subOrderNo = json.readTree(mvc().perform(get("/biz/order")
                        .header("Authorization", "Bearer " + merchantToken))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("records").get(0).get("orderNo").asString();

        mvc().perform(post("/biz/order/" + subOrderNo + "/ship")
                .header("Authorization", "Bearer " + merchantToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expressNo\":\"SF-TEST-1\"}"));
        mvc().perform(post("/biz/order/" + subOrderNo + "/delivered")
                .header("Authorization", "Bearer " + merchantToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        return data.get("orderNo").asString();
    }

    private String saveGoods(String token, String title) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"subtitle\":\"测试\",\"type\":\"NORMAL\","
                                + "\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("goodsNo").asString();
    }

    private void approveGoods(String goodsNo) throws Exception {
        String goodsOps = opsLogin("goods", "goods123");
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                        .header("Authorization", "Bearer " + goodsOps)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
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

        String bd = opsLogin("bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return login(phone);
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

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
