package ai.neargo.shop.pay.api.guard.internal.port;

import ai.neargo.shop.pay.access.PayAction;
import ai.neargo.shop.pay.api.guard.internal.InternalCallRecorder;
import ai.neargo.shop.pay.api.guard.internal.InternalCallRecorder.Call;
import ai.neargo.shop.pay.client.port.RemotePaymentPort;
import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSources;
import ai.neargo.shop.spi.pay.PaymentPort;

/**
 * {@link PaymentPort} 的留痕装饰器（内嵌形态；ADR-045 G9）：每个方法经 {@link InternalCallRecorder} 写一行，再转调真实现。
 * 由宿主在装配内嵌 {@code PaymentPortImpl} 时包上（{@code shop.pay.guard.internal.record-port-calls}，默认开）；
 * 独立形态的 {@code pay-svc} 不包 —— 那边由内部入口的守卫管线记 {@code INTERNAL_HTTP}，再包一层就记两行。
 */
public class RecordingPaymentPort implements PaymentPort {

    private final PaymentPort delegate;
    private final InternalCallRecorder recorder;

    public RecordingPaymentPort(PaymentPort delegate, InternalCallRecorder recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    @Override
    public PaymentHandle createPayment(InternalCreatePaymentCmd c) {
        return recorder.record(new Call("PaymentPort#createPayment", PayAction.CREATE_ONLINE.name(), app(c.bizType()), c.bizType(),
                c.outOrderNo(), c.storeId(), c.amountMinor(), c.currency(), null, sourceOf(c.source())),
                () -> delegate.createPayment(c), PaymentHandle::payOrderId);
    }

    @Override
    public PaymentHandle queryPayment(String bizType, String payOrderNo, CallSource source) {
        return recorder.record(new Call("PaymentPort#queryPayment", PayAction.QUERY.name(), app(bizType), bizType, payOrderNo, null,
                null, null, null, sourceOf(source)),
                () -> delegate.queryPayment(bizType, payOrderNo, source), PaymentHandle::payOrderId);
    }

    @Override
    public PaymentHandle closePayment(String bizType, String outOrderNo, String reason, CallSource source) {
        return recorder.record(new Call("PaymentPort#closePayment", PayAction.CLOSE.name(), app(bizType), bizType, outOrderNo, null,
                null, null, null, sourceOf(source)),
                () -> delegate.closePayment(bizType, outOrderNo, reason, source), PaymentHandle::payOrderId);
    }

    @Override
    public RefundHandle requestRefund(InternalRefundCmd c) {
        return recorder.record(new Call("PaymentPort#requestRefund", PayAction.REQUEST_REFUND.name(), app(c.bizType()), c.bizType(),
                c.outRefundNo(), null, c.amountMinor(), null, c.operatorNo(), sourceOf(c.source())),
                () -> delegate.requestRefund(c), h -> null);
    }

    private static String app(String bizType) {
        return RemotePaymentPort.appOf(bizType);
    }

    private static CallSource sourceOf(CallSource s) {
        return s != null ? s : CallSources.current();
    }
}
