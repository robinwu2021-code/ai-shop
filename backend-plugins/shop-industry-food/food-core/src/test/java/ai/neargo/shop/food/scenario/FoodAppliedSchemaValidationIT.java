package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.food.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit opt-in diagnostic. VALIDATE only; never a migration/repair command. Credentials only from environment. */
@EnabledIfEnvironmentVariable(named = "FOOD_VALIDATE_EXISTING", matches = "true")
class FoodAppliedSchemaValidationIT {
    @Test void validateExistingHistoryWithoutWrites() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("FOOD_VALIDATE_JDBC_URL"),
                System.getenv("FOOD_VALIDATE_USER"), System.getenv("FOOD_VALIDATE_PASSWORD"));
        var sql = new JdbcTemplate(source);
        var before = sql.queryForList("SELECT installed_rank,version,description,type,script,checksum,installed_by,installed_on,execution_time,success FROM fnb_flyway_history ORDER BY installed_rank");
        var coreBefore = sql.queryForList("SELECT installed_rank,version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        try {
            var result = new FoodSchemaValidator().initialize(source, FoodProperties.MigrationMode.VALIDATE);
            assertThat(result.validated()).isTrue();
            System.out.println("FOOD_VALIDATE_RESULT engine=" + sql.queryForObject("SELECT VERSION()", String.class)
                    + "; version=" + result.version() + "; foodHistory=" + before.size()
                    + "; v1Checksum=" + before.stream().filter(row -> "1".equals(row.get("version"))).map(row -> row.get("checksum")).toList()
                    + "; coreLastVersion=" + coreBefore.getLast().get("version")
                    + "; schemaValidated=" + result.validated()
                    + "; ready=" + result.ready() + "; missing=" + result.missingCapabilities());
        } finally {
            assertThat(sql.queryForList("SELECT installed_rank,version,description,type,script,checksum,installed_by,installed_on,execution_time,success FROM fnb_flyway_history ORDER BY installed_rank")).isEqualTo(before);
            assertThat(sql.queryForList("SELECT installed_rank,version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank")).isEqualTo(coreBefore);
            System.out.println("FOOD_VALIDATE_HISTORY food-and-core-unchanged");
        }
    }
}
