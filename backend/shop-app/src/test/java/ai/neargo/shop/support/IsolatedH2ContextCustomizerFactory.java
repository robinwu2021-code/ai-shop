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

/** A pooled in-memory database belongs to one cached context, including after cache eviction. */
public final class IsolatedH2ContextCustomizerFactory implements ContextCustomizerFactory {
    private static final List<String> URL_PROPERTIES = List.of(
            "spring.datasource.url", "shop.inventory.datasource.url", "shop.job.datasource.url");

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return new IsolationCustomizer();
    }

    static String isolateUrl(String url, String namespace) {
        String prefix = "jdbc:h2:mem:";
        if (url == null || !url.startsWith(prefix)) return url;
        int end = url.indexOf(';', prefix.length());
        if (end < 0) end = url.length();
        if (end == prefix.length()) return url; // Preserve anonymous H2 semantics.
        String options = url.substring(end).replaceAll("(?i);DB_CLOSE_DELAY=[^;]*", "");
        return url.substring(0, end) + "_ctx_" + namespace + options + ";DB_CLOSE_DELAY=0";
    }

    private static final class IsolationCustomizer implements ContextCustomizer {
        @Override
        public void customizeContext(
                ConfigurableApplicationContext context, MergedContextConfiguration configuration) {
            // Run after profile/dynamic test properties are installed and before any datasource
            // properties or pools are instantiated. Raw unpooled JDBC fixtures are not rewritten.
            context.addBeanFactoryPostProcessor(beanFactory -> {
                String namespace = UUID.randomUUID().toString().replace("-", "");
                Map<String, Object> overrides = new LinkedHashMap<>();
                Map<String, String> urls = new LinkedHashMap<>();
                for (String property : URL_PROPERTIES) {
                    String original = context.getEnvironment().getProperty(property);
                    if (original == null) continue;
                    String isolated = urls.computeIfAbsent(original, url -> isolateUrl(url, namespace));
                    if (!original.equals(isolated)) overrides.put(property, isolated);
                }
                if (!overrides.isEmpty()) {
                    context.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("context-owned-h2-databases", overrides));
                }
            });
        }

        // UUIDs are allocated only when loading a context, never in its cache key.
        @Override public boolean equals(Object other) { return other instanceof IsolationCustomizer; }
        @Override public int hashCode() { return IsolationCustomizer.class.hashCode(); }
    }
}
