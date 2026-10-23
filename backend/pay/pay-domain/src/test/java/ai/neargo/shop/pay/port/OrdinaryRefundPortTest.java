package ai.neargo.shop.pay.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort.Attempt;
import ai.neargo.shop.spi.settle.OrdinaryRefundPort;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundRequest;
import org.junit.jupiter.api.Test;

class OrdinaryRefundPortTest {
    private final PaymentLedgerService ledger = mock(PaymentLedgerService.class);
    private final RefundExecutionService refunds = mock(RefundExecutionService.class);
    private final OrdinaryRefundPort port = new OrdinaryRefundPortImpl(ledger, refunds);
    private final RefundRequest frozen = new RefundRequest("RF1", "AS1", "S1", "P1", 50, "USD", "return");
    private final OrdinaryRefundPort.Execution command = new OrdinaryRefundPort.Execution("O1", "U1", "O1-2", frozen);

    private Attempt original(String owner, String status) {
        return new Attempt("P1", "O1-2", "O1", owner, "E1", "WECHAT", "TX1", 100L, "USD", status, "D1");
    }

    @Test
    void executeUsesExactOriginalRetryAndFrozenExecutionWithoutReversalShortcut() {
        when(ledger.ordinaryPayment("O1-2")).thenReturn(original("U1", "SUCCESS"));
        var accepted = new RefundDispatchResult("RF1", "RFP1", RefundDispatchResult.DispatchStatus.ACCEPTED, null, true, null);
        when(refunds.request(frozen)).thenReturn(accepted);
        assertThat(port.request(command)).isEqualTo(new OrdinaryRefundPort.Receipt(command, accepted));
        verify(refunds).request(frozen);
        verify(refunds, never()).reverseUnsplit(any());
    }

    @Test
    void queryUsesFrozenRequestAndPreservesUnknownInsteadOfQueryingBareExecution() {
        when(ledger.ordinaryPayment("O1-2")).thenReturn(original("U1", "SUCCESS"));
        var unknown = new RefundDispatchResult("RF1", "RFP1", RefundDispatchResult.DispatchStatus.UNKNOWN,
                "REFUND_QUERY_UNKNOWN", true, null);
        when(refunds.query(frozen)).thenReturn(unknown);
        assertThat(port.query(command).outcome()).isEqualTo(unknown);
        verify(refunds, never()).query(anyString());
        verify(refunds, never()).request(any());
    }

    @Test
    void foreignOwnerFailedOriginAndDifferentPhysicalPaymentCannotDispatch() {
        for (Attempt wrong : java.util.List.of(original("OTHER", "SUCCESS"), original("U1", "PENDING"),
                new Attempt("OTHER_PAYMENT", "O1-2", "O1", "U1", "E1", "WECHAT", "TX1", 100L,
                        "USD", "SUCCESS", "D1"))) {
            when(ledger.ordinaryPayment("O1-2")).thenReturn(wrong);
            assertThatThrownBy(() -> port.request(command)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(refunds);
    }

    @Test
    void absentOriginalAndCurrencyMismatchCannotReachFinancialOwner() {
        assertThatThrownBy(() -> port.query(command)).isInstanceOf(IllegalArgumentException.class);
        when(ledger.ordinaryPayment("O1-2")).thenReturn(original("U1", "SUCCESS"));
        var changed = new RefundRequest("RF1", "AS1", "S1", "P1", 50, "CNY", "return");
        assertThatThrownBy(() -> port.request(new OrdinaryRefundPort.Execution("O1", "U1", "O1-2", changed)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(refunds);
    }
}
