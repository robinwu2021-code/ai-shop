package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.dto.*;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.food.mapper.ReservationTableMapper;
import ai.neargo.shop.food.service.*;
import ai.neargo.shop.food.service.impl.*;
import ai.neargo.shop.food.spi.QueueNotificationPort;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Acceptance gaps: real primary persistence and business services, external owners only are doubles. */
class CatalogOperationsAcceptanceTest extends FoodWriteGuardFixture {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final String COMMON = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
            + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
            + "updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";

    private void run(Acceptance test) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,
                TableServiceTest.Areas.class, AcceptanceMappers.class).run(c -> {
            var f = new TableConfigurationTest().fixture(c);
            // Production SQL uses UTC_TIMESTAMP(3), absent from H2's MySQL compatibility mode.
            if (!"true".equals(System.getenv("FOOD_TEST_DISPOSABLE")))
                f.sql().execute("CREATE ALIAS UTC_TIMESTAMP FOR 'ai.neargo.shop.food.scenario.FoodBusinessOperationsPersistenceTest.utc'");
            f.sql().execute("CREATE TABLE fnb_reservation (" + COMMON
                    + "reservation_no VARCHAR(32) UNIQUE,store_no VARCHAR(32),request_key VARCHAR(64),request_hash VARCHAR(64),"
                    + "resource_hold_no VARCHAR(32),buyer_no VARCHAR(32),contact_ref VARCHAR(64),party_size INT,"
                    + "start_at DATETIME(3),end_at DATETIME(3),status VARCHAR(24),deposit_ref VARCHAR(64),check_no VARCHAR(32),"
                    + "UNIQUE(entity_no,store_no,request_key))");
            f.sql().execute("CREATE TABLE fnb_reservation_table (" + COMMON
                    + "store_no VARCHAR(32),reservation_no VARCHAR(32),table_no VARCHAR(32),"
                    + "UNIQUE(entity_no,reservation_no,table_no,deleted))");
            for (String table : List.of("fnb_service_slot", "fnb_capacity_claim", "fnb_business_command",
                    "fnb_reservation_preorder", "fnb_queue_notice")) collaborationTable(f, table);
            f.sql().execute("ALTER TABLE fnb_capacity_claim ADD COLUMN batch_no VARCHAR(32)");
            f.sql().execute("CREATE TABLE fnb_queue_ticket (" + COMMON
                    + "ticket_no VARCHAR(32) UNIQUE,store_no VARCHAR(32),business_date DATE,party_size INT,contact_ref VARCHAR(64),"
                    + "status VARCHAR(16),called_at DATETIME(3),check_no VARCHAR(32),request_key VARCHAR(64),request_hash VARCHAR(64),"
                    + "seat_request VARCHAR(10000),seat_actor_no VARCHAR(32),seat_request_hash VARCHAR(64))");
            f.sql().update("INSERT INTO fnb_store_policy(entity_no,store_no,policy_no,effective_from,timezone,cutover_minute,pay_policy)"
                    + " VALUES('E','A','P','2026-01-01','UTC',0,'PREPAY')");
            test.run(c, f);
        });
    }
    private void collaborationTable(Fixture f, String name) throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/industry/food/V17__food_service_collaboration_operations.sql"));
        int start = migration.indexOf("CREATE TABLE " + name + " (");
        String sql = migration.substring(start, migration.indexOf(';', start));
        if (!"true".equals(System.getenv("FOOD_TEST_DISPOSABLE"))) sql = sql
                .replace("CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", "")
                .replace("ENGINE=InnoDB", "").replace(" JSON ", " VARCHAR(100000) ");
        f.sql().execute(sql);
    }
    private interface Acceptance { void run(AssertableApplicationContext c, Fixture f) throws Exception; }
    private PlatformTransactionManager manager(AssertableApplicationContext c) { return c.getBean(PlatformTransactionManager.class); }
    private OutboxPublisher outbox(AssertableApplicationContext c) { return c.getBean("foodOutboxPublisher", OutboxPublisher.class); }
    private ObjectMapper json(AssertableApplicationContext c) { return c.getBean(ObjectMapper.class); }
    private StoreCardQueryPort stores() {
        return numbers -> Map.of("A", new StoreCard("A", "Store", "E", null, "ACTIVE", true, null, null, null, null, 0, 0));
    }
    private ServiceSlotServiceImpl slots(AssertableApplicationContext c, Fixture f, Instant now) {
        return new ServiceSlotServiceImpl(new FoodBusinessOperationsMapper(f.source()),
                c.getBean(IdempotencyService.class), Clock.fixed(now, ZoneOffset.UTC), json(c));
    }
    private ReservationServiceImpl reservations(AssertableApplicationContext c) {
        return new ReservationServiceImpl(c.getBean(ReservationMapper.class), c.getBean(ReservationTableMapper.class),
                c.getBean(TableAreaMapper.class), c.getBean(TableMapper.class), c.getBean(TableOccupancyMapper.class),
                c.getBean(ActionLogMapper.class), mock(CheckService.class), c.getBean(StoreOperationService.class),
                c.getBean(IdempotencyService.class), json(c), stores(), outbox(c), manager(c));
    }
    private TableServiceImpl guardedTables(AssertableApplicationContext c) {
        return new TableServiceImpl(c.getBean(TableAreaMapper.class), c.getBean(TableMapper.class), c.getBean(ActionLogMapper.class),
                c.getBean(IdempotencyService.class), outbox(c), manager(c), c.getBean(ReservationTableMapper.class));
    }
    private TableVO table(AssertableApplicationContext c) {
        return c.getBean(TableService.class).createTable("E", "A", "actor",
                new CreateTableReq("AREA1", "T1", "Table", 4, 0, null), "table").table();
    }
    private ReservationContracts.CreateReq appointment(TableVO table) {
        Instant start = Instant.now().plusSeconds(86400).truncatedTo(ChronoUnit.MILLIS);
        return new ReservationContracts.CreateReq(2, start, start.plusSeconds(3600), "contact-ref",
                List.of(new TableVersionReq(table.tableNo(), table.version())));
    }
    private ServiceSlotContracts.SlotVO slot(AssertableApplicationContext c, Fixture f) {
        return f.tx().execute(t -> slots(c, f, NOW).create("E", "A", "actor", new ServiceSlotContracts.CreateReq(
                NOW.plusSeconds(7200), NOW.plusSeconds(10800), NOW.plusSeconds(3600), NOW.plusSeconds(5400),
                BigDecimal.ONE, "ORDER"), "slot"));
    }
    private void code(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }

    // Removing the inclusive cutoff or OPEN guard must create an illegal claim and fail this test.
    @Test void cutoffAtEqualityAndClosedSlotRejectWithoutCapacityOrCommandWrites() {
        run((c, f) -> {
            var slot = slot(c, f);
            var before = f.sql().queryForList("SELECT * FROM fnb_service_slot");
            for (Instant instant : List.of(NOW.plusSeconds(3600), NOW.plusSeconds(3601))) {
                code(() -> f.tx().execute(t -> slots(c, f, instant).reserve("E", "A", "actor",
                        new ServiceSlotContracts.ClaimReq(slot.slotNo(), "late", BigDecimal.ONE), "late")), ErrorCode.CONFLICT);
            }
            assertThat(f.sql().queryForList("SELECT * FROM fnb_service_slot")).isEqualTo(before);
            f.sql().update("UPDATE fnb_service_slot SET status='CLOSED'");
            code(() -> f.tx().execute(t -> slots(c, f, NOW).reserve("E", "A", "actor",
                    new ServiceSlotContracts.ClaimReq(slot.slotNo(), "closed", BigDecimal.ONE), "closed")), ErrorCode.CONFLICT);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_capacity_claim", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT reserved_qty FROM fnb_service_slot", BigDecimal.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT consumed_qty FROM fnb_service_slot", BigDecimal.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_business_command", Integer.class)).isOne();
        });
    }

    // Dropping the reservation join from a table guard would allow each prohibited mutation.
    @Test void effectiveReservationProtectsShrinkMoveDisableAndArchiveWithoutChangingFacts() {
        run((c, f) -> {
            var table = table(c); var service = guardedTables(c); var reservations = reservations(c);
            var reservation = reservations.create("E", "A", "actor", appointment(table), "reservation");
            var before = f.sql().queryForList("SELECT * FROM fnb_reservation");
            var members = f.sql().queryForList("SELECT * FROM fnb_reservation_table");
            for (Runnable attempt : List.<Runnable>of(
                    () -> service.saveTable("E", "A", table.tableNo(), "actor", new SaveTableReq("AREA1", "small", 1, 0, 0L), "shrink"),
                    () -> service.saveTable("E", "A", table.tableNo(), "actor", new SaveTableReq("AREA2", "move", 4, 0, 0L), "move"),
                    () -> service.setStatusTable("E", "A", table.tableNo(), "actor", new SetStatusTableReq("DISABLED", 0L, "off"), "off"))) {
                code(attempt, ErrorCode.CONFLICT);
                assertThat(service.getTable("E", "A", table.tableNo()).table()).isEqualTo(table);
            }
            // A legacy disabled table still cannot be archived while the appointment is effective.
            f.sql().update("UPDATE fnb_table SET status='DISABLED' WHERE table_no=?", table.tableNo());
            var disabled = f.sql().queryForList("SELECT * FROM fnb_table");
            code(() -> service.archiveTable("E", "A", table.tableNo(), "actor", new ArchiveTableReq(0L, "archive"), "archive"), ErrorCode.CONFLICT);
            assertThat(f.sql().queryForList("SELECT * FROM fnb_table")).isEqualTo(disabled);
            assertThat(f.sql().queryForList("SELECT * FROM fnb_reservation")).isEqualTo(before);
            assertThat(f.sql().queryForList("SELECT * FROM fnb_reservation_table")).isEqualTo(members);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_action_log", Integer.class)).isEqualTo(2);
            // Negative control: a cancelled appointment no longer prevents the same archive command.
            reservations.cancel("E", "A", "actor", reservation.reservation().reservationNo(),
                    new ReservationContracts.TransitionReq(0L, "cancelled"), "cancel");
            service.archiveTable("E", "A", table.tableNo(), "actor", new ArchiveTableReq(0L, "archive"), "archive");
            assertThat(f.sql().queryForObject("SELECT deleted FROM fnb_table", Integer.class)).isOne();
        });
    }

    @Test void confirmedTableAppointmentDoesNotReserveOrPromiseFullOrderSlot() {
        run((c, f) -> {
            var table = table(c); var request = appointment(table); var slots = slots(c, f, NOW);
            var slot = f.tx().execute(t -> slots.create("E", "A", "actor", new ServiceSlotContracts.CreateReq(
                    request.startAt(), request.endAt(), request.startAt().minusSeconds(3600), request.startAt().minusSeconds(1800),
                    BigDecimal.ONE, "ORDER"), "slot"));
            f.sql().execute("CREATE TABLE fnb_service_calendar (" + COMMON
                    + "calendar_no VARCHAR(32),store_no VARCHAR(32),local_date DATE,mode VARCHAR(16),intervals VARCHAR(2048),policy_no VARCHAR(32))");
            f.sql().update("INSERT INTO fnb_service_calendar(entity_no,store_no,calendar_no,local_date,mode,intervals,policy_no)"
                    + " VALUES('E','A','CLOSED',?,'CLOSED','[]','P')", LocalDate.ofInstant(request.startAt(), ZoneOffset.UTC));
            var calendar = f.sql().queryForList("SELECT * FROM fnb_service_calendar");
            f.tx().execute(t -> slots.reserve("E", "A", "actor",
                    new ServiceSlotContracts.ClaimReq(slot.slotNo(), "another-order", BigDecimal.ONE), "fill"));
            var claims = f.sql().queryForList("SELECT * FROM fnb_capacity_claim");
            var capacity = f.sql().queryForList("SELECT * FROM fnb_service_slot");
            var reservation = reservations(c).create("E", "A", "actor", request, "reservation");
            assertThat(reservation.reservation().status()).isEqualTo("CONFIRMED");
            assertThat(reservation.tableNos()).containsExactly(table.tableNo());
            assertThat(f.sql().queryForList("SELECT * FROM fnb_service_calendar")).isEqualTo(calendar);
            assertThat(f.sql().queryForList("SELECT * FROM fnb_capacity_claim")).isEqualTo(claims);
            assertThat(f.sql().queryForList("SELECT * FROM fnb_service_slot")).isEqualTo(capacity);
            code(() -> f.tx().execute(t -> slots.reserve("E", "A", "actor",
                    new ServiceSlotContracts.ClaimReq(slot.slotNo(), reservation.reservation().reservationNo(), BigDecimal.ONE), "later-order")), ErrorCode.CONFLICT);
            assertThat(f.sql().queryForList("SELECT * FROM fnb_capacity_claim")).isEqualTo(claims);
        });
    }

    @Test void preorderSaveIsOnlyDraftAndPreparationRequiresArrival() {
        run((c, f) -> {
            var table = table(c); var request = appointment(table);
            var reservation = reservations(c).create("E", "A", "actor", request, "reservation");
            var line = new OrderLineInput("line-1", "menu", "item", "revision", "goods", "sku", BigDecimal.ONE,
                    null, null, List.of(), List.of());
            var preorders = new ReservationPreorderServiceImpl(new FoodBusinessOperationsMapper(f.source()), manager(c),
                    c.getBean(IdempotencyService.class), mock(CheckCartService.class, call -> { throw new AssertionError("Draft must not write cart"); }),
                    mock(DiningOrderService.class, call -> { throw new AssertionError("Draft must not place order"); }), json(c),
                    Clock.fixed(request.startAt().minusSeconds(3600), ZoneOffset.UTC));
            String number = reservation.reservation().reservationNo();
            var save = new ReservationPreorderContracts.SaveReq(0, request.startAt().minusSeconds(600), List.of(line));
            for (Instant invalid : List.of(request.startAt().minusSeconds(3600), request.startAt().plusMillis(1))) {
                code(() -> preorders.save("E", "A", "actor", number,
                        new ReservationPreorderContracts.SaveReq(0, invalid, List.of(line)), "invalid"), ErrorCode.BAD_REQUEST);
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_reservation_preorder", Integer.class)).isZero();
            }
            var saved = preorders.save("E", "A", "actor", number, save, "save");
            assertThat(saved.status()).isEqualTo("DRAFT");
            assertThat(saved.lines()).containsExactly(line);
            assertThat(saved.cutoffAt()).isEqualTo(request.startAt().minusSeconds(600));
            assertThat(saved.cartNo()).isNull(); assertThat(saved.batchNo()).isNull();
            assertThat(preorders.save("E", "A", "actor", number, save, "save")).isEqualTo(saved);
            code(() -> preorders.prepareCart("E", "A", "actor", number, saved.preorderNo(), 0, "prepare"), ErrorCode.CONFLICT);
            assertThat(preorders.get("E", "A", number, saved.preorderNo())).isEqualTo(saved);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_capacity_claim", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_reservation_preorder", Integer.class)).isOne();
        });
    }

    private QueueServiceImpl queue(AssertableApplicationContext c, QueueNotificationServiceImpl notices) {
        var queue = new QueueServiceImpl(c.getBean(QueueTicketMapper.class), c.getBean(StorePolicyMapper.class),
                c.getBean(ActionLogMapper.class), c.getBean(StoreOperationService.class), mock(CheckService.class), stores(),
                c.getBean(IdempotencyService.class), outbox(c), json(c), manager(c));
        queue.setNotifications(notices); return queue;
    }
    private QueueNotificationServiceImpl notices(AssertableApplicationContext c, Fixture f, QueueNotificationPort provider, Instant now) {
        return new QueueNotificationServiceImpl(new FoodBusinessOperationsMapper(f.source()), manager(c), () -> provider,
                Clock.fixed(now, ZoneOffset.UTC));
    }
    private void ticket(Fixture f, String no, String store, String day, String status, String created) {
        f.sql().update("INSERT INTO fnb_queue_ticket(entity_no,store_no,ticket_no,business_date,party_size,status,contact_ref,"
                + "created_at,created_by,updated_at,updated_by) VALUES('E',?,?,?,2,?,'contact-ref',?,'actor',?,'actor')",
                store, no, day, status, created, created);
    }
    @Test void callNextFiltersBusinessDateStoreAndWaitingThenPersistsOneNoticeOnReplay() {
        run((c, f) -> {
            var provider = new Provider(); var notices = notices(c, f, provider, NOW.plusSeconds(60)); var queue = queue(c, notices);
            ticket(f, "old-day", "A", "2026-10-23", "WAITING", "2026-10-23 01:00:00");
            ticket(f, "foreign", "B", "2026-10-24", "WAITING", "2026-10-24 00:00:00");
            ticket(f, "cancelled", "A", "2026-10-24", "CANCELLED", "2026-10-24 00:00:00");
            ticket(f, "first", "A", "2026-10-24", "WAITING", "2026-10-24 00:00:01");
            ticket(f, "second", "A", "2026-10-24", "WAITING", "2026-10-24 00:00:01");
            var called = queue.callNext("E", "A", "actor", LocalDate.of(2026, 10, 24), "call");
            assertThat(called.ticketNo()).isEqualTo("first"); assertThat(called.status()).isEqualTo("CALLED");
            assertThat(called.version()).isOne(); assertThat(called.calledAt()).isNotNull();
            assertThat(queue.callNext("E", "A", "actor", LocalDate.of(2026, 10, 24), "call")).isEqualTo(called);
            assertThat(f.sql().queryForList("SELECT ticket_no FROM fnb_queue_ticket WHERE status='WAITING' ORDER BY id", String.class))
                    .containsExactly("old-day", "foreign", "second");
            assertThat(notices.list("E", "A", "first")).hasSize(1).first().satisfies(n -> assertThat(n.status()).isEqualTo("PENDING"));
            assertThat(provider.calls).isEmpty();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isOne();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_action_log", Integer.class)).isOne();
            assertThat(queue.callNext("E", "A", "actor", LocalDate.of(2026, 10, 24), "next").ticketNo()).isEqualTo("second");
        });
    }
    private String notice(AssertableApplicationContext c, Fixture f, QueueNotificationServiceImpl notices) {
        f.tx().execute(t -> { notices.called("E", "A", "ticket", 1, "contact-ref", "actor"); return null; });
        String no = notices.list("E", "A", "ticket").getFirst().noticeNo();
        // DATETIME stores the owner's UTC local time. Timestamp.from(Instant) would be
        // converted through the JVM/driver timezone (+08 here), making the row not yet due.
        var due = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        f.sql().update("UPDATE fnb_queue_notice SET next_attempt_at=? WHERE notice_no=?", due, no);
        assertThat(f.sql().queryForObject("SELECT next_attempt_at FROM fnb_queue_notice WHERE notice_no=?",
                LocalDateTime.class, no)).isEqualTo(due);
        return no;
    }
    @Test void recoveryLooksUpOriginalKeySendsOnlyWhenAbsentAndAcceptanceIsNotDelivery() {
        run((c, f) -> {
            var provider = new Provider(); var service = notices(c, f, provider, NOW.plusSeconds(60));
            String no = notice(c, f, service);
            var accepted = service.recover("E", "A", "actor", no, "recover");
            assertThat(accepted.status()).isEqualTo("ACCEPTED"); assertThat(accepted.providerRef()).isEqualTo("provider-1");
            assertThat(provider.calls).containsExactly("lookup:QUEUE_NOTICE:" + no, "send:QUEUE_NOTICE:" + no);
            assertThat(provider.request.entityNo()).isEqualTo("E"); assertThat(provider.request.storeNo()).isEqualTo("A");
            assertThat(provider.request.ticketNo()).isEqualTo("ticket"); assertThat(provider.request.contactRef()).isEqualTo("contact-ref");
            // Provider completed after the first attempt; recovery reads it without another send.
            provider.status = "DELIVERED";
            var delivered = notices(c, f, provider, NOW.plusSeconds(241)).recover("E", "A", "actor", no, "retry");
            assertThat(delivered.status()).isEqualTo("DELIVERED");
            assertThat(provider.calls).containsExactly("lookup:QUEUE_NOTICE:" + no, "send:QUEUE_NOTICE:" + no, "lookup:QUEUE_NOTICE:" + no);
            assertThat(f.sql().queryForObject("SELECT status FROM fnb_queue_notice", String.class)).isEqualTo("DELIVERED");
        });
    }
    @Test void unavailableOrUnknownProviderNeverFabricatesDeliveryOrSendsOverAnUnknownOutcome() {
        run((c, f) -> {
            var unavailable = notices(c, f, null, NOW.plusSeconds(60)); String no = notice(c, f, unavailable);
            assertThat(unavailable.recover("E", "A", "actor", no, "recover").status()).isEqualTo("PENDING");
            assertThat(f.sql().queryForObject("SELECT last_error FROM fnb_queue_notice", String.class)).isEqualTo("PROVIDER_UNAVAILABLE");
            var provider = new Provider(); provider.status = "UNKNOWN";
            var unknown = notices(c, f, provider, NOW.plusSeconds(241)).recover("E", "A", "actor", no, "retry");
            assertThat(unknown.status()).isEqualTo("UNKNOWN");
            assertThat(provider.calls).containsExactly("lookup:QUEUE_NOTICE:" + no);
            assertThat(f.sql().queryForObject("SELECT status FROM fnb_queue_notice", String.class)).isEqualTo("UNKNOWN");
        });
    }
    private static class Provider implements QueueNotificationPort {
        final List<String> calls = new ArrayList<>(); String status = "ABSENT"; Request request;
        public Receipt lookup(Request request) { this.request = request; calls.add("lookup:" + request.originalKey()); return new Receipt(request.originalKey(), status, "ABSENT".equals(status) ? null : "provider-1"); }
        public Receipt send(Request request) { this.request = request; calls.add("send:" + request.originalKey()); status = "ACCEPTED"; return new Receipt(request.originalKey(), status, "provider-1"); }
    }
    @Configuration(proxyBeanMethods = false)
    static class AcceptanceMappers {
        private static <T> MapperFactoryBean<T> mapper(Class<T> type, SqlSessionTemplate template) {
            var bean = new MapperFactoryBean<>(type); bean.setSqlSessionTemplate(template); return bean;
        }
        @Bean MapperFactoryBean<ReservationMapper> acceptanceReservations(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(ReservationMapper.class, t); }
        @Bean MapperFactoryBean<ReservationTableMapper> acceptanceMemberships(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(ReservationTableMapper.class, t); }
        @Bean MapperFactoryBean<TableOccupancyMapper> acceptanceOccupancies(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(TableOccupancyMapper.class, t); }
        @Bean MapperFactoryBean<QueueTicketMapper> acceptanceTickets(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(QueueTicketMapper.class, t); }
    }
}
