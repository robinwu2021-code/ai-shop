package ai.neargo.shop.pay.channel.api.command;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import java.util.Objects;

/** 进件结果查询。与提交是两个接口：进件是异步的，提交只拿到申请单号 */
public record OnboardingQueryCommand(InvocationMeta meta, String applyNo, String channelApplyNo)
        implements ChannelCommand {

    public OnboardingQueryCommand {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(applyNo, "applyNo");
    }

    @Override
    public ChannelOperation operation() {
        return ChannelOperation.ONBOARDING_QUERY;
    }

    @Override
    public String outNo() {
        return applyNo;
    }
}
