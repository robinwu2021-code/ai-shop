package ai.neargo.shop.pay.channel.api.command;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import java.util.Objects;

/** 查单：按我方收款单号查渠道侧的真实状态 */
public record QueryCommand(InvocationMeta meta, String outTradeNo) implements ChannelCommand {

    public QueryCommand {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(outTradeNo, "outTradeNo");
    }

    @Override
    public ChannelOperation operation() {
        return ChannelOperation.QUERY;
    }

    @Override
    public String outNo() {
        return outTradeNo;
    }
}
