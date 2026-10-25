package ai.neargo.shop.pay.channel.invoke.interceptor;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.entity.PayChannelCallLog;
import ai.neargo.shop.pay.channel.invoke.Chain;
import ai.neargo.shop.pay.channel.invoke.ChannelInterceptor;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.pay.channel.invoke.record.CallLogStore;
import java.util.Map;
import java.util.Optional;

/**
 * 序 70：同一渠道、同一操作、同一我方单号<b>已经明确成功过</b> → 直接返回记录里的结果，不再发（渠道接入统一封装设计 §5.2）。
 *
 * <p>护的是退款：上游重试、补查、人工重推都可能把同一笔退款再交下来一次。渠道按退款单号去重是渠道的事，
 * 我们不把「渠道会去重」当成重发的理由。只认<b>成功</b>：失败与不明照常走（失败可能是暂时的，不明要靠查单收尾）。
 * 纯读操作不拦 —— 查询本来就该每次都问。
 */
public final class IdempotencyInterceptor implements ChannelInterceptor {

    public static final int ORDER = 70;
    private final CallLogStore store;

    public IdempotencyInterceptor(CallLogStore store) {
        this.store = store;
    }

    @Override
    public ChannelResult intercept(Invocation inv, Chain next) {
        ChannelOperation op = inv.key().operation();
        if (op.readOnly() || inv.command().outNo() == null) {
            return next.proceed(inv);
        }
        Optional<PayChannelCallLog> done = store.lastSucceeded(inv.key().payChannel(), op.name(), inv.command().outNo());
        if (done.isPresent()) {
            PayChannelCallLog r = done.get();
            Money amount = r.getResponseAmountMinor() == null || r.getResponseCurrency() == null ? null
                    : new Money(r.getResponseAmountMinor(), r.getResponseCurrency());
            return new ChannelResult.Succeeded(r.getChannelTradeNo(), amount, r.getFeeMinor(),
                    Map.of("replayedFromCallLog", String.valueOf(r.getId())));
        }
        return next.proceed(inv);
    }

    @Override
    public int order() {
        return ORDER;
    }
}
