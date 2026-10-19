package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.spi.settle.PointsPort;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Cash payment preserves the order's checkout scene snapshot and never calls the points grant path. */
@SpringBootTest(properties = {
        /*
         * **必须另开一个库。** @MockitoSpyBean 会让这个类拿到一个新的 Spring 上下文，
         * 而上下文初始化会把 schema-test.sql 再跑一遍 —— 跑在同一个
         * `jdbc:h2:mem:shop` 上就是往已经有数据的表里重插种子，整个上下文起不来，
         * 症状与本用例毫无关系（**单独跑绿、全量跑红**）。
         * 与 ChannelPayCallbackFlowTest / PayChannelUnavailableFlowTest 同一手法 ——
         * 这次是把 spy 加进 M7SettleFlowTest 时撞上的，那让同批 6 个类一起变红。
         */
        "spring.datasource.url=jdbc:h2:mem:pay-scene;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
@ActiveProfiles({"test", "api"})
class PayScenePassedThroughTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired private JdbcTemplate jdbc;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @MockitoSpyBean
    private PointsPort pointsPort;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("一期支付回调保留下单场景且不调用积分")
    void phaseOnePreservesSceneSnapshotWithoutPointsGrant() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "12800128096");

        // 下单时带 X-Client=MP_WECHAT —— 订单的 payScene 就是从这个头来的
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "pay-scene-1")
                        .header("X-Client", "MP_WECHAT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();

        mvc().perform(post("/mp/order/" + payOrderNo + "/pay")
                .header("Authorization", "Bearer " + token));
        // **回调这条路上没有任何请求头** —— 「读当前端」在这里会读到 null
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-scene\","
                        + "\"sign\":\"" + STUB_SECRET + "\"}"));

        assertThat(jdbc.queryForObject("select pay_scene from ord_order where order_no=?", String.class, payOrderNo))
                .isEqualTo("MP_WECHAT");
        Mockito.verifyNoInteractions(pointsPort);

    }
}
