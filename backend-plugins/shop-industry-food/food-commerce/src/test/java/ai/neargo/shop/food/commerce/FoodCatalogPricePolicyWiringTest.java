package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.food.commerce.config.FoodCatalogPricePolicyAutoConfiguration;
import ai.neargo.shop.food.commerce.service.FoodCatalogBindingService;
import ai.neargo.shop.food.commerce.service.FoodCatalogPricePolicyService;
import ai.neargo.shop.food.service.MenuCatalogPriceService;
import ai.neargo.shop.food.service.MenuChannelBindingService;
import ai.neargo.shop.food.spi.MenuChannelAuthorityPort;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FoodCatalogPricePolicyWiringTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    FoodCatalogPricePolicyAutoConfiguration.class))
            .withBean(ChannelCatalogAuthorityService.class, () -> mock(ChannelCatalogAuthorityService.class))
            .withBean(MenuChannelBindingService.class, () -> mock(MenuChannelBindingService.class))
            .withBean(CatalogPriceSourcePort.class, () -> mock(CatalogPriceSourcePort.class))
            .withBean(FoodCatalogBindingService.class, () -> mock(FoodCatalogBindingService.class))
            .withBean(MenuCatalogPriceService.class, () -> mock(MenuCatalogPriceService.class));

    @Test
    void defaultsClosedAndExplicitEnableUsesOriginalServicesAndBootMapper() {
        runner.run(c -> assertThat(c).doesNotHaveBean(MenuChannelAuthorityPort.class)
                .doesNotHaveBean(FoodCatalogPricePolicyService.class));
        runner.withPropertyValues("shop.food.enabled=true", "shop.food.commerce.price-policy.enabled=true")
                .run(c -> assertThat(c).hasSingleBean(MenuChannelAuthorityPort.class)
                        .hasSingleBean(FoodCatalogPricePolicyService.class));
    }

    @Test
    void absentOriginalsDoNotFabricateAuthorityAndCustomServicesWin() {
        new ApplicationContextRunner().withConfiguration(
                AutoConfigurations.of(FoodCatalogPricePolicyAutoConfiguration.class))
                .withPropertyValues("shop.food.enabled=true", "shop.food.commerce.price-policy.enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(MenuChannelAuthorityPort.class)
                        .doesNotHaveBean(FoodCatalogPricePolicyService.class));
        var custom = mock(MenuChannelAuthorityPort.class);
        var policy = mock(FoodCatalogPricePolicyService.class);
        runner.withPropertyValues("shop.food.enabled=true", "shop.food.commerce.price-policy.enabled=true")
                .withBean(MenuChannelAuthorityPort.class, () -> custom)
                .withBean(FoodCatalogPricePolicyService.class, () -> policy)
                .run(c -> {
                    assertThat(c.getBean(MenuChannelAuthorityPort.class)).isSameAs(custom);
                    assertThat(c.getBean(FoodCatalogPricePolicyService.class)).isSameAs(policy);
                });
    }
}
