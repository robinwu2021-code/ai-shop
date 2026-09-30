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

        /**
         * 版本清单文件，发版脚本写的（{@code /dl/latest.json}）。
         *
         * <p><b>它在，这里的 {@link #android} 与版本号就都以它为准</b>；它不在就用上面那两个。
         *
         * <p>为什么要有它：版本号此前写死在三处（官网 site.config、服务器 env、人的记性），
         * 每处都要手工跟，于是每处都会掉队 —— env 那处掉了二十多个版本
         * （0.4.98 vs 0.5.21），而掉队时**下载照样 200、照样装得上**，只是功能旧，
         * 没有任何信号。清单让「最新版是哪个」变成可查的一件事，发版即生效。
         *
         * <p>留空 = 不读文件，行为与改造前逐字相同（切片测试、本机开发都走这一支）。
         */
        private String manifestFile = "";

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

        public String getManifestFile() {
            return manifestFile;
        }

        public void setManifestFile(String manifestFile) {
            this.manifestFile = manifestFile;
        }
    }
}
