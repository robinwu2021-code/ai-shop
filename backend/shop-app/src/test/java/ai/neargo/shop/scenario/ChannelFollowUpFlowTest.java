package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.channel.api.ChannelTransportException;
import ai.neargo.shop.pay.channel.followup.ChannelFollowUpRunner;
import ai.neargo.shop.support.TestLogin;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AC9：渠道结果「未知」→ 登记补查 → 补查查到已付走与回调同一条入账路径，两者同时到达只入账一次
 * （TDD-支付服务-I2渠道统一封装 S5）。
 *
 * <p>此前下单超时只留一行日志：用户其实付了钱，订单停在待支付，要等每小时的对账碰巧扫到；
 * 而补查若另写一段「补一下状态」，与回调撞在一起就是重复入账。
 */
// 支付服务 I1–I3 的场景共用一个 H2 库（DB_CLOSE_DELAY=-1 的内存库活到 JVM 结束，每个库都是一整套表）
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class ChannelFollowUpFlowTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ChannelFollowUpRunner followUps;
    @MockitoSpyBean private TestPayGateway bank;

    @AfterEach
    void restore() {
        reset(bank);
    }

    @Test
    @DisplayName("★★★ AC9 下单超时（渠道其实收下了）→ 登记补查；用户付款后补查独自把订单转成已支付")
    void unknownPrepayIsFollowedUpAndBooked() throws Exception {
        String outNo = payWithTimeout("13207031801");
        String payNo = orderOf(outNo);
        assertThat(jdbc.queryForObject("SELECT status FROM pay_channel_followup WHERE open_key=?", String.class,
                "PAY|TEST|" + outNo)).as("下单结果不明要登记补查").isEqualTo("PENDING");
        assertThat(status(payNo)).isEqualTo("WAIT_PAY");

        assertThat(bank.markPaid(outNo)).isTrue();
        makeDue(outNo);
        assertThat(followUps.runOnce(50)).isPositive();

        assertThat(status(payNo)).as("补查查到已付，订单要转已支付").isEqualTo("PAID");
        assertThat(followUpStatus(outNo)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_channel_call_log WHERE operation='QUERY' AND"
                        + " out_trade_no=? AND trace_id=(SELECT trace_id FROM pay_channel_followup WHERE invocation_no ="
                        + " (SELECT invocation_no FROM pay_channel_call_log WHERE operation='PAY' AND out_trade_no=? LIMIT 1))",
                Integer.class, outNo, outNo)).as("补查的调用记录沿用原追踪号").isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ AC9 补查与回调同时到达：钱只记一次、订单只转一次")
    void followUpRacingCallbackBooksOnce() throws Exception {
        String outNo = payWithTimeout("13207031802");
        String payNo = orderOf(outNo);
        assertThat(bank.markPaid(outNo)).isTrue();
        makeDue(outNo);

        var start = new CountDownLatch(1);
        var followUp = CompletableFuture.supplyAsync(() -> {
            await(start);
            return followUps.runOnce(50);
        });
        var callback = CompletableFuture.supplyAsync(() -> {
            await(start);
            try {
                return mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("outTradeNo", outNo,
                                "transactionId", bank.query(outNo).tradeNo(), "sign", "stub-secret"))))
                        .andReturn().getResponse().getContentAsString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        start.countDown();
        followUp.get(20, TimeUnit.SECONDS);
        assertThat(callback.get(20, TimeUnit.SECONDS)).isEqualTo("SUCCESS");

        assertThat(status(payNo)).isEqualTo("PAID");
        assertThat(followUpStatus(outNo)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE order_no=? AND direction='PAY'"
                + " AND status='SUCCESS'", Integer.class, payNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_status_log WHERE event='PAY_SUCCEEDED'"
                + " AND (aggregate_no=? OR sub_order_no LIKE ?)", Integer.class, payNo, payNo + "%"))
                .as("订单只转一次已支付").isPositive();
        assertThat(jdbc.queryForList("SELECT aggregate_no, sub_order_no FROM ord_status_log WHERE event='PAY_SUCCEEDED'"
                + " AND (aggregate_no=? OR sub_order_no LIKE ?) GROUP BY aggregate_no, sub_order_no HAVING COUNT(*) > 1",
                payNo, payNo + "%")).as("同一单据两次「支付成功」= 重复入账").isEmpty();
    }

    /** 下单：渠道收下了这笔，但回执丢了（超时）—— 调用结果是「未知」 */
    private String payWithTimeout(String phone) throws Exception {
        doAnswer(call -> {
            call.callRealMethod();
            throw new ChannelTransportException(ChannelTransportException.Kind.TIMEOUT, "模拟：渠道收下了，回执超时");
        }).when(bank).prepay(any());
        String token = TestLogin.consumer(mvc(), json, otp, phone);
        JsonNode created = request(token, "/mp/order", "{\"items\":[{\"goodsNo\":\"G0002\","
                + "\"skuNo\":\"SK0003\",\"qty\":1}],\"fulfillment\":\"STORE_PICKUP\","
                + "\"pickupNo\":\"PP0001\",\"usePoints\":0}");
        assertThat(created.path("code").asInt()).as(created.toString()).isZero();
        String payNo = created.path("data").path("payOrderNo").asString();
        request(token, "/mp/order/" + payNo + "/pay", "{}");
        reset(bank);
        return jdbc.queryForObject("SELECT out_trade_no FROM stl_payment WHERE order_no=? AND direction='PAY'",
                String.class, payNo);
    }

    private String orderOf(String outNo) {
        return jdbc.queryForObject("SELECT order_no FROM stl_payment WHERE out_trade_no=?", String.class, outNo);
    }

    private String status(String orderNo) {
        return jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no=?", String.class, orderNo);
    }

    private String followUpStatus(String outNo) {
        return jdbc.queryForObject("SELECT status FROM pay_channel_followup WHERE invocation_no ="
                + " (SELECT invocation_no FROM pay_channel_call_log WHERE operation='PAY' AND out_trade_no=? LIMIT 1)",
                String.class, outNo);
    }

    private void makeDue(String outNo) {
        jdbc.update("UPDATE pay_channel_followup SET next_at=TIMESTAMP '2000-01-01 00:00:00' WHERE open_key=?",
                "PAY|TEST|" + outNo);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private JsonNode request(String token, String path, String body) throws Exception {
        return json.readTree(mvc().perform(post(path).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse().getContentAsString());
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
}
