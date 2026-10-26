package ai.neargo.shop.pay.channel.invoke.interceptor;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.fault.FaultPolicyRegistry;
import ai.neargo.shop.pay.channel.invoke.Chain;
import ai.neargo.shop.pay.channel.invoke.ChannelInterceptor;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import java.time.Duration;

/**
 * 序 100：每个渠道独立的并发上限（渠道接入统一封装设计 §8.3）。PayBy 变慢不拖垮蓝新：
 * 满了立刻 {@code Failed(CHANNEL_BUSY, retryable)}，不排队等 —— 排队等就是把一个渠道的慢传染给调用方的线程池。
 */
public final class BulkheadInterceptor implements ChannelInterceptor {

    public static final int ORDER = 100;
    private final BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
    private final FaultPolicyRegistry policies;

    public BulkheadInterceptor(FaultPolicyRegistry policies) {
        this.policies = policies;
    }

    @Override
    public ChannelResult intercept(Invocation inv, Chain next) {
        int limit = policies.policyFor(inv.key().payChannel(), inv.key().operation()).maxConcurrent();
        Bulkhead bulkhead = bulkheads.bulkhead(inv.key().payChannel(), () -> BulkheadConfig.custom()
                .maxConcurrentCalls(limit).maxWaitDuration(Duration.ZERO).build());
        if (!bulkhead.tryAcquirePermission()) {
            return new ChannelResult.Failed(PayErrorCode.CHANNEL_BUSY, null,
                    inv.key().payChannel() + " 在途调用已达上限 " + limit, true);
        }
        try {
            return next.proceed(inv);
        } finally {
            bulkhead.onComplete();
        }
    }

    @Override
    public int order() {
        return ORDER;
    }
}
