package ai.neargo.shop.pay.channel.invoke;

import ai.neargo.shop.pay.channel.api.ChannelResult;

/**
 * 观察单次调用（Observer）：日志、记录、指标挂在这上面，而不是散在管线代码里（渠道接入统一封装设计 §9.2）。
 * 每次「再发一次」都会先收到 {@link #attemptFinished} 再收到 {@link #attemptStarted} —— 调用记录据此重试每次一行。
 */
public interface InvocationListener {

    default void attemptStarted(Invocation inv) {
    }

    default void attemptFinished(Invocation inv, ChannelResult result) {
    }

    /** 整次调用的最终结果（重试、分类之后），在最后一次 {@link #attemptFinished} 之后 */
    default void invocationEnded(Invocation inv, ChannelResult result) {
    }
}
