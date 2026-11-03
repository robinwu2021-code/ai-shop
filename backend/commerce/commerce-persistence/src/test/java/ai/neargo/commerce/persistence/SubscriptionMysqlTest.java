package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.subscription.*;
import ai.neargo.commerce.core.access.*;
import ai.neargo.commerce.core.subscription.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class SubscriptionMysqlTest {
    static final StoreRef STORE = new StoreRef("tenant-a", "store");
    static final ActorContext OWNER =
            new ActorContext("tenant-a", SubjectKind.ACCOUNT, "owner", "session");
    static final Instant NOV1 = Instant.parse("2026-11-01T00:00:00Z");
    static final long MONTH = Duration.ofDays(30).toMillis();
    final CommerceIdGenerator ids = ns -> UUID.randomUUID().toString();

    record Services(
            JdbcSubscriptionRepository repo,
            StoreProvisioningService create,
            SubscriptionService service,
            EndScheduleService schedule) {}

    Services services(MysqlDatabase db, JdbcCatalogFixture f, Instant now) {
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var repo = new JdbcSubscriptionRepository(db.ds, ids);
        var events = new JdbcCommerceEventPersistence(db.ds);
        var schedule = new EndScheduleService(repo, f.uow, events, ids, clock);
        var service = new SubscriptionService(repo, f.repo, f.uow, schedule, events, ids, clock);
        var create =
                new StoreProvisioningService(
                        service,
                        (actor, cmd) ->
                                new StoreProvisioningService.CreationFacts(
                                        new StoreSnapshot(
                                                cmd.store(),
                                                "entity",
                                                "RETAIL",
                                                StoreStatus.ACTIVE,
                                                CommerceModel.STORE_V1),
                                        "CNY"));
        return new Services(repo, create, service, schedule);
    }

    JdbcCatalogFixture setup(MysqlDatabase db) {
        db.migrate();
        var f = new JdbcCatalogFixture(db);
        f.setup();
        f.plans();
        f.facade.publishIndustry(f.ops, f.publication(List.of(), 0));
        f.facade.saveBusinessType(
                f.ops,
                new ai.neargo.commerce.api.catalog.BusinessTypeDraft(
                        "RETAIL", "零售", 1, true, 2, "enable"));
        return f;
    }

    InitializeStoreCommand init() {
        return new InitializeStoreCommand(STORE, "RETAIL", "init");
    }

    SubscriptionService.Delivery pack(String ref) {
        return new SubscriptionService.Delivery(
                ref, SubscriptionService.Origin.GRANT, null, "POS", MONTH, 0, 0, "CNY", false);
    }

    SubscriptionService.Delivery plan(String ref) {
        return new SubscriptionService.Delivery(
                ref,
                SubscriptionService.Origin.ORDER,
                "pro-1",
                null,
                MONTH,
                1234,
                100,
                "CNY",
                false);
    }

    @Test
    void rollbackDuplicateConflictAndCalendarRenewal() {
        try (var db = new MysqlDatabase()) {
            var f = setup(db);
            var svc = services(db, f, NOV1);
            assertThrows(
                    IllegalStateException.class, () -> svc.create.initializeFree(OWNER, init()));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            f.uow.required(
                                    () -> {
                                        svc.create.initializeFree(OWNER, init());
                                        throw new IllegalStateException("outer failure");
                                    }));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "select count(*) from mch_subscription_account", Integer.class));
            f.uow.required(() -> svc.create.initializeFree(OWNER, init()));
            f.uow.required(() -> svc.create.initializeFree(OWNER, init()));
            svc.service.deliver(STORE, "one", List.of(plan("pro"), pack("pack")));
            assertEquals("2026-12-31T00:00:00Z", tail(db));
            var later = services(db, f, NOV1.plus(Duration.ofDays(10)));
            later.service.deliver(STORE, "two", List.of(plan("renew")));
            assertEquals("2027-01-30T00:00:00Z", tail(db));
            var view = f.uow.required(() -> later.create.initializeFree(OWNER, init()));
            assertEquals(4, view.sources().size());
            assertEquals(
                    2668,
                    view.sources().stream().mapToLong(row -> row.valuationBasis().minor()).sum());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new SubscriptionService.Delivery(
                                    "gift",
                                    SubscriptionService.Origin.ORDER,
                                    "pro-1",
                                    null,
                                    MONTH,
                                    1,
                                    0,
                                    "CNY",
                                    true));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            later.service.deliver(
                                    STORE,
                                    "fx",
                                    List.of(
                                            new SubscriptionService.Delivery(
                                                    "usd",
                                                    SubscriptionService.Origin.ORDER,
                                                    "pro-1",
                                                    null,
                                                    MONTH,
                                                    10,
                                                    0,
                                                    "USD",
                                                    false))));
            svc.service.deliver(STORE, "two", List.of(plan("renew")));
            assertThrows(
                    RuntimeException.class,
                    () -> svc.service.deliver(STORE, "two", List.of(pack("changed"))));
            assertEquals(
                    3,
                    db.jdbc.queryForObject(
                            "select entitlement_version from mch_subscription_account",
                            Integer.class));
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "select count(*) from mch_store_end_grant where"
                                    + " entitlement_code='BASE'",
                            Integer.class));
            assertThrows(
                    RuntimeException.class,
                    () -> svc.service.deliver(STORE, "past", List.of(pack("old"))));
            db.jdbc.update(
                    "update mch_subscription_account set pending_change_order_no='reserved'");
            assertThrows(
                    RuntimeException.class,
                    () -> later.service.deliver(STORE, "busy", List.of(pack("busy"))));
            assertEquals(
                    4,
                    db.jdbc.queryForObject(
                            "select count(*) from mch_subscription_period", Integer.class));
        }
    }

    String tail(MysqlDatabase db) {
        return Instant.ofEpochMilli(
                        db.jdbc.queryForObject(
                                "select max(end_at) from mch_store_end_grant where state='NORMAL'"
                                        + " and entitlement_code='POS'",
                                Long.class))
                .toString();
    }

    @Test
    void independentJvmWritersSerializeAndDeduplicateTheSameOperation() throws Exception {
        try (var db = new MysqlDatabase();
                var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            var f = setup(db);
            var svc = services(db, f, NOV1);
            f.uow.required(() -> svc.create.initializeFree(OWNER, init()));
            var workers = new ArrayList<Process>();
            try {
                for (String ref : List.of("a", "b", "b")) {
                    var worker =
                            new ProcessBuilder(
                                            java.nio.file.Path.of(
                                                            System.getProperty("java.home"),
                                                            "bin",
                                                            "java")
                                                    .toString(),
                                            "-cp",
                                            System.getProperty(
                                                    "surefire.test.class.path",
                                                    System.getProperty("java.class.path")),
                                            SubscriptionMysqlTest.class.getName(),
                                            db.name,
                                            ref)
                                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                                    .start();
                    workers.add(worker);
                }
                for (var worker : workers)
                    assertEquals(
                            "READY",
                            readers.submit(() -> worker.inputReader().readLine())
                                    .get(15, TimeUnit.SECONDS));
                for (var worker : workers) {
                    worker.getOutputStream().write(1);
                    worker.getOutputStream().flush();
                }
                for (var worker : workers) {
                    assertTrue(worker.waitFor(20, TimeUnit.SECONDS));
                    assertEquals(0, worker.exitValue());
                }
            } finally {
                workers.stream().filter(Process::isAlive).forEach(Process::destroyForcibly);
            }
            assertEquals("2026-12-31T00:00:00Z", tail(db));
            assertEquals(
                    3,
                    db.jdbc.queryForObject(
                            "select entitlement_version from mch_subscription_account",
                            Integer.class));
        }
    }

    /** Child process of the native concurrency test. Attaches only to this run's fixture. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].matches("commerce_test_[0-9a-f]{32}"))
            throw new IllegalArgumentException();
        String url =
                MysqlDatabase.required("COMMERCE_MYSQL_URL").replace("/?", "/" + args[0] + "?");
        var ds =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        url,
                        MysqlDatabase.required("COMMERCE_MYSQL_USER"),
                        MysqlDatabase.required("COMMERCE_MYSQL_PASSWORD"));
        var uow =
                new JdbcCommerceUnitOfWork(
                        ds,
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        var fixture = new SubscriptionMysqlTest();
        var clock = Clock.fixed(NOV1, ZoneOffset.UTC);
        var repo = new JdbcSubscriptionRepository(ds, fixture.ids);
        var events = new JdbcCommerceEventPersistence(ds);
        var schedule = new EndScheduleService(repo, uow, events, fixture.ids, clock);
        var service =
                new SubscriptionService(
                        repo,
                        new JdbcCatalogRepository(ds),
                        uow,
                        schedule,
                        events,
                        fixture.ids,
                        clock);
        System.out.println("READY");
        System.out.flush();
        if (System.in.read() != 1)
            throw new IllegalStateException("parent did not release workers");
        service.deliver(STORE, args[1], List.of(fixture.pack(args[1])));
    }

    @Test
    void consumedPrefixAndUnspentRemainderSurviveRebuildAndCatalogChange() {
        try (var db = new MysqlDatabase()) {
            var f = setup(db);
            var svc = services(db, f, NOV1);
            f.uow.required(() -> svc.create.initializeFree(OWNER, init()));
            svc.service.deliver(STORE, "pack-op", List.of(pack("gift")));
            // The store's own immutable permanent grant remains BASE. Newly advertised FREE
            // contains POS.
            db.jdbc.update(
                    "update sys_industry_plan set current_version_no='pro-1' where"
                            + " tier_code='FREE'");
            var cut = NOV1.plus(Duration.ofDays(10));
            var later = services(db, f, cut);
            f.uow.required(() -> later.create.initializeFree(OWNER, init()));
            later.service.deliver(STORE, "plan-op", List.of(plan("plan")));
            var current = f.uow.required(() -> later.repo.segments(STORE, "POS"));
            assertEquals(
                    List.of(NOV1, cut, cut.plusMillis(MONTH)),
                    current.stream().map(AccessSegment::startsAt).toList());
            assertEquals(
                    List.of(cut, cut.plusMillis(MONTH), NOV1.plusMillis(2 * MONTH)),
                    current.stream().map(AccessSegment::endsAt).toList());
            assertEquals(current.getFirst().sourceNo(), current.getLast().sourceNo());
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "select count(*) from mch_store_end_grant where state='SUPERSEDED'",
                            Integer.class));
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "select count(*) from mch_end_schedule_event where"
                                    + " operation_ref_no='plan-op' and"
                                    + " json_length(duration_check_snapshot)=2 and"
                                    + " json_length(before_snapshot)=1",
                            Integer.class));
            later.schedule.rebuild(STORE, Set.of("POS"), "noop", cut);
            later.schedule.rebuild(STORE, Set.of("POS"), "noop", cut);
            assertEquals(
                    3,
                    db.jdbc.queryForObject(
                            "select entitlement_version from mch_subscription_account",
                            Integer.class));
            assertEquals(
                    3,
                    db.jdbc.queryForObject(
                            "select count(*) from commerce_outbox where"
                                    + " aggregate_type='SUBSCRIPTION'",
                            Integer.class));
            assertThrows(
                    RuntimeException.class,
                    () -> later.schedule.rebuild(STORE, Set.of("BASE"), "noop", cut));
            assertThrows(
                    RuntimeException.class,
                    () -> later.schedule.rebuild(STORE, Set.of("POS"), "backdated", NOV1));
            assertEquals(
                    "free-1",
                    db.jdbc.queryForObject(
                            "select plan_version_no from mch_subscription_period where"
                                    + " stream_key='FREE'",
                            String.class));
        }
    }

    @Test
    void staleOuterReadSeesCommittedLatestScheduleAndPartialBatchRollsBack() throws Exception {
        try (var db = new MysqlDatabase();
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var f = setup(db);
            var svc = services(db, f, NOV1);
            f.uow.required(() -> svc.create.initializeFree(OWNER, init()));
            f.uow.required(
                    () -> {
                        assertEquals(
                                1,
                                db.jdbc.queryForObject(
                                        "select count(*) from mch_subscription_period",
                                        Integer.class));
                        var job =
                                pool.submit(
                                        () ->
                                                services(db, new JdbcCatalogFixture(db), NOV1)
                                                        .service
                                                        .deliver(STORE, "a", List.of(plan("a"))));
                        try {
                            job.get(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                        svc.service.deliver(STORE, "b", List.of(plan("b")));
                        return null;
                    });
            assertEquals("2026-12-31T00:00:00Z", tail(db));
            var invalid =
                    new SubscriptionService.Delivery(
                            "wrong",
                            SubscriptionService.Origin.ORDER,
                            "chain-1",
                            null,
                            MONTH,
                            0,
                            0,
                            "CNY",
                            false);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            svc.service.deliver(
                                    STORE, "broken-batch", List.of(pack("first"), invalid)));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "select count(*) from mch_subscription_period where"
                                    + " source_ref_no='first'",
                            Integer.class));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "select count(*) from commerce_event_inbox where"
                                    + " event_id='broken-batch'",
                            Integer.class));
            assertEquals(
                    3,
                    db.jdbc.queryForObject(
                            "select count(*) from mch_end_schedule_event", Integer.class));
        }
    }

    @Test
    void unexplainedExistingGrantCannotBeLostOnRebuild() {
        try (var db = new MysqlDatabase()) {
            var f = setup(db);
            var services = services(db, f, NOV1);
            f.uow.required(() -> services.create.initializeFree(OWNER, init()));
            services.service.deliver(STORE, "first", List.of(pack("pack")));
            db.jdbc.update(
                    "update mch_subscription_period set source_policy_snapshot=JSON_OBJECT() where"
                        + " source_ref_no='pack'");
            assertThrows(
                    IllegalStateException.class,
                    () -> services.schedule.rebuild(STORE, Set.of("POS"), "review", NOV1));
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "select count(*) from mch_store_end_grant where entitlement_code='POS'"
                                + " and state='NORMAL'",
                            Integer.class));
        }
    }
}
