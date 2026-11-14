package ai.neargo.shop.pay.channel.invoke.interceptor;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.fault.FaultPolicyRegistry;
import ai.neargo.shop.pay.channel.invoke.Chain;
import ai.neargo.shop.pay.channel.invoke.ChannelInterceptor;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.MDC;

/**
 * 序 120：按「渠道 × 操作」的超时上限等结果，超了按 {@code Unknown} 返回（渠道接入统一封装设计 §8.1）。
 *
 * <p>超时<b>不是失败</b>：请求可能已经在渠道那边生效。处理器那条线程不强行打断（打断不等于撤回已发出的请求），
 * 由补查去问清楚。用虚拟线程跑处理器：一个渠道卡住几百个调用也不占平台线程。
 */
public final class TimeoutInterceptor implements ChannelInterceptor {

    public static final int ORDER = 120;
    private final FaultPolicyRegistry policies;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public TimeoutInterceptor(FaultPolicyRegistry policies) {
        this.policies = policies;
    }

    @Override
    public ChannelResult intercept(Invocation inv, Chain next) {
        Duration timeout = policies.policyFor(inv.key().payChannel(), inv.key().operation()).readTimeout();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Future<ChannelResult> f = executor.submit(() -> {
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                return next.proceed(inv);
            } finally {
                MDC.clear();
            }
        });
        try {
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new ChannelResult.Unknown("TIMEOUT: " + timeout.toMillis() + "ms 内渠道没有答复");
        } catch (ExecutionException e) {
            // 分类器在更内层，按理不会漏出异常；漏出来就是意料之外，仍按不明处理
            return new ChannelResult.Unknown("UNEXPECTED: " + e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ChannelResult.Unknown("INTERRUPTED");
        }
    }

    @Override
    public int order() {
        return ORDER;
    }
}
