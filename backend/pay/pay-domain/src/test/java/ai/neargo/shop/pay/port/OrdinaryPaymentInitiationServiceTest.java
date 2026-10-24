package ai.neargo.shop.pay.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.PayGatewayRouter;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.impl.OrdinaryPaymentInitiationServiceImpl;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests;
import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests.PayerIdentity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OrdinaryPaymentInitiationServiceTest {
    private final PaymentLedgerService ledger = mock(PaymentLedgerService.class);
    private final PayGatewayRouter router = mock(PayGatewayRouter.class);
    private final PayGateway gateway = mock(PayGateway.class);
    private final OrdinaryPaymentInitiationServiceImpl service =
            new OrdinaryPaymentInitiationServiceImpl(ledger, router);
    private final SettlePort.PaymentOpen request = new SettlePort.PaymentOpen(
            "O1", "U1", "E1", "WECHAT", 123, "items", "USD", List.of());

    private void prepare() {
        when(ledger.open(request)).thenReturn("O1-2");
        when(ledger.paymentRequest("O1-2")).thenReturn(Optional.of(request));
        when(router.of("WECHAT")).thenReturn(gateway);
    }

    private PayerIdentity payer(String openid) {
        return new PayerIdentity("U1", "O1", openid, "APP1", PayOrdinaryPaymentRequests.hash(request));
    }

    @Test
    void missingIdentityCannotCloseAnExistingIntent() {
        assertThatThrownBy(() -> service.initiate(request, payer(null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ledger, gateway);
    }

    @Test
    void changedIdentityFailsBeforeChannelWithoutClosingOriginalAttempt() {
        prepare();
        doThrow(new IllegalArgumentException("PAY_PAYER_IDENTITY_CONFLICT"))
                .when(ledger).freezePayer("O1-2", "U1", "OTHER", "APP1");
        assertThatThrownBy(() -> service.initiate(request, payer("OTHER")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(gateway);
        verify(ledger, never()).close(anyString(), anyString());
    }

    @Test
    void sharesActualLedgerSnapshotGatewayAndPayerResolution() {
        prepare();
        when(gateway.prepay(any())).thenReturn(PayGateway.PrepayResult.ok(Map.of("package", "p"), null));
        var result = service.initiate(request, payer("PAYER1"));
        assertThat(result.success()).isTrue();
        assertThat(result.outTradeNo()).isEqualTo("O1-2");
        verify(gateway).prepay(new PayGateway.PrepayCommand("O1-2", 123, "USD", "items", "JSAPI", null, "PAYER1"));
        var ordered = inOrder(ledger, gateway);
        ordered.verify(ledger).freezePayer("O1-2", "U1", "PAYER1", "APP1");
        ordered.verify(gateway).prepay(any());
        ordered.verify(ledger).recordPayer("O1-2", "PAYER1", "APP1");
    }

    @Test
    void ambiguousChannelResponseKeepsOriginalIntentPending() {
        prepare();
        when(gateway.prepay(any())).thenReturn(PayGateway.PrepayResult.unknown("timeout"));
        assertThat(service.initiate(request, payer("PAYER1")).success()).isFalse();
        verify(ledger, never()).close(anyString(), anyString());
    }

    @Test
    void thrownTransportFailureCannotCloseIntent() {
        prepare();
        when(gateway.prepay(any())).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(() -> service.initiate(request, payer("PAYER1"))).isInstanceOf(IllegalStateException.class);
        verify(ledger, never()).close(anyString(), anyString());
    }
    @Test
    void evenDefinitiveLocalFailureCannotCloseEarlierUncertainDispatch() {
        prepare();
        when(gateway.prepay(any())).thenReturn(PayGateway.PrepayResult.fail("configuration missing"));
        assertThat(service.initiate(request, payer("PAYER1")).success()).isFalse();
        verify(ledger, never()).close(anyString(), anyString());
    }

    @Test
    void missingGatewayKeepsOriginalIntentPending() {
        prepare();
        when(router.of("WECHAT")).thenThrow(new IllegalStateException("not installed"));
        assertThat(service.initiate(request, payer("PAYER1")).success()).isFalse();
        verify(ledger, never()).close(anyString(), anyString());
        verifyNoInteractions(gateway);
    }

}
