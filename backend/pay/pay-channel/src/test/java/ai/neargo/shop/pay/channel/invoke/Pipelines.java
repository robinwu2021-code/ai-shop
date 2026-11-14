package ai.neargo.shop.pay.channel.invoke;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.api.SourceInfo;
import ai.neargo.shop.pay.channel.api.command.ChannelCommand;
import ai.neargo.shop.pay.channel.api.command.PayCommand;
import ai.neargo.shop.pay.channel.api.command.QueryCommand;
import ai.neargo.shop.pay.channel.api.command.RefundCommand;
import ai.neargo.shop.pay.channel.fault.ChannelErrorMapper;
import ai.neargo.shop.pay.channel.fault.FaultPolicyRegistry;
import ai.neargo.shop.pay.channel.fault.OutcomeClassifier;
import ai.neargo.shop.pay.channel.handler.ChannelHandler;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.invoke.interceptor.AvailabilityInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.BulkheadInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.CircuitBreakerInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.ClassifyInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.RetryInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.TimeoutInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.TraceInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.ValidationInterceptor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** 管线测试的装配：真拦截器 + 脚本化处理器 + 可控的产品目录 */
final class Pipelines {

    private Pipelines() {
    }

    /** 每次调用按脚本返回结果或抛异常，记下被调了几次 */
    static final class ScriptedHandler implements ChannelHandler {
        final AtomicInteger calls = new AtomicInteger();
        final Set<ChannelKey.Pattern> patterns;
        final Function<Integer, ChannelResult> script;

        ScriptedHandler(Set<ChannelKey.Pattern> patterns, Function<Integer, ChannelResult> script) {
            this.patterns = patterns;
            this.script = script;
        }

        @Override
        public Set<ChannelKey.Pattern> supports() {
            return patterns;
        }

        @Override
        public ChannelResult handle(Invocation inv, ChannelCommand command) {
            return script.apply(calls.incrementAndGet());
        }
    }

    static final class Catalog implements ChannelProductCatalog {
        final Set<ChannelKey> enabled;

        Catalog(Set<ChannelKey> enabled) {
            this.enabled = enabled;
        }

        @Override
        public Set<ChannelKey> enabledPayKeys() {
            return enabled;
        }

        @Override
        public boolean payEnabled(ChannelKey key) {
            return enabled.contains(key);
        }
    }

    static Set<ChannelKey.Pattern> all(String channel, AccessMode mode) {
        Set<ChannelKey.Pattern> out = new java.util.HashSet<>();
        for (ChannelOperation op : ChannelOperation.values()) {
            out.add(new ChannelKey.Pattern(null, channel, mode, PayScene.ANY, op));
        }
        return out;
    }

    static List<ChannelInterceptor> s2Interceptors(ChannelProductCatalog catalog, FaultPolicyRegistry policies,
                                                   List<ChannelErrorMapper> mappers) {
        return new ArrayList<>(List.of(new TraceInterceptor(), new ValidationInterceptor(),
                new AvailabilityInterceptor(catalog), new CircuitBreakerInterceptor(policies),
                new BulkheadInterceptor(policies), new RetryInterceptor(policies), new TimeoutInterceptor(policies),
                new ClassifyInterceptor(new OutcomeClassifier(mappers))));
    }

    static ChannelInvoker invoker(ChannelHandler handler, ChannelProductCatalog catalog, FaultPolicyRegistry policies) {
        List<ChannelInterceptor> chain = s2Interceptors(catalog, policies, List.of());
        Collections.shuffle(chain);
        return new ChannelInvoker(new ChannelHandlerRegistry(List.of(handler), catalog), chain, List.of());
    }

    static ChannelAccountContext wechat(long merchantId) {
        return new ChannelAccountContext("CN", "WECHAT", AccessMode.MERCHANT_OWN, merchantId, "PM-" + merchantId,
                null, "CNY", 2);
    }

    static ChannelKey payKey() {
        return new ChannelKey("CN", "WECHAT", AccessMode.MERCHANT_OWN, PayScene.ONLINE_SDK, ChannelOperation.PAY);
    }

    static InvocationMeta meta() {
        return new InvocationMeta(null, 1L, 2L, null, 9L, SourceInfo.system("MALL_ORDER", "OD1"));
    }

    static PayCommand pay(long minor, String currency) {
        return new PayCommand(meta(), PayScene.ONLINE_SDK, new Money(minor, currency), "goods", "OD1",
                Instant.now().plusSeconds(900), "openid-1", null);
    }

    static QueryCommand query() {
        return new QueryCommand(meta(), "OD1");
    }

    static RefundCommand refund(long refund, long origin) {
        return new RefundCommand(meta(), "OD1", "4200001", new Money(origin, "CNY"), new Money(refund, "CNY"),
                "OD1-R1", "七天无理由");
    }
}
