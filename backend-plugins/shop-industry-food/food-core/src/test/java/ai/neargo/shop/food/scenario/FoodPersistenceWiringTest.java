package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.food.config.FoodAutoConfiguration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FoodPersistenceWiringTest {
    @Test void offRequiresNoDatabaseAndNeverReportsReady() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FoodAutoConfiguration.class))
                .withPropertyValues("shop.food.enabled=true", "shop.food.migration.mode=off")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(DataSource.class);
                    assertThat(context.getBean(ai.neargo.shop.food.config.FoodSchemaReady.class).ready()).isFalse();
                });
    }

    @Test
    void defaultValidationRequiresNamedPrimaryDataSource() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FoodAutoConfiguration.class))
                .withBean("invDataSource", DataSource.class,
                        () -> new DriverManagerDataSource("jdbc:h2:mem:food_wrong_store", "sa", ""))
                .withPropertyValues("shop.food.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
