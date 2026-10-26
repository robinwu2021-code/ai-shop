package ai.neargo.shop.pay.channel.invoke;

import ai.neargo.shop.pay.channel.api.ChannelResult;

/**
 * 管线上的一环（Chain of Responsibility，渠道接入统一封装设计 §5.2）。顺序由 {@link #order()} 定死，
 * 执行器启动时校验：同一序号两个 → 启动失败。
 */
public interface ChannelInterceptor {

    ChannelResult intercept(Invocation inv, Chain next);

    int order();
}
