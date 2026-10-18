package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.support.port.FakePayQueryPort;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import org.springframework.context.annotation.Import;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.service.AfterSaleService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import javax.sql.DataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real C application and merchant approval must not confuse channel acceptance with cash success. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.refund.test.url:jdbc:h2:mem:phase1-basic-refund;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
})
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class BasicRefundRecoveryFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("payDataSource") private DataSource payDataSource;
    @Autowired private AfterSaleService afterSales;
    @Autowired private TestPayGateway gateway;
    @Autowired private FakePayQueryPort channel;
    @Autowired private BasicRefundExecutionService refunds;
    private String oldFundsMode;

    @BeforeEach
    void merchantApprovalFixture() {
        oldFundsMode = jdbc.queryForObject("select funds_mode from mch_entity where entity_no='M0001'", String.class);
        jdbc.update("update mch_entity set funds_mode='DIRECT' where entity_no='M0001'");
    }

    @AfterEach
    void restoreMerchantFixture() {
        channel.reset();
        jdbc.update("update mch_entity set funds_mode=? where entity_no='M0001'", oldFundsMode);
    }

    @Test
    void acceptedWithoutConfirmationDoesNotRecordActualRefund() throws Exception {
        Purchase p = purchase("13207071001");
        String afterSale = apply(p);
        approve(p, afterSale);
        assertNotRefunded(p, afterSale);
        assertThat(jdbc.queryForObject("select status from stl_payment where after_sale_no=?",
                String.class, afterSale)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select status from ord_refund_execution where after_sale_no=?",
                String.class, afterSale)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("select status from ord_after_sale where after_sale_no=?",
                String.class, afterSale)).isEqualTo("REFUNDING");
        assertThat(jdbc.queryForObject("select count(*) from stl_payment where after_sale_no=?"
                + " and direction='REFUND' and status='SUCCESS'", Integer.class, afterSale)).isZero();
    }

    @Test
    void missingOriginDoesNotBecomeSuccessfulRefund() throws Exception {
        Purchase p = purchase("13207071002");
        String afterSale = apply(p);
        new JdbcTemplate(payDataSource).update(
                "update stl_payment set status='CLOSED' where order_no=? and direction='PAY'", p.payNo());
        assertThat(new JdbcTemplate(payDataSource).queryForObject(
                "select status from stl_payment where order_no=? and direction='PAY'", String.class, p.payNo()))
                .isEqualTo("CLOSED");
        try {
            approve(p, afterSale);
        } catch (RuntimeException expectedValidation) {
            // A rejected approval and a durable visible exception are both safer than fabricated cash success.
        }
        assertNotRefunded(p, afterSale);
        assertThat(jdbc.queryForObject("select count(*) from stl_payment where after_sale_no=?"
                + " and direction='REFUND'", Integer.class, afterSale)).isZero();
    }

    @Test
    void confirmedCallbackProjectsFullRefundAndReplayRestoresStockOnce() throws Exception {
        Purchase p = purchase("13207071003");
        String afterSale = apply(p);
        approve(p, afterSale);
        String outNo = jdbc.queryForObject("select out_trade_no from stl_payment where after_sale_no=?"
                + " and direction='REFUND'", String.class, afterSale);
        long amount = jdbc.queryForObject("select pay_amount from ord_sub_order where sub_order_no=?",
                Long.class, p.subNo());
        String provider = jdbc.queryForObject("select trade_no from stl_payment where out_trade_no=?",
                String.class, outNo);
        assertThat(provider).isNotBlank();
        channel.answer(new PayQueryPort.Result(true, true, true, amount, provider, "CNY"));
        String ack = mvc().perform(post("/pay/callback/TEST/refund").contentType(MediaType.APPLICATION_JSON)
                .content("verified:" + outNo)).andReturn().getResponse().getContentAsString();
        assertThat(ack).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("select status from ord_after_sale where after_sale_no=?", String.class,
                afterSale)).isEqualTo("REFUNDED");
        refunds.recover("RF-" + afterSale);
        refunds.recover("RF-" + afterSale);
        assertThat(jdbc.queryForObject("select refunded_minor from ord_sub_order where sub_order_no=?",
                Long.class, p.subNo())).isEqualTo(amount);
        assertThat(jdbc.queryForObject("select sum(refunded_qty) from ord_item where sub_order_no=?",
                Long.class, p.subNo())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select sum(qty) from prd_stock_restore where purpose_no=?",
                Long.class, "RF-" + afterSale)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from stl_payment where after_sale_no=?"
                + " and direction='REFUND'", Long.class, afterSale)).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 1, Long.MAX_VALUE})
    void explicitlyNonFullCashRequestIsRejected(long amount) throws Exception {
        Purchase p = purchase("13207071004");
        JsonNode response = request(p.token(), "/mp/order/" + p.subNo() + "/after-sale",
                "{\"type\":\"REFUND_ONLY\",\"reason\":\"不想要了\",\"reasonCode\":\"NOT_WANTED\","
                        + "\"refundMinor\":" + amount + "}");
        assertThat(response.path("code").asInt()).as(response.toString()).isNotZero();
        assertThat(jdbc.queryForObject("select count(*) from ord_after_sale where sub_order_no=?",
                Integer.class, p.subNo())).isZero();
    }

    private void assertNotRefunded(Purchase p, String afterSale) {
        assertThat(jdbc.queryForObject("select status from ord_after_sale where after_sale_no=?", String.class,
                afterSale)).isNotEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("select coalesce(refunded_minor,0) from ord_sub_order where sub_order_no=?",
                Long.class, p.subNo())).isZero();
        assertThat(jdbc.queryForObject("select coalesce(sum(refunded_qty),0) from ord_item where sub_order_no=?",
                Long.class, p.subNo())).isZero();
    }

    private void approve(Purchase p, String afterSale) {
        String merchant = jdbc.queryForObject("select entity_no from ord_sub_order where sub_order_no=?",
                String.class, p.subNo());
        afterSales.approve(merchant, afterSale, "full refund approved", null, null);
    }

    private String apply(Purchase p) throws Exception {
        JsonNode response = request(p.token(), "/mp/order/" + p.subNo() + "/after-sale",
                "{\"type\":\"REFUND_ONLY\",\"reason\":\"不想要了\",\"reasonCode\":\"NOT_WANTED\"}");
        assertThat(response.path("code").asInt()).as(response.toString()).isZero();
        return response.path("data").path("afterSaleNo").asString();
    }

    private Purchase purchase(String phone) throws Exception {
        String token = TestLogin.consumer(mvc(), json, otp, phone);
        JsonNode created = request(token, "/mp/order", "{\"items\":[{\"goodsNo\":\"G0002\","
                + "\"skuNo\":\"SK0003\",\"qty\":1}],\"fulfillment\":\"STORE_PICKUP\","
                + "\"pickupNo\":\"PP0001\",\"usePoints\":0}");
        assertThat(created.path("code").asInt()).as(created.toString()).isZero();
        String payNo = created.path("data").path("payOrderNo").asString();
        JsonNode initiated = request(token, "/mp/order/" + payNo + "/pay", "{}");
        assertThat(initiated.path("code").asInt()).as(initiated.toString()).isZero();
        String outNo = jdbc.queryForObject("select out_trade_no from stl_payment where order_no=?"
                + " and direction='PAY'", String.class, payNo);
        assertThat(gateway.markPaid(outNo)).isTrue();
        String tradeNo = gateway.query(outNo).tradeNo();
        String paid = mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + outNo + "\",\"transactionId\":\""
                        + tradeNo + "\",\"sign\":\"stub-secret\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(paid).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("select status from stl_payment where out_trade_no=?",
                String.class, outNo)).isEqualTo("SUCCESS");
        String subNo = jdbc.queryForObject("select sub_order_no from ord_sub_order where order_no=?",
                String.class, payNo);
        return new Purchase(token, payNo, subNo);
    }

    private JsonNode request(String token, String path, String body) throws Exception {
        return json.readTree(mvc().perform(post(path).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse().getContentAsString());
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private record Purchase(String token, String payNo, String subNo) { }
}
