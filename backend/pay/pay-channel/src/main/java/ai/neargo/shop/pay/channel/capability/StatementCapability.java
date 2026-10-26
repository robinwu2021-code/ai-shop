package ai.neargo.shop.pay.channel.capability;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.command.StatementFetchCommand;
import ai.neargo.shop.pay.channel.invoke.Invocation;

/**
 * 对账单下载与解析（I6 {@code StatementFetchService} 按收款商户扇出调它）。没有对账单接口的渠道不实现，
 * 对账退化为逐笔查单（{@code sys_pay_channel.supports_statement}）。
 */
public interface StatementCapability extends ChannelCapability {

    /** {@code Succeeded.extra} 里给原件的 {@code pay_raw_payload} 引用与行数；解析后的明细由 I6 落表 */
    ChannelResult fetchStatement(Invocation inv, StatementFetchCommand command);
}
