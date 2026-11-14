package ai.neargo.shop.payclient.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.spi.settle.OrdinaryRefundPort;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundRequest;
import org.junit.jupiter.api.Test;

class RemoteOrdinaryRefundPortTest {
    private final PayInternalApi api = mock(PayInternalApi.class);
    private final RemoteOrdinaryRefundPort port = new RemoteOrdinaryRefundPort(api);
    private final RefundRequest frozen = new RefundRequest("RF1", "AS1", "S1", "P1", 50, "USD", "return");
    private final OrdinaryRefundPort.Execution command = new OrdinaryRefundPort.Execution("O1", "U1", "O1-2", frozen);
    private final RefundDispatchResult unknown = new RefundDispatchResult("RF1", "RP1",
            RefundDispatchResult.DispatchStatus.UNKNOWN, "REFUND_QUERY_UNKNOWN", true, null);

    @Test
    void sendOnceAndQueryOriginalFrozenInstructionPreserveUnknown() {
        var expected = new OrdinaryRefundPort.Receipt(command, unknown);
        when(api.ordinaryRefundRequest(command)).thenReturn(expected);
        when(api.ordinaryRefundQuery(command)).thenReturn(expected);
        assertThat(port.request(command)).isEqualTo(expected);
        assertThat(port.query(command)).isEqualTo(expected);
        verify(api, times(1)).ordinaryRefundRequest(command);
        verify(api, times(1)).ordinaryRefundQuery(command);
    }

    @Test
    void responseWithAnotherOwnerOrExecutionIsRejected() {
        var foreign = new OrdinaryRefundPort.Execution("O1", "OTHER", "O1-2", frozen);
        when(api.ordinaryRefundQuery(command)).thenReturn(new OrdinaryRefundPort.Receipt(foreign, unknown));
        assertThatThrownBy(() -> port.query(command)).isInstanceOf(IllegalStateException.class);
        var changed = new RefundDispatchResult("OTHER", "RP1", RefundDispatchResult.DispatchStatus.SUCCEEDED, null, false, 123L);
        when(api.ordinaryRefundQuery(command)).thenReturn(new OrdinaryRefundPort.Receipt(command, changed));
        assertThatThrownBy(() -> port.query(command)).isInstanceOf(IllegalStateException.class);
    }
}
