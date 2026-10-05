package ai.neargo.shop.channel.express.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
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

    /** 门店号 → provider 名。没列的用 {@link #defaultProvider} */
    private Map<String, String> storeRoute = new HashMap<>();

    /** 没在 storeRoute 里的门店走哪个 provider。**生产默认圆通（yto）**；开发期 stub（空轨迹，不白屏） */
    private String defaultProvider = "stub";

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
}
