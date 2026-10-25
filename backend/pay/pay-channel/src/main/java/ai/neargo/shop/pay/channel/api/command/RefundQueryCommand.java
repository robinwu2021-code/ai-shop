package ai.neargo.shop.pay.channel.api.command;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import java.util.Objects;

/** 查退款。与查单是两套单据，不能拿退款单号去查收款接口 */
public record RefundQueryCommand(InvocationMeta meta, String outRefundNo, String originOutTradeNo)
        implements ChannelCommand {

    public RefundQueryCommand {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(outRefundNo, "outRefundNo");
    }

    @Override
    public ChannelOperation operation() {
        return ChannelOperation.REFUND_QUERY;
    }

    @Override
    public String outNo() {
        return outRefundNo;
    }
}
