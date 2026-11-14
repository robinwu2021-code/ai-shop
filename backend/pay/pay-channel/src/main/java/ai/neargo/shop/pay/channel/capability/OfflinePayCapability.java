package ai.neargo.shop.pay.channel.capability;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.api.command.PayCommand;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.util.Set;

/**
 * 线下扫码收款：扫顾客付款码（同步扣款，<b>不可重发</b>）或终端屏显码。I7 / I8 接入（LINE Pay Offline、PayBy）。
 */
public interface OfflinePayCapability extends ChannelCapability {

    /** {@code IN_STORE_SCAN_CUSTOMER} / {@code IN_STORE_SHOW_QR} 的子集 */
    Set<PayScene> offlineScenes();

    ChannelResult payOffline(Invocation inv, PayCommand command);
}
