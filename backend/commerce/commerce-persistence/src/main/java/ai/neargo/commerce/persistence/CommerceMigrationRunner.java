package ai.neargo.commerce.persistence;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/**
 * Internal Flyway instance: never expose this as a Spring Flyway bean. Host migration must finish
 * first.
 */
public final class CommerceMigrationRunner {
    private final DataSource ds;

    public CommerceMigrationRunner(DataSource ds) {
        this.ds = java.util.Objects.requireNonNull(ds);
    }

    public void migrate() {
        Flyway.configure()
                .dataSource(ds)
                .table("commerce_schema_history")
                .locations("classpath:db/commerce/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }
}
