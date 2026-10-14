package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.service.MerchantOrderService;
import ai.neargo.shop.trade.service.OrderService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 步 4：支付单 / 订单写路径经转换门（TDD-订单状态机 §2B AC13–AC18）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("订单状态经转换门")
class TradeStatusGateFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private OrderService orderService;
    @Autowired
    private MerchantOrderService merchantOrderService;
    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ AC15 代客取消：支付单与订单一起取消（此前只改子单，支付单仍可付）")
    void proxyCancelCancelsPayment() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13400151002");
        String payNo = createOrder(token, "gate-proxy-cancel");
        String subNo = subNos(payNo).get(0);
        String ops = TestLogin.admin(mvc(), json);
        mvc().perform(post("/ops/orders/" + subNo + "/proxy-cancel").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"用户电话要求取消\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(paymentStatus(payNo)).isEqualTo("CANCELLED");
        assertThat(subStatuses(payNo)).containsOnly("CANCELLED");
    }

    @Test
    @DisplayName("★★ AC17 人工干预改到「已退款」被拒（不碰钱）；往前推到「已完成」可以")
    void interveneNonMonetaryOnly() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13400151003");
        String payNo = createOrder(token, "gate-intervene");
        pay(token, payNo, "gate-intervene");
        String subNo = subNos(payNo).get(0);
        String ops = TestLogin.admin(mvc(), json);
        mvc().perform(post("/ops/orders/" + subNo + "/intervene").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"REFUNDED\",\"remark\":\"试\"}"))
                .andExpect(jsonPath("$.code").value(20004));
        mvc().perform(post("/ops/orders/" + subNo + "/intervene").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"COMPLETED\",\"remark\":\"线下已交付\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(subStatuses(payNo)).containsOnly("COMPLETED");
    }

    @Test
    @DisplayName("★★ AC16 有进行中售后时不许点「开始备货」；AC18 每次迁移只记一行流水")
    void prepareBlockedByOpenAfterSale() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13400151004");
        String payNo = createOrder(token, "gate-prepare");
        pay(token, payNo, "gate-prepare");
        String subNo = subNos(payNo).get(0);
        assertThat(jdbc.queryForObject("select count(*) from ord_status_log where sub_order_no = ? and status = 'WAIT_FULFILL'",
                Integer.class, subNo)).as("支付成功只该记一行（门记，不再手写一份）").isOne();

        mvc().perform(post("/mp/order/" + subNo + "/after-sale").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REFUND_ONLY\",\"reasonCode\":\"NOT_WANTED\",\"reason\":\"不想要了\",\"images\":[]}"))
                .andExpect(jsonPath("$.code").value(0));
        var row = jdbc.queryForMap("select entity_no, store_no from ord_sub_order where sub_order_no = ?", subNo);
        assertThatThrownBy(() -> merchantOrderService.prepare((String) row.get("ENTITY_NO"),
                (String) row.get("STORE_NO"), subNo))
                .extracting(e -> ((ai.neargo.shop.common.BizException) e).errorCode())
                .isEqualTo(ai.neargo.shop.common.ErrorCode.CONFLICT);
        assertThat(jdbc.queryForObject("select prepared_at from ord_sub_order where sub_order_no = ?", Long.class, subNo))
                .as("被拒了还写上了备货时间").isNull();
    }

    // ---------------------------------------------------------------- helpers

    private String createOrder(String token, String key) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"))
                .andExpect(status().isOk());
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("payOrderNo").asString();
    }

    private void pay(String token, String payNo, String key) throws Exception {
        mvc().perform(post("/mp/order/" + payNo + "/pay").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payNo + "\",\"transactionId\":\"tx-" + key + "\",\"sign\":\"stub-secret\"}"))
                .andExpect(status().isOk());
    }

    private String paymentStatus(String payNo) {
        return jdbc.queryForObject("select status from ord_order where order_no = ?", String.class, payNo);
    }

    private List<String> subNos(String payNo) {
        return jdbc.queryForList("select sub_order_no from ord_sub_order where order_no = ? order by id", String.class, payNo);
    }

    private List<String> subStatuses(String payNo) {
        return jdbc.queryForList("select status from ord_sub_order where order_no = ?", String.class, payNo);
    }

}
