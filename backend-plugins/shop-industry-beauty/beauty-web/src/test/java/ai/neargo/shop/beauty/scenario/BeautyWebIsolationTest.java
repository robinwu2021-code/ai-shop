package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** Web can be shipped without the beauty business implementation. */
class BeautyWebIsolationTest {
    private Class<?> configuration() {
        return assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty.config.BeautyWebAutoConfiguration"),
                "beauty must provide a web-only configuration");
    }

    private WebApplicationContextRunner runner() {
        assertThat(getClass().getClassLoader().getResource(
                "ai/neargo/shop/beauty/config/BeautyAutoConfiguration.class")).isNull();
        return new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(configuration()));
    }

    @Test
    void disabledWebDoesNotNeedBusinessOrDeployment() {
        runner().withPropertyValues("spring.profiles.active=api")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(configuration()));
    }

    @Test
    void enabledWebRejectsMissingDeployment() {
        runner().withPropertyValues("spring.profiles.active=api", "shop.beauty.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void webLoadsWithoutCoreForBothDeployments() {
        for (String mode : new String[] {"embedded", "standalone"}) {
            runner().withPropertyValues("spring.profiles.active=api", "shop.beauty.enabled=true",
                            "shop.beauty.deployment=" + mode)
                    .run(context -> assertThat(context).hasNotFailed().hasSingleBean(configuration()));
        }
    }

    @Test
    void noUserProfileDoesNotRegisterWeb() {
        runner().withPropertyValues("shop.beauty.enabled=true", "shop.beauty.deployment=standalone")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(configuration()));
    }
}
