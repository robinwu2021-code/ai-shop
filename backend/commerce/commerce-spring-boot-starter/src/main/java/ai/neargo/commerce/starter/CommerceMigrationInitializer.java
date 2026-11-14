package ai.neargo.commerce.starter;

import ai.neargo.commerce.persistence.CommerceMigrationRunner;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;

import javax.sql.DataSource;

/** Spring resolves and initializes hostInitializer before constructing this bean. */
public final class CommerceMigrationInitializer implements InitializingBean {
    private final CommerceMigrationRunner runner;

    public CommerceMigrationInitializer(DataSource ds, FlywayMigrationInitializer hostInitializer) {
        java.util.Objects.requireNonNull(hostInitializer);
        runner = new CommerceMigrationRunner(ds);
    }

    public void afterPropertiesSet() {
        runner.migrate();
    }
}
