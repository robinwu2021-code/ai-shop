package ai.neargo.shop.food.config;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 平台既有餐饮配置；进程部署方式不决定独立服务是否拥有本地业务实现。 */
@ConfigurationProperties(prefix = "shop.food")
public record FoodProperties(boolean enabled, @DefaultValue("embedded") Deployment deployment) {

    public FoodProperties {
        Objects.requireNonNull(deployment, "shop.food.deployment is required");
    }

    public enum Deployment {
        EMBEDDED,
        STANDALONE
    }
}
