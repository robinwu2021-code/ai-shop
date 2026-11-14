package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.channel.FreePayGateway;
import ai.neargo.shop.support.BasicRefundFixture;
import ai.neargo.shop.support.ZeroRefundCrashFixture;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.WebApplicationContext;

/** Server-calculated FREE orders still release original resources without sending a zero cash request. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.refund.zero.test.url:jdbc:h2:mem:phase1-refund-zero;"
                + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
})
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class BasicZeroRefundFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BasicRefundExecutionService refunds;
    @MockitoSpyBean private TestPayGateway gateway;
    @MockitoSpyBean private FreePayGateway free;

    @Test
    void freeOrderRecoversCommittedZeroClaimWithoutGatewayInstruction() throws Exception {
        long old = jdbc.queryForObject("select price from prd_sku where sku_no='SK0003'", Long.class);
        jdbc.update("update prd_sku set price=0 where sku_no='SK0003'");
        try (var f = new BasicRefundFixture(context)) {
            var purchase = f.purchase("13207071302");
            assertOriginalFreeReceipt(purchase.payNo());
            assertThat(jdbc.queryForObject("select pay_amount from ord_order where order_no=?",
                    Long.class, purchase.payNo())).isZero();
            ZeroRefundCrashFixture crash = new ZeroRefundCrashFixture(context);
            String no = crash.applyWithInterruptedCompletion(f, purchase);
            crash.recoverAndAssert(no, 1);
            verify(free, never()).refund(any(), anyLong(), anyString(), anyString());
            verify(free, never()).queryRefund(anyString());
            verify(gateway, never()).refund(any(), anyLong(), anyString(), anyString());
            verify(gateway, never()).queryRefund(anyString());
        } finally {
            jdbc.update("update prd_sku set price=? where sku_no='SK0003'", old);
        }
    }

    @Test
    void freeOrderRefundCompletesResourcesWithoutCashDispatch() throws Exception {
        long old = jdbc.queryForObject("select price from prd_sku where sku_no='SK0003'", Long.class);
        jdbc.update("update prd_sku set price=0 where sku_no='SK0003'");
        try (var f = new BasicRefundFixture(context)) {
            var p = f.purchase("13207071301");
            assertOriginalFreeReceipt(p.payNo());
            assertThat(jdbc.queryForObject("select pay_amount from ord_sub_order where sub_order_no=?",
                    Long.class, p.subNo())).isZero();
            String no = f.apply(p, "REFUND_ONLY");
            f.approve(p, no);
            refunds.recover("RF-" + no);
            refunds.recover("RF-" + no);
            assertThat(jdbc.queryForObject("select status from ord_after_sale where after_sale_no=?",
                    String.class, no)).isEqualTo("REFUNDED");
            assertThat(jdbc.queryForObject("select sum(qty) from prd_stock_restore where purpose_no=?",
                    Long.class, "RF-" + no)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select resource_status from ord_refund_execution where after_sale_no=?",
                    String.class, no)).isEqualTo("DONE");
            verify(gateway, never()).refund(any(), anyLong(), anyString(), anyString());
        } finally {
            jdbc.update("update prd_sku set price=? where sku_no='SK0003'", old);
        }
    }
    private void assertOriginalFreeReceipt(String orderNo) {
        String outTradeNo = jdbc.queryForObject("select out_trade_no from stl_payment where order_no=?"
                + " and direction='PAY' and pay_channel='FREE' and status='SUCCESS'", String.class, orderNo);
        String providerTradeNo = jdbc.queryForObject("select trade_no from stl_payment where out_trade_no=?"
                + " and direction='PAY'", String.class, outTradeNo);
        assertThat(providerTradeNo).isEqualTo("FREE:" + outTradeNo);
        assertThat(jdbc.queryForObject("select pay_trade_no from ord_order where order_no=?",
                String.class, orderNo)).isEqualTo(providerTradeNo).isNotEqualTo(outTradeNo);
    }

}
