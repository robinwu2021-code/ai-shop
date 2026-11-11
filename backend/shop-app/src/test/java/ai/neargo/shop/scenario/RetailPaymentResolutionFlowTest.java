package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.spi.settle.OrdinaryRefundPort;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentResolutionOpsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@ActiveProfiles("test")
class RetailPaymentResolutionFlowTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PaymentResolutionOpsService operations;
    @Autowired ai.neargo.shop.trade.order.service.lifecycle.PaymentResolutionService evidence;
    @MockitoBean OrdinaryRefundPort refunds;

    @Test
    void manualActionKeepsCancelledOrderAndRecoversSameOriginalExecution() throws Exception {
        String order = seed("LATE_PAYMENT", "CANCELLED", true);
        when(refunds.request(any())).thenAnswer(call -> {
            var command = (OrdinaryRefundPort.Execution) call.getArgument(0);
            assertThat(command.originOutTradeNo()).isEqualTo("OT-" + order);
            assertThat(command.refund().originPaymentNo()).isEqualTo("P-" + order);
            assertThat(command.refund().subOrderNo()).isNull();
            assertThat(command.refund().amountMinor()).isEqualTo(1000);
            return receipt(command, RefundDispatchResult.DispatchStatus.UNKNOWN, "CHANNEL_UNKNOWN");
        });
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        when(refunds.query(any())).thenAnswer(call -> {
            var command = (OrdinaryRefundPort.Execution) call.getArgument(0);
            return reads.getAndIncrement() == 0 ? new OrdinaryRefundPort.Receipt(command,
                    new RefundDispatchResult(command.refund().executionNo(), null,
                            RefundDispatchResult.DispatchStatus.UNKNOWN, "REFUND_NOT_PREPARED", true, null))
                    : receipt(command, RefundDispatchResult.DispatchStatus.SUCCEEDED, null);
        });
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String token = TestLogin.admin(mvc, json);
        String path = "/ops/order-payment-resolutions/" + order + "/P-" + order + "/refund";
        mvc.perform(post(path).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("REFUNDING"))
                .andExpect(jsonPath("$.data.refundErrorCode").value("CHANNEL_UNKNOWN"));
        mvc.perform(post(path).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));
        mvc.perform(post(path).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));
        var sent = org.mockito.ArgumentCaptor.forClass(OrdinaryRefundPort.Execution.class);
        verify(refunds).request(sent.capture());
        verify(refunds, times(2)).query(sent.getValue());
        assertThat(jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no=?", String.class, order))
                .isEqualTo("CANCELLED");
    }

    @Test
    void historicalMissingCoordinatesAndUnapprovedKindsCannotDispatch() {
        for (String order : new String[]{seed("LATE_PAYMENT", "CANCELLED", false),
                seed("RECEIPT_MISMATCH", "CANCELLED", true), seed("LATE_PAYMENT", "PAID", true)}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> operations.refund(order, "P-" + order))
                    .isInstanceOf(ai.neargo.shop.common.BizException.class);
        }
        verifyNoInteractions(refunds);
    }

    @Test
    void oneMerchantCannotSeeOrRefundCrossMerchantParent() {
        String order = seed("LATE_PAYMENT", "CANCELLED", true);
        child(order, "S2-" + order, "OTHER");
        DataScopeContext.set(DataScopeSpec.of("MERCHANT", Set.of("M-" + order)));
        try {
            assertThat(operations.list(null).stream().map(PaymentResolutionOpsService.Case::payNo))
                    .doesNotContain(order);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> operations.refund(order, "P-" + order))
                    .isInstanceOf(ai.neargo.shop.common.BizException.class);
        } finally {
            DataScopeContext.clear();
        }
        verifyNoInteractions(refunds);
    }

    @Test
    void merchantSeesParentOnlyWhenEveryChildIsVisible() {
        String order = seed("LATE_PAYMENT", "CANCELLED", true);
        child(order, "S2-" + order, "M-" + order);
        DataScopeContext.set(DataScopeSpec.of("MERCHANT", Set.of("M-" + order)));
        try {
            var cases = operations.list(null).stream()
                    .filter(row -> order.equals(row.payNo())).toList();
            assertThat(cases).hasSize(1);
            assertThat(cases.getFirst().canRefund()).isTrue();
            assertThat(cases.getFirst().amountMinor()).isEqualTo(1000);
        } finally {
            DataScopeContext.clear();
        }
        verifyNoInteractions(refunds);
    }

    @Test
    void callbackReplayKeepsOriginalCoordinatesAndCompletedResolution() {
        String no = "FACT" + UUID.randomUUID().toString().replace("-", "");
        var order = new ai.neargo.shop.trade.entity.OrdOrder();
        order.setOrderNo(no);
        order.setUserNo("BUYER");
        var receipt = new ai.neargo.shop.spi.settle.SettlePort.PaymentReceipt("P-" + no, no, "BUYER", "WX",
                "T-" + no, 1000, "CNY", "SUCCESS", null, "ORIGINAL-" + no);
        evidence.record(order, receipt, "LATE_PAYMENT");
        jdbc.update("UPDATE ord_payment_resolution SET status='RESOLVED',refund_no='ORIGINAL-REFUND' "
                + "WHERE pay_no=?", no);
        evidence.record(order, new ai.neargo.shop.spi.settle.SettlePort.PaymentReceipt("P-" + no, no, "BUYER",
                "WX", "T-" + no, 1000, "CNY", "SUCCESS", null, "CHANGED"), "LATE_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT origin_out_trade_no FROM ord_payment_resolution WHERE pay_no=?",
                String.class, no)).isEqualTo("ORIGINAL-" + no);
        assertThat(jdbc.queryForObject("SELECT refund_no FROM ord_payment_resolution WHERE pay_no=?",
                String.class, no)).isEqualTo("ORIGINAL-REFUND");
    }

    private String seed(String reason, String state, boolean coordinates) {
        String order = "R02" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_order(order_no,user_no,status,pay_amount,currency,created_at,updated_at)"
                + " VALUES (?,'R02-BUYER',?,1000,'CNY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", order, state);
        child(order, "S-" + order, "M-" + order);
        jdbc.update("INSERT INTO ord_payment_resolution(pay_no,payment_no,user_no,source_user_no,source_pay_no,"
                + "amount_minor,currency,pay_channel,trade_no,reason,status,origin_out_trade_no,created_at,updated_at)"
                + " VALUES (?,?,'R02-BUYER','R02-BUYER',?,1000,'CNY','WX',?,?,'MANUAL_REQUIRED',?,"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", order, "P-" + order, order, "T-" + order, reason,
                coordinates ? "OT-" + order : null);
        return order;
    }

    private void child(String order, String sub, String merchant) {
        jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,status,fulfillment,"
                + "created_at,updated_at) VALUES (?,?,'R02-BUYER',?,'CANCELLED','EXPRESS',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, order, merchant);
    }

    private OrdinaryRefundPort.Receipt receipt(OrdinaryRefundPort.Execution command,
            RefundDispatchResult.DispatchStatus status, String error) {
        return new OrdinaryRefundPort.Receipt(command, new RefundDispatchResult(command.refund().executionNo(),
                "RF-PAY", status, error, false, status == RefundDispatchResult.DispatchStatus.SUCCEEDED ? 1L : null));
    }
}
