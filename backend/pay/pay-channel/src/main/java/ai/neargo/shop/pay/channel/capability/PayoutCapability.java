package ai.neargo.shop.pay.channel.capability;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.command.PayoutCommand;
import ai.neargo.shop.pay.channel.invoke.Invocation;

/** 归集路径向商户出款（PayBy AGGREGATED，I8）。资金出账，<b>永不重发</b> */
public interface PayoutCapability extends ChannelCapability {

    ChannelResult payout(Invocation inv, PayoutCommand command);
}
