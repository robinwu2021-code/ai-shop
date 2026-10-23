package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.job.api.JobHandler;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.dto.CancelAppointmentReq;
import ai.neargo.shop.beauty.dto.OpenAppointmentWorkOrderReq;
import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.ReceiveWalkInReq;
import ai.neargo.shop.beauty.dto.WalkInVO;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.service.ReceptionService;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.beauty.service.impl.ReceptionRecovery;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;

/** Native source transactions; external directory and resource owner stay explicitly controlled. */
class WalkInRecoveryIT extends ReceptionFixture {
    ReceiveWalkInReq walkIn() {
        return new ReceiveWalkInReq("S", "cr1_" + "c".repeat(43), null,
                List.of(new AppointmentItemReq("SKU", 1)), List.of(), start);
    }
    @Test void createHeadLockWaitCrossingResourceStartCannotAcceptCommand() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            sql.execute("CREATE TRIGGER fail_root BEFORE INSERT ON bty_appointment FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled root rollback'");
            try {
                assertThatThrownBy(() -> c.getBean(ReceptionService.class)
                        .receiveWalkIn("E", "S", "A", walkIn(), "HEAD-WAIT")).isInstanceOf(RuntimeException.class);
            } finally { sql.execute("DROP TRIGGER fail_root"); }
            var original = sql.queryForMap("SELECT * FROM bty_reception_operation");
            assertThat(original.get("phase")).isEqualTo("RECEIVED");
            clockAt = start.minusMinutes(3).minusSeconds(1).toInstant();
            revokedActor = "A";
            try (var blocker = database.source.getConnection()) {
                blocker.setAutoCommit(false);
                try (var statement = blocker.createStatement()) {
                    try (var row = statement.executeQuery("SELECT definition_no FROM bty_service_definition "
                            + "WHERE definition_no='D' FOR UPDATE")) {
                        assertThat(row.next()).isTrue();
                    }
                }
                try (var pool = Executors.newSingleThreadExecutor()) {
                    var worker = new AtomicReference<Thread>();
                    var recovery = pool.submit(() -> {
                        worker.set(Thread.currentThread());
                        login(LoginUser.merchant("B", "second"));
                        return c.getBean(ServiceOperationRecoveryService.class)
                                .recoverOperation("E", "S", "B", (String) original.get("operation_no"));
                    });
                    try {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        boolean waiting;
                        do {
                            if (recovery.isDone()) {
                                throw new AssertionError("Recovery completed before head release: " + recovery.get());
                            }
                            var trace = worker.get() == null ? List.<StackTraceElement>of()
                                    : List.of(worker.get().getStackTrace());
                            waiting = trace.stream().anyMatch(f -> f.getMethodName().equals("selectScopedForUpdate"))
                                    && trace.stream().anyMatch(f -> f.getClassName().equals(
                                            "ai.neargo.shop.beauty.service.impl.WorkOrderPreparation")
                                            && f.getMethodName().equals("verifyCreateLocked"))
                                    && trace.stream().anyMatch(f -> f.getClassName().equals("sun.nio.ch.Net")
                                            && f.getMethodName().equals("poll"));
                            if (!waiting) { Thread.sleep(10); }
                        } while (!waiting && System.nanoTime() < deadline);
                        assertThat(waiting).as("actual JDBC head SELECT waits behind this transaction").isTrue();
                        clockAt = start.minusMinutes(3).toInstant();
                    } finally { blocker.rollback(); }
                    OperationVO result = null;
                    Throwable recoveryFailure = null;
                    try { result = recovery.get(20, TimeUnit.SECONDS); }
                    catch (ExecutionException failure) { recoveryFailure = failure.getCause(); }
                    assertThat(owner.accepts).isEmpty();
                    assertThat(recoveryFailure).isNull();
                    assertThat(result.state()).isEqualTo("PENDING");
                    assertThat(result.failureCode()).isEqualTo("ARRIVAL_REQUIRES_RESCHEDULE");
                }
            } catch (Exception failure) { throw new AssertionError(failure); }
            for (String table : List.of("bty_appointment", "bty_appointment_resource_fence",
                    "bty_appointment_current", "bty_appointment_preparation", "bty_appointment_operation")) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
            }
            var latest = sql.queryForMap("SELECT * FROM bty_reception_operation");
            for (String field : List.of("operation_no", "actor_no", "key_hash", "request_hash", "frozen_payload",
                    "derived_key_hash", "appointment_no", "appointment_operation_no")) {
                assertThat(latest.get(field)).isEqualTo(original.get(field));
            }
            assertThat(latest.get("phase")).isEqualTo("RECEIVED");
        });
    }
    @Test void openPendingCrossingResourceStartCannotCreateScope() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            sql.execute("CREATE TRIGGER fail_draft BEFORE INSERT ON bty_service_submission FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled scope rollback'");
            try {
                assertThatThrownBy(() -> c.getBean(ReceptionService.class)
                        .receiveWalkIn("E", "S", "A", walkIn(), "CROSS-START")).isInstanceOf(RuntimeException.class);
            } finally { sql.execute("DROP TRIGGER fail_draft"); }
            var original = sql.queryForMap("SELECT * FROM bty_reception_operation");
            assertThat(original.get("phase")).isEqualTo("OPEN_PENDING");
            clockAt = start.minusMinutes(3).minusSeconds(1).toInstant();
            owner.onQueryHold = () -> clockAt = start.minusMinutes(3).toInstant();
            var recovered = c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "A", (String) original.get("operation_no"));
            assertThat(recovered.state()).isEqualTo("PENDING");
            assertThat(recovered.failureCode()).isEqualTo("ARRIVAL_REQUIRES_RESCHEDULE");
            for (String table : List.of("bty_work_order", "bty_work_order_context", "bty_service_task",
                    "bty_service_submission")) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
            }
            var latest = sql.queryForMap("SELECT * FROM bty_reception_operation");
            for (String field : List.of("operation_no", "actor_no", "key_hash", "request_hash", "frozen_payload",
                    "appointment_no", "appointment_operation_no")) {
                assertThat(latest.get(field)).isEqualTo(original.get(field));
            }
            assertThat(latest.get("phase")).isEqualTo("OPEN_PENDING");
            // Ordinary 052 retains its existing before-end rule, independent of the 048 frozen-start contract.
            long revision = sql.queryForObject("SELECT version FROM bty_appointment", Long.class);
            var ordinary = c.getBean(AppointmentService.class).openWorkOrderFromAppointment("E", "S", "A",
                    recovered.appointmentNo(), new OpenAppointmentWorkOrderReq(revision, List.of()), "ORDINARY-052");
            assertThat(ordinary.operationState()).isEqualTo("SUCCEEDED");
        });
    }
    @ParameterizedTest
    @ValueSource(strings = {"bty_service_definition", "bty_store_policy"})
    void changedLocalDefinitionBeforeCreateDoesNotReserveResources(String changedHead) {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            sql.execute("CREATE TRIGGER fail_root BEFORE INSERT ON bty_appointment FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled root rollback'");
            try {
                assertThatThrownBy(() -> c.getBean(ReceptionService.class)
                        .receiveWalkIn("E", "S", "A", walkIn(), "CHANGED-HEAD")).isInstanceOf(RuntimeException.class);
            } finally { sql.execute("DROP TRIGGER fail_root"); }
            var original = sql.queryForMap("SELECT * FROM bty_reception_operation");
            assertThat(original.get("phase")).isEqualTo("RECEIVED");
            sql.update("UPDATE " + changedHead + " SET status='DISABLED',version=version+1");
            assertThatThrownBy(() -> c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "A", (String) original.get("operation_no")))
                    .isInstanceOf(BizException.class);
            assertThat(owner.accepts).isEmpty();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment", Integer.class)).isZero();
            var latest = sql.queryForMap("SELECT * FROM bty_reception_operation");
            assertThat(latest.get("phase")).isEqualTo("RECEIVED");
            assertThat(latest.get("frozen_payload")).isEqualTo(original.get("frozen_payload"));
            assertThat(latest.get("derived_key_hash")).isEqualTo(original.get("derived_key_hash"));
        });
    }
    @Test void slowBatchRetainsPerItemLeaseAndCompletionBackoff() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            owner.freezeOnly = true;
            var service = c.getBean(ReceptionService.class);
            var first = service.receiveWalkIn("E", "S", "A", walkIn(), "SLOW-1");
            var second = service.receiveWalkIn("E", "S", "A", walkIn(), "SLOW-2");
            clockAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(2);
            Instant initial = clockAt;
            List<LocalDateTime> claimedLeases = new ArrayList<>();
            owner.onReconcile = binding -> {
                var lease = sql.queryForObject("SELECT lease_until FROM bty_reception_operation "
                        + "WHERE appointment_no=?", LocalDateTime.class, binding.sourceNo());
                claimedLeases.add(lease);
                clockAt = clockAt.plusSeconds(binding.sourceNo().equals(first.appointmentNo()) ? 31 : 61);
            };
            owner.brokenSource = second.appointmentNo();
            SecurityContextHolder.clearContext();
            assertThat(c.getBean(ReceptionRecovery.class).scan()).isEqualTo(2);
            assertThat(claimedLeases).containsExactly(LocalDateTime.ofInstant(initial.plusSeconds(30), ZoneOffset.UTC),
                    LocalDateTime.ofInstant(initial.plusSeconds(61), ZoneOffset.UTC));
            assertThat(sql.queryForObject("SELECT next_retry_at FROM bty_reception_operation WHERE operation_no=?",
                    LocalDateTime.class, first.operationNo()))
                    .isEqualTo(LocalDateTime.ofInstant(initial.plusSeconds(61), ZoneOffset.UTC));
            assertThat(sql.queryForObject("SELECT next_retry_at FROM bty_reception_operation WHERE operation_no=?",
                    LocalDateTime.class, second.operationNo()))
                    .isEqualTo(LocalDateTime.ofInstant(initial.plusSeconds(152), ZoneOffset.UTC));
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_reception_operation WHERE lease_until IS NOT NULL",
                    Integer.class)).isZero();
        });
    }
    @ParameterizedTest
    @ValueSource(ints = {500, 503})
    void releaseProofPermissionFailureIsNotConvertedToPending(int serverStatus) {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            owner.reject = "CONFIRM";
            var pending = c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", walkIn(), "RELEASE-DENIED");
            long revision = sql.queryForObject("SELECT version FROM bty_appointment", Long.class);
            var cancelled = c.getBean(AppointmentService.class).cancelAppointment("E", "S", "A",
                    pending.appointmentNo(), new CancelAppointmentReq(revision, "CUSTOMER_REQUEST"), "REAL-RELEASE");
            assertThat(cancelled.operationState()).isEqualTo("SUCCEEDED");
            var original = sql.queryForMap("SELECT * FROM bty_reception_operation");
            owner.onQueryHold = () -> { throw BizException.of(ErrorCode.FORBIDDEN); };
            assertThatThrownBy(() -> c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "A", pending.operationNo()))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThat(sql.queryForMap("SELECT * FROM bty_reception_operation")).isEqualTo(original);
            owner.onQueryHold = () -> { throw BizException.of(ErrorCode.PLAN_CAPABILITY_REQUIRED); };
            assertThatThrownBy(() -> c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "A", pending.operationNo()))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PLAN_CAPABILITY_REQUIRED));
            assertThat(sql.queryForMap("SELECT * FROM bty_reception_operation")).isEqualTo(original);
            owner.onQueryHold = () -> { throw new ServiceCallException(CallOutcome.REMOTE_ERROR,
                    "PLATFORM", 403, "controlled forbidden"); };
            assertThatThrownBy(() -> c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "A", pending.operationNo()))
                    .isInstanceOf(ServiceCallException.class);
            assertThat(sql.queryForMap("SELECT * FROM bty_reception_operation")).isEqualTo(original);
            // InternalServiceResponses maps unenumerated capability BizExceptions to an empty HTTP 500.
            owner.onQueryHold = () -> { throw new ServiceCallException(CallOutcome.REMOTE_ERROR,
                    "PLATFORM", serverStatus, "controlled explicit owner failure"); };
            assertThatThrownBy(() -> c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "A", pending.operationNo()))
                    .isInstanceOf(ServiceCallException.class);
            assertThat(sql.queryForMap("SELECT * FROM bty_reception_operation")).isEqualTo(original);
            owner.onQueryHold = () -> { throw new ServiceCallException(CallOutcome.TIMEOUT,
                    "PLATFORM", 0, "controlled timeout"); };
            var unavailable = c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "A", pending.operationNo());
            assertThat(unavailable.state()).isEqualTo("PENDING");
            assertThat(unavailable.failureCode()).isEqualTo("RESOURCE_RELEASE_REQUIRED");
        });
    }
    @Test void confirmedWalkInCreatesOneAppointmentScope() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator"));
            owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            var service = c.getBean(ReceptionService.class);
            WalkInVO result = assertDoesNotThrow(() -> service.receiveWalkIn("E", "S", "A", walkIn(), "WALK"));
            assertThat(result.operationState()).isEqualTo("SUCCEEDED");
            assertThat(result.startAt()).isEqualTo(start);
            assertThat(result.workOrder().status()).isEqualTo("OPEN");
            assertThat(result.workOrder().sourceState()).isEqualTo("UNBOUND");
            assertThat(result.workOrder().taskNos()).hasSize(1);
            for (String table : List.of("bty_appointment", "bty_work_order", "bty_work_order_context",
                    "bty_service_task", "bty_service_submission")) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isOne();
            }
            assertThat(service.receiveWalkIn("E", "S", "A", walkIn(), "WALK")).isEqualTo(result);
            var detail = c.getBean(WorkOrderService.class).getWorkOrder("E", "S", "A",
                    result.workOrder().workOrderNo());
            assertThat(detail.tasks().getFirst().status()).isEqualTo("PENDING");
            assertThat(detail.tasks().getFirst().actualStartedAt()).isNull();
            assertThat(detail.tasks().getFirst().steps()).isEmpty();
            assertThat(detail.tasks().getFirst().participations()).isEmpty();
            String saved = sql.queryForObject("SELECT result FROM bty_reception_operation WHERE operation_no=?",
                    String.class, result.operationNo());
            assertThat(json.readTree(saved).path("workOrder").path("workOrderNo").asString())
                    .isEqualTo(result.workOrder().workOrderNo());
        });
    }
    @Test void unknownAcceptLeavesNoScopeAndReplaysPersistedCommand() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            owner.loseAccept = true;
            var service = c.getBean(ReceptionService.class);
            var pending = assertDoesNotThrow(() -> service.receiveWalkIn("E", "S", "A", walkIn(), "WALK"));
            assertThat(pending.operationState()).isEqualTo("PENDING"); assertThat(pending.workOrder()).isNull();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_work_order", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_service_task", Integer.class)).isZero();
            var original = owner.accepts.values().iterator().next();
            var result = service.receiveWalkIn("E", "S", "A", walkIn(), "WALK");
            assertThat(result.operationState()).isEqualTo("SUCCEEDED");
            assertThat(result.operationNo()).isEqualTo(pending.operationNo());
            assertThat(result.appointmentNo()).isEqualTo(pending.appointmentNo());
            assertThat(owner.replayed).contains(original);
            assertThat(owner.sequence.get()).isEqualTo(3);
        });
    }
    @Test void operationQueryIsPureScopedReadAndSecondEmployeeRecoversOriginal() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            owner.loseAccept = true;
            var pending = c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", walkIn(), "RECOVER");
            String frozen = sql.queryForObject(
                    "SELECT frozen_payload FROM bty_reception_operation WHERE operation_no=?",
                    String.class, pending.operationNo());
            int calls = owner.accepts.size();
            var query = c.getBean(ServiceOperationQueryService.class);
            var state = assertDoesNotThrow(() -> query.getOperation("E", "S", "A", pending.operationNo()));
            assertThat(state.state()).isEqualTo("PENDING"); assertThat(state.action()).isEqualTo("RECEIVE_WALK_IN");
            assertThat(owner.accepts).hasSize(calls);
            revokedActor = "A"; login(LoginUser.merchant("B", "second"));
            var result = assertDoesNotThrow(() -> c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "B", pending.operationNo()));
            assertThat(result.state()).isEqualTo("SUCCEEDED");
            assertThat(result.operationNo()).isEqualTo(pending.operationNo());
            assertThat(sql.queryForObject("SELECT actor_no FROM bty_reception_operation WHERE operation_no=?",
                    String.class, pending.operationNo())).isEqualTo("A");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_work_order", Integer.class)).isOne();
            assertThat(owner.actors).contains("B");
            assertThat(json.readTree(sql.queryForObject(
                    "SELECT frozen_payload FROM bty_reception_operation WHERE operation_no=?", String.class,
                    pending.operationNo())).path("appointmentKey"))
                    .isEqualTo(json.readTree(frozen).path("appointmentKey"));
            assertThatThrownBy(() -> query.getOperation("E", "OTHER", "B", pending.operationNo()))
                    .isInstanceOf(BizException.class);
            allowed = false;
            assertThatThrownBy(() -> query.getOperation("E", "S", "B", pending.operationNo()))
                    .isInstanceOf(BizException.class);
        });
    }
    @Test void reserveRejectionIsFinalButConfirmRejectionRequiresRealRelease() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            owner.reject = "RESERVE";
            var rejected = c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", walkIn(), "REJECT");
            assertThat(rejected.operationState()).isEqualTo("REJECTED");
            assertThat(rejected.recoveryAction()).isNull();
            owner.reject = "CONFIRM";
            var pending = c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", walkIn(), "RELEASE");
            assertThat(pending.operationState()).isEqualTo("PENDING");
            assertThat(pending.recoveryAction()).isEqualTo("AUTHENTICATED_CANCEL");
            assertThat(sql.queryForObject("SELECT hold_no FROM bty_appointment WHERE appointment_no=?", String.class,
                    pending.appointmentNo())).isNotBlank();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_work_order", Integer.class)).isZero();
        });
    }
    @Test void backgroundReconcilesOnlyAcceptedOriginalCommands() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            owner.loseExecuteAction = "CONFIRM";
            var pending = c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", walkIn(), "BACKGROUND");
            assertThat(pending.operationState()).isEqualTo("PENDING");
            int accepted = owner.accepts.size(); int actors = owner.actors.size();
            SecurityContextHolder.clearContext();
            assertThat(c.getBean(ReceptionRecovery.class).scan()).isOne();
            assertThat(sql.queryForObject("SELECT phase FROM bty_reception_operation WHERE operation_no=?",
                    String.class, pending.operationNo())).isEqualTo("ARRIVAL_PENDING");
            assertThat(sql.queryForObject("SELECT checked_in_at FROM bty_appointment WHERE appointment_no=?",
                    String.class, pending.appointmentNo())).isNull();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_work_order", Integer.class)).isZero();
            assertThat(owner.accepts).hasSize(accepted); assertThat(owner.actors).hasSize(actors);
            revokedActor = "A"; login(LoginUser.merchant("B", "second"));
            assertThat(c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "B", pending.operationNo()).state()).isEqualTo("SUCCEEDED");
        });
    }
    @Test void recoverySkipsSixtyFiveUserBlockedRows() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            var service = c.getBean(ReceptionService.class);
            for (int i = 0; i < 65; i++) {
                owner.loseAccept = true;
                service.receiveWalkIn("E", "S", "A", walkIn(), "BLOCKED" + i);
            }
            owner.freezeOnly = true;
            var eligible = service.receiveWalkIn("E", "S", "A", walkIn(), "ELIGIBLE");
            SecurityContextHolder.clearContext();
            assertThat(c.getBean(ReceptionRecovery.class).scan()).isOne();
            assertThat(owner.reconciled).containsExactly(eligible.appointmentNo());
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_work_order", Integer.class)).isZero();
        });
    }
    @Test void resourceRetriesRotateBeyondFirstFifty() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            owner.freezeOnly = true;
            for (int i = 0; i < 60; i++) {
                c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", walkIn(), "ROTATE" + i);
            }
            SecurityContextHolder.clearContext();
            var recovery = c.getBean(ReceptionRecovery.class);
            assertThat(recovery.scan()).isEqualTo(50);
            assertThat(recovery.scan()).isEqualTo(10);
            assertThat(owner.reconciled).hasSize(60).doesNotHaveDuplicates();
            String first = owner.reconciled.getFirst();
            sql.update("UPDATE bty_reception_operation SET next_retry_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND),"
                    + "lease_until=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 5 MINUTE) WHERE appointment_no=?", first);
            assertThat(recovery.scan()).isZero();
            sql.update("UPDATE bty_reception_operation SET lease_until=NULL WHERE appointment_no=?", first);
            owner.brokenSource = first;
            assertThat(recovery.scan()).isOne();
            assertThat(sql.queryForObject("SELECT TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),next_retry_at) "
                    + "FROM bty_reception_operation WHERE appointment_no=?", Integer.class, first)).isBetween(58, 60);

            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_reception_operation WHERE lease_until IS NOT NULL",
                    Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_work_order", Integer.class)).isZero();
        });
    }
    @Test void releasedOriginalHoldClosesRejectedWalkInOnlyAfterVerifiedRelease() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            owner.reject = "CONFIRM";
            var pending = c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", walkIn(), "RELEASE-CLOSE");
            var recovery = c.getBean(ServiceOperationRecoveryService.class);
            assertThat(recovery.recoverOperation("E", "S", "A", pending.operationNo()).state()).isEqualTo("PENDING");
            long revision = sql.queryForObject("SELECT version FROM bty_appointment WHERE appointment_no=?",
                    Long.class, pending.appointmentNo());
            sql.update("UPDATE bty_appointment SET status='CANCELLED' WHERE appointment_no=?", pending.appointmentNo());
            assertThat(recovery.recoverOperation("E", "S", "A", pending.operationNo()).state()).isEqualTo("PENDING");
            sql.update("UPDATE bty_appointment SET status='HELD' WHERE appointment_no=?", pending.appointmentNo());
            owner.reject = null; owner.loseExecuteAction = "RELEASE";
            var cancel = c.getBean(AppointmentService.class).cancelAppointment("E", "S", "A", pending.appointmentNo(),
                    new CancelAppointmentReq(revision, "resource rejected"), "CANCEL-ORIGINAL");
            assertThat(recovery.recoverOperation("E", "S", "A", pending.operationNo()).state()).isEqualTo("PENDING");
            assertThat(recovery.recoverOperation("E", "S", "A", cancel.operationNo()).state()).isEqualTo("SUCCEEDED");
            var h = owner.hold;
            owner.queryHoldOverride = new ResourceBookingPort.Hold("WRONG", h.status(), h.version(), h.leaseRevision(),
                    h.sourceRevision(), h.ownerVersion(), h.planHash());
            assertThat(recovery.recoverOperation("E", "S", "A", pending.operationNo()).state()).isEqualTo("PENDING");
            owner.queryHoldOverride = null;
            int accepts = owner.accepts.size();
            assertThat(recovery.recoverOperation("E", "S", "A", pending.operationNo()).state()).isEqualTo("REJECTED");
            assertThat(owner.accepts).hasSize(accepts);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_work_order", Integer.class)).isZero();
        });
    }
    @Test void openOperationRecoveryRetainsOriginalNamespaceAndCanonical() {
        business().run(c -> {
            var appointment = create(c);
            login(LoginUser.merchant("A", "operator"));
            var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                    request(appointment.appointmentNo(), appointment.revision()), "ARRIVE-C");
            owner.loseAccept = true;
            var pending = c.getBean(AppointmentService.class).openWorkOrderFromAppointment("E", "S", "A",
                    appointment.appointmentNo(), new OpenAppointmentWorkOrderReq(arrived.revision(), List.of(),
                            start.plusHours(1)), "OPEN-C");
            assertThat(pending.operationState()).isEqualTo("PENDING");
            revokedActor = "A"; login(LoginUser.merchant("B", "second"));
            var result = assertDoesNotThrow(() -> c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "B", pending.operationNo()));
            assertThat(result.state()).isEqualTo("SUCCEEDED");
            assertThat(sql.queryForObject("SELECT actor_no FROM bty_reception_operation WHERE operation_no=?",
                    String.class, pending.operationNo())).isEqualTo("A");
            assertThat(owner.actors.getLast()).isEqualTo("B");
        });
    }
    @Test void invalidBindingsCannotOccupyFirstBatch() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            owner.freezeOnly = true;
            var service = c.getBean(ReceptionService.class);
            for (int i = 0; i < 65; i++) { service.receiveWalkIn("E", "S", "A", walkIn(), "INVALID" + i); }
            sql.update("UPDATE bty_appointment_operation SET receipt=JSON_SET(receipt,'$.binding.commandHash',?)",
                    "f".repeat(64));
            var valid = service.receiveWalkIn("E", "S", "A", walkIn(), "VALID");
            SecurityContextHolder.clearContext();
            assertThat(c.getBean(ReceptionRecovery.class).scan()).isOne();
            assertThat(owner.reconciled).containsExactly(valid.appointmentNo());
        });
    }
    @Test void receptionJobUsesApiProfileAndExistingFramework() {
        business().withPropertyValues("spring.profiles.active=api").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBeansOfType(JobHandler.class).values().stream().map(JobHandler::name))
                    .contains("beauty-reception-recovery");
        });
        business().run(c -> assertThat(c.getBeansOfType(JobHandler.class).values().stream().map(JobHandler::name))
                .doesNotContain("beauty-reception-recovery"));
    }
    @ParameterizedTest
    @ValueSource(strings = {"bty_appointment", "bty_appointment_resource_fence", "bty_appointment_current",
            "bty_appointment_preparation", "bty_appointment_operation", "bty_reception_operation"})
    void atomicCreatePreparationRollsBackWithoutHalfLinks(String failedTable) {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            boolean outerLink = "bty_reception_operation".equals(failedTable);
            sql.execute("CREATE TRIGGER fail_preparation BEFORE " + (outerLink ? "UPDATE" : "INSERT")
                    + " ON " + failedTable + " FOR EACH ROW "
                    + (outerLink ? "BEGIN IF NEW.phase='RESOURCE_PENDING' THEN " : "")
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled prepare rollback'"
                    + (outerLink ? "; END IF; END" : ""));
            try {
                assertThatThrownBy(() -> c.getBean(ReceptionService.class)
                        .receiveWalkIn("E", "S", "A", walkIn(), "ATOMIC")).isInstanceOf(RuntimeException.class);
            } finally { sql.execute("DROP TRIGGER fail_preparation"); }
            for (String table : List.of("bty_appointment", "bty_appointment_preparation", "bty_appointment_current",
                    "bty_appointment_resource_fence", "bty_appointment_operation")) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
            }
            assertThat(owner.accepts).isEmpty();
            var original = sql.queryForMap("SELECT operation_no,phase,appointment_no,appointment_operation_no "
                    + "FROM bty_reception_operation");
            assertThat(original.get("phase")).isEqualTo("RECEIVED");
            assertThat(original.get("appointment_no")).isNull();
            assertThat(original.get("appointment_operation_no")).isNull();
            revokedActor = "A"; login(LoginUser.merchant("B", "second"));
            var recovered = c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "B", (String) original.get("operation_no"));
            assertThat(recovered.state()).isEqualTo("SUCCEEDED");
        });
    }
    @Test void finalScopeAndTypedResultRollBackTogetherThenSecondEmployeeOpens() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            sql.execute("CREATE TRIGGER fail_draft BEFORE INSERT ON bty_service_submission FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled scope rollback'");
            try {
                assertThatThrownBy(() -> c.getBean(ReceptionService.class)
                        .receiveWalkIn("E", "S", "A", walkIn(), "SCOPE-ATOMIC")).isInstanceOf(RuntimeException.class);
            } finally { sql.execute("DROP TRIGGER fail_draft"); }
            for (String table : List.of("bty_work_order", "bty_work_order_context", "bty_service_task",
                    "bty_service_submission")) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
            }
            var original = sql.queryForMap("SELECT operation_no,phase,result FROM bty_reception_operation");
            assertThat(original.get("phase")).isEqualTo("OPEN_PENDING"); assertThat(original.get("result")).isNull();
            revokedActor = "A"; login(LoginUser.merchant("B", "second"));
            var result = c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "B", (String) original.get("operation_no"));
            assertThat(result.state()).isEqualTo("SUCCEEDED");
            assertThat(result.workOrderNo()).isNotBlank();
        });
    }
    @Test void defaultStartIncludesEveryBeforeBufferAndOffset() {
        roomQuantities = true; clockAt = Instant.now();
        var duration = new PublishServiceDefinitionVersionReq.Duration(17, 80, 4);
        var rule = new PublishServiceDefinitionVersionReq.Requirement("ROOM", "ROOM", null, 1, 0, 0, 17,
                "ANY_QUALIFIED");
        seed("bty_service_definition", "definition_no", "D2", "store_no", "S", "sku_no", "SKU2",
                "current_definition_version_no", "DV2", "status", "ACTIVE", "version", 1L);
        seed("bty_service_definition_version", "definition_no", "D2", "definition_version_no", "DV2",
                "revision_index", 1, "duration_min_snapshot", 17, "buffer_before_min", 80, "buffer_after_min", 4,
                "steps_template", "[{\"stepCode\":\"SERVICE\",\"sequenceNo\":0}]", "notes", "[]",
                "schema_version", 1, "snapshot_hash", IdempotencyScope.requestHash("SKU2",
                        json.writeValueAsString(duration), json.writeValueAsString(List.of(rule))));
        seed("bty_service_requirement", "requirement_no", "REQ2", "definition_version_no", "DV2", "role_code", "ROOM",
                "resource_type", "ROOM", "qty", 1, "sequence_no", 0, "offset_min", 0, "duration_min", 17,
                "selection_rule", "ANY_QUALIFIED");
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            var req = new ReceiveWalkInReq("S", walkIn().customerRef(), null,
                    List.of(new AppointmentItemReq("SKU", 1), new AppointmentItemReq("SKU2", 1)), List.of(), null);
            var result = c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", req, "DEFAULT");
            // Second service has a larger before buffer; every raw window must still be strictly future.
            var expected = Instant.ofEpochSecond(Math.floorDiv(clockAt.plus(3, ChronoUnit.MINUTES)
                    .getEpochSecond(), 300) * 300 + 300).atOffset(ZoneOffset.UTC);
            assertThat(result.startAt()).isEqualTo(expected);
            assertThat(result.workOrder().taskNos()).hasSize(2);
            owner.accepts.values().forEach(a -> assertThat(ResourcePlanContract.decode(a.frozenPayload()).demands())
                    .allSatisfy(d -> assertThat(d.intervals())
                            .allSatisfy(i -> assertThat(i.startAt()).isAfter(clockAt))));
            var offGrid = new ReceiveWalkInReq("S", walkIn().customerRef(), null, walkIn().items(), List.of(),
                    start.plusSeconds(1));
            assertThatThrownBy(() -> c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", offGrid, "GRID"))
                    .isInstanceOf(BizException.class);
        });
    }
    @Test void expiredSelectedPlanRequiresRescheduleWithoutRematching() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            owner.loseAccept = true;
            var pending = c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", walkIn(), "EXPIRE");
            String before = sql.queryForObject("SELECT frozen_payload FROM bty_reception_operation", String.class);
            clockAt = start.toInstant();
            var result = c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "A", pending.operationNo());
            assertThat(result.state()).isEqualTo("PENDING");
            assertThat(result.failureCode()).isEqualTo("ARRIVAL_REQUIRES_RESCHEDULE");
            assertThat(sql.queryForObject("SELECT frozen_payload FROM bty_reception_operation", String.class))
                    .isEqualTo(before);
            assertThat(owner.accepts).hasSize(1); assertThat(owner.replayed).isEmpty();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_work_order", Integer.class)).isZero();
        });
    }
    @Test void unknownConfirmRestartsWithOriginalCommandAndCurrentEmployee() {
        String[] original = new String[3];
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            owner.loseExecuteAction = "CONFIRM";
            var pending = c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", walkIn(), "RESTART");
            original[0] = pending.operationNo(); original[1] = pending.appointmentNo();
            original[2] = sql.queryForObject("SELECT accept_payload FROM bty_appointment_operation", String.class);
            assertThat(pending.operationState()).isEqualTo("PENDING");
            assertThat(sql.queryForObject("SELECT active_intent_no FROM bty_appointment_resource_fence", String.class))
                    .isNotBlank();
        });
        business().run(c -> {
            revokedActor = "A"; login(LoginUser.merchant("B", "second"));
            owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            int calls = owner.accepts.size();
            var result = c.getBean(ServiceOperationRecoveryService.class).recoverOperation("E", "S", "B", original[0]);
            assertThat(result.state()).isEqualTo("SUCCEEDED");
            assertThat(result.appointmentNo()).isEqualTo(original[1]);
            assertThat(owner.accepts).hasSize(calls);
            assertThat(sql.queryForObject("SELECT accept_payload FROM bty_appointment_operation", String.class))
                    .isEqualTo(original[2]);
        });
    }
    @Test void expiredConfirmedCommandStillReconcilesWithoutArrival() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            owner.loseExecuteAction = "CONFIRM";
            var pending = c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", walkIn(), "EXPIRED-CONFIRM");
            clockAt = start.toInstant();
            var result = c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "A", pending.operationNo());
            assertThat(result.failureCode()).isEqualTo("ARRIVAL_REQUIRES_RESCHEDULE");
            assertThat(sql.queryForObject("SELECT state FROM bty_appointment_operation", String.class))
                    .isEqualTo("SUCCEEDED");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_work_order", Integer.class)).isZero();
        });
    }
    @Test void innerAppointmentRecoveryCannotBypassCustomerPermission() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            owner.loseAccept = true;
            c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", walkIn(), "INNER");
            String inner = sql.queryForObject("SELECT appointment_operation_no FROM bty_reception_operation",
                    String.class);
            customerAllowed = false;
            assertThatThrownBy(() -> c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "A", inner)).isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThat(owner.replayed).isEmpty();
        });
    }
    @Test void originalReplayChecksRequestNamespaceAndCurrentGrants() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            var service = c.getBean(ReceptionService.class);
            var opened = service.receiveWalkIn("E", "S", "A", walkIn(), "ORIGINAL");
            clockAt = start.plusDays(2).toInstant();
            assertThat(service.receiveWalkIn("E", "S", "A", walkIn(), "ORIGINAL")).isEqualTo(opened);
            var changed = new ReceiveWalkInReq("S", walkIn().customerRef(), null,
                    List.of(new AppointmentItemReq("SKU", 2)), List.of(), start);
            assertThatThrownBy(() -> service.receiveWalkIn("E", "S", "A", changed, "ORIGINAL"))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            customerAllowed = false;
            assertThatThrownBy(() -> service.receiveWalkIn("E", "S", "A", walkIn(), "ORIGINAL"))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            customerAllowed = true;
            sql.update("UPDATE bty_reception_operation SET endpoint='BAPI-052' WHERE operation_no=?",
                    opened.operationNo());
            assertThatThrownBy(() -> c.getBean(WorkOrderService.class).getWorkOrder("E", "S", "A",
                    opened.workOrder().workOrderNo())).isInstanceOf(BizException.class);
        });
    }
    @Test void linkedPreviewNamespaceMustMatchOriginalAppointmentCreator() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            owner.loseAccept = true;
            var pending = c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", walkIn(), "BAD-PREVIEW");
            sql.update("UPDATE bty_appointment_preparation SET actor_no='WRONG'");
            assertThatThrownBy(() -> c.getBean(ServiceOperationRecoveryService.class)
                    .recoverOperation("E", "S", "A", pending.operationNo())).isInstanceOf(BizException.class);
            assertThat(owner.replayed).isEmpty();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_work_order", Integer.class)).isZero();
        });
    }
    @Test void concurrentOriginalKeyCreatesOnlyOnePreparationChain() {
        business().run(c -> {
            owner.authority = c.getBean(ResourceSourceAuthorityPort.class); owner.freezeOnly = true;
            var gate = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var results = List.of(1, 2).stream().map(i -> pool.submit(() -> {
                    login(LoginUser.merchant("A", "operator")); gate.await(10, TimeUnit.SECONDS);
                    return c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", walkIn(), "RACE");
                })).toList();
                gate.countDown();
                var first = results.getFirst().get(20, TimeUnit.SECONDS);
                var second = results.getLast().get(20, TimeUnit.SECONDS);
                assertThat(first.operationNo()).isEqualTo(second.operationNo());
                assertThat(first.appointmentNo()).isEqualTo(second.appointmentNo());
            }
            for (String table : List.of("bty_reception_operation", "bty_appointment", "bty_appointment_preparation",
                    "bty_appointment_operation", "bty_appointment_current", "bty_appointment_resource_fence")) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isOne();
            }
        });
    }
    @Test void backgroundReserveTerminalWaitsForAuthenticatedConfirm() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            owner.loseExecuteAction = "RESERVE";
            c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", walkIn(), "RESERVE-BACKGROUND");
            SecurityContextHolder.clearContext();
            assertThat(c.getBean(ReceptionRecovery.class).scan()).isOne();
            assertThat(owner.accepts).hasSize(1);
            assertThat(sql.queryForObject("SELECT receipt FROM bty_appointment_operation", String.class)).isNull();
            assertThat(sql.queryForObject("SELECT phase FROM bty_reception_operation", String.class))
                    .isEqualTo("RESOURCE_PENDING");
            sql.update("UPDATE bty_reception_operation SET next_retry_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)");
            assertThat(c.getBean(ReceptionRecovery.class).scan()).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_work_order", Integer.class)).isZero();
        });
    }
    @Test void walkInPreservesRepeatedPhysicalResourceUnits() {
        roomQuantities = true;
        var rule = new PublishServiceDefinitionVersionReq.Requirement("MAIN", "ROOM", null, 2, 0, 0, 17,
                "ANY_QUALIFIED");
        sql.update("UPDATE bty_service_requirement SET resource_type='ROOM',skill_goods_no=NULL,qty=2");
        sql.update("UPDATE bty_service_definition_version SET snapshot_hash=?", IdempotencyScope.requestHash("SKU",
                json.writeValueAsString(new PublishServiceDefinitionVersionReq.Duration(17, 3, 4)),
                json.writeValueAsString(List.of(rule))));
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            var result = c.getBean(ReceptionService.class).receiveWalkIn("E", "S", "A", walkIn(), "ROOM-UNITS");
            var detail = c.getBean(WorkOrderService.class).getWorkOrder("E", "S", "A",
                    result.workOrder().workOrderNo());
            assertThat(detail.tasks().getFirst().assignments()).hasSize(2);
            assertThat(detail.tasks().getFirst().assignments().stream().map(a -> a.resourceNo())).containsOnly("ROOM1");
        });
    }
}
