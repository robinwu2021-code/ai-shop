package ai.neargo.shop.scenario;

import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.support.OrderLogisticsFixture;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.command.ShipCommand;
import ai.neargo.shop.trade.order.service.OrderShippingService;
import ai.neargo.shop.trade.order.service.ship.ShipmentSnapshotConverger;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Existing authorized Ops correction must supersede the actual source and isolate late old receipts. */
@SpringBootTest
@ActiveProfiles("test")
class OrderMappedOpsLogisticsFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private OrderShippingService shipping;
    @Autowired private ShipmentSnapshotConverger snapshots;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private ObjectMapper json;
    @Autowired private WebApplicationContext context;

    @Test
    void currentWaybillSignedAfterDispatchCompletesTradeButOldSignatureDoesNot() {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, false);
        String number = "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT) + "R";
        String source = shipping.ship(sub, new ShipCommand(List.of(), "STO", number),
                new OrderActor("LG-MERCHANT", "LG-STORE")).shipmentId();
        drain();
        long oldSignedAt = System.currentTimeMillis() - 86_400_000L;
        snapshots.converge(source, 0, number, "DELIVERED", oldSignedAt, oldSignedAt, oldSignedAt);
        assertThat(jdbc.queryForObject("SELECT status FROM ord_sub_order WHERE sub_order_no=?", String.class, sub))
                .isEqualTo("FULFILLING");
        long signedAt = System.currentTimeMillis();
        java.time.LocalDateTime sourceCreated = jdbc.queryForObject(
                "SELECT created_at FROM ord_shipment WHERE shipment_no=?", java.time.LocalDateTime.class, source);
        assertThat(signedAt).as("sourceCreated=%s zone=%s", sourceCreated, java.time.ZoneId.systemDefault())
                .isGreaterThanOrEqualTo(sourceCreated.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        - 1_000L);
        snapshots.converge(source, 0, number, "DELIVERED", signedAt, signedAt, signedAt);
        assertThat(jdbc.queryForObject("SELECT status FROM ord_shipment WHERE shipment_no=?", String.class, source))
                .isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT shipped_qty FROM ord_item WHERE sub_order_no=?", Integer.class, sub))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM ord_sub_order WHERE sub_order_no=?", String.class, sub))
                .isEqualTo("COMPLETED");
    }

    @Test
    void signedStatusWithoutDatedSignatureNodeDoesNotCompleteTrade() throws Exception {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, false);
        String number = "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT) + "U";
        shipping.ship(sub, new ShipCommand(List.of(), "STO", number),
                new OrderActor("LG-MERCHANT", "LG-STORE"));
        drain();
        jdbc.update("UPDATE lgs_waybill SET sub_channel='kuaidi100',sub_state='DONE' WHERE biz_ref=?", sub);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        OrderLogisticsFixture.pushForCurrentShipment(jdbc, mvc, number, "3", true, "shentong");
        drain();
        assertThat(jdbc.queryForObject("SELECT status FROM lgs_waybill WHERE biz_ref=?", String.class, sub))
                .isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT status FROM ord_sub_order WHERE sub_order_no=?", String.class, sub))
                .isEqualTo("FULFILLING");
    }

    @Test
    void statusOnlySignatureCannotCompleteTradeWithoutDatedNode() {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, false);
        String number = "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT) + "V";
        String source = shipping.ship(sub, new ShipCommand(List.of(), "STO", number),
                new OrderActor("LG-MERCHANT", "LG-STORE")).shipmentId();
        drain();
        snapshots.converge(source, 0, number, "DELIVERED", System.currentTimeMillis(), null, null);
        assertThat(jdbc.queryForObject("SELECT status FROM ord_sub_order WHERE sub_order_no=?", String.class, sub))
                .isEqualTo("FULFILLING");
    }

    @Test
    void reusedTrackingWithScansBeforeOrderCannotCompleteTrade() {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, false);
        String number = "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT) + "W";
        String source = shipping.ship(sub, new ShipCommand(List.of(), "STO", number),
                new OrderActor("LG-MERCHANT", "LG-STORE")).shipmentId();
        drain();
        long signedAt = System.currentTimeMillis();
        snapshots.converge(source, 0, number, "DELIVERED", signedAt, signedAt,
                signedAt - 86_400_000L);
        assertThat(jdbc.queryForObject("SELECT status FROM ord_sub_order WHERE sub_order_no=?", String.class, sub))
                .isEqualTo("FULFILLING");
    }

    @Test
    void mistakenSignedWaybillCanBeCorrectedWhileTradeStillFulfilling() throws Exception {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, false);
        String oldNo = "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT) + "S";
        String newNo = oldNo.substring(0, oldNo.length() - 1) + "T";
        String sourceNo = shipping.ship(sub, new ShipCommand(List.of(), "STO", oldNo),
                new OrderActor("LG-MERCHANT", "LG-STORE")).shipmentId();
        drain();
        String lgsNo = jdbc.queryForObject("SELECT shipment_no FROM lgs_waybill WHERE biz_ref=?", String.class, sub);
        jdbc.update("UPDATE lgs_waybill SET status='DELIVERED',signed_at=? WHERE shipment_no=?",
                System.currentTimeMillis(), lgsNo);
        jdbc.update("UPDATE ord_shipment SET status='DELIVERED',signed_at=? WHERE shipment_no=?",
                System.currentTimeMillis(), sourceNo);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String token = TestLogin.admin(mvc, json);

        String body = mvc.perform(post("/ops/shipments/" + lgsNo + "/waybill")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"carrier\":\"STO\",\"waybillNo\":\"" + newNo
                        + "\",\"reason\":\"原号码误签收\"}"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).path("code").asInt(-1)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM ord_shipment WHERE shipment_no=?",
                String.class, sourceNo)).isEqualTo("VOIDED");
        assertThat(jdbc.queryForObject("SELECT status FROM lgs_waybill WHERE shipment_no=?",
                String.class, lgsNo)).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT signed_at FROM lgs_waybill WHERE shipment_no=?",
                Long.class, lgsNo)).isNull();
    }

    @Test
    void mappedOpsCorrectionUsesPlatformWriterAndNewPushConverges() throws Exception {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, false);
        String oldNo = "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT) + "A";
        String newNo = oldNo.substring(0, oldNo.length() - 1) + "B";
        String oldSource = shipping.ship(sub, new ShipCommand(List.of(), "STO", oldNo),
                new OrderActor("LG-MERCHANT", "LG-STORE")).shipmentId();
        drain();
        String lgsNo = jdbc.queryForObject("SELECT shipment_no FROM lgs_waybill WHERE biz_ref=?", String.class, sub);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String token = TestLogin.admin(mvc, json);
        String body = mvc.perform(post("/ops/shipments/" + lgsNo + "/waybill")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"carrier\":\"STO\",\"waybillNo\":\"" + newNo + "\",\"reason\":\"修正面单\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).path("code").asInt(-1)).isZero();
        drain();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_shipment WHERE sub_order_no=?",
                Long.class, sub)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM ord_shipment WHERE shipment_no=?",
                String.class, oldSource)).isEqualTo("VOIDED");
        assertThat(jdbc.queryForObject("SELECT operator_type FROM ord_status_log WHERE aggregate_no=? "
                + "AND status='VOIDED'", String.class, oldSource)).isEqualTo("PLATFORM");
        jdbc.update("UPDATE sys_outbox SET status='PENDING',next_retry_at=NULL WHERE aggregate_id=? "
                + "AND event_type='SUB_ORDER_SHIPPED'", sub);
        drain();
        assertThat(jdbc.queryForObject("SELECT waybill_no FROM lgs_waybill WHERE biz_ref=?", String.class, sub))
                .isEqualTo(newNo);
        jdbc.update("UPDATE lgs_waybill SET sub_channel='kuaidi100',sub_state='DONE' WHERE biz_ref=?", sub);
        OrderLogisticsFixture.push(mvc, oldNo, "3", true);
        OrderLogisticsFixture.pushForCurrentShipment(jdbc, mvc, newNo, "3", true, "shentong");
        drain();
        assertThat(jdbc.queryForObject("SELECT status FROM lgs_waybill WHERE biz_ref=?", String.class, sub))
                .isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT status FROM sys_outbox WHERE event_type='LGS_SHIPMENT_SNAPSHOT' "
                + "AND aggregate_id=(SELECT shipment_no FROM ord_shipment WHERE sub_order_no=? "
                + "AND status<>'VOIDED') ORDER BY id DESC LIMIT 1", String.class, sub))
                .isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT status FROM ord_shipment WHERE sub_order_no=? "
                + "AND status<>'VOIDED'", String.class, sub)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT status FROM ord_sub_order WHERE sub_order_no=?",
                String.class, sub)).isEqualTo("FULFILLING");
    }

    @Test
    void mappedUploadFailureIsVisibleThroughAuthorizedOps() throws Exception {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, true);
        shipping.ship(sub, new ShipCommand(List.of(), "STO", "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT)),
                new OrderActor("LG-MERCHANT", "LG-STORE"));
        drain();
        jdbc.update("UPDATE trd_shipping_upload SET status='FAILED',err_code=10060011,err_msg='未开通上传' "
                + "WHERE order_no=?", "O-" + sub);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String token = TestLogin.admin(mvc, json);
        String body = mvc.perform(get("/ops/shipments").param("keyword", sub)
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString();
        var row = json.readTree(body).path("data").path("records").get(0);
        assertThat(row.path("wxUploadStatus").asString("")).isEqualTo("FAILED");
        assertThat(row.path("wxUploadError").asString("")).contains("未开通上传");
    }

    @Test
    void mappedUploadRetryUsesOriginalProtectedResourceAndPreservesHardBlock() throws Exception {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, true);
        shipping.ship(sub, new ShipCommand(List.of(), "STO", "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT)),
                new OrderActor("LG-MERCHANT", "LG-STORE"));
        drain();
        String lgsNo = jdbc.queryForObject("SELECT shipment_no FROM lgs_waybill WHERE biz_ref=?", String.class, sub);
        Long queueId = jdbc.queryForObject("SELECT id FROM trd_shipping_upload WHERE order_no=?",
                Long.class, "O-" + sub);
        jdbc.update("UPDATE trd_shipping_upload SET status='FAILED',err_code=10060011 WHERE id=?", queueId);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String token = TestLogin.admin(mvc, json);
        String body = mvc.perform(post("/ops/shipments/" + lgsNo + "/replay")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"WX_UPLOAD\"}")).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).path("code").asInt(-1)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM trd_shipping_upload WHERE id=?", String.class, queueId))
                .isEqualTo("PENDING");
        jdbc.update("UPDATE trd_shipping_upload SET status='FAILED',err_code=10060003 WHERE id=?", queueId);
        String rejected = mvc.perform(post("/ops/shipments/" + lgsNo + "/replay")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"WX_UPLOAD\"}")).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(rejected).path("code").asInt(-1)).isNotZero();
        assertThat(jdbc.queryForObject("SELECT status FROM trd_shipping_upload WHERE id=?", String.class, queueId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_shipment WHERE sub_order_no=?", Long.class, sub))
                .isEqualTo(1);
    }

    @Test
    void sameNumberOldCarrierPushCannotSignCorrectedCompany() throws Exception {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, false);
        String number = "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT);
        shipping.ship(sub, new ShipCommand(List.of(), "STO", number), new OrderActor("LG-MERCHANT", "LG-STORE"));
        drain();
        String current = shipping.ship(sub, new ShipCommand(List.of(), "YTO", number),
                new OrderActor("LG-MERCHANT", "LG-STORE")).shipmentId();
        drain();
        jdbc.update("UPDATE lgs_waybill SET sub_channel='kuaidi100',sub_state='DONE' WHERE biz_ref=?", sub);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        OrderLogisticsFixture.push(mvc, number, "3", true);
        drain();
        assertThat(jdbc.queryForObject("SELECT status FROM ord_shipment WHERE shipment_no=?", String.class, current))
                .isEqualTo("CREATED");
        OrderLogisticsFixture.pushForCurrentShipment(jdbc, mvc, number, "3", true, "yuantong");
        drain();
        assertThat(jdbc.queryForObject("SELECT status FROM lgs_waybill WHERE biz_ref=?", String.class, sub))
                .isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT status FROM ord_shipment WHERE shipment_no=?", String.class, current))
                .isEqualTo("DELIVERED");
    }

    private void drain() {
        for (int i = 0; i < 50 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
    }
}
