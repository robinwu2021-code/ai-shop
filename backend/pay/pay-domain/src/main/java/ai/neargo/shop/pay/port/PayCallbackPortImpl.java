package ai.neargo.shop.pay.port;

import ai.neargo.shop.pay.callback.CallbackTemplate;
import ai.neargo.shop.spi.pay.PayCallbackPort;

/** {@link PayCallbackPort} 的实现：转给支付域的回调模板（装配见 {@code CallbackConfiguration}） */
public class PayCallbackPortImpl implements PayCallbackPort {

    private final CallbackTemplate template;

    public PayCallbackPortImpl(CallbackTemplate template) {
        this.template = template;
    }

    @Override
    public Verdict accept(Envelope envelope) {
        return template.accept(envelope);
    }

    @Override
    public void handled(Verdict verdict, boolean applied, String reason) {
        template.handled(verdict, applied, reason);
    }
}
