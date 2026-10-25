package ai.neargo.shop.pay.channel.invoke;

import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelGateway;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.command.ChannelCommand;
import ai.neargo.shop.pay.channel.handler.ChannelHandler;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行器：解析处理器、建单次调用、走固定顺序的管线（渠道接入统一封装设计 §5.1）。
 * 管线在构造时排序并冻结；同一序号两个拦截器 → 构造失败（启动即炸，不在运行时撞上）。
 */
public final class ChannelInvoker implements ChannelGateway {

    private final ChannelHandlerRegistry registry;
    private final List<ChannelInterceptor> chain;
    private final List<InvocationListener> listeners;

    public ChannelInvoker(ChannelHandlerRegistry registry, List<ChannelInterceptor> interceptors,
                          List<InvocationListener> listeners) {
        this.registry = registry;
        List<ChannelInterceptor> sorted = new ArrayList<>(interceptors);
        sorted.sort(Comparator.comparingInt(ChannelInterceptor::order));
        Map<Integer, String> seen = new HashMap<>();
        for (ChannelInterceptor i : sorted) {
            String prev = seen.put(i.order(), i.getClass().getSimpleName());
            if (prev != null) {
                throw new IllegalStateException("渠道管线里两个拦截器同一序号 " + i.order() + "：" + prev
                        + " 与 " + i.getClass().getSimpleName() + " —— 顺序是设计的一部分，不能靠 Spring 注入顺序碰运气");
            }
        }
        this.chain = List.copyOf(sorted);
        this.listeners = List.copyOf(listeners);
    }

    @Override
    public ChannelResult execute(ChannelAccountContext account, ChannelCommand command) {
        ChannelKey key = new ChannelKey(account.market(), account.payChannel(), account.accessMode(),
                command.scene(), command.operation());
        ChannelHandler handler = registry.resolve(key);
        Invocation inv = Invocation.start(key, account, command);
        listeners.forEach(inv::addListener);
        inv.begin();
        ChannelResult result = new Chain(chain, handler, 0).proceed(inv);
        inv.end(result);
        return result;
    }

    /** 冻结后的管线（按序号），供 InterceptorOrderTest 与排障 */
    public List<ChannelInterceptor> interceptors() {
        return chain;
    }
}
