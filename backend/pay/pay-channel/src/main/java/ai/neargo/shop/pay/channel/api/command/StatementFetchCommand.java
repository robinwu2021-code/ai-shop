package ai.neargo.shop.pay.channel.api.command;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 拉对账单（I6 接入）。按收款商户的身份拉，商户坐标在 {@code ChannelAccountContext} 里。
 *
 * @param billType 渠道无关的账单种类：{@code TRADE} 交易 / {@code FUND} 资金
 */
public record StatementFetchCommand(InvocationMeta meta, LocalDate billDate, String billType)
        implements ChannelCommand {

    public StatementFetchCommand {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(billDate, "billDate");
        Objects.requireNonNull(billType, "billType");
    }

    @Override
    public ChannelOperation operation() {
        return ChannelOperation.STATEMENT_FETCH;
    }

    @Override
    public String outNo() {
        return billType + ":" + billDate;
    }
}
