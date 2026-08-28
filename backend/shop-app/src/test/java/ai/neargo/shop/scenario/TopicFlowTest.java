package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
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

/**
 * 主题分类 —— 陈列（商品域-优化总方案 批 E）。
 *
 * <p>与类目正交、与活动分开：主题回答的是「这周首页摆什么」，
 * 而不是「这是什么货」或者「打几折」。
 */
@SpringBootTest
@ActiveProfiles("test")
class TopicFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 专题有种子且游客可见 —— 首页入口在登录之前就要看得见")
    void topicsAreSeededAndPublic() throws Exception {
        String body = mvc().perform(get("/mp/topics"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = json.readTree(body).get("data");
        assertThat(rows).as("空表时首页那几个入口会静默消失").isNotEmpty();
    }

    @Test
    @DisplayName("★★ 摆进专题的必须是在架商品 —— 否则 C 端点进去是空位，后台却看得到它")
    void onlyLiveGoodsGoIntoATopic() throws Exception {
        String ops = opsLogin();
        String topicNo = createTopic(ops, "测试专题·上架校验");

        String biz = merchant("12600161001", "专题测试店");
        String goodsNo = saveGoods(biz, "还没过审的货");

        // 草稿/待审的货：直接拒
        assertThat(codeOf(setGoods(ops, topicNo, goodsNo))).isEqualTo(70003);

        approveGoods(goodsNo);
        publish(biz, goodsNo);
        assertThat(codeOf(setGoods(ops, topicNo, goodsNo))).isZero();

        String listed = mvc().perform(get("/mp/topics/" + topicNo + "/goods"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(listed).get("data").get("records").get(0).get("goodsNo").asString())
                .isEqualTo(goodsNo);
    }

    @Test
    @DisplayName("★ 归档不删：买家侧列表里没了，但分享出去的链接还打得开")
    void archivedTopicKeepsItsLink() throws Exception {
        String ops = opsLogin();
        String topicNo = createTopic(ops, "测试专题·归档");

        mvc().perform(post("/ops/topics/" + topicNo + "/archived")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"));

        String body = mvc().perform(get("/mp/topics"))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode t : json.readTree(body).get("data")) {
            assertThat(t.get("topicNo").asString()).as("归档的不该出现在买家侧列表").isNotEqualTo(topicNo);
        }
        // 但它的商品页仍然打得开 —— 海报与历史链接都还指着它，404 说不出「这个专题结束了」
        mvc().perform(get("/mp/topics/" + topicNo + "/goods"))
                .andExpect(jsonPath("$.code").value(0));

        // 运营侧默认带归档：看不见的话「上周那个专题去哪了」没有答案，他会再建一个同名的
        String opsList = mvc().perform(get("/ops/topics").header("Authorization", "Bearer " + ops))
                .andReturn().getResponse().getContentAsString();
        boolean found = false;
        for (JsonNode t : json.readTree(opsList).get("data")) {
            found = found || topicNo.equals(t.get("topicNo").asString());
        }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("★ 结束早于开始直接拒 —— 那个专题从建出来的第一秒就不生效，而后台看着完全正常")
    void endBeforeStartIsRejected() throws Exception {
        String ops = opsLogin();
        String body = mvc().perform(post("/ops/topics").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"档期反了\",\"startAt\":1800000000000,\"endAt\":1700000000000}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(codeOf(body)).isEqualTo(10400);
    }

    // ---------------------------------------------------------------- helpers

    private String createTopic(String ops, String title) throws Exception {
        String body = mvc().perform(post("/ops/topics").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"subtitle\":\"测试\",\"sort\":90}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("topicNo").asString();
    }

    private String setGoods(String ops, String topicNo, String... goodsNos) throws Exception {
        String arr = String.join(",", java.util.Arrays.stream(goodsNos)
                .map(n -> "\"" + n + "\"").toList());
        return mvc().perform(post("/ops/topics/" + topicNo + "/goods")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNos\":[" + arr + "]}"))
                .andReturn().getResponse().getContentAsString();
    }

    private int codeOf(String body) {
        return json.readTree(body).get("code").asInt();
    }

    private String saveGoods(String token, String title) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"" + title + "\",\"subtitle\":\"测试\","
                                + "\"cover\":\"c.jpg\",\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("goodsNo").asString();
    }

    private void approveGoods(String goodsNo) throws Exception {
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                        .header("Authorization", "Bearer " + opsLogin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private void publish(String token, String goodsNo) throws Exception {
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private String merchant(String phone, String name) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
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
                        .header("Authorization", "Bearer " + opsLogin("bd", "bd123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String opsLogin() throws Exception {
        return opsLogin("goods", "goods123");
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
