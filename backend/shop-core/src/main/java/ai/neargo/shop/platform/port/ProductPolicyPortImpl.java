package ai.neargo.shop.platform.port;

import ai.neargo.shop.spi.platform.ProductPolicyPort;
import ai.neargo.shop.spi.platform.SettingPort;
import org.springframework.stereotype.Component;

/**
 * 薄转发。**不缓存**：它只在提审那一刻读一次，
 * 而提审是人点一次按钮的动作，不是热路径 —— 加一层缓存只会多一个
 * 「改了规则要等 60 秒才生效」的坑（禁售词那边是每次校验都读，才需要缓存）。
 */
@Component
public class ProductPolicyPortImpl implements ProductPolicyPort {

    private static final String KEY = "product.policy";

    private final SettingPort settings;

    public ProductPolicyPortImpl(SettingPort settings) {
        this.settings = settings;
    }

    @Override
    public Policy current() {
        return ProductPolicyPort.parse(settings.get(KEY, null));
    }
}
