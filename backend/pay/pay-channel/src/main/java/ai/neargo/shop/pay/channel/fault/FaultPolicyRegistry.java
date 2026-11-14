package ai.neargo.shop.pay.channel.fault;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * 「渠道 × 操作」→ 容错策略（渠道接入统一封装设计 §8.1）。
 *
 * <p>默认值按操作定死在代码里：纯读可重试 3 次；收款只在请求没发出去时重发 1 次；退款、出款永不重发。
 * 配置（{@code shop.pay.channel.policy.<渠道>.<操作>.*}）<b>只能收紧</b> —— 更短的超时、更少的重试、更小的并发；
 * 想放宽、或者给不可重发的操作配上重试，构造时就抛：那是把「可能重复扣钱」写进了配置文件。
 */
public final class FaultPolicyRegistry {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private final Map<ChannelOperation, FaultPolicy> defaults = new EnumMap<>(ChannelOperation.class);
    private final Map<String, FaultPolicy> overrides = new HashMap<>();

    public FaultPolicyRegistry() {
        for (ChannelOperation op : ChannelOperation.values()) {
            defaults.put(op, defaultOf(op));
        }
    }

    static FaultPolicy defaultOf(ChannelOperation op) {
        return switch (op) {
            case QUERY, REFUND_QUERY, ONBOARDING_QUERY, STATEMENT_FETCH ->
                    new FaultPolicy(Duration.ofSeconds(10), 3, true, Duration.ofMillis(200), 32, 50f, 20, WAIT);
            // 同一 out_trade_no 渠道按单号去重；仍只在请求没发出去时重发 1 次（RetryInterceptor 按 requestSent 判）
            case PAY, CLOSE -> new FaultPolicy(Duration.ofSeconds(15), 1, false, Duration.ofMillis(200), 32, 50f, 20, WAIT);
            // 同步扣款与资金出账：重发 = 可能重复扣钱 / 重复出账
            case CONFIRM, VOID, REFUND, PAYOUT, SPLIT ->
                    new FaultPolicy(Duration.ofSeconds(20), 0, false, Duration.ZERO, 16, 50f, 20, WAIT);
            case ONBOARDING_SUBMIT, MEDIA_UPLOAD, KEY_VERIFY ->
                    new FaultPolicy(Duration.ofSeconds(30), 0, false, Duration.ZERO, 8, 50f, 20, WAIT);
        };
    }

    /**
     * 登记一条配置覆盖。只能比默认更严：超时不更长、重试不更多、并发不更大、不可重发的仍不可重发。
     *
     * @throws IllegalArgumentException 放宽了任何一项
     */
    public void override(String payChannel, ChannelOperation op, FaultPolicy tighter) {
        FaultPolicy base = defaults.get(op);
        if (tighter.readTimeout().compareTo(base.readTimeout()) > 0
                || tighter.maxRetries() > base.maxRetries()
                || (tighter.retryOnUncertain() && !base.retryOnUncertain())
                || tighter.maxConcurrent() > base.maxConcurrent()) {
            throw new IllegalArgumentException("渠道容错配置只能收紧：" + payChannel + "." + op
                    + " 默认 " + base.summary() + "，配置 " + tighter.summary()
                    + "。把不可重发的操作改成可重发 = 可能重复扣钱");
        }
        overrides.put(payChannel + "." + op, tighter);
    }

    public FaultPolicy policyFor(String payChannel, ChannelOperation op) {
        return overrides.getOrDefault(payChannel + "." + op, defaults.get(op));
    }
}
