import ai.neargo.shop.config.FlywayMariaDbDialectDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Native smoke for the production Flyway adapter; creates and drops only its own table. */
public class LocalMysqlHistoricalSmoke {
    public static void main(String[] args) throws Exception {
        var source = new DriverManagerDataSource(System.getenv("LOCAL_DB_URL"),
                System.getenv("LOCAL_DB_USER"), System.getenv("LOCAL_DB_PASS"));
        try (var connection = new FlywayMariaDbDialectDataSource(source).getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE local_adapter_smoke(id BIGINT PRIMARY KEY, x VARCHAR(20), UNIQUE KEY ux(x))");
            String[] cases = {
                "ALTER TABLE local_adapter_smoke DROP INDEX IF EXISTS ux",
                "ALTER TABLE local_adapter_smoke ADD UNIQUE KEY IF NOT EXISTS ux(x)",
                "ALTER TABLE local_adapter_smoke ADD COLUMN IF NOT EXISTS y VARCHAR(32) COMMENT 'a,b', ADD COLUMN IF NOT EXISTS z DECIMAL(12,2)",
                "CREATE INDEX IF NOT EXISTS idx_y ON local_adapter_smoke(y)"
            };
            for (String sql : cases) {
                statement.execute(sql);
                statement.execute(sql);
            }
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='local_adapter_smoke'")) {
                result.next();
                if (result.getInt(1) != 4) throw new AssertionError("Conditional column DDL did not preserve columns");
            }
            try (var result = statement.executeQuery("SELECT 'ADD COLUMN IF NOT EXISTS y'")) {
                result.next();
                if (!result.getString(1).equals("ADD COLUMN IF NOT EXISTS y")) throw new AssertionError("Literal changed");
            }
            statement.execute("DROP TABLE local_adapter_smoke");
            System.out.println("native_production_adapter_idempotence PASS");
        }
    }
}
