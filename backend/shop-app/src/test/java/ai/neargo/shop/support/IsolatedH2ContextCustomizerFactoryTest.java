package ai.neargo.shop.support;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

class IsolatedH2ContextCustomizerFactoryTest {
    private static final String URL = "jdbc:h2:mem:context_lifetime;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test void contextsGetDifferentDatabasesWithoutFragmentingCacheIdentity() {
        var factory = new IsolatedH2ContextCustomizerFactory();
        var customizer = factory.createContextCustomizer(getClass(), List.of());
        assertThat(customizer).isEqualTo(factory.createContextCustomizer(String.class, List.of()));
        try (var first = new GenericApplicationContext(); var second = new GenericApplicationContext()) {
            for (var context : List.of(first, second)) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                        "spring.datasource.url", URL, "shop.inventory.datasource.url", URL.replace("context_lifetime", "inventory"),
                        "shop.report.datasource.url", "jdbc:mysql://localhost/report")));
                customizer.customizeContext(context, null);
                context.refresh();
                assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                        .contains("MODE=MySQL", "DATABASE_TO_LOWER=TRUE", "DB_CLOSE_DELAY=0").doesNotContain("DB_CLOSE_DELAY=-1");
                assertThat(context.getEnvironment().getProperty("shop.report.datasource.url")).isEqualTo("jdbc:mysql://localhost/report");
                assertThat(context.getEnvironment().getProperty("shop.inventory.datasource.url"))
                        .isNotEqualTo(context.getEnvironment().getProperty("spring.datasource.url"));
            }
            assertThat(first.getEnvironment().getProperty("spring.datasource.url"))
                    .isNotEqualTo(second.getEnvironment().getProperty("spring.datasource.url"));
        }
    }

    @Test void lateDynamicOverridesAreIsolatedAndSameDatabaseAliasesStayColocated() throws Exception {
        var customizer = new IsolatedH2ContextCustomizerFactory().createContextCustomizer(getClass(), List.of());
        try (var context = new GenericApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("base", Map.of("spring.datasource.url", URL)));
            customizer.customizeContext(context, null);
            // Same timing as a later DynamicPropertiesContextCustomizer: add overrides after ours.
            String overridden = URL.replace("context_lifetime", "dynamic_override");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("dynamic", Map.of(
                    "spring.datasource.url", overridden, "shop.report.datasource.url", overridden)));
            context.refresh();
            String primary = context.getEnvironment().getProperty("spring.datasource.url");
            assertThat(primary).contains("dynamic_override_");
            assertThat(context.getEnvironment().getProperty("shop.report.datasource.url")).isEqualTo(primary);
            // Production pay uses the same primary URL with its own independent pool.
            try (var core = DriverManager.getConnection(primary, "sa", "");
                    var pay = DriverManager.getConnection(primary, "sa", "")) {
                core.createStatement().execute("CREATE TABLE receipt(id INT)");
                pay.createStatement().execute("INSERT INTO receipt VALUES(1)");
                try (var result = core.createStatement().executeQuery("SELECT COUNT(*) FROM receipt")) {
                    result.next(); assertThat(result.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    @Test void closingTheLastPoolConnectionReleasesSchemaAndItsCheckConstraintSession() throws Exception {
        String url = IsolatedH2ContextCustomizerFactory.isolatedUrl(URL, "recreate");
        for (int context = 0; context < 2; context++) {
            try (var connection = DriverManager.getConnection(url, "sa", "")) {
                var sql = connection.createStatement();
                // No IF NOT EXISTS: the prior context's whole database must have been released.
                sql.execute("CREATE TABLE probe(kind VARCHAR(24), CHECK(kind IN ('ACTIVITY','PAID_PRODUCT')))");
                sql.execute("INSERT INTO probe VALUES('ACTIVITY')");
                try (var result = sql.executeQuery("SELECT COUNT(*) FROM probe")) {
                    result.next(); assertThat(result.getInt(1)).isEqualTo(1);
                }
            }
        }
    }
}
