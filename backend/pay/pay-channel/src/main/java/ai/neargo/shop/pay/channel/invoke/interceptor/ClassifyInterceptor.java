package ai.neargo.shop.pay.channel.invoke.interceptor;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.fault.OutcomeClassifier;
import ai.neargo.shop.pay.channel.invoke.Chain;
import ai.neargo.shop.pay.channel.invoke.ChannelInterceptor;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.pay.channel.invoke.InvocationState;

/**
 * 序 130，<b>最内层</b>：处理器抛出的任何东西都在这里归入四态（渠道接入统一封装设计 §8.2）。
 * 放在最内层是因为重试、熔断、记录都要看的是<b>分好类的结果</b>，而不是裸异常。
 * 「未知」登记补查随 S5 接入。
 */
public final class ClassifyInterceptor implements ChannelInterceptor {

    public static final int ORDER = 130;
    private final OutcomeClassifier classifier;

    public ClassifyInterceptor(OutcomeClassifier classifier) {
        this.classifier = classifier;
    }

    @Override
    public ChannelResult intercept(Invocation inv, Chain next) {
        ChannelResult result;
        try {
            result = next.proceed(inv);
        } catch (RuntimeException e) {
            result = classifier.classify(inv.key().payChannel(), inv.key().operation(), e);
        }
        inv.state(InvocationState.CLASSIFIED);
        return result;
    }

    @Override
    public int order() {
        return ORDER;
    }
}
