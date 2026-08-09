import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

public class FlywayRun {
    public static void main(String[] a) {
        Flyway f = Flyway.configure()
            .dataSource(System.getenv("DB_URL"), System.getenv("DB_USER"), System.getenv("DB_PASS"))
            .locations("filesystem:" + System.getenv("MIG_DIR"))
            .baselineOnMigrate(true).baselineVersion("0").validateOnMigrate(true)
            .load();
        if (a.length > 0 && a[0].equals("migrate")) {
            var r = f.migrate();
            System.out.println("applied=" + r.migrationsExecuted + " target=" + r.targetSchemaVersion);
            return;
        }
        for (MigrationInfo m : f.info().all()) {
            if (m.getState().isApplied() && m.getVersion() != null
                && Integer.parseInt(m.getVersion().getVersion()) < 36) continue;
            System.out.printf("  %-6s %-12s %s%n", m.getVersion(), m.getState(), m.getDescription());
        }
    }
}
