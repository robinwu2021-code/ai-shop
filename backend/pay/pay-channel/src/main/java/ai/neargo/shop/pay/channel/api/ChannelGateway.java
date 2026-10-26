package ai.neargo.shop.pay.channel.api;

import ai.neargo.shop.pay.channel.api.command.ChannelCommand;

/**
 * 渠道门面（Facade）：任何命令进来，四态结果出去（渠道接入统一封装设计 §3）。
 * 应用服务<b>只依赖这一层</b>；渠道差异只允许出现在处理器与协议适配两层。
 */
public interface ChannelGateway {

    ChannelResult execute(ChannelAccountContext account, ChannelCommand command);
}
