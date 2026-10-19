package ai.neargo.shop.scenario;

import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.logistics.registration.ShipmentRegistrar;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import ai.neargo.shop.support.OrderLogisticsFixture;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.command.ShipCommand;
import ai.neargo.shop.trade.order.service.OrderShippingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Production shipping, registration, signature verification, database projection and original-event recovery. */
@SpringBootTest
@ActiveProfiles("test")
class OrderShipmentLogisticsFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private OrderShippingService shipping;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private ShipmentSourcePort sources;
    @Autowired private ShipmentRegistrar registrar;
    @Autowired private ObjectMapper json;
    @Autowired private WebApplicationContext context;

    @Test
    void registrationCarriesActualSourceIdentity() {
        String sub = seed(false);
        String source = ship(sub, "A");
        drain();
        var snapshot = json.valueToTree(sources.sourceOf(sub).orElseThrow());
        assertThat(snapshot.path("sourceShipmentNo").asString("")).isEqualTo(source);
        assertThat(snapshot.path("sourceShipmentVersion").asLong(-1)).isZero();
    }

    @Test
    void signedWithoutPickupConverges() throws Exception {
        String sub = seed(false);
        String source = ship(sub, "B");
        drain();
        subscribeForPush(sub);
        push(number(sub, "B"), "3", true);
        drain();
        assertThat(packageStatus(source)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT status FROM ord_sub_order WHERE sub_order_no=?",
                String.class, sub)).isEqualTo("FULFILLING");
    }

    @Test
    void signatureFailureCannotAdvancePackage() throws Exception {
        String sub = seed(false);
        String source = ship(sub, "C");
        drain();
        subscribeForPush(sub);
        push(number(sub, "C"), "3", false);
        drain();
        assertThat(packageStatus(source)).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT status FROM lgs_waybill WHERE biz_ref=?", String.class, sub))
                .isEqualTo("CREATED");
    }

    @Test
    void oldWaybillCannotAdvanceReplacement() throws Exception {
        String sub = seed(false);
        String old = ship(sub, "D");
        drain();
        subscribeForPush(sub);
        push(number(sub, "D"), "0", true);
        drain();
        String replacement = ship(sub, "E");
        drain();
        push(number(sub, "D"), "3", true);
        drain();
        assertThat(packageStatus(old)).isEqualTo("VOIDED");
        assertThat(packageStatus(replacement)).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lgs_waybill_node WHERE shipment_no="
                + "(SELECT shipment_no FROM lgs_waybill WHERE biz_ref=?)", Long.class, sub)).isZero();
    }

    @Test
    void lateOriginalShippingEventCannotRestoreSupersededWaybill() {
        String sub = seed(false);
        ship(sub, "F");
        drain();
        String original = jdbc.queryForObject("SELECT payload FROM sys_outbox WHERE aggregate_id=? "
                + "AND event_type='SUB_ORDER_SHIPPED' ORDER BY id LIMIT 1", String.class, sub);
        ship(sub, "G");
        drain();
        SysOutbox replay = new SysOutbox();
        replay.setPayload(original);
        registrar.consume(replay);
        assertThat(jdbc.queryForObject("SELECT waybill_no FROM lgs_waybill WHERE biz_ref=?", String.class, sub))
                .isEqualTo(number(sub, "G"));
    }

    @Test
    void missingUploadQueueRecoversFromOriginalShippingEvent() {
        String sub = seed(true);
        ship(sub, "H");
        drain();
        jdbc.update("DELETE FROM trd_shipping_upload WHERE order_no=?", "O-" + sub);
        replayShipping(sub);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trd_shipping_upload WHERE order_no=?",
                Long.class, "O-" + sub)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_shipment WHERE sub_order_no=?",
                Long.class, sub)).isEqualTo(1);
    }

    @Test
    void queueWriteFailureCannotRollBackShipmentAndOriginalEventCanRecover() {
        String sub = seed(true);
        jdbc.execute("ALTER TABLE trd_shipping_upload ADD CONSTRAINT logistics_queue_failure "
                + "CHECK (order_no <> 'O-" + sub + "')");
        try {
            assertThatCode(() -> ship(sub, "I")).doesNotThrowAnyException();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_shipment WHERE sub_order_no=?",
                    Long.class, sub)).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE trd_shipping_upload DROP CONSTRAINT logistics_queue_failure");
        }
        replayShipping(sub);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trd_shipping_upload WHERE order_no=?",
                Long.class, "O-" + sub)).isEqualTo(1);
    }

    private String seed(boolean paid) { return OrderLogisticsFixture.seed(jdbc, ledger, paid); }
    private String ship(String sub, String suffix) {
        return shipping.ship(sub, new ShipCommand(List.of(), "STO", number(sub, suffix)),
                new OrderActor("LG-MERCHANT", "LG-STORE")).shipmentId();
    }
    private static String number(String sub, String suffix) {
        return "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT) + suffix;
    }
    private String packageStatus(String source) {
        return jdbc.queryForObject("SELECT status FROM ord_shipment WHERE shipment_no=?", String.class, source);
    }
    private void subscribeForPush(String sub) {
        jdbc.update("UPDATE lgs_waybill SET sub_channel='kuaidi100',sub_state='DONE' WHERE biz_ref=?", sub);
    }
    private void replayShipping(String sub) {
        jdbc.update("UPDATE sys_outbox SET status='PENDING',next_retry_at=NULL WHERE aggregate_id=? "
                + "AND event_type='SUB_ORDER_SHIPPED'", sub);
        drain();
    }
    private void drain() {
        for (int i = 0; i < 8; i++) { dispatcher.dispatchPending(); }
    }
    private void push(String number, String state, boolean valid) throws Exception {
        OrderLogisticsFixture.pushForCurrentShipment(jdbc,
                MockMvcBuilders.webAppContextSetup(context).build(), number, state, valid, "shentong");
    }
}
