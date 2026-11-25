package ai.neargo.shop.trade.api.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.pay.PayMessagePort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.service.OrderService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(OutputCaptureExtension.class)
class StubPayCallbackLoggingTest {

    @Test
    void signatureRejectionLogsInternalMessageNumberAndKeepsRejectedReceipt(CapturedOutput logs) {
        var orders = mock(OrderService.class);
        var settle = mock(SettlePort.class);
        var messages = mock(PayMessagePort.class);
        var callback = new PayCallbackController(orders, settle, messages);
        ReflectionTestUtils.setField(callback, "stubSecret", "expected-signature");
        when(messages.callbackReceived("STUB", "/pay/callback/stub", Map.of(), null)).thenReturn("PM-SYNTH-01");

        var body = new PayCallbackController.StubCallback("out-trade-marker", "transaction-marker", "signature-marker");
        assertThat(callback.stubPaid(body)).isEqualTo("FAIL");

        verify(messages).callbackSettled("PM-SYNTH-01", PayMessagePort.REJECTED, "验签失败", body.outTradeNo(), null,
                Map.of("outTradeNo", body.outTradeNo(), "transactionId", body.transactionId()));
        verifyNoInteractions(orders, settle);
        assertThat(logs.getAll()).contains("pay.stub.callback messageNo=PM-SYNTH-01 reasonCode=INVALID_SIGNATURE count=1")
                .doesNotContain("out-trade-marker", "transaction-marker", "signature-marker");
    }
}
