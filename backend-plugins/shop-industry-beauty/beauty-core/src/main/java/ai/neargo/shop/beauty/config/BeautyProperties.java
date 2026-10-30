package ai.neargo.shop.beauty.config;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment is an explicit operator decision; enabling beauty must never silently choose a write owner. */
@ConfigurationProperties(prefix = "shop.beauty")
public record BeautyProperties(boolean enabled, Deployment deployment) {
    public BeautyProperties {
        if (enabled) {
            Objects.requireNonNull(deployment, "shop.beauty.deployment is required when beauty is enabled");
        }
    }

    public enum Deployment {
        EMBEDDED,
        STANDALONE
    }
}
