package ai.neargo.shop.scenario;

import ai.neargo.shop.support.OrderReceiptFixture;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.order.service.OrderShippingService;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real consumer receipt route must commit packages and child through the common status gate. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.test.url:jdbc:h2:mem:receipt-wiring;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"
})
@ActiveProfiles("test")
class OrderReceiptWiringFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderShippingService shipping;
    @Autowired private ObjectMapper json;
    @Autowired private WebApplicationContext context;

    @Test
    void realBuyerReceiptCompletesDispatchedCreatedPackageExactlyOnce() throws Exception {
        Buyer buyer = buyer();
        String sub = OrderReceiptFixture.seed(jdbc, buyer.no());
        String shipment = OrderReceiptFixture.ship(shipping, sub);
        assertThat(packageStatus(shipment)).isEqualTo("CREATED");
        assertThat(confirm(sub, buyer.token())).isZero();
        assertThat(packageStatus(shipment)).isEqualTo("RECEIVED");
        assertThat(orderStatus(sub)).isEqualTo("COMPLETED");
        assertThat(confirm(sub, buyer.token())).isZero();
        assertThat(completionLogs(sub)).isEqualTo(1);
    }

    @Test
    void unrelatedBuyerCannotConfirmPackage() throws Exception {
        Buyer buyer = buyer();
        String sub = OrderReceiptFixture.seed(jdbc, buyer.no());
        String shipment = OrderReceiptFixture.ship(shipping, sub);
        assertThat(confirm(sub, buyer().token())).isNotZero();
        assertThat(packageStatus(shipment)).isEqualTo("CREATED");
        assertThat(orderStatus(sub)).isEqualTo("FULFILLING");
    }

    @Test
    void missingShipmentNeedsActualLegacyDispatchFacts() throws Exception {
        Buyer buyer = buyer();
        String sub = OrderReceiptFixture.seed(jdbc, buyer.no());
        assertThat(confirm(sub, buyer.token())).isNotZero();
        assertThat(orderStatus(sub)).isEqualTo("WAIT_FULFILL");
    }

    @Test
    void actualLegacyExpressShipmentCanStillBeReceived() throws Exception {
        Buyer buyer = buyer();
        String sub = OrderReceiptFixture.seed(jdbc, buyer.no());
        jdbc.update("UPDATE ord_sub_order SET status='FULFILLING',express_company='SF',express_no=? "
                + "WHERE sub_order_no=?", "SF" + sub, sub);
        jdbc.update("INSERT INTO ord_status_log (sub_order_no,status,label,operator_type,at,created_at) "
                + "VALUES (?,'FULFILLING','商家发货','MERCHANT',?,CURRENT_TIMESTAMP)",
                sub, System.currentTimeMillis());
        assertThat(confirm(sub, buyer.token())).isZero();
        assertThat(orderStatus(sub)).isEqualTo("COMPLETED");
        assertThat(completionLogs(sub)).isEqualTo(1);
    }

    @Test
    void completionLogFailureRollsBackPackageAndChild() throws Exception {
        Buyer buyer = buyer();
        String sub = OrderReceiptFixture.seed(jdbc, buyer.no());
        String shipment = OrderReceiptFixture.ship(shipping, sub);
        jdbc.execute("ALTER TABLE ord_status_log ADD CONSTRAINT receipt_log_failure CHECK (sub_order_no <> '"
                + sub + "' OR aggregate_type IS NULL OR aggregate_type <> 'ORDER' "
                + "OR event IS NULL OR event <> 'RECEIPT_CONFIRMED')");
        try {
            assertThat(confirm(sub, buyer.token())).isNotZero();
            assertThat(orderStatus(sub)).isEqualTo("FULFILLING");
            assertThat(packageStatus(shipment)).isEqualTo("CREATED");
            assertThat(completionLogs(sub)).isZero();
            assertThat(jdbc.queryForObject("SELECT completed_at FROM ord_sub_order WHERE sub_order_no=?",
                    Long.class, sub)).isNull();
        } finally {
            jdbc.execute("ALTER TABLE ord_status_log DROP CONSTRAINT receipt_log_failure");
        }
    }

    private Buyer buyer() throws Exception {
        String token = TestLogin.consumerByWechat(mvc(), json, "receipt-" + UUID.randomUUID());
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return new Buyer(token, json.readTree(body).get("data").get("userNo").asString());
    }

    private int confirm(String sub, String token) throws Exception {
        String body = mvc().perform(post("/mp/order/" + sub + "/confirm-receipt")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asInt();
    }

    private MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    private String packageStatus(String no) {
        return jdbc.queryForObject("SELECT status FROM ord_shipment WHERE shipment_no=?", String.class, no);
    }
    private String orderStatus(String no) {
        return jdbc.queryForObject("SELECT status FROM ord_sub_order WHERE sub_order_no=?", String.class, no);
    }
    private long completionLogs(String no) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ord_status_log WHERE sub_order_no=? "
                + "AND aggregate_type='ORDER' AND status='COMPLETED'", Long.class, no);
    }
    private record Buyer(String token, String no) { }
}
