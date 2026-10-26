package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Query;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceCalendarMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.policy.StoreCalendarSourcePolicy;
import ai.neargo.shop.food.service.impl.StoreCalendarSourceServiceImpl;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

class StoreCalendarSourceServiceTest extends FoodWriteGuardFixture {
    static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Query QUERY =
            new Query("E", "A", LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 22));

    void run(BiConsumer<AssertableApplicationContext, Fixture> test) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class)
                .run(
                        context -> {
                            var fixture = prepare(context);
                            StorePolicyPublicationTest.createPolicyTables(fixture);
                            fixture.sql()
                                    .execute(
                                            "CREATE TABLE fnb_service_calendar (id BIGINT"
                                                + " AUTO_INCREMENT PRIMARY KEY,tenant_no"
                                                + " VARCHAR(32) DEFAULT 'MAIN',entity_no"
                                                + " VARCHAR(32),version BIGINT DEFAULT 0,deleted"
                                                + " INT DEFAULT 0,created_at DATETIME,updated_at"
                                                + " DATETIME,created_by VARCHAR(32),updated_by"
                                                + " VARCHAR(32),calendar_no VARCHAR(32),store_no"
                                                + " VARCHAR(32),local_date DATE,mode"
                                                + " VARCHAR(16),intervals VARCHAR(2048),policy_no"
                                                + " VARCHAR(32))");
                            fixture.sql()
                                    .update(
                                            "INSERT INTO"
                                                + " fnb_store_policy(entity_no,policy_no,store_no,"
                                                + "timezone,cutover_minute,effective_from,"
                                                + "pay_policy,capabilities,settings,version)"
                                                + " VALUES"
                                                + " ('E','P1','A','Asia/Shanghai',240,'2026-10-01"
                                                + " 00:00:00','PREPAY','[]','{}',1)");
                            test.accept(context, fixture);
                        });
    }

    StoreCard card(String status, String hours) {
        return new StoreCard("A", "Store", "E", null, status, true, hours, null, null, null, 0, 0);
    }

    StoreCalendarSourceServiceImpl service(
            AssertableApplicationContext c, StoreCardQueryPort stores) {
        return new StoreCalendarSourceServiceImpl(
                c.getBean(StorePolicyMapper.class),
                c.getBean(ServiceCalendarMapper.class),
                () -> stores,
                JsonMapper.builder().findAndAddModules().build(),
                c.getBean(PlatformTransactionManager.class),
                CLOCK);
    }

    StoreCardQueryPort stores() {
        return n -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Map.of("A", card("ACTIVE", "22:00-03:00"));
        };
    }

    @Test
    void originalCalendarCaptureExistsInsteadOfAnAlwaysOpenDefault() {
        run(
                (c, f) -> {
                    var facts = service(c, stores()).capture(QUERY);
                    StoreCalendarSourcePolicy.validate(QUERY, facts, NOW);
                    assertThat(facts.purpose()).isEqualTo("STORE_CALENDAR");
                    assertThat(facts.store().openHours()).isEqualTo("22:00-03:00");
                    assertThat(facts.policies().getFirst().policyNo()).isEqualTo("P1");
                    assertThat(facts.validUntil()).isEqualTo(NOW.plusSeconds(30));
                    assertThat(facts.exceptions()).isEmpty();
                    assertThat(
                                    StoreCalendarSourcePolicy.acceptsAt(
                                            facts, Instant.parse("2026-10-20T18:00:00Z"), NOW))
                            .isTrue();
                    assertThat(
                                    StoreCalendarSourcePolicy.acceptsAt(
                                            facts, Instant.parse("2026-10-20T07:00:00Z"), NOW))
                            .isFalse();
                    for (var table : List.of("fnb_action_log", "sys_idempotent", "sys_outbox"))
                        assertThat(
                                        f.sql()
                                                .queryForObject(
                                                        "SELECT count(*) FROM " + table,
                                                        Integer.class))
                                .isZero();
                });
    }

    @Test
    void businessDayCutoverAndExceptionOnlyNarrowOriginalHours() {
        run(
                (c, f) -> {
                    f.sql()
                            .update(
                                    "INSERT INTO fnb_service_calendar(entity_no,calendar_no,"
                                        + "store_no,local_date,mode,intervals,policy_no,version)"
                                        + " VALUES"
                                        + " ('E','C1','A','2026-10-20','CLOSED','[]','OLD_POLICY',1)");
                    var facts = service(c, stores()).capture(QUERY);
                    assertThat(
                                    StoreCalendarSourcePolicy.acceptsAt(
                                            facts, Instant.parse("2026-10-20T18:00:00Z"), NOW))
                            .isFalse();
                    assertThat(
                                    StoreCalendarSourcePolicy.acceptsAt(
                                            facts, Instant.parse("2026-10-21T18:00:00Z"), NOW))
                            .isTrue();
                    f.sql()
                            .update(
                                    "UPDATE fnb_service_calendar SET"
                                        + " mode='OPEN',intervals='[\"01:00-04:00\"]',version=2");
                    var open = service(c, stores()).capture(QUERY);
                    assertThat(
                                    StoreCalendarSourcePolicy.acceptsAt(
                                            open, Instant.parse("2026-10-20T18:00:00Z"), NOW))
                            .isTrue();
                    assertThat(
                                    StoreCalendarSourcePolicy.acceptsAt(
                                            open, Instant.parse("2026-10-20T19:30:00Z"), NOW))
                            .isFalse();
                    assertThat(
                                    StoreCalendarSourcePolicy.acceptsAt(
                                            open, Instant.parse("2026-10-20T14:00:00Z"), NOW))
                            .isFalse();
                    assertThat(open.exceptions().getFirst().savedPolicyNo())
                            .isEqualTo("OLD_POLICY");
                });
    }

    @Test
    void futureEffectivePoliciesAndUnknownStoreHoursArePreserved() {
        run(
                (c, f) -> {
                    f.sql()
                            .update(
                                    "INSERT INTO"
                                        + " fnb_store_policy(entity_no,policy_no,store_no,"
                                        + "timezone,cutover_minute,effective_from,"
                                        + "pay_policy,capabilities,settings,version)"
                                        + " VALUES ('E','P2','A','Asia/Shanghai',0,'2026-10-20"
                                        + " 17:00:00','POSTPAY','[]','{}',2)");
                    var facts = service(c, stores()).capture(QUERY);
                    assertThat(facts.policies()).hasSize(2);
                    f.sql()
                            .update(
                                    "INSERT INTO"
                                        + " fnb_service_calendar(entity_no,calendar_no,"
                                        + "store_no,local_date,mode,intervals,policy_no)"
                                        + " VALUES ('E','C2','A','2026-10-21','CLOSED','[]','P1')");
                    facts = service(c, stores()).capture(QUERY);
                    assertThat(
                                    StoreCalendarSourcePolicy.acceptsAt(
                                            facts, Instant.parse("2026-10-20T18:00:00Z"), NOW))
                            .isFalse();
                    for (var hours : Arrays.asList(null, "unknown", "25:00-03:00")) {
                        var unknown =
                                service(c, n -> Map.of("A", card("ACTIVE", hours))).capture(QUERY);
                        assertThat(unknown.store().openHours()).isEqualTo(hours);
                        assertThat(StoreCalendarSourcePolicy.acceptsAt(unknown, NOW, NOW))
                                .isFalse();
                    }
                    var closed =
                            service(c, n -> Map.of("A", card("READONLY", "00:00-00:00")))
                                    .capture(QUERY);
                    assertThat(StoreCalendarSourcePolicy.acceptsAt(closed, NOW, NOW)).isFalse();
                    assertThat(
                                    StoreCalendarSourcePolicy.acceptsAt(
                                            facts, Instant.parse("2026-10-25T18:00:00Z"), NOW))
                            .isFalse();
                });
    }

    @Test
    void scopeMissingOwnersAndMalformedOriginalRowsFailClosed() {
        run(
                (c, f) -> {
                    assertThatThrownBy(() -> service(c, null).capture(QUERY))
                            .isInstanceOf(BizException.class);
                    assertThatThrownBy(() -> service(c, n -> Map.of()).capture(QUERY))
                            .isInstanceOf(BizException.class);
                    assertThatThrownBy(
                                    () ->
                                            service(c, stores())
                                                    .capture(
                                                            new Query(
                                                                    "OTHER",
                                                                    "A",
                                                                    QUERY.fromDate(),
                                                                    QUERY.toDate())))
                            .isInstanceOf(BizException.class);
                    f.tx().executeWithoutResult(
                                    tx ->
                                            assertThatThrownBy(
                                                            () ->
                                                                    service(c, stores())
                                                                            .capture(QUERY))
                                                    .isInstanceOf(BizException.class));
                    f.sql().update("UPDATE fnb_store_policy SET timezone='invalid'");
                    assertThatThrownBy(() -> service(c, stores()).capture(QUERY))
                            .isInstanceOf(BizException.class);
                    f.sql().update("DELETE FROM fnb_store_policy");
                    assertThatThrownBy(() -> service(c, stores()).capture(QUERY))
                            .isInstanceOf(BizException.class);
                });
    }

    @Test
    void changesDuringStoreRefreshAreRejected() {
        var mutations =
                List.of(
                        "UPDATE fnb_store_policy SET cutover_minute=0",
                        "INSERT INTO fnb_service_calendar(entity_no,calendar_no,store_no,"
                                + "local_date,mode,intervals,policy_no) VALUES "
                                + "('E','NEW','A','2026-10-20','CLOSED','[]','P1')");
        for (var mutation : mutations)
            run(
                    (c, f) -> {
                        var calls = new AtomicInteger();
                        StoreCardQueryPort changing =
                                n -> {
                                    assertThat(
                                                    TransactionSynchronizationManager
                                                            .isActualTransactionActive())
                                            .isFalse();
                                    if (calls.incrementAndGet() == 2) f.sql().update(mutation);
                                    return Map.of("A", card("ACTIVE", "22:00-03:00"));
                                };
                        assertThatThrownBy(() -> service(c, changing).capture(QUERY))
                                .isInstanceOf(BizException.class);
                    });
        run(
                (c, f) -> {
                    var calls = new AtomicInteger();
                    StoreCardQueryPort changing =
                            n -> {
                                var status = calls.incrementAndGet() == 1 ? "ACTIVE" : "READONLY";
                                return Map.of("A", card(status, "22:00-03:00"));
                            };
                    assertThatThrownBy(() -> service(c, changing).capture(QUERY))
                            .isInstanceOf(BizException.class);
                });
    }

    @Test
    void strictBoundsAndExpiredOrTamperedEvidenceAreRejected() {
        run(
                (c, f) -> {
                    for (var q :
                            List.of(
                                    new Query(
                                            "E",
                                            "A",
                                            QUERY.fromDate(),
                                            QUERY.fromDate().plusDays(31)),
                                    new Query(" E", "A", QUERY.fromDate(), QUERY.toDate()),
                                    new Query("E", "A", QUERY.toDate(), QUERY.fromDate())))
                        assertThatThrownBy(() -> service(c, stores()).capture(q))
                                .isInstanceOf(BizException.class);
                    var facts = service(c, stores()).capture(QUERY);
                    assertThatThrownBy(
                                    () ->
                                            StoreCalendarSourcePolicy.validate(
                                                    QUERY, facts, NOW.plusSeconds(30)))
                            .isInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void largePolicyTimelineAndDuplicateBusinessDaysCannotBeTruncatedIntoSuccess() {
        run(
                (c, f) -> {
                    for (int n = 0; n < 257; n++)
                        f.sql()
                                .update(
                                        "INSERT INTO fnb_store_policy(entity_no,policy_no,store_no,"
                                            + "timezone,cutover_minute,effective_from,"
                                            + "pay_policy,capabilities,settings,version) VALUES "
                                            + "('E',?,'A','Asia/Shanghai',240,?,'PREPAY','[]','{}',?)",
                                        "P" + (n + 2),
                                        LocalDateTime.of(2026, 10, 19, 0, 0).plusSeconds(n),
                                        n + 2);
                    assertThatThrownBy(() -> service(c, stores()).capture(QUERY))
                            .isInstanceOf(BizException.class);
                });
        run(
                (c, f) -> {
                    for (var no : List.of("C1", "C2"))
                        f.sql()
                                .update(
                                        "INSERT INTO"
                                            + " fnb_service_calendar(entity_no,calendar_no,"
                                            + "store_no,local_date,mode,intervals,policy_no)"
                                            + " VALUES ('E',?,'A','2026-10-20','CLOSED','[]','P1')",
                                        no);
                    assertThatThrownBy(() -> service(c, stores()).capture(QUERY))
                            .isInstanceOf(BizException.class);
                });
    }
}
