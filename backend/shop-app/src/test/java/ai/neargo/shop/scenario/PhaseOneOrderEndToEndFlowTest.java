package ai.neargo.shop.scenario;

import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.support.OrderJourneyFixture;
import ai.neargo.shop.support.OrderLogisticsFixture;
import ai.neargo.shop.support.port.FakePayQueryPort;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Actual C/B controllers, production stores, signed logistics push and verified external financial facts. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.journey.test.url:jdbc:h2:mem:phase1-order-journey;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
})
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class PhaseOneOrderEndToEndFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private FakePayQueryPort external;
    @Autowired private BasicRefundExecutionService refunds;

    @Test
    void actualPurchasePaymentPreparationShippingPushAndBuyerReceiptCompleteOnce() throws Exception {
        try (var f = new OrderJourneyFixture(context, "13208081001", "13208081002")) {
            var p = f.purchase();
            f.prepare(p);
            String number = f.ship(p);
            drain();
            assertThat(f.buyerDetail(p).path("expressNo").asString()).isEqualTo(number);
            assertThat(f.merchantDetail(p).path("expressNo").asString()).isEqualTo(number);
            String source = jdbc.queryForObject("select shipment_no from ord_shipment where sub_order_no=?",
                    String.class, p.subNo());
            assertThat(jdbc.queryForObject("select source_shipment_no from lgs_waybill where biz_ref=?",
                    String.class, p.subNo())).isEqualTo(source);
            // The external subscription succeeds; the actual callback still verifies its signature and source.
            jdbc.update("update lgs_waybill set sub_channel='kuaidi100',sub_state='DONE' where biz_ref=?", p.subNo());
            OrderLogisticsFixture.pushForCurrentShipment(jdbc, f.mvc(), number, "3", true, "shentong");
            drain();
            assertThat(jdbc.queryForObject("select status from ord_shipment where shipment_no=?",
                    String.class, source)).isEqualTo("DELIVERED");
            assertThat(f.buyerDetail(p).path("status").asString()).isEqualTo("FULFILLING");
            assertThat(f.buyerDetail(p).path("logisticsSync").path("status").asString()).isEqualTo("DELIVERED");
            assertThat(f.merchantDetail(p).path("logisticsSync").path("status").asString()).isEqualTo("DELIVERED");
            assertThat(f.buyerOrders().path("records").toString()).contains(p.subNo());
            assertThat(f.merchantOrders().path("records").toString()).contains(p.subNo());
            f.receive(p);
            f.receive(p);
            assertThat(f.buyerDetail(p).path("status").asString()).isEqualTo("COMPLETED");
            assertThat(f.merchantDetail(p).path("status").asString()).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("select status from ord_shipment where shipment_no=?",
                    String.class, source)).isEqualTo("RECEIVED");
            assertThat(jdbc.queryForObject("select count(*) from ord_status_log where sub_order_no=?"
                    + " and status='COMPLETED'", Long.class, p.subNo())).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select count(*) from stl_bill where order_no=?",
                    Long.class, p.payNo())).isEqualTo(1L);
        }
    }

    @Test
    void actualBuyerRefundMerchantApprovalAndCashCallbackRestoreOnce() throws Exception {
        try (var f = new OrderJourneyFixture(context, "13208081003", "13208081004")) {
            var p = f.purchase();
            String afterSale = f.applyRefund(p);
            assertThat(f.approveRefund(afterSale).path("status").asString()).isEqualTo("REFUNDING");
            assertThat(jdbc.queryForObject("select coalesce(refunded_minor,0) from ord_sub_order"
                    + " where sub_order_no=?", Long.class, p.subNo())).isZero();
            String outNo = jdbc.queryForObject("select out_trade_no from stl_payment where after_sale_no=?"
                    + " and direction='REFUND'", String.class, afterSale);
            var fact = f.gateway().queryRefund(outNo);
            assertThat(fact.ok() && fact.found() && fact.paid()).isTrue();
            external.answer(new PayQueryPort.Result(true, true, true,
                    fact.amountMinor(), fact.tradeNo(), fact.currency()));
            String ack = f.mvc().perform(post("/pay/callback/TEST/refund").contentType(MediaType.APPLICATION_JSON)
                    .content("verified:" + outNo)).andReturn().getResponse().getContentAsString();
            assertThat(ack).isEqualTo("SUCCESS");
            refunds.recover("RF-" + afterSale);
            refunds.recover("RF-" + afterSale);
            assertThat(f.buyerDetail(p).path("afterSale").path("status").asString()).isEqualTo("REFUNDED");
            assertThat(jdbc.queryForObject("select status from ord_after_sale where after_sale_no=?",
                    String.class, afterSale)).isEqualTo("REFUNDED");
            assertThat(jdbc.queryForObject("select refunded_minor from ord_sub_order where sub_order_no=?",
                    Long.class, p.subNo())).isEqualTo(fact.amountMinor());
            assertThat(jdbc.queryForObject("select sum(qty) from prd_stock_restore where purpose_no=?",
                    Long.class, "RF-" + afterSale)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select count(*) from stl_payment where after_sale_no=?"
                    + " and direction='REFUND'", Long.class, afterSale)).isEqualTo(1L);
        } finally {
            external.reset();
        }
    }

    private void drain() {
        for (int i = 0; i < 8; i++) { dispatcher.dispatchPending(); }
    }
}
