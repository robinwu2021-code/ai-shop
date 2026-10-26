package ai.neargo.shop.pay.channel.invoke.interceptor;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.fault.FaultPolicy;
import ai.neargo.shop.pay.channel.fault.FaultPolicyRegistry;
import ai.neargo.shop.pay.channel.invoke.Chain;
import ai.neargo.shop.pay.channel.invoke.ChannelInterceptor;
import ai.neargo.shop.pay.channel.invoke.Invocation;

/**
 * 序 110：只对<b>可安全重发</b>的情况重发（渠道接入统一封装设计 §8.1）。两种：
 * <ol>
 *   <li>请求根本没发出去（{@code CHANNEL_CONNECT_FAILED} 且没 {@code markSent}）—— 渠道一定没收到，任何操作都可以再发；
 *       但次数仍受策略约束，退款 / 刷卡 / 出款的策略是 0 次；</li>
 *   <li>纯读操作（查单、查退款…）结果不明或可重试失败。</li>
 * </ol>
 * 发出去之后的任何不明，对写操作一律不重发：交给补查。每次再发前通知监听者，调用记录重试每次一行。
 */
public final class RetryInterceptor implements ChannelInterceptor {

    public static final int ORDER = 110;
    private final FaultPolicyRegistry policies;

    public RetryInterceptor(FaultPolicyRegistry policies) {
        this.policies = policies;
    }

    @Override
    public ChannelResult intercept(Invocation inv, Chain next) {
        FaultPolicy policy = policies.policyFor(inv.key().payChannel(), inv.key().operation());
        inv.faultPolicySummary(policy.summary());
        ChannelResult result = next.proceed(inv);
        int retries = 0;
        while (retries < policy.maxRetries() && shouldRetry(inv, policy, result)) {
            retries++;
            sleep(policy);
            inv.nextAttempt(result);
            result = next.proceed(inv);
        }
        return result;
    }

    static boolean shouldRetry(Invocation inv, FaultPolicy policy, ChannelResult result) {
        boolean neverLeft = result instanceof ChannelResult.Failed f
                && f.code() == PayErrorCode.CHANNEL_CONNECT_FAILED && !inv.requestSent();
        if (neverLeft) {
            return true;
        }
        if (!policy.retryOnUncertain()) {
            return false;
        }
        return result instanceof ChannelResult.Unknown
                || (result instanceof ChannelResult.Failed f && f.retryable());
    }

    private static void sleep(FaultPolicy policy) {
        if (policy.backoff().isZero()) {
            return;
        }
        try {
            Thread.sleep(policy.backoff().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public int order() {
        return ORDER;
    }
}
