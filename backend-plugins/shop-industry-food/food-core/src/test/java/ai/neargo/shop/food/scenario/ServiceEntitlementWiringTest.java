package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.food.config.FoodServiceEntitlementAutoConfiguration;
import ai.neargo.shop.food.service.ServiceEntitlementService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ServiceEntitlementWiringTest {
    final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(FoodServiceEntitlementAutoConfiguration.class));

    @Test
    void disabledByDefaultAndMissingActualQuoteMenuMaterialCapacityOwnersCannotInventProvider() {
        runner.run(c -> assertThat(c).doesNotHaveBean(ServiceEntitlementService.class));
        runner.withPropertyValues(
                        "shop.food.enabled=true", "shop.food.service-entitlements.enabled=true")
                .run(
                        c ->
                                assertThat(c)
                                        .hasNotFailed()
                                        .doesNotHaveBean(ServiceEntitlementService.class));
    }
}
