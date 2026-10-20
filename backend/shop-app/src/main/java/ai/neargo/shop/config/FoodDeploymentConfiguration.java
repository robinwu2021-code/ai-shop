package ai.neargo.shop.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/** 构建选项负责依赖，运行开关负责激活；两者冲突时拒绝启动。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shop.food", name = "enabled", havingValue = "true")
public class FoodDeploymentConfiguration {

    public FoodDeploymentConfiguration(Environment environment) {
        Deployment deployment = Binder.get(environment).bind("shop.food.deployment", Deployment.class)
                .orElse(Deployment.EMBEDDED);
        ClassLoader loader = FoodDeploymentConfiguration.class.getClassLoader();
        boolean web = ClassUtils.isPresent("ai.neargo.shop.food.config.FoodWebAutoConfiguration", loader);
        boolean core = ClassUtils.isPresent("ai.neargo.shop.food.config.FoodAutoConfiguration", loader);
        if (!web) {
            throw new IllegalStateException("Enabled food requires a food-enabled host build");
        }
        if ((deployment == Deployment.EMBEDDED) != core) {
            throw new IllegalStateException("Food deployment does not match host build: " + deployment);
        }
    }

    private enum Deployment {
        EMBEDDED,
        STANDALONE
    }
}
