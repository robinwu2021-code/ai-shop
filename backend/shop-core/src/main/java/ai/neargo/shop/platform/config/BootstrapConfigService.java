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
     */
    record BootstrapConfig(String defaultSkin,
                           java.util.Map<String, Boolean> features,
                           String minAppVer,
                           String serviceHours) {
    }
}
