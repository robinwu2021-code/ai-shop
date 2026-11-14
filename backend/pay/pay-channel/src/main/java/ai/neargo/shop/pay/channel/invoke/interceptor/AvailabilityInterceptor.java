package ai.neargo.shop.pay.channel.invoke.interceptor;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.invoke.Chain;
import ai.neargo.shop.pay.channel.invoke.ChannelInterceptor;
import ai.neargo.shop.pay.channel.invoke.Invocation;

/**
 * 序 30：运营开关。<b>只拦新收款</b> —— 产品停用后，已经收到的钱仍要能查、能退、能关单，
 * 否则停用一个产品就把历史订单的退款一起冻住了（渠道接入统一封装设计 §5.2）。
 * 适配器生命周期（DEGRADED / SUSPENDED）随 S6 接入这里。
 */
public final class AvailabilityInterceptor implements ChannelInterceptor {

    public static final int ORDER = 30;
    private final ChannelProductCatalog catalog;

    public AvailabilityInterceptor(ChannelProductCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public ChannelResult intercept(Invocation inv, Chain next) {
        if (inv.key().operation() == ChannelOperation.PAY && !catalog.payEnabled(inv.key())) {
            return new ChannelResult.Failed(PayErrorCode.CHANNEL_DISABLED, null, "支付产品未开通：" + inv.key(), false);
        }
        return next.proceed(inv);
    }

    @Override
    public int order() {
        return ORDER;
    }
}
