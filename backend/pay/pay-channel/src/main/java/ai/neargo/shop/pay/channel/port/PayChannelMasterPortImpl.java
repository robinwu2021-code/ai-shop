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
    /** 判断通道有没有网关实现 —— 运营开着但没接入的通道下不了单 */
    private final ai.neargo.shop.pay.channel.PayGatewayRouter router;

    public PayChannelMasterPortImpl(PayChannelMasterService master, ai.neargo.shop.pay.channel.PayGatewayRouter router) {
        this.router = router;
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
    public List<String> payableChannels(String market) {
        // 与 enabledChannels 同一份筛选，再交上「网关装配了没有」
        return enabledChannels(market).stream().filter(router::supports).toList();
    }

    @Override
    public String currencyOf(String payChannel) {
        return master.find(payChannel)
                .map(ai.neargo.shop.pay.channel.entity.SysPayChannel::getCurrency)
                .filter(c -> c != null && !c.isBlank())
                .orElse(null);
    }

    @Override
    public List<ChannelBrief> enabledBriefs(String market) {
        return master.enabled(market).stream()
                .map(c -> new ChannelBrief(c.getPayChannel(), c.getName(),
                        Boolean.TRUE.equals(c.getEnabled()), readList(c.getPayMethods())))
                .toList();
    }

    @Override
    public List<String> payMethodsOf(String payChannel) {
        return master.find(payChannel).map(c -> readList(c.getPayMethods())).orElse(List.of());
    }

    /**
     * payMethods 存的是 JSON 数组字面量。解析失败给空列表 —— 收银台少几个按钮，不是崩。
     *
     * <p><b>刻意不用 Jackson。</b>这一列的字节在两个方言里不一样：
     * 种子写的是 {@code '[\"JSAPI\"]'}，MariaDB 解成 {@code ["JSAPI"]}，
     * H2 原样存下带反斜杠的那份。Jackson 解后者会抛，而调用方
     * 常把「解析失败」兜成空集 —— 于是本地测试里这份清单<b>永远是空的</b>，
     * 且没有任何东西会报。按 token 切分对两种字节都成立。
     */
    private static List<String> readList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.replaceAll("[\\[\\]\"\\\\\\s]", "").split(","))
                .filter(t -> !t.isBlank())
                .toList();
    }
}
