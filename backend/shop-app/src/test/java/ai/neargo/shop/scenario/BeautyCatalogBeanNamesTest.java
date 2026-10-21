package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Real retail/beauty controller discovery must coexist before deployment can instantiate either graph. */
class BeautyCatalogBeanNamesTest {
    @Test
    void retailAndBeautyPublicControllersHaveDistinctBeanNames() {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("api");
        environment.getPropertySources().addFirst(new MapPropertySource("beauty-embedded", Map.of(
                "shop.beauty.enabled", "true", "shop.beauty.deployment", "embedded")));
        var scanner = new ClassPathBeanDefinitionScanner(new DefaultListableBeanFactory());
        scanner.setEnvironment(environment);
        assertThatCode(() -> scanner.scan("ai.neargo.shop.portal.mp", "ai.neargo.shop.beauty.api.mp"))
                .doesNotThrowAnyException();
    }
}
