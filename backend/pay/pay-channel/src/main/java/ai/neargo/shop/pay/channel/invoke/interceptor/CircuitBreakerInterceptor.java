package ai.neargo.shop.pay.channel.invoke.interceptor;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.fault.FaultPolicy;
import ai.neargo.shop.pay.channel.fault.FaultPolicyRegistry;
import ai.neargo.shop.pay.channel.invoke.Chain;
import ai.neargo.shop.pay.channel.invoke.ChannelInterceptor;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.concurrent.TimeUnit;

/**
 * 序 90：按<b>（渠道 × 收款商户）</b>熔断（渠道接入统一封装设计 §8.3）。一个商户的密钥失效不该让整个渠道熔断；
 * 整个渠道故障时，各商户的熔断器会各自打开。
 *
 * <p>计入失败的只有「渠道那边出了问题」：{@code Unknown} 与可重试的 {@code Failed}；参数错、业务拒绝不算。
 * 打开时<b>查询类照样放行</b> —— 结果不明的单还得靠查单收尾，熔断不能把补查也挡掉。
 */
public final class CircuitBreakerInterceptor implements ChannelInterceptor {

    public static final int ORDER = 90;
    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
    private final FaultPolicyRegistry policies;

    public CircuitBreakerInterceptor(FaultPolicyRegistry policies) {
        this.policies = policies;
    }

    @Override
    public ChannelResult intercept(Invocation inv, Chain next) {
        CircuitBreaker breaker = breakerOf(inv);
        inv.breakerState(breaker.getState().name());
        boolean readOnly = inv.key().operation().readOnly();
        if (!readOnly && !breaker.tryAcquirePermission()) {
            return new ChannelResult.Failed(PayErrorCode.CHANNEL_UNAVAILABLE, null,
                    "熔断打开：" + inv.key().payChannel() + " / 商户 " + inv.account().payMerchantId(), true);
        }
        long start = System.nanoTime();
        ChannelResult result = next.proceed(inv);
        long elapsed = System.nanoTime() - start;
        if (readOnly) {
            return result;
        }
        if (result instanceof ChannelResult.Unknown
                || (result instanceof ChannelResult.Failed f && f.retryable())) {
            breaker.onError(elapsed, TimeUnit.NANOSECONDS, new IllegalStateException(result.outcomeClass()));
        } else {
            breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
        }
        return result;
    }

    CircuitBreaker breakerOf(Invocation inv) {
        String name = inv.key().payChannel() + ":" + inv.account().payMerchantId();
        FaultPolicy p = policies.policyFor(inv.key().payChannel(), inv.key().operation());
        return breakers.circuitBreaker(name, () -> CircuitBreakerConfig.custom()
                .failureRateThreshold(p.failureRateThreshold())
                .minimumNumberOfCalls(p.minimumCalls())
                .slidingWindowSize(Math.max(p.minimumCalls(), 10))
                .waitDurationInOpenState(p.openWait())
                .build());
    }

    @Override
    public int order() {
        return ORDER;
    }
}
