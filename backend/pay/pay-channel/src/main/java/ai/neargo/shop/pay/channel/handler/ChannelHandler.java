package ai.neargo.shop.pay.channel.handler;

import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.command.ChannelCommand;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.util.Set;

/**
 * 一个「渠道 × 接入模式 × 场景 × 操作」的实现（Strategy，渠道接入统一封装设计 §6）。
 *
 * <p>处理器<b>不重试、不写库、不打业务日志</b>：那些都在管线里统一做（架构守卫）。
 * 发送出了问题抛 {@code ChannelTransportException}，由管线归入四态；处理器自己不判断「这算失败还是未知」。
 */
public interface ChannelHandler {

    Set<ChannelKey.Pattern> supports();

    ChannelResult handle(Invocation inv, ChannelCommand command);
}
