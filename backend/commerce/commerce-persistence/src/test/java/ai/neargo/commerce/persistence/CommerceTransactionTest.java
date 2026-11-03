package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

class CommerceTransactionTest {
    Object uow(MysqlDatabase db) {
        return new JdbcCommerceUnitOfWork(db.ds, new DataSourceTransactionManager(db.ds));
    }

    Object required(Object u, Supplier<?> fn) {
        return ((ai.neargo.commerce.core.spi.CommerceUnitOfWork) u).required(fn);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void failureAtEveryDeliveryStepRollsBackAllCommerceAndHostWork(int failAfter) {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            Object u = uow(db);
            db.jdbc.execute("CREATE TABLE host_work(id INT)");
            db.jdbc.execute(Rows.mch_subscription_credit_account("A"));
            db.jdbc.execute(Rows.mch_subscription_account("A"));
            var steps =
                    java.util.List.<Runnable>of(
                            () -> db.jdbc.execute(Rows.mch_subscription_period("A")),
                            () -> db.jdbc.execute(Rows.mch_store_end_grant("A")),
                            () ->
                                    db.jdbc.update(
                                            "UPDATE mch_subscription_credit_account SET"
                                                + " balance_minor=100 WHERE tenant_no='A'"),
                            () -> db.jdbc.execute(Rows.mch_subscription_credit_lot("A")),
                            () -> db.jdbc.execute(Rows.mch_subscription_credit_ledger("A")),
                            () -> db.jdbc.execute(Rows.mch_end_schedule_event("A")),
                            () -> db.jdbc.execute(Rows.commerce_outbox("A")),
                            () ->
                                    db.jdbc.update(
                                            "UPDATE mch_subscription_account SET"
                                                + " entitlement_version=1 WHERE tenant_no='A'"));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            new TransactionTemplate(new DataSourceTransactionManager(db.ds))
                                    .execute(
                                            status -> {
                                                db.jdbc.execute("INSERT INTO host_work VALUES(1)");
                                                required(
                                                        u,
                                                        () -> {
                                                            for (int i = 0; i < steps.size(); i++) {
                                                                steps.get(i).run();
                                                                if (i + 1 == failAfter)
                                                                    throw new IllegalStateException(
                                                                            "injected delivery"
                                                                                + " failure at "
                                                                                    + failAfter);
                                                            }
                                                            return null;
                                                        });
                                                return null;
                                            }));
            for (String table :
                    java.util.List.of(
                            "host_work",
                            "mch_subscription_period",
                            "mch_store_end_grant",
                            "mch_subscription_credit_lot",
                            "mch_subscription_credit_ledger",
                            "mch_end_schedule_event",
                            "commerce_outbox"))
                assertEquals(
                        0, db.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
            assertEquals(
                    0L,
                    db.jdbc.queryForObject(
                            "SELECT balance_minor FROM mch_subscription_credit_account WHERE"
                                + " tenant_no='A'",
                            Long.class));
            assertEquals(
                    0L,
                    db.jdbc.queryForObject(
                            "SELECT entitlement_version FROM mch_subscription_account WHERE"
                                + " tenant_no='A'",
                            Long.class));
        }
    }

    @Test
    void accountLockIsHeldUntilOuterHostTransactionCommits() throws Exception {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            db.jdbc.execute(Rows.mch_subscription_account("A"));
            var u = new JdbcCommerceUnitOfWork(db.ds, new DataSourceTransactionManager(db.ds));
            var repo = new JdbcSubscriptionAccountRepository(db.ds);
            var started = new java.util.concurrent.CountDownLatch(1);
            var update =
                    new java.util.concurrent.atomic.AtomicReference<
                            java.util.concurrent.CompletableFuture<Void>>();
            new TransactionTemplate(new DataSourceTransactionManager(db.ds))
                    .execute(
                            status -> {
                                u.required(
                                        () ->
                                                repo.lockSubscriptionAccount(
                                                        new ai.neargo.commerce.api.common.StoreRef(
                                                                "A", "x")));
                                update.set(
                                        java.util.concurrent.CompletableFuture.runAsync(
                                                () -> {
                                                    started.countDown();
                                                    db.jdbc.update(
                                                            "UPDATE mch_subscription_account SET"
                                                                + " entitlement_version=1 WHERE"
                                                                + " tenant_no='A'");
                                                }));
                                assertDoesNotThrow(
                                        () ->
                                                assertTrue(
                                                        started.await(
                                                                2,
                                                                java.util.concurrent.TimeUnit
                                                                        .SECONDS)));
                                assertThrows(
                                        java.util.concurrent.TimeoutException.class,
                                        () ->
                                                update.get()
                                                        .get(
                                                                150,
                                                                java.util.concurrent.TimeUnit
                                                                        .MILLISECONDS));
                                return null;
                            });
            update.get().get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(
                    1L,
                    repo.readEntitlementVersion(
                            new ai.neargo.commerce.api.common.StoreRef("A", "x")));
        }
    }

    @Test
    void rejectsUnrelatedManagerAndLocksOutsideTransaction() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new JdbcCommerceUnitOfWork(
                                    db.ds, new DataSourceTransactionManager(db.admin)));
            var repo = new JdbcSubscriptionAccountRepository(db.ds);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            repo.lockSubscriptionAccount(
                                    new ai.neargo.commerce.api.common.StoreRef("A", "x")));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            repo.readEntitlementVersion(
                                    new ai.neargo.commerce.api.common.StoreRef("", "x")));
        }
    }

    @Test
    void commitsAllParticipantsAndJoinsOuterRollback() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            Object u = uow(db);
            required(
                    u,
                    () -> {
                        db.jdbc.execute(Rows.mch_subscription_period("A"));
                        db.jdbc.execute(Rows.mch_store_end_grant("A"));
                        db.jdbc.execute(Rows.mch_subscription_credit_account("A"));
                        db.jdbc.execute(Rows.commerce_outbox("A"));
                        return 7;
                    });
            for (String table :
                    java.util.List.of(
                            "mch_subscription_period",
                            "mch_store_end_grant",
                            "mch_subscription_credit_account",
                            "commerce_outbox"))
                assertEquals(
                        1, db.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
            new TransactionTemplate(new DataSourceTransactionManager(db.ds))
                    .execute(
                            s -> {
                                required(
                                        u,
                                        () -> {
                                            db.jdbc.execute(Rows.mch_subscription_period("B"));
                                            return null;
                                        });
                                s.setRollbackOnly();
                                return null;
                            });
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mch_subscription_period", Integer.class));
        }
    }

    @Test
    void authoritativeVersionReadEscapesEarlierRepeatableReadSnapshot() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            db.jdbc.execute(Rows.mch_subscription_account("A"));
            Object u = uow(db);
            required(
                    u,
                    () -> {
                        assertEquals(
                                0L,
                                db.jdbc.queryForObject(
                                        "SELECT entitlement_version FROM mch_subscription_account"
                                            + " WHERE tenant_no='A'",
                                        Long.class));
                        java.util.concurrent.CompletableFuture.runAsync(
                                        () ->
                                                db.jdbc.update(
                                                        "UPDATE mch_subscription_account SET"
                                                            + " entitlement_version=17 WHERE"
                                                            + " tenant_no='A'"))
                                .join();
                        assertEquals(
                                17L,
                                new JdbcSubscriptionAccountRepository(db.ds)
                                        .readEntitlementVersion(
                                                new ai.neargo.commerce.api.common.StoreRef(
                                                        "A", "x")));
                        return null;
                    });
        }
    }

    @Test
    void lockingReadReturnsCurrentVersionDespiteEarlierRepeatableReadSnapshot() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            db.jdbc.execute(Rows.mch_subscription_account("A"));
            Object u = uow(db);
            required(
                    u,
                    () -> {
                        assertEquals(
                                0L,
                                db.jdbc.queryForObject(
                                        "SELECT entitlement_version FROM mch_subscription_account"
                                            + " WHERE tenant_no='A'",
                                        Long.class));
                        var future =
                                java.util.concurrent.CompletableFuture.runAsync(
                                        () ->
                                                db.jdbc.update(
                                                        "UPDATE mch_subscription_account SET"
                                                            + " entitlement_version=9 WHERE"
                                                            + " tenant_no='A'"));
                        future.join();
                        var repo = new JdbcSubscriptionAccountRepository(db.ds);
                        var snapshot =
                                repo.lockSubscriptionAccount(
                                        new ai.neargo.commerce.api.common.StoreRef("A", "x"));
                        assertEquals(9L, snapshot.entitlementVersion());
                        return null;
                    });
        }
    }
}
