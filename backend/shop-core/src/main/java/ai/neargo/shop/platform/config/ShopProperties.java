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

    /**
     * 商家版 App 的下载地址，按平台各一条。
     *
     * <p><b>地址归后端</b>：写在端上就有两处真源（官网一份、小程序一份），
     * 而这个项目已经错过一次 —— 商家端链接曾写死成 {@code shop.example.com}，
     * 印了贴纸才发现指向一个不存在的地方。
     *
     * <p><b>空就是不发</b>：iOS 版在苹果审核队列里还没上架，这一档留空，
     * 端上据此不显示那一栏，而不是显示一个点不开的地址。
     */
    private MerchantApp merchantApp = new MerchantApp();

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

    public MerchantApp getMerchantApp() {
        return merchantApp;
    }

    public void setMerchantApp(MerchantApp merchantApp) {
        this.merchantApp = merchantApp;
    }

    /** 商家版 App 的下载地址。两档都可能为空 —— 空 = 这一档还没有，端上不显示 */
    public static class MerchantApp {

        /** 安卓包直链（官网 /dl/ 下那个 apk） */
        private String android = "";

        /** iOS：上架前是 TestFlight 公开链接，上架后换 App Store 地址 */
        private String ios = "";

        public String getAndroid() {
            return android;
        }

        public void setAndroid(String android) {
            this.android = android;
        }

        public String getIos() {
            return ios;
        }

        public void setIos(String ios) {
            this.ios = ios;
        }
    }
}
