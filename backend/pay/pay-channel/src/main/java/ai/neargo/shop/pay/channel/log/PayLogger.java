package ai.neargo.shop.pay.channel.log;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.invoke.Invocation;

/**
 * 支付域唯一允许的业务日志入口（渠道接入统一封装设计 §10）。处理器、管线、回调、通知都经它，
 * 处理器里不直接 {@code log.info} 业务字段（架构守卫）。渠道调用由管线记录开始与结束。
 */
public interface PayLogger {

    void invocationStarted(Invocation inv);

    void invocationFinished(Invocation inv, ChannelResult result);

}
