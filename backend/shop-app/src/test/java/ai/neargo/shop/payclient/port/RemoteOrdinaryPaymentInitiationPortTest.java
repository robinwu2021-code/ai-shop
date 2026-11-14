package ai.neargo.shop.payclient.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests;
import ai.neargo.shop.payclient.OrdinaryPaymentPayerResolver;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.user.UserIdentityPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RemoteOrdinaryPaymentInitiationPortTest {
    @Test
    void resolvesSessionAwarePayerInCoreAndSendsOnceWithoutForwardingUserToken() {
        var identities = mock(UserIdentityPort.class);
        var pay = mock(PayInternalApi.class);
        var resolver = new OrdinaryPaymentPayerResolver(identities, "APP1");
        var port = new RemoteOrdinaryPaymentInitiationPort(pay, resolver);
        var request = new SettlePort.PaymentOpen("O1", "U1", "E1", "WECHAT", 120,
                "items", "USD", List.of());
        when(identities.wxPayerOpenIdMp("U1")).thenReturn(Optional.of("session-openid"));
        var identity = new PayOrdinaryPaymentRequests.PayerIdentity("U1", "O1", "session-openid", "APP1",
                PayOrdinaryPaymentRequests.hash(request));
        var command = new PayOrdinaryPaymentRequests.Initiate(request, identity);
        var result = new SettlePort.PayInitResult(true, "O1-2", "WECHAT", Map.of("package", "p1"), null);
        when(pay.ordinaryPaymentInitiate(command)).thenReturn(result);
        assertThat(port.initiate(request)).isEqualTo(result);
        verify(identities).wxPayerOpenIdMp("U1");
        verify(pay, times(1)).ordinaryPaymentInitiate(command);
        verify(identities, never()).wxOpenIdMp(anyString());
    }
}
