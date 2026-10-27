package ai.neargo.shop.pay.channel.api.command;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.Money;
import java.util.Objects;

/**
 * 服务商模式下的资金动作：补差、补差回退、分账、分账回退。<b>永不重发</b>（同退款）。
 *
 * <p>时序是硬约束，调用方负责：支付 → 补差 → 分账；退款反着来：分账回退 → 补差回退 → 退款。
 *
 * @param channelTradeNo   渠道交易号（补差 / 分账）或渠道侧原单号（回退：补差单号 / 分账单号）
 * @param subMerchantId    二级商户号（收付通 {@code sub_mchid}、直付通收款方）
 * @param requestNo        我方幂等号，重试用同一个
 */
public record SplitCommand(InvocationMeta meta, Kind kind, String subMerchantId, String channelTradeNo,
                           String originOutTradeNo, Money orderAmount, Money amount, String requestNo,
                           String description) implements ChannelCommand {

    public enum Kind { SUBSIDY, SUBSIDY_RETURN, SPLIT, SPLIT_REVERSE }

    public SplitCommand {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(requestNo, "requestNo");
    }

    @Override
    public ChannelOperation operation() {
        return ChannelOperation.SPLIT;
    }

    @Override
    public Money requestAmount() {
        return amount;
    }

    @Override
    public String outNo() {
        return requestNo;
    }
}
