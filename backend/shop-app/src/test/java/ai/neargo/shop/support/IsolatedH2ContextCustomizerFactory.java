package ai.neargo.shop.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/** Bind in-memory databases to a cached context's lifetime, including its eviction. */
public final class IsolatedH2ContextCustomizerFactory implements ContextCustomizerFactory {
    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> attributes) {
        // A stable customizer identity preserves Spring's existing context sharing/cache keys.
        return Isolation.INSTANCE;
    }

    enum Isolation implements ContextCustomizer {
        INSTANCE;

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            // DynamicPropertySource and other customizers may run after this one. Rewrite only
            // after all of them finish, before datasource properties bind or pools are created.
            context.addBeanFactoryPostProcessor(beanFactory -> isolate(context));
        }

        private static void isolate(ConfigurableApplicationContext context) {
            String identity = UUID.randomUUID().toString().replace("-", "");
            Map<String, Object> urls = new LinkedHashMap<>();
            for (String property : List.of("spring.datasource.url", "shop.inventory.datasource.url",
                    "shop.report.datasource.url")) {
                String original = context.getEnvironment().getProperty(property);
                if (original != null && original.startsWith("jdbc:h2:mem:")) {
                    urls.put(property, isolatedUrl(original, identity));
                }
            }
            if (!urls.isEmpty()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("contextH2Databases", urls));
            }
        }
    }

    static String isolatedUrl(String original, String identity) {
        if (!original.startsWith("jdbc:h2:mem:")) return original;
        int settings = original.indexOf(';');
        String database = settings < 0 ? original : original.substring(0, settings);
        String options = settings < 0 ? "" : original.substring(settings);
        // -1 retains both database heap and CHECK expressions bound to a closed H2 SessionLocal.
        options = options.replaceAll("(?i);DB_CLOSE_DELAY=[^;]*", "");
        return database + "_" + identity + options + ";DB_CLOSE_DELAY=0";
    }
}
