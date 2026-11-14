package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.beauty.config.BeautyDefinitionConfiguration;
import ai.neargo.shop.beauty.dto.AppointmentAssignmentReq;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.dto.CreateMyAppointmentReq;
import ai.neargo.shop.beauty.dto.OpenAppointmentWorkOrderReq;
import ai.neargo.shop.beauty.dto.OpenWorkOrderReq;
import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.beauty.entity.BtyAppointmentOperation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ReceptionIdentity.VerifiedIdentity;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.service.ReceptionService;
import ai.neargo.shop.beauty.service.ServiceDefinitionService;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.beauty.service.impl.AppointmentCoordinator;
import ai.neargo.shop.beauty.service.impl.ReceptionCoordinator;
import ai.neargo.shop.beauty.service.impl.ReceptionPreparation;
import ai.neargo.shop.beauty.service.impl.WorkOrderCoordinator;
import ai.neargo.shop.beauty.service.impl.WorkOrderPreparation;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;

import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;

/** Native local scope/transaction behavior; directory and resource owners are controlled fixtures. */
class WorkOrderScopeIT extends ReceptionFixture {
    @Test
    void openAliasesShareCanonicalIntentAndPreserveOriginalProofReplay() {
        var expired = new AtomicBoolean();
        var resolutions = new AtomicInteger();
        business()
                .withBean(
                        "expiringProof",
                        BeanPostProcessor.class,
                        () ->
                                new BeanPostProcessor() {
                                    @Override
                                    public Object postProcessAfterInitialization(
                                            Object bean, String name) {
                                        if (!(bean instanceof CustomerIdentityPort port)) {
                                            return bean;
                                        }
                                        var observed = spy(port);
                                        doAnswer(
                                                        invocation -> {
                                                            resolutions.incrementAndGet();
                                                            if (expired.get()) {
                                                                throw BizException.of(
                                                                        ErrorCode
                                                                                .MEMBER_CODE_INVALID);
                                                            }
                                                            return invocation.callRealMethod();
                                                        })
                                                .when(observed)
                                                .resolveClaimReference(any(), any());
                                        return observed;
                                    }
                                })
                .run(
                        c -> {
                            var appointment = create(c);
                            login(LoginUser.merchant("A", "operator"));
                            var arrived =
                                    c.getBean(ReceptionService.class)
                                            .checkIn(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    request(
                                                            appointment.appointmentNo(),
                                                            appointment.revision()),
                                                    "ARRIVE");
                            var minimal =
                                    new OpenAppointmentWorkOrderReq(arrived.revision(), List.of());
                            var full =
                                    new OpenWorkOrderReq(
                                            "cr1_" + "c".repeat(43),
                                            "S",
                                            List.of(new AppointmentItemReq("SKU", 1)),
                                            appointment.appointmentNo(),
                                            null,
                                            arrived.revision(),
                                            List.of(),
                                            null);
                            var workOrders = c.getBean(WorkOrderService.class);
                            var appointments = c.getBean(AppointmentService.class);
                            var opened = assertDoesNotThrow(
                                    () -> workOrders.openWorkOrder("E", "S", "A", full, "ALIAS"));
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT endpoint FROM bty_reception_operation"
                                                        + " WHERE operation_no=?",
                                                    String.class,
                                                    opened.operationNo()))
                                    .isEqualTo("BAPI-052");
                            assertThat(
                                            appointments.openWorkOrderFromAppointment(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    appointment.appointmentNo(),
                                                    minimal,
                                                    "ALIAS"))
                                    .isEqualTo(opened);
                            assertThat(workOrders.openWorkOrder("E", "S", "A", full, "ALIAS"))
                                    .isEqualTo(opened);
                            var original =
                                    sql.queryForObject(
                                            "SELECT frozen_payload FROM bty_reception_operation"
                                                + " WHERE operation_no=?",
                                            String.class,
                                            opened.operationNo());
                            assertThat(original).doesNotContain("cr1_", "customerRef", "ALIAS");
                            assertThat(json.readTree(original).get("schemaVersion").asInt())
                                    .isEqualTo(2);
                            assertThat(
                                            json.readTree(original)
                                                    .get("replayEvidence")
                                                    .get("originalEndpoint")
                                                    .asString())
                                    .isEqualTo("BAPI-054");
                            int acceptedProofReads = resolutions.get();
                            assertThat(acceptedProofReads).isPositive();
                            clockAt = receptionClock.instant().plusSeconds(3600);
                            expired.set(true);
                            assertThat(workOrders.openWorkOrder("E", "S", "A", full, "ALIAS"))
                                    .isEqualTo(opened);
                            for (var changed :
                                    List.of(
                                            new OpenWorkOrderReq(
                                                    "cr1_" + "d".repeat(43),
                                                    "S",
                                                    full.items(),
                                                    appointment.appointmentNo(),
                                                    null,
                                                    arrived.revision(),
                                                    List.of(),
                                                    null),
                                            new OpenWorkOrderReq(
                                                    full.customerRef(),
                                                    "S",
                                                    List.of(new AppointmentItemReq("SKU", 2)),
                                                    appointment.appointmentNo(),
                                                    null,
                                                    arrived.revision(),
                                                    List.of(),
                                                    null),
                                            new OpenWorkOrderReq(
                                                    null,
                                                    "S",
                                                    List.of(),
                                                    appointment.appointmentNo(),
                                                    null,
                                                    arrived.revision() + 1,
                                                    List.of(),
                                                    null))) {
                                assertThatThrownBy(
                                                () ->
                                                        workOrders.openWorkOrder(
                                                                "E", "S", "A", changed, "ALIAS"))
                                        .isInstanceOfSatisfying(
                                                BizException.class,
                                                failure ->
                                                        assertThat(failure.errorCode())
                                                                .isEqualTo(ErrorCode.CONFLICT));
                            }
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_reception_operation"
                                                        + " WHERE action='OPEN_WORK_ORDER'",
                                                    Integer.class))
                                    .isEqualTo(1);
                            assertThat(count("bty_work_order")).isEqualTo(1);
                            assertThat(resolutions).hasValue(acceptedProofReads);
                        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void openAliasRaceAcceptsEitherWinnerAndLegacyRowsStayOriginal(boolean assertions) {
        business()
                .run(
                        c -> {
                            var appointment = create(c);
                            login(LoginUser.merchant("A", "operator"));
                            var arrived =
                                    c.getBean(ReceptionService.class)
                                            .checkIn(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    request(
                                                            appointment.appointmentNo(),
                                                            appointment.revision()),
                                                    "ARRIVE");
                            var minimal =
                                    new OpenAppointmentWorkOrderReq(arrived.revision(), List.of());
                            var full =
                                    new OpenWorkOrderReq(
                                            assertions ? "cr1_" + "c".repeat(43) : null,
                                            "S",
                                            List.of(),
                                            appointment.appointmentNo(),
                                            null,
                                            arrived.revision(),
                                            List.of(),
                                            null);
                            var gate = new CountDownLatch(1);
                            try (var pool = Executors.newFixedThreadPool(2)) {
                                var first =
                                        pool.submit(
                                                () -> {
                                                    login(LoginUser.merchant("A", "operator"));
                                                    gate.await(10, TimeUnit.SECONDS);
                                                    return c.getBean(AppointmentService.class)
                                                            .openWorkOrderFromAppointment(
                                                                    "E",
                                                                    "S",
                                                                    "A",
                                                                    appointment.appointmentNo(),
                                                                    minimal,
                                                                    "RACE");
                                                });
                                var second =
                                        pool.submit(
                                                () -> {
                                                    login(LoginUser.merchant("A", "operator"));
                                                    gate.await(10, TimeUnit.SECONDS);
                                                    try {
                                                        return c.getBean(WorkOrderService.class)
                                                                .openWorkOrder(
                                                                        "E", "S", "A", full,
                                                                        "RACE");
                                                    } catch (BizException failure) {
                                                        return failure;
                                                    }
                                                });
                                gate.countDown();
                                var opened = first.get(25, TimeUnit.SECONDS);
                                var other = second.get(25, TimeUnit.SECONDS);
                                if (other instanceof BizException failure) {
                                    assertThat(assertions).isTrue();
                                    assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                                    assertThat(
                                                    json.readTree(
                                                                    sql.queryForObject(
                                                                            "SELECT frozen_payload"
                                                                                + " FROM"
                                                                                + " bty_reception_operation"
                                                                                + " WHERE"
                                                                                + " operation_no=?",
                                                                            String.class,
                                                                            opened.operationNo()))
                                                            .get("replayEvidence")
                                                            .get("originalEndpoint")
                                                            .asString())
                                            .isEqualTo("BAPI-052");
                                } else {
                                    assertThat(other).isEqualTo(opened);
                                }
                                assertThat(count("bty_work_order")).isEqualTo(1);
                                assertThat(
                                                sql.queryForObject(
                                                        "SELECT COUNT(*) FROM"
                                                            + " bty_reception_operation WHERE"
                                                            + " action='OPEN_WORK_ORDER'",
                                                        Integer.class))
                                        .isEqualTo(1);
                                assertThat(
                                                sql.queryForObject(
                                                        "SELECT endpoint FROM"
                                                            + " bty_reception_operation WHERE"
                                                            + " operation_no=?",
                                                        String.class,
                                                        opened.operationNo()))
                                        .isEqualTo("BAPI-052");
                                var nonminimal =
                                        new OpenWorkOrderReq(
                                                "cr1_" + "d".repeat(43),
                                                "S",
                                                List.of(),
                                                appointment.appointmentNo(),
                                                null,
                                                arrived.revision(),
                                                List.of(),
                                                null);
                                assertThatThrownBy(
                                                () ->
                                                        c.getBean(WorkOrderService.class)
                                                                .openWorkOrder(
                                                                        "E",
                                                                        "S",
                                                                        "A",
                                                                        nonminimal,
                                                                        "RACE"))
                                        .isInstanceOf(BizException.class);
                                // Exact old namespace/full fingerprint is retained, never
                                // recomputed as canonical052 during recovery.
                                var old =
                                        (ObjectNode)
                                                json.readTree(
                                                        sql.queryForObject(
                                                                "SELECT frozen_payload FROM"
                                                                    + " bty_reception_operation"
                                                                    + " WHERE operation_no=?",
                                                                String.class,
                                                                opened.operationNo()));
                                old.put("schemaVersion", 1);
                                old.remove("replayEvidence");
                                String fullHash =
                                        IdempotencyScope.requestHash(
                                                "BEAUTY_RECEPTION_V1",
                                                "BAPI-054",
                                                "MAIN",
                                                "E",
                                                "S",
                                                "MERCHANT",
                                                "MCH",
                                                "A",
                                                appointment.appointmentNo(),
                                                json.writeValueAsString(full));
                                String derived =
                                        ReceptionPreparation.derived("E", "S", "BAPI-054", "RACE");
                                sql.update(
                                        "UPDATE bty_reception_operation SET"
                                            + " endpoint='BAPI-054',request_hash=?,derived_key_hash=?,frozen_payload=?"
                                            + " WHERE operation_no=?",
                                        fullHash,
                                        derived,
                                        old.toString(),
                                        opened.operationNo());
                                assertThat(
                                                c.getBean(WorkOrderService.class)
                                                        .openWorkOrder("E", "S", "A", full, "RACE"))
                                        .isEqualTo(opened);
                                assertThat(
                                                c.getBean(AppointmentService.class)
                                                        .openWorkOrderFromAppointment(
                                                                "E",
                                                                "S",
                                                                "A",
                                                                appointment.appointmentNo(),
                                                                minimal,
                                                                "RACE"))
                                        .isEqualTo(opened);
                                assertThat(
                                                c.getBean(WorkOrderCoordinator.class)
                                                        .recover(
                                                                "E",
                                                                "S",
                                                                "A",
                                                                opened.operationNo()))
                                        .isEqualTo(opened);
                                assertThat(
                                                sql.queryForObject(
                                                        "SELECT derived_key_hash FROM"
                                                            + " bty_reception_operation WHERE"
                                                            + " operation_no=?",
                                                        String.class,
                                                        opened.operationNo()))
                                        .isEqualTo(derived);
                                var duplicate =
                                        new LinkedHashMap<>(
                                                sql.queryForMap(
                                                        "SELECT * FROM bty_reception_operation"
                                                            + " WHERE operation_no=?",
                                                        opened.operationNo()));
                                duplicate.remove("id");
                                duplicate.put("operation_no", "LEGACY_DUP");
                                duplicate.put("endpoint", "BAPI-052");
                                sql.update(
                                        "INSERT INTO bty_reception_operation ("
                                                + String.join(",", duplicate.keySet())
                                                + ") VALUES ("
                                                + String.join(
                                                        ",",
                                                        Collections.nCopies(duplicate.size(), "?"))
                                                + ")",
                                        duplicate.values().toArray());
                                int accepts = owner.accepts.size();
                                assertThatThrownBy(
                                                () ->
                                                        c.getBean(WorkOrderService.class)
                                                                .openWorkOrder(
                                                                        "E", "S", "A", full,
                                                                        "RACE"))
                                        .isInstanceOf(BizException.class);
                                assertThat(count("bty_work_order")).isEqualTo(1);
                                assertThat(owner.accepts).hasSize(accepts);
                            }
                        });
    }

    @Test void originalKeyReplaysAndDifferentKeysCreateOneScope() {
        business().run(c -> {
            var appointment = create(c);
            login(LoginUser.merchant("A", "operator"));
            var arrival = c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                    request(appointment.appointmentNo(), appointment.revision()), "ARRIVE");
            var req = new OpenAppointmentWorkOrderReq(arrival.revision(), List.of());
            var service = c.getBean(AppointmentService.class);
            var opened = assertDoesNotThrow(() -> service.openWorkOrderFromAppointment("E", "S", "A",
                    appointment.appointmentNo(), req, "OPEN"));
            assertThat(opened.status()).isEqualTo("OPEN");
            assertThat(opened.scopeRevision()).isEqualTo(1);
            assertThat(opened.sourceState()).isEqualTo("UNBOUND");
            assertThat(opened.taskNos()).hasSize(1);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_work_order", Integer.class)).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_service_submission WHERE action='OPEN' "
                    + "AND status='RECEIVED' AND next_retry_at IS NULL", Integer.class)).isEqualTo(1);
            assertThat(service.openWorkOrderFromAppointment("E", "S", "A", appointment.appointmentNo(), req,
                    "OPEN")).isEqualTo(opened);
        });
    }

    @Test void concurrentDifferentKeysOnlyOneScopeAndSameKeyReplaysOldRevision() {
        business().run(c -> {
            var appointment = create(c);
            login(LoginUser.merchant("A", "operator"));
            var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                    request(appointment.appointmentNo(), appointment.revision()), "ARRIVE");
            var req = new OpenAppointmentWorkOrderReq(arrived.revision(), List.of());
            var startGate = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var outcomes = List.of("OPEN1", "OPEN2").stream().map(key -> pool.submit(() -> {
                    login(LoginUser.merchant("A", "operator")); startGate.await(10, TimeUnit.SECONDS);
                    try { return c.getBean(AppointmentService.class).openWorkOrderFromAppointment("E", "S", "A",
                            appointment.appointmentNo(), req, key); }
                    catch (BizException rejected) { return rejected; }
                })).toList();
                startGate.countDown();
                var results = new ArrayList<Object>();
                for (var outcome : outcomes) { results.add(outcome.get(20, TimeUnit.SECONDS)); }
                assertThat(results.stream().filter(WorkOrderVO.class::isInstance)).hasSize(1);
                assertThat(results.stream().filter(BizException.class::isInstance)).hasSize(1);
                var opened = (WorkOrderVO) results.stream().filter(WorkOrderVO.class::isInstance).findFirst().orElseThrow();
                String winner = sql.queryForObject("SELECT key_hash FROM bty_reception_operation WHERE phase='SUCCEEDED' "
                        + "AND action='OPEN_WORK_ORDER'", String.class);
                String key = IdempotencyScope.requestHash("OPEN1").equals(winner) ? "OPEN1" : "OPEN2";
                assertThat(c.getBean(AppointmentService.class).openWorkOrderFromAppointment("E", "S", "A",
                        appointment.appointmentNo(), req, key)).isEqualTo(opened);
                assertThat(count("bty_work_order")).isEqualTo(1);
                assertThat(count("bty_work_order_context")).isEqualTo(1);
                assertThat(count("bty_service_submission")).isEqualTo(1);
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_action_log WHERE action='WORK_ORDER_OPENED'",
                        Integer.class)).isEqualTo(1);
            }
        });
    }
    @Test void replayChecksFullRequestAndCurrentPermissionBeforeFrozenSuccess() {
        business().run(c -> {
            var appointment = create(c);
            login(LoginUser.merchant("A", "operator"));
            var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                    request(appointment.appointmentNo(), appointment.revision()), "ARRIVE");
            var service = c.getBean(WorkOrderService.class);
            var req = new OpenWorkOrderReq("cr1_" + "c".repeat(43), "S", List.of(), appointment.appointmentNo(),
                    null, arrived.revision(), List.of(), null);
            var opened = assertDoesNotThrow(() -> service.openWorkOrder("E", "S", "A", req, "OPEN"));
            assertThat(service.openWorkOrder("E", "S", "A", req, "OPEN")).isEqualTo(opened);
            var mutations = List.of(
                    new OpenWorkOrderReq("cr1_" + "d".repeat(43), "S", List.of(), appointment.appointmentNo(), null,
                            arrived.revision(), List.of(), null),
                    new OpenWorkOrderReq(req.customerRef(), "S", List.of(new AppointmentItemReq("SKU", 1)),
                            appointment.appointmentNo(), null, arrived.revision(), List.of(), null),
                    new OpenWorkOrderReq(req.customerRef(), "S", List.of(), appointment.appointmentNo(), null,
                            arrived.revision() + 1, List.of(), null),
                    new OpenWorkOrderReq(req.customerRef(), "S", List.of(), appointment.appointmentNo(), null,
                            arrived.revision(), List.of(), start.plusHours(1)),
                    new OpenWorkOrderReq(req.customerRef(), "S", List.of(), appointment.appointmentNo(), null,
                            arrived.revision(), List.of(new AppointmentAssignmentReq(0, "REQ", "R2", "P2")), null));
            for (var mutation : mutations) {
                assertThatThrownBy(() -> service.openWorkOrder("E", "S", "A", mutation, "OPEN"))
                        .isInstanceOf(BizException.class);
            }
            customerAllowed = false;
            assertThatThrownBy(() -> service.openWorkOrder("E", "S", "A", req, "OPEN"))
                    .isInstanceOf(BizException.class);
            customerAllowed = true; allowed = false;
            assertThatThrownBy(() -> service.openWorkOrder("E", "S", "A", req, "OPEN"))
                    .isInstanceOf(BizException.class);
            assertThat(count("bty_work_order")).isEqualTo(1);
        });
    }
    @Test void requiresCheckedInAndLiveOriginalArrangement() {
        business().run(c -> {
            var appointment = create(c); login(LoginUser.merchant("A", "operator"));
            var service = c.getBean(AppointmentService.class);
            assertThatThrownBy(() -> service.openWorkOrderFromAppointment("E", "S", "A", appointment.appointmentNo(),
                    new OpenAppointmentWorkOrderReq(appointment.revision(), List.of()), "EARLY"))
                    .isInstanceOf(BizException.class);
            var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                    request(appointment.appointmentNo(), appointment.revision()), "ARRIVE");
            var req = new OpenAppointmentWorkOrderReq(arrived.revision(), List.of());
            sql.update("UPDATE bty_appointment SET checked_in_at=NULL");
            assertThatThrownBy(() -> service.openWorkOrderFromAppointment("E", "S", "A", appointment.appointmentNo(),
                    req, "NO-CHECKIN" )).isInstanceOf(BizException.class);
            sql.update("UPDATE bty_appointment SET checked_in_at=?",
                    Timestamp.from(arrived.checkedInAt().toInstant()));
            clockAt = start.plusHours(2).toInstant();
            assertThatThrownBy(() -> service.openWorkOrderFromAppointment("E", "S", "A", appointment.appointmentNo(),
                    req, "EXPIRED")).isInstanceOf(BizException.class);
            clockAt = null;
            sql.update("UPDATE bty_appointment_resource_fence SET active_intent_no='UNKNOWN'");
            assertThatThrownBy(() -> service.openWorkOrderFromAppointment("E", "S", "A", appointment.appointmentNo(),
                    req, "ACTIVE-FENCE")).isInstanceOf(BizException.class);
            sql.update("UPDATE bty_appointment_resource_fence SET active_intent_no=NULL");
            sql.update("UPDATE bty_appointment_current SET operation_no='UNKNOWN'");
            assertThatThrownBy(() -> service.openWorkOrderFromAppointment("E", "S", "A", appointment.appointmentNo(),
                    req, "ACTIVE-CURRENT")).isInstanceOf(BizException.class);
            sql.update("UPDATE bty_appointment_current SET operation_no=NULL");
            sql.update("DELETE FROM bty_appointment_resource_fence");
            assertThatThrownBy(() -> service.openWorkOrderFromAppointment("E", "S", "A", appointment.appointmentNo(),
                    req, "NO-FENCE")).isInstanceOf(BizException.class);
            assertThat(count("bty_work_order")).isZero(); assertThat(count("bty_service_task")).isZero();
        });
    }
    @Test void unassignedOrderHasNoResourceOrExecutableTask() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator"));
            sql.update("DELETE FROM bty_store_policy_version"); sql.update("DELETE FROM bty_store_policy");
            var service = c.getBean(WorkOrderService.class);
            var req = new OpenWorkOrderReq("cr1_" + "c".repeat(43), "S", List.of(new AppointmentItemReq("SKU", 2)),
                    null);
            var opened = assertDoesNotThrow(() -> service.openWorkOrder("E", "S", "A", req, "OPEN"));
            assertThat(opened.taskNos()).hasSize(2);
            assertThat(service.openWorkOrder("E", "S", "A", req, "OPEN")).isEqualTo(opened);
            var detail = service.getWorkOrder("E", "S", "A", opened.workOrderNo());
            assertThat(detail.originalScopeRevision()).isEqualTo(1);
            assertThat(detail.availableActions()).containsExactly("READ");
            assertThat(detail.tasks()).allSatisfy(task -> {
                assertThat(task.status()).isEqualTo("PENDING"); assertThat(task.assignmentState())
                        .isEqualTo("UNASSIGNED");
                assertThat(task.sourceState()).isEqualTo("UNBOUND"); assertThat(task.canStart()).isFalse();
                assertThat(task.bindingRevision()).isZero(); assertThat(task.doneQty()).isZero();
                assertThat(task.voidedQty()).isZero(); assertThat(task.qty()).isEqualByComparingTo("1");
                assertThat(task.uom()).isEqualTo("COUNT"); assertThat(task.actualStartedAt()).isNull();
                assertThat(task.plannedStartAt()).isNull(); assertThat(task.appointmentItemNo()).isNull();
                assertThat(task.assignments()).isEmpty(); assertThat(task.steps()).isEmpty();
                assertThat(task.participations()).isEmpty(); assertThat(task.taskSnapshot().definitionVersionNo())
                        .isEqualTo("DV");
            });
            assertThat(count("bty_appointment")).isZero(); assertThat(count("bty_appointment_assignment")).isZero();
            assertThat(count("bty_service_step")).isZero(); assertThat(count("bty_service_participation")).isZero();
            assertThat(owner.accepts).isEmpty();
            String context = sql.queryForObject("SELECT frozen_scope FROM bty_work_order_context", String.class);
            String draft = sql.queryForObject("SELECT frozen_command FROM bty_service_submission", String.class);
            assertThat(json.readTree(context).get("scopeHash").asText())
                    .isEqualTo(json.readTree(draft).get("scopeHash").asText());
            assertThat(json.readTree(context).get("taskRefs").size()).isEqualTo(2);
            assertThat(context).contains("durationMin", "requirements").doesNotContain(req.customerRef());
            login(LoginUser.consumer("USR1", "customer"));
            assertThat(service.getMyWorkOrder("USR1", opened.workOrderNo()).tasks()).hasSize(2);
        });
    }
    @Test void unassignedPreparationRejectsForeignInactiveAndChangedHeads() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator"));
            var loader = c.getBean(WorkOrderPreparation.class);
            var identity = new VerifiedIdentity("USER", "USR1");
            var items = List.of(new AppointmentItemReq("SKU", 1));
            catalogEntity = "OTHER";
            assertThatThrownBy(() -> loader.unassigned("E", "S", identity, items)).isInstanceOf(BizException.class);
            catalogEntity = "E"; catalogStatus = "INACTIVE";
            assertThatThrownBy(() -> loader.unassigned("E", "S", identity, items)).isInstanceOf(BizException.class);
            catalogStatus = "ACTIVE";
            var prepared = loader.unassigned("E", "S", identity, items);
            sql.update("UPDATE bty_service_definition SET version=version+1");
            assertThatThrownBy(() -> tx(c, () -> { loader.verifyLocked(prepared); return null; }))
                    .isInstanceOf(BizException.class);
            sql.update("UPDATE bty_service_definition SET status='INACTIVE'");
            assertThatThrownBy(() -> loader.unassigned("E", "S", identity, items)).isInstanceOf(BizException.class);
            sql.update("UPDATE bty_service_definition SET status='ACTIVE'");
            sql.update("UPDATE bty_service_definition_version SET definition_no='FOREIGN'");
            assertThatThrownBy(() -> loader.unassigned("E", "S", identity, items)).isInstanceOf(BizException.class);
            sql.update("UPDATE bty_service_definition_version SET definition_no='D'");
            assertThatThrownBy(() -> loader.unassigned("OTHER", "S", identity, items)).isInstanceOf(BizException.class);
            sql.update("UPDATE bty_store_policy_version SET snapshot_hash=?", "e".repeat(64));
            assertThatThrownBy(() -> loader.unassigned("E", "S", identity, items)).isInstanceOf(BizException.class);
            assertThat(owner.accepts).isEmpty(); assertThat(count("bty_work_order")).isZero();
        });
    }
    @Test void replacementAcceptUnknownReusesDurableOperationAfterRestart() {
        runReplacementUnknown("ACCEPT");
    }
    @Test void replacementExecuteUnknownReusesDurableOperationAfterRestart() {
        runReplacementUnknown("EXECUTE");
    }
    private void runReplacementUnknown(String lostResponse) {
        String[] appointment = new String[1]; long[] revision = new long[1]; String[] operation = new String[1];
        business().run(c -> {
            var root = create(c); appointment[0] = root.appointmentNo(); login(LoginUser.merchant("A", "operator"));
            revision[0] = c.getBean(ReceptionService.class).checkIn("E", "S", "A", request(root.appointmentNo(),
                    root.revision()), "ARRIVE").revision();
            owner.loseAccept = "ACCEPT".equals(lostResponse);
            owner.loseExecute = "EXECUTE".equals(lostResponse);
            var pending = c.getBean(AppointmentService.class).openWorkOrderFromAppointment("E", "S", "A",
                    appointment[0],
                    new OpenAppointmentWorkOrderReq(revision[0], List.of(), start.plusHours(1)), "OPEN");
            assertThat(pending.status()).isEqualTo("PENDING"); operation[0] = pending.operationNo();
            assertThat(count("bty_work_order")).isZero(); assertThat(count("bty_service_submission")).isZero();
            assertThat(sql.queryForObject("SELECT appointment_operation_no FROM bty_reception_operation "
                    + "WHERE operation_no=?", String.class, operation[0])).isNotBlank();
        });
        business().run(c -> {
            login(LoginUser.merchant("A", "operator")); owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
            var result = c.getBean(AppointmentService.class).openWorkOrderFromAppointment("E", "S", "A", appointment[0],
                    new OpenAppointmentWorkOrderReq(revision[0], List.of(), start.plusHours(1)), "OPEN");
            assertThat(result.status()).isEqualTo("OPEN"); assertThat(result.operationNo()).isEqualTo(operation[0]);
            assertThat(owner.accepts).hasSize(3);
            assertThat(owner.replayed).hasSize("ACCEPT".equals(lostResponse) ? 1 : 0);
            assertThat(count("bty_work_order")).isEqualTo(1);
            assertThat(result.plannedStartAt()).isEqualTo(start.plusHours(1));
        });
    }
    @Test void openedAppointmentRejectsReceptionReassignment() {
        business().run(c -> {
            var opened = open(c); int calls = owner.accepts.size();
            long revision = sql.queryForObject("SELECT version FROM bty_appointment", Long.class);
            assertThatThrownBy(() -> c.getBean(ReceptionCoordinator.class).reassign("E", "S", "A",
                    opened.appointmentNo(),
                    revision, List.of(), start.plusHours(1), "REASSIGN")).isInstanceOf(BizException.class);
            assertThat(owner.accepts).hasSize(calls); assertThat(count("bty_work_order")).isEqualTo(1);
        });
    }
    @Test void scopedReadsRejectDeletedAndForeignParents() {
        business().run(c -> {
            var opened = open(c); var service = c.getBean(WorkOrderService.class);
            String task = opened.taskNos().getFirst();
            seed("bty_service_step", "step_no", "REAL", "task_no", task, "step_code", "SERVICE", "sequence_no", 0,
                    "status", "PENDING");
            seed("bty_service_step", "entity_no", "OTHER", "step_no", "POISON", "task_no", task,
                    "step_code", "POISON", "sequence_no", 1, "status", "PENDING");
            seed("bty_service_participation", "participation_no", "REAL-P", "task_no", task,
                    "personnel_no", "P1", "assignment_no", "A1", "occupation_no", "O1",
                    "qualification_snapshot", "{}", "role_code", "MAIN", "started_at", T0);
            seed("bty_service_participation", "entity_no", "OTHER", "participation_no", "POISON-P", "task_no", task,
                    "personnel_no", "OTHER-P", "assignment_no", "OTHER-A", "occupation_no", "OTHER-O",
                    "qualification_snapshot", "{}", "role_code", "MAIN", "started_at", T0);
            var detail = service.getWorkOrder("E", "S", "A", opened.workOrderNo());
            assertThat(detail.tasks().getFirst().steps()).extracting(step -> step.stepNo()).containsExactly("REAL");
            assertThat(detail.tasks().getFirst().participations()).extracting(person -> person.participationNo())
                    .containsExactly("REAL-P");
            assertThatThrownBy(() -> service.getWorkOrder("E", "OTHER", "A", opened.workOrderNo()))
                    .isInstanceOf(BizException.class);
            login(LoginUser.consumer("OTHER", "customer"));
            assertThatThrownBy(() -> service.getMyWorkOrder("OTHER", opened.workOrderNo()))
                    .isInstanceOf(BizException.class);
            login(LoginUser.merchant("A", "operator"));
            sql.update("UPDATE bty_work_order SET customer_ref='GUEST:INVALID'");
            assertThatThrownBy(() -> service.getWorkOrder("E", "S", "A", opened.workOrderNo()))
                    .isInstanceOf(BizException.class);
            sql.update("UPDATE bty_work_order SET customer_ref='USER:USR1'");
            sql.update("UPDATE bty_service_task SET deleted=1");
            assertThat(service.getWorkOrder("E", "S", "A", opened.workOrderNo()).tasks()).isEmpty();
            assertThat(c.getBean(BeautyMappers.ServiceStepMapper.class).selectForTask("E", "S", opened.workOrderNo(),
                    task)).isEmpty();
            assertThat(c.getBean(BeautyMappers.ServiceParticipationMapper.class).selectForTask("E", "S",
                    opened.workOrderNo(), task)).isEmpty();
            sql.update("UPDATE bty_service_task SET deleted=0");
            assertThat(c.getBean(BeautyMappers.ServiceStepMapper.class).selectForTask("E", "S", "OTHER-ORDER", task))
                    .isEmpty();
            appointment("OTHER-APT", "S");
            seed("bty_appointment_item", "appointment_item_no", "OTHER-LINE", "appointment_no", "OTHER-APT",
                    "sku_no", "SKU", "definition_version_no", "DV", "qty", 1, "uom", "COUNT", "sequence_no", 0,
                    "execution_snapshot", "{}");
            String originalItem = detail.tasks().getFirst().appointmentItemNo();
            sql.update("UPDATE bty_service_task SET appointment_item_no='OTHER-LINE'");
            assertThat(service.getWorkOrder("E", "S", "A", opened.workOrderNo()).tasks()).isEmpty();
            sql.update("UPDATE bty_service_task SET appointment_item_no=?", originalItem);
            sql.update("UPDATE bty_appointment_item SET deleted=1");
            assertThat(service.getWorkOrder("E", "S", "A", opened.workOrderNo()).tasks()).isEmpty();
            sql.update("UPDATE bty_appointment_item SET deleted=0");
            sql.update("UPDATE bty_work_order SET deleted=1");
            assertThatThrownBy(() -> service.getWorkOrder("E", "S", "A", opened.workOrderNo()))
                    .isInstanceOf(BizException.class);
            login(LoginUser.consumer("USR1", "customer"));
            assertThatThrownBy(() -> service.getMyWorkOrder("USR1", opened.workOrderNo()))
                    .isInstanceOf(BizException.class);
        });
    }
    @Test void legacyContextIsReadOnlyUnknownAndReceiptOwnershipIsScoped() {
        business().run(c -> {
            var opened = open(c); var service = c.getBean(WorkOrderService.class);
            sql.update("UPDATE bty_work_order_context SET identity_kind='GUEST',identity_ref='BTG123'");
            sql.update("UPDATE bty_work_order SET customer_ref='GUEST:BTG123'");
            sql.update("UPDATE bty_reception_operation SET identity_kind='GUEST',identity_ref='BTG123' "
                    + "WHERE action='OPEN_WORK_ORDER'");
            login(LoginUser.consumer("USR1", "customer"));
            assertThatThrownBy(() -> service.getMyWorkOrder("USR1", opened.workOrderNo()))
                    .isInstanceOf(BizException.class);
            seed("bty_guest_claim_receipt", "store_no", "OTHER", "guest_no", "BTG123", "claim_revision", 2,
                    "user_no", "USR1", "owner_receipt_hash", "f".repeat(64), "recorded_at", T0);
            assertThatThrownBy(() -> service.getMyWorkOrder("USR1", opened.workOrderNo()))
                    .isInstanceOf(BizException.class);
            seed("bty_guest_claim_receipt", "store_no", "S", "guest_no", "BTG123", "claim_revision", 2,
                    "user_no", "USR1", "owner_receipt_hash", "f".repeat(64), "recorded_at", T0);
            assertThat(service.getMyWorkOrder("USR1", opened.workOrderNo()).tasks()).hasSize(1);
            assertThat(json.writeValueAsString(service.getMyWorkOrder("USR1", opened.workOrderNo())))
                    .doesNotContain("identityKind", "identityRef", "BTG123", "USR1");
            login(LoginUser.merchant("A", "operator")); sql.update("DELETE FROM bty_work_order_context");
            var legacy = service.getWorkOrder("E", "S", "A", opened.workOrderNo());
            assertThat(legacy.customer().kind()).isEqualTo("UNKNOWN"); assertThat(legacy.customer().reference())
                    .isNull();
            assertThat(legacy.originalScopeRevision()).isZero(); assertThat(legacy.availableActions()).isEmpty();
            assertThat(legacy.workOrder().sourceState()).isEqualTo("UNKNOWN");
            login(LoginUser.consumer("USR1", "customer"));
            assertThatThrownBy(() -> service.getMyWorkOrder("USR1", opened.workOrderNo()))
                    .isInstanceOf(BizException.class);
        });
    }
    @Test void openScopeRollsBackWhenOutboxFails() {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                    request(root.appointmentNo(), root.revision()), "ARRIVE");
            var req = new OpenAppointmentWorkOrderReq(arrived.revision(), List.of());
            failOutbox = true;
            assertThatThrownBy(() -> c.getBean(AppointmentService.class).openWorkOrderFromAppointment("E", "S", "A",
                    root.appointmentNo(), req, "OPEN")).hasMessage("reception-outbox-rollback");
            for (String table : List.of("bty_work_order", "bty_work_order_context", "bty_service_task",
                    "bty_service_submission")) { assertThat(count(table)).isZero(); }
            assertThat(sql.queryForObject("SELECT version FROM bty_appointment", Long.class))
                    .isEqualTo(arrived.revision());
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_action_log WHERE action='WORK_ORDER_OPENED'",
                    Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_reception_operation WHERE action='OPEN_WORK_ORDER' "
                    + "AND phase='SUCCEEDED'", Integer.class)).isZero();
            failOutbox = false;
            assertThat(c.getBean(AppointmentService.class).openWorkOrderFromAppointment("E", "S", "A",
                    root.appointmentNo(), req, "OPEN").status()).isEqualTo("OPEN");
            assertThat(count("bty_work_order")).isEqualTo(1);
        });
    }
    @Test void catalogChangesCannotOverwriteFrozenScope() {
        business().withUserConfiguration(BeautyDefinitionConfiguration.class)
                .withBean(ServiceProductFactsPort.class, () -> (entity, store, sku) -> {
                    outside(); return new ServiceProductFactsPort.Fact(entity, store, sku, "G", 5000, 27, 8L, 1L);
                }).run(c -> {
                    var root = create(c); login(LoginUser.merchant("A", "operator"));
                    var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A", request(root.appointmentNo(),
                            root.revision()), "ARRIVE");
                    var req = new OpenAppointmentWorkOrderReq(arrived.revision(), List.of());
                    failOutbox = true;
                    assertThatThrownBy(() -> c.getBean(AppointmentService.class).openWorkOrderFromAppointment("E", "S",
                            "A", root.appointmentNo(), req, "OPEN")).hasMessage("reception-outbox-rollback");
                    String originalHash = json.readTree(sql.queryForObject("SELECT frozen_payload FROM "
                            + "bty_reception_operation WHERE action='OPEN_WORK_ORDER'", String.class))
                            .get("scopeHash").asText();
                    failOutbox = false;
                    c.getBean(ServiceDefinitionService.class).publishDefinitionVersion("E", "S", "A",
                            new PublishServiceDefinitionVersionReq("SKU", 1L,
                                    new PublishServiceDefinitionVersionReq.Duration(27, 9, 12), List.of(
                                            new PublishServiceDefinitionVersionReq.Requirement("NEW", "STAFF", "G",
                                                    1, 0, 0, 27, "ANY_QUALIFIED")), List.of("new notes")), "PUBLISH");
                    quotePrice = 5000; goodsRevision = 8L;
                    var opened = assertDoesNotThrow(() -> c.getBean(AppointmentService.class)
                            .openWorkOrderFromAppointment("E", "S", "A", root.appointmentNo(), req, "OPEN"));
                    var detail = c.getBean(WorkOrderService.class).getWorkOrder("E", "S", "A", opened.workOrderNo());
                    var frozen = detail.tasks().getFirst().taskSnapshot();
                    assertThat(frozen.definitionVersionNo()).isEqualTo("DV"); assertThat(frozen.durationMin())
                            .isEqualTo(17);
                    assertThat(frozen.bufferBeforeMin()).isEqualTo(3); assertThat(frozen.bufferAfterMin()).isEqualTo(4);
                    assertThat(frozen.notes()).isEmpty(); assertThat(frozen.requirements().getFirst().roleCode())
                            .isEqualTo("MAIN");
                    assertThat(json.readTree(sql.queryForObject("SELECT frozen_scope FROM bty_work_order_context",
                            String.class)).get("scopeHash").asText()).isEqualTo(originalHash);
                });
    }
    @Test void preservesExpandedItemsAndEveryResourceCapacityUnit() {
        roomQuantities = true;
        business().withUserConfiguration(BeautyDefinitionConfiguration.class)
                .withBean(ServiceProductFactsPort.class, () -> (entity, store, sku) -> {
                    outside(); return new ServiceProductFactsPort.Fact(entity, store, sku, "G", 1000, 17, 7L, 0L);
                }).run(c -> {
                    login(LoginUser.merchant("A", "operator"));
                    c.getBean(ServiceDefinitionService.class).publishDefinitionVersion("E", "S", "A",
                            new PublishServiceDefinitionVersionReq("SKU", 1L,
                                    new PublishServiceDefinitionVersionReq.Duration(17, 3, 4), List.of(
                                            new PublishServiceDefinitionVersionReq.Requirement("MAIN", "STAFF", "G",
                                                    1, 0, 0, 17, "ANY_QUALIFIED"),
                                            new PublishServiceDefinitionVersionReq.Requirement("ROOM", "ROOM", null,
                                                    2, 0, 0, 17, "ANY_QUALIFIED"))), "PUBLISH");
                    owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
                    login(LoginUser.consumer("USR1", "customer"));
                    var appointments = c.getBean(AppointmentService.class);
                    var preview = appointments.previewMyAppointment("USR1", preview(2));
                    var root = appointments.createMyAppointment("USR1", new CreateMyAppointmentReq(preview.previewNo(),
                            List.of(), null), "CREATE");
                    login(LoginUser.merchant("A", "operator"));
                    var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A", request(root.appointmentNo(),
                            root.revision()), "ARRIVE");
                    var opened = appointments.openWorkOrderFromAppointment("E", "S", "A", root.appointmentNo(),
                            new OpenAppointmentWorkOrderReq(arrived.revision(), List.of()), "OPEN");
                    var tasks = c.getBean(WorkOrderService.class).getWorkOrder("E", "S", "A",
                            opened.workOrderNo()).tasks();
                    assertThat(tasks).hasSize(2);
                    assertThat(tasks).allSatisfy(task -> {
                        assertThat(task.qty()).isEqualByComparingTo("1"); assertThat(task.assignments()).hasSize(3);
                        assertThat(task.assignments().stream().filter(a -> "ROOM1".equals(a.resourceNo()))).hasSize(2);
                        assertThat(task.taskSnapshot().requirements().stream().filter(r -> "ROOM".equals(r.resourceType()))
                                .findFirst().orElseThrow().qty()).isEqualTo(2);
                    });
                    assertThat(tasks.stream().flatMap(task -> task.assignments().stream())).hasSize(6);
                    assertThat(count("bty_service_task")).isEqualTo(2);
                });
    }
    @Test void unassignedExpandsMultipleDefinitionsAndEnforcesBounds() {
        business().run(c -> {
            login(LoginUser.merchant("A", "operator"));
            seed("bty_service_definition", "definition_no", "D2", "store_no", "S", "sku_no", "SKU2",
                    "current_definition_version_no", "DV2", "status", "ACTIVE", "version", 1);
            String hash = IdempotencyScope.requestHash("SKU2",
                    json.writeValueAsString(new PublishServiceDefinitionVersionReq.Duration(17, 0, 0)),
                    json.writeValueAsString(List.of(new PublishServiceDefinitionVersionReq.Requirement("MAIN", "STAFF",
                            "G", 1, 0, 0, 17, "ANY_QUALIFIED"))));
            seed("bty_service_definition_version", "definition_no", "D2", "definition_version_no", "DV2",
                    "revision_index", 1, "duration_min_snapshot", 17, "buffer_before_min", 0, "buffer_after_min", 0,
                    "steps_template", "[{\"stepCode\":\"SERVICE\",\"sequenceNo\":0}]", "notes", "[]",
                    "schema_version", 1, "snapshot_hash", hash);
            seed("bty_service_requirement", "requirement_no", "REQ2", "definition_version_no", "DV2", "role_code",
                    "MAIN",
                    "resource_type", "STAFF", "skill_goods_no", "G", "qty", 1, "sequence_no", 0,
                    "offset_min", 0, "duration_min", 17, "selection_rule", "ANY_QUALIFIED");
            var items = List.of(new AppointmentItemReq("SKU", 2), new AppointmentItemReq("SKU2", 1));
            var service = c.getBean(WorkOrderService.class);
            var opened = service.openWorkOrder("E", "S", "A", new OpenWorkOrderReq("cr1_" + "c".repeat(43), "S", items,
                    null), "OPEN");
            var tasks = service.getWorkOrder("E", "S", "A", opened.workOrderNo()).tasks();
            assertThat(tasks).extracting(task -> task.definitionVersionNo()).containsExactly("DV", "DV", "DV2");
            assertThat(tasks).extracting(task -> task.taskSnapshot().bufferBeforeMin()).containsExactly(3, 3, 0);
            assertThatThrownBy(() -> new OpenWorkOrderReq("cr1_" + "c".repeat(43), "S",
                    List.of(new AppointmentItemReq("SKU", 10), new AppointmentItemReq("SKU2", 1)), null))
                    .isInstanceOf(BizException.class);
            assertThat(owner.accepts).isEmpty(); assertThat(count("bty_appointment")).isZero();
        });
    }
    @Test void replacementRejectedKeepsOriginalHoldAndCannotOpenOnReplay() {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A", request(root.appointmentNo(),
                    root.revision()), "ARRIVE");
            String hold = sql.queryForObject("SELECT hold_no FROM bty_appointment", String.class);
            owner.reject = "REPLACE";
            var req = new OpenAppointmentWorkOrderReq(arrived.revision(), List.of(), start.plusHours(1));
            var service = c.getBean(AppointmentService.class);
            assertThatThrownBy(() -> service.openWorkOrderFromAppointment("E", "S", "A", root.appointmentNo(), req,
                    "OPEN")).isInstanceOf(BizException.class);
            int accepted = owner.accepts.size();
            assertThatThrownBy(() -> service.openWorkOrderFromAppointment("E", "S", "A", root.appointmentNo(), req,
                    "OPEN")).isInstanceOf(BizException.class);
            assertThat(owner.accepts).hasSize(accepted);
            assertThat(sql.queryForObject("SELECT hold_no FROM bty_appointment", String.class)).isEqualTo(hold);
            assertThat(sql.queryForObject("SELECT version FROM bty_appointment", Long.class))
                    .isEqualTo(arrived.revision());
            assertThat(count("bty_work_order")).isZero(); assertThat(count("bty_service_submission")).isZero();
        });
    }
    @Test void concurrentSameKeyReturnsOneFrozenResult() {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A", request(root.appointmentNo(),
                    root.revision()), "ARRIVE");
            var gate = new CountDownLatch(1);
            var req = new OpenAppointmentWorkOrderReq(arrived.revision(), List.of());
            try (var pool = Executors.newFixedThreadPool(2)) {
                var futures = IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
                    login(LoginUser.merchant("A", "operator")); gate.await(10, TimeUnit.SECONDS);
                    return c.getBean(AppointmentService.class).openWorkOrderFromAppointment("E", "S", "A",
                            root.appointmentNo(), req, "OPEN");
                })).toList();
                gate.countDown();
                var first = assertDoesNotThrow(() -> futures.getFirst().get(20, TimeUnit.SECONDS));
                assertThat(assertDoesNotThrow(() -> futures.getLast().get(20, TimeUnit.SECONDS))).isEqualTo(first);
                assertThat(count("bty_work_order")).isEqualTo(1); assertThat(count("bty_service_submission")).isEqualTo(1);
            }
        });
    }
    @Test void scopedReadRequiresOriginalSucceededOperation() {
        business().run(c -> {
            var opened = open(c); var service = c.getBean(WorkOrderService.class);
            assertThat(service.getWorkOrder("E", "S", "A", opened.workOrderNo()).workOrder().operationState())
                    .isEqualTo("SUCCEEDED");
            sql.update("UPDATE bty_reception_operation SET work_order_no='FOREIGN' WHERE operation_no=?",
                    opened.operationNo());
            assertThatThrownBy(() -> service.getWorkOrder("E", "S", "A", opened.workOrderNo()))
                    .isInstanceOf(BizException.class);
            sql.update("UPDATE bty_reception_operation SET work_order_no=?,phase='OPEN_PENDING' WHERE operation_no=?",
                    opened.workOrderNo(), opened.operationNo());
            assertThatThrownBy(() -> service.getWorkOrder("E", "S", "A", opened.workOrderNo()))
                    .isInstanceOf(BizException.class);
            sql.update("UPDATE bty_reception_operation SET phase='SUCCEEDED' WHERE operation_no=?", opened.operationNo());
            assertThat(service.getWorkOrder("E", "S", "A", opened.workOrderNo()).workOrder().operationNo())
                    .isEqualTo(opened.operationNo());
        });
    }
    @Test void successfulConcurrentReplaySkipsOldRevisionAndDoesNotRegressPhase() {
        runSuccessfulReplayRace("PENDING");
    }
    @Test void successfulReplacementReplayNeverRegressesSucceededPhase() {
        runSuccessfulReplayRace("REPLACEMENT");
    }
    @Test void successfulReplaySurvivesInitialFindAbsence() {
        runSuccessfulReplayRace("ABSENCE");
    }
    @Test void successfulReplayReturnsAlreadySucceededBegin() {
        runSuccessfulReplayRace("BEFORE_BEGIN");
    }
    @Test void unassignedReplayIgnoresChangedPreparationAfterWinnerSucceeds() {
        runSuccessfulReplayRace("UNASSIGNED");
    }
    @Test void successfulOtherKeyDoesNotRescueStalePending() {
        runSuccessfulReplayRace("OTHER_KEY");
    }
    private void runSuccessfulReplayRace(String window) {
        var captured = new CountDownLatch(1); var winnerFinished = new CountDownLatch(1);
        var paused = new AtomicBoolean();
        boolean replacement = "REPLACEMENT".equals(window), unassigned = "UNASSIGNED".equals(window);
        business().withUserConfiguration(BeautyDefinitionConfiguration.class)
                .withBean(ServiceProductFactsPort.class, () -> (entity, store, sku) -> {
                    outside(); return new ServiceProductFactsPort.Fact(entity, store, sku, "G", 1000, 17, 7L, 0L);
                }).withInitializer(application -> application.getBeanFactory().addBeanPostProcessor(
                new BeanPostProcessor() {
                    private void pause() throws InterruptedException {
                        if (Thread.currentThread().getName().equals("task55c-loser")
                                && paused.compareAndSet(false, true)) {
                            captured.countDown();
                            if (!winnerFinished.await(20, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("winner scheduling timeout");
                            }
                        }
                    }
                    @Override public Object postProcessAfterInitialization(Object bean, String name) {
                        if (unassigned && bean instanceof WorkOrderPreparation real) {
                            var controlled = spy(real);
                            doAnswer(invocation -> { pause(); return invocation.callRealMethod(); })
                                    .when(controlled).unassigned(anyString(), anyString(),
                                            any(VerifiedIdentity.class), any());
                            return controlled;
                        }
                        if (replacement && bean instanceof AppointmentCoordinator real) {
                            var controlled = spy(real);
                            doAnswer(invocation -> {
                                var result = (BtyAppointmentOperation) invocation.callRealMethod();
                                if ("RECEPTION_CHANGE".equals(result.getAction())) { pause(); }
                                return result;
                            }).when(controlled).advance(anyString());
                            return controlled;
                        }
                        if (replacement || unassigned || !(bean instanceof ReceptionPreparation real)) { return bean; }
                        var controlled = spy(real);
                        if ("ABSENCE".equals(window)) {
                            doAnswer(invocation -> {
                                var result = invocation.callRealMethod();
                                if ("BAPI-052".equals(invocation.getArgument(3)) && result == null) { pause(); }
                                return result;
                            }).when(controlled).find(anyString(), anyString(), anyString(), anyString(), anyString(),
                                    anyString());
                        } else {
                            doAnswer(invocation -> {
                                if ("BAPI-052".equals(invocation.getArgument(3)) && "BEFORE_BEGIN".equals(window)) {
                                    pause();
                                }
                                var result = invocation.callRealMethod();
                                if ("BAPI-052".equals(invocation.getArgument(3)) && !"BEFORE_BEGIN".equals(window)) {
                                    pause();
                                }
                                return result;
                            }).when(controlled).begin(anyString(), anyString(), anyString(), anyString(), anyString(),
                                    anyString(), any(VerifiedIdentity.class),
                                    any(ReceptionPreparation.ReceptionPayload.class));
                        }
                        return controlled;
                    }
                })).run(c -> {
                    var root = unassigned ? null : create(c); login(LoginUser.merchant("A", "operator"));
                    var arrived = unassigned ? null : c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                            request(root.appointmentNo(), root.revision()), "ARRIVE");
                    var req = unassigned ? null : new OpenAppointmentWorkOrderReq(arrived.revision(), List.of(),
                            replacement ? start.plusHours(1) : start);
                    var unassignedReq = new OpenWorkOrderReq("cr1_" + "c".repeat(43), "S",
                            List.of(new AppointmentItemReq("SKU", 1)), null);
                    Function<String, WorkOrderVO> open = key -> unassigned
                            ? c.getBean(WorkOrderService.class).openWorkOrder("E", "S", "A", unassignedReq, key)
                            : c.getBean(AppointmentService.class).openWorkOrderFromAppointment("E", "S", "A",
                                    root.appointmentNo(), req, key);
                    try (var pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "task55c-loser"))) {
                        var loser = pool.submit(() -> {
                            login(LoginUser.merchant("A", "operator")); return open.apply("OPEN");
                        });
                        assertThat(captured.await(20, TimeUnit.SECONDS)).isTrue();
                        WorkOrderVO winner; String frozen, result;
                        try {
                            winner = open.apply("OTHER_KEY".equals(window) ? "OTHER" : "OPEN");
                            if (unassigned) {
                                c.getBean(ServiceDefinitionService.class).publishDefinitionVersion("E", "S", "A",
                                        new PublishServiceDefinitionVersionReq("SKU", 1L,
                                                new PublishServiceDefinitionVersionReq.Duration(17, 9, 12), List.of(
                                                        new PublishServiceDefinitionVersionReq.Requirement(
                                                                "NEW", "STAFF", "G", 1, 0, 0, 17,
                                                                "ANY_QUALIFIED"))), "PUBLISH");
                            }
                            frozen = sql.queryForObject(
                                "SELECT frozen_payload FROM bty_reception_operation WHERE operation_no=?",
                                    String.class, winner.operationNo());
                            result = sql.queryForObject(
                                    "SELECT result FROM bty_reception_operation WHERE operation_no=?",
                                    String.class, winner.operationNo());
                        } finally { winnerFinished.countDown(); }
                        Object replay;
                        try { replay = loser.get(25, TimeUnit.SECONDS); }
                        catch (ExecutionException failed) { replay = failed.getCause(); }
                        assertThat(sql.queryForObject("SELECT phase FROM bty_reception_operation WHERE operation_no=?",
                                String.class, winner.operationNo())).isEqualTo("SUCCEEDED");
                        assertThat(sql.queryForObject(
                                "SELECT frozen_payload FROM bty_reception_operation WHERE operation_no=?",
                                String.class, winner.operationNo())).isEqualTo(frozen);
                        assertThat(sql.queryForObject(
                                    "SELECT result FROM bty_reception_operation WHERE operation_no=?",
                                String.class, winner.operationNo())).isEqualTo(result);
                        if ("OTHER_KEY".equals(window)) {
                            assertThat(replay).isInstanceOfSatisfying(BizException.class, failed ->
                                    assertThat(failed.errorCode()).isEqualTo(ErrorCode.CONFLICT));
                        }
                        else { assertThat(replay).isEqualTo(winner); }
                        assertThat(c.getBean(WorkOrderService.class).getWorkOrder("E", "S", "A", winner.workOrderNo())
                                .workOrder().operationState()).isEqualTo("SUCCEEDED");
                        login(LoginUser.consumer("USR1", "customer"));
                        assertThat(c.getBean(WorkOrderService.class).getMyWorkOrder("USR1", winner.workOrderNo()))
                                .isNotNull();
                        assertThat(count("bty_work_order")).isEqualTo(1);
                        assertThat(count("bty_service_submission")).isEqualTo(1);
                    }
                });
    }
    @Test void pendingGuestIntentRetainsCanonicalIdentityAfterClaim() {
        var state = new AtomicReference<>("REGISTERED");
        var reads = new AtomicInteger();
        business().withBean(GuestRegistrationPort.class,
                () -> new GuestRegistrationPort() {
                    @Override public GuestFact get(Scope scope, String guest) {
                        outside(); reads.incrementAndGet();
                        assertThat(scope.entityNo()).isEqualTo("E"); assertThat(scope.storeNo()).isEqualTo("S");
                        return new GuestFact(guest, state.get(), "CLAIMED".equals(state.get()) ? "USR1" : null,
                                "CLAIMED".equals(state.get()) ? 2 : 1);
                    }
                    @Override public Registration register(Scope s, RegisterCommand c) { throw new AssertionError(); }
                    @Override public GuestFact verify(Scope s, String g, String t) { throw new AssertionError(); }
                    @Override public Claim claim(Scope s, String g, ClaimCommand c) { throw new AssertionError(); }
                }).run(c -> {
                    var root = create(c); login(LoginUser.merchant("A", "operator"));
                    var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                            request(root.appointmentNo(), root.revision()), "ARRIVE");
                    sql.update("UPDATE bty_appointment SET customer_ref=NULL,guest_ref='GUE_REAL' "
                            + "WHERE appointment_no=?",
                            root.appointmentNo());
                    var req = new OpenAppointmentWorkOrderReq(arrived.revision(), List.of());
                    failOutbox = true;
                    assertThatThrownBy(() -> c.getBean(AppointmentService.class).openWorkOrderFromAppointment("E", "S",
                            "A", root.appointmentNo(), req, "OPEN")).hasMessage("reception-outbox-rollback");
                    assertThat(reads.get()).isEqualTo(1);
                    String payload = sql.queryForObject("SELECT frozen_payload FROM bty_reception_operation "
                            + "WHERE action='OPEN_WORK_ORDER'", String.class);
                    state.set("CLAIMED"); failOutbox = false;
                    var opened = c.getBean(AppointmentService.class).openWorkOrderFromAppointment("E", "S", "A",
                            root.appointmentNo(), req, "OPEN");
                    assertThat(reads.get()).isEqualTo(1);
                    assertThat(sql.queryForObject("SELECT customer_ref FROM bty_work_order", String.class))
                            .isEqualTo("GUEST:GUE_REAL");
                    assertThat(sql.queryForObject(
                                "SELECT frozen_payload FROM bty_reception_operation WHERE operation_no=?",
                            String.class, opened.operationNo())).isEqualTo(payload);
                    assertThat(sql.queryForObject("SELECT guest_ref FROM bty_appointment", String.class))
                            .isEqualTo("GUE_REAL");
                    assertThat(count("bty_service_submission")).isEqualTo(1);
                });
    }
    @Test void appointmentWithOrphanActualTaskRejectsOpening() { runOrphanTaskOpening(false); }
    @Test void fullAppointmentWithOrphanActualTaskRejectsOpening() { runOrphanTaskOpening(true); }
    private void runOrphanTaskOpening(boolean full) {
        business().run(c -> {
            var root = create(c); login(LoginUser.merchant("A", "operator"));
            var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A",
                    request(root.appointmentNo(), root.revision()), "ARRIVE");
            String item = sql.queryForObject("SELECT appointment_item_no FROM bty_appointment_item", String.class);
            sql.update("INSERT INTO bty_service_task(tenant_no,entity_no,created_at,updated_at,task_no,work_order_no,"
                    + "scope_revision,sku_no,definition_version_no,appointment_item_no,qty,done_qty,voided_qty,uom,"
                    + "status,binding_revision,execution_snapshot) VALUES ('MAIN','E',NOW(),NOW(),'ORPHAN','MISSING',"
                    + "1,'SKU','DV',?,1,0,0,'COUNT','PENDING',0,'{}')", item);
            int accepts = owner.accepts.size();
            assertThatThrownBy(() -> {
                if (full) {
                    c.getBean(WorkOrderService.class).openWorkOrder("E", "S", "A", new OpenWorkOrderReq(null,
                            "S", List.of(), root.appointmentNo(), null, arrived.revision(), List.of(), null), "OPEN");
                } else {
                    c.getBean(AppointmentService.class).openWorkOrderFromAppointment("E", "S", "A",
                            root.appointmentNo(), new OpenAppointmentWorkOrderReq(arrived.revision(), List.of()),
                            "OPEN");
                }
            }).isInstanceOfSatisfying(BizException.class,
                    failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(count("bty_service_task")).isEqualTo(1); assertThat(count("bty_work_order")).isZero();
            assertThat(count("bty_work_order_context")).isZero(); assertThat(count("bty_service_submission")).isZero();
            assertThat(owner.accepts).hasSize(accepts);
        });
    }
    private WorkOrderVO open(AssertableApplicationContext c) {
        var root = create(c); login(LoginUser.merchant("A", "operator"));
        var arrived = c.getBean(ReceptionService.class).checkIn("E", "S", "A", request(root.appointmentNo(),
                root.revision()), "ARRIVE");
        return c.getBean(AppointmentService.class).openWorkOrderFromAppointment("E", "S", "A", root.appointmentNo(),
                new OpenAppointmentWorkOrderReq(arrived.revision(), List.of()), "OPEN");
    }
    private int count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
}
