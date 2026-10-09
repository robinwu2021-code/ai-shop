package ai.neargo.shop.logistics.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物流模块配置（{@code shop.logistics.*}，TDD-物流模块 §2.5）。
 *
 * <p>集合字段一律<b>给字段默认值、不挂 {@code ${ENV:}}</b>：空串绑不进 Map / List，整个上下文起不来。
 * 路由键名沿用 {@code shop.express.trace} 的 {@code by-default / by-carrier / by-store}，不另起一套。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "shop.logistics")
public class LogisticsProperties {

    /** 各能力的路由链 */
    private Routes routes = new Routes();

    /**
     * 渠道名 → 允许它被「刷新」现查的界面（MP / APP / H5 / BIZ / OPS）。
     * 默认快递100 一个界面都不允许 —— 额度不够。没列的渠道视为全部允许（微信探测本来就要 token）。
     */
    private Map<String, List<String>> probeSurfaces = new LinkedHashMap<>(Map.of("kuaidi100", List.of()));

    /** 物流页读时校正的最短间隔（分钟） */
    private int readCacheMinutes = 10;

    /** 推送回调地址前缀，后面拼渠道名 */
    private String callbackBase = "https://www.hxmall.top/callback/logistics";

    /** 收件人手机号加密密钥。空 → 不存密文（同 PhoneCrypto 的失败方式：降隐私不降可用），订阅不带手机号 */
    private String phoneKey = "";

    /** 渠道开关。**默认启用**，能不能用由凭据决定（{@code available()}）；{@code enabled: false} 只当紧急关闭用 */
    private Map<String, Toggle> channels = new LinkedHashMap<>();

    public boolean enabled(String channel) {
        Toggle t = channels.get(channel);
        return t == null || t.isEnabled();
    }

    @Getter
    @Setter
    public static class Routes {
        /** 订阅链。默认先圆通直连、再快递100：圆通单不占快递100 额度，圆通不可用就落到快递100 */
        private Chain subscribe = Chain.of("yto", "kuaidi100");
        /** 探测链。默认只问微信 */
        private Chain probe = Chain.of("wx");
    }

    /** 一条路由：门店 → 承运商 → 默认，逐级，每级一条渠道链 */
    @Getter
    @Setter
    public static class Chain {
        private List<String> byDefault = new ArrayList<>();
        private Map<String, List<String>> byCarrier = new LinkedHashMap<>();
        private Map<String, List<String>> byStore = new LinkedHashMap<>();

        static Chain of(String... channels) {
            Chain c = new Chain();
            c.byDefault = new ArrayList<>(List.of(channels));
            return c;
        }

        public List<String> resolve(String storeNo, String carrier) {
            if (storeNo != null && byStore.containsKey(storeNo)) {
                return byStore.get(storeNo);
            }
            if (carrier != null && byCarrier.containsKey(carrier)) {
                return byCarrier.get(carrier);
            }
            return byDefault;
        }
    }

    @Getter
    @Setter
    public static class Toggle {
        private boolean enabled = true;
    }
}
