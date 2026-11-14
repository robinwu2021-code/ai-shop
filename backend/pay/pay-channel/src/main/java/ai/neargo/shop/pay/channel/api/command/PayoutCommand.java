package ai.neargo.shop.pay.channel.api.command;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.Money;
import java.util.Objects;

/** 归集路径（AGGREGATED）向商户出款（I8 PayBy 接入）。<b>永不重发</b>：资金出账 */
public record PayoutCommand(InvocationMeta meta, String outPayoutNo, Money amount, String payeeAccount,
                            String remark) implements ChannelCommand {

    public PayoutCommand {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(outPayoutNo, "outPayoutNo");
        Objects.requireNonNull(amount, "amount");
    }

    @Override
    public ChannelOperation operation() {
        return ChannelOperation.PAYOUT;
    }

    @Override
    public Money requestAmount() {
        return amount;
    }

    @Override
    public String outNo() {
        return outPayoutNo;
    }
}
