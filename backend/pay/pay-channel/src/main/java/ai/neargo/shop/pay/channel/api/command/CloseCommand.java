package ai.neargo.shop.pay.channel.api.command;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import java.util.Objects;

/** 关单：渠道侧未支付的单关掉，之后该单号不能再付 */
public record CloseCommand(InvocationMeta meta, String outTradeNo) implements ChannelCommand {

    public CloseCommand {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(outTradeNo, "outTradeNo");
    }

    @Override
    public ChannelOperation operation() {
        return ChannelOperation.CLOSE;
    }

    @Override
    public String outNo() {
        return outTradeNo;
    }
}
