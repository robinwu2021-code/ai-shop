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
     * @param customerService 微信客服的接入参数；<b>任一为空端上就回落</b>到
     *                        {@code open-type="contact"}（TDD-微信客服接入 §4.1）
     */
    record BootstrapConfig(String defaultSkin,
                           java.util.Map<String, Boolean> features,
                           String minAppVer,
                           String serviceHours,
                           MerchantApp merchantApp,
                           CustomerService customerService) {
    }

    /**
     * 微信客服（企业微信那款）的接入参数。
     *
     * <p><b>为什么随冷启动一起下发，而不是点的时候现拉</b>：
     * {@code wx.openCustomerServiceChat} 在 iOS 上必须由用户手势<u>直接</u>触发 ——
     * 先 await 再调会被判「并非点击触发」而失败，Android 却能过。
     * 所以这两个值必须在点击之前就躺在端上。
     *
     * @param corpId 企业微信 CorpID。<b>同主体还不够，必须在小程序后台绑过</b>；
     *               没绑的表现是端上 {@code errCode 6}
     * @param url    客服接入链接（企微后台 → 应用管理 → 微信客服 → 客服账号详情）
     */
    record CustomerService(String corpId, String url) {
    }

    /**
     * 商家版 App 的下载地址。
     *
     * <p><b>为什么由后端发而不是端上写死</b>：写在端上就有两处真源（官网一份、
     * 小程序一份），而这个项目已经错过一次 —— 商家端链接曾写死成
     * {@code shop.example.com}，商家印了贴纸才发现指向一个不存在的地方。
     *
     * @param android        安卓包直链；空 = 还没有，端上不显示这一档
     * @param ios            iOS 地址（上架前是 TestFlight 公开链接）；空 = 同上。
     *                       iOS 版在苹果审核队列里，所以这一档现在就是空的
     * @param androidVersion 安卓包的版本号（如 {@code 0.5.21}），从发版脚本写的
     *                       {@code /dl/latest.json} 读；读不到就是空串，端上不显示版本。
     *                       <b>不要在别处再写一份</b> —— 此前版本号写死在三处，
     *                       每处都要手工跟，于是每处都会掉队
     */
    record MerchantApp(String android, String ios, String androidVersion) {

        /** 旧调用点用的两参构造：不带版本号 */
        public MerchantApp(String android, String ios) {
            this(android, ios, "");
        }
    }
}
