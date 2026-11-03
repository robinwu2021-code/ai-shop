package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.persistence.catalog.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.*;

class CommerceTransactionBoundaryTest {
    private SysBusinessTypeRecord row() {
        var now = LocalDateTime.of(2026, 11, 3, 0, 0);
        return new SysBusinessTypeRecord(null, "A", now, now, "actor", "actor", 0L, 0,
                "RETAIL", "Retail", 1, 0, 0L, null);
    }

    private CommerceEvent event() {
        return new CommerceEvent("event", "A", "STORE", "store", 1L, "CHANGED", 1, "{}", Instant.EPOCH);
    }

    private boolean rejected(Runnable work) {
        try { work.run(); return false; }
        catch (IllegalStateException expected) { return true; }
    }

    private void noWrites(MysqlDatabase db) {
        for (String table : java.util.List.of("sys_business_type", "commerce_outbox", "commerce_event_inbox"))
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
    }

    // A read binds B for synchronization. Neither auto-commit setting establishes Spring ownership.
    private DataSource commerceSource(MysqlDatabase db, boolean autoCommit) {
        return new DelegatingDataSource(db.ds) {
            @Override public Connection getConnection() throws SQLException {
                var connection = super.getConnection();
                connection.setAutoCommit(autoCommit);
                return connection;
            }
        };
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void foreignTransactionCannotStartAnIndependentCommerceUnitOfWork(boolean autoCommit) {
        try (var host = new MysqlDatabase(); var commerce = new MysqlDatabase()) {
            commerce.migrate();
            host.jdbc.execute("CREATE TABLE host_work(id INT)");
            var ds = commerceSource(commerce, autoCommit);
            var uow = new JdbcCommerceUnitOfWork(ds, new DataSourceTransactionManager(ds));
            var entered = new AtomicBoolean();
            var denied = new AtomicBoolean();
            new TransactionTemplate(new DataSourceTransactionManager(host.ds)).execute(status -> {
                host.jdbc.update("INSERT INTO host_work VALUES(1)");
                denied.set(rejected(() -> uow.required(() -> {
                    entered.set(true);
                    new SysBusinessTypeRepository(ds).insert(row());
                    new JdbcCommerceEventPersistence(ds).append(event());
                    return null;
                })));
                status.setRollbackOnly();
                return null;
            });
            assertAll(() -> assertTrue(denied.get(), "foreign transaction rejected at entry"),
                    () -> assertFalse(entered.get(), "commerce callback never entered"),
                    () -> noWrites(commerce),
                    () -> assertEquals(0, host.jdbc.queryForObject("SELECT COUNT(*) FROM host_work", Integer.class)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void synchronizationOnlyCommerceReadCannotAuthorizeTypedOrEventWrites(boolean autoCommit) {
        try (var host = new MysqlDatabase(); var commerce = new MysqlDatabase()) {
            commerce.migrate();
            var ds = commerceSource(commerce, autoCommit);
            var repo = new SysBusinessTypeRepository(ds);
            var events = new JdbcCommerceEventPersistence(ds);
            var denied = new boolean[3];
            new TransactionTemplate(new DataSourceTransactionManager(host.ds)).execute(status -> {
                assertTrue(repo.findById("A", 1).isEmpty());
                assertTrue(TransactionSynchronizationManager.hasResource(ds), "read must bind commerce connection");
                denied[0] = rejected(() -> repo.insert(row()));
                denied[1] = rejected(() -> events.append(event()));
                denied[2] = rejected(() -> events.recordInbound(new InboundFact(
                        "A", "PAYMENT", "fact", "PAID", "order", "digest", Instant.EPOCH)));
                status.setRollbackOnly();
                return null;
            });
            assertAll(() -> assertTrue(denied[0], "typed insert denied"),
                    () -> assertTrue(denied[1], "event append denied"),
                    () -> assertTrue(denied[2], "inbound fact denied"),
                    () -> noWrites(commerce));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void synchronizationOnlyCommerceReadCannotClaimAccountLockLifetime(boolean autoCommit) {
        try (var host = new MysqlDatabase(); var commerce = new MysqlDatabase()) {
            commerce.migrate();
            commerce.jdbc.execute(Rows.mch_subscription_account("A"));
            var ds = commerceSource(commerce, autoCommit);
            var denied = new AtomicBoolean();
            new TransactionTemplate(new DataSourceTransactionManager(host.ds)).execute(status -> {
                new JdbcTemplate(ds).queryForObject("SELECT COUNT(*) FROM mch_subscription_account", Integer.class);
                assertTrue(TransactionSynchronizationManager.hasResource(ds));
                denied.set(rejected(() -> new JdbcSubscriptionAccountRepository(ds)
                        .lockSubscriptionAccount(new StoreRef("A", "x"))));
                status.setRollbackOnly();
                return null;
            });
            assertAll(() -> assertTrue(denied.get(), "FOR UPDATE requires a commerce physical transaction"),
                    () -> noWrites(commerce),
                    () -> assertEquals(0L, new JdbcSubscriptionAccountRepository(commerce.ds)
                            .readEntitlementVersion(new StoreRef("A", "x"))));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void standaloneAndSharedPrimaryWorkWithEitherConnectionDefault(boolean autoCommit) {
        try (var commerce = new MysqlDatabase()) {
            commerce.migrate();
            var ds = commerceSource(commerce, autoCommit);
            var manager = new org.springframework.jdbc.support.JdbcTransactionManager(ds);
            var uow = new JdbcCommerceUnitOfWork(ds, manager);
            var repo = new SysBusinessTypeRepository(ds);
            uow.required(() -> { repo.insert(row()); return null; });
            assertEquals(1, commerce.jdbc.queryForObject("SELECT COUNT(*) FROM sys_business_type", Integer.class));
            new TransactionTemplate(manager).execute(status -> {
                uow.required(() -> new JdbcCommerceEventPersistence(ds).append(event()));
                status.setRollbackOnly();
                return null;
            });
            assertEquals(0, commerce.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_outbox", Integer.class));
        }
    }

    @Test
    void retainedCommerceTransactionCannotJoinASecondPhysicalDataSourceTransaction() {
        try (var host = new MysqlDatabase(); var commerce = new MysqlDatabase()) {
            commerce.migrate();
            commerce.jdbc.execute(Rows.mch_subscription_account("A"));
            var uow = new JdbcCommerceUnitOfWork(commerce.ds, new DataSourceTransactionManager(commerce.ds));
            var denied = new boolean[3];
            var entered = new AtomicBoolean();
            uow.required(() -> {
                new TransactionTemplate(new DataSourceTransactionManager(host.ds)).execute(status -> {
                    denied[0] = rejected(() -> uow.required(() -> {
                        entered.set(true);
                        new SysBusinessTypeRepository(commerce.ds).insert(row());
                        return null;
                    }));
                    denied[1] = rejected(() -> new JdbcCommerceEventPersistence(commerce.ds).append(event()));
                    denied[2] = rejected(() -> new JdbcSubscriptionAccountRepository(commerce.ds)
                            .lockSubscriptionAccount(new StoreRef("A", "x")));
                    status.setRollbackOnly();
                    return null;
                });
                return null;
            });
            assertAll(() -> assertTrue(denied[0], "retained outer holder cannot authorize UoW"),
                    () -> assertFalse(entered.get(), "mixed-resource callback never entered"),
                    () -> assertTrue(denied[1], "retained outer holder cannot authorize event write"),
                    () -> assertTrue(denied[2], "retained outer holder cannot authorize account lock"),
                    () -> noWrites(commerce));
            // Rejection leaves the normal one-resource path available.
            uow.required(() -> new SysBusinessTypeRepository(commerce.ds).insert(row()));
            assertEquals(1, commerce.jdbc.queryForObject("SELECT COUNT(*) FROM sys_business_type", Integer.class));
        }
    }

    @Test
    void caughtNestedFailureStillRollsBackSharedPrimaryTransaction() {
        try (var commerce = new MysqlDatabase()) {
            commerce.migrate();
            var uow = new JdbcCommerceUnitOfWork(commerce.ds, new DataSourceTransactionManager(commerce.ds));
            assertThrows(UnexpectedRollbackException.class, () -> uow.required(() -> {
                new SysBusinessTypeRepository(commerce.ds).insert(row());
                try {
                    uow.required(() -> {
                        new JdbcCommerceEventPersistence(commerce.ds).append(event());
                        throw new IllegalStateException("nested failure");
                    });
                } catch (IllegalStateException expected) { /* outer callback continues */ }
                return null;
            }));
            noWrites(commerce);
        }
    }
}
