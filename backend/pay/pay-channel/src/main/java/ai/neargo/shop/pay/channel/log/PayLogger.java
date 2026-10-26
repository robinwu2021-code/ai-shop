package ai.neargo.shop.pay.channel.log;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.util.Map;

/**
 * 支付域唯一允许的业务日志入口（渠道接入统一封装设计 §10）。处理器、管线、回调、通知都经它，
 * 处理器里不直接 {@code log.info} 业务字段（架构守卫）。I2 先落渠道调用两个方法与 {@link #anomaly}；
 * 回调与通知的方法随 S7 / I5 加入。
 */
public interface PayLogger {

    void invocationStarted(Invocation inv);

    void invocationFinished(Invocation inv, ChannelResult result);

    /** 验签失败、金额不符、未登记渠道码…… */
    void anomaly(String type, Map<String, Object> fields);
}
