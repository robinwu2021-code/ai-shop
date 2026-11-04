package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class CommerceMigrationIsolationTest {
    @TempDir Path host;

    CommerceMigrationRunner runner(MysqlDatabase db) {
        return new CommerceMigrationRunner(db.ds);
    }

    void migrate(CommerceMigrationRunner r) {
        r.migrate();
    }

    Flyway host(MysqlDatabase db) {
        return Flyway.configure().dataSource(db.ds).locations("filesystem:" + host).load();
    }

    @Test
    void hostHistoryAndLaterHostVersionStayIndependentAcrossRestarts() throws Exception {
        try (var db = new MysqlDatabase()) {
            Files.writeString(
                    host.resolve("V100__host.sql"),
                    "CREATE TABLE host_store (id BIGINT PRIMARY KEY); INSERT INTO host_store VALUES"
                            + " (7);");
            host(db).migrate();
            migrate(runner(db));
            migrate(runner(db));
            assertEquals(
                    "10",
                    db.jdbc.queryForObject(
                            "SELECT version FROM commerce_schema_history WHERE success=1 ORDER BY"
                                    + " installed_rank DESC LIMIT 1",
                            String.class));
            Files.writeString(
                    host.resolve("V101__host_later.sql"),
                    "ALTER TABLE host_store ADD later_flag INT NOT NULL DEFAULT 1;");
            host(db).migrate();
            migrate(runner(db));
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "SELECT later_flag FROM host_store WHERE id=7", Integer.class));
            assertEquals(
                    "101",
                    db.jdbc.queryForObject(
                            "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC"
                                    + " LIMIT 1",
                            String.class));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?"
                                + " AND table_name IN"
                                + " ('mch_store','mall_sku_stock','sys_outbox','inv_stock_balance')",
                            Integer.class,
                            db.name));
        }
    }
}
