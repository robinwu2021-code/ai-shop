package ai.neargo.shop.pay.channel.port;

import ai.neargo.shop.pay.channel.entity.SysPayChannel;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import ai.neargo.shop.spi.pay.PayChannelMasterPort;
import java.util.List;
import org.springframework.stereotype.Component;

/** {@link PayChannelMasterPort} 实现 —— 薄适配器，逻辑在 {@link PayChannelMasterService} */
@Component
public class PayChannelMasterPortImpl implements PayChannelMasterPort {

    private final PayChannelMasterService master;

    public PayChannelMasterPortImpl(PayChannelMasterService master) {
        this.master = master;
    }

    @Override
    public String channelName(String payChannel) {
        // 查不到返回通道码本身：界面上宁可显示 WECHAT 也别显示空
        return master.find(payChannel)
                .map(SysPayChannel::getName)
                .filter(n -> !n.isBlank())
                .orElse(payChannel);
    }

    @Override
    public List<String> enabledChannels(String market) {
        return master.enabled(market).stream().map(SysPayChannel::getPayChannel).toList();
    }

    @Override
    public List<ChannelBrief> enabledBriefs(String market) {
        return master.enabled(market).stream()
                .map(c -> new ChannelBrief(c.getPayChannel(), c.getName(),
                        Boolean.TRUE.equals(c.getEnabled()), readList(c.getPayMethods())))
                .toList();
    }

    /** payMethods 存的是 JSON 数组字面量。解析失败给空列表 —— 收银台少几个按钮，不是崩 */
    private static List<String> readList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.replaceAll("[\\[\\]\"\\\\\\s]", "").split(","))
                .filter(t -> !t.isBlank())
                .toList();
    }
}
