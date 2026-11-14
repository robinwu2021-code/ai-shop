package ai.neargo.shop.payclient.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort.*;
import org.junit.jupiter.api.Test;

class RemoteOrdinaryPaymentPortTest {
    private final PayInternalApi api = mock(PayInternalApi.class);
    private final RemoteOrdinaryPaymentPort port = new RemoteOrdinaryPaymentPort(api);
    private final Reference reference = new Reference("O1", "U1", "O1-2");

    private Attempt attempt(String userNo, String outTradeNo) {
        return new Attempt("P1", outTradeNo, "O1", userNo, "E1", "WECHAT", "TX1",
                123L, "USD", "SUCCESS", "D1");
    }

    @Test
    void queryPreservesOriginalMoneyAndRetryReference() {
        var result = new Lookup(true, attempt("U1", "O1-2"));
        when(api.ordinaryPaymentQuery(reference)).thenReturn(result);
        assertThat(port.query(reference)).isEqualTo(result);
        verify(api, times(1)).ordinaryPaymentQuery(reference);
    }

    @Test
    void responseForAnotherOwnerOrAttemptFailsClosed() {
        when(api.ordinaryPaymentQuery(reference)).thenReturn(new Lookup(true, attempt("OTHER", "O1-2")));
        assertThatThrownBy(() -> port.query(reference)).isInstanceOf(IllegalStateException.class);
        when(api.ordinaryPaymentQuery(reference)).thenReturn(new Lookup(true, attempt("U1", "O1")));
        assertThatThrownBy(() -> port.query(reference)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void incompleteNotFoundReplyCannotHidePayment() {
        when(api.ordinaryPaymentQuery(reference)).thenReturn(new Lookup(false, attempt("U1", "O1-2")));
        assertThatThrownBy(() -> port.query(reference)).isInstanceOf(IllegalStateException.class);
    }
}
