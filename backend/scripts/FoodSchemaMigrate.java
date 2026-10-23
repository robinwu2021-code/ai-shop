import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;

/** Migrates only fnb-owned tables, using the product/order database and a separate history. */
public final class FoodSchemaMigrate {
    public static void main(String[] args) throws Exception {
        String action = args.length == 0 ? "info" : args[0];
        if (!Set.of("info", "migrate", "validate").contains(action)) {
            throw new IllegalArgumentException("Only info, migrate and validate are supported");
        }
        String url = required("DB_URL");
        String user = required("DB_USER");
        String password = required("DB_PASS");
        Path directory = Path.of(required("MIG_DIR")).toRealPath();
        Path migration = directory.resolve("V1__food_business_tables.sql");
        String sql = Files.readString(migration);
        Set<String> expected = new HashSet<>();
        var matcher = java.util.regex.Pattern.compile("CREATE TABLE `([a-z0-9_]+)`").matcher(sql);
        while (matcher.find()) {
            if (!matcher.group(1).startsWith("fnb_")) {
                throw new IllegalArgumentException("Food migration contains a non-fnb table");
            }
            expected.add(matcher.group(1));
        }
        if (expected.isEmpty()) throw new IllegalArgumentException("Empty food migration");
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.prepareStatement(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()")) {
            Set<String> present = new HashSet<>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) present.add(rows.getString(1));
            }
            if (!present.containsAll(Set.of("prd_goods", "ord_order"))) {
                throw new IllegalStateException("Target must be the existing product/order database");
            }
            if (!present.contains("fnb_flyway_history")) {
                if (present.stream().anyMatch(name -> name.startsWith("fnb_"))) {
                    throw new IllegalStateException("Untracked fnb tables exist; refusing implicit baseline");
                }
            }
        }
        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("filesystem:" + directory)
                .table("fnb_flyway_history")
                .baselineOnMigrate(true).baselineVersion("0")
                .validateOnMigrate(true).cleanDisabled(true)
                .placeholderReplacement(false)
                .load();
        if (action.equals("migrate")) {
            var result = flyway.migrate();
            System.out.println("Food migrations applied=" + result.migrationsExecuted
                    + "; target=" + result.targetSchemaVersion + "; expected business tables=" + expected.size());
        } else if (action.equals("validate")) {
            flyway.validate();
            System.out.println("Food migration validation passed");
        } else {
            for (var migrationInfo : flyway.info().all()) {
                System.out.println(migrationInfo.getVersion() + " " + migrationInfo.getState()
                        + " " + migrationInfo.getDescription());
            }
        }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}
