package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.food.commerce.config.FoodChannelSourceFactsAutoConfiguration;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.spi.DiningChannelSourceFactsPort;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.food.spi.FoodMaterialSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import tools.jackson.databind.ObjectMapper;

class FoodChannelSourceFactsWiringTest {
    final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(FoodChannelSourceFactsAutoConfiguration.class));

    ApplicationContextRunner owners() {
        return runner.withBean(
                        ChannelOrderSourceEvidencePort.class,
                        () -> mock(ChannelOrderSourceEvidencePort.class))
                .withBean(ConfigRevisionService.class, () -> mock(ConfigRevisionService.class))
                // b91bcc006 起配置依赖餐饮自己的 FoodMaterialSource，而不是全局的 MaterialCatalogQueryPort
                .withBean(FoodMaterialSource.class, () -> () -> mock(MaterialCatalogQueryPort.class))
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }

    @Test
    void requiresBothOriginalOwnerFlagsAndEveryRealDependency() {
        owners().run(c -> assertThat(c).doesNotHaveBean(DiningChannelSourceFactsPort.class));
        owners().withPropertyValues("shop.food.enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(DiningChannelSourceFactsPort.class));
        owners().withPropertyValues("shop.food.enabled=true", "shop.food.channel.enabled=true")
                .run(
                        c ->
                                assertThat(c)
                                        .hasNotFailed()
                                        .hasSingleBean(DiningChannelSourceFactsPort.class));
        runner.withBean(ConfigRevisionService.class, () -> mock(ConfigRevisionService.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("shop.food.enabled=true", "shop.food.channel.enabled=true")
                .run(
                        c ->
                                assertThat(c)
                                        .hasNotFailed()
                                        .doesNotHaveBean(DiningChannelSourceFactsPort.class));
    }
}
