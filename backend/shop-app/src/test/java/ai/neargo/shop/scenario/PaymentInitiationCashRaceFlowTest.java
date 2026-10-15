package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.support.TestLogin;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Pause only an actual read; the financial success and stale cashier request use real production controllers. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.init.race.test.url:jdbc:h2:mem:phase1-init-cash-race;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
})
@ActiveProfiles("test")
class PaymentInitiationCashRaceFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestPayGateway gateway;
    @MockitoSpyBean private SettlePort settlement;

    @Test
    void cashConfirmedAfterCashierReadCannotCreateAnotherChargeableAttempt() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otp, "13207031999");
        JsonNode created = request(token, "/mp/order", "{\"items\":[{\"goodsNo\":\"G0002\","
                + "\"skuNo\":\"SK0003\",\"qty\":1}],\"fulfillment\":\"STORE_PICKUP\","
                + "\"pickupNo\":\"PP0001\",\"usePoints\":0}");
        assertThat(created.path("code").asInt()).isZero();
        String payNo = created.path("data").path("payOrderNo").asString();
        assertThat(request(token, "/mp/order/" + payNo + "/pay", "{}").path("code").asInt()).isZero();
        String outNo = jdbc.queryForObject("select out_trade_no from stl_payment where order_no=?"
                + " and direction='PAY'", String.class, payNo);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(call -> {
            Object actual = call.callRealMethod();
            read.countDown();
            assertThat(resume.await(5, TimeUnit.SECONDS)).isTrue();
            return actual;
        }).when(settlement).successPayment(payNo);
        CompletableFuture<JsonNode> stale = CompletableFuture.supplyAsync(() -> {
            try { return request(token, "/mp/order/" + payNo + "/pay", "{}"); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
        try {
            assertThat(read.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(gateway.markPaid(outNo)).isTrue();
            String ack = mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("outTradeNo", outNo,
                            "transactionId", gateway.query(outNo).tradeNo(), "sign", "stub-secret"))))
                    .andReturn().getResponse().getContentAsString();
            assertThat(ack).isEqualTo("SUCCESS");
            assertThat(jdbc.queryForObject("select status from ord_order where order_no=?",
                    String.class, payNo)).isEqualTo("PAID");
            resume.countDown();
            assertThat(stale.get(5, TimeUnit.SECONDS).path("code").asInt()).isEqualTo(10409);
            assertThat(jdbc.queryForObject("select count(*) from stl_payment where order_no=?"
                    + " and direction='PAY'", Long.class, payNo)).isEqualTo(1L);
        } finally {
            resume.countDown();
            try { stale.get(5, TimeUnit.SECONDS); }
            finally { reset(settlement); }
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
