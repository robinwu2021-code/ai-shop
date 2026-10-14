package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.pay.PointsService;
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.spi.product.PointsRulePort;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.settle.PointsPort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.service.OrderService;
import java.util.UUID;
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

/** Real order entry points must work without touching the unfinished points subsystem. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-no-points;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class OrderWithoutPointsFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderService orders;
    @Autowired private SettleService settlement;
    @MockitoSpyBean private PointsService pointsService;
    @MockitoSpyBean private PointsPort points;
    @MockitoSpyBean private PointsRulePort rules;
    @MockitoSpyBean private StockPort stock;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void positivePointsRejectedBeforeReservation() throws Exception {
        String token = buyer("13107010001");
        mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(command(1)))
                .andExpect(jsonPath("$.code").value(10400));
        verifyNoInteractions(stock, points, rules);
    }

    @Test
    void negativePointsRejectedBeforeReservation() throws Exception {
        String token = buyer("13107010002");
        mvc().perform(post("/mp/order/preview").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(command(-1)))
                .andExpect(jsonPath("$.code").value(10400));
        verifyNoInteractions(stock, points, rules);
    }

    @Test
    void checkoutExplicitlyDisablesPoints() throws Exception {
        String token = buyer("13107010003");
        mvc().perform(post("/mp/order/capability").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(command(0)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.pointsEnabled").value(false));
    }

    @Test
    void cashPaymentNeverGrantsOrQueriesPoints() throws Exception {
        String token = buyer("13107010004");
        String payNo = create(token);
        mvc().perform(post("/mp/order/" + payNo + "/pay").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payNo
                                + "\",\"transactionId\":\"NP-" + payNo + "\",\"sign\":\"stub-secret\"}"))
                .andExpect(status().isOk());
        mvc().perform(get("/mp/order/" + payNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.status").value("PAID"));
        verifyNoInteractions(points, rules);
    }

    @Test
    void cashCancellationAndDetailNeverCallPoints() throws Exception {
        String token = buyer("13107010005");
        String payNo = create(token);
        mvc().perform(post("/mp/order/" + payNo + "/cancel").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"changed mind\"}"))
                .andExpect(jsonPath("$.code").value(0));
        JsonNode data = json.readTree(mvc().perform(get("/mp/order/" + payNo)
                        .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString());
        assertThat(data.path("data").path("status").asString()).isEqualTo("CANCELLED");
        verifyNoInteractions(points, rules);
    }

    @Test
    void historicalPointsRequireManualResolutionBeforeCancellation() throws Exception {
        String token = buyer("13107010006");
        String payNo = create(token);
        jdbc.update("update ord_sub_order set points_deduct=5, points_deduct_minor=5 where order_no=?", payNo);
        mvc().perform(post("/mp/order/" + payNo + "/cancel").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"changed mind\"}"))
                .andExpect(jsonPath("$.code").value(20014));
        assertThat(jdbc.queryForObject("select status from ord_order where order_no=?", String.class, payNo))
                .isEqualTo("WAIT_PAY");
        assertThat(jdbc.queryForObject("select points_deduct_minor from ord_sub_order where order_no=?",
                Long.class, payNo)).isEqualTo(5L);
        verifyNoInteractions(points, rules);
    }

    @Test
    void expirySkipsHistoricalPointsButClosesCashOrders() throws Exception {
        String token = buyer("13107010007");
        String historical = create(token);
        String cash = create(token);
        jdbc.update("update ord_sub_order set points_deduct=5 where order_no=?", historical);
        jdbc.update("update ord_order set pay_deadline_at=1 where order_no in (?,?)", historical, cash);
        orders.closeExpiredOrders(System.currentTimeMillis());
        assertThat(jdbc.queryForObject("select status from ord_order where order_no=?", String.class, historical))
                .isEqualTo("WAIT_PAY");
        assertThat(jdbc.queryForObject("select status from ord_order where order_no=?", String.class, cash))
                .isEqualTo("CLOSED");
        verifyNoInteractions(points, rules);
    }

    @Test
    void cashSettlementNeverConfirmsPoints() throws Exception {
        String token = buyer("13107010008");
        String payNo = create(token);
        mvc().perform(post("/mp/order/" + payNo + "/pay").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payNo
                                + "\",\"transactionId\":\"NP-" + payNo + "\",\"sign\":\"stub-secret\"}"))
                .andExpect(status().isOk());
        String settleNo = jdbc.queryForObject("select settle_no from stl_bill where order_no=?", String.class, payNo);
        settlement.confirmRecon(settleNo, "TEST");
        jdbc.update("update stl_bill set invoice_status=? where settle_no=?", StlBill.INV_NONE, settleNo);
        settlement.markPaid(settleNo, "TEST-CASH-RECEIPT", "TEST");
        assertThat(jdbc.queryForObject("select status from stl_bill where settle_no=?", String.class, settleNo))
                .isEqualTo("PAID");
        verifyNoInteractions(pointsService);
    }

    private String buyer(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otp, phone);
    }

    private String create(String token) throws Exception {
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(command(0)))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("payOrderNo").asString();
    }

    private String command(long usePoints) {
        return "{\"items\":[{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}],"
                + "\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"usePoints\":" + usePoints + "}";
    }
}
