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
    @DisplayName("★★★ 用 C 端手上那个单号（子单号）能发表评价 —— 这条链路曾经整条是断的")
    void reviewAcceptsTheSubOrderNoCEndActuallyHas() throws Exception {
        String token = merchant("12600142007", "评价测试·子单号");
        String goodsNo = saveGoods(token, "评价测试·子单号 商品");
        approveGoods(goodsNo);
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));

        String buyer = login("13700142007");
        String subOrderNo = orderAndComplete(buyer, token, goodsNo);
        /*
         * 这里断言的东西很朴素：**买家点「发表评价」能成**。
         *
         * 它曾经不成 —— C 端传的是 `SUB…`，而后端拿它去比 `ord_item.order_no`
         * （那一列是 `SO…`），永远查不中，每一次都返回「数据不存在」。
         * 单测当时是绿的：夹具改传了主单号，还把这件事写成注释当规则。
         *
         * 所以这条用例刻意<b>只走真链路上的那个号</b>：撤掉 ReviewableOrderPortImpl
         * 的修复，它立刻红。
         */
        assertThat(subOrderNo).as("C 端拿到的就是子单号").startsWith("SUB");

        mvc().perform(post("/mp/review").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + subOrderNo + "\",\"goodsNo\":\"" + goodsNo
                                + "\",\"rating\":4,\"content\":\"米还行，就是自提点等得久\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reviewNo").isNotEmpty());

        // 商家那边立刻看得到它，且是「待回复」——否则评价发了等于没发
        assertThat(todoField(token, "toReply")).isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 评价要真的计入商家评分 —— 那句话就印在发表页上")
    void reviewActuallyMovesTheRating() throws Exception {
        var seeded = seedReview("12600142008", "评价测试·评分", 2);
        String merchantNo = json.readTree(mvc().perform(get("/biz/merchant/profile")
                        .header("Authorization", "Bearer " + seeded.token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("merchantNo").asString();

        // 一条 2 分，店的评分就该是 2.0 —— 而不是建店时那个初始值一动不动
        String body = mvc().perform(get("/mp/merchant/" + merchantNo))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("data").get("rating").asDouble())
                .as("评价发表后要计入商家评分（发表页上就是这么写的）").isEqualTo(2.0);
        assertThat(json.readTree(body).get("data").get("ratingCount").asInt()).isEqualTo(1);

        /*
         * 平台把这条差评驳回之后，分要跟着回去。
         * 只从 C 端隐掉而分还压着，等于申诉只赢了一半 —— 而商家看不出为什么还是 2 分。
         */
        mvc().perform(post("/ops/reviews/" + seeded.reviewNo + "/decide")
                        .header("Authorization", "Bearer " + opsLogin("admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pass\":false,\"reason\":\"恶意差评\"}"))
                .andExpect(jsonPath("$.code").value(0));

        String after = mvc().perform(get("/mp/merchant/" + merchantNo))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(after).get("data").get("ratingCount").asInt())
                .as("被驳回的评价不计入").isZero();
        /*
         * **退回中位分，不是 0 分**。唯一那条评价被裁掉之后这家店回到「还没人评过」，
         * 而 0 分会让他在按评分排的列表里垫底 —— 因为一条被平台判定为恶意的差评。
         * 页面按 ratingCount == 0 显示「暂无评价」，所以这个数只影响排序。
         */
        assertThat(json.readTree(after).get("data").get("rating").asDouble())
                .as("没人评过时回到中位分").isEqualTo(5.0);
    }

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
    @DisplayName("★★★ 团购价不低于原价就开不了团 —— 否则「团购」是假的")
    void groupPriceMustBeatOriginPrice() throws Exception {
        /*
         * ops-web 的类型注释上写着「必须低于原价，否则『团购』是假的」，
         * 而后端两条开团路径都只校验 > 0 —— 又一处「注释承诺了一个不存在的校验」。
         *
         * 它是在补运营端 VO 时被一条手工造的数据撞出来的：
         * groupPrice 1500 / originPrice 990 一路存进库、发到接口、渲染上页面，
         * 没有任何一层拦。C 端看到的会是「团购价 ¥15.00 / 原价 ¥9.90」，
         * 而凑齐人数的买家实际上多付了钱。
         */
        String token = merchant("12600144010", "开团测试·价格倒挂");
        // saveGoods 的原价是 500 分
        String goodsNo = saveGoods(token, "倒挂商品");
        approveGoods(goodsNo);
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));

        setGroupPrice(goodsNo, 900L);   // 比原价贵
        mvc().perform(post("/biz/groups").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\"}"))
                .andExpect(jsonPath("$.code").value(20004));

        // 相等也拒：一个不省钱的团没有存在的理由，而它会占掉一个开团位
        setGroupPrice(goodsNo, 500L);
        mvc().perform(post("/biz/groups").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\"}"))
                .andExpect(jsonPath("$.code").value(20004));

        // 真便宜了才放行 —— 两条一起才说明判断是对的，而不是把开团整个挡死了
        setGroupPrice(goodsNo, 400L);
        mvc().perform(post("/biz/groups").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper goodsMapperForGroup;

    /** 拼团价配在商品上（开团这一步不能临时定价），这里直接落库 */
    private void setGroupPrice(String goodsNo, long groupPriceMinor) {
        var g = goodsMapperForGroup.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.product.entity.PrdGoods>lambdaQuery()
                .eq(ai.neargo.shop.product.entity.PrdGoods::getGoodsNo, goodsNo).last("limit 1"));
        g.setGroupPriceMinor(groupPriceMinor);
        g.setGroupMinCount(3);
        goodsMapperForGroup.updateById(g);
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

    // ---------------------------------------------------------------- 平台裁决台（P-13.1）

    @Test
    @DisplayName("★ 申诉的另一半：商家申诉 → 平台支持 → 差评从 C 端消失")
    void appealUpheldRemovesTheReview() throws Exception {
        var seeded = seedReview("12600146001", "裁决测试·支持", 1);
        String goodsNo = goodsOf(seeded.token);

        // 差评此刻对买家可见
        assertThat(publicReviewCount(goodsNo)).isEqualTo(1);

        String appealNo = json.readTree(mvc().perform(post("/biz/review/" + seeded.reviewNo + "/appeal")
                                .header("Authorization", "Bearer " + seeded.token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"买家未按说明冷藏，有聊天记录\"}"))
                        .andReturn().getResponse().getContentAsString())
                .get("data").get("appeal").get("appealNo").asString();

        String support = opsLogin("support", "support123");
        mvc().perform(post("/ops/review-appeals/" + appealNo + "/decide")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uphold\":true,\"verdict\":\"证据充分，差评下架\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("UPHELD"));

        // ★ 这一步是整条链路的意义：支持之后，差评真的不再出现在 C 端
        assertThat(publicReviewCount(goodsNo)).isZero();
    }

    @Test
    @DisplayName("驳回申诉时差评保留 —— 驳回不是「什么都没发生」")
    void appealDismissedKeepsTheReview() throws Exception {
        var seeded = seedReview("12600146002", "裁决测试·驳回", 1);
        String goodsNo = goodsOf(seeded.token);
        String appealNo = json.readTree(mvc().perform(post("/biz/review/" + seeded.reviewNo + "/appeal")
                                .header("Authorization", "Bearer " + seeded.token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"我觉得不公平\"}"))
                        .andReturn().getResponse().getContentAsString())
                .get("data").get("appeal").get("appealNo").asString();

        String support = opsLogin("support", "support123");
        mvc().perform(post("/ops/review-appeals/" + appealNo + "/decide")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uphold\":false,\"verdict\":\"未提供有效证据\"}"))
                // 词典 §11：驳回一律 REJECTED。端上两处都按它写，后端此前是 DISMISSED
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        assertThat(publicReviewCount(goodsNo)).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 裁决必须写说明，且裁完就是终态（同一条差评不能有两个结论）")
    void appealDecisionNeedsVerdictAndIsFinal() throws Exception {
        var seeded = seedReview("12600146003", "裁决测试·终态", 1);
        String appealNo = json.readTree(mvc().perform(post("/biz/review/" + seeded.reviewNo + "/appeal")
                                .header("Authorization", "Bearer " + seeded.token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"申诉理由\"}"))
                        .andReturn().getResponse().getContentAsString())
                .get("data").get("appeal").get("appealNo").asString();
        String support = opsLogin("support", "support123");

        mvc().perform(post("/ops/review-appeals/" + appealNo + "/decide")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uphold\":true,\"verdict\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(10400));

        mvc().perform(post("/ops/review-appeals/" + appealNo + "/decide")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uphold\":false,\"verdict\":\"驳回\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/ops/review-appeals/" + appealNo + "/decide")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uphold\":true,\"verdict\":\"改主意了\"}"))
                .andExpect(jsonPath("$.code").value(10409));
    }

    @Test
    @DisplayName("评价驳回必须写理由；待裁决列表按状态筛得出来")
    void reviewDecideAndAppealQueue() throws Exception {
        var seeded = seedReview("12600146004", "裁决测试·队列", 2);
        String support = opsLogin("support", "support123");

        mvc().perform(post("/ops/reviews/" + seeded.reviewNo + "/decide")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pass\":false}"))
                .andExpect(jsonPath("$.code").value(10400));

        mvc().perform(post("/biz/review/" + seeded.reviewNo + "/appeal")
                .header("Authorization", "Bearer " + seeded.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"排队用\"}"));

        String body = mvc().perform(get("/ops/review-appeals").param("status", "PENDING")
                        .header("Authorization", "Bearer " + support))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("data")).isNotEmpty();
    }

    @Test
    @DisplayName("★ 三维权重之和不是 100 会整体拉高/压低全平台评分，被拒")
    void scoreWeightsMustSumTo100() throws Exception {
        String support = opsLogin("support", "support123");

        mvc().perform(get("/ops/review-score-config").header("Authorization", "Bearer " + support))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.weightProduct").value(50));

        mvc().perform(post("/ops/review-score-config").header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weightProduct\":50,\"weightFulfill\":30,\"weightService\":30,"
                                + "\"newMerchantProtectDays\":30,\"decayHalfLifeDays\":180}"))
                .andExpect(jsonPath("$.code").value(10400));

        mvc().perform(post("/ops/review-score-config").header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weightProduct\":60,\"weightFulfill\":25,\"weightService\":15,"
                                + "\"newMerchantProtectDays\":14,\"decayHalfLifeDays\":90}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(get("/ops/review-score-config").header("Authorization", "Bearer " + support))
                .andExpect(jsonPath("$.data.weightProduct").value(60));
    }

    /** C 端能看到这件商品的几条评价 —— 裁决的效果最终要在这里体现 */
    private int publicReviewCount(String goodsNo) throws Exception {
        String body = mvc().perform(get("/mp/review").param("goodsNo", goodsNo))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").size();
    }

    private String goodsOf(String merchantToken) throws Exception {
        String body = mvc().perform(get("/biz/goods").header("Authorization", "Bearer " + merchantToken))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records").get(0).get("goodsNo").asString();
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
        /*
         * **传子单号** —— C 端的「订单」就是子单：列表、详情、评价入口拿的都是 `SUB…`。
         *
         * 这里原先传主单号，还写着「C 端拿到的就是它，传子单号会 404」——
         * 那句话把<b>缺陷记成了规则</b>：写测试的人撞上 404，改了测试去迁就实现，
         * 于是这条链路在真机上整条是断的（每一次「发表评价」都得到「数据不存在」），
         * 而单测一直是绿的。注释里甚至写明了「那个 404 看起来像『这单不存在』」——
         * 已经看见症状了，只差没问一句「那 C 端到底传的什么」。
         */
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
        // **返回子单号**：C 端的「订单」就是子单，评价也是对商家的（一主单可拆给多家）
        return subOrderNo;
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
