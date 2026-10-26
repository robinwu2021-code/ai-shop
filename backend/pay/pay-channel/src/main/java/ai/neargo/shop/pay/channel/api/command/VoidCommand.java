package ai.neargo.shop.pay.channel.api.command;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.Money;
import java.util.Objects;

/** 刷卡当日撤销（渠道接入统一封装设计 §8.1）。<b>永不重发</b>：结果不明只查不发 */
public record VoidCommand(InvocationMeta meta, String outTradeNo, Money amount) implements ChannelCommand {

    public VoidCommand {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(outTradeNo, "outTradeNo");
        Objects.requireNonNull(amount, "amount");
    }

    @Override
    public ChannelOperation operation() {
        return ChannelOperation.VOID;
    }

    @Override
    public Money requestAmount() {
        return amount;
    }

    @Override
    public String outNo() {
        return outTradeNo;
    }
}
