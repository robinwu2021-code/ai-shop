package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.service.AfterSaleService;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 5.5 行级申请与退款账本（TDD-订单状态机 AC28–AC31）。 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("行级退款 · 申请与执行")
class LineRefundFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AfterSaleService afterSales;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 3 件先退 1 件：订单状态不变、进度 PARTIAL、结算单仍待分账；再退剩下 2 件：REFUNDED、两笔退款流水各挂各的售后单")
    void partialThenRest() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13400155001");
        String sub = buy(token, 3, "line-partial");
        long itemId = jdbc.queryForObject("select id from ord_item where sub_order_no = ?", Long.class, sub);
        long share = jdbc.queryForObject("select paid_share_minor from ord_item where id = ?", Long.class, itemId);
        String statusBefore = subStatus(sub);

        JsonNode first = apply(token, sub, "{\"type\":\"REFUND_ONLY\",\"reason\":\"不想要了\",\"reasonCode\":\"NOT_WANTED\","
                + "\"items\":[{\"itemId\":" + itemId + ",\"qty\":1}]}");
        assertThat(first.get("code").asInt()).isZero();
        assertThat(first.get("data").get("refundMinor").asLong()).isEqualTo(share / 3);
        afterSales.approve(entityOf(sub), first.get("data").get("afterSaleNo").asString(), null, null, null);

        assertThat(subStatus(sub)).as("部分退款不改订单状态").isEqualTo(statusBefore);
        assertThat(col(sub, "refund_progress")).isEqualTo("PARTIAL");
        assertThat(jdbc.queryForObject("select refunded_qty from ord_item where id = ?", Integer.class, itemId)).isOne();
        assertThat(jdbc.queryForObject("select status from stl_bill where sub_order_no = ?", String.class, sub))
                .as("分账前部分退不能把结算单标 REVERSED").isNotEqualTo("REVERSED");

        JsonNode rest = apply(token, sub, "{\"type\":\"REFUND_ONLY\",\"reason\":\"不想要了\",\"reasonCode\":\"NOT_WANTED\"}");
        assertThat(rest.get("data").get("refundMinor").asLong()).as("不传行 = 剩余全部").isEqualTo(share - share / 3);
        afterSales.approve(entityOf(sub), rest.get("data").get("afterSaleNo").asString(), null, null, null);

        assertThat(subStatus(sub)).isEqualTo("REFUNDED");
        assertThat(col(sub, "refund_progress")).isEqualTo("FULL");
        assertThat(jdbc.queryForObject("select count(distinct after_sale_no) from stl_payment where sub_order_no = ?"
                + " and direction = 'REFUND'", Integer.class, sub)).as("第二笔退款没被第一笔的幂等键吞掉").isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 售后期：完成 8 天后申请非质量原因 → 20012；质量原因 15 天内可申请")
    void windowGuard() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13400155002");
        String sub = buy(token, 1, "line-window");
        jdbc.update("update ord_sub_order set status = 'COMPLETED', completed_at = ? where sub_order_no = ?",
                System.currentTimeMillis() - 8 * 86_400_000L, sub);
        assertThat(apply(token, sub, "{\"type\":\"REFUND_ONLY\",\"reason\":\"不想要了\",\"reasonCode\":\"NOT_WANTED\"}")
                .get("code").asInt()).isEqualTo(20012);
        assertThat(apply(token, sub, "{\"type\":\"RETURN_REFUND\",\"reason\":\"质量问题\",\"reasonCode\":\"QUALITY\"}")
                .get("code").asInt()).isZero();
    }

    @Test
    @DisplayName("★ 选的件数超过剩余 → 20011，不建单")
    void overQtyRejected() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13400155003");
        String sub = buy(token, 1, "line-over");
        long itemId = jdbc.queryForObject("select id from ord_item where sub_order_no = ?", Long.class, sub);
        assertThat(apply(token, sub, "{\"type\":\"REFUND_ONLY\",\"reason\":\"不想要了\","
                + "\"items\":[{\"itemId\":" + itemId + ",\"qty\":2}]}").get("code").asInt()).isEqualTo(20011);
        assertThat(jdbc.queryForObject("select count(*) from ord_after_sale where sub_order_no = ?", Integer.class, sub)).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode apply(String token, String sub, String body) throws Exception {
        return json.readTree(mvc().perform(post("/mp/order/" + sub + "/after-sale").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString());
    }

    private String subStatus(String sub) {
        return col(sub, "status");
    }

    private String col(String sub, String column) {
        return jdbc.queryForObject("select " + column + " from ord_sub_order where sub_order_no = ?", String.class, sub);
    }

    private String entityOf(String sub) {
        return col(sub, "entity_no");
    }

    /** 买 qty 件同一个 SKU、自提、付款，返回子单号 */
    private String buy(String token, int qty, String key) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":%d}".formatted(qty)))
                .andExpect(status().isOk());
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String payNo = json.readTree(body).get("data").get("payOrderNo").asString();
        mvc().perform(post("/mp/order/" + payNo + "/pay").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payNo + "\",\"transactionId\":\"tx-" + key + "\",\"sign\":\"stub-secret\"}"))
                .andExpect(status().isOk());
        return jdbc.queryForObject("select sub_order_no from ord_sub_order where order_no = ?", String.class, payNo);
    }
}
