package ai.neargo.shop.platform.config.impl;

import ai.neargo.shop.platform.config.BootstrapConfigService;
import ai.neargo.shop.platform.config.ShopProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 配置文件驱动的实现（S0）。刻意做成 {@code interface + impl} 而不是一个具体类
 * —— powerbank 那边早期写成具体类的两个 Service，后来都要回填规整（TDD-backend §3.1）。
 */
@Service
@EnableConfigurationProperties(ShopProperties.class)
public class BootstrapConfigServiceImpl implements BootstrapConfigService {

    private final ShopProperties props;
    /**
     * 平台开关（运营端「功能开关」那一屏）。
     *
     * <p>setter 注入：切片测试里没有它时，bootstrap 退回只发 yml 里那几个 ——
     * 少几个开关不该让冷启动这条端点整个挂掉。
     */
    private ai.neargo.shop.platform.PlatformConfigService platformConfig;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPlatformConfig(ai.neargo.shop.platform.PlatformConfigService platformConfig) {
        this.platformConfig = platformConfig;
    }

    public BootstrapConfigServiceImpl(ShopProperties props) {
        this.props = props;
    }

    /**
     * <p><b>yml 里的开关与运营端的开关在这里合流</b>，端上只认一份。
     *
     * <p>为什么要合：运营端那一屏能改的开关此前<b>到不了 C 端</b> —— 端上拿到的只有
     * yml 里那几个，而 yml 改了要重启。于是「运营后台一键关」对 C 端的任何行为都不成立。
     * 合流之后，像 {@code merchant.apply.mp-visible}（小程序显不显示入驻入口）
     * 这种会影响小程序审核的开关，被驳回时**关一下就止血**，不用重新发版重新提审。
     *
     * <p>同名时以**运营端为准**：他改了就该生效，yml 是兜底不是覆盖。
     */
    @Override
    public BootstrapConfig get() {
        var features = new java.util.HashMap<>(props.getFeatures());
        if (platformConfig != null) {
            for (var f : platformConfig.featureFlags()) {
                features.put(f.key(), f.enabled());
            }
        }
        return new BootstrapConfig(
                props.getDefaultSkin(),
                Map.copyOf(features),
                props.getMinAppVer(),
                props.getServiceHours(),
                new MerchantApp(props.getMerchantApp().getAndroid(),
                        props.getMerchantApp().getIos()));
    }
}
