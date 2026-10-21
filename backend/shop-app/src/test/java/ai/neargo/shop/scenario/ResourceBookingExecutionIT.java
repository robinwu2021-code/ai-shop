package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Demand;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Interval;
import ai.neargo.shop.merchant.policy.ResourcePlanCodec;
import ai.neargo.shop.merchant.service.ResourceAdmissionService.Qualification;
import ai.neargo.shop.merchant.service.ResourceCommandService;
import ai.neargo.shop.merchant.service.ResourceCommandService.Accept;
import ai.neargo.shop.merchant.service.ResourceCommandService.Action;
import ai.neargo.shop.merchant.service.ResourceCommandService.Receipt;
import ai.neargo.shop.merchant.service.ResourceCommandService.Scope;
import ai.neargo.shop.merchant.service.ResourceCommandService.Status;
import ai.neargo.shop.merchant.service.ResourceCommandService.Target;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.AbstractDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real owner/admission/capacity transactions; only an explicitly registered test source may authorize. */
class ResourceBookingExecutionIT {
    private static final Instant NOW = Instant.parse("2026-10-21T08:00:00Z");
    private static final Scope S = new Scope("MAIN", "E", "S");
    private static final String SERVICE = "ai.neargo.shop.merchant.service.ResourceBookingService";
    private JdbcTemplate sql;
    private String url;
    private final TestClock clock = new TestClock();
    private final RegisteredSource source = new RegisteredSource();

    @Configuration(proxyBeanMethods = false)
    @MapperScan("ai.neargo.shop.merchant.mapper")
    static class Mappers {
    }

    @BeforeEach
    void database() throws Exception {
        String admin = System.getProperty("resource-booking.test.admin-url");
        assertThat(admin).isNotBlank();
        String name = "booking_execution_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(admin, "root", ""))
                .execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        url = admin.replace("/mysql?", "/" + name + "?");
        var ds = new DriverManagerDataSource(url, "root", "");
        sql = new JdbcTemplate(ds);
        sql.execute("CREATE TABLE mch_entity (tenant_no VARCHAR(32),entity_no VARCHAR(32),status VARCHAR(24),"
                + "archived_at DATETIME(3),deleted INT,PRIMARY KEY(tenant_no,entity_no))");
        sql.execute("CREATE TABLE mch_store (tenant_no VARCHAR(32),entity_no VARCHAR(32),store_no VARCHAR(32),"
                + "status VARCHAR(24),deleted INT,PRIMARY KEY(tenant_no,entity_no,store_no))");
        sql.execute("INSERT INTO mch_entity VALUES('MAIN','E','ACTIVE',NULL,0)");
        sql.execute("INSERT INTO mch_store VALUES('MAIN','E','S','ACTIVE',0),('MAIN','E','OTHER','ACTIVE',0)");
        try (var c = ds.getConnection()) {
            for (String migration : List.of("V532__shared_personnel_foundation.sql",
                    "V533__shared_resource_identity.sql", "V534__shared_schedule_rules.sql",
                    "V535__shared_resource_booking_foundation.sql")) {
                ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/" + migration));
            }
        }
        sql.execute("INSERT INTO mch_personnel(entity_no,created_at,updated_at,personnel_no,name,status) "
                + "VALUES('E',NOW(),NOW(),'P','Person','ACTIVE')");
        sql.execute("INSERT INTO mch_resource(entity_no,created_at,updated_at,resource_no,resource_type,"
                + "personnel_no,name,capacity,status) VALUES('E',NOW(),NOW(),'R','STAFF','P','Staff',1,'ACTIVE')");
        for (String store : List.of("S", "OTHER")) {
            sql.update("INSERT INTO mch_personnel_store(entity_no,created_at,updated_at,assignment_no,"
                    + "personnel_no,store_no,valid_from,status,public_visible,sort_order) "
                    + "VALUES('E',NOW(),NOW(),?,'P',?,'2020-01-01','ACTIVE',1,0)", "A" + store, store);
            sql.update("INSERT INTO mch_schedule_rule(entity_no,created_at,updated_at,rule_no,resource_no,store_no,"
                    + "timezone,weekday_mask,start_minute,end_minute,end_day_offset,valid_from,status) "
                    + "VALUES('E',NOW(),NOW(),?,'R',?,'UTC',127,540,1020,0,'2020-01-01','ACTIVE')", "W" + store, store);
        }
        // Overlapping cross-store rules fail closed; contention test switches requested store between executions.
        sql.execute("UPDATE mch_schedule_rule SET status='INACTIVE' WHERE store_no='OTHER'");
        physical("B", 1);
        physical("C", 1);
        String schema;
        try (var stream = new ClassPathResource("schema-test.sql").getInputStream()) {
            schema = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String table : List.of("sys_outbox", "sys_outbox_stream", "sys_outbox_delivery")) {
            var matcher = java.util.regex.Pattern.compile("CREATE TABLE IF NOT EXISTS " + table
                    + "\\s*\\([\\s\\S]*?\\n\\);",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(schema);
            assertThat(matcher.find()).isTrue();
            sql.execute(matcher.group());
        }
        login();
    }

    private void physical(String no, int capacity) {
        sql.update("INSERT INTO mch_resource(entity_no,created_at,updated_at,resource_no,resource_type,store_no,"
                + "name,capacity,status) VALUES('E',NOW(),NOW(),?,'ROOM','S',?,?, 'ACTIVE')", no, no, capacity);
        sql.update("INSERT INTO mch_schedule_rule(entity_no,created_at,updated_at,rule_no,resource_no,store_no,"
                + "timezone,weekday_mask,start_minute,end_minute,end_day_offset,valid_from,status) "
                + "VALUES('E',NOW(),NOW(),?,?,'S','UTC',127,540,1020,0,'2020-01-01','ACTIVE')", "W" + no, no);
    }

    private ApplicationContextRunner runner() {
        try {
            return new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class,
                    Mappers.class, Class.forName("ai.neargo.shop.merchant.config.PersonnelDataScopeRegistration"),
                    Class.forName("ai.neargo.shop.merchant.service.impl.ResourceCommandServiceImpl"),
                    Class.forName("ai.neargo.shop.merchant.service.impl.ResourceAdmissionServiceImpl"),
                    Class.forName("ai.neargo.shop.merchant.service.impl.ResourceSourceAuthorizationServiceImpl"),
                    Class.forName("ai.neargo.shop.merchant.service.impl.ResourceBookingServiceImpl"))
                    .withBean(AuditMetaObjectHandler.class).withBean(Clock.class, () -> clock)
                    .withBean(ObjectMapper.class, JsonMapper::new)
                    .withBean(ResourceSourceAuthorityPort.class, () -> source)
                    .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=root",
                            "spring.datasource.password=", "shop.resource.publication.archive-only=true");
        } catch (ClassNotFoundException ex) {
            throw new AssertionError("booking execution service is missing", ex);
        }
    }

    @AfterEach
    void clear() {
        DataScopeContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void twoResourceReserveIsAtomicAndOriginalReceiptNeverChanges() {
        runner().run(ctx -> {
            var accepted = accept(ctx, S, Action.RESERVE, "A", 0, 1, null, "B", "R");
            var receipt = execute(ctx, accepted);
            assertThat(receipt.status()).isEqualTo(Status.SUCCEEDED);
            assertThat(number("SELECT COUNT(*) FROM mch_booking_hold")).isOne();
            assertThat(number("SELECT COUNT(*) FROM mch_booking_hold_unit WHERE released_at IS NULL")).isEqualTo(4);
            assertThat(number("SELECT SUM(held_qty) FROM mch_resource_bucket")).isEqualTo(4);
            assertThat(sql.queryForObject("SELECT expiration_policy FROM mch_booking_hold", String.class))
                    .isEqualTo("SOURCE_COORDINATED");
            assertThat(sql.queryForObject("SELECT expires_at FROM mch_booking_hold", LocalDateTime.class))
                    .isEqualTo(LocalDateTime.of(2026, 10, 21, 8, 15));
            assertThat(execute(ctx, accepted)).isEqualTo(receipt);
            assertThat(number("SELECT COUNT(*) FROM sys_outbox")).isOne();
            assertThat(sql.queryForObject("SELECT event_type FROM sys_outbox", String.class))
                    .isEqualTo("RESOURCE_BOOKING_CHANGED");
            String hold = current();
            execute(ctx, accept(ctx, S, Action.CONFIRM, "A", 1, 2, target(hold), "B", "R"));
            assertThat(number("SELECT SUM(held_qty) FROM mch_resource_bucket")).isZero();
            assertThat(number("SELECT SUM(confirmed_qty) FROM mch_resource_bucket")).isEqualTo(4);
            assertThat(execute(ctx, accepted)).isEqualTo(receipt);
        });
    }

    @Test
    void secondResourceShortageRollsBackFirstResourceAndAllNewRows() {
        runner().run(ctx -> {
            execute(ctx, accept(ctx, S, Action.RESERVE, "TAKEN", 0, 1, null, "R"));
            var command = accept(ctx, S, Action.RESERVE, "LOSER", 0, 1, null, "B", "R");
            var rejected = execute(ctx, command);
            assertThat(rejected.status()).isEqualTo(Status.REJECTED);
            assertThat(rejected.rejectionCode()).isEqualTo("CAPACITY_UNAVAILABLE");
            assertThat(number("SELECT COUNT(*) FROM mch_resource_bucket WHERE resource_no='B'")).isZero();
            assertThat(number("SELECT COUNT(*) FROM mch_booking_hold")).isOne();
            assertThat(number("SELECT COUNT(*) FROM mch_booking_hold_unit")).isEqualTo(2);
            assertThat(number("SELECT owner_version FROM mch_booking_owner WHERE source_no='LOSER'")).isZero();
            assertThat(execute(ctx, command)).isEqualTo(rejected);
        });
    }

    @Test
    void concurrentCommandsForSameStaffCannotOversell() {
        runner().run(ctx -> {
            var one = accept(ctx, S, Action.RESERVE, "ONE", 0, 1, null, "R");
            var two = accept(ctx, S, Action.RESERVE, "TWO", 0, 1, null, "r");
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var tasks = new ArrayList<java.util.concurrent.Future<Receipt>>();
                for (var command : List.of(one, two)) {
                    tasks.add(pool.submit(() -> {
                        login();
                        ready.countDown();
                        assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
                        try { return execute(ctx, command); } finally { SecurityContextHolder.clearContext(); }
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                var results = List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS));
                assertThat(results.stream().filter(r -> r.status() == Status.SUCCEEDED).count()).isOne();
                assertThat(number("SELECT MAX(held_qty+confirmed_qty) FROM mch_resource_bucket")).isOne();
            }
        });
    }

    @Test
    void protectRenewConfirmReleaseKeepRevisionsAndHistory() {
        runner().run(ctx -> {
            execute(ctx, accept(ctx, S, Action.RESERVE, "A", 0, 1, null, "B"));
            String h = current();
            clock.time.set(NOW.plusSeconds(60));
            execute(ctx, accept(ctx, S, Action.PROTECT, "A", 1, 2, target(h), "B"));
            assertThat(number("SELECT lease_revision FROM mch_booking_hold")).isEqualTo(2);
            clock.time.set(NOW.plusSeconds(120));
            execute(ctx, accept(ctx, S, Action.RENEW, "A", 2, 3, target(h), "B"));
            assertThat(number("SELECT lease_revision FROM mch_booking_hold")).isEqualTo(3);
            assertThat(sql.queryForObject("SELECT expires_at FROM mch_booking_hold", LocalDateTime.class))
                    .isEqualTo(LocalDateTime.of(2026, 10, 21, 8, 17));
            assertThat(number("SELECT source_revision FROM mch_booking_hold")).isEqualTo(3);
            execute(ctx, accept(ctx, S, Action.CONFIRM, "A", 3, 4, target(h), "B"));
            clock.time.set(NOW.plusSeconds(2000));
            assertThat(execute(ctx, accept(ctx, S, Action.EXPIRE, "A", 4, 4, target(h), "B")).status())
                    .isEqualTo(Status.REJECTED);
            var release = accept(ctx, S, Action.RELEASE, "A", 4, 5, target(h), "B");
            var result = execute(ctx, release);
            assertThat(result.status()).isEqualTo(Status.SUCCEEDED);
            assertThat(number("SELECT SUM(held_qty+confirmed_qty) FROM mch_resource_bucket")).isZero();
            assertThat(number("SELECT COUNT(*) FROM mch_booking_hold_unit WHERE released_at IS NOT NULL")).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT current_hold_no FROM mch_booking_owner", String.class)).isNull();
            assertThat(execute(ctx, release)).isEqualTo(result);
        });
    }

    @Test
    void expirationRequiresCurrentLeaseAndHeldDeadline() {
        runner().run(ctx -> {
            execute(ctx, accept(ctx, S, Action.RESERVE, "A", 0, 1, null, "B"));
            String h = current();
            assertThat(execute(ctx, accept(ctx, S, Action.EXPIRE, "A", 1, 1, target(h), "B")).status())
                    .isEqualTo(Status.REJECTED);
            clock.time.set(NOW.plusSeconds(900));
            for (Action action : List.of(Action.CONFIRM, Action.RENEW, Action.PROTECT)) {
                assertThat(execute(ctx, accept(ctx, S, action, "A", 1, 1, target(h), "B")).status())
                        .isEqualTo(Status.REJECTED);
            }
            assertThat(execute(ctx, accept(ctx, S, Action.EXPIRE, "A", 1, 1, target(h), "B")).status())
                    .isEqualTo(Status.SUCCEEDED);
            assertThat(sql.queryForObject("SELECT status FROM mch_booking_hold", String.class)).isEqualTo("EXPIRED");
            assertThat(number("SELECT SUM(held_qty) FROM mch_resource_bucket")).isZero();
        });
    }

    @Test
    void replaceUsesNetCapacityKeepsOriginalHashAndFailedReplacementKeepsOldClaim() {
        runner().run(ctx -> {
            execute(ctx, accept(ctx, S, Action.RESERVE, "A", 0, 1, null, "B", "R"));
            String old = current();
            String hash = sql.queryForObject("SELECT creation_plan_hash FROM mch_booking_hold", String.class);
            execute(ctx, accept(ctx, S, Action.RESERVE, "TAKEN", 0, 1, null, "C"));
            assertThat(execute(ctx, accept(ctx, S, Action.REPLACE, "A", 1, 2, target(old), "C", "R")).status())
                    .isEqualTo(Status.REJECTED);
            assertThat(sql.queryForObject("SELECT current_hold_no FROM mch_booking_owner WHERE source_no='A'",
                    String.class)).isEqualTo(old);
            var replacement = execute(ctx, accept(ctx, S, Action.REPLACE, "A", 1, 2, target(old), "B", "R"));
            assertThat(replacement.status()).isEqualTo(Status.SUCCEEDED);
            assertThat(number("SELECT MAX(held_qty+confirmed_qty) FROM mch_resource_bucket")).isOne();
            assertThat(number("SELECT COUNT(*) FROM mch_booking_hold_unit WHERE released_at IS NOT NULL")).isEqualTo(4);
            String next = sql.queryForObject("SELECT hold_no FROM mch_booking_hold WHERE replaces_hold_no=?",
                    String.class, old);
            assertThat(sql.queryForObject("SELECT creation_plan_hash FROM mch_booking_hold WHERE hold_no=?",
                    String.class, old)).isEqualTo(hash);
            // Confirmed replacement can change the complete plan without losing the original hold history.
            execute(ctx, accept(ctx, S, Action.CONFIRM, "A", 2, 3, target(next), "B", "R"));
            var changed = accept(ctx, S, Action.REPLACE, "A", 3, 4, target(next), "B");
            assertThat(execute(ctx, changed).status()).isEqualTo(Status.SUCCEEDED);
            String latest = sql.queryForObject("SELECT current_hold_no FROM mch_booking_owner WHERE source_no='A'",
                    String.class);
            assertThat(sql.queryForObject("SELECT creation_plan_hash FROM mch_booking_hold WHERE hold_no=?",
                    String.class, latest)).isEqualTo(ResourcePlanCodec.hash(changed.frozenPayload())).isNotEqualTo(hash);
            assertThat(sql.queryForObject("SELECT current_plan_hash FROM mch_booking_hold WHERE hold_no=?",
                    String.class, latest)).isEqualTo(ResourcePlanCodec.hash(changed.frozenPayload()));
            assertThat(sql.queryForObject("SELECT status FROM mch_booking_hold WHERE hold_no=?", String.class, latest))
                    .isEqualTo("CONFIRMED");
            assertThat(number("SELECT SUM(confirmed_qty) FROM mch_resource_bucket")).isEqualTo(2);
        });
    }

    @Test
    void staleSourceRevisionAndStartedExecutionNeverRelease() {
        runner().run(ctx -> {
            execute(ctx, accept(ctx, S, Action.RESERVE, "A", 0, 5, null, "B"));
            String h = current();
            assertThat(execute(ctx, accept(ctx, S, Action.RELEASE, "A", 1, 4, target(h), "B")).status())
                    .isEqualTo(Status.REJECTED);
            execute(ctx, accept(ctx, S, Action.CONFIRM, "A", 1, 6, target(h), "B"));
            sql.execute("UPDATE mch_booking_hold SET execution_started_at=NOW()");
            assertThat(execute(ctx, accept(ctx, S, Action.RELEASE, "A", 2, 7, target(h), "B")).status())
                    .isEqualTo(Status.REJECTED);
            assertThat(number("SELECT SUM(confirmed_qty) FROM mch_resource_bucket")).isEqualTo(2);
        });
    }

    @Test
    void deniedOrExpiredSourceDoesNotWriteAndUnknownTransactionRemainsRecoverable() {
        runner().run(ctx -> {
            var command = accept(ctx, S, Action.RESERVE, "A", 0, 1, null, "B");
            source.pending = true;
            assertThat(execute(ctx, command).status()).isEqualTo(Status.ACCEPTED);
            source.pending = false;
            // A failing BEFORE INSERT trigger proves rollback after capacity writes, rather than a mock success.
            sql.execute("CREATE TRIGGER fail_hold BEFORE INSERT ON mch_booking_hold FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected storage failure'");
            assertThatThrownBy(() -> execute(ctx, command)).isInstanceOf(RuntimeException.class);
            assertThat(commands(ctx).find(S, command.commandNo()).orElseThrow().status()).isEqualTo(Status.ACCEPTED);
            assertThat(number("SELECT COUNT(*) FROM mch_resource_bucket")).isZero();
            sql.execute("DROP TRIGGER fail_hold");
            assertThat(execute(ctx, command).status()).isEqualTo(Status.SUCCEEDED);
        });
    }

    @Test
    void protectedExpiredHoldReconcilesOnlyWithLiveExactSourceDecision() {
        runner().run(ctx -> {
            execute(ctx, accept(ctx, S, Action.RESERVE, "A", 0, 1, null, "B"));
            String h = current();
            clock.time.set(NOW.plusSeconds(1000));
            var command = accept(ctx, S, Action.RECONCILE, "A", 1, 2, target(h), "B");
            source.pending = true;
            assertThat(execute(ctx, command).status()).isEqualTo(Status.ACCEPTED);
            assertThat(number("SELECT SUM(held_qty) FROM mch_resource_bucket")).isEqualTo(2);
            source.pending = false;
            assertThat(execute(ctx, command).status()).isEqualTo(Status.SUCCEEDED);
            assertThat(number("SELECT SUM(confirmed_qty) FROM mch_resource_bucket")).isEqualTo(2);
            assertThat(number("SELECT SUM(held_qty) FROM mch_resource_bucket")).isZero();
            assertThat(execute(ctx, accept(ctx, S, Action.RECONCILE, "A", 2, 2, target(h), "B")).status())
                    .isEqualTo(Status.REJECTED);
        });
    }

    @Test
    void outboxInsertFailureRollsBackCapacityHistoryOwnerAndOriginalResult() {
        runner().run(ctx -> {
            var command = accept(ctx, S, Action.RESERVE, "A", 0, 1, null, "B");
            sql.execute("CREATE TRIGGER fail_outbox BEFORE INSERT ON sys_outbox FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected outbox failure'");
            assertThatThrownBy(() -> execute(ctx, command)).isInstanceOf(RuntimeException.class);
            assertThat(number("SELECT COUNT(*) FROM mch_resource_bucket")).isZero();
            assertThat(number("SELECT COUNT(*) FROM mch_booking_hold_unit")).isZero();
            assertThat(number("SELECT COUNT(*) FROM mch_booking_hold")).isZero();
            assertThat(number("SELECT owner_version FROM mch_booking_owner")).isZero();
            assertThat(commands(ctx).find(S, command.commandNo()).orElseThrow().status()).isEqualTo(Status.ACCEPTED);
            sql.execute("DROP TRIGGER fail_outbox");
            assertThat(execute(ctx, command).status()).isEqualTo(Status.SUCCEEDED);
            assertThat(number("SELECT COUNT(*) FROM sys_outbox")).isOne();
        });
    }

    @Test
    void crossStoreStaffCommandsShareOneBucketDuringFixtureScheduleHandoff() {
        runner().run(ctx -> {
            var first = accept(ctx, S, Action.RESERVE, "ONE", 0, 1, null, "R");
            var other = new Scope("MAIN", "E", "OTHER");
            var second = accept(ctx, other, Action.RESERVE, "TWO", 0, 1, null, "r");
            var firstFinished = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var a = pool.submit(() -> {
                    login();
                    try { return execute(ctx, first); } finally { firstFinished.countDown(); }
                });
                var b = pool.submit(() -> {
                    login();
                    assertThat(firstFinished.await(10, TimeUnit.SECONDS)).isTrue();
                    // Fixture-only handoff isolates shared bucket identity. Production maintenance must
                    // reject this schedule edit while active holds exist; no maintenance bypass is shipped.
                    var tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
                    tx.execute(status -> {
                        var jdbc = new JdbcTemplate(ctx.getBean(DataSource.class));
                        jdbc.queryForList("SELECT * FROM mch_personnel WHERE personnel_no='P' FOR UPDATE");
                        jdbc.queryForList("SELECT * FROM mch_resource WHERE resource_no='R' FOR UPDATE");
                        jdbc.update("UPDATE mch_schedule_rule SET status=IF(store_no='OTHER','ACTIVE','INACTIVE') "
                                + "WHERE resource_no='R'");
                        return null;
                    });
                    return execute(ctx, second);
                });
                assertThat(a.get(20, TimeUnit.SECONDS).status()).isEqualTo(Status.SUCCEEDED);
                var rejected = b.get(20, TimeUnit.SECONDS);
                assertThat(rejected.status()).isEqualTo(Status.REJECTED);
                assertThat(rejected.rejectionCode()).isEqualTo("CAPACITY_UNAVAILABLE");
                assertThat(number("SELECT COUNT(*) FROM mch_resource_bucket WHERE resource_no='R'")).isEqualTo(2);
            }
        });
    }

    @Test
    void durableDeniedAndExpiredPermissionsTerminalizeOriginalWithoutResourceEffects() {
        runner().run(ctx -> {
            var denied = accept(ctx, S, Action.RESERVE, "DENIED", 0, 1, null, "B");
            source.allowed.remove(denied.commandNo());
            var rejected = execute(ctx, denied);
            assertThat(rejected.status()).isEqualTo(Status.REJECTED);
            assertThat(rejected.rejectionCode()).isEqualTo("SOURCE_DENIED");
            assertThat(execute(ctx, denied)).isEqualTo(rejected);
            var expired = accept(ctx, S, Action.RESERVE, "EXPIRED", 0, 1, null, "B");
            clock.time.set(NOW.plusSeconds(60));
            var expiry = execute(ctx, expired);
            assertThat(expiry.status()).isEqualTo(Status.REJECTED);
            assertThat(expiry.rejectionCode()).isEqualTo("SOURCE_PERMISSION_EXPIRED");
            assertThat(number("SELECT COUNT(*) FROM mch_booking_hold")).isZero();
            assertThat(number("SELECT COUNT(*) FROM mch_resource_bucket")).isZero();
            assertThat(number("SELECT COUNT(*) FROM sys_outbox")).isZero();
        });
    }

    @Test
    void staleOwnerHoldAndLeaseRevisionsRefuseAllCapacityMutations() {
        runner().run(ctx -> {
            execute(ctx, accept(ctx, S, Action.RESERVE, "A", 0, 1, null, "B"));
            String h = current();
            Target stale = target(h);
            execute(ctx, accept(ctx, S, Action.RENEW, "A", 1, 2, stale, "B"));
            for (Target wrong : List.of(stale, new Target(h, target(h).holdVersion(), stale.leaseRevision()))) {
                var command = accept(ctx, S, Action.RELEASE, "A", 2, 2, wrong, "B");
                assertThatThrownBy(() -> execute(ctx, command)).isInstanceOf(IllegalStateException.class);
            }
            var owner = accept(ctx, S, Action.RELEASE, "A", 1, 2, target(h), "B");
            assertThatThrownBy(() -> execute(ctx, owner)).isInstanceOf(IllegalStateException.class);
            assertThat(number("SELECT SUM(held_qty) FROM mch_resource_bucket")).isEqualTo(2);
            assertThat(number("SELECT COUNT(*) FROM mch_booking_hold_unit WHERE released_at IS NOT NULL")).isZero();
        });
    }

    @Test
    void missingUnitOrIncorrectBucketCountCannotSilentlyRetireAClaim() {
        runner().run(ctx -> {
            execute(ctx, accept(ctx, S, Action.RESERVE, "A", 0, 1, null, "B"));
            String h = current();
            sql.execute("DELETE FROM mch_booking_hold_unit ORDER BY id LIMIT 1");
            var command = accept(ctx, S, Action.RELEASE, "A", 1, 2, target(h), "B");
            assertThatThrownBy(() -> execute(ctx, command)).isInstanceOf(IllegalStateException.class);
            assertThat(number("SELECT SUM(held_qty) FROM mch_resource_bucket")).isEqualTo(2);
            assertThat(current()).isEqualTo(h);
        });
    }

    @Test
    void bucketCounterMustAgreeWithAllUnreleasedHoldUnitsBeforeNewAllocation() {
        runner().run(ctx -> {
            execute(ctx, accept(ctx, S, Action.RESERVE, "A", 0, 1, null, "B"));
            sql.execute("UPDATE mch_resource_bucket SET held_qty=0");
            var command = accept(ctx, S, Action.RESERVE, "OTHER", 0, 1, null, "B");
            assertThatThrownBy(() -> execute(ctx, command)).isInstanceOf(IllegalStateException.class);
            assertThat(number("SELECT COUNT(*) FROM mch_booking_hold")).isOne();
            assertThat(commands(ctx).find(S, command.commandNo()).orElseThrow().status()).isEqualTo(Status.ACCEPTED);
        });
    }

    @Test
    void lostCommitAcknowledgementFindsOriginalSuccessAndNeverWritesASecondEffect() {
        var fault = new CommitAckFault();
        runner().withBean("commitAckFault", BeanPostProcessor.class, () -> fault).run(ctx -> {
            var command = accept(ctx, S, Action.RESERVE, "A", 0, 1, null, "B");
            fault.enabled.set(true);
            var result = execute(ctx, command);
            assertThat(fault.fired.get()).isTrue();
            assertThat(result.status()).isEqualTo(Status.SUCCEEDED);
            assertThat(commands(ctx).find(S, command.commandNo()).orElseThrow()).isEqualTo(result);
            assertThat(execute(ctx, command)).isEqualTo(result);
            assertThat(number("SELECT COUNT(*) FROM mch_booking_hold")).isOne();
            assertThat(number("SELECT COUNT(*) FROM sys_outbox")).isOne();
        });
    }

    /** Injects failure after real MySQL commit, at the JDBC acknowledgement boundary rather than in a mock. */
    private static final class CommitAckFault implements BeanPostProcessor {
        private final AtomicBoolean enabled = new AtomicBoolean();
        private final AtomicBoolean fired = new AtomicBoolean();
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
            if (!name.equals("dataSource")) { return bean; }
            DataSource delegate = (DataSource) bean;
            return new AbstractDataSource() {
                @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
                @Override public Connection getConnection(String user, String password) throws SQLException {
                    return wrap(delegate.getConnection(user, password));
                }
            };
        }
        private Connection wrap(Connection delegate) {
            var terminal = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String text
                                && text.contains("mch_resource_command") && text.matches("(?is).*SET\\s+status\\s*=.*")) {
                            terminal.set(true);
                        }
                        try {
                            Object result = method.invoke(delegate, args);
                            if (method.getName().equals("commit") && terminal.get() && enabled.get()
                                    && fired.compareAndSet(false, true)) {
                                throw new SQLException("injected lost commit acknowledgement", "08007");
                            }
                            return result;
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
    }

    private Receipt accept(ApplicationContext ctx, Scope scope, Action action, String sourceNo, long owner,
            long revision, Target target, String... resources) {
        var demands = new ArrayList<Demand>();
        var qualifications = new ArrayList<Qualification>();
        for (String no : resources) {
            demands.add(new Demand(no, "L" + no.toUpperCase(), 1, List.of(new Interval(
                    NOW.plusSeconds(3600), NOW.plusSeconds(4200)))));
            if (no.equalsIgnoreCase("R")) {
                qualifications.add(new Qualification(no, "L" + no.toUpperCase(), null, null, false));
            }
        }
        var receipt = commands(ctx).accept(new Accept(scope, action, UUID.randomUUID().toString(),
                source.sourceType(), sourceNo, owner, revision, target, ResourcePlanCodec.encode(demands, qualifications)));
        register(receipt);
        return receipt;
    }

    private void register(Receipt r) {
        var b = new ResourceSourceAuthorityPort.Binding(r.scope().tenantNo(),
                r.scope().entityNo(), r.scope().storeNo(), r.commandNo(), r.sourceType(), r.sourceNo(),
                r.sourceRevision(), r.action().name(), r.payloadHash(), ResourcePlanCodec.hash(r.frozenPayload()));
        source.allowed.put(r.commandNo(), new ResourceSourceAuthorityPort.Decision(b,
                ResourceSourceAuthorityPort.State.AUTHORIZED, "D" + b.commandNo(), "F" + b.commandNo(), 1L,
                clock.instant(), clock.instant().plusSeconds(60), null));
    }

    private static Receipt execute(ApplicationContext ctx, Receipt command) {
        try {
            Class<?> service = Class.forName(SERVICE);
            return (Receipt) service.getMethod("execute", Scope.class, String.class)
                    .invoke(ctx.getBean(service), command.scope(), command.commandNo());
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException failure) { throw failure; }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private static ResourceCommandService commands(ApplicationContext ctx) {
        return ctx.getBean(ResourceCommandService.class);
    }

    private String current() {
        return sql.queryForObject("SELECT current_hold_no FROM mch_booking_owner WHERE source_no='A'", String.class);
    }

    private Target target(String hold) {
        return sql.queryForObject("SELECT hold_no,version,lease_revision FROM mch_booking_hold WHERE hold_no=?",
                (rs, n) -> new Target(rs.getString(1), rs.getLong(2), rs.getLong(3)), hold);
    }

    private long number(String query) {
        return sql.queryForObject(query, Long.class);
    }

    private static void login() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant("ACTOR", "operator"), null, List.of()));
    }

    private final class RegisteredSource implements ResourceSourceAuthorityPort {
        private final Map<String, Decision> allowed = new ConcurrentHashMap<>();
        private boolean pending;
        @Override public String sourceType() { return "TEST_BOOKING"; }
        @Override public Decision freeze(Request request) { return query(request); }
        @Override public Decision query(Request request) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var b = request.binding();
            if (pending) { return new Decision(b, State.PENDING, null, null, null, null, null, "WAITING"); }
            Decision decision = allowed.get(b.commandNo());
            if (decision == null || !b.equals(decision.binding())) {
                return new Decision(b, State.DENIED, "D", null, null, null, null, "UNREGISTERED");
            }
            return decision;
        }
    }

    private static final class TestClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time.get(); }
    }
}
