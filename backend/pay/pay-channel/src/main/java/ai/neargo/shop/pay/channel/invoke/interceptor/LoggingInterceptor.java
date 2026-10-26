package ai.neargo.shop.pay.channel.invoke.interceptor;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.invoke.Chain;
import ai.neargo.shop.pay.channel.invoke.ChannelInterceptor;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.pay.channel.log.PayLogger;

/** 序 50：经 {@link PayLogger} 一条开始、一条结束的结构化日志（渠道接入统一封装设计 §10） */
public final class LoggingInterceptor implements ChannelInterceptor {

    public static final int ORDER = 50;
    private final PayLogger logger;

    public LoggingInterceptor(PayLogger logger) {
        this.logger = logger;
    }

    @Override
    public ChannelResult intercept(Invocation inv, Chain next) {
        logger.invocationStarted(inv);
        ChannelResult result = next.proceed(inv);
        logger.invocationFinished(inv, result);
        return result;
    }

    @Override
    public int order() {
        return ORDER;
    }
}
