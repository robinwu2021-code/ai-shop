package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.support.BasicRefundFixture;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.WebApplicationContext;

/** Only the external gateway is fault-injected; financial and order transactions execute against real databases. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.refund.recovery.test.url:jdbc:h2:mem:phase1-refund-recovery;"
                + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
})
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class RefundOriginalExecutionRecoveryFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BasicRefundExecutionService refunds;
    @MockitoSpyBean private TestPayGateway gateway;

    @Test
    void externalSuccessLocalFailureRecoversSameExecution() throws Exception {
        try (var f = new BasicRefundFixture(context)) {
            var p = f.purchase("13207071401");
            f.prepare(p);
            String no = f.apply(p, "REFUND_ONLY");
            doAnswer(call -> {
                call.callRealMethod();
                throw new IllegalStateException("external success followed by lost response");
            }).when(gateway).refund(any(), anyLong(), anyString(), anyString());
            f.approve(p, no);
            String execution = "RF-" + no;
            assertThat(state("ord_refund_execution", no)).isEqualTo("UNKNOWN");
            assertThat(state("stl_payment", no)).isEqualTo("PENDING");
            assertRefundedQty(p.subNo(), 0L);
            jdbc.update("update ord_after_sale set status='ARBITRATING' where after_sale_no=?", no);
            assertThatThrownBy(() -> refunds.recover(execution)).isInstanceOf(RuntimeException.class);
            assertThat(state("stl_payment", no)).isEqualTo("SUCCESS");
            assertRefundedQty(p.subNo(), 0L);
            jdbc.update("update ord_after_sale set status='REFUNDING' where after_sale_no=?", no);
            refunds.recover(execution);
            refunds.recover(execution);
            assertThat(state("ord_after_sale", no)).isEqualTo("REFUNDED");
            assertRefundedQty(p.subNo(), 1L);
            assertThat(jdbc.queryForObject("select count(*) from stl_payment where after_sale_no=?",
                    Integer.class, no)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select sum(qty) from prd_stock_restore where purpose_no=?",
                    Long.class, execution)).isEqualTo(1L);
            verify(gateway, times(1)).refund(any(), anyLong(), anyString(), anyString());
            verify(gateway, times(1)).queryRefund(execution);
        }
    }

    private String state(String table, String no) {
        return jdbc.queryForObject("select status from " + table + " where after_sale_no=?", String.class, no);
    }

    private void assertRefundedQty(String subNo, long expected) {
        assertThat(jdbc.queryForObject("select sum(refunded_qty) from ord_item where sub_order_no=?",
                Long.class, subNo)).isEqualTo(expected);
    }
}
