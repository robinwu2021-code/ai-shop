package ai.neargo.shop.paybridge.port;

import ai.neargo.shop.pay.channel.ChannelMessageRecorder;
import ai.neargo.shop.spi.pay.PayMessagePort;
import org.springframework.stereotype.Component;

import java.util.Map;

/** {@link PayMessagePort} 的实现：直接转给支付域的 {@link ChannelMessageRecorder} */
@Component
public class PayMessagePortImpl implements PayMessagePort {

    private final ChannelMessageRecorder recorder;

    public PayMessagePortImpl(ChannelMessageRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public String callbackReceived(String payChannel, String api,
                                   Map<String, String> headers, String rawBody) {
        return recorder.received(payChannel, api, headers, rawBody);
    }

    @Override
    public void callbackSettled(String messageNo, String outcome, String reason,
                                String bizNo, String paymentNo, Map<String, ?> payload) {
        recorder.settle(messageNo, outcome, reason, bizNo, paymentNo, payload);
    }
}
