package ai.neargo.shop.scenario;

import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.TrackingSubscriber;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.registration.ShipmentRegistrar;
import ai.neargo.shop.logistics.registration.SubscribeExecutor;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.support.OrderLogisticsFixture;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.command.ShipCommand;
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

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Final production-wiring checks preserve unmapped manual authority, exact-source retry and monotonic receipts. */
@SpringBootTest
@ActiveProfiles("test")
class OrderLogisticsFinalFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private OrderShippingService shipping;
    @Autowired private ShipmentRegistrar registrar;
    @Autowired private SubscribeExecutor subscriber;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private ChannelRouter router;
    @Autowired private LogisticsProperties properties;
    @Autowired private ObjectMapper json;
    @Autowired private WebApplicationContext context;

    @Test
    void legacyShippingReplayCannotUndoAuthorizedOpsAudit() throws Exception {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, false);
        SysOutbox legacy = new SysOutbox();
        legacy.setPayload(json.writeValueAsString(new OrderEvents.SubOrderShipped(sub, "O-" + sub,
                "LG-MERCHANT", "LG-BUYER", "EXPRESS", "STO", number(sub, "A"))));
        registrar.consume(legacy);
        drain();
        String lgs = lgs(sub);
        MockMvc mvc = mvc();
        String token = TestLogin.admin(mvc, json);
        String reply = mvc.perform(post("/ops/shipments/" + lgs + "/waybill")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"carrier\":\"STO\",\"waybillNo\":\"" + number(sub, "B")
                        + "\",\"reason\":\"存量面单人工核对\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(reply).path("code").asInt(-1)).isZero();
        registrar.consume(legacy);
        drain();
        assertThat(jdbc.queryForObject("SELECT waybill_no FROM lgs_waybill WHERE biz_ref=?", String.class, sub))
                .isEqualTo(number(sub, "B"));
        assertThat(jdbc.queryForObject("SELECT source_shipment_no FROM lgs_waybill WHERE biz_ref=?",
                String.class, sub)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_shipment WHERE sub_order_no=?", Long.class, sub))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lgs_waybill_node WHERE shipment_no=? AND mode='OPS'",
                Long.class, lgs)).isEqualTo(1);
    }

    @Test
    void durableSubscriptionRetryKeepsOneSourceAndClearsOriginalFailure() {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, false);
        AtomicInteger calls = new AtomicInteger();
        TrackingSubscriber fake = new TrackingSubscriber() {
            public String channel() { return "kuaidi100"; }
            public ChannelOutcome subscribe(SubscribeCmd command) {
                assertThat(command.waybillNo()).isEqualTo(number(sub, "C"));
                return calls.incrementAndGet() == 1 ? ChannelOutcome.retryable("500", "暂时繁忙")
                        : ChannelOutcome.ok("501", "same-accepted-waybill");
            }
        };
        TrackingSubscriber original = router.installedSubscribers().put("kuaidi100", fake);
        List<String> originalRoute = properties.getRoutes().getSubscribe().getByStore().put("LG-STORE",
                List.of("kuaidi100"));
        boolean originalEnabled = properties.isSubscribeEnabled();
        properties.setSubscribeEnabled(true);
        try {
            String source = ship(sub, "C");
            drain();
            String lgs = lgs(sub);
            assertThat(jdbc.queryForObject("SELECT sub_error FROM lgs_waybill WHERE biz_ref=?", String.class, sub))
                    .contains("500");
            jdbc.update("UPDATE sys_outbox SET next_retry_at=NULL WHERE aggregate_id=? "
                    + "AND event_type='LGS_WAYBILL_REGISTERED' AND status='PENDING'", lgs);
            drain();
            assertThat(calls.get()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT sub_state FROM lgs_waybill WHERE biz_ref=?", String.class, sub))
                    .isEqualTo("DONE");
            assertThat(jdbc.queryForObject("SELECT sub_error FROM lgs_waybill WHERE biz_ref=?", String.class, sub))
                    .isNull();
            assertThat(jdbc.queryForObject("SELECT source_shipment_no FROM lgs_waybill WHERE biz_ref=?",
                    String.class, sub)).isEqualTo(source);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_shipment WHERE sub_order_no=?", Long.class, sub))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE aggregate_id=? "
                    + "AND event_type='SUB_ORDER_SHIPPED'", Long.class, sub)).isEqualTo(1);
            SysOutbox replay = new SysOutbox();
            replay.setPayload("{\"shipmentNo\":\"" + lgs + "\"}");
            subscriber.consume(replay);
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            router.installedSubscribers().put("kuaidi100", original);
            properties.setSubscribeEnabled(originalEnabled);
            if (originalRoute == null) { properties.getRoutes().getSubscribe().getByStore().remove("LG-STORE"); }
            else { properties.getRoutes().getSubscribe().getByStore().put("LG-STORE", originalRoute); }
        }
    }

    @Test
    void verifiedDuplicateAndOutOfOrderPushCannotResignOrCompleteChild() throws Exception {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, false);
        String source = ship(sub, "D");
        drain();
        jdbc.update("UPDATE lgs_waybill SET sub_channel='kuaidi100',sub_state='DONE' WHERE biz_ref=?", sub);
        MockMvc mvc = mvc();
        OrderLogisticsFixture.push(mvc, number(sub, "D"), "3", true);
        drain();
        Long at = jdbc.queryForObject("SELECT signed_at FROM ord_shipment WHERE shipment_no=?", Long.class, source);
        OrderLogisticsFixture.push(mvc, number(sub, "D"), "3", true);
        OrderLogisticsFixture.push(mvc, number(sub, "D"), "0", true);
        drain();
        assertThat(jdbc.queryForObject("SELECT status FROM ord_shipment WHERE shipment_no=?", String.class, source))
                .isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT signed_at FROM ord_shipment WHERE shipment_no=?", Long.class, source))
                .isEqualTo(at);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_status_log WHERE aggregate_no=? "
                + "AND status='DELIVERED'", Long.class, source)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM ord_sub_order WHERE sub_order_no=?", String.class, sub))
                .isEqualTo("FULFILLING");
    }

    private String ship(String sub, String suffix) {
        return shipping.ship(sub, new ShipCommand(List.of(), "STO", number(sub, suffix)),
                new OrderActor("LG-MERCHANT", "LG-STORE")).shipmentId();
    }
    private static String number(String sub, String suffix) {
        return "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT) + suffix;
    }
    private String lgs(String sub) {
        return jdbc.queryForObject("SELECT shipment_no FROM lgs_waybill WHERE biz_ref=?", String.class, sub);
    }
    private MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    private void drain() { for (int i = 0; i < 8; i++) { dispatcher.dispatchPending(); } }
}
