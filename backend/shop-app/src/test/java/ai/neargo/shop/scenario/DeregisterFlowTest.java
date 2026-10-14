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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 注销账号（微信对有账号体系的小程序**要求提供**，上架审核会查）。
 *
 * <p>这条链路最容易做错的是**做得太狠或太轻**：
 * <ul>
 *   <li>太狠 = 把订单一起删了 —— 违反留存义务，且对账与售后凭空断掉</li>
 *   <li>太轻 = 只打个标记 —— 同一个微信再进来还是回到那个「已注销」的壳里，
 *       用户会问「我不是注销了吗」</li>
 * </ul>
 * 正确的位置在中间：<b>匿名化 + 解绑凭证 + 交易记录留着</b>。
 */
@SpringBootTest
@ActiveProfiles("test")
class DeregisterFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.user.mapper.UserMappers.UserMapper userMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private String userNoOf(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("cUserNo").asString();
    }

    private int deregister(String token) throws Exception {
        String body = mvc().perform(post("/mp/user/deregister")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asInt();
    }

    @Test
    @DisplayName("★★★ 注销后同一个微信再进来 → **一个全新账号**，不是回到旧壳里")
    void sameWechatGetsAFreshAccountAfterDeregister() throws Exception {
        String openId = "wx-open-dereg-1";
        String first = TestLogin.consumerByWechat(mvc(), json, openId);
        String firstUserNo = userNoOf(first);

        assertThat(deregister(first)).isZero();

        /*
         * 解绑 openid 才是「注销」的实质。只打标记的话，同一个微信再进来
         * 还是命中那一行凭证、回到已注销的壳里 —— 用户会问「我不是注销了吗」。
         */
        String second = TestLogin.consumerByWechat(mvc(), json, openId);
        assertThat(userNoOf(second))
                .as("同一个 openid 注销后再登录，必须是新账号")
                .isNotEqualTo(firstUserNo);
    }

    @Test
    @DisplayName("★★★ 交易记录**不删** —— 订单、结算、发票有留存义务")
    void businessRecordsSurviveDeregistration() throws Exception {
        String token = TestLogin.consumerByWechat(mvc(), json, "wx-open-dereg-2");
        String userNo = userNoOf(token);

        assertThat(deregister(token)).isZero();

        /*
         * 账号行还在，只是被匿名化了：昵称抹掉、状态 DEREGISTERED、凭证清空。
         * 删行的话，挂在这个 userNo 上的订单会变成孤儿 —— 对账时对不出人。
         */
        var row = userMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.user.entity.UsrAccount>lambdaQuery()
                .eq(ai.neargo.shop.user.entity.UsrAccount::getUserNo, userNo));
        assertThat(row).as("账号行必须还在（订单挂在它上面）").isNotNull();
        assertThat(row.getStatus()).isEqualTo("DEREGISTERED");
        assertThat(row.getOpenid()).isNull();
        assertThat(row.getPhone()).isNull();
        assertThat(row.getNickname()).isEqualTo("已注销用户");
    }

    @Test
    @DisplayName("★★ 注销后原来的 token 立刻失效")
    void oldSessionsAreRevoked() throws Exception {
        String token = TestLogin.consumerByWechat(mvc(), json, "wx-open-dereg-3");
        assertThat(deregister(token)).isZero();

        mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(10402));
    }

    @Test
    @DisplayName("★★★ 还有没走完的单 → 拒绝，且账号原样不动")
    void openOrdersBlockDeregistration() throws Exception {
        String token = TestLogin.consumerByWechat(mvc(), json, "wx-open-dereg-4");
        String userNo = userNoOf(token);

        // 下一单不付款：它停在待付款，正是「没走完」
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"));
        mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0));

        /*
         * 注销之后没人能再联系到他，而货可能在路上、款可能还没退。
         * 放行的损失落在两边：他收不到货也找不回入口，客服连人都对不上。
         */
        assertThat(deregister(token)).isEqualTo(70028);

        var row = userMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.user.entity.UsrAccount>lambdaQuery()
                .eq(ai.neargo.shop.user.entity.UsrAccount::getUserNo, userNo));
        assertThat(row.getStatus()).as("被拒时账号不能被改动").isEqualTo("NORMAL");
        assertThat(row.getOpenid()).isNotNull();
    }
}
