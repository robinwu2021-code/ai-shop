package ai.neargo.shop.pay.channel.capability;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.command.OnboardingQueryCommand;
import ai.neargo.shop.pay.channel.api.command.OnboardingSubmitCommand;
import ai.neargo.shop.pay.channel.invoke.Invocation;

/** 进件（二级商户 / 子商户开户）。今天的 CN 进件仍走 {@code PayApplymentGateway}；W4 / I8 改接这里 */
public interface OnboardingCapability extends ChannelCapability {

    ChannelResult submitOnboarding(Invocation inv, OnboardingSubmitCommand command);

    ChannelResult queryOnboarding(Invocation inv, OnboardingQueryCommand command);
}
