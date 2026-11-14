package ai.neargo.shop.pay.channel.invoke.interceptor;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.invoke.Chain;
import ai.neargo.shop.pay.channel.invoke.ChannelInterceptor;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.util.Map;
import org.slf4j.MDC;

/** 序 10：把追踪号、渠道、操作、地区放进 MDC，出管线时还原（渠道接入统一封装设计 §5.2、§10） */
public final class TraceInterceptor implements ChannelInterceptor {

    public static final int ORDER = 10;

    @Override
    public ChannelResult intercept(Invocation inv, Chain next) {
        Map<String, String> before = MDC.getCopyOfContextMap();
        try {
            MDC.put("traceId", inv.command().meta().traceId());
            MDC.put("channel", inv.key().payChannel());
            MDC.put("operation", inv.key().operation().name());
            if (inv.key().market() != null) {
                MDC.put("market", inv.key().market());
            }
            return next.proceed(inv);
        } finally {
            if (before == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(before);
            }
        }
    }

    @Override
    public int order() {
        return ORDER;
    }
}
