package ai.neargo.shop.platform.config;

/**
 * 启动配置：C 端冷启动拉一次，决定皮肤、开关、强更、客服入口（[API 清单 §2.14]）。
 *
 * <p>S0 先用配置文件驱动，S7 换成 {@code sys_feature_flag} 表 + ops-web 维护界面。
 * 接口位现在就定下来，届时换的是实现而不是端点。
 */
public interface BootstrapConfigService {

    BootstrapConfig get();

    /**
     * @param defaultSkin  运营下发的默认皮肤（C-TH-05），端侧本地偏好优先
     * @param features     功能开关，如 {@code points=false}（ADR-006 一期关闭）
     * @param minAppVer    最低可用端版本，低于此值端侧强更
     * @param serviceHours 客服在线时段（展示用）
     * @param merchantApp  商家版 App 的下载地址，按平台各一条；<b>空的那一档端上不显示</b>
     */
    record BootstrapConfig(String defaultSkin,
                           java.util.Map<String, Boolean> features,
                           String minAppVer,
                           String serviceHours,
                           MerchantApp merchantApp) {
    }

    /**
     * 商家版 App 的下载地址。
     *
     * <p><b>为什么由后端发而不是端上写死</b>：写在端上就有两处真源（官网一份、
     * 小程序一份），而这个项目已经错过一次 —— 商家端链接曾写死成
     * {@code shop.example.com}，商家印了贴纸才发现指向一个不存在的地方。
     *
     * @param android 安卓包直链；空 = 还没有，端上不显示这一档
     * @param ios     iOS 地址（上架前是 TestFlight 公开链接）；空 = 同上。
     *                iOS 版在苹果审核队列里，所以这一档现在就是空的
     */
    record MerchantApp(String android, String ios) {
    }
}
