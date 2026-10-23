package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.beauty.config.BeautyAppointmentConfiguration;
import ai.neargo.shop.beauty.config.BeautyCatalogConfiguration;
import ai.neargo.shop.beauty.config.BeautyDefinitionConfiguration;
import ai.neargo.shop.beauty.service.ServiceDefinitionService;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;
import ai.neargo.shop.beauty.config.BeautyWriteGuardConfiguration;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.dto.CheckInReq;
import ai.neargo.shop.beauty.dto.MyAppointmentVO;
import ai.neargo.shop.beauty.service.impl.AppointmentCoordinator;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import java.sql.Timestamp;
import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.AppointmentAssignmentReq;
import ai.neargo.shop.beauty.service.impl.ReceptionCoordinator;
import ai.neargo.shop.beauty.dto.CheckInProof;
import ai.neargo.shop.beauty.dto.MyCheckInReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionReq;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.beauty.dto.ReceptionIdentityProof;
import ai.neargo.shop.beauty.service.ReceptionService;
import ai.neargo.shop.beauty.dto.AppointmentQuery;
import ai.neargo.shop.beauty.dto.CancelAppointmentReq;
import ai.neargo.shop.beauty.dto.ChangeAppointmentReq;
import ai.neargo.shop.beauty.dto.CreateAppointmentReq;
import ai.neargo.shop.beauty.dto.CreateMyAppointmentReq;
import ai.neargo.shop.beauty.dto.PreviewAppointmentReq;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentChangeReq;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentReq;
import ai.neargo.shop.beauty.dto.RecordNoShowReq;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import ai.neargo.shop.beauty.service.impl.AppointmentRecovery;
import ai.neargo.shop.beauty.service.impl.AppointmentPersistence;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Real private MySQL/source/fence/services. External catalog/personnel/resource owner are explicit controlled
 * fixtures.
 */
class ReceptionBusinessIT extends BeautyExecutionFixture {
    private final ObjectMapper json = new ObjectMapper();
    private final ControlledOwner owner = new ControlledOwner();
    private boolean allowed = true;
    private String proofUser = "USR1";
    private Instant clockAt;
    private final Clock receptionClock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return clockAt == null ? Instant.now() : clockAt; }
    };
    private boolean customerAllowed = true;
    private boolean guestClaimed;
    private String revokedActor;
    private boolean roomQuantities;
    private boolean occupied;
    private boolean otherOccupied;
    private boolean failOutbox;
    private long quotePrice = 1000;
    private Long goodsRevision = 7L;
    private final OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
            .truncatedTo(ChronoUnit.DAYS).plusHours(10);

    @Configuration(proxyBeanMethods = false)
    static class OutboxRoute {
        @Bean
        UnifiedOutboxFacade facade(DataSource source, PlatformTransactionManager manager, ObjectMapper mapper) {
            var facade = new UnifiedOutboxFacade(mapper);
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, source, manager, mapper), List.of());
            facade.setArchiveOnly(Set.of("PLATFORM:BEAUTY_APPOINTMENT_OPERATION_COMPLETED",
                    "PLATFORM:BEAUTY_RECEPTION_STATE_CHANGED",
                    "PLATFORM:BEAUTY_SERVICE_DEFINITION_VERSION_PUBLISHED"));
            return facade;
        }
    }

    @BeforeEach
    void database() throws Exception {
        initialize("BtyAppointmentPreparation", "BtyAppointmentOperation", "BtyReceptionOperation",
                "BtyArrivalChallenge");
        new ResourceDatabasePopulator(new ClassPathResource("platform-write-fixture.sql")).execute(database.source);
        login(LoginUser.consumer("USR1", "customer"));
        seed("bty_store_policy", "policy_no", "P", "store_no", "S", "current_policy_version_no", "PV",
                "status", "ACTIVE", "version", 1L);
        seed("bty_store_policy_version", "policy_version_no", "PV", "policy_no", "P", "revision_index", 1,
                "booking_ahead_days", 30, "cancel_rule", "{\"freeCancelBeforeMinutes\":60,"
                        + "\"allowCustomerCancel\":true}", "allowed_modes", "[\"PREPAID_SINGLE\"]",
                "effective_at", T0.minusYears(1), "schema_version", 1, "snapshot_hash", "a".repeat(64));
        seed("bty_service_definition", "definition_no", "D", "store_no", "S", "sku_no", "SKU",
                "current_definition_version_no", "DV", "status", "ACTIVE", "version", 1L);
        seed("bty_service_definition_version", "definition_no", "D", "definition_version_no", "DV",
                "revision_index", 1, "duration_min_snapshot", 17, "buffer_before_min", 3, "buffer_after_min", 4,
                "steps_template", "[{\"stepCode\":\"SERVICE\",\"sequenceNo\":0}]", "notes", "[]",
                        "schema_version", 1, "snapshot_hash", "b".repeat(64));
        seed("bty_service_requirement", "requirement_no", "REQ", "definition_version_no", "DV", "role_code", "MAIN",
                "resource_type", "STAFF", "skill_goods_no", "G", "qty", 1, "sequence_no", 0,
                "offset_min", 0, "duration_min", 17, "selection_rule", "ANY_QUALIFIED");
        sql.update("UPDATE bty_service_definition_version SET snapshot_hash=?", IdempotencyScope.requestHash("SKU",
                json.writeValueAsString(new PublishServiceDefinitionVersionReq.Duration(17, 3, 4)),
                json.writeValueAsString(List.of(new PublishServiceDefinitionVersionReq.Requirement("MAIN", "STAFF",
                        "G", 1, 0, 0, 17, "ANY_QUALIFIED")))));
        sql.update("UPDATE bty_store_policy_version SET snapshot_hash=?", IdempotencyScope.requestHash(
                T0.minusYears(1).atOffset(ZoneOffset.UTC).toString(), "30", "60", "true", "PREPAID_SINGLE"));
    }
    private void login(LoginUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null,
                List.of()));
    }
    private ApplicationContextRunner business() {
        return runner().withUserConfiguration(BeautyCatalogConfiguration.class, BeautyAppointmentConfiguration.class,
                        BeautyWriteGuardConfiguration.class, OutboxRoute.class)
                .withBean(ObjectMapper.class, () -> json)
                .withBean(ResourceBookingPort.class, () -> owner)
                .withBean(CustomerIdentityPort.class, () -> new CustomerIdentityPort() {
                    @Override public ResolvedCustomer resolveReference(
                            EntitlementContext context, ReferenceQuery q) {
                        throw new AssertionError("legacy reference cannot authorize reception");
                    }
                    @Override public ResolvedCustomer verifyConsumerProof(EntitlementContext context, String code) {
                        outside(); return new ResolvedCustomer(proofUser);
                    }
                    @Override public ResolvedCustomer resolveClaimReference(
                            EntitlementContext context, ReferenceQuery q) {
                        outside(); return new ResolvedCustomer("USR1");
                    }
                })
                .withBean(MerchantOperatorAccessPort.class, () -> q -> allowed && !q.operatorNo().equals(revokedActor)
                        && Set.of("biz:booking:manage", "biz:customer").contains(q.permission())
                        && (customerAllowed || !"biz:customer".equals(q.permission())))
                .withBean(ServiceCatalogFactsPort.class, () -> new ServiceCatalogFactsPort() {
                    @Override public Store store(String number) {
                        outside();
                        return new Store("E", number, "Store", null, "ACTIVE", "always", "address",
                                new StoreSalesCatalogPort.Snapshot("E", number, null, 0, null, 0, null, false, null,
                                        null));
                    }
                    @Override public Service service(String store, String sku) {
                        outside();
                        return new Service(store(store), sku, "G", "Service", null, quotePrice, 17,
                                goodsRevision, 0L);
                    }
                })
                .withBean(ResourceCandidatesPort.class, () -> new ResourceCandidatesPort() {
                    @Override public List<ResourceFactsPort.ResourceFact> candidates(Query query) {
                        outside();
                        if (roomQuantities) {
                            return List.of(new ResourceFactsPort.ResourceFact("R1", ResourceFactsPort.Type.STAFF,
                                    null, "P1", "Technician", 1, false),
                                    new ResourceFactsPort.ResourceFact("ROOM1", ResourceFactsPort.Type.ROOM,
                                            null, null, "Shared room", 2, false),
                                    new ResourceFactsPort.ResourceFact("ROOM2", ResourceFactsPort.Type.ROOM,
                                            null, null, "Other room", 2, false));
                        }
                        return List.of(new ResourceFactsPort.ResourceFact("R1", ResourceFactsPort.Type.STAFF,
                                null, "P1", "Technician", 1, false),
                                new ResourceFactsPort.ResourceFact("R2", ResourceFactsPort.Type.STAFF,
                                null, "P2", "Second", 1, false));
                    }
                    @Override public List<Capacity> capacity(CapacityQuery query) {
                        outside();
                        if (!occupied) { return List.of(); }
                        boolean excluded = query.replacement() != null;
                        if (excluded) {
                            var proof = query.replacement();
                            assertThat(proof.binding().sourceType()).isEqualTo("BEAUTY_APPOINTMENT");
                            assertThat(owner.receipts.values()).anySatisfy(receipt ->
                                    assertThat(receipt.binding()).isEqualTo(proof.binding()));
                            assertThat(owner.allHolds.get(proof.target().holdNo()).status()).isEqualTo("CONFIRMED");
                        }
                        assertThat(query.factAt()).isAfter(query.from());
                        int used = (excluded ? 0 : 1) + (otherOccupied ? 1 : 0);
                        return List.of(new Capacity("R1", start.minusMinutes(5).toInstant(),
                                start.plusMinutes(25).toInstant(), 1, used));
                    }
                })
                .withBean(PersonnelFactsPort.class, () -> q -> {
                    outside(); return List.of("P1", "P2").stream().map(p -> new PersonnelFactsPort.PersonnelFact(p,
                            "A" + p, "S", p, "L", true, Instant.EPOCH, null, List.of("TECH"),
                            List.of(new PersonnelFactsPort.Qualification("Q" + p, "S", "G", "SKU", "L",
                                    Instant.EPOCH, null)))).toList();
                })
                .withBean(ScheduleWindowsPort.class, () -> q -> {
                    outside(); return q.resourceNos().stream().map(no -> new ScheduleWindowsPort.Window(no, "S",
                            "A", q.startAt(), q.endAt(), List.of("RULE"))).toList();
                }).withInitializer(application -> application.getBeanFactory().addBeanPostProcessor(
                        new BeanPostProcessor() {
                            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                                if ("beautyReceptionClock".equals(name)) { return receptionClock; }
                                if (!"beautyOutboxPublisher".equals(name)) { return bean; }
                                OutboxPublisher delegate = (OutboxPublisher) bean;
                                return (OutboxPublisher) (event, options) -> {
                                    var receipt = delegate.publish(event, options);
                                    if (failOutbox) { throw new IllegalStateException("reception-outbox-rollback"); }
                                    return receipt;
                                };
                            }
                        }));
    }
    private void outside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private PreviewMyAppointmentReq preview(int qty) {
        return new PreviewMyAppointmentReq("S", List.of(new AppointmentItemReq("SKU", qty)), start, "P1");
    }
    @Test void checkInKeepsScheduleHoldAndNoTasks() {
        business().run(c -> {
            owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            var appointments = c.getBean(AppointmentService.class);
            var p = appointments.previewMyAppointment("USR1", preview(1));
            var root = appointments.createMyAppointment("USR1", new CreateMyAppointmentReq(p.previewNo(),
                    List.of(), null), "CREATE");
            String hold = owner.hold.holdNo();
            login(LoginUser.merchant("A", "operator"));
            var req = new CheckInReq(root.appointmentNo(),
                    new ReceptionIdentityProof("cr1_" + "c".repeat(43), null, null), root.revision());
            var reception = c.getBean(ReceptionService.class);
            var arrived = assertDoesNotThrow(() -> reception.checkIn("E", "S", "A", req, "ARRIVE"));
            assertThat(arrived.status()).isEqualTo("CHECKED_IN");
            assertThat(arrived.timing()).isEqualTo("EARLY");
            assertThat(arrived.plannedStartAt()).isEqualTo(p.startAt());
            assertThat(owner.hold.holdNo()).isEqualTo(hold);
            assertThat(owner.accepts).hasSize(2);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_work_order", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_service_task", Integer.class)).isZero();
            assertThat(reception.checkIn("E", "S", "A", req, "ARRIVE")).isEqualTo(arrived);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_reception_operation", Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_action_log WHERE action='CHECKED_IN'",
                    Integer.class)).isOne();
        });
    }

    @Test void consumerNeedsOwnIdentityAndLiveStoreChallenge() {
        business().run(c -> {
            owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            var appointments = c.getBean(AppointmentService.class);
            var p = appointments.previewMyAppointment("USR1", preview(1));
            var root = appointments.createMyAppointment("USR1", new CreateMyAppointmentReq(p.previewNo(),
                    List.of(), null), "CREATE");
            var reception = c.getBean(ReceptionService.class);
            login(LoginUser.merchant("A", "operator"));
            var challenge = reception.issueArrivalChallenge("E", "S", "A", "CODE");
            login(LoginUser.consumer("USR1", "customer"));
            var req = new MyCheckInReq(root.appointmentNo(),
                    new CheckInProof("mc1_" + "m".repeat(43), challenge.code()), root.revision());
            proofUser = "OTHER";
            assertThatThrownBy(() -> reception.checkInMyAppointment("USR1", req, "ARRIVE"))
                    .isInstanceOf(BizException.class);
            proofUser = "USR1";
            var result = assertDoesNotThrow(() -> reception.checkInMyAppointment("USR1", req, "ARRIVE"));
            assertThat(result.status()).isEqualTo("CHECKED_IN");
        });
    }
    @Test void exceptionReplayIsAtomicWithActionAndOutbox() {
        business().run(c -> {
            owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            var appointments = c.getBean(AppointmentService.class);
            var p = appointments.previewMyAppointment("USR1", preview(1));
            var root = appointments.createMyAppointment("USR1", new CreateMyAppointmentReq(p.previewNo(),
                    List.of(), null), "CREATE");
            login(LoginUser.merchant("A", "operator"));
            var req = new ReceptionExceptionReq(root.appointmentNo(), "LATE", "REQUEST_RESCHEDULE",
                    "private-reason", root.revision());
            var reception = c.getBean(ReceptionService.class);
            var result = assertDoesNotThrow(() -> reception.coordinateReceptionException("E", "S", "A", req, "EX"));
            assertThat(result.state()).isEqualTo("PENDING_REVIEW");
            assertThat(result.affectedTaskNos()).isEmpty();
            assertThat(reception.coordinateReceptionException("E", "S", "A", req, "EX")).isEqualTo(result);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_action_log "
                    + "WHERE action='RECEPTION_EXCEPTION_REQUESTED'", Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT payload FROM sys_outbox "
                    + "WHERE event_type='BEAUTY_RECEPTION_STATE_CHANGED'", String.class))
                    .doesNotContain("private-reason", "proof", "frozenPlan");
            assertThat(owner.accepts).hasSize(2);
        });
    }

    @Test void reassignsCheckedInWithFrozenRules() {
        business().run(c -> {
            owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            var appointments = c.getBean(AppointmentService.class);
            var p = appointments.previewMyAppointment("USR1", preview(2));
            var root = appointments.createMyAppointment("USR1", new CreateMyAppointmentReq(p.previewNo(),
                    List.of(), null), "CREATE");
            login(LoginUser.merchant("A", "operator"));
            var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                    new CheckInReq(root.appointmentNo(),
                            new ReceptionIdentityProof("cr1_" + "c".repeat(43), null, null), root.revision()),
                                    "ARRIVE");
            seed("bty_service_definition_version", "definition_no", "D", "definition_version_no", "DV2",
                    "revision_index", 2, "duration_min_snapshot", 17, "buffer_before_min", 9, "buffer_after_min", 12,
                    "steps_template", "[]", "notes", "[]", "schema_version", 1, "snapshot_hash", "d".repeat(64));
            seed("bty_service_requirement", "requirement_no", "REQ2", "definition_version_no", "DV2",
                    "role_code", "MAIN", "resource_type", "STAFF", "skill_goods_no", "G", "qty", 1,
                    "sequence_no", 0, "offset_min", 0, "duration_min", 17, "selection_rule", "ANY_QUALIFIED");
            sql.update("UPDATE bty_service_definition SET current_definition_version_no='DV2',version=2");
            quotePrice = 5000;
            var changed = assertDoesNotThrow(() -> c.getBean(ReceptionCoordinator.class).reassign("E", "S", "A",
                    root.appointmentNo(), arrived.revision(), List.of(new AppointmentAssignmentReq(0, "REQ",
                            "R2", "P2"),
                            new AppointmentAssignmentReq(1, "REQ", "R2", "P2")), start.plusMinutes(60), "REASSIGN"));
            assertThat(changed.getState()).isEqualTo("SUCCEEDED");
            var fresh = appointments.getAppointment("E", "S", "A", root.appointmentNo());
            assertThat(fresh.items()).allSatisfy(item -> {
                assertThat(item.definitionVersionNo()).isEqualTo("DV");
                assertThat(item.durationMin()).isEqualTo(17); assertThat(item.priceMinor()).isEqualTo(1000);
            });
            assertThat(fresh.assignments().getFirst().startAt()).isEqualTo(start.plusMinutes(57));
            assertThat(fresh.assignments().getFirst().endAt()).isEqualTo(start.plusMinutes(81));
            assertThat(sql.queryForObject("SELECT checked_in_at FROM bty_appointment", Timestamp.class)
                    .toInstant()).isEqualTo(arrived.checkedInAt().toInstant());
            assertThat(changed.getAction()).isEqualTo("RECEPTION_CHANGE");
            assertThat(sql.queryForObject("SELECT status FROM bty_appointment", String.class)).isEqualTo("CHECKED_IN");
            assertThat(owner.accepts.values().stream().map(ResourceBookingPort.Accept::action))
                    .containsExactly("RESERVE", "CONFIRM", "REPLACE");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_work_order", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment_item", Integer.class)).isEqualTo(4);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"KEEP", "CHANGE_RESOURCE", "CHANGE_TIME"})
    void receptionRetainsRepeatedResourceUnits(String change) {
        roomQuantities = true;
        business().withUserConfiguration(BeautyDefinitionConfiguration.class)
                .withBean(ServiceProductFactsPort.class, () -> (entity, store, sku) -> {
                    outside();
                    return new ServiceProductFactsPort.Fact(entity, store, sku, "G", 1000, 17, 7L, 0L);
                }).run(c -> {
                    login(LoginUser.merchant("A", "operator"));
                    var published = c.getBean(ServiceDefinitionService.class).publishDefinitionVersion("E", "S", "A",
                            new PublishServiceDefinitionVersionReq("SKU", 1L,
                                    new PublishServiceDefinitionVersionReq.Duration(17, 3, 4), List.of(
                                            new PublishServiceDefinitionVersionReq.Requirement("MAIN", "STAFF", "G",
                                                    1, 0, 0, 17, "ANY_QUALIFIED"),
                                            new PublishServiceDefinitionVersionReq.Requirement("ROOM", "ROOM", null,
                                                    2, 0, 0, 17, "ANY_QUALIFIED"))), "PUBLISH-QUANTITY");
                    var root = create(c);
                    login(LoginUser.merchant("A", "operator"));
                    var originalDetail = c.getBean(AppointmentService.class).getAppointment("E", "S", "A",
                            root.appointmentNo());
                    assertThat(originalDetail.items()).allSatisfy(item -> assertThat(item.definitionVersionNo())
                            .isEqualTo(published.definitionVersionNo()));
                    assertThat(originalDetail.assignments().stream().map(a -> a.resourceNo()))
                            .containsExactlyInAnyOrder("R1", "ROOM1", "ROOM1");
                    login(LoginUser.merchant("A", "operator"));
                    var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                            request(root.appointmentNo(), root.revision()), "ARRIVE");
                    var coordinator = c.getBean(ReceptionCoordinator.class);
                    var original = originalDetail.assignments().stream().map(a -> new AppointmentAssignmentReq(0,
                            a.requirementNo(), a.resourceNo(), a.personnelNo())).toList();
                    assertThatThrownBy(() -> coordinator.reassign("E", "S", "A", root.appointmentNo(),
                            arrived.revision(), original, start, "EXPLICIT-DUPLICATE"))
                            .isInstanceOf(BizException.class);
                    var requested = new ArrayList<AppointmentAssignmentReq>();
                    if ("CHANGE_RESOURCE".equals(change)) {
                        requested.addAll(original);
                        var room = requested.stream().filter(a -> "ROOM1".equals(a.resourceNo()))
                                .findFirst().orElseThrow();
                        requested.set(requested.indexOf(room),
                                new AppointmentAssignmentReq(0, room.requirementNo(), "ROOM2", null));
                    }
                    var chosenStart = "CHANGE_TIME".equals(change) ? start.plusHours(1) : start;
                    var result = assertDoesNotThrow(() -> coordinator.reassign("E", "S", "A", root.appointmentNo(),
                            arrived.revision(), requested, chosenStart, "QUANTITY"));
                    if ("KEEP".equals(change)) {
                        assertThat(result).isNull();
                        assertThat(owner.accepts).hasSize(2);
                    } else {
                        assertThat(result).isNotNull();
                        assertThat(result.getState()).isEqualTo("SUCCEEDED");
                        assertThat(owner.accepts.values().stream().map(ResourceBookingPort.Accept::action))
                                .containsExactly("RESERVE", "CONFIRM", "REPLACE");
                        var fresh = c.getBean(AppointmentService.class).getAppointment("E", "S", "A",
                                root.appointmentNo());
                        assertThat(fresh.appointment().startAt()).isEqualTo(chosenStart);
                        assertThat(fresh.assignments().stream().map(a -> a.resourceNo())).containsExactlyInAnyOrder(
                                "R1", "ROOM1", "CHANGE_RESOURCE".equals(change) ? "ROOM2" : "ROOM1");
                    }
                });
    }

    private MyAppointmentVO create(
            AssertableApplicationContext c) {
        login(LoginUser.consumer("USR1", "customer"));
        owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
        var service = c.getBean(AppointmentService.class);
        var p = service.previewMyAppointment("USR1", preview(1));
        return service.createMyAppointment("USR1", new CreateMyAppointmentReq(p.previewNo(), List.of(), null),
                "CREATE");
    }
    private CheckInReq request(String number, long revision) {
        return new CheckInReq(number, new ReceptionIdentityProof("cr1_" + "c".repeat(43), null, null), revision);
    }
    @Test void activeFenceAloneRejectsCheckIn() {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            sql.update("UPDATE bty_appointment_resource_fence SET active_intent_no='ISOLATED_ACTIVE'");
            assertThatThrownBy(() -> c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                    request(root.appointmentNo(), root.revision()), "ARRIVE")).isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT status FROM bty_appointment", String.class)).isEqualTo("CONFIRMED");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_reception_operation", Integer.class)).isZero();
        });
    }
    @Test void missingFenceOrUnknownCurrentRejectsCheckIn() {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            var req = request(root.appointmentNo(), root.revision()); var service = c.getBean(ReceptionService.class);
            sql.update("UPDATE bty_appointment_current SET operation_no='UNKNOWN'");
            assertThatThrownBy(() -> service.checkIn("E", "S", "A", req, "ARRIVE")).isInstanceOf(BizException.class);
            sql.update("UPDATE bty_appointment_current SET operation_no=NULL");
            sql.update("DELETE FROM bty_appointment_resource_fence");
            assertThatThrownBy(() -> service.checkIn("E", "S", "A", req, "ARRIVE")).isInstanceOf(BizException.class);
        });
    }
    @ParameterizedTest @ValueSource(strings = {"EARLY", "ON_TIME", "LATE", "ENDED"})
    void earlyLateAndEndedBoundaries(String timing) {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            clockAt = start.toInstant().plusSeconds(switch (timing) {
                case "EARLY" -> -1; case "ON_TIME" -> 0; case "LATE" -> 1; default -> 17 * 60;
            });
            var service = c.getBean(ReceptionService.class); var req = request(root.appointmentNo(), root.revision());
            if ("ENDED".equals(timing)) {
                assertThatThrownBy(() -> service.checkIn("E", "S", "A", req, "ARRIVE"))
                        .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.errorCode())
                                .isEqualTo(ErrorCode.BEAUTY_ARRIVAL_REQUIRES_RESCHEDULE));
            } else {
                var result = service.checkIn("E", "S", "A", req, "ARRIVE");
                assertThat(result.timing()).isEqualTo(timing);
                        assertThat(result.checkedInAt().toInstant()).isEqualTo(clockAt);
            }
        });
    }
    @Test void arrivalAndExceptionOutboxFailuresRollBackAllLocalEffects() {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            var reception = c.getBean(ReceptionService.class); failOutbox = true;
            assertThatThrownBy(() -> reception.checkIn("E", "S", "A",
                    request(root.appointmentNo(), root.revision()), "ARRIVE")).hasMessage("reception-outbox-rollback");
            assertThat(sql.queryForObject("SELECT status FROM bty_appointment", String.class)).isEqualTo("CONFIRMED");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_reception_operation", Integer.class)).isZero();
            assertThatThrownBy(() -> reception.coordinateReceptionException("E", "S", "A",
                    new ReceptionExceptionReq(root.appointmentNo(), "LATE", "KEEP_WAITING", "reason", root.revision()),
                    "EX")).hasMessage("reception-outbox-rollback");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_reception_operation", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_action_log WHERE action IN "
                    + "('CHECKED_IN','RECEPTION_EXCEPTION_REQUESTED')", Integer.class)).isZero();
            failOutbox = false;
            assertThat(reception.checkIn("E", "S", "A", request(root.appointmentNo(), root.revision()), "ARRIVE")
                    .status()).isEqualTo("CHECKED_IN");
        });
    }
    @Test void currentScopeAndPermissionAreRecheckedOnSuccessfulReplay() {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            var service = c.getBean(ReceptionService.class); var req = request(root.appointmentNo(), root.revision());
            assertThatThrownBy(() -> service.checkIn("E", "OTHER", "A", req,
                    "ARRIVE")).isInstanceOf(BizException.class);
            service.checkIn("E", "S", "A", req, "ARRIVE"); customerAllowed = false;
            assertThatThrownBy(() -> service.checkIn("E", "S", "A", req, "ARRIVE")).isInstanceOf(BizException.class);
            customerAllowed = true; allowed = false;
            assertThatThrownBy(() -> service.checkIn("E", "S", "A", req, "ARRIVE")).isInstanceOf(BizException.class);
        });
    }
    @Test void replacementRejectsWithoutChangingOldArrangement() {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                    request(root.appointmentNo(), root.revision()), "ARRIVE");
            var coordinator = c.getBean(ReceptionCoordinator.class);
            var assignments = List.of(new AppointmentAssignmentReq(0, "REQ", "R2", "P2"));
            String hold = owner.hold.holdNo(); owner.reject = "REPLACE";
            var rejected = coordinator.reassign("E", "S", "A", root.appointmentNo(), arrived.revision(), assignments,
                    start.plusMinutes(60), "REASSIGN");
            assertThat(rejected.getState()).isEqualTo("REJECTED");
            assertThat(sql.queryForObject("SELECT hold_no FROM bty_appointment", String.class)).isEqualTo(hold);
            assertThat(sql.queryForObject("SELECT arrangement_revision FROM bty_appointment_current",
                    Long.class)).isOne();
            assertThat(c.getBean(AppointmentCoordinator.class)
                    .currentArrangementEvidence(c.getBean(AppointmentPersistence.class).root(root.appointmentNo()))
                    .hold().holdNo()).isEqualTo(hold);
            owner.reject = null;
            assertThat(coordinator.reassign("E", "S", "A", root.appointmentNo(), arrived.revision(), assignments,
                    start.plusMinutes(60), "RETRY").getState()).isEqualTo("SUCCEEDED");
        });
    }
    @Test void replacementUnknownKeepsOriginalCommandAndFence() {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                    request(root.appointmentNo(), root.revision()), "ARRIVE");
            var coordinator = c.getBean(ReceptionCoordinator.class);
            var assignments = List.of(new AppointmentAssignmentReq(0, "REQ", "R2", "P2"));
            String hold = owner.hold.holdNo(); owner.freezeOnly = true;
            var pending = coordinator.reassign("E", "S", "A", root.appointmentNo(), arrived.revision(), assignments,
                    start.plusMinutes(60), "REASSIGN");
            assertThat(pending.getState()).isEqualTo("PENDING");
            assertThat(sql.queryForObject("SELECT hold_no FROM bty_appointment", String.class)).isEqualTo(hold);
            assertThat(sql.queryForObject("SELECT active_intent_no FROM bty_appointment_resource_fence", String.class))
                    .isNotNull();
            assertThat(sql.queryForObject("SELECT operation_no FROM bty_appointment_current", String.class))
                    .isEqualTo(pending.getOperationNo());
            owner.freezeOnly = false;
            var result = coordinator.reassign("E", "S", "A", root.appointmentNo(), arrived.revision(), assignments,
                    start.plusMinutes(60), "REASSIGN");
            assertThat(result.getOperationNo()).isEqualTo(pending.getOperationNo());
            assertThat(result.getState()).isEqualTo("SUCCEEDED"); assertThat(owner.accepts).hasSize(3);
        });
    }

    @Test void consumerRejectsGuessedExpiredAndForeignStoreChallenges() {
        business().run(c -> {
            var root = create(c); var reception = c.getBean(ReceptionService.class);
            login(LoginUser.merchant("A", "operator"));
            var challenge = reception.issueArrivalChallenge("E", "S", "A", "CODE");
            login(LoginUser.consumer("USR1", "customer"));
            var guessed = new MyCheckInReq(root.appointmentNo(), new CheckInProof("mc1_" + "m".repeat(43),
                    "x".repeat(43)), root.revision());
            assertThatThrownBy(() -> reception.checkInMyAppointment("USR1", guessed, "ARRIVE"))
                    .isInstanceOf(BizException.class);
            var req = new MyCheckInReq(root.appointmentNo(), new CheckInProof("mc1_" + "m".repeat(43),
                    challenge.code()),
                    root.revision());
            sql.update("UPDATE bty_arrival_challenge SET store_no='OTHER'");
            assertThatThrownBy(() -> reception.checkInMyAppointment("USR1", req, "ARRIVE"))
                    .isInstanceOf(BizException.class);
            sql.update("UPDATE bty_arrival_challenge SET store_no='S'");
            clockAt = challenge.expiresAt().toInstant();
            assertThatThrownBy(() -> reception.checkInMyAppointment("USR1", req, "ARRIVE"))
                    .isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_reception_operation", Integer.class)).isZero();
        });
    }
    @Test void replacementRejectsPartialOffGridPastDisabledAndExistingOrder() {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                    request(root.appointmentNo(), root.revision()), "ARRIVE");
            var coordinator = c.getBean(ReceptionCoordinator.class);
            var desired = List.of(new AppointmentAssignmentReq(0, "REQ", "R2", "P2"));
            for (var invalid : List.of(start.plusSeconds(1), start.minusDays(2))) {
                assertThatThrownBy(() -> coordinator.reassign("E", "S", "A", root.appointmentNo(),
                        arrived.revision(), desired, invalid, "INVALID")).isInstanceOf(BizException.class);
            }
            assertThatThrownBy(() -> coordinator.reassign("E", "S", "A", root.appointmentNo(), arrived.revision(),
                    List.of(new AppointmentAssignmentReq(1, "REQ", "R2", "P2")), start, "INVALID"))
                    .isInstanceOf(BizException.class);
            try (var instant = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                Instant elapsed = start.plusMinutes(1).toInstant(); instant.when(Instant::now).thenReturn(elapsed);
                assertThatThrownBy(() -> coordinator.reassign("E", "S", "A", root.appointmentNo(),
                        arrived.revision(), desired, null, "IMPLICIT")).isInstanceOf(BizException.class);
            }
            sql.update("UPDATE bty_service_definition SET status='INACTIVE'");
            assertThatThrownBy(() -> coordinator.reassign("E", "S", "A", root.appointmentNo(), arrived.revision(),
                    desired, start, "DISABLED")).isInstanceOf(BizException.class);
            sql.update("UPDATE bty_service_definition SET status='ACTIVE'");
            seed("bty_work_order", "work_order_no", "WO", "store_no", "S", "appointment_no", root.appointmentNo(),
                    "customer_ref", "USR1", "status", "OPEN", "scope_revision", 1, "scope_frozen", false);
            assertThatThrownBy(() -> coordinator.reassign("E", "S", "A", root.appointmentNo(), arrived.revision(),
                    desired, start, "HAS-ORDER")).isInstanceOf(BizException.class);
            assertThat(owner.accepts).hasSize(2);
        });
    }
    @Test void checkInRacesCancelOnIndependentConnections() {
        business().run(c -> {
            var root = create(c);
            var gate = new CountDownLatch(1);
            var started = new CountDownLatch(2);
            try (var executor = Executors.newFixedThreadPool(2); var connection = database.source.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("SELECT id FROM bty_appointment "
                        + "WHERE appointment_no=? FOR UPDATE")) {
                    statement.setString(1, root.appointmentNo()); statement.executeQuery().close();
                }
                var arrive = executor.submit(() -> {
                    login(LoginUser.merchant("A", "operator")); started.countDown(); gate.await();
                    try { c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                            request(root.appointmentNo(), root.revision()), "ARRIVE"); return true; }
                    catch (BizException rejected) { return false; }
                    finally { SecurityContextHolder.clearContext(); }
                });
                var cancel = executor.submit(() -> {
                    login(LoginUser.merchant("B", "operator")); started.countDown(); gate.await();
                    try { c.getBean(AppointmentService.class).cancelAppointment("E", "S", "B", root.appointmentNo(),
                            new CancelAppointmentReq(root.revision(), "cancel"), "CANCEL"); return true; }
                    catch (BizException rejected) { return false; }
                    finally { SecurityContextHolder.clearContext(); }
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue(); gate.countDown(); connection.commit();
                boolean arrived = arrive.get(15, TimeUnit.SECONDS), cancelled = cancel.get(15, TimeUnit.SECONDS);
                assertThat(arrived ^ cancelled).isTrue();
                assertThat(sql.queryForObject("SELECT status FROM bty_appointment", String.class))
                        .isEqualTo(arrived ? "CHECKED_IN" : "CANCELLED");
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
    }

    @Test void replacementBeforeBufferMustAlsoRemainInFuture() {
        sql.update("UPDATE bty_service_definition_version SET buffer_before_min=10,snapshot_hash=?",
                IdempotencyScope.requestHash("SKU",
                        json.writeValueAsString(new PublishServiceDefinitionVersionReq.Duration(17, 10, 4)),
                        json.writeValueAsString(List.of(new PublishServiceDefinitionVersionReq.Requirement(
                                "MAIN", "STAFF", "G", 1, 0, 0, 17, "ANY_QUALIFIED")))));
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                    request(root.appointmentNo(), root.revision()), "ARRIVE");
            var nextGrid = Instant.ofEpochSecond((Instant.now().getEpochSecond() / 300 + 1) * 300)
                    .atOffset(ZoneOffset.UTC);
            assertThatThrownBy(() -> c.getBean(ReceptionCoordinator.class).reassign("E", "S", "A",
                    root.appointmentNo(), arrived.revision(),
                    List.of(new AppointmentAssignmentReq(0, "REQ", "R2", "P2")), nextGrid, "BUFFER"))
                    .isInstanceOf(BizException.class);
            assertThat(owner.accepts).hasSize(2);
            assertThat(sql.queryForObject("SELECT arrangement_revision FROM bty_appointment_current", Long.class))
                    .isOne();
        });
    }
    @Test void exceptionReportsOnlyActualScopedTasksAndLeavesActiveFenceUntouched() {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            workOrder("W", "S"); workOrder("OTHER-W", "OTHER");
            sql.update("UPDATE bty_work_order SET entity_no='OTHER-E' WHERE work_order_no='OTHER-W'");
            sql.update("UPDATE bty_work_order SET appointment_no=? WHERE tenant_no='MAIN' "
                    + "AND entity_no='E' AND store_no='S' AND work_order_no='W'", root.appointmentNo());
            sql.update("UPDATE bty_work_order SET appointment_no=? WHERE tenant_no='MAIN' "
                    + "AND entity_no='OTHER-E' AND store_no='OTHER' AND work_order_no='OTHER-W'", root.appointmentNo());
            task("TASK", "W"); task("FOREIGN-TASK", "OTHER-W"); task("DELETED-TASK", "W");
            sql.update("UPDATE bty_service_task SET entity_no='OTHER-E' WHERE task_no='FOREIGN-TASK'");
            sql.update("UPDATE bty_service_task SET deleted=1 WHERE task_no='DELETED-TASK'");
            sql.update("UPDATE bty_appointment_resource_fence SET active_intent_no='UNRESOLVED'");
            var req = new ReceptionExceptionReq(root.appointmentNo(), "CONFLICT", "KEEP_WAITING", "private reason",
                    root.revision());
            var service = c.getBean(ReceptionService.class);
            var result = service.coordinateReceptionException("E", "S", "A", req, "EX");
            assertThat(result.affectedTaskNos()).containsExactly("TASK");
            assertThat(service.coordinateReceptionException("E", "S", "A", req, "EX")).isEqualTo(result);
            assertThat(sql.queryForObject("SELECT active_intent_no FROM bty_appointment_resource_fence", String.class))
                    .isEqualTo("UNRESOLVED");
            assertThat(sql.queryForObject("SELECT status FROM bty_appointment", String.class)).isEqualTo("CONFIRMED");
            assertThat(owner.accepts).hasSize(2);
        });
    }

    /** An explicit external-owner fixture. It is never installed as a production provider or admission proof. */
    private final class ControlledOwner implements ResourceBookingPort {
        ResourceSourceAuthorityPort authority;
        final Map<String, Accept> accepts = new LinkedHashMap<>();
        final Map<String, Receipt> receipts = new LinkedHashMap<>();
        final Map<String, ResourceSourceAuthorityPort.Decision> decisions = new LinkedHashMap<>();
        final List<Accept> replayed = new ArrayList<>();
        final AtomicInteger sequence = new AtomicInteger();
        boolean loseAccept; boolean loseExecute; boolean loseQuery; boolean freezeOnly; String reject;
        CountDownLatch entered; CountDownLatch proceed;
        Hold hold;
        String brokenSource;
        String loseExecuteAction;
        final Map<String, Hold> allHolds = new LinkedHashMap<>();
        @Override public Receipt accept(Actor actor, Accept req) {
            outside();
            if (entered != null) {
                entered.countDown();
                try { if (!proceed.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("fixture timeout"); } }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new
                        IllegalStateException(failure); }
            }
            var previous = accepts.putIfAbsent(req.key(), req);
            if (previous != null) { assertThat(req).isEqualTo(previous); replayed.add(req); }
            var receipt = receipts.computeIfAbsent(req.key(), key -> {
                var b = new ResourceSourceAuthorityPort.Binding("MAIN", "E", "S", "CMD" + sequence.incrementAndGet(),
                        req.sourceType(), req.sourceNo(), req.sourceRevision(), req.action(),
                        IdempotencyScope.requestHash(json.writeValueAsString(req)),
                                ResourcePlanContract.hash(req.frozenPayload()));
                return new Receipt(b, "OWNER", "ACCEPTED", 0, req.expectedOwnerVersion(), req.target(),
                        req.frozenPayload(), null, null);
            });
            if (loseAccept) { loseAccept = false; throw new IllegalStateException("controlled lost accept response"); }
            return receipt;
        }
        @Override public Optional<Receipt> query(Actor actor, Scope scope, String command) {
            outside();
            if (loseQuery) { loseQuery = false; throw new IllegalStateException("controlled unavailable query"); }
            return receipts.values().stream().filter(r -> r.binding().commandNo().equals(command)).findFirst();
        }
        @Override public Receipt execute(Actor actor, Scope scope, String command) {
            outside();
            var original = query(actor, scope, command).orElseThrow();
            if (!"ACCEPTED".equals(original.status())) { return original; }
            var b = original.binding();
            var decision = authority.freeze(new ResourceSourceAuthorityPort.Request(b, actor.operatorNo(),
                    actor.realm(), actor.subjectKind()));
            assertThat(decision.state()).isEqualTo(ResourceSourceAuthorityPort.State.AUTHORIZED);
            decisions.put(b.commandNo(), decision);
            if (freezeOnly) { throw new IllegalStateException("controlled pending original command"); }
            boolean rejected = b.action().equals(reject) || !decision.validUntil().isAfter(Instant.now());
            String result;
            if (rejected) { result = "{}"; }
            else {
                String state = "RESERVE".equals(b.action()) ? "HELD" : "RELEASE".equals(b.action()) ? "RELEASED" :
                        "CONFIRMED";
                String no = hold == null || "REPLACE".equals(b.action()) ? "H" + sequence.incrementAndGet() :
                        hold.holdNo();
                hold = new Hold(no, state, hold == null ? 0 : hold.version() + 1, 1, b.sourceRevision(),
                        original.expectedOwnerVersion() + 1, b.planHash());
                allHolds.put(no, hold);
                result = json.writeValueAsString(Map.of("holdNo", no, "status", state, "ownerRevision",
                        hold.ownerVersion()));
            }
            var terminal = new Receipt(b, "OWNER", rejected ? "REJECTED" : "SUCCEEDED", 1,
                    original.expectedOwnerVersion(), original.target(), original.frozenPayload(), result,
                    rejected ? "CAPACITY_UNAVAILABLE" : null);
            receipts.replaceAll((key, value) -> value.binding().equals(b) ? terminal : value);
            if (loseExecute || b.action().equals(loseExecuteAction)) {
                loseExecuteAction = null; loseExecute = false; loseQuery = true; throw new
                    IllegalStateException("controlled lost execute response"); }
            return terminal;
        }
        @Override public Hold queryHold(Actor actor, ResourceSourceAuthorityPort.Binding binding, String no) {
            outside(); assertThat(hold.holdNo()).isEqualTo(no); return hold;
        }
        @Override public Optional<Receipt> reconcile(ResourceSourceAuthorityPort.Binding binding) {
            outside();
            if (binding.sourceNo().equals(brokenSource)) { throw new IllegalStateException("unavailable original"); }
            return receipts.values().stream().filter(r -> r.binding().equals(binding)).findFirst();
        }
        @Override public Hold reconcileHold(ResourceSourceAuthorityPort.Binding binding, String no) {
            outside(); return allHolds.get(no);
        }
    }
}
