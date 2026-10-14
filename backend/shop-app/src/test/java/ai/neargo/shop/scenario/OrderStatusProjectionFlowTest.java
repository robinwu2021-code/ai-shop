package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单服务 2.0 接线第一步：对外状态收口到 {@code OrderStatusProjector} 后，主单号视图（支付视角）的可见变化。
 *
 * <p>子单状态用 jdbc 直接拨：这里验的是<b>读</b>（投影），不是发货/收货写路径。
 * 每条用例都挑了新旧口径<b>能分辨</b>的输入 —— 例如多门店「一家已发、一家已完成」，
 * 旧口径回落主单 PAID，新口径取最落后的 FULFILLING；挑「一家没发」的话新旧都是 PAID，用例恒绿。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("订单状态投影接线")
class OrderStatusProjectionFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 主单号打开多店订单：状态不一时取最落后的活跃单，不再停在「已支付」")
    void payViewAggregatesMostLagging() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13400141001");
        String payNo = paidCrossStoreOrder(token, "proj-agg");
        List<String> subs = subNos(payNo);
        assertThat(subs).hasSize(2);

        setSub(subs.get(0), "FULFILLING");
        setSub(subs.get(1), "COMPLETED");
        assertThat(headStatus(token, payNo)).as("一家履约中、一家已完成 → 整单履约中").isEqualTo("FULFILLING");

        setSub(subs.get(1), "REFUNDED");
        assertThat(headStatus(token, payNo)).as("已退款的不算活跃 → 跟剩下那家").isEqualTo("FULFILLING");
    }

    // ---------------------------------------------------------------- helpers

    private String paidCrossStoreOrder(String token, String key) throws Exception {
        addToCart(token, "G0001", "SK0001");   // M0001
        addToCart(token, "G0003", "SK0004");   // M0002
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String payNo = json.readTree(body).get("data").get("payOrderNo").asString();
        mvc().perform(post("/mp/order/" + payNo + "/pay").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payNo + "\",\"transactionId\":\"tx-" + key
                                + "\",\"sign\":\"stub-secret\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from ord_order where order_no = ?", String.class, payNo))
                .as("前置：主单应已付款").isEqualTo("PAID");
        return payNo;
    }

    private void addToCart(String token, String goodsNo, String skuNo) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"%s\",\"skuNo\":\"%s\",\"qty\":1}".formatted(goodsNo, skuNo)))
                .andExpect(status().isOk());
    }

    private List<String> subNos(String payNo) {
        return jdbc.queryForList("select sub_order_no from ord_sub_order where order_no = ? order by id",
                String.class, payNo);
    }

    private void setSub(String subNo, String status) {
        assertThat(jdbc.update("update ord_sub_order set status = ? where sub_order_no = ?", status, subNo))
                .isEqualTo(1);
    }

    private String headStatus(String token, String payNo) throws Exception {
        String body = mvc().perform(get("/mp/order/" + payNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("status").asString();
    }
}
