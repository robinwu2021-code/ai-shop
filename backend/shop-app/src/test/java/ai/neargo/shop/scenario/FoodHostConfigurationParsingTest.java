package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.ShopApplication;
import java.io.IOException;
import java.util.Map;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.core.env.MapPropertySource;

/** Parse the real host configuration without starting jobs, migrations or external connections. */
@EnabledIfSystemProperty(named = "food.build.shape", matches = "embedded")
class FoodHostConfigurationParsingTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void realHostScanKeepsRetailAndFoodCatalogsDistinct(boolean enabled) throws IOException {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getDefaultListableBeanFactory().setAllowBeanDefinitionOverriding(false);
            context.getEnvironment().setActiveProfiles("api", "ops");
            for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
                context.getEnvironment().getPropertySources().addLast(source);
            }
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("food-host", Map.of(
                    "shop.food.enabled", Boolean.toString(enabled), "shop.food.deployment", "embedded",
                    "shop.food.migration.mode", "validate")));
            var tests = getClass().getProtectionDomain().getCodeSource().getLocation().toExternalForm();
            context.getBeanFactory().registerSingleton("productionOnlyScan", new TypeExcludeFilter() {
                @Override
                public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
                    return reader.getResource().getURL().toExternalForm().startsWith(tests);
                }
            });
            context.register(ShopApplication.class);
            var parser = new ConfigurationClassPostProcessor();
            parser.setEnvironment(context.getEnvironment());
            parser.postProcessBeanDefinitionRegistry(context.getDefaultListableBeanFactory());
            var factory = context.getDefaultListableBeanFactory();
            for (String name : factory.getBeanNamesForType(MapperScannerConfigurer.class, false, false)) {
                var scanner = factory.getBean(name, MapperScannerConfigurer.class);
                scanner.setApplicationContext(context);
                // Production scan packages/refs are literal; no refresh is needed to resolve placeholders.
                scanner.setProcessPropertyPlaceHolders(false);
                scanner.postProcessBeanDefinitionRegistry(factory);
            }
            assertThat(factory.getBeanDefinition("sysIdempotentMapper").getFactoryMethodName()).isNull();
            assertThat(context.getBeanDefinition("mpCatalogController").getBeanClassName())
                    .isEqualTo("ai.neargo.shop.portal.mp.MpCatalogController");
            assertThat(context.containsBeanDefinition("foodMpCatalogController")).isEqualTo(enabled);
        }
    }
}
