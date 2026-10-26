package ai.neargo.shop.pay.channel.capability;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.command.PayCommand;
import ai.neargo.shop.pay.channel.api.command.VoidCommand;
import ai.neargo.shop.pay.channel.invoke.Invocation;

/** 刷卡（{@code IN_STORE_CARD}）与当日撤销。同步扣款，两者都<b>永不重发</b>。I7 / I8 接入 */
public interface CardPayCapability extends ChannelCapability {

    ChannelResult payByCard(Invocation inv, PayCommand command);

    ChannelResult voidCard(Invocation inv, VoidCommand command);
}
