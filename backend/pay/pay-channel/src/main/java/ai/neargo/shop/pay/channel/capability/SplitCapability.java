package ai.neargo.shop.pay.channel.capability;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.command.SplitCommand;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.util.Set;

/**
 * 服务商模式的资金动作：补差、分账及其回退（微信收付通、支付宝直付通）。直连 / 归集路径的渠道<b>不实现</b>它 ——
 * 让「直连商户号去分账」在路由这一步就明确失败，而不是发出去等通道报 NO_AUTH，或更糟，返回一个假成功。
 */
public interface SplitCapability extends ChannelCapability {

    Set<SplitCommand.Kind> splitKinds();

    ChannelResult split(Invocation inv, SplitCommand command);
}
