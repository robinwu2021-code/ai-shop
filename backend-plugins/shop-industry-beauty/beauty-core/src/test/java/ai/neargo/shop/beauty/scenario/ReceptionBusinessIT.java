package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.beauty.config.BeautyDefinitionConfiguration;
import ai.neargo.shop.beauty.dto.AppointmentAssignmentReq;
import ai.neargo.shop.beauty.dto.CancelAppointmentReq;
import ai.neargo.shop.beauty.dto.CheckInProof;
import ai.neargo.shop.beauty.dto.CheckInReq;
import ai.neargo.shop.beauty.dto.CreateMyAppointmentReq;
import ai.neargo.shop.beauty.dto.MyCheckInReq;
import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionReq;
import ai.neargo.shop.beauty.dto.ReceptionIdentityProof;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.service.ReceptionService;
import ai.neargo.shop.beauty.service.ServiceDefinitionService;
import ai.neargo.shop.beauty.service.impl.AppointmentCoordinator;
import ai.neargo.shop.beauty.service.impl.AppointmentPersistence;
import ai.neargo.shop.beauty.service.impl.ReceptionCoordinator;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;

/** Real private MySQL/source/fence/services. External catalog/personnel/resource owner are explicit controlled
 * fixtures.
 */
class ReceptionBusinessIT extends ReceptionFixture {
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
            // 到店时刻就是这次受理时定的那个（TDD §2.6 第 12 项：重做靠它认出是这次标的）
            assertThat(arrived.checkedInAt()).isEqualTo(java.time.LocalDateTime.parse(json.readTree(
                    sql.queryForObject("SELECT frozen_payload FROM bty_reception_operation", String.class))
                    .get("arriveAt").asText()).atOffset(java.time.ZoneOffset.UTC));
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
    @Test void exceptionReplayIsAtomicWithAction() {
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
            // 申请整笔记在接待操作行上：申请号就是接待操作号，原因在冻结参数里
            assertThat(result.requestNo()).isEqualTo(result.operationNo());
            assertThat(sql.queryForObject("SELECT frozen_payload FROM bty_reception_operation WHERE action="
                    + "'RECEPTION_EXCEPTION'", String.class)).contains("private-reason", "REQUEST_RESCHEDULE");
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
            definition(17, 9, 12, List.of(), "REQ2");
            quotePrice = 5000;
            var changed = assertDoesNotThrow(() -> c.getBean(ReceptionCoordinator.class).reassign("E", "S", "A",
                    root.appointmentNo(), arrived.revision(), List.of(new AppointmentAssignmentReq(0, "REQ",
                            "R2", "P2"),
                            new AppointmentAssignmentReq(1, "REQ", "R2", "P2")), start.plusMinutes(60), "REASSIGN"));
            assertThat(changed.getState()).isEqualTo("SUCCEEDED");
            var fresh = appointments.getAppointment("E", "S", "A", root.appointmentNo());
            assertThat(fresh.items()).allSatisfy(item -> {
                assertThat(item.definitionVersionNo()).isEqualTo("1");
                assertThat(item.durationMin()).isEqualTo(17); assertThat(item.priceMinor()).isEqualTo(1000);
            });
            assertThat(fresh.assignments().getFirst().startAt()).isEqualTo(start.plusMinutes(57));
            assertThat(fresh.assignments().getFirst().endAt()).isEqualTo(start.plusMinutes(81));
            assertThat(sql.queryForObject("SELECT checked_in_at FROM ord_appointment", Timestamp.class)
                    .toInstant()).isEqualTo(arrived.checkedInAt().toInstant());
            assertThat(changed.getAction()).isEqualTo("RECEPTION_CHANGE");
            assertThat(sql.queryForObject("SELECT status FROM ord_appointment", String.class)).isEqualTo("CHECKED_IN");
            assertThat(owner.accepts.values().stream().map(ResourceBookingPort.Accept::action))
                    .containsExactly("RESERVE", "CONFIRM", "REPLACE");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_work_order", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM ord_appointment_item", Integer.class)).isEqualTo(4);
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

    @Test void inFlightChangeAloneRejectsCheckIn() {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            sql.update("UPDATE ord_appointment_revision SET status='APPLYING' WHERE status='CONFIRMED'"); // 有变更在途（在途闸门在修订上，4-4b-2）
            assertThatThrownBy(() -> c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                    request(root.appointmentNo(), root.revision()), "ARRIVE")).isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT status FROM ord_appointment", String.class)).isEqualTo("CONFIRMED");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_reception_operation", Integer.class)).isZero();
        });
    }
    @Test void inFlightChangeOrUnknownCurrentRevisionRejectsCheckIn() {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            var req = request(root.appointmentNo(), root.revision()); var service = c.getBean(ReceptionService.class);
            sql.update("UPDATE ord_appointment_revision SET status='APPLYING' WHERE status='CONFIRMED'");
            assertThatThrownBy(() -> service.checkIn("E", "S", "A", req, "ARRIVE")).isInstanceOf(BizException.class);
            sql.update("UPDATE ord_appointment_revision SET status='CONFIRMED' WHERE status='APPLYING'");
            // 当前生效版本对不上（预约单指向不存在的修订）也拒绝；原「围栏缺失」的情形已不存在
            sql.update("UPDATE ord_appointment SET current_revision=99");
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
    @Test void arrivalAndExceptionAuditFailuresRollBackAllLocalEffects() {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            var reception = c.getBean(ReceptionService.class); failAt = "ARRIVE";
            assertThatThrownBy(() -> reception.checkIn("E", "S", "A",
                    request(root.appointmentNo(), root.revision()), "ARRIVE")).hasMessage("reception-step-rollback");
            assertThat(sql.queryForObject("SELECT status FROM ord_appointment", String.class)).isEqualTo("CONFIRMED");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_reception_operation", Integer.class)).isZero();
            failAt = "EXCEPTION";
            assertThatThrownBy(() -> reception.coordinateReceptionException("E", "S", "A",
                    new ReceptionExceptionReq(root.appointmentNo(), "LATE", "KEEP_WAITING", "reason", root.revision()),
                    "EX")).hasMessage("reception-step-rollback");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_reception_operation", Integer.class)).isZero();
            failAt = null;
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
            assertThat(sql.queryForObject("SELECT hold_no FROM ord_appointment", String.class)).isEqualTo(hold);
            assertThat(sql.queryForObject("SELECT current_revision FROM ord_appointment",
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
            assertThat(sql.queryForObject("SELECT hold_no FROM ord_appointment", String.class)).isEqualTo(hold);
            assertThat(sql.queryForObject("SELECT change_no FROM ord_appointment_revision WHERE applying_key IS NOT NULL",
                    String.class)).isEqualTo(pending.getOperationNo());
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
            sql.update("UPDATE prd_service_definition SET status='INACTIVE'");
            assertThatThrownBy(() -> coordinator.reassign("E", "S", "A", root.appointmentNo(), arrived.revision(),
                    desired, start, "DISABLED")).isInstanceOf(BizException.class);
            sql.update("UPDATE prd_service_definition SET status='ACTIVE'");
            seed("bty_work_order", "work_order_no", "WO", "store_no", "S", "appointment_no", root.appointmentNo(),
                    "customer_ref", "USR1", "status", "OPEN", "scope_revision", 1, "scope_frozen", false);
            // V9.1：开过工单的预约自己记着工单号，预约侧的护栏看的是这一列
            sql.update("UPDATE ord_appointment SET work_order_no='WO' WHERE appointment_no=?", root.appointmentNo());
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
                try (var statement = connection.prepareStatement("SELECT id FROM ord_appointment "
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
                assertThat(sql.queryForObject("SELECT status FROM ord_appointment", String.class))
                        .isEqualTo(arrived ? "CHECKED_IN" : "CANCELLED");
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
    }

    @Test void replacementBeforeBufferMustAlsoRemainInFuture() {
        sql.update("UPDATE prd_service_definition SET buffer_before_min=10");
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
            assertThat(sql.queryForObject("SELECT current_revision FROM ord_appointment", Long.class))
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
            sql.update("UPDATE ord_appointment_revision SET status='APPLYING' WHERE status='CONFIRMED'");
            var req = new ReceptionExceptionReq(root.appointmentNo(), "CONFLICT", "KEEP_WAITING", "private reason",
                    root.revision());
            var service = c.getBean(ReceptionService.class);
            var result = service.coordinateReceptionException("E", "S", "A", req, "EX");
            assertThat(result.affectedTaskNos()).containsExactly("TASK");
            assertThat(service.coordinateReceptionException("E", "S", "A", req, "EX")).isEqualTo(result);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM ord_appointment_revision WHERE status='APPLYING'",
                    Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT status FROM ord_appointment", String.class)).isEqualTo("CONFIRMED");
            assertThat(owner.accepts).hasSize(2);
        });
    }

    /** An explicit external-owner fixture. It is never installed as a production provider or admission proof. */
}
