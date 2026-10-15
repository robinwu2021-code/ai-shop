package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.support.BasicRefundFixture;
import ai.neargo.shop.support.OrderJourneyFixture;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import ai.neargo.shop.trade.service.AfterSaleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;

/** Actual B-created express goods, shipment, buyer return, merchant receipt and confirmed cash refund. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:refund-shipped;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class RefundShippedResourceFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AfterSaleService afterSales;
    @Autowired private BasicRefundExecutionService refunds;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void receivedReturnRestocksOnlyWhenMerchantApprovedResale(boolean restock) throws Exception {
        String buyer = restock ? "13207072201" : "13207072202";
        String owner = restock ? "13207072211" : "13207072212";
        try (var journey = new OrderJourneyFixture(context, buyer, owner);
             var f = new BasicRefundFixture(context)) {
            var shipped = journey.purchase();
            journey.prepare(shipped);
            journey.ship(shipped);
            var p = new BasicRefundFixture.Purchase(f.login(buyer), shipped.payNo(), shipped.subNo());
            String no = f.apply(p, "RETURN_REFUND");
            f.approve(p, no);
            assertThat(f.request(p.token(), "/mp/after-sale/" + no + "/ship",
                    "{\"expressCompany\":\"SF\",\"expressNo\":\"SF12345678901\"}")
                    .path("code").asInt()).isZero();
            assertThat(stockRestored(no)).isZero();
            afterSales.confirmReturn(f.merchant(p), no, restock);
            f.confirmCash(no);
            refunds.recover("RF-" + no);
            assertThat(stockRestored(no)).isEqualTo(restock ? 1L : 0L);
            assertThat(jdbc.queryForObject("select restock_approved from ord_after_sale where after_sale_no=?",
                    Boolean.class, no)).isEqualTo(restock);
            assertThat(jdbc.queryForObject("select return_received_at from ord_after_sale where after_sale_no=?",
                    Long.class, no)).isPositive();
            assertFullCash(p, no);
        }
    }

    @Test
    void shippedRefundOnlyNeverRestocksWithoutActualReturn() throws Exception {
        try (var journey = new OrderJourneyFixture(context, "13207072203", "13207072213");
             var f = new BasicRefundFixture(context)) {
            var shipped = journey.purchase();
            journey.prepare(shipped);
            journey.ship(shipped);
            var p = new BasicRefundFixture.Purchase(f.login("13207072203"), shipped.payNo(), shipped.subNo());
            String no = f.apply(p, "REFUND_ONLY");
            f.approve(p, no);
            if ("WAIT_RECEIPT".equals(jdbc.queryForObject(
                    "select status from ord_after_sale where after_sale_no=?", String.class, no))) {
                afterSales.confirmRefund(f.merchant(p), no);
            }
            f.confirmCash(no);
            refunds.recover("RF-" + no);
            assertThat(stockRestored(no)).isZero();
            assertThat(jdbc.queryForObject("select return_received_at from ord_after_sale where after_sale_no=?",
                    Long.class, no)).isNull();
            assertFullCash(p, no);
        }
    }

    private long stockRestored(String no) {
        Long qty = jdbc.queryForObject("select sum(qty) from prd_stock_restore where purpose_no=?",
                Long.class, "RF-" + no);
        return qty == null ? 0 : qty;
    }

    private void assertFullCash(BasicRefundFixture.Purchase p, String no) {
        long paid = jdbc.queryForObject("select pay_amount from ord_sub_order where sub_order_no=?",
                Long.class, p.subNo());
        assertThat(jdbc.queryForObject("select amount_minor from stl_payment where after_sale_no=?"
                + " and direction='REFUND' and status='SUCCESS'", Long.class, no)).isEqualTo(paid);
        assertThat(jdbc.queryForObject("select resource_status from ord_refund_execution where after_sale_no=?",
                String.class, no)).isEqualTo("DONE");
    }
}
