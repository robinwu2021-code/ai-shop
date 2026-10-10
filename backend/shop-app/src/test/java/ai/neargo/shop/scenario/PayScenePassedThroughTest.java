package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.spi.settle.PointsPort;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

/**
 * <b>支付回调传给支付域的 payScene，是订单快照，不是当前请求。</b>
 *
 * <h2>它补的是一条被移交的保护</h2>
 * 2026-09-01 之前，「读订单快照而不是当前请求」由支付域自己保证 ——
 * 它通过 {@code OrderSceneQueryPort} 反向查订单域。按
 * 「除回调外不做反向依赖，pay 只解决 pay 的核心问题」去掉那条反向依赖之后，
 * 场景改由 {@code OrderServiceImpl.markPaid} 传入，<b>责任移到了 trade 侧</b>。
 *
 * <p>责任移交时最容易丢的就是原来那条保护：改完之后支付域的用例照样绿
 * （它只管「传进来什么就按什么判」），而没有任何东西再盯着「传的是不是快照」。
 *
 * <p>它防的是什么：发放发生在支付那一刻，而那时用户可能已经换了端，
 * 更常见的是根本没有用户在场（超时自动确认收货是系统动作）。
 * 读当前端会让「这单发不发积分」取决于谁在哪个端点确认、
 * 甚至取决于是不是定时任务跑的 —— 不可复现也无法对账。
 *
 * <h2>前两版都是假的保护，记在这里</h2>
 * <ol>
 *   <li><b>第一版</b>写在支付域的用例里、直接调 grant 并由测试自己读快照 ——
 *       把 {@code OrderServiceImpl} 改成传 null 也照样绿；</li>
 *   <li><b>第二版</b>走真实链路、断言「禁发端拿不到分」—— 消融仍然不红。
 *       加了正对照才发现：那条链路上积分本来就发不出来
 *       （商家/小区开关、积分规则都没配），<b>那个 0 一直是假的</b>。</li>
 * </ol>
 *
 * <p>所以这一版换了判据：<b>不看结果，看传进去的值</b> ——
 * 用 spy 拦住 {@code PointsPort.grant}，断言它收到的 payScene 就是订单上那个。
 * 这与积分最终发没发无关，而后者取决于一串与本用例无关的开关。
 */
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

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    /**
     * 拦住 {@code PointsPort.grant} 看它收到什么。
     *
     * <p>用 spy 不用 mock：mock 会把真实发放整个挡掉，
     * 而这条用例要的是「真实链路跑完之后它被怎么调的」。
     */
    @MockitoSpyBean
    private PointsPort pointsPort;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 支付回调传的 payScene 是【订单快照】—— 传成当前请求会静默按错的端判发放")
    void payScenePassedToPointsIsTheOrderSnapshot() throws Exception {
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

        var scene = ArgumentCaptor.forClass(String.class);
        Mockito.verify(pointsPort, Mockito.atLeastOnce())
                .grant(Mockito.anyString(), Mockito.anyString(), Mockito.anyList(),
                        Mockito.anyString(), Mockito.any(), scene.capture());

        assertThat(scene.getAllValues())
                .as("回调链路上没有请求头 —— 传的若是「当前请求的端」这里会是 null。"
                        + "必须是下单时那个 MP_WECHAT，才说明用的是订单快照")
                .isNotEmpty()
                .allMatch("MP_WECHAT"::equals);
    }
}
