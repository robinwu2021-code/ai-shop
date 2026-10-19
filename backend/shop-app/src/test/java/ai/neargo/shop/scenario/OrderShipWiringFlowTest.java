package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.command.ShipCommand;
import ai.neargo.shop.trade.order.service.OrderShippingService;
import ai.neargo.shop.support.ShippingMerchantFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Real B route must persist packages and every physical order row atomically. */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderShipWiringFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderShippingService shipping;
    private MockMvc mvc;
    private String token;
    private String merchant;
    private String store;
    private final String waybillRun = UUID.randomUUID().toString().substring(0, 8);
    @BeforeAll
    void merchant() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ShippingMerchantFixture.Scope scope = ShippingMerchantFixture.open(mvc, json, otp, "13400854001");
        token = scope.token();
        merchant = scope.merchant();
        store = scope.store();
    }

    @Test
    void realShipRouteBindsDuplicateSkuAndGiftRows() throws Exception {
        String sub = seed("PAID", store);
        assertThat(ship(sub, "SF10000001")).isZero();
        assertThat(count("ord_shipment", sub)).isEqualTo(1);
        String shipment = jdbc.queryForObject("SELECT shipment_no FROM ord_shipment WHERE sub_order_no=?",
                String.class, sub);
        assertThat(jdbc.queryForList("SELECT shipment_no FROM ord_item WHERE sub_order_no=?", String.class, sub))
                .containsExactly(shipment, shipment, shipment);
        assertThat(jdbc.queryForList("SELECT shipped_qty FROM ord_item WHERE sub_order_no=? ORDER BY id",
                Integer.class, sub))
                .containsExactly(1, 2, 1);
    }

    @Test
    void duplicateRequestHasOneEffectivePackageAndOneShipLog() throws Exception {
        String sub = seed("PAID", store);
        assertThat(ship(sub, "SF10000002")).isZero();
        assertThat(ship(sub, "SF10000002")).isZero();
        assertThat(count("ord_shipment", sub)).isEqualTo(1);
        assertThat(count("ord_status_log", sub)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE aggregate_id=? "
                + "AND event_type='SUB_ORDER_SHIPPED'", Long.class, sub)).isEqualTo(1);
    }

    @Test
    void parentUnpaidCannotShipEvenWithPaidChildSnapshot() throws Exception {
        String sub = seed("WAIT_PAY", store);
        assertThat(ship(sub, "SF10000003")).isNotZero();
        assertThat(count("ord_shipment", sub)).isZero();
        assertThat(status(sub)).isEqualTo("WAIT_FULFILL");
    }

    @Test
    void foreignStoreCannotShip() throws Exception {
        String sub = seed("PAID", "FOREIGN-STORE");
        assertThat(ship(sub, "SF10000004")).isNotZero();
        assertThat(count("ord_shipment", sub)).isZero();
    }

    @Test
    void openAfterSaleCannotShip() throws Exception {
        String sub = seed("PAID", store);
        jdbc.update("INSERT INTO ord_after_sale (after_sale_no,sub_order_no,order_no,user_no,entity_no,type,"
                        + "reason,status,created_at,updated_at) VALUES (?,?,?,?,?,'REFUND','test','APPLIED',"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "AS-" + sub, sub, "O-" + sub, "SHIP-BUYER", merchant);
        assertThat(ship(sub, "SF10000005")).isNotZero();
        assertThat(count("ord_shipment", sub)).isZero();
        assertThat(status(sub)).isEqualTo("WAIT_FULFILL");
    }

    @Test
    void replacementVoidsPriorPackageAndMovesAllRows() throws Exception {
        String sub = seed("PAID", store);
        assertThat(ship(sub, "SF10000006")).isZero();
        assertThat(count("ord_shipment", sub)).isEqualTo(1);
        String old = jdbc.queryForObject("SELECT shipment_no FROM ord_shipment WHERE sub_order_no=?",
                String.class, sub);
        assertThat(ship(sub, "SF10000007")).isZero();
        assertThat(count("ord_shipment", sub)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM ord_shipment WHERE shipment_no=?", String.class, old))
                .isEqualTo("VOIDED");
        String replacement = jdbc.queryForObject("SELECT superseded_by FROM ord_shipment WHERE shipment_no=?",
                String.class, old);
        assertThat(jdbc.queryForList("SELECT shipment_no FROM ord_item WHERE sub_order_no=?", String.class, sub))
                .containsExactly(replacement, replacement, replacement);
        assertThat(count("ord_status_log", sub)).isEqualTo(2);
    }

    @Test
    void saveFailureRollsBackAllFacts() throws Exception {
        String sub = seed("PAID", store);
        jdbc.execute("ALTER TABLE ord_item ADD CONSTRAINT shipping_failure CHECK (sub_order_no <> '"
                + sub + "' OR qty <> 2 OR shipped_qty = 0)");
        try {
            assertThat(ship(sub, "SF10000008")).isNotZero();
            assertThat(count("ord_shipment", sub)).isZero();
            assertThat(count("ord_status_log", sub)).isZero();
            assertThat(status(sub)).isEqualTo("WAIT_FULFILL");
            assertThat(jdbc.queryForList("SELECT shipment_no FROM ord_item WHERE sub_order_no=?", String.class, sub))
                    .containsOnlyNulls();
        } finally {
            jdbc.execute("ALTER TABLE ord_item DROP CONSTRAINT shipping_failure");
        }
    }

    @Test
    void physicalIdsShipTogetherAndMismatchedReplayIsRejected() {
        String sub = seed("PAID", store);
        List<Long> ids = jdbc.queryForList("SELECT id FROM ord_item WHERE sub_order_no=?", Long.class, sub);
        String number = "SF10000009-" + waybillRun;
        shipping.ship(sub, new ShipCommand(ids, "SF", number), new OrderActor(merchant, store));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_item WHERE sub_order_no=? AND shipped_qty=qty",
                Long.class, sub)).isEqualTo(3);
        assertThatThrownBy(() -> shipping.ship(sub, new ShipCommand(List.of(ids.getFirst()), "SF", number),
                new OrderActor(merchant, store))).isInstanceOf(BizException.class);
        assertThat(count("ord_shipment", sub)).isEqualTo(1);
    }

    @Test
    void nonExpressShipKeepsLegacyRouteAndAuditsPriorState() throws Exception {
        String sub = seed("PAID", store);
        jdbc.update("UPDATE ord_sub_order SET fulfillment='MERCHANT_DELIVERY' WHERE sub_order_no=?", sub);
        assertThat(ship(sub, "SF10000010")).isZero();
        assertThat(status(sub)).isEqualTo("FULFILLING");
        assertThat(count("ord_shipment", sub)).isZero();
        assertThat(jdbc.queryForObject("SELECT from_status FROM ord_status_log WHERE sub_order_no=?",
                String.class, sub)).isEqualTo("WAIT_FULFILL");
    }

    private String seed(String paid, String storeNo) {
        String sub = "SW" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_order (order_no,user_no,status,created_at,updated_at) "
                + "VALUES (?,? ,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "O-" + sub, "SHIP-BUYER", paid);
        jdbc.update("INSERT INTO ord_sub_order (sub_order_no,order_no,user_no,entity_no,store_no,fulfillment,"
                + "status,created_at,updated_at) VALUES (?,?,?,?,?,'EXPRESS','WAIT_FULFILL',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, "O-" + sub, "SHIP-BUYER", merchant, storeNo);
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO ord_item (sub_order_no,order_no,goods_no,sku_no,qty,is_gift,created_at,"
                    + "updated_at) VALUES (?,?,'SHIP-GOODS','SAME-SKU',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    sub, "O-" + sub, i == 1 ? 2 : 1, i == 2);
        }
        return sub;
    }
    private int ship(String sub, String waybill) throws Exception {
        String number = waybill + "-" + waybillRun;
        String body = mvc.perform(post("/biz/order/" + sub + "/ship")
                .header("Authorization", "Bearer " + token).header("X-Store-No", store)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expressNo\":\"" + number + "\",\"expressCompany\":\"SF\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asInt();
    }

    private long count(String table, String sub) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE sub_order_no=?", Long.class, sub);
    }

    private String status(String sub) {
        return jdbc.queryForObject("SELECT status FROM ord_sub_order WHERE sub_order_no=?", String.class, sub);
    }
}
