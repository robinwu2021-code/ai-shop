package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.spi.settle.RefundDispatchResult.DispatchStatus;
import ai.neargo.shop.support.BasicRefundFixture;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.WebApplicationContext;

/** A real cash-success receipt cannot project a different immutable local execution. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:refund-coordinate;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class RefundCoordinateRecoveryFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RefundExecutionService financial;
    @Autowired private BasicRefundExecutionService refunds;
    @MockitoSpyBean private TestPayGateway gateway;

    @ParameterizedTest
    @ValueSource(strings = {"amount_minor", "currency", "pay_channel", "user_no", "origin_payment_no",
            "after_sale_no", "sub_order_no"})
    void mismatchedFinancialCoordinatesRemainVisibleUntilReconciled(String column) throws Exception {
        int suffix = List.of("amount_minor", "currency", "pay_channel", "user_no", "origin_payment_no",
                "after_sale_no", "sub_order_no").indexOf(column);
        try (var f = new BasicRefundFixture(context)) {
            var p = f.purchase("1320707190" + suffix);
            String no = f.apply(p, "REFUND_ONLY");
            f.approve(p, no);
            String out = jdbc.queryForObject("select out_trade_no from stl_payment where after_sale_no=?",
                    String.class, no);
            var actual = gateway.queryRefund(out);
            assertThat(financial.confirm(out, "TEST", actual.amountMinor(), actual.currency(), actual.tradeNo())
                    .status()).isEqualTo(DispatchStatus.SUCCEEDED);
            Object original = jdbc.queryForObject("select " + column + " from stl_payment where out_trade_no=?",
                    Object.class, out);
            Object mismatch = "amount_minor".equals(column) ? actual.amountMinor() - 1 : "MISMATCH";
            jdbc.update("update stl_payment set " + column + "=? where out_trade_no=?", mismatch, out);
            clearInvocations(gateway);
            try {
                refunds.recover("RF-" + no);
                assertThat(jdbc.queryForObject("select status from ord_refund_execution where after_sale_no=?",
                        String.class, no)).isEqualTo("UNKNOWN");
                assertThat(jdbc.queryForObject("select error_code from ord_refund_execution where after_sale_no=?",
                        String.class, no)).isEqualTo("REFUND_EXECUTION_CONFLICT");
                assertThat(jdbc.queryForObject("select refunded_minor from ord_sub_order where sub_order_no=?",
                        Long.class, p.subNo())).isZero();
            } finally {
                jdbc.update("update stl_payment set " + column + "=? where out_trade_no=?", original, out);
            }
            refunds.recover("RF-" + no);
            assertThat(jdbc.queryForObject("select status from ord_refund_execution where after_sale_no=?",
                    String.class, no)).isEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("select sum(qty) from prd_stock_restore where purpose_no=?",
                    Long.class, "RF-" + no)).isEqualTo(1L);
            verify(gateway, never()).refund(any(), anyLong(), anyString(), anyString());
            verify(gateway, never()).queryRefund(anyString());
        }
    }
}
