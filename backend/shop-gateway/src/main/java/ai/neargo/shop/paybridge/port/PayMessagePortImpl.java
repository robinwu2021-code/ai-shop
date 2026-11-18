package ai.neargo.shop.paybridge.port;

import ai.neargo.shop.pay.channel.ChannelMessageRecorder;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.PayMessagePort;
import org.springframework.stereotype.Component;

import java.util.Map;

/** {@link PayMessagePort} 的实现：直接转给支付域的 {@link ChannelMessageRecorder} */
@Component
public class PayMessagePortImpl implements PayMessagePort {

    private final ChannelMessageRecorder recorder;
    private final PaymentLedgerService ledger;

    public PayMessagePortImpl(ChannelMessageRecorder recorder,
                              PaymentLedgerService ledger) {
        this.recorder = recorder;
        this.ledger = ledger;
    }

    @Override
    public String callbackReceived(String payChannel, String api,
                                   Map<String, String> headers, String rawBody) {
        return recorder.received(payChannel, api, headers, rawBody);
    }

    @Override
    public void callbackSettled(String messageNo, String outcome, String reason,
                                String bizNo, String paymentNo, Map<String, ?> payload) {
        /*
         * 调用方（回调控制器）只知道商户单号，十几处都传 paymentNo = null —— 「按支付流水查报文」于是永远查不到
         * （F13 · Review R15）。在这里按商户单号补上，查不到也照样落：报文先落是硬规矩，流水号缺了只是少一个检索维度。
         */
        String resolved = paymentNo != null && !paymentNo.isBlank() ? paymentNo
                : bizNo == null ? null : ledger.paymentNoOf(bizNo).orElse(null);
        recorder.settle(messageNo, outcome, reason, bizNo, resolved, payload);
    }
}
