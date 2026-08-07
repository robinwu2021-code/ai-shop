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

    public BootstrapConfigServiceImpl(ShopProperties props) {
        this.props = props;
    }

    @Override
    public BootstrapConfig get() {
        return new BootstrapConfig(
                props.getDefaultSkin(),
                Map.copyOf(props.getFeatures()),
                props.getMinAppVer(),
                props.getServiceHours());
    }
}
