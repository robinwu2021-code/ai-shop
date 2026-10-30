package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import ai.neargo.shop.food.commerce.config.FoodSourceValidationAutoConfiguration;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class FoodSourceValidationWiringTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodSourceValidationAutoConfiguration.class));
    ApplicationContextRunner owners() {
        return runner.withBean(ConfigRevisionService.class, () -> mock(ConfigRevisionService.class))
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }

    @Test void disabledByDefaultAndBothFlagsAreRequired() {
        owners().run(c -> assertThat(c).doesNotHaveBean(ChannelSourceValidationProvider.class));
        owners().withPropertyValues("shop.food.enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(ChannelSourceValidationProvider.class));
        owners().withPropertyValues("shop.food.commerce.source-validation.enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(ChannelSourceValidationProvider.class));
        owners().withPropertyValues("shop.food.enabled=true", "shop.food.commerce.source-validation.enabled=true")
                .run(c -> assertThat(c).hasNotFailed().hasSingleBean(ChannelSourceValidationProvider.class));
    }

    @Test void explicitFoodCapabilityNeedsNoCimDatabaseAndMissingOriginalServiceDoesNotInventProvider() {
        runner.withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("shop.food.enabled=true", "shop.food.commerce.source-validation.enabled=true")
                .run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(ChannelSourceValidationProvider.class));
    }
}
