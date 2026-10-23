package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.food.config.FoodAutoConfiguration;
import ai.neargo.shop.food.config.FoodProperties;
import ai.neargo.shop.food.fixture.core.DomainProbes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

class FoodConditionalWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withPropertyValues("shop.food.migration.mode=off")
            .withConfiguration(AutoConfigurations.of(FoodAutoConfiguration.class))
            .withUserConfiguration(HostScan.class);

    @Test
    void discoversRestaurantAutoConfiguration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
                .contains("ai.neargo.shop.food.config.FoodAutoConfiguration");
    }

    @Test
    void disabledFoodRegistersNoComponents() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(FoodAutoConfiguration.class)
                    .doesNotHaveBean(FoodProperties.class)
                    .doesNotHaveBean(DomainProbes.ServiceProbe.class)
                    .doesNotHaveBean(DomainProbes.MapperProbe.class)
                    .doesNotHaveBean(DomainProbes.JobProbe.class);
        });
    }

    @Test
    void explicitFalseOverridesDeployment() {
        runner.withPropertyValues("shop.food.enabled=false", "shop.food.deployment=standalone")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(DomainProbes.ServiceProbe.class)
                        .doesNotHaveBean(DomainProbes.MapperProbe.class)
                        .doesNotHaveBean(DomainProbes.JobProbe.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"embedded", "standalone"})
    void enabledBusinessLoadsInBothProcessModes(String mode) {
        runner.withPropertyValues("shop.food.enabled=true", "shop.food.deployment=" + mode)
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(FoodAutoConfiguration.class)
                        .hasSingleBean(DomainProbes.ServiceProbe.class)
                        .hasSingleBean(DomainProbes.MapperProbe.class)
                        .hasSingleBean(DomainProbes.JobProbe.class));
    }

    @Test
    void enabledDefaultsToEmbedded() {
        runner.withPropertyValues("shop.food.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FoodProperties.class);
            assertThat(context.getBean(FoodProperties.class).deployment())
                    .isEqualTo(FoodProperties.Deployment.EMBEDDED);
        });
    }

    @Test
    void bindsStandaloneDeployment() {
        runner.withPropertyValues("shop.food.enabled=true", "shop.food.deployment=standalone")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(FoodProperties.class);
                    assertThat(context.getBean(FoodProperties.class).deployment())
                            .isEqualTo(FoodProperties.Deployment.STANDALONE);
                });
    }

    @Test
    void invalidDeploymentFailsWhenEnabled() {
        runner.withPropertyValues("shop.food.enabled=true", "shop.food.deployment=typo")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = DomainProbes.class)
    static class HostScan {
    }
}
