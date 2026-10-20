package ai.neargo.shop.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/** 构建选项负责依赖，运行开关负责激活；两者冲突时拒绝启动。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shop.beauty", name = "enabled", havingValue = "true")
public class BeautyDeploymentConfiguration {

    public BeautyDeploymentConfiguration(Environment environment) {
        Deployment deployment = Binder.get(environment).bind("shop.beauty.deployment", Deployment.class)
                .orElseThrow(() -> new IllegalStateException("shop.beauty.deployment is required when enabled"));
        ClassLoader loader = BeautyDeploymentConfiguration.class.getClassLoader();
        boolean web = ClassUtils.isPresent("ai.neargo.shop.beauty.config.BeautyWebAutoConfiguration", loader);
        boolean core = ClassUtils.isPresent("ai.neargo.shop.beauty.config.BeautyAutoConfiguration", loader);
        if (!web) {
            throw new IllegalStateException("Enabled beauty requires a beauty-enabled host build");
        }
        if ((deployment == Deployment.EMBEDDED) != core) {
            throw new IllegalStateException("Beauty deployment does not match host build: " + deployment);
        }
    }

    private enum Deployment {
        EMBEDDED,
        STANDALONE
    }
}
