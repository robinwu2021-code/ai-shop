package ai.neargo.shop.beauty.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.beauty.dto.AppointmentAssignmentVO;
import ai.neargo.shop.beauty.dto.WorkOrderRequirementVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskSnapshotVO;
import ai.neargo.shop.beauty.entity.BtyServiceTask;
import ai.neargo.shop.beauty.entity.BtyWorkOrder;
import ai.neargo.shop.beauty.service.ServiceTaskActionService;
import ai.neargo.shop.beauty.service.impl.ServiceTaskExecutionServiceImpl.ExecutionOriginal;
import ai.neargo.shop.beauty.service.impl.ServiceTaskExecutionServiceImpl.ExecutionPlan;
import ai.neargo.shop.beauty.service.impl.ServiceTaskExecutionServiceImpl.StartCommand;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.resource.ResourceBookingExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntent;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Real source projection preserves Native allocation generation and the entire selected appointment
 * group.
 */
class BookingTaskExecutionPlanTest {
    private final String h = "a".repeat(64);
    private final Instant at = Instant.parse("2026-11-10T00:00:00Z");
    private final AppointmentPersistence db =
            new AppointmentPersistence(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    JsonMapper.builder().build(),
                    mock(PlatformTransactionManager.class));
    private final BtyServiceTask task = new BtyServiceTask();
    private final BtyWorkOrder order = new BtyWorkOrder();
    private final ServiceTaskActionService.TaskActionIntentV2 action =
            mock(ServiceTaskActionService.TaskActionIntentV2.class);

    private ExecutionOriginal original() {
        var definition =
                new WorkOrderTaskSnapshotVO(
                        1,
                        "G",
                        "SKU",
                        "DV",
                        h,
                        5,
                        0,
                        0,
                        List.of(
                                new WorkOrderRequirementVO(
                                        "STAFF", "MAIN", "STAFF", "G", 1, 0, 0, 5, "ANY_QUALIFIED"),
                                new WorkOrderRequirementVO(
                                        "ROOM", "ROOM", "ROOM", null, 1, 1, 0, 5, "ANY")),
                        List.of(),
                        List.of());
        var assignments =
                List.of(
                        new AppointmentAssignmentVO(
                                "ITEM",
                                "STAFF",
                                "P",
                                "STAFF_R",
                                at.atOffset(ZoneOffset.UTC),
                                at.plusSeconds(300).atOffset(ZoneOffset.UTC)),
                        new AppointmentAssignmentVO(
                                "ITEM",
                                "ROOM",
                                null,
                                "ROOM_R",
                                at.atOffset(ZoneOffset.UTC),
                                at.plusSeconds(300).atOffset(ZoneOffset.UTC)));
        var taskPlan =
                ResourcePlanContract.encode(
                        List.of(
                                new ResourcePlanContract.Demand(
                                        "STAFF_R",
                                        "ITEM:STAFF:0",
                                        1,
                                        List.of(
                                                new ResourcePlanContract.Interval(
                                                        at, at.plusSeconds(300)))),
                                new ResourcePlanContract.Demand(
                                        "ROOM_R",
                                        "ITEM:ROOM:0",
                                        1,
                                        List.of(
                                                new ResourcePlanContract.Interval(
                                                        at, at.plusSeconds(300))))),
                        List.of(
                                new ResourcePlanContract.Qualification(
                                        "STAFF_R", "ITEM:STAFF:0", "G", "SKU", true),
                                new ResourcePlanContract.Qualification(
                                        "ROOM_R", "ITEM:ROOM:0", null, null, false)));
        task.setAppointmentItemNo("ITEM");
        order.setAppointmentNo("APPOINTMENT");
        task.setExecutionSnapshot(
                db.json.writeValueAsString(
                        new WorkOrderCoordinator.TaskExecutionSnapshot(
                                1,
                                "ASSIGNED",
                                definition,
                                assignments,
                                at.atOffset(ZoneOffset.UTC),
                                at.plusSeconds(300).atOffset(ZoneOffset.UTC))));
        String planHash = ResourcePlanContract.hash(taskPlan);
        var binding =
                new ResourceSourceAuthorityPort.Binding(
                        "MAIN",
                        "E",
                        "S",
                        "CONFIRM",
                        "BEAUTY_APPOINTMENT",
                        "APPOINTMENT",
                        42,
                        "CONFIRM",
                        h,
                        planHash);
        var hold = new ResourceBookingPort.Hold("HOLD", "CONFIRMED", 3, 6, 42, 10, planHash);
        var plan =
                new ExecutionPlan(
                        "BEAUTY_EXECUTION_PLAN_V1",
                        "APPOINTMENT",
                        "ITEM",
                        42,
                        h,
                        new HoldEvidence(binding, hold, 42, h),
                        taskPlan,
                        planHash,
                        IdempotencyScope.requestHash(task.getExecutionSnapshot()),
                        taskPlan,
                        planHash);
        var intent = mock(StartIntent.class);
        when(intent.frozenPlan()).thenReturn(db.json.writeValueAsString(plan));
        var ref =
                new ExecutionReference(
                                "SERVICE_EXECUTION_REFERENCE_V1",
                                new ExecutionScope("MAIN", "E", "S"),
                                ExecutionReference.SourceKind.STORE_ORDER,
                                "MANUAL_EXECUTION_V1",
                                "EXEC",
                                "GRANT",
                                "UNIT",
                                "TASK",
                                "WORK",
                                1,
                                1,
                                h,
                                h,
                                h,
                                h,
                                h,
                                "FENCE",
                                1,
                                h,
                                "BTRC",
                                h,
                                "ACTION",
                                h,
                                h,
                                null)
                        .signed();
        when(action.reference()).thenReturn(ref);
        when(action.initialPlan())
                .thenReturn(
                        new ServiceTaskActionService.InitialPlan(
                                ServiceTaskActionService.PlanKind.BOOKING,
                                "APPOINTMENT",
                                42,
                                h,
                                planHash));
        return new ExecutionOriginal(
                null,
                null,
                new StartCommand("START_MANUAL_V1", null, null, null, intent, null, null),
                intent,
                order,
                task,
                null);
    }

    private ResourceBookingExecutionEvidencePort.Evidence observed(ExecutionOriginal original) {
        var lookup = BookingTaskExecutionPlan.lookup(db, action, original);
        return new ResourceBookingExecutionEvidencePort.Evidence(
                        lookup,
                        "NATIVE_OWNER",
                        8,
                        List.of(
                                new ResourceBookingExecutionEvidencePort.Allocation(
                                        "U1",
                                        "B1",
                                        "STAFF_R",
                                        "ITEM:STAFF:0",
                                        at,
                                        at.plusSeconds(300),
                                        1,
                                        8,
                                        false),
                                new ResourceBookingExecutionEvidencePort.Allocation(
                                        "U2",
                                        "B2",
                                        "ROOM_R",
                                        "ITEM:ROOM:0",
                                        at,
                                        at.plusSeconds(300),
                                        1,
                                        8,
                                        false)),
                        at,
                        null)
                .signed();
    }

    @Test
    void freezesRealAllocationEightInsteadOfArrangementFortyTwoAndIncludesNonStaffGroup() {
        var original = original();
        var result = BookingTaskExecutionPlan.freeze(db, action, original, observed(original));
        assertThat(result.original().allocationRevision()).isEqualTo(8);
        assertThat(result.original().arrangementRevision()).isEqualTo(42);
        assertThat(result.original().leaseRevision()).isEqualTo(6);
        assertThat(result.original().bookingOwnerNo()).isEqualTo("NATIVE_OWNER");
        assertThat(result.groups())
                .extracting(g -> g.sourceLineRef())
                .containsExactly("ITEM:STAFF:0", "ITEM:ROOM:0");
    }

    @Test
    void wrongHoldAndPartialOrConsumedGroupCannotBecomeFrozenStart() {
        var original = original();
        var valid = observed(original);
        var l = valid.lookup();
        var wrong =
                new ResourceBookingExecutionEvidencePort.Lookup(
                        l.reference(),
                        l.binding(),
                        "OTHER_HOLD",
                        l.leaseRevision(),
                        l.appointmentItemNo(),
                        l.arrangementRevision(),
                        l.arrangementHash(),
                        l.wholePlanJson(),
                        l.wholePlanHash());
        assertThatThrownBy(
                        () ->
                                BookingTaskExecutionPlan.freeze(
                                        db,
                                        action,
                                        original,
                                        new ResourceBookingExecutionEvidencePort.Evidence(
                                                        wrong,
                                                        valid.bookingOwnerNo(),
                                                        8,
                                                        valid.allocations(),
                                                        at,
                                                        null)
                                                .signed()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                BookingTaskExecutionPlan.freeze(
                                        db,
                                        action,
                                        original,
                                        new ResourceBookingExecutionEvidencePort.Evidence(
                                                        l,
                                                        valid.bookingOwnerNo(),
                                                        8,
                                                        List.of(valid.allocations().getFirst()),
                                                        at,
                                                        null)
                                                .signed()))
                .isInstanceOf(RuntimeException.class);
        var a = valid.allocations().getFirst();
        var consumed =
                new ResourceBookingExecutionEvidencePort.Allocation(
                        a.unitNo(),
                        a.bucketNo(),
                        a.resourceNo(),
                        a.sourceLineRef(),
                        a.startAt(),
                        a.endAt(),
                        1,
                        8,
                        true);
        assertThatThrownBy(
                        () ->
                                BookingTaskExecutionPlan.freeze(
                                        db,
                                        action,
                                        original,
                                        new ResourceBookingExecutionEvidencePort.Evidence(
                                                        l,
                                                        valid.bookingOwnerNo(),
                                                        8,
                                                        List.of(
                                                                consumed,
                                                                valid.allocations().getLast()),
                                                        at,
                                                        null)
                                                .signed()))
                .isInstanceOf(RuntimeException.class);
    }
}
