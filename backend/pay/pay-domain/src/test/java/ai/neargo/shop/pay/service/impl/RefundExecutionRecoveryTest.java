package ai.neargo.shop.pay.service.impl;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.channel.*;
import ai.neargo.shop.pay.policy.RefundOutcomePolicy;
import org.junit.jupiter.api.Test;
class RefundExecutionRecoveryTest {
    @Test void nullOrThrownQueryMustKeepKnownManualResponse() {
        var row=new StlPayment();row.setStatus("PENDING");row.setRefundQueryState("MANUAL_REQUIRED");
        row.setPayChannel("TEST");row.setOutTradeNo("RF");row.setPaymentNo("PY");row.setRefundExecutionNo("RF");row.setAmountMinor(100L);
        var store=mock(RefundPaymentStore.class);var capacity=mock(RefundCapacityLedger.class);
        var router=mock(PayGatewayRouter.class);var gateway=mock(PayGateway.class);
        when(store.byExecution("RF")).thenReturn(row);
        when(capacity.safety(any())).thenAnswer(c -> RefundOutcomePolicy.resolve(c.getArgument(0),false));
        when(router.of("TEST")).thenReturn(gateway);
        when(gateway.queryRefundOutcome("RF")).thenReturn(null).thenThrow(new IllegalStateException("transport"));
        var service=new RefundExecutionServiceImpl(store,router,mock(UnsplitRefundAdjustment.class),capacity);
        for(int attempt=0;attempt<2;attempt++) {
            var result=service.query("RF");
            assertThat(result.errorCode()).isEqualTo("REFUND_MANUAL_REQUIRED");
            assertThat(result.retryable()).isFalse();
        }
        verify(store,never()).result(anyString(),anyString(),anyString(),any());
    }
}
