package ai.neargo.shop.pay.channel.handler;

import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.command.ChannelCommand;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.util.Set;

/**
 * 空对象（Null Object）：没实现的「渠道 × 场景 × 操作」明确失败，而不是 NPE、更不是静默走别的渠道。
 * 不可重试 —— 换个时间再发也不会长出处理器。
 */
public final class UnsupportedHandler implements ChannelHandler {

    private final ChannelKey key;

    UnsupportedHandler(ChannelKey key) {
        this.key = key;
    }

    @Override
    public Set<ChannelKey.Pattern> supports() {
        return Set.of();
    }

    @Override
    public ChannelResult handle(Invocation inv, ChannelCommand command) {
        return new ChannelResult.Failed(PayErrorCode.CHANNEL_UNSUPPORTED, null,
                "没有处理器：" + key, false);
    }
}
