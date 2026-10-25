package ai.neargo.shop.pay.channel.api.command;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.Money;
import java.util.Objects;

/** 退款。<b>永不重发</b>：结果不明只查不发（渠道接入统一封装设计 §8.1） */
public record RefundCommand(InvocationMeta meta, String originOutTradeNo, String originChannelTradeNo,
                            Money originAmount, Money refundAmount, String outRefundNo, String reason)
        implements ChannelCommand {

    public RefundCommand {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(originOutTradeNo, "originOutTradeNo");
        Objects.requireNonNull(originAmount, "originAmount");
        Objects.requireNonNull(refundAmount, "refundAmount");
        Objects.requireNonNull(outRefundNo, "outRefundNo");
    }

    @Override
    public ChannelOperation operation() {
        return ChannelOperation.REFUND;
    }

    @Override
    public Money requestAmount() {
        return refundAmount;
    }

    @Override
    public String outNo() {
        return outRefundNo;
    }
}
