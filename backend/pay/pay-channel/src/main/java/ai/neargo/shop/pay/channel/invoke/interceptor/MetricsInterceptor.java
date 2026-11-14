package ai.neargo.shop.pay.channel.invoke.interceptor;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.invoke.Chain;
import ai.neargo.shop.pay.channel.invoke.ChannelInterceptor;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 序 60：按「渠道 × 操作 × 结果」计数与计时（渠道接入统一封装设计 §5.2；TDD-支付服务-W2渠道层补完 S7，补 I2 偏差 0）。
 *
 * <p>在记录与日志之后、幂等与限速之前：被幂等拦下、在限速里排队的那一次也算一次调用，耗时含排队与重试 ——
 * 这里量的是「业务等了多久」，那正是告警要的；渠道自身的耗时在调用记录的 {@code elapsed_ms} 里。
 * 标签只有渠道、操作、结果三个低基数维度：单号、商户不进标签（基数爆炸）。
 */
public final class MetricsInterceptor implements ChannelInterceptor {

    public static final int ORDER = 60;
    static final String CALLS = "pay.channel.calls";
    static final String LATENCY = "pay.channel.latency";

    private final MeterRegistry registry;

    public MetricsInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ChannelResult intercept(Invocation inv, Chain next) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "EXCEPTION";
        try {
            ChannelResult result = next.proceed(inv);
            outcome = result.outcomeClass();
            return result;
        } finally {
            String channel = inv.key().payChannel();
            String operation = inv.key().operation().name();
            sample.stop(Timer.builder(LATENCY).description("渠道调用耗时（含重试、排队）")
                    .tag("channel", channel).tag("operation", operation).register(registry));
            Counter.builder(CALLS).description("渠道调用次数，按四态结果")
                    .tag("channel", channel).tag("operation", operation).tag("outcome", outcome)
                    .register(registry).increment();
        }
    }

    @Override
    public int order() {
        return ORDER;
    }
}
