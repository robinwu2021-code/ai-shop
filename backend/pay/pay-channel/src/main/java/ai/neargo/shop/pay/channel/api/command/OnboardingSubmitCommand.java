package ai.neargo.shop.pay.channel.api.command;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import java.util.Map;
import java.util.Objects;

/**
 * 进件提交（W4 / I8 接入；今天的进件仍走 {@code PayApplymentGateway}）。
 *
 * @param application 渠道无关的进件资料（主体、结算账户、联系人…），由处理器翻成渠道报文
 */
public record OnboardingSubmitCommand(InvocationMeta meta, String applyNo, Map<String, Object> application)
        implements ChannelCommand {

    public OnboardingSubmitCommand {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(applyNo, "applyNo");
        application = application == null ? Map.of() : Map.copyOf(application);
    }

    @Override
    public ChannelOperation operation() {
        return ChannelOperation.ONBOARDING_SUBMIT;
    }

    @Override
    public String outNo() {
        return applyNo;
    }
}
