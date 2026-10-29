package ai.neargo.shop.pay.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.legacy.LegacyChannelBridge;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.impl.OrdinaryPaymentInitiationServiceImpl;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests;
import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests.PayerIdentity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort.PayRoute;

class OrdinaryPaymentInitiationServiceTest {
    private final PaymentLedgerService ledger = mock(PaymentLedgerService.class);
    /** 渠道层（W2：路由表退役，调用方只认桥） */
    private final LegacyChannelBridge channels = mock(LegacyChannelBridge.class);
    private final OrdinaryPaymentInitiationServiceImpl service =
            new OrdinaryPaymentInitiationServiceImpl(ledger, channels);
    private final SettlePort.PaymentOpen request = new SettlePort.PaymentOpen(
            "O1", "U1", "E1", "WECHAT", 123, "items", "USD", List.of());

    private void prepare() {
        when(ledger.open(request)).thenReturn("O1-2");
        when(ledger.paymentRequest("O1-2")).thenReturn(Optional.of(request));
        when(channels.supports("WECHAT")).thenReturn(true);
    }

    private PayerIdentity payer(String openid) {
        return new PayerIdentity("U1", "O1", openid, "APP1", PayOrdinaryPaymentRequests.hash(request));
    }

    @Test
    void missingIdentityCannotCloseAnExistingIntent() {
        assertThatThrownBy(() -> service.initiate(request, payer(null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ledger, channels);
    }

    @Test
    void changedIdentityFailsBeforeChannelWithoutClosingOriginalAttempt() {
        prepare();
        doThrow(new IllegalArgumentException("PAY_PAYER_IDENTITY_CONFLICT"))
                .when(ledger).freezePayer("O1-2", "U1", "OTHER", "APP1");
        assertThatThrownBy(() -> service.initiate(request, payer("OTHER")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(channels, never()).prepay(anyString(), any());
        verify(ledger, never()).close(anyString(), anyString());
    }

    @Test
    void sharesActualLedgerSnapshotGatewayAndPayerResolution() {
        prepare();
        when(channels.prepay(eq("WECHAT"), any())).thenReturn(PayGateway.PrepayResult.ok(Map.of("package", "p"), null));
        var result = service.initiate(request, payer("PAYER1"));
        assertThat(result.success()).isTrue();
        assertThat(result.outTradeNo()).isEqualTo("O1-2");
        verify(channels).prepay("WECHAT",
                new PayGateway.PrepayCommand("O1-2", 123, "USD", "items", "JSAPI", null, "PAYER1"));
        var ordered = inOrder(ledger, channels);
        ordered.verify(ledger).freezePayer("O1-2", "U1", "PAYER1", "APP1");
        ordered.verify(channels).prepay(eq("WECHAT"), any());
        ordered.verify(ledger).recordPayer("O1-2", "PAYER1", "APP1");
    }

    @Test
    void ambiguousChannelResponseKeepsOriginalIntentPending() {
        prepare();
        when(channels.prepay(eq("WECHAT"), any())).thenReturn(PayGateway.PrepayResult.unknown("timeout"));
        assertThat(service.initiate(request, payer("PAYER1")).success()).isFalse();
        verify(ledger, never()).close(anyString(), anyString());
    }

    @Test
    void thrownTransportFailureCannotCloseIntent() {
        prepare();
        when(channels.prepay(eq("WECHAT"), any())).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(() -> service.initiate(request, payer("PAYER1"))).isInstanceOf(IllegalStateException.class);
        verify(ledger, never()).close(anyString(), anyString());
    }
    @Test
    void evenDefinitiveLocalFailureCannotCloseEarlierUncertainDispatch() {
        prepare();
        when(channels.prepay(eq("WECHAT"), any())).thenReturn(PayGateway.PrepayResult.fail("configuration missing"));
        assertThat(service.initiate(request, payer("PAYER1")).success()).isFalse();
        verify(ledger, never()).close(anyString(), anyString());
    }

    @Test
    void missingGatewayKeepsOriginalIntentPending() {
        prepare();
        when(channels.supports("WECHAT")).thenReturn(false);
        assertThat(service.initiate(request, payer("PAYER1")).success()).isFalse();
        verify(ledger, never()).close(anyString(), anyString());
        verify(channels, never()).prepay(anyString(), any());
    }

    @Test
    void oldRequestJsonAndDigestRemainCompatibleWithoutAFrozenAcquiringRoute() {
        String oldJson = "{\"orderNo\":\"O1\",\"userNo\":\"U1\",\"entityNo\":\"E1\","
                + "\"payChannel\":\"WECHAT\",\"amountMinor\":123,\"subject\":\"items\","
                + "\"currency\":\"USD\",\"stores\":[]}";
        var mapper = new ObjectMapper();
        var restored = mapper.readValue(oldJson, SettlePort.PaymentOpen.class);
        assertThat(restored).isEqualTo(request);
        assertThat(mapper.writeValueAsString(restored)).isEqualTo(oldJson);
        assertThat(restored.acquiringRoute()).isNull();
        assertThat(PayOrdinaryPaymentRequests.hash(restored)).isEqualTo(PayOrdinaryPaymentRequests.hash(request));
    }

    @Test
    void directInitiationCannotPrepareAnUnprovenSigningAccount() {
        var routed = request.withAcquiringRoute(new PayRoute(1L, 2L, "PM-2", "WECHAT", "CN", "USD",
                "MERCHANT_OWN", "AGGREGATED", "MCH-2", "SUB-2", null, 7, 1));
        var identity = new PayerIdentity("U1", "O1", "OPEN", "APP1", PayOrdinaryPaymentRequests.hash(routed));
        assertThatThrownBy(() -> service.initiate(routed, identity))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("ACQUIRING_ACCOUNT_UNVERIFIED");
        verifyNoInteractions(ledger);
        verify(channels, never()).prepay(anyString(), any());
    }

}
