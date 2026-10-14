package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.service.OrderService;
import java.util.UUID;
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

/** Production cash entry points and financial receipts, without replacing the order service. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.payment.test.url:jdbc:h2:mem:phase1-payment;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
})
@ActiveProfiles("test")
class OrderPaymentLifecycleWiringFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderService orders;
    @Autowired private SettlePort settlement;

    @Test
    void unknownReceiptCannotClaimAnOrder() {
        String unknown = "MISSING-" + UUID.randomUUID();
        assertThat(settlement.settlePayment(receipt(unknown, "STUB", 100, "unknown"))).isNull();
    }

    @Test
    void noFinancialReceiptCannotAdvanceCashOrder() throws Exception {
        Purchase p = purchase(false);
        assertThatThrownBy(() -> orders.markPaid(p.payNo(), "STUB", "invented"))
                .isInstanceOf(RuntimeException.class);
        assertWaiting(p);
    }

    @Test
    void mismatchedPaymentNotApplied() throws Exception {
        Purchase p = purchase(true);
        assertThat(settlement.settlePayment(receipt(p.payNo(), p.channel(), p.amount() + 1, "amount")))
                .isEqualTo(p.payNo());
        assertThatThrownBy(() -> orders.markPaid(p.payNo(), p.channel(), "amount-" + p.payNo()))
                .isInstanceOf(RuntimeException.class);
        assertWaiting(p);
        assertThat(jdbc.queryForObject("select amount_minor from stl_payment where order_no=? and direction='PAY'",
                Long.class, p.payNo())).isEqualTo(p.amount() + 1);
    }

    @Test
    void foreignCurrencyNotApplied() throws Exception {
        Purchase p = purchase(true);
        settlement.settlePayment(receipt(p.payNo(), p.channel(), p.amount(), "currency"));
        jdbc.update("update stl_payment set currency='USD' where order_no=? and direction='PAY'", p.payNo());
        assertThatThrownBy(() -> orders.markPaid(p.payNo(), p.channel(), "currency-" + p.payNo()))
                .isInstanceOf(RuntimeException.class);
        assertWaiting(p);
    }

    @Test
    void anotherBuyersReceiptNotApplied() throws Exception {
        Purchase p = purchase(true);
        settlement.settlePayment(receipt(p.payNo(), p.channel(), p.amount(), "owner"));
        jdbc.update("update stl_payment set user_no='OTHER-BUYER' where order_no=? and direction='PAY'", p.payNo());
        assertThatThrownBy(() -> orders.markPaid(p.payNo(), p.channel(), "owner-" + p.payNo()))
                .isInstanceOf(RuntimeException.class);
        assertWaiting(p);
    }

    @Test
    void stubCallbackUsesServerAmountAndDuplicateConfirmsOnce() throws Exception {
        Purchase p = purchase(true);
        String body = "{\"outTradeNo\":\"" + p.payNo() + "\",\"transactionId\":\"paid-" + p.payNo()
                + "\",\"sign\":\"stub-secret\"}";
        for (int i = 0; i < 2; i++) {
            assertThat(mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andReturn().getResponse().getContentAsString()).isEqualTo("SUCCESS");
        }
        assertThat(jdbc.queryForObject("select amount_minor from stl_payment where order_no=? and direction='PAY'",
                Long.class, p.payNo())).isEqualTo(p.amount());
        assertThat(jdbc.queryForObject("select status from ord_order where order_no=?", String.class, p.payNo()))
                .isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select count(*) from stl_bill where order_no=?", Long.class, p.payNo()))
                .isEqualTo(1);
    }

    private SettlePort.PaymentSettled receipt(String no, String channel, long amount, String trade) {
        return new SettlePort.PaymentSettled(no, channel, trade + '-' + no, amount, System.currentTimeMillis());
    }

    private void assertWaiting(Purchase p) {
        assertThat(jdbc.queryForObject("select status from ord_order where order_no=?", String.class, p.payNo()))
                .isEqualTo("WAIT_PAY");
        assertThat(jdbc.queryForObject("select count(*) from stl_bill where order_no=?", Long.class, p.payNo()))
                .isZero();
    }

    private Purchase purchase(boolean open) throws Exception {
        String token = TestLogin.consumer(mvc(), json, otp, "132" + String.format("%08d", System.nanoTime() % 100000000));
        String body = "{\"items\":[{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}],"
                + "\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"usePoints\":0}";
        var created = json.readTree(mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse().getContentAsString());
        assertThat(created.path("code").asInt()).isZero();
        String no = created.path("data").path("payOrderNo").asString();
        if (open) {
            var paid = json.readTree(mvc().perform(post("/mp/order/" + no + "/pay")
                    .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString());
            assertThat(paid.path("code").asInt()).isZero();
        }
        return new Purchase(no, jdbc.queryForObject("select pay_amount from ord_order where order_no=?", Long.class, no),
                open ? jdbc.queryForObject("select pay_channel from stl_payment where order_no=? and direction='PAY'",
                        String.class, no) : null);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private record Purchase(String payNo, long amount, String channel) { }
}
