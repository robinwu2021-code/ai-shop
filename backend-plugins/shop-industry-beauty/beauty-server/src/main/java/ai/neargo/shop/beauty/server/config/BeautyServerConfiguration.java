package ai.neargo.shop.beauty.server.config;

import ai.neargo.shop.beauty.config.BeautyAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/** Business readiness stays closed until real persistence and required authority contracts are verified. */
@Configuration(proxyBeanMethods = false)
@Import(BeautyAutoConfiguration.class)
public class BeautyServerConfiguration {
    public BeautyServerConfiguration(Environment environment) {
        if (environment.getProperty("shop.beauty.enabled", Boolean.class, false)
                && !"standalone".equalsIgnoreCase(environment.getProperty("shop.beauty.deployment"))) {
            throw new IllegalStateException("beauty-server requires standalone deployment");
        }
    }

    @Bean
    HealthIndicator beautyReadiness() {
        return () -> Health.outOfService().withDetail("reason", "beauty-persistence-and-contracts-pending").build();
    }
}
