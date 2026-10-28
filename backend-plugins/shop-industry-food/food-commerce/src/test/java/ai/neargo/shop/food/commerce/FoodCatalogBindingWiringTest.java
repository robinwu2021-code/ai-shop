package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.food.commerce.config.FoodCatalogBindingAutoConfiguration;
import ai.neargo.shop.food.commerce.service.FoodCatalogBindingService;
import ai.neargo.shop.food.service.ConfigRevisionService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

class FoodCatalogBindingWiringTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodCatalogBindingAutoConfiguration.class));

    ApplicationContextRunner owners() {
        return runner.withBean(ObjectMapper.class, () -> JsonMapper.builder().findAndAddModules().build())
                .withBean(ConfigRevisionService.class, () -> mock(ConfigRevisionService.class))
                .withBean(ChannelCatalogAuthorityService.class, () -> mock(ChannelCatalogAuthorityService.class));
    }

    @Test
    void defaultDisabledAndBothExplicitFlagsRequired() {
        owners().run(c -> assertThat(c).doesNotHaveBean(FoodCatalogBindingService.class));
        owners().withPropertyValues("shop.food.enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(FoodCatalogBindingService.class));
        owners().withPropertyValues("shop.food.commerce.binding.enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(FoodCatalogBindingService.class));
        owners().withPropertyValues("shop.food.enabled=true", "shop.food.commerce.binding.enabled=true")
                .run(c -> assertThat(c).hasSingleBean(FoodCatalogBindingService.class));
    }

    @Test
    void originalServicesUseTheActualBootMapperWithoutAUserDefinedMapper() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class, FoodCatalogBindingAutoConfiguration.class))
                .withBean(ConfigRevisionService.class, () -> mock(ConfigRevisionService.class))
                .withBean(ChannelCatalogAuthorityService.class, () -> mock(ChannelCatalogAuthorityService.class))
                .withPropertyValues("shop.food.enabled=true", "shop.food.commerce.binding.enabled=true")
                .run(c -> {
                    assertThat(c).hasSingleBean(ObjectMapper.class);
                    assertThat(c).hasSingleBean(FoodCatalogBindingService.class);
                });
    }

    @Test
    void missingOriginalOwnerDoesNotFabricateAProviderAndCustomBeanWins() {
        runner.withPropertyValues("shop.food.enabled=true", "shop.food.commerce.binding.enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(FoodCatalogBindingService.class));
        var custom = mock(FoodCatalogBindingService.class);
        owners().withPropertyValues("shop.food.enabled=true", "shop.food.commerce.binding.enabled=true")
                .withBean(FoodCatalogBindingService.class, () -> custom)
                .run(c -> assertThat(c.getBean(FoodCatalogBindingService.class)).isSameAs(custom));
    }

    @Test
    void onlyOptionalBridgeDependsOnBothApisAndDefaultHostsStayUnchanged() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("backend/pom.xml"))) root = root.getParent();
        assertThat(root).isNotNull();
        var food = root.resolve("backend-plugins/shop-industry-food");
        assertThat(Files.readString(food.resolve("food-commerce/pom.xml")))
                .contains("<artifactId>food-api</artifactId>", "<artifactId>commerce-api</artifactId>")
                .doesNotContain("<artifactId>food-core</artifactId>", "<artifactId>commerce-core</artifactId>",
                        "<artifactId>food-web</artifactId>", "<artifactId>commerce-web</artifactId>");
        for (String module : new String[] {"commerce-api", "commerce-core", "commerce-web", "commerce-channel"}) {
            assertThat(Files.readString(
                            root.resolve("backend-plugins/shop-commerce-integration/" + module + "/pom.xml")))
                    .doesNotContain("<artifactId>food-");
        }
        var app = Files.readString(root.resolve("backend/shop-app/pom.xml"));
        assertThat(app.substring(0, app.indexOf("<profiles>")))
                .doesNotContain("<artifactId>food-commerce</artifactId>");
        var server = Files.readString(food.resolve("food-server/pom.xml"));
        assertThat(server.substring(0, server.indexOf("<profiles>")))
                .doesNotContain("<artifactId>food-commerce</artifactId>");
    }
}
