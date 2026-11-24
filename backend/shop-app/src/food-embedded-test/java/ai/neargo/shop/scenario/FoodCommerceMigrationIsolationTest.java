package ai.neargo.shop.scenario;

import ai.neargo.shop.commerce.config.CommerceSchemaValidator;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FoodCommerceMigrationIsolationTest {
    @Test
    void embeddedChannelAndMallMigrationsResolveIndependently() {
        var channel = scripts(CommerceSchemaValidator.MIGRATION_LOCATION);
        var mall = scripts("classpath:db/commerce/migration");

        assertThat(channel).contains("V1__commerce_draft_resources.sql", "V18__commerce_tax_source_notice.sql");
        assertThat(mall).contains("V1__commerce_catalog.sql");
        assertThat(channel).doesNotContainAnyElementsOf(mall);
    }

    private static List<String> scripts(String location) {
        var flyway = Flyway.configure()
                .dataSource("jdbc:h2:mem:embedded_commerce_migration_locations", "sa", "")
                .locations(location)
                .failOnMissingLocations(true)
                .load();
        return Arrays.stream(flyway.info().all()).map(MigrationInfo::getScript).toList();
    }
}
