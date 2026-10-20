package ai.neargo.shop.beauty.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Public ingress reuses the host's authentication and requires an explicit deployment selection. */
@AutoConfiguration
@ConditionalOnBeautyWebEnabled
@Profile({"api", "ops"})
public class BeautyWebAutoConfiguration {
    public BeautyWebAutoConfiguration(Environment environment) {
        String deployment = environment.getProperty("shop.beauty.deployment", "").toLowerCase(Locale.ROOT);
        if (!Set.of("embedded", "standalone").contains(deployment)) {
            throw new IllegalStateException("shop.beauty.deployment must explicitly select embedded or standalone");
        }
    }
}
