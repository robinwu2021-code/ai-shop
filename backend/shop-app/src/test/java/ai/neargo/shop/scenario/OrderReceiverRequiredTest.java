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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 送到人手上的单，**必须有收货地址**（2026-08-15 模拟器 e2e 发现）。
 *
 * <p>此前没有这道闸：不带 {@code addressId} 的快递单能一路下成功、付成功，
 * 而商家侧订单详情的收货人是 null，界面上是「—」——<b>货发不出去，
 * 系统全程没有任何异常</b>，要等商家准备发货那一刻才发现，那时钱已经收了。
 * 实测时库里 55 张快递子单，有收货人的 <b>0 张</b>。
 *
 * <p><b>端上拦着不算数</b>：C 端结算页确实有 {@code !needAddress || !!address} 的门禁，
 * 但那只挡住了走页面的人。库里那 55 张正是绕过页面的调用（测试脚本、旧版 App）造出来的。
 *
 * <p>三条一起测的理由：**只测「拒绝」会把闸修得过宽而没人发现** ——
 * 自提单本来就没有地址，被一起挡住的话整个自提链路全断，
 * 而那是这个平台的主力履约方式。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderReceiverRequiredTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * **自愈种子**：`DevSeeder` 给演示商品配的履约方式只有 `["STORE_PICKUP"]`，
     * 而这条闸测的恰恰是快递与自送 —— 不补的话请求会先被 70013
     * 「该商品不支持这种履约方式」挡下，测试看起来过了，实际一次都没走到要测的分支。
     */
    @org.junit.jupiter.api.BeforeEach
    void goodsSupportsShipping() {
        jdbc.update("UPDATE prd_goods SET fulfillments = ? WHERE goods_no = ?",
                "[\"STORE_PICKUP\",\"MERCHANT_DELIVERY\",\"EXPRESS\"]", "G0001");
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private static final String ITEMS =
            "\"items\":[{\"goodsNo\":\"G0001\",\"skuNo\":\"SK0001\",\"qty\":1}]";

    private String order(String token, String body) throws Exception {
        return mvc().perform(post("/mp/order")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("★★★ 快递单不给收货地址 → 70014，且**拦在创建这一步**（不是付完款再说）")
    void expressWithoutAddressIsRejected() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13900000801");
        mvc().perform(post("/mp/order")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + ITEMS + ",\"fulfillment\":\"EXPRESS\","
                                + "\"idempotencyKey\":\"t-express-noaddr\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(70014));
    }

    @Test
    @DisplayName("★★★ 自送单同样要地址 —— 它也是送到人手上的")
    void merchantDeliveryWithoutAddressIsRejected() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13900000802");
        mvc().perform(post("/mp/order")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + ITEMS + ",\"fulfillment\":\"MERCHANT_DELIVERY\","
                                + "\"idempotencyKey\":\"t-delivery-noaddr\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(70014));
    }

    @Test
    @DisplayName("★★★ 自提单**不需要**地址 —— 闸修宽了会把主力履约方式一起挡死")
    void pickupWithoutAddressStillWorks() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13900000803");
        String body = order(token, "{" + ITEMS + ",\"fulfillment\":\"STORE_PICKUP\","
                + "\"pickupNo\":\"PP0001\",\"idempotencyKey\":\"t-pickup-noaddr\"}");
        org.assertj.core.api.Assertions
                .assertThat(json.readTree(body).get("code").asInt())
                .as("自提单本来就没有收货地址，被这道闸挡住的话整个自提链路全断")
                .isZero();
    }

    @Test
    @DisplayName("★★ 给了一个不属于自己的 addressId 也要挡 —— 解析不出来等于没给")
    void unknownAddressIdIsRejected() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13900000804");
        mvc().perform(post("/mp/order")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + ITEMS + ",\"fulfillment\":\"EXPRESS\","
                                + "\"addressId\":\"ADDR-NOT-MINE\","
                                + "\"idempotencyKey\":\"t-express-badaddr\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(70014));
    }
}
