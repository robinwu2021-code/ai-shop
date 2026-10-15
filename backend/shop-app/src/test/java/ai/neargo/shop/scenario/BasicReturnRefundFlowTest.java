package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.support.BasicRefundFixture;
import ai.neargo.shop.trade.service.AfterSaleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** A return tracking number is transit evidence, never merchant receipt or permission to restock. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.refund.return.test.url:jdbc:h2:mem:phase1-refund-return;"
                + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
})
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class BasicReturnRefundFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AfterSaleService afterSales;
    @Autowired private ObjectMapper json;

    @Test
    void platformDecisionCannotTreatTrackingNumberAsMerchantReceipt() throws Exception {
        try (var f = new BasicRefundFixture(context)) {
            var p = f.purchase("13207071201");
            String no = f.apply(p, "RETURN_REFUND");
            f.approve(p, no);
            assertThat(f.request(p.token(), "/mp/after-sale/" + no + "/ship",
                    "{\"expressCompany\":\"SF\",\"expressNo\":\"SF123456789\"}")
                    .path("code").asInt()).isZero();
            jdbc.update("update ord_after_sale set updated_at='2020-01-01 00:00:00' where after_sale_no=?", no);
            assertThat(f.request(p.token(), "/mp/after-sale/" + no + "/escalate",
                    "{\"appeal\":\"merchant has not received the return\"}").path("code").asInt()).isZero();
            var decision = afterSales.arbitrate(no, true, "MERCHANT", "return goods must arrive first", "OPS");
            assertThat(decision.status()).isEqualTo("RETURNING");
            assertThat(jdbc.queryForObject("select count(*) from ord_refund_execution where after_sale_no=?",
                    Integer.class, no)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from stl_payment where after_sale_no=?",
                    Integer.class, no)).isZero();
            afterSales.confirmReturn(f.merchant(p), no, false);
            assertThat(jdbc.queryForObject("select return_received_at from ord_after_sale where after_sale_no=?",
                    Long.class, no)).isPositive();
            f.confirmCash(no);
            assertThat(jdbc.queryForObject("select status from ord_after_sale where after_sale_no=?",
                    String.class, no)).isEqualTo("REFUNDED");
        }
    }

    @Test
    void merchantListExposesOriginalExecutionFailureForRecovery() throws Exception {
        try (var f = new BasicRefundFixture(context)) {
            var p = f.purchase("13207071202");
            String no = f.apply(p, "REFUND_ONLY");
            jdbc.update("update stl_payment set status='CLOSED' where order_no=? and direction='PAY'", p.payNo());
            f.approve(p, no);
            var view = afterSales.merchantList(f.merchant(p), null).stream()
                    .filter(as -> no.equals(as.afterSaleNo())).findFirst().orElseThrow();
            var progress = json.readTree(json.writeValueAsString(view)).path("refundExecution");
            assertThat(progress.path("executionNo").asString()).isEqualTo("RF-" + no);
            assertThat(progress.path("status").asString()).isEqualTo("FAILED");
            assertThat(progress.path("errorCode").asString()).isEqualTo("ORIGIN_PAYMENT_MISMATCH");
        }
    }
}
