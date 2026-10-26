package ai.neargo.shop.paybridge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.pay.channel.ChannelMessageRecorder;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.paybridge.port.PayMessagePortImpl;
import ai.neargo.shop.spi.pay.PayMessagePort;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F13 · Review R15：回调报文落库时带上流水号（TDD-支付服务-I1存量缺陷）。
 *
 * <p>回调控制器十几处调用都传 {@code paymentNo = null}（它只知道商户单号），于是「按支付流水查报文」永远查不到。
 * 收口在这里：调用方没给就按商户单号补，控制器不必各处去查。
 */
class PayMessagePortImplTest {

    @Test
    @DisplayName("★★ F13 没给 payment_no 时按商户单号补上 —— 否则按流水查报文永远查不到")
    void fillsPaymentNoFromOutTradeNo() {
        var recorder = mock(ChannelMessageRecorder.class);
        var ledger = mock(PaymentLedgerService.class);
        when(ledger.paymentNoOf("ORD-1")).thenReturn(Optional.of("PY-1"));
        var port = new PayMessagePortImpl(recorder, ledger);

        port.callbackSettled("MSG-1", PayMessagePort.ACCEPTED, null, "ORD-1", null, Map.of("k", "v"));

        verify(recorder).settle(eq("MSG-1"), eq(PayMessagePort.ACCEPTED), any(), eq("ORD-1"), eq("PY-1"), any());
    }

    @Test
    @DisplayName("★ 调用方给了就用给的；商户单号查不到也照样落（流水号留空）—— 报文先落是硬规矩")
    void explicitWinsAndUnknownStillRecords() {
        var recorder = mock(ChannelMessageRecorder.class);
        var ledger = mock(PaymentLedgerService.class);
        when(ledger.paymentNoOf(any())).thenReturn(Optional.empty());
        var port = new PayMessagePortImpl(recorder, ledger);

        port.callbackSettled("MSG-2", PayMessagePort.ACCEPTED, null, "ORD-2", "PY-EXPLICIT", Map.of());
        port.callbackSettled("MSG-3", PayMessagePort.REJECTED, "验签失败", null, null, null);

        verify(recorder).settle(eq("MSG-2"), any(), any(), eq("ORD-2"), eq("PY-EXPLICIT"), any());
        verify(recorder).settle(eq("MSG-3"), any(), eq("验签失败"), eq(null), eq(null), eq(null));
    }
}
