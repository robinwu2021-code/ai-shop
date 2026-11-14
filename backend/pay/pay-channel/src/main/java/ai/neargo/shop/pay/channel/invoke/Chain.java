package ai.neargo.shop.pay.channel.invoke;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.handler.ChannelHandler;
import java.util.List;

/** 责任链的游标：走完全部拦截器后交给处理器。每次 {@link #proceed} 都从当前位置往里走一步 */
public final class Chain {

    private final List<ChannelInterceptor> interceptors;
    private final ChannelHandler handler;
    private final int index;

    Chain(List<ChannelInterceptor> interceptors, ChannelHandler handler, int index) {
        this.interceptors = interceptors;
        this.handler = handler;
        this.index = index;
    }

    public ChannelResult proceed(Invocation inv) {
        if (index < interceptors.size()) {
            return interceptors.get(index).intercept(inv, new Chain(interceptors, handler, index + 1));
        }
        return handler.handle(inv, inv.command());
    }
}
