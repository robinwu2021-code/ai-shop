package ai.neargo.shop.pay.channel.capability;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.api.command.PayCommand;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.util.Set;

/**
 * 线上收款：用户在端上完成支付（SDK 唤起、跳转收银台、扫二维码）。结果多为 {@code Pending} + {@code PayInstruction}，
 * 成功以回调或查单为准。
 */
public interface OnlinePayCapability extends ChannelCapability {

    /** 支持的线上场景：{@code ONLINE_SDK} / {@code ONLINE_REDIRECT} / {@code ONLINE_QR} 的子集 */
    Set<PayScene> onlineScenes();

    ChannelResult payOnline(Invocation inv, PayCommand command);
}
