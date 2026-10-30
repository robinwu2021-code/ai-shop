package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.paybridge.PaymentReconReconciler;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.port.FakePayQueryPort;
import ai.neargo.shop.trade.service.OrderService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Only the external query is controllable; financial claim and order projection use production
 * services.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=${order.phase1.recovery.test.url:jdbc:h2:mem:phase1-cash-recovery;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
        })
@ActiveProfiles("test")
class OrderPaymentRecoveryWiringFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderService orders;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private SettlePort settlement;
    @Autowired private TestPayGateway gateway;
    @Autowired private PaymentReconReconciler recovery;
    @MockitoSpyBean private FakePayQueryPort channel;
    private String outTradeNo;

    @AfterEach
    void resetExternalQuery() {
        Mockito.reset(channel);
        channel.reset();
        if (outTradeNo != null) {
            ledger.close(outTradeNo, "isolated query fixture cleanup");
        }
    }

    @Test
    void missingCallbackRecordsOriginalCashBeforeRepairingProjection() throws Exception {
        Purchase p = purchase("13207031001");
        answerOnlyOriginal(
                new PayQueryPort.Result(
                        true, true, true, p.amount(), "RECOVERY-" + p.payNo(), "CNY"));
        recovery.scan(System.currentTimeMillis() + 25 * 60_000L);
        assertFinancial(p, "SUCCESS", "CNY");
        assertThat(orderStatus(p)).isEqualTo("PAID");
        recovery.scan(System.currentTimeMillis() + 25 * 60_000L);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from stl_bill where order_no=?",
                                Long.class,
                                p.payNo()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from stl_payment where order_no=? and"
                                        + " direction='PAY'",
                                Long.class,
                                p.payNo()))
                .isEqualTo(1);
    }

    @Test
    void queriedForeignCurrencyKeepsCashFactWithoutApplyingTheOrder() throws Exception {
        Purchase p = purchase("13207031002");
        answerOnlyOriginal(
                new PayQueryPort.Result(true, true, true, p.amount(), "USD-" + p.payNo(), "USD"));
        recovery.scan(System.currentTimeMillis() + 25 * 60_000L);
        assertFinancial(p, "SUCCESS", "USD");
        assertThat(orderStatus(p)).isEqualTo("WAIT_PAY");
        assertThat(jdbc.queryForObject("select reason from ord_payment_resolution where pay_no=?",
                String.class, p.payNo())).isEqualTo("RECEIPT_MISMATCH");
        assertRepaymentBlocked(p);
    }

    @Test
    void unavailableQueryDoesNotCloseOrManufactureCash() throws Exception {
        Purchase p = purchase("13207031003");
        answerOnlyOriginal(new PayQueryPort.Result(false, false, false, 0, null));
        recovery.scan(System.currentTimeMillis() + 25 * 60_000L);
        assertFinancial(p, "PENDING", "CNY");
        assertThat(orderStatus(p)).isEqualTo("WAIT_PAY");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from stl_bill where order_no=?",
                                Long.class,
                                p.payNo()))
                .isZero();
    }

    @Test
    void queryOfClosedOrderRetainsCashAndOneRecoverableResolution() throws Exception {
        Purchase p = purchase("13207031004");
        orders.closeUnpaid(p.payNo(), "closed before lost callback repair");
        answerOnlyOriginal(
                new PayQueryPort.Result(true, true, true, p.amount(), "LATE-" + p.payNo(), "CNY"));
        recovery.scan(System.currentTimeMillis() + 25 * 60_000L);
        assertFinancial(p, "SUCCESS", "CNY");
        assertThat(orderStatus(p)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("select reason from ord_payment_resolution where pay_no=?",
                String.class, p.payNo())).isEqualTo("LATE_PAYMENT");
    }

    private void assertRepaymentBlocked(Purchase p) throws Exception {
        var result =
                json.readTree(
                        mvc().perform(
                                        post("/mp/order/" + p.payNo() + "/pay")
                                                .header("Authorization", "Bearer " + p.token()))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(result.path("code").asInt()).isEqualTo(10409);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from stl_payment where order_no=? and"
                                        + " direction='PAY'",
                                Long.class,
                                p.payNo()))
                .isEqualTo(1L);
    }

    @Test
    void historicalClosedAttemptRetainsItsOwnCashResolutionAfterNewerAttemptPaid()
            throws Exception {
        Purchase p = purchase("13207031006", true);
        String older = outTradeNo;
        String oldPayment = settlement.paymentByOutTradeNo(older).paymentNo();
        // Pre-migration ledger rows have no canonical operation. This preserves the real historical
        // callback case.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT initiating_operation_no FROM stl_payment WHERE"
                                        + " out_trade_no=?",
                                String.class,
                                older))
                .isNull();
        ledger.close(older, "historical pre-migration closed attempt");
        var retry =
                json.readTree(
                        mvc().perform(
                                        post("/mp/order/" + p.payNo() + "/pay")
                                                .header("Authorization", "Bearer " + p.token()))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(retry.path("code").asInt()).isZero();
        String newer = jdbc.queryForObject("select out_trade_no from stl_payment where order_no=?"
                + " and direction='PAY' order by id desc limit 1", String.class, p.payNo());
        assertThat(newer).isNotEqualTo(older);
        cashCallback(newer, false);
        assertThat(orderStatus(p)).isEqualTo("PAID");
        String appliedTrade =
                jdbc.queryForObject(
                        "select pay_trade_no from ord_order where order_no=?",
                        String.class,
                        p.payNo());
        cashCallback(older, true);
        cashCallback(older, true);
        var resolution =
                jdbc.queryForMap("select * from ord_payment_resolution where pay_no=?", p.payNo());
        assertThat(resolution)
                .containsEntry("payment_no", oldPayment)
                .containsEntry("currency", "CNY")
                .containsEntry("reason", "DUPLICATE_PAYMENT")
                .containsEntry("amount_minor", p.amount())
                .containsEntry("trade_no", gateway.query(older).tradeNo());
        assertThat(jdbc.queryForObject("select pay_trade_no from ord_order where order_no=?",
                String.class, p.payNo())).isEqualTo(appliedTrade);
        assertThat(jdbc.queryForObject("select count(*) from stl_bill where order_no=?",
                Long.class, p.payNo())).isEqualTo(1L);
    }
    private void cashCallback(String outNo, boolean conflict) throws Exception {
        assertThat(gateway.markPaid(outNo)).isTrue();
        String ack =
                mvc().perform(
                                post("/pay/callback/stub")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "outTradeNo",
                                                                outNo,
                                                                "transactionId",
                                                                gateway.query(outNo).tradeNo(),
                                                                "sign",
                                                                "stub-secret"))))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        if (conflict) { assertThat(json.readTree(ack).path("code").asInt()).isEqualTo(10409); }
        else { assertThat(ack).isEqualTo("SUCCESS"); }
    }

    @Test
    void recordedCashBlocksRepaymentBeforeOrderProjectionOrManualResolution() throws Exception {
        Purchase p = purchase("13207031005");
        var original = settlement.paymentByOutTradeNo(outTradeNo);
        assertThat(
                        settlement.settlePayment(
                                new SettlePort.PaymentSettled(
                                        outTradeNo,
                                        original.payChannel(),
                                        "CASH-" + p.payNo(),
                                        p.amount(),
                                        System.currentTimeMillis(),
                                        "CNY")))
                .isEqualTo(p.payNo());
        assertThat(orderStatus(p)).isEqualTo("WAIT_PAY");
        assertThat(jdbc.queryForObject("select count(*) from ord_payment_resolution where pay_no=?",
                Long.class, p.payNo())).isZero();
        assertRepaymentBlocked(p);
    }

    private void assertFinancial(Purchase p, String status, String currency) {
        assertThat(channel.asked()).contains(outTradeNo);
        assertThat(
                        jdbc.queryForObject(
                                "select status from stl_payment where out_trade_no=?",
                                String.class,
                                outTradeNo))
                .isEqualTo(status);
        assertThat(
                        jdbc.queryForObject(
                                "select currency from stl_payment where out_trade_no=?",
                                String.class,
                                outTradeNo))
                .isEqualTo(currency);
        assertThat(
                        jdbc.queryForObject(
                                "select amount_minor from stl_payment where out_trade_no=?",
                                Long.class,
                                outTradeNo))
                .isEqualTo(p.amount());
    }

    private String orderStatus(Purchase p) {
        return jdbc.queryForObject(
                "select status from ord_order where order_no=?", String.class, p.payNo());
    }

    private void answerOnlyOriginal(PayQueryPort.Result result) {
        String original = outTradeNo;
        Mockito.doAnswer(
                        call -> {
                            call.callRealMethod(); // Preserve the actual asked-original-number
                            // observation.
                            return original.equals(call.getArgument(1))
                                    ? result
                                    : new PayQueryPort.Result(false, false, false, 0, null);
                        })
                .when(channel)
                .query(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    private Purchase purchase(String phone) throws Exception {
        return purchase(phone, false);
    }

    private Purchase purchase(String phone, boolean historical) throws Exception {
        channel.reset();
        String token = TestLogin.consumer(mvc(), json, otp, phone);
        String input = "{\"items\":[{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}],"
                + "\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"usePoints\":0}";
        var created =
                json.readTree(
                        mvc().perform(
                                        post("/mp/order")
                                                .header("Authorization", "Bearer " + token)
                                                .header(
                                                        "Idempotency-Key",
                                                        UUID.randomUUID().toString())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(input))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(created.path("code").asInt()).isZero();
        String no = created.path("data").path("payOrderNo").asString();
        if (historical) {
            String user =
                    jdbc.queryForObject(
                            "SELECT user_no FROM ord_order WHERE order_no=?", String.class, no);
            String entity =
                    jdbc.queryForObject(
                            "SELECT entity_no FROM ord_sub_order WHERE order_no=?",
                            String.class,
                            no);
            long amount =
                    jdbc.queryForObject(
                            "SELECT pay_amount FROM ord_order WHERE order_no=?", Long.class, no);
            outTradeNo =
                    ledger.open(
                            new SettlePort.PaymentOpen(
                                    no, user, entity, "TEST", amount, "historical order"));
            assertThat(
                            gateway.prepay(
                                            new PayGateway.PrepayCommand(
                                                    outTradeNo,
                                                    amount,
                                                    "CNY",
                                                    "historical order",
                                                    "JSAPI",
                                                    null,
                                                    null))
                                    .success())
                    .isTrue();
            return new Purchase(no, amount, token);
        }
        var initiated =
                json.readTree(
                        mvc().perform(
                                        post("/mp/order/" + no + "/pay")
                                                .header("Authorization", "Bearer " + token))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(initiated.path("code").asInt()).isZero();
        outTradeNo =
                jdbc.queryForObject(
                        "select out_trade_no from stl_payment where order_no=? and direction='PAY'",
                        String.class,
                        no);
        return new Purchase(
                no,
                jdbc.queryForObject(
                        "select pay_amount from ord_order where order_no=?", Long.class, no),
                token);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private record Purchase(String payNo, long amount, String token) { }
}
