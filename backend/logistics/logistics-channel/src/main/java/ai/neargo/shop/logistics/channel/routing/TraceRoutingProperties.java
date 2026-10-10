package ai.neargo.shop.logistics.channel.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物流轨迹的路由配置（TDD-圆通物流直连 §4.2）。**按门店切换物流路径，默认圆通。**
 *
 * <pre>
 * shop.express.trace:
 *   store-route:        # 门店 → provider（门店级覆盖；没列的用 default-provider）
 *     ST-xxx: kuaidi100 # 这家店走快递100聚合
 *   default-provider: yto   # 默认圆通直连（生产）；Y1 开发期是 stub
 * </pre>
 *
 * <p>**并存就在这张表里**：A 店走圆通直连、B 店走聚合、默认圆通。加 provider + 加一行门店路由即可，
 * 调用方一行不变。门店级配置本期走 yml；将来挪到门店设置（运营/商家可配，运行时切），见 §4.3。
 */
@Component
@ConfigurationProperties(prefix = "shop.express.trace")
public class TraceRoutingProperties {

    /**
     * 门店号 → provider 名。**老形状，保留兼容**：线上 yml 里可能还写着它。
     * 新配置用 {@link #source}；两者都给时 source 赢。
     */
    private Map<String, String> storeRoute = new HashMap<>();

    /** 老形状的默认 provider。新配置用 {@code source.default} */
    private String defaultProvider = "stub";

    /** 轴一：数据源优先级链（TDD-物流轨迹多渠道 §2.2） */
    private Source source = new Source();

    /** 轴二：展示渠道优先级链，按端（§2.3） */
    private Display display = new Display();

    /**
     * 查询与备载荷的缓存时长（分钟），默认 30。
     *
     * <p>管两件事：①距上次向数据源查轨迹不足这么久就不再查 ②距上次备展示载荷不足这么久就不再备。
     * <b>既是省钱也是硬约束</b>：快递100 要求同一单查询间隔 ≥30 分钟否则锁单；
     * 微信 trace_waybill 有调用次数上限（9300513），买家反复刷详情页就能打穿。
     */
    private int cacheTtlMinutes = 30;

    public int getCacheTtlMinutes() {
        return cacheTtlMinutes;
    }

    public void setCacheTtlMinutes(int cacheTtlMinutes) {
        this.cacheTtlMinutes = cacheTtlMinutes;
    }

    /** 距上次动作还在缓存期内吗。{@code at} 为空（从没做过）一律返回 false */
    public boolean withinTtl(Long at, long now) {
        return at != null && now - at < cacheTtlMinutes * 60_000L;
    }

    /**
     * 数据源链。查的时候顺着链走，第一个「可用且认这个承运商且查到了」的胜出；
     * 查不到就继续下一个，全链走完仍空才算空。
     *
     * <p>键的优先级：{@link #byStore} › {@link #byCarrier} › {@link #byDefault}。
     * **第一个命中的键胜出，不叠加** —— 叠加的话「门店配了一条短链」反而比默认链还长，与直觉相反。
     */
    public static class Source {
        /**
         * 兜底链。**不挂 ${ENV} 占位**：List/Map 字段挂空串绑不进去，整个 context 起不来
         * （见 configprops-map-empty-env-crashes-context）。要改就改 yml 里的字面量。
         */
        private List<String> byDefault = new ArrayList<>(List.of("stub"));
        /** 承运商码（微信 delivery_id，如 YTO）→ 链。圆通单优先直连、回退聚合就配在这儿 */
        private Map<String, List<String>> byCarrier = new HashMap<>();
        /** 门店号 → 链。优先级最高 */
        private Map<String, List<String>> byStore = new HashMap<>();

        public List<String> getByDefault() {
            return byDefault;
        }

        public void setByDefault(List<String> byDefault) {
            this.byDefault = byDefault;
        }

        public Map<String, List<String>> getByCarrier() {
            return byCarrier;
        }

        public void setByCarrier(Map<String, List<String>> byCarrier) {
            this.byCarrier = byCarrier;
        }

        public Map<String, List<String>> getByStore() {
            return byStore;
        }

        public void setByStore(Map<String, List<String>> byStore) {
            this.byStore = byStore;
        }
    }

    /**
     * 展示渠道链，按端。**链尾应当永远是 self-map**（它 supports 恒真，是兜底）——
     * 链尾放一个会挑单的渠道，等于某些单什么都不显示。
     */
    public static class Display {
        /** C 端小程序：优先微信插件，备不出落到自建 */
        private List<String> mp = new ArrayList<>(List.of("self-map"));
        /** B 端 App：插件只在小程序内可用，这里只有自建 */
        private List<String> app = new ArrayList<>(List.of("self-map"));
        /** 运营端 */
        private List<String> ops = new ArrayList<>(List.of("self-map"));
        /** H5（B 端调试用） */
        private List<String> h5 = new ArrayList<>(List.of("self-map"));

        public List<String> getMp() {
            return mp;
        }

        public void setMp(List<String> mp) {
            this.mp = mp;
        }

        public List<String> getApp() {
            return app;
        }

        public void setApp(List<String> app) {
            this.app = app;
        }

        public List<String> getOps() {
            return ops;
        }

        public void setOps(List<String> ops) {
            this.ops = ops;
        }

        public List<String> getH5() {
            return h5;
        }

        public void setH5(List<String> h5) {
            this.h5 = h5;
        }
    }

    /**
     * 这个门店 + 这个承运商该按哪条数据源链查。老配置（storeRoute/defaultProvider）
     * 在新配置没写时继续生效，于是线上 yml 不动也不会变行为。
     */
    public List<String> sourceChain(String storeNo, String carrier) {
        List<String> byStore = storeNo == null ? null : source.getByStore().get(storeNo);
        if (byStore != null && !byStore.isEmpty()) {
            return byStore;
        }
        List<String> byCarrier = carrier == null ? null : source.getByCarrier().get(carrier);
        if (byCarrier != null && !byCarrier.isEmpty()) {
            return byCarrier;
        }
        // 老配置兜底：门店路由表里有就用它，否则 defaultProvider
        String legacy = storeNo == null ? null : storeRoute.get(storeNo);
        if (legacy != null && !legacy.isBlank()) {
            return List.of(legacy);
        }
        if (source.getByDefault() != null && !source.getByDefault().isEmpty()
                && !List.of("stub").equals(source.getByDefault())) {
            return source.getByDefault();
        }
        return defaultProvider == null || defaultProvider.isBlank()
                ? source.getByDefault() : List.of(defaultProvider);
    }

    /** 这个端的展示渠道链 */
    public List<String> displayChain(String surface) {
        return switch (surface == null ? "" : surface.toUpperCase(java.util.Locale.ROOT)) {
            case "MP" -> display.getMp();
            case "APP" -> display.getApp();
            case "OPS" -> display.getOps();
            case "H5" -> display.getH5();
            default -> display.getMp();
        };
    }

    public Map<String, String> getStoreRoute() {
        return storeRoute;
    }

    public void setStoreRoute(Map<String, String> storeRoute) {
        this.storeRoute = storeRoute;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public Display getDisplay() {
        return display;
    }

    public void setDisplay(Display display) {
        this.display = display;
    }
}
