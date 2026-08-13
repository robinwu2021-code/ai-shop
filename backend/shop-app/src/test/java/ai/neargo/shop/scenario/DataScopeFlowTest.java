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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据域拦截器的行为验证（防线 ③）。
 *
 * <p><b>这个测试存在的理由</b>：{@code DataScopeHandler} 对**已注册的表**是 fail-closed ——
 * 当前会话的维度在该表锚点里找不到列时，它拼的是 {@code 1=0} 而不是放行。
 * C 端会话的维度是 SELF，而商品表上没有、也不该有 {@code user_no} 这种锚点。
 * 于是「游客能逛、一登录就什么都看不见」这种事会静默发生 —— 不报错、不告警、日志干净。
 * powerbank 正是在这里栽过（C 端「我的订单」空列表）。
 */
@SpringBootTest
@ActiveProfiles("test")
class DataScopeFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("登录用户逛商品，结果与游客一致（不能被 SELF 维度拼成 1=0）")
    void loggedInUserSeesSameCatalogAsGuest() throws Exception {
        int guestTotal = totalOf(mvc().perform(get("/mp/goods").param("communityNo", "C0001"))
                .andReturn().getResponse().getContentAsString());

        String token = login("13900139000");
        int userTotal = totalOf(mvc().perform(get("/mp/goods").param("communityNo", "C0001")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        org.assertj.core.api.Assertions.assertThat(userTotal)
                .as("登录后商品数变少 = 数据域把公共目录也过滤了")
                .isEqualTo(guestTotal)
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("登录用户能打开商品详情")
    void loggedInUserCanOpenGoodsDetail() throws Exception {
        String token = login("13900139001");
        mvc().perform(get("/mp/goods/G0001").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.goodsNo").value("G0001"));
    }

    @Test
    @DisplayName("非商家用户访问 /biz/** 一律 403（BizIdentityResolver fail-closed）")
    void nonMerchantIsRejectedFromBiz() throws Exception {
        String token = login("13900139002");
        // M4 实现 /biz/order 之前这里断言的是 404（端点不存在）；
        // 端点存在之后，真正要守的是**作用域为空 → 403**
        mvc().perform(get("/biz/order").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(10403));
    }

    private int totalOf(String body) {
        return json.readTree(body).get("data").get("total").asInt();
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
