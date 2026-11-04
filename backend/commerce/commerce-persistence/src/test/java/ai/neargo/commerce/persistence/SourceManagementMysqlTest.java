package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.MallException;
import ai.neargo.commerce.api.subscription.*;
import ai.neargo.commerce.core.access.EndScheduleService;
import ai.neargo.commerce.core.subscription.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class SourceManagementMysqlTest {
    static final StoreRef STORE = MallOrderConcurrencyMysqlTest.STORE;

    @Test
    void zeroGrantAndRevokeAreAuditedIdempotentAndPreserveIndependentUsedHistory() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new MallOrderConcurrencyMysqlTest.Fixture(db);
            var repo = new JdbcSubscriptionRepository(db.ds, f.c.ids);
            var events = new JdbcCommerceEventPersistence(db.ds);
            var clock = new java.util.concurrent.atomic.AtomicLong(2000);
            Clock businessClock =
                    new Clock() {
                        public ZoneId getZone() {
                            return ZoneOffset.UTC;
                        }

                        public Clock withZone(ZoneId z) {
                            return this;
                        }

                        public Instant instant() {
                            return Instant.ofEpochMilli(clock.get());
                        }
                    };
            var schedule = new EndScheduleService(repo, f.c.uow, events, f.c.ids, businessClock);
            f.c.uow.required(
                    () -> {
                        schedule.persist(
                                repo.lock(STORE),
                                Set.of("BASE"),
                                "initialize",
                                Instant.ofEpochMilli(1000),
                                Set.of("free"),
                                Map.of(),
                                "buyer");
                        return null;
                    });
            var service =
                    new DefaultSourceManagementFacade(
                            repo,
                            f.c.repo,
                            f.c.uow,
                            s ->
                                    Optional.of(
                                            new StoreSnapshot(
                                                    s,
                                                    "merchant",
                                                    "RETAIL",
                                                    StoreStatus.ACTIVE,
                                                    CommerceModel.STORE_V1)),
                            (a, s, c) ->
                                    new AuthorizationDecision(
                                            a.subjectKind() == SubjectKind.OPERATOR
                                                    && "trusted"
                                                            .equals(a.accountSessionFingerprint()),
                                            "OPS",
                                            Optional.empty()),
                            f.c.readiness,
                            schedule,
                            events,
                            f.c.ids,
                            businessClock);
            var grant =
                    new GrantCommand(
                            STORE, "grant.1", "POS", 2, Optional.empty(), "Service recovery");
            assertThrows(
                    MallException.class,
                    () -> service.grant(MallOrderConcurrencyMysqlTest.BUYER, grant));
            var view = service.grant(f.c.ops, grant);
            assertNotNull(view);
            assertEquals(view, service.grant(f.c.ops, grant));
            assertThrows(
                    RuntimeException.class,
                    () ->
                            service.grant(
                                    f.c.ops,
                                    new GrantCommand(
                                            STORE,
                                            "grant.1",
                                            "POS",
                                            3,
                                            Optional.empty(),
                                            "Changed payload")));
            assertThrows(
                    MallException.class,
                    () ->
                            service.grant(
                                    f.c.ops,
                                    new GrantCommand(
                                            STORE,
                                            "plan-grant",
                                            "POS",
                                            2,
                                            Optional.of("pro-1"),
                                            "No partial Plan grant")));
            assertThrows(
                    MallException.class,
                    () ->
                            service.revoke(
                                    f.c.ops,
                                    new RevokeCommand(
                                            STORE,
                                            "free",
                                            "free-revoke",
                                            "Forbidden",
                                            view.entitlementVersion())));
            db.jdbc.update("UPDATE mch_subscription_account SET pending_change_order_no='busy'");
            assertThrows(
                    MallException.class,
                    () ->
                            service.grant(
                                    f.c.ops,
                                    new GrantCommand(
                                            STORE,
                                            "blocked-grant",
                                            "KDS",
                                            2,
                                            Optional.empty(),
                                            "Busy")));
            var firstGift =
                    view.sources().stream()
                            .filter(v -> v.originNo().equals("grant.1"))
                            .findFirst()
                            .orElseThrow();
            assertThrows(
                    MallException.class,
                    () ->
                            service.revoke(
                                    f.c.ops,
                                    new RevokeCommand(
                                            STORE,
                                            firstGift.sourceNo(),
                                            "blocked-revoke",
                                            "Busy",
                                            view.entitlementVersion())));
            db.jdbc.update("UPDATE mch_subscription_account SET pending_change_order_no=NULL");
            new SubscriptionService(
                            repo, f.c.repo, f.c.uow, schedule, events, f.c.ids, businessClock)
                    .deliver(
                            STORE,
                            "paid-end-delivery",
                            List.of(
                                    new SubscriptionService.Delivery(
                                            "paid-end",
                                            SubscriptionService.Origin.ORDER,
                                            null,
                                            "POS",
                                            86400000,
                                            100,
                                            0,
                                            "CNY",
                                            false)));
            var current =
                    service.subscription(f.c.ops, new SubscriptionQuery(STORE, Optional.empty()));
            var gifted =
                    current.sources().stream()
                            .filter(s -> s.originNo().equals("grant.1"))
                            .findFirst()
                            .orElseThrow();
            var paid =
                    current.sources().stream()
                            .filter(s -> s.originNo().equals("paid-end"))
                            .findFirst()
                            .orElseThrow();
            assertThrows(
                    MallException.class,
                    () ->
                            service.revoke(
                                    f.c.ops,
                                    new RevokeCommand(
                                            STORE,
                                            paid.sourceNo(),
                                            "paid-revoke",
                                            "Must use refund",
                                            current.entitlementVersion())));
            clock.set(1002000);
            var command =
                    new RevokeCommand(
                            STORE,
                            gifted.sourceNo(),
                            "revoke.1",
                            "Accepted support decision",
                            current.entitlementVersion());
            var revoked = service.revoke(f.c.ops, command);
            assertNotNull(revoked);
            assertEquals(revoked, service.revoke(f.c.ops, command));
            assertThrows(
                    RuntimeException.class,
                    () ->
                            service.revoke(
                                    f.c.ops,
                                    new RevokeCommand(
                                            STORE,
                                            gifted.sourceNo(),
                                            "revoke.1",
                                            "Changed reason",
                                            current.entitlementVersion())));
            var source =
                    revoked.sources().stream()
                            .filter(s -> s.sourceNo().equals(gifted.sourceNo()))
                            .findFirst()
                            .orElseThrow();
            assertEquals("REVOKED", source.lifecycle());
            assertEquals(1000000, source.consumedMillis());
            assertEquals(171800000, source.revokedDurationMillis());
            var slices =
                    revoked.segments().stream()
                            .filter(s -> s.sourceNo().equals(gifted.sourceNo()))
                            .toList();
            assertEquals(1, slices.size());
            assertEquals(Instant.ofEpochMilli(1002000), slices.getFirst().endsAt());
            assertEquals(
                    86400000,
                    revoked.segments().stream()
                            .filter(s -> s.sourceNo().equals(paid.sourceNo()))
                            .mapToLong(s -> s.endsAt().toEpochMilli() - s.startsAt().toEpochMilli())
                            .sum());
            assertEquals(
                    2,
                    db.jdbc.queryForObject(
                            "SELECT count(*) FROM commerce_outbox WHERE event_type IN"
                                + " ('OPS_SOURCE_GRANTED','OPS_SOURCE_REVOKED')",
                            Integer.class));
            clock.set(999999999L); // Expiry does not erase original paid economics.
            assertThrows(
                    MallException.class,
                    () ->
                            service.revoke(
                                    f.c.ops,
                                    new RevokeCommand(
                                            STORE,
                                            paid.sourceNo(),
                                            "expired-paid-revoke",
                                            "Still refund only",
                                            revoked.entitlementVersion())));
            db.jdbc.update(
                    "UPDATE mch_subscription_period SET revoked_by_no=NULL WHERE period_no=?",
                    gifted.sourceNo());
            assertThrows(
                    RuntimeException.class,
                    () ->
                            service.subscription(
                                    f.c.ops, new SubscriptionQuery(STORE, Optional.empty())));
            assertThrows(
                    RuntimeException.class,
                    () ->
                            f.c.uow.required(
                                    () ->
                                            new JdbcEndAccessRepository(db.ds)
                                                    .lockAndRead(STORE, "POS", "POS", "client")));
            assertThrows(RuntimeException.class, () -> f.quote());
        }
    }

    @Test
    void currentRevocationProofCannotHideBehindAnOlderRepeatableReadSnapshot() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new MallOrderConcurrencyMysqlTest.Fixture(db);
            var repo = new JdbcSubscriptionRepository(db.ds, f.c.ids);
            var manager =
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.ds);
            var outer = new org.springframework.transaction.support.TransactionTemplate(manager);
            outer.setIsolationLevel(
                    org.springframework.transaction.TransactionDefinition
                            .ISOLATION_REPEATABLE_READ);
            var newer = new org.springframework.transaction.support.TransactionTemplate(manager);
            newer.setPropagationBehavior(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            outer.execute(
                    status -> {
                        db.jdbc.queryForObject(
                                "SELECT count(*) FROM mch_subscription_period WHERE"
                                    + " state='REVOKED'",
                                Integer.class);
                        newer.execute(
                                inner -> {
                                    db.jdbc.update(
                                            "UPDATE mch_subscription_period SET state='REVOKED'"
                                                + " WHERE period_no='free'");
                                    return null;
                                });
                        assertThrows(
                                RuntimeException.class, () -> repo.verifyRevokedEvidence(STORE));
                        return null;
                    });
        }
    }
}
