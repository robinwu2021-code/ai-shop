package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.shop.food.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FoodSchemaValidatorTest {
    @Test void offNeverReportsReady() {
        assertThat(new FoodSchemaValidator().initialize(null, FoodProperties.MigrationMode.OFF).ready()).isFalse();
    }
    @Test void rejectWrongPrimaryDatabase() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:food_wrong;DB_CLOSE_DELAY=-1", "sa", "");
        assertThatThrownBy(() -> new FoodSchemaValidator().initialize(source, FoodProperties.MigrationMode.VALIDATE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("prd_goods");
    }
    @Test void rejectUntrackedFoodTablesBeforeAnyMigration() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:food_untracked;DB_CLOSE_DELAY=-1", "sa", "");
        new JdbcTemplate(source).execute("CREATE TABLE prd_goods(id bigint); CREATE TABLE ord_order(id bigint); CREATE TABLE fnb_table_area(id bigint)");
        assertThatThrownBy(() -> new FoodSchemaValidator().initialize(source, FoodProperties.MigrationMode.MIGRATE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("untracked");
        assertThat(new JdbcTemplate(source).queryForObject("select count(*) from information_schema.tables where lower(table_name)='fnb_flyway_history'", Integer.class)).isZero();
    }
}
