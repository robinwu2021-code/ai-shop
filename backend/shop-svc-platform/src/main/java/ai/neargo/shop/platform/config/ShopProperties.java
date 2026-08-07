package ai.neargo.shop.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code shop.*} 配置。零硬编码原则的后端落点：皮肤名、开关、版本号都不写在代码里。
 */
@ConfigurationProperties(prefix = "shop")
public class ShopProperties {

    /** 默认皮肤，与 c-app 的 {@code data-skin} 取值一致：fresh / promo / mono / blue。 */
    private String defaultSkin = "fresh";

    /** 功能开关。一期 {@code points=false}（ADR-006：跨商家清算未定，打开即上线）。 */
    private Map<String, Boolean> features = new HashMap<>(Map.of("points", false));

    private String minAppVer = "1.0.0";

    private String serviceHours = "09:00-21:00";

    public String getDefaultSkin() {
        return defaultSkin;
    }

    public void setDefaultSkin(String defaultSkin) {
        this.defaultSkin = defaultSkin;
    }

    public Map<String, Boolean> getFeatures() {
        return features;
    }

    public void setFeatures(Map<String, Boolean> features) {
        this.features = features;
    }

    public String getMinAppVer() {
        return minAppVer;
    }

    public void setMinAppVer(String minAppVer) {
        this.minAppVer = minAppVer;
    }

    public String getServiceHours() {
        return serviceHours;
    }

    public void setServiceHours(String serviceHours) {
        this.serviceHours = serviceHours;
    }
}
